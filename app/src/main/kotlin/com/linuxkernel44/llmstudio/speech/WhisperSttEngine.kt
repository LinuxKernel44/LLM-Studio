package com.linuxkernel44.llmstudio.speech

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.k2fsa.sherpa.onnx.FeatureConfig
import com.k2fsa.sherpa.onnx.OfflineModelConfig
import com.k2fsa.sherpa.onnx.OfflineRecognizer
import com.k2fsa.sherpa.onnx.OfflineRecognizerConfig
import com.k2fsa.sherpa.onnx.OfflineWhisperModelConfig
import com.k2fsa.sherpa.onnx.SileroVadModelConfig
import com.k2fsa.sherpa.onnx.Vad
import com.k2fsa.sherpa.onnx.VadModelConfig
import com.linuxkernel44.llmstudio.data.WhisperModelManager
import java.util.Locale
import java.util.concurrent.Executors

/**
 * Local, fully-offline speech-to-text backed by sherpa-onnx running OpenAI Whisper (base,
 * multilingual) - once the model is downloaded (see WhisperModelManager), no audio ever leaves the
 * device. Switchable back to the system [SpeechToTextHelper] (see ChatViewModel's engine switch).
 *
 * Whisper is a non-streaming model: audio is captured first, then transcribed, so there are no live
 * partial results (onPartialResult is never called). One [startListening]..result cycle is a session:
 *   - Push-to-talk: capture from startListening() until stopListening(), then transcribe everything.
 *   - Continuous:   capture and feed a Silero VAD; the first detected speech-then-silence segment
 *                   ends the session automatically (matching how SpeechRecognizer endpoints itself).
 */
class WhisperSttEngine(context: Context, private val callback: SttEngine.Callback) : SttEngine {

    private val appContext = context.applicationContext
    private val mainHandler = Handler(Looper.getMainLooper())
    private val loadExecutor = Executors.newSingleThreadExecutor()

    @Volatile
    private var recognizer: OfflineRecognizer? = null

    @Volatile
    private var vad: Vad? = null

    @Volatile
    private var ready = false

    @Volatile
    private var continuousMode = false

    @Volatile
    private var audioRecord: AudioRecord? = null

    @Volatile
    private var capturing = false

    @Volatile
    private var cancelled = false

    init {
        loadExecutor.execute { loadModels() }
    }

    private fun loadModels() {
        try {
            val enc = WhisperModelManager.encoderFile(appContext)
            val dec = WhisperModelManager.decoderFile(appContext)
            val tok = WhisperModelManager.tokensFile(appContext)
            val vadf = WhisperModelManager.vadFile(appContext)
            Log.d(TAG, "loadModels: encoder=${enc.exists()}(${enc.length()}) decoder=${dec.exists()}(${dec.length()}) " +
                "tokens=${tok.exists()} vad=${vadf.exists()}")
            val config = OfflineRecognizerConfig(
                featConfig = FeatureConfig(sampleRate = SAMPLE_RATE, featureDim = 80),
                modelConfig = OfflineModelConfig(
                    whisper = OfflineWhisperModelConfig(
                        encoder = WhisperModelManager.encoderFile(appContext).absolutePath,
                        decoder = WhisperModelManager.decoderFile(appContext).absolutePath,
                        // Empty = auto-detect the spoken language per utterance. Forcing a language
                        // (e.g. "en") makes Whisper transcribe French audio AS English, producing a
                        // rough English translation. Auto-detect handles FR and EN transparently and
                        // avoids having to rebuild the decoder when the user switches languages.
                        language = "",
                        task = "transcribe",
                        tailPaddings = 1000,
                    ),
                    tokens = WhisperModelManager.tokensFile(appContext).absolutePath,
                    modelType = "whisper",
                    numThreads = 2,
                ),
            )
            recognizer = OfflineRecognizer(assetManager = null, config = config)

            val vadConfig = VadModelConfig(
                sileroVadModelConfig = SileroVadModelConfig(
                    model = WhisperModelManager.vadFile(appContext).absolutePath,
                    threshold = 0.5f,
                    minSilenceDuration = 0.5f,
                    minSpeechDuration = 0.25f,
                    windowSize = VAD_WINDOW,
                ),
                sampleRate = SAMPLE_RATE,
                numThreads = 1,
            )
            vad = Vad(assetManager = null, config = vadConfig)

            ready = true
            Log.d(TAG, "loadModels: recognizer + VAD loaded OK, ready=true")
        } catch (t: Throwable) {
            Log.e(TAG, "loadModels FAILED", t)
            post { callback.onError("Speech-to-text model failed to load: ${t.message}") }
        }
    }

    override fun isReady() = ready

    override fun setRecognitionLocale(locale: Locale) {
        // No-op: the recognizer runs in auto-detect mode (see loadModels), so it transcribes
        // whatever language is actually spoken - the app's language setting doesn't need to be
        // forced onto Whisper, which avoids the "French spoken -> English text" problem.
    }

    override fun setContinuousMode(continuous: Boolean) {
        continuousMode = continuous
    }

    override fun startListening() {
        Log.d(TAG, "startListening: ready=$ready capturing=$capturing continuous=$continuousMode")
        if (!ready) {
            post { callback.onError("Speech-to-text model is still loading…") }
            return
        }
        if (capturing) {
            return
        }
        cancelled = false
        capturing = true
        val continuous = continuousMode
        Thread({ captureLoop(continuous) }, "whisper-capture").start()
    }

    override fun stopListening() {
        // Push-to-talk release: end capture; the capture thread then transcribes what it collected.
        capturing = false
    }

    override fun cancel() {
        cancelled = true
        capturing = false
    }

    override fun destroy() {
        cancel()
        loadExecutor.execute {
            recognizer?.release()
            recognizer = null
            vad?.release()
            vad = null
        }
        loadExecutor.shutdown()
    }

    private fun captureLoop(continuous: Boolean) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        // getMinBufferSize is in bytes; PCM_16BIT = 2 bytes/sample. Keep a generous buffer.
        val bufferSize = if (minBuf > 0) maxOf(minBuf * 2, VAD_WINDOW * 2 * 8) else VAD_WINDOW * 2 * 8
        Log.d(TAG, "captureLoop: continuous=$continuous minBuf=$minBuf bufferSize=$bufferSize")
        val record: AudioRecord
        try {
            record = AudioRecord(
                MediaRecorder.AudioSource.VOICE_RECOGNITION,
                SAMPLE_RATE, CHANNEL, ENCODING, bufferSize
            )
        } catch (t: Throwable) {
            Log.e(TAG, "AudioRecord construction failed", t)
            capturing = false
            post { callback.onError("Microphone unavailable for on-device transcription.") }
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord not initialized (state=${record.state})")
            record.release()
            capturing = false
            post { callback.onError("Microphone unavailable for on-device transcription.") }
            return
        }
        audioRecord = record

        val theVad = vad
        theVad?.reset()
        // Capture as 16-bit PCM (universally supported for AudioRecord input, unlike PCM_FLOAT which
        // some device HALs reject) and convert to float [-1, 1] for sherpa-onnx.
        val window = ShortArray(VAD_WINDOW)
        val collected = ArrayList<FloatArray>() // push-to-talk: all captured audio
        var segment: FloatArray? = null // continuous: the one VAD-detected utterance
        val startedAt = SystemClock.elapsedRealtime()

        try {
            record.startRecording()
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording failed", t)
            record.release()
            audioRecord = null
            capturing = false
            post { callback.onError("Couldn't start the microphone.") }
            return
        }
        Log.d(TAG, "captureLoop: recording started, recordingState=${record.recordingState}")
        post { callback.onListeningStarted() }

        var totalFloats = 0
        var reads = 0
        var negativeReads = 0
        while (capturing && !cancelled) {
            val n = record.read(window, 0, window.size, AudioRecord.READ_BLOCKING)
            if (n <= 0) {
                negativeReads++
                if (n < 0) {
                    Log.e(TAG, "AudioRecord.read error code=$n; aborting")
                    break
                }
                continue
            }
            reads++
            totalFloats += n
            val chunk = FloatArray(n) { window[it] / 32768.0f }
            if (continuous && theVad != null) {
                theVad.acceptWaveform(chunk)
                while (!theVad.empty()) {
                    segment = theVad.front().samples
                    theVad.pop()
                }
                if (segment != null) {
                    break // captured one complete utterance
                }
                if (SystemClock.elapsedRealtime() - startedAt > MAX_LISTEN_MS) {
                    break // safety: nobody spoke
                }
            } else {
                collected.add(chunk)
            }
        }

        capturing = false
        stopAudioRecord()
        Log.d(TAG, "captureLoop ended: reads=$reads badReads=$negativeReads totalFloats=$totalFloats " +
            "segment=${segment?.size ?: -1} cancelled=$cancelled")

        if (cancelled) {
            return // session abandoned - deliver nothing
        }

        val samples = segment ?: flatten(collected)
        if (samples.isEmpty()) {
            Log.w(TAG, "no samples captured -> 'didn't catch that'")
            post { callback.onError("Didn't catch that - please try again.") }
            return
        }
        transcribe(samples)
    }

    private fun transcribe(samples: FloatArray) {
        val rec = recognizer
        if (rec == null) {
            post { callback.onError("Speech-to-text engine is not ready.") }
            return
        }
        try {
            val startMs = SystemClock.elapsedRealtime()
            val stream = rec.createStream()
            stream.acceptWaveform(samples, SAMPLE_RATE)
            rec.decode(stream)
            val text = rec.getResult(stream).text.trim()
            stream.release()
            Log.d(TAG, "transcribe: ${samples.size} samples (${samples.size / SAMPLE_RATE.toFloat()}s) -> " +
                "\"$text\" in ${SystemClock.elapsedRealtime() - startMs}ms")
            if (cancelled) {
                return // session was abandoned while we were transcribing - drop the result
            }
            if (text.isEmpty()) {
                post { callback.onError("Didn't catch that - please try again.") }
            } else {
                post { callback.onFinalResult(text) }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "transcribe failed", t)
            post { callback.onError("Transcription failed - please try again.") }
        }
    }

    private fun stopAudioRecord() {
        val record = audioRecord ?: return
        try {
            record.stop()
        } catch (_: Throwable) {
        }
        try {
            record.release()
        } catch (_: Throwable) {
        }
        audioRecord = null
    }

    private fun flatten(chunks: List<FloatArray>): FloatArray {
        val total = chunks.sumOf { it.size }
        val out = FloatArray(total)
        var offset = 0
        for (c in chunks) {
            c.copyInto(out, offset)
            offset += c.size
        }
        return out
    }

    private fun post(action: () -> Unit) {
        mainHandler.post(action)
    }

    companion object {
        private const val TAG = "WhisperStt"
        private const val SAMPLE_RATE = 16000
        private const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        private const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
        private const val VAD_WINDOW = 512 // Silero VAD's required window size at 16 kHz
        private const val MAX_LISTEN_MS = 12000L // continuous-mode safety cap when no speech occurs
    }
}
