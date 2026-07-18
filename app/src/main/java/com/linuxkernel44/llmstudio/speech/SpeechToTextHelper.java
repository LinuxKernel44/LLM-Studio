package com.linuxkernel44.llmstudio.speech;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Wraps Android's SpeechRecognizer, preferring the strictly-on-device engine so voice input never
 * leaves the phone. SpeechRecognizer is a stateful, single-use-per-session object: startListening()
 * begins one recognition session that ends itself (onResults / onError), so a fresh session must be
 * started for every voice turn - it is not a continuous stream.
 *
 * On Android 13+ (API 33), {@link SpeechRecognizer#createOnDeviceSpeechRecognizer} is used when the
 * device reports on-device recognition as available - this guarantees local-only processing and
 * fails outright rather than silently falling back to the cloud. Below API 33 there is no such
 * guarantee in the platform API; {@code EXTRA_PREFER_OFFLINE} is set as a best-effort hint, but
 * whether it actually stays on-device then depends on the device's default assistant/recognizer.
 */
public class SpeechToTextHelper implements SttEngine {

    /** Retained as an alias of {@link SttEngine.Callback} so existing references keep compiling. */
    public interface Callback extends SttEngine.Callback {
    }

    private final SpeechRecognizer speechRecognizer;
    private final boolean onDeviceRecognition;
    private final SttEngine.Callback callback;
    private boolean listening = false;
    private Locale recognitionLocale = Locale.forLanguageTag("en-US");

    public SpeechToTextHelper(Context context, SttEngine.Callback callback) {
        this.callback = callback;
        Context appContext = context.getApplicationContext();

        boolean useOnDevice = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext);
        this.onDeviceRecognition = useOnDevice;
        this.speechRecognizer = useOnDevice
                ? SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
                : SpeechRecognizer.createSpeechRecognizer(appContext);

        this.speechRecognizer.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle params) {
                listening = true;
                callback.onListeningStarted();
            }

            @Override
            public void onPartialResults(Bundle partialResults) {
                String text = firstResult(partialResults);
                if (text != null) {
                    callback.onPartialResult(text);
                }
            }

            @Override
            public void onResults(Bundle results) {
                listening = false;
                String text = firstResult(results);
                if (text != null && !text.trim().isEmpty()) {
                    callback.onFinalResult(text.trim());
                } else {
                    callback.onError("Didn't catch that - please try again.");
                }
            }

            @Override
            public void onError(int error) {
                listening = false;
                callback.onError(describeError(error));
            }

            @Override public void onBeginningOfSpeech() { }
            @Override public void onRmsChanged(float rmsdB) { }
            @Override public void onBufferReceived(byte[] buffer) { }
            @Override public void onEndOfSpeech() { }
            @Override public void onEvent(int eventType, Bundle params) { }
        });
    }

    /** The system recognizer is ready as soon as it's constructed (no model to load on our side). */
    @Override
    public boolean isReady() {
        return true;
    }

    /** No-op: SpeechRecognizer detects end-of-speech itself, so the same session model works for
     *  both push-to-talk and continuous - only Whisper needs the distinction (see WhisperSttEngine). */
    @Override
    public void setContinuousMode(boolean continuous) {
    }

    /** True when recognition is guaranteed on-device (API 33+ on-device recognizer available). */
    public boolean isOnDeviceRecognition() {
        return onDeviceRecognition;
    }

    /** Switches which language is recognized; takes effect from the next startListening() call. */
    @Override
    public void setRecognitionLocale(Locale locale) {
        this.recognitionLocale = locale;
    }

    public void startListening() {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        // EXTRA_LANGUAGE expects a BCP-47 tag String (e.g. "fr-FR"), not a Locale object - passing
        // the Locale itself silently fails to apply (it's stored as a Serializable extra that the
        // recognizer's getStringExtra() lookup can't read), leaving the system locale in effect.
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, recognitionLocale.toLanguageTag());
        intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        if (!onDeviceRecognition) {
            // Best-effort hint below API 33 - not a guarantee, since there's no platform API to
            // enforce on-device processing pre-Tiramisu the way createOnDeviceSpeechRecognizer does.
            intent.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true);
        }
        speechRecognizer.startListening(intent);
    }

    public void stopListening() {
        if (listening) {
            speechRecognizer.stopListening();
            listening = false;
        }
    }

    public void cancel() {
        listening = false;
        speechRecognizer.cancel();
    }

    public void destroy() {
        speechRecognizer.destroy();
    }

    private static String firstResult(Bundle bundle) {
        if (bundle == null) return null;
        List<String> matches = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        if (matches == null) matches = new ArrayList<>();
        return matches.isEmpty() ? null : matches.get(0);
    }

    private static String describeError(int error) {
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "Didn't catch that - please try again.";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Microphone permission is required.";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "Speech recognition needs network access.";
            case SpeechRecognizer.ERROR_AUDIO:
                return "Microphone error - please try again.";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "Speech recognizer is busy - please try again.";
            case SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED:
            case SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE:
                return "This language isn't downloaded for offline speech recognition. Download it in the system's on-device speech recognition settings.";
            default:
                return "Speech recognition failed - please try again.";
        }
    }
}
