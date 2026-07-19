package com.linuxkernel44.llmstudio.speech;

import android.content.Context;
import android.media.AudioAttributes;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import java.util.Locale;

/**
 * Wraps Android's on-device TextToSpeech engine and speaks a reply incrementally: sentences are
 * enqueued (QUEUE_ADD) as they stream in, so speech starts before the full response is generated.
 *
 * A "batch" models one assistant turn:
 *   beginBatch() -> enqueue(sentence) * N -> finishBatch()
 * onSpeechStarted fires when the first sentence begins; onSpeechFinished fires only once the stream
 * is marked finished AND every queued sentence has finished playing (tracked via utterance counters).
 */
public class TextToSpeechHelper implements TtsEngine {

    // Not final: onInit's lambda reads this field before the constructor's assignment
    // statement finishes, which javac only permits for non-final fields (it fires async anyway).
    private TextToSpeech textToSpeech;
    private final Callback callback;
    private boolean ready = false;
    private Locale pendingLocale = Locale.forLanguageTag("en-US");
    private float pendingSpeechRate = 1.0f;

    // Counters are touched from the main thread (enqueue/finishBatch/begin/stop) and from the TTS
    // engine's callback thread (onStart/onDone), so all access is synchronized on this instance.
    private boolean batchActive = false;
    private boolean streamFinished = false;
    private boolean speakingStarted = false;
    private int enqueuedCount = 0;
    private int completedCount = 0;

    public TextToSpeechHelper(Context context, Callback callback) {
        this.callback = callback;
        // The TextToSpeech constructor initializes asynchronously; onInit fires once the engine
        // and voice data are ready, which is when it's actually safe to call speak().
        this.textToSpeech = new TextToSpeech(context.getApplicationContext(), status -> {
            if (status == TextToSpeech.SUCCESS) {
                ready = true;
                // Route spoken replies through the ASSISTANT audio usage so they follow the phone's
                // assistant/AI volume category where one exists (e.g. Samsung's "Bixby/assistant"
                // volume), matching KokoroTtsEngine's AudioTrack. Phones without a dedicated assistant
                // stream fall back to a sensible default automatically. USAGE_ASSISTANT is API 26+,
                // always available at our minSdk 27.
                textToSpeech.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ASSISTANT)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                applyLocale(pendingLocale);
                textToSpeech.setSpeechRate(pendingSpeechRate);
                callback.onTtsReady();
            } else {
                callback.onTtsError("Text-to-speech engine failed to initialize.");
            }
        });

        textToSpeech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            @Override
            public void onStart(String utteranceId) {
                boolean fireStarted = false;
                synchronized (TextToSpeechHelper.this) {
                    if (batchActive && !speakingStarted) {
                        speakingStarted = true;
                        fireStarted = true;
                    }
                }
                if (fireStarted) {
                    callback.onSpeechStarted();
                }
            }

            @Override
            public void onDone(String utteranceId) {
                onUtteranceEnded();
            }

            @Override
            public void onError(String utteranceId) {
                // A single utterance failing shouldn't abort the whole turn or show an error toast;
                // count it as ended so the batch can still drain and finish cleanly.
                onUtteranceEnded();
            }
        });
    }

    @Override
    public boolean isReady() {
        return ready;
    }

    /** Switches the spoken language; applied immediately if the engine is ready, otherwise once it is. */
    @Override
    public void setLanguage(Locale locale) {
        pendingLocale = locale;
        if (ready) {
            applyLocale(locale);
        }
    }

    /** 1.0 = normal speed; e.g. 0.5 = half speed, 2.0 = double speed. Applied immediately if ready. */
    @Override
    public void setSpeechRate(float rate) {
        pendingSpeechRate = rate;
        if (ready) {
            textToSpeech.setSpeechRate(rate);
        }
    }

    private void applyLocale(Locale locale) {
        int result = textToSpeech.setLanguage(locale);
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            callback.onTtsError("This language isn't available for text-to-speech on this device.");
        }
    }

    /** Starts a fresh assistant turn, discarding anything currently playing or queued. */
    @Override
    public synchronized void beginBatch() {
        textToSpeech.stop();
        batchActive = true;
        streamFinished = false;
        speakingStarted = false;
        enqueuedCount = 0;
        completedCount = 0;
    }

    /** Queues one sentence/fragment to be spoken after whatever is already queued. */
    @Override
    public synchronized void enqueue(String text) {
        if (!ready || !batchActive || text == null || text.trim().isEmpty()) {
            return;
        }
        enqueuedCount++;
        String utteranceId = "u" + enqueuedCount;
        textToSpeech.speak(text, TextToSpeech.QUEUE_ADD, null, utteranceId);
    }

    /** Signals no more sentences will be enqueued; fires onSpeechFinished once the queue drains. */
    @Override
    public void finishBatch() {
        boolean fireFinished;
        synchronized (this) {
            streamFinished = true;
            fireFinished = shouldFinishLocked();
            if (fireFinished) {
                batchActive = false;
            }
        }
        if (fireFinished) {
            callback.onSpeechFinished();
        }
    }

    private void onUtteranceEnded() {
        boolean fireFinished;
        synchronized (this) {
            if (!batchActive) {
                return;
            }
            completedCount++;
            fireFinished = shouldFinishLocked();
            if (fireFinished) {
                batchActive = false;
            }
        }
        if (fireFinished) {
            callback.onSpeechFinished();
        }
    }

    private boolean shouldFinishLocked() {
        return batchActive && streamFinished && completedCount >= enqueuedCount;
    }

    /**
     * Immediately stops playback and abandons the current batch WITHOUT firing onSpeechFinished -
     * used when the user interrupts (e.g. barges in / stops the loop). No resume should follow.
     */
    @Override
    public synchronized void stop() {
        batchActive = false;
        streamFinished = false;
        textToSpeech.stop();
    }

    @Override
    public void shutdown() {
        textToSpeech.shutdown();
    }
}
