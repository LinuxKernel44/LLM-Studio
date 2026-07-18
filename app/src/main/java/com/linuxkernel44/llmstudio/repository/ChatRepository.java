package com.linuxkernel44.llmstudio.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;

import com.linuxkernel44.llmstudio.data.AppDatabase;
import com.linuxkernel44.llmstudio.data.ChatMessageDao;
import com.linuxkernel44.llmstudio.data.ChatMessageEntity;
import com.linuxkernel44.llmstudio.data.MessageRole;
import com.linuxkernel44.llmstudio.data.ProfileEntity;
import com.linuxkernel44.llmstudio.network.ChatMessagePayload;
import com.linuxkernel44.llmstudio.network.StreamingChatClient;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import okhttp3.sse.EventSource;

/** Bridges the Room-backed message history with the streaming network client. */
public class ChatRepository {

    private final ChatMessageDao dao;
    private final ProfileRepository profileRepository;
    private final ConversationRepository conversationRepository;
    private final StreamingChatClient streamingChatClient = new StreamingChatClient();
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    private EventSource activeStream;

    public ChatRepository(Context context, ProfileRepository profileRepository, ConversationRepository conversationRepository) {
        this.dao = AppDatabase.getInstance(context).chatMessageDao();
        this.profileRepository = profileRepository;
        this.conversationRepository = conversationRepository;
    }

    public LiveData<List<ChatMessageEntity>> observeMessages(long conversationId) {
        return dao.observeByConversation(conversationId);
    }

    public interface ReplyCallback {
        void onStart(long assistantMessageId);

        /** fullTextSoFar is the RAW accumulated stream text (not the sanitized display text shown
         *  in the chat bubble, which is written straight to Room) - callers use it to track TTS
         *  progress by character index, which requires a strictly append-only buffer. */
        void onTokenReceived(long assistantMessageId, String fullTextSoFar);

        /** finalText is likewise the raw accumulated text, for the same reason. */
        void onComplete(long assistantMessageId, String finalText);

        void onError(long assistantMessageId, String userFriendlyMessage);
    }

    /**
     * Persists the user's message into the given conversation, then sends that conversation's whole
     * history (system prompt + prior turns + new turn) to the ACTIVE PROFILE's backend and streams
     * the assistant's reply back into a placeholder row.
     */
    public void sendUserMessage(long conversationId, String userText, ReplyCallback callback) {
        dbExecutor.execute(() -> {
            boolean isFirstMessage = dao.countForConversationSync(conversationId) == 0;

            ChatMessageEntity userMessage = new ChatMessageEntity(
                    conversationId, MessageRole.USER, userText, System.currentTimeMillis(), false);
            dao.insert(userMessage);

            if (isFirstMessage) {
                conversationRepository.touchAfterFirstMessage(conversationId, userText);
            } else {
                conversationRepository.touchTimestamp(conversationId);
            }

            List<ChatMessageEntity> history = dao.getAllSyncForConversation(conversationId);
            List<ChatMessagePayload> payload = new ArrayList<>();
            ProfileEntity activeProfile = profileRepository.getActiveProfileSync();
            String systemPrompt = activeProfile.systemPrompt;
            if (systemPrompt != null && !systemPrompt.trim().isEmpty()) {
                payload.add(new ChatMessagePayload("system", systemPrompt));
            }
            for (ChatMessageEntity entity : history) {
                payload.add(new ChatMessagePayload(entity.role.getApiValue(), entity.content));
            }

            ChatMessageEntity assistantPlaceholder = new ChatMessageEntity(
                    conversationId, MessageRole.ASSISTANT, "", System.currentTimeMillis(), true);
            long assistantId = dao.insert(assistantPlaceholder);
            assistantPlaceholder.id = assistantId;

            String endpointUrl = activeProfile.endpointUrl;
            String modelName = activeProfile.modelName;

            StringBuilder accumulated = new StringBuilder();
            activeStream = streamingChatClient.streamChatCompletion(endpointUrl, modelName, payload, new StreamingChatClient.StreamListener() {
                @Override
                public void onStart() {
                    mainHandler.post(() -> callback.onStart(assistantId));
                }

                @Override
                public void onTokenReceived(String contentDelta) {
                    accumulated.append(contentDelta);
                    String textSoFar = sanitizeForDisplay(accumulated.toString());
                    dbExecutor.execute(() -> {
                        assistantPlaceholder.content = textSoFar;
                        dao.update(assistantPlaceholder);
                    });
                    // The callback's text is used by ChatViewModel purely as a monotonically-growing
                    // buffer to track TTS sentence-boundary indices (spokenUpTo) - it must be the RAW
                    // accumulated text, not the sanitized display text. sanitizeForDisplay's regex
                    // replacements (collapsing 3+ newlines, trimming) are re-run over the WHOLE buffer
                    // on every token, so their output can change length non-monotonically as more text
                    // streams in (e.g. a trailing run of newlines gets trimmed away, then un-trimmed
                    // once more text follows it) - that desyncs spokenUpTo from the string it indexes
                    // into and was causing the tail of the reply to be spoken twice.
                    mainHandler.post(() -> callback.onTokenReceived(assistantId, accumulated.toString()));
                }

                @Override
                public void onComplete() {
                    String finalText = sanitizeForDisplay(accumulated.toString());
                    dbExecutor.execute(() -> {
                        assistantPlaceholder.content = finalText;
                        assistantPlaceholder.isStreaming = false;
                        dao.update(assistantPlaceholder);
                    });
                    conversationRepository.touchTimestamp(conversationId);
                    // Raw text here too, for the same reason as onTokenReceived above - must match
                    // the buffer spokenUpTo was computed against.
                    mainHandler.post(() -> callback.onComplete(assistantId, accumulated.toString()));
                }

                @Override
                public void onError(String userFriendlyMessage) {
                    dbExecutor.execute(() -> {
                        assistantPlaceholder.isStreaming = false;
                        if (assistantPlaceholder.content.isEmpty()) {
                            assistantPlaceholder.content = userFriendlyMessage;
                        }
                        dao.update(assistantPlaceholder);
                    });
                    mainHandler.post(() -> callback.onError(assistantId, userFriendlyMessage));
                }
            });
        });
    }

    /**
     * llama.cpp/Gemma occasionally emit a run of blank lines (or trailing whitespace) around their
     * stop token, which otherwise renders as a tall, mostly-empty bubble in the chat UI even though
     * the visible text is short. Collapses 3+ consecutive newlines down to one blank line (preserving
     * intentional paragraph breaks) and trims trailing whitespace. Applied to the full accumulated
     * text on every token, so it's idempotent and never conflicts with the raw text already spoken.
     */
    private static String sanitizeForDisplay(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return raw.replaceAll("\\n{3,}", "\n\n").replaceAll("[ \\t]+\\n", "\n").trim();
    }

    /** Cancels an in-flight SSE stream, e.g. when the user starts a new voice turn mid-reply. */
    public void cancelActiveStream() {
        if (activeStream != null) {
            activeStream.cancel();
            activeStream = null;
        }
    }
}
