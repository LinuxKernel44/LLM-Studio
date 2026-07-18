package com.linuxkernel44.llmstudio.network;

import java.util.List;

/** One "data: {...}" SSE event from /v1/chat/completions with "stream": true. */
public class ChatCompletionChunk {
    public String id;
    public List<ChunkChoice> choices;

    public static class ChunkChoice {
        public int index;
        public Delta delta;
        public String finish_reason;
    }

    public static class Delta {
        public String role;
        public String content;
    }
}
