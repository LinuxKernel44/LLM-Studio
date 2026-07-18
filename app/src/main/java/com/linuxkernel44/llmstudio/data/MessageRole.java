package com.linuxkernel44.llmstudio.data;

/** Mirrors the "role" field of the OpenAI chat completions schema. */
public enum MessageRole {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant");

    private final String apiValue;

    MessageRole(String apiValue) {
        this.apiValue = apiValue;
    }

    public String getApiValue() {
        return apiValue;
    }
}
