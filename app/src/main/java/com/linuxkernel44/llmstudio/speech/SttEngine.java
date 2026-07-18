package com.linuxkernel44.llmstudio.speech;

import java.util.Locale;

/**
 * Common surface for whichever speech-to-text backend is active: the system's built-in
 * {@link SpeechToTextHelper} (Android SpeechRecognizer) or the local neural {@link
 * com.linuxkernel44.llmstudio.speech.WhisperSttEngine}. ChatViewModel talks to this interface only,
 * so switching engines in Settings doesn't change any of its own logic.
 *
 * One "session" is one voice turn: startListening() begins capturing, and the session ends when
 * exactly one of onFinalResult() or onError() fires. In push-to-talk the caller ends it with
 * stopListening(); in continuous mode the engine ends it itself by detecting end-of-speech.
 */
public interface SttEngine {

    interface Callback {
        /** Mic has opened and is actively capturing audio - safe to show a "listening" UI state. */
        void onListeningStarted();

        /** Live partial transcript. Engines that transcribe only after capture (e.g. Whisper) never
         *  call this; the UI simply shows no live text for them. */
        void onPartialResult(String partialText);

        /** Final transcript for this session; the session is now over. */
        void onFinalResult(String finalText);

        /** Session ended with no usable speech (silence, no match, permission/engine error). */
        void onError(String message);
    }

    /** True when the engine can service startListening() right now (e.g. Whisper model loaded). */
    boolean isReady();

    /** Switches which language is recognized; takes effect from the next startListening() call. */
    void setRecognitionLocale(Locale locale);

    /** Tells the engine whether it must self-detect end-of-speech (continuous) or wait for an
     *  explicit stopListening() (push-to-talk). No-op on engines that always endpoint themselves. */
    void setContinuousMode(boolean continuous);

    void startListening();

    /** Push-to-talk release: finalize the current utterance (delivers onFinalResult/onError). */
    void stopListening();

    /** Abandons the current session without delivering any result. */
    void cancel();

    /** Releases engine resources; called once when the owning ViewModel is cleared. */
    void destroy();
}
