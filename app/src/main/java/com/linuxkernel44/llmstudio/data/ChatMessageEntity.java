package com.linuxkernel44.llmstudio.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.ForeignKey;
import androidx.room.Index;
import androidx.room.PrimaryKey;

/** A single persisted chat turn, shown as one bubble in the RecyclerView. */
@Entity(
        tableName = "chat_messages",
        foreignKeys = @ForeignKey(
                entity = ConversationEntity.class,
                parentColumns = "id",
                childColumns = "conversationId",
                onDelete = ForeignKey.CASCADE),
        indices = {@Index("conversationId")}
)
public class ChatMessageEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    /** Which conversation this turn belongs to; deleting the conversation deletes its messages too. */
    @ColumnInfo(name = "conversationId")
    public long conversationId;

    @NonNull
    @ColumnInfo(name = "role")
    public MessageRole role;

    @NonNull
    @ColumnInfo(name = "content")
    public String content;

    @ColumnInfo(name = "timestamp")
    public long timestamp;

    /** True while an assistant reply is still streaming in (used to show a typing indicator). */
    @ColumnInfo(name = "is_streaming")
    public boolean isStreaming;

    public ChatMessageEntity(long conversationId, @NonNull MessageRole role, @NonNull String content,
                              long timestamp, boolean isStreaming) {
        this.conversationId = conversationId;
        this.role = role;
        this.content = content;
        this.timestamp = timestamp;
        this.isStreaming = isStreaming;
    }
}
