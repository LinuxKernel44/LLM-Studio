package com.linuxkernel44.llmstudio.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;

import com.linuxkernel44.llmstudio.data.AppDatabase;
import com.linuxkernel44.llmstudio.data.ConversationDao;
import com.linuxkernel44.llmstudio.data.ConversationEntity;
import com.linuxkernel44.llmstudio.data.SettingsManager;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/** CRUD for conversations (the drawer list) and startup/active-conversation bookkeeping. */
public class ConversationRepository {

    public static final String NEW_CONVERSATION_TITLE = "New conversation";
    private static final int TITLE_MAX_LENGTH = 48;

    private final ConversationDao dao;
    private final SettingsManager settingsManager;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public ConversationRepository(Context context) {
        this.dao = AppDatabase.getInstance(context).conversationDao();
        this.settingsManager = new SettingsManager(context);
    }

    public LiveData<List<ConversationEntity>> observeAll() {
        return dao.observeAll();
    }

    /**
     * Decides which conversation to show on a fresh app start: the last active one (if "resume
     * last conversation" is on and it still exists), otherwise a brand-new empty conversation.
     * Runs on a background thread; delivers the resolved id on the main thread.
     */
    public void resolveStartupConversation(Consumer<Long> onResolved) {
        dbExecutor.execute(() -> {
            long resolvedId;
            if (settingsManager.isResumeLastConversation()) {
                long lastId = settingsManager.getActiveConversationId();
                ConversationEntity last = lastId == SettingsManager.NO_ID ? null : dao.getByIdSync(lastId);
                resolvedId = last != null ? last.id : insertNewConversation();
            } else {
                resolvedId = insertNewConversation();
            }
            settingsManager.setActiveConversationId(resolvedId);
            long finalId = resolvedId;
            mainHandler.post(() -> onResolved.accept(finalId));
        });
    }

    /** Creates a fresh empty conversation and makes it active. */
    public void createNewConversation(Consumer<Long> onCreated) {
        dbExecutor.execute(() -> {
            long id = insertNewConversation();
            settingsManager.setActiveConversationId(id);
            mainHandler.post(() -> onCreated.accept(id));
        });
    }

    private long insertNewConversation() {
        long now = System.currentTimeMillis();
        return dao.insert(new ConversationEntity(NEW_CONVERSATION_TITLE, now, now));
    }

    public void setActiveConversation(long id) {
        settingsManager.setActiveConversationId(id);
    }

    /** Sets the conversation's title from the first user message the first time it's sent. */
    public void touchAfterFirstMessage(long conversationId, String firstUserText) {
        dbExecutor.execute(() -> {
            ConversationEntity conversation = dao.getByIdSync(conversationId);
            if (conversation == null) {
                return;
            }
            String trimmed = firstUserText.trim();
            conversation.title = trimmed.length() > TITLE_MAX_LENGTH
                    ? trimmed.substring(0, TITLE_MAX_LENGTH).trim() + "…"
                    : trimmed;
            conversation.updatedAt = System.currentTimeMillis();
            dao.update(conversation);
        });
    }

    /** Bumps recency ordering without touching the title (used for turns after the first). */
    public void touchTimestamp(long conversationId) {
        dbExecutor.execute(() -> {
            ConversationEntity conversation = dao.getByIdSync(conversationId);
            if (conversation != null) {
                conversation.updatedAt = System.currentTimeMillis();
                dao.update(conversation);
            }
        });
    }

    public void renameConversation(long conversationId, String newTitle) {
        dbExecutor.execute(() -> {
            ConversationEntity conversation = dao.getByIdSync(conversationId);
            if (conversation != null) {
                conversation.title = newTitle;
                dao.update(conversation);
            }
        });
    }

    /** Cascade-deletes the conversation's messages too (see ChatMessageEntity's foreign key). */
    public void deleteConversation(long conversationId) {
        dbExecutor.execute(() -> dao.deleteById(conversationId));
    }

    /** Used by "Clear conversation history" in Settings: wipes every conversation and its messages. */
    public void deleteAll() {
        dbExecutor.execute(dao::deleteAll);
    }
}
