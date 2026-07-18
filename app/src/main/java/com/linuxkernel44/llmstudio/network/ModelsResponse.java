package com.linuxkernel44.llmstudio.network;

import java.util.List;

/** Response body of the OpenAI-compatible GET /v1/models endpoint. */
public class ModelsResponse {
    public List<ModelInfo> data;

    public static class ModelInfo {
        public String id;
    }
}
