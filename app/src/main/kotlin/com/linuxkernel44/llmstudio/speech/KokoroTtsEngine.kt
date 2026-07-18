package com.linuxkernel44.llmstudio.speech

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import com.k2fsa.sherpa.onnx.GeneratedAudio
import com.k2fsa.sherpa.onnx.OfflineTts
import com.k2fsa.sherpa.onnx.getOfflineTtsConfig
import com.linuxkernel44.llmstudio.data.KokoroModelManager
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * Local neural TTS backed by sherpa-onnx running the Kokoro-en-v0_19 model fully on-device - once
 * the model is downloaded (see KokoroModelManager), no network call is ever made to speak text.
 * English only; French keeps using [TextToSpeechHelper] (see ChatViewModel's engine switch).
 *
 * Both model loading and per-sentence synthesis are CPU-heavy blocking calls, so everything runs on
 * a single background executor. That executor doubling as a work queue also gives [enqueue] the same
 * "play sentences one at a time, in arrival order" behavior [TextToSpeechHelper] gets for free from
 * TextToSpeech's own QUEUE_ADD.
 */
class KokoroTtsEngine(context: Context, private val callback: TtsEngine.Callback) : TtsEngine {

    private val appContext = context.applicationContext
    private val executor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var tts: OfflineTts? = null

    @Volatile
    private var ready = false

    @Volatile
    private var speakerId = 0

    @Volatile
    private var speechRate = 1.0f

    @Volatile
    private var currentTrack: AudioTrack? = null

    // Same batch bookkeeping contract as TextToSpeechHelper.
    @Volatile
    private var batchActive = false

    @Volatile
    private var streamFinished = false

    @Volatile
    private var speakingStarted = false

    @Volatile
    private var stopRequested = false
    private val enqueuedCount = AtomicInteger(0)
    private val completedCount = AtomicInteger(0)

    init {
        executor.execute {
            try {
                val modelDir = KokoroModelManager.modelDir(appContext).absolutePath
                val config = getOfflineTtsConfig(
                    modelDir = modelDir,
                    modelName = "model.onnx",
                    acousticModelName = "",
                    vocoder = "",
                    voices = "voices.bin",
                    lexicon = "",
                    dataDir = "$modelDir/espeak-ng-data",
                    dictDir = "",
                    ruleFsts = "",
                    ruleFars = "",
                )
                tts = OfflineTts(assetManager = null, config = config)
                ready = true
                callback.onTtsReady()
            } catch (t: Throwable) {
                callback.onTtsError("Kokoro voice model failed to load: ${t.message}")
            }
        }
    }

    /** sid index into KokoroModelManager.VOICE_NAMES (0..10 for this model). */
    fun setSpeakerId(id: Int) {
        speakerId = id
    }

    override fun isReady() = ready

    override fun setLanguage(locale: Locale) {
        // Kokoro-en-v0_19 only has English voices; nothing to switch here.
    }

    override fun setSpeechRate(rate: Float) {
        speechRate = rate
    }

    override fun beginBatch() {
        stopPlaybackNow()
        batchActive = true
        streamFinished = false
        speakingStarted = false
        stopRequested = false
        enqueuedCount.set(0)
        completedCount.set(0)
    }

    override fun enqueue(text: String) {
        if (!ready || !batchActive || text.isBlank()) {
            return
        }
        enqueuedCount.incrementAndGet()
        executor.execute { synthesizeAndPlay(text) }
    }

    override fun finishBatch() {
        streamFinished = true
        maybeFireFinished()
    }

    /** Immediately stops playback and abandons the batch WITHOUT firing onSpeechFinished. */
    override fun stop() {
        batchActive = false
        streamFinished = false
        stopRequested = true
        stopPlaybackNow()
    }

    override fun shutdown() {
        stop()
        executor.execute {
            tts?.release()
            tts = null
        }
        executor.shutdown()
    }

    private fun synthesizeAndPlay(text: String) {
        if (stopRequested) {
            completedCount.incrementAndGet()
            return
        }
        val engine = tts
        if (engine == null) {
            completedCount.incrementAndGet()
            maybeFireFinished()
            return
        }
        val audio: GeneratedAudio
        try {
            audio = engine.generate(text = text, sid = speakerId, speed = speechRate)
        } catch (t: Throwable) {
            callback.onTtsError("Kokoro speech synthesis failed.")
            completedCount.incrementAndGet()
            maybeFireFinished()
            return
        }
        if (stopRequested) {
            completedCount.incrementAndGet()
            return
        }
        if (!speakingStarted) {
            speakingStarted = true
            callback.onSpeechStarted()
        }
        playSamples(audio.samples, audio.sampleRate)
        completedCount.incrementAndGet()
        maybeFireFinished()
    }

    /**
     * Plays raw float PCM via a STREAM-mode AudioTrack. write(..., WRITE_BLOCKING) blocks this
     * worker thread until the samples are handed to the audio HAL, which keeps enqueue()'s
     * one-sentence-at-a-time ordering without extra bookkeeping. Calling stop()/release() on the
     * track from another thread (see stopPlaybackNow) makes a blocked write() return early, which
     * is the standard, documented way to interrupt AudioTrack playback for a barge-in.
     */
    private fun playSamples(samples: FloatArray, sampleRate: Int) {
        val minBufferSize = AudioTrack.getMinBufferSize(
            sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT
        )
        val bufferSize = if (minBufferSize > 0) minBufferSize else samples.size * 4
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_ASSISTANT)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(bufferSize)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        currentTrack = track
        try {
            track.play()
            track.write(samples, 0, samples.size, AudioTrack.WRITE_BLOCKING)
        } catch (_: Exception) {
            // Track was stopped/released concurrently by stopPlaybackNow() - fine, just move on.
        } finally {
            releaseTrack(track)
            if (currentTrack === track) {
                currentTrack = null
            }
        }
    }

    @Synchronized
    private fun stopPlaybackNow() {
        currentTrack?.let { releaseTrack(it) }
        currentTrack = null
    }

    private fun releaseTrack(track: AudioTrack) {
        try {
            track.pause()
            track.flush()
            track.stop()
        } catch (_: Exception) {
        }
        try {
            track.release()
        } catch (_: Exception) {
        }
    }

    private fun maybeFireFinished() {
        if (batchActive && streamFinished && completedCount.get() >= enqueuedCount.get()) {
            batchActive = false
            callback.onSpeechFinished()
        }
    }
}
