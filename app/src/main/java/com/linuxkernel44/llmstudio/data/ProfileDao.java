package com.linuxkernel44.llmstudio.data;

import androidx.lifecycle.LiveData;
import androidx.room.Dao;
import androidx.room.Insert;
import androidx.room.Query;
import androidx.room.Update;

import java.util.List;

@Dao
public interface ProfileDao {

    @Query("SELECT * FROM profiles ORDER BY id ASC")
    LiveData<List<ProfileEntity>> observeAll();

    @Query("SELECT * FROM profiles ORDER BY id ASC")
    List<ProfileEntity> getAllSync();

    @Query("SELECT * FROM profiles WHERE id = :id LIMIT 1")
    ProfileEntity getByIdSync(long id);

    @Query("SELECT * FROM profiles ORDER BY id ASC LIMIT 1")
    ProfileEntity getFirstSync();

    @Query("SELECT COUNT(*) FROM profiles")
    int countSync();

    @Insert
    long insert(ProfileEntity entity);

    @Update
    void update(ProfileEntity entity);

    @Query("DELETE FROM profiles WHERE id = :id")
    void deleteById(long id);
}
