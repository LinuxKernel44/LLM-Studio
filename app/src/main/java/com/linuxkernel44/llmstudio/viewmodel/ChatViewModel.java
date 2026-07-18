package com.linuxkernel44.llmstudio.viewmodel;

import android.app.Application;
import android.os.Handler;
import android.os.Looper;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.Observer;
import androidx.lifecycle.Transformations;

import com.linuxkernel44.llmstudio.data.ChatMessageEntity;
import com.linuxkernel44.llmstudio.data.ConversationEntity;
import com.linuxkernel44.llmstudio.data.KokoroModelManager;
import com.linuxkernel44.llmstudio.repository.ChatRepository;
import com.linuxkernel44.llmstudio.repository.ConversationRepository;
import com.linuxkernel44.llmstudio.repository.ProfileRepository;
import com.linuxkernel44.llmstudio.speech.KokoroTtsEngine;
import com.linuxkernel44.llmstudio.speech.SpeechToTextHelper;
import com.linuxkernel44.llmstudio.speech.TextToSpeechHelper;
import com.linuxkernel44.llmstudio.speech.TtsEngine;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Owns the voice loop's state machine, coordinating the SpeechRecognizer, the streaming network
 * call, and TextToSpeech, plus which conversation is currently active. Supports two input modes:
 *   - Push-to-talk: hold the mic (press -> listen, release -> send); ends at IDLE after the reply.
 *   - Continuous:   tap to start a hands-free loop that auto-listens again after each spoken reply,
 *                   tap again to stop.
 * TTS is spoken incrementally: sentences are enqueued as the reply streams in.
 */
public class ChatViewModel extends AndroidViewModel {

    private final ChatRepository repository;
    private final ConversationRepository conversationRepository;
    private final ProfileRepository profileRepository;
    private final SpeechToTextHelper speechToTextHelper;
    private final TextToSpeechHelper systemTts;
    private final TtsEngine.Callback ttsCallback;
    private KokoroTtsEngine kokoroTts; // lazily created only if/when the user selects it
    private TtsEngine activeTts;

    // SpeechRecognizer / TextToSpeech require certain calls on the main thread; TTS callbacks arrive
    // on a binder thread, so resumes are posted here.
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private final MutableLiveData<MicState> micState = new MutableLiveData<>(MicState.IDLE);
    private final MutableLiveData<String> partialTranscript = new MutableLiveData<>("");
    private final SingleLiveEvent<String> errorEvent = new SingleLiveEvent<>();

    private final MutableLiveData<Long> currentConversationId = new MutableLiveData<>();
    private final LiveData<List<ChatMessageEntity>> messages;
    private final LiveData<List<ConversationEntity>> conversations;
    private final Observer<List<ConversationEntity>> conversationListWatcher;

    private boolean continuousMode = false;   // reflects the Settings choice
    private boolean continuousActive = false; // whether the hands-free loop is currently running

    // Tracks how much of the streamed reply has already been handed to TTS, so each token only
    // enqueues the newly-completed sentence(s).
    private int spokenUpTo = 0;

    public ChatViewModel(@NonNull Application application) {
        super(application);
        profileRepository = new ProfileRepository(application);
        conversationRepository = new ConversationRepository(application);
        repository = new ChatRepository(application, profileRepository, conversationRepository);

        conversations = conversationRepository.observeAll();
        messages = Transformations.switchMap(currentConversationId,
                id -> id == null ? new MutableLiveData<>(Collections.emptyList()) : repository.observeMessages(id));

        // Keeps currentConversationId valid whenever the conversation list changes: if the active
        // conversation was deleted (from the drawer, or via "Clear conversation history" wiping
        // everything in Settings), fall back to the most recent remaining one, or create a fresh
        // one if none are left. Guarded on `current == null` so it doesn't race the initial
        // resolveStartupConversation() call below, which sets currentConversationId itself once.
        conversationListWatcher = list -> {
            Long current = currentConversationId.getValue();
            if (current == null) {
                return;
            }
            boolean stillExists = false;
            for (ConversationEntity c : list) {
                if (c.id == current) {
                    stillExists = true;
                    break;
                }
            }
            if (!stillExists) {
                if (list.isEmpty()) {
                    conversationRepository.createNewConversation(currentConversationId::postValue);
                } else {
                    long fallbackId = list.get(0).id; // observeAll() is sorted most-recent-first
                    conversationRepository.setActiveConversation(fallbackId);
                    currentConversationId.postValue(fallbackId);
                }
            }
        };
        conversations.observeForever(conversationListWatcher);

        profileRepository.ensureActiveProfile(profileId -> { /* just needs to exist before the first send */ });
        conversationRepository.resolveStartupConversation(currentConversationId::postValue);

        speechToTextHelper = new SpeechToTextHelper(application, new SpeechToTextHelper.Callback() {
            @Override
            public void onListeningStarted() {
                micState.setValue(MicState.LISTENING);
            }

            @Override
            public void onPartialResult(String partialText) {
                partialTranscript.setValue(partialText);
            }

            @Override
            public void onFinalResult(String finalText) {
                partialTranscript.setValue("");
                sendMessage(finalText);
            }

            @Override
            public void onError(String message) {
                partialTranscript.setValue("");
                if (continuousActive) {
                    // Silence / no-match during a hands-free loop: quietly keep listening.
                    restartListening();
                } else {
                    micState.setValue(MicState.IDLE);
                    errorEvent.setValue(message);
                }
            }
        });

        // Shared by whichever engine is active (system or Kokoro) - switching engines never needs
        // to touch this wiring, since both implementations drive the exact same TtsEngine.Callback.
        ttsCallback = new TtsEngine.Callback() {
            @Override
            public void onTtsReady() {
                // No state to track here: readiness is queried live via activeTts.isReady().
            }

            @Override
            public void onSpeechStarted() {
                micState.postValue(MicState.SPEAKING);
            }

            @Override
            public void onSpeechFinished() {
                // Whole reply has finished being spoken -> resume the loop or go idle.
                finishAssistantTurn();
            }

            @Override
            public void onTtsError(String message) {
                micState.postValue(MicState.IDLE);
                errorEvent.postValue(message);
            }
        };
        systemTts = new TextToSpeechHelper(application, ttsCallback);
        activeTts = systemTts;
    }

    public LiveData<List<ChatMessageEntity>> getMessages() {
        return messages;
    }

    public LiveData<List<ConversationEntity>> getConversations() {
        return conversations;
    }

    public LiveData<Long> getCurrentConversationId() {
        return currentConversationId;
    }

    public LiveData<MicState> getMicState() {
        return micState;
    }

    public LiveData<String> getPartialTranscript() {
        return partialTranscript;
    }

    public LiveData<String> getErrorEvent() {
        return errorEvent;
    }

    // ---------------------------------------------------------------------------------------------
    // Conversation drawer actions.
    // ---------------------------------------------------------------------------------------------

    public void startNewConversation() {
        interruptOngoing();
        conversationRepository.createNewConversation(currentConversationId::postValue);
    }

    public void switchToConversation(long conversationId) {
        Long current = currentConversationId.getValue();
        if (current != null && current == conversationId) {
            return;
        }
        interruptOngoing();
        // A conversation switch always cancels any hands-free loop, since it was listening/speaking
        // in the context of the conversation being left.
        continuousActive = false;
        speechToTextHelper.cancel();
        micState.setValue(MicState.IDLE);
        conversationRepository.setActiveConversation(conversationId);
        currentConversationId.setValue(conversationId);
    }

    public void renameConversation(long conversationId, String newTitle) {
        if (newTitle == null || newTitle.trim().isEmpty()) {
            return;
        }
        conversationRepository.renameConversation(conversationId, newTitle.trim());
    }

    public void deleteConversation(long conversationId) {
        conversationRepository.deleteConversation(conversationId);
        // If it was the active one, conversationListWatcher above will pick a replacement once
        // Room's LiveData reflects the deletion.
    }

    /** Wipes every conversation, then starts a fresh empty one since the app always needs an active id. */
    public void deleteAllConversations() {
        interruptOngoing();
        conversationRepository.deleteAll();
        conversationRepository.createNewConversation(currentConversationId::postValue);
    }

    // ---------------------------------------------------------------------------------------------
    // Input mode wiring (called by the Activity, which reads the current Settings choice on resume).
    // ---------------------------------------------------------------------------------------------

    /** Applied to both the recognizer and the active TTS engine - the app's own UI text stays English. */
    public void setVoiceLanguage(Locale locale) {
        speechToTextHelper.setRecognitionLocale(locale);
        activeTts.setLanguage(locale);
    }

    /** 1.0 = normal TTS speed; no-op on engines that don't support rate control. */
    public void setSpeechRate(float rate) {
        activeTts.setSpeechRate(rate);
    }

    /**
     * Switches the active TTS backend. useKokoro is ignored (falls back to the system engine) if
     * the Kokoro model hasn't been downloaded yet - see KokoroModelManager/SettingsController for
     * the download flow. kokoroSpeakerId indexes KokoroModelManager.VOICE_NAMES.
     */
    public void setTtsEngine(boolean useKokoro, int kokoroSpeakerId) {
        TtsEngine target = systemTts;
        if (useKokoro && KokoroModelManager.isModelReady(getApplication())) {
            if (kokoroTts == null) {
                kokoroTts = new KokoroTtsEngine(getApplication(), ttsCallback);
            }
            kokoroTts.setSpeakerId(kokoroSpeakerId);
            target = kokoroTts;
        }
        if (activeTts != target) {
            // stop() never fires onSpeechFinished, so switching away mid-speech can't cause the
            // outgoing engine to trigger a stray state transition once it's no longer active.
            activeTts.stop();
            activeTts = target;
        }
    }

    public void setContinuousMode(boolean continuous) {
        if (this.continuousMode == continuous) {
            return;
        }
        // Mode changed while the screen was away: abort anything in flight and reset to a clean idle.
        this.continuousMode = continuous;
        continuousActive = false;
        speechToTextHelper.cancel();
        activeTts.stop();
        repository.cancelActiveStream();
        micState.setValue(MicState.IDLE);
    }

    // ---------------------------------------------------------------------------------------------
    // Push-to-talk entry points.
    // ---------------------------------------------------------------------------------------------

    /** Mic pressed and held: interrupt anything playing/streaming and start a listening session. */
    public void onPushToTalkStart() {
        interruptOngoing();
        speechToTextHelper.startListening();
    }

    /** Mic released: finalize the current utterance, which auto-sends via onFinalResult. */
    public void onPushToTalkStop() {
        if (micState.getValue() == MicState.LISTENING) {
            speechToTextHelper.stopListening();
        }
    }

    // ---------------------------------------------------------------------------------------------
    // Continuous (hands-free) entry point.
    // ---------------------------------------------------------------------------------------------

    /** Tap toggles the hands-free loop on/off. */
    public void onContinuousToggle() {
        if (continuousActive) {
            continuousActive = false;
            interruptOngoing();
            speechToTextHelper.cancel();
            micState.setValue(MicState.IDLE);
        } else {
            continuousActive = true;
            interruptOngoing();
            speechToTextHelper.startListening();
        }
    }

    /** Stops TTS playback and cancels any in-flight network stream, without changing the loop flag. */
    private void interruptOngoing() {
        MicState current = micState.getValue();
        if (current == MicState.SPEAKING) {
            activeTts.stop();
        } else if (current == MicState.PROCESSING) {
            repository.cancelActiveStream();
        }
    }

    /** Manual text-input fallback: bypasses the recognizer entirely. */
    public void sendManualMessage(String text) {
        if (text == null || text.trim().isEmpty()) {
            return;
        }
        sendMessage(text.trim());
    }

    private void sendMessage(String text) {
        Long conversationId = currentConversationId.getValue();
        if (conversationId == null) {
            // Startup resolution hasn't landed yet (should be rare/instant); drop the turn rather
            // than send it into a void conversation id.
            errorEvent.setValue("Still setting up - please try again in a moment.");
            return;
        }
        micState.setValue(MicState.PROCESSING);
        spokenUpTo = 0;
        boolean ttsReady = activeTts.isReady();
        if (ttsReady) {
            activeTts.beginBatch();
        }
        repository.sendUserMessage(conversationId, text, new ChatRepository.ReplyCallback() {
            @Override
            public void onStart(long assistantMessageId) {
                // Room's LiveData already reflects the new placeholder row; nothing else to do.
            }

            @Override
            public void onTokenReceived(long assistantMessageId, String fullTextSoFar) {
                // Speak each newly-completed sentence as it arrives, so playback starts mid-stream.
                if (ttsReady) {
                    speakCompletedSentences(fullTextSoFar);
                }
            }

            @Override
            public void onComplete(long assistantMessageId, String finalText) {
                if (ttsReady) {
                    // Flush any trailing text that had no sentence-ending punctuation, then close
                    // the batch; onSpeechFinished will drive the next state transition.
                    String remainder = finalText.substring(Math.min(spokenUpTo, finalText.length())).trim();
                    String spokenRemainder = stripEmojiForSpeech(remainder);
                    if (!spokenRemainder.isEmpty()) {
                        activeTts.enqueue(spokenRemainder);
                    }
                    activeTts.finishBatch();
                } else {
                    finishAssistantTurn();
                }
            }

            @Override
            public void onError(long assistantMessageId, String userFriendlyMessage) {
                activeTts.stop();
                if (continuousActive) {
                    errorEvent.setValue(userFriendlyMessage);
                    restartListening();
                } else {
                    micState.setValue(MicState.IDLE);
                    errorEvent.setValue(userFriendlyMessage);
                }
            }
        });
    }

    /** Enqueues to TTS every complete sentence in the streamed text past what was already spoken. */
    private void speakCompletedSentences(String fullText) {
        int boundary = lastSentenceBoundary(fullText, spokenUpTo);
        if (boundary > spokenUpTo) {
            // Sentence boundaries are found in the original text (so indices stay valid against the
            // next fullTextSoFar/finalText), but emoji are dropped only from what actually gets spoken.
            String chunk = stripEmojiForSpeech(fullText.substring(spokenUpTo, boundary).trim());
            if (!chunk.isEmpty()) {
                activeTts.enqueue(chunk);
            }
            spokenUpTo = boundary;
        }
    }

    private static final java.util.regex.Pattern EMOJI_PATTERN = java.util.regex.Pattern.compile(
            "[\\x{203C}\\x{2049}\\x{2122}\\x{2139}\\x{2190}-\\x{21FF}\\x{2300}-\\x{23FF}\\x{2460}-\\x{24FF}"
            + "\\x{25A0}-\\x{27BF}\\x{2900}-\\x{29FF}\\x{2B00}-\\x{2BFF}\\x{2C60}-\\x{2C7F}\\x{2E00}-\\x{2E7F}"
            + "\\x{3030}\\x{303D}\\x{3297}\\x{3299}\\x{1F000}-\\x{1FAFF}\\x{FE00}-\\x{FE0F}\\x{200D}]+");

    /** Strips emoji/pictographs (which most TTS engines either skip or read as odd unicode names)
     *  from text that will actually be spoken; the displayed chat bubble keeps them untouched. */
    private static String stripEmojiForSpeech(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        return EMOJI_PATTERN.matcher(text).replaceAll("").replaceAll("[ \\t]{2,}", " ").trim();
    }

    /**
     * Returns the index just past the last sentence-ending punctuation followed by whitespace, or
     * -1 if none. Requiring a trailing whitespace avoids splitting on a "." that is still mid-stream
     * (e.g. the middle of a number) - the tail is flushed at onComplete instead.
     */
    private int lastSentenceBoundary(String text, int from) {
        int lastBoundary = -1;
        for (int i = from; i < text.length() - 1; i++) {
            char c = text.charAt(i);
            if ((c == '.' || c == '!' || c == '?' || c == '\n') && Character.isWhitespace(text.charAt(i + 1))) {
                lastBoundary = i + 1;
            }
        }
        return lastBoundary;
    }

    /** After a reply is fully spoken: resume listening if the hands-free loop is on, else go idle. */
    private void finishAssistantTurn() {
        if (continuousActive) {
            restartListening();
        } else {
            micState.postValue(MicState.IDLE);
        }
    }

    /** Starts a new listening session on the main thread (safe from any calling thread). */
    private void restartListening() {
        mainHandler.post(speechToTextHelper::startListening);
    }

    @Override
    protected void onCleared() {
        super.onCleared();
        conversations.removeObserver(conversationListWatcher);
        continuousActive = false;
        repository.cancelActiveStream();
        speechToTextHelper.destroy();
        systemTts.shutdown();
        if (kokoroTts != null) {
            kokoroTts.shutdown();
        }
    }
}
