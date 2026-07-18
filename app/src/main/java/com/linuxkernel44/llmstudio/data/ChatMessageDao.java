package com.linuxkernel44.llmstudio.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface ChatMessageDao {

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY id ASC")
    LiveData<List<ChatMessageEntity>> observeByConversation(long conversationId);

    @Query("SELECT * FROM chat_messages WHERE conversationId = :conversationId ORDER BY id ASC")
    List<ChatMessageEntity> getAllSyncForConversation(long conversationId);

    @Query("SELECT COUNT(*) FROM chat_messages WHERE conversationId = :conversationId")
    int countForConversationSync(long conversationId);

    @Insert
    long insert(ChatMessageEntity entity);

    @Update
    void update(ChatMessageEntity entity);
}
