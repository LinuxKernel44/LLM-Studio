package com.linuxkernel44.llmstudio.data;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.room.Database;
import androidx.room.Room;
import androidx.room.RoomDatabase;
import androidx.room.TypeConverters;

@Database(
        entities = {ChatMessageEntity.class, ConversationEntity.class, ProfileEntity.class},
        version = 2,
        exportSchema = false)
@TypeConverters({Converters.class})
public abstract class AppDatabase extends RoomDatabase {

    private static volatile AppDatabase instance;

    public abstract ChatMessageDao chatMessageDao();

    public abstract ConversationDao conversationDao();

    public abstract ProfileDao profileDao();

    public static AppDatabase getInstance(@NonNull Context context) {
        if (instance == null) {
            synchronized (AppDatabase.class) {
                if (instance == null) {
                    instance = Room.databaseBuilder(
                                    context.getApplicationContext(),
                                    AppDatabase.class,
                                    "llm_studio.db")
                            // Adding conversations/profiles reshapes chat_messages (new required
                            // conversationId column) - this is a pre-release app with no migration
                            // path to preserve yet, so the DB just resets cleanly on this upgrade.
                            .fallbackToDestructiveMigration()
                            .build();
                }
            }
        }
        return instance;
    }
}
