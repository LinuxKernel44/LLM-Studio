package com.linuxkernel44.llmstudio.network;

/** One entry of the "messages" array in an OpenAI-compatible chat completions request. */
public class ChatMessagePayload {
    public String role;
    public String content;

    public ChatMessagePayload(String role, String content) {
        this.role = role;
        this.content = content;
    }
}
