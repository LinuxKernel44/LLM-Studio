package com.linuxkernel44.llmstudio.network;

import com.google.gson.annotations.SerializedName;

import java.util.List;

public class ChatCompletionRequest {

    /** Optional: llama-server ignores this if only one model is loaded, but we send it if set. */
    @SerializedName("model")
    public String model;

    @SerializedName("messages")
    public List<ChatMessagePayload> messages;

    @SerializedName("stream")
    public boolean stream;

    public ChatCompletionRequest(String model, List<ChatMessagePayload> messages, boolean stream) {
        this.model = model;
        this.messages = messages;
        this.stream = stream;
    }
}
