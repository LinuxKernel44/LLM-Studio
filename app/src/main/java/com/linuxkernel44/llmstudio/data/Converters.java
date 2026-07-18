package com.linuxkernel44.llmstudio.data;

import androidx.room.TypeConverter;

public class Converters {

    @TypeConverter
    public static String fromRole(MessageRole role) {
        return role == null ? null : role.name();
    }

    @TypeConverter
    public static MessageRole toRole(String value) {
        return value == null ? null : MessageRole.valueOf(value);
    }
}
