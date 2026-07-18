package com.linuxkernel44.llmstudio.speech;

import java.util.Locale;

/**
 * Common surface for whichever speech-synthesis backend is active: the system's built-in
 * {@link TextToSpeechHelper} or the local neural {@link KokoroTtsEngine}. ChatViewModel talks to
 * this interface only, so switching engines in Settings doesn't change any of its own logic.
 */
public interface TtsEngine {

    interface Callback {
        void onTtsReady();

        void onSpeechStarted();

        void onSpeechFinished();

        void onTtsError(String message);
    }

    boolean isReady();

    /** No-op on engines that only support one language (e.g. Kokoro's English-only voice pack). */
    void setLanguage(Locale locale);

    /** 1.0 = normal speed. No-op on engines that don't support rate control. */
    void setSpeechRate(float rate);

    /** Starts a fresh assistant turn, discarding anything currently playing or queued. */
    void beginBatch();

    /** Queues one sentence/fragment to be spoken after whatever is already queued. */
    void enqueue(String text);

    /** Signals no more sentences will be enqueued; fires onSpeechFinished once the queue drains. */
    void finishBatch();

    /** Immediately stops playback and abandons the current batch WITHOUT firing onSpeechFinished. */
    void stop();

    /** Releases engine resources; called once when the owning ViewModel is cleared. */
    void shutdown();
}
