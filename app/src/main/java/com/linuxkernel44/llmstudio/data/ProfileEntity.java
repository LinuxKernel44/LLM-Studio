package com.linuxkernel44.llmstudio.data;

import androidx.annotation.NonNull;
import androidx.room.ColumnInfo;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

/**
 * A named backend configuration (URL + model + system prompt) - lets the user keep several
 * different llama-server instances configured and switch between them without retyping anything.
 * Conversations are NOT tied to a profile: switching profiles only changes which server new
 * messages are sent to, the conversation list stays the same across all profiles.
 */
@Entity(tableName = "profiles")
public class ProfileEntity {

    @PrimaryKey(autoGenerate = true)
    public long id;

    @NonNull
    @ColumnInfo(name = "name")
    public String name;

    @NonNull
    @ColumnInfo(name = "endpoint_url")
    public String endpointUrl;

    /** May be empty; an empty model name is omitted from the request body. */
    @NonNull
    @ColumnInfo(name = "model_name")
    public String modelName;

    @NonNull
    @ColumnInfo(name = "system_prompt")
    public String systemPrompt;

    public ProfileEntity(@NonNull String name, @NonNull String endpointUrl,
                          @NonNull String modelName, @NonNull String systemPrompt) {
        this.name = name;
        this.endpointUrl = endpointUrl;
        this.modelName = modelName;
        this.systemPrompt = systemPrompt;
    }
}
