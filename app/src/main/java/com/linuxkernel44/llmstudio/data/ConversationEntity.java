package com.linuxkernel44.llmstudio.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/** One chat thread, shown as a row in the conversation drawer. Conversations are shared across
 *  all profiles - a profile only changes which server/model a conversation talks to. */
@Entity(tableName = "conversations")
public class ConversationEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    @ColumnInfo(name = "title")
    public String title;

    @ColumnInfo(name = "created_at")
    public long createdAt;

    /** Bumped on every new message; drives the drawer's most-recent-first ordering. */
    @ColumnInfo(name = "updated_at")
    public long updatedAt;

    public ConversationEntity(@NonNull String title, long createdAt, long updatedAt) {
        this.title = title;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }
}
