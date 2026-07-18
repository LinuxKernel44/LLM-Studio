package com.linuxkernel44.llmstudio.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface ConversationDao {

    @Query("SELECT * FROM conversations ORDER BY updated_at DESC")
    LiveData<List<ConversationEntity>> observeAll();

    @Query("SELECT * FROM conversations WHERE id = :id LIMIT 1")
    ConversationEntity getByIdSync(long id);

    @Query("SELECT * FROM conversations ORDER BY updated_at DESC LIMIT 1")
    ConversationEntity getMostRecentSync();

    @Insert
    long insert(ConversationEntity entity);

    @Update
    void update(ConversationEntity entity);

    @Query("DELETE FROM conversations WHERE id = :id")
    void deleteById(long id);

    @Query("DELETE FROM conversations")
    void deleteAll();
}
