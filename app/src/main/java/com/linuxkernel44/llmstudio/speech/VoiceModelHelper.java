package com.linuxkernel44.llmstudio.speech;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.speech.ModelDownloadListener;
import android.speech.RecognitionSupport;
import android.speech.RecognitionSupportCallback;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Inspects and downloads the on-device speech recognition models, which are managed by the system's
 * speech service (Android System Intelligence) and are COMPLETELY SEPARATE from the language packs
 * installed for the keyboard/Gboard or from the phone's system languages. That separation is why a
 * language can look "downloaded" everywhere in the system settings while the on-device recognizer
 * still reports it as unavailable.
 *
 * Uses the API 33+ {@link SpeechRecognizer#checkRecognitionSupport} to list what is really installed
 * and {@link SpeechRecognizer#triggerModelDownload} to fetch a missing model (which may prompt the
 * user to approve the download). Only usable on Android 13+; callers must check
 * {@link #isSupported(Context)} first.
 */
@RequiresApi(Build.VERSION_CODES.TIRAMISU)
public class VoiceModelHelper {

    public interface SupportCallback {
        /**
         * @param installed    languages ready to use offline right now
         * @param downloadable languages the recognizer supports but must download first
         * @param pending      languages whose download is already scheduled/in progress
         */
        void onSupportResult(List<String> installed, List<String> downloadable, List<String> pending);

        void onError(String message);
    }

    public interface DownloadCallback {
        void onProgress(int percent);

        void onCompleted();

        void onScheduled();

        void onFailed(String message);
    }

    private final SpeechRecognizer recognizer;

    /** True only when the platform exposes a guaranteed on-device recognizer to query/download for. */
    public static boolean isSupported(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
                && SpeechRecognizer.isOnDeviceRecognitionAvailable(context.getApplicationContext());
    }

    /** Must be constructed on the main thread (SpeechRecognizer requirement). */
    public VoiceModelHelper(Context context) {
        this.recognizer = SpeechRecognizer.createOnDeviceSpeechRecognizer(context.getApplicationContext());
    }

    /** Asks the recognizer which languages it actually has installed / can download. Main thread only. */
    public void checkSupport(Locale locale, SupportCallback callback) {
        recognizer.checkRecognitionSupport(buildIntent(locale), Runnable::run, new RecognitionSupportCallback() {
            @Override
            public void onSupportResult(@NonNull RecognitionSupport recognitionSupport) {
                callback.onSupportResult(
                        safeList(recognitionSupport.getInstalledOnDeviceLanguages()),
                        safeList(recognitionSupport.getSupportedOnDeviceLanguages()),
                        safeList(recognitionSupport.getPendingOnDeviceLanguages()));
            }

            @Override
            public void onError(int error) {
                callback.onError("Couldn't check language support (error " + error + ").");
            }
        });
    }

    /**
     * Requests the on-device model for the given language. May show a system prompt asking the user
     * to approve the download. Main thread only.
     */
    public void triggerDownload(Locale locale, DownloadCallback callback) {
        Intent intent = buildIntent(locale);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+ can report progress/success; API 33 only has the fire-and-forget overload.
            recognizer.triggerModelDownload(intent, Runnable::run, new ModelDownloadListener() {
                @Override
                public void onProgress(int completedPercent) {
                    callback.onProgress(completedPercent);
                }

                @Override
                public void onSuccess() {
                    callback.onCompleted();
                }

                @Override
                public void onScheduled() {
                    callback.onScheduled();
                }

                @Override
                public void onError(int error) {
                    callback.onFailed("Model download failed (error " + error + ").");
                }
            });
        } else {
            recognizer.triggerModelDownload(intent);
            callback.onScheduled();
        }
    }

    public void destroy() {
        recognizer.destroy();
    }

    private static Intent buildIntent(Locale locale) {
        Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, locale.toLanguageTag());
        return intent;
    }

    private static List<String> safeList(List<String> list) {
        return list == null ? new ArrayList<>() : list;
    }
}
