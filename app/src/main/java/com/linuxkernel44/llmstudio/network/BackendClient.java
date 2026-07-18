package com.linuxkernel44.llmstudio.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * Talks to the OpenAI-compatible GET /v1/models endpoint - used both to "test" that the backend
 * is reachable (Settings screen) and to list which models are actually loaded, so the user can
 * pick one instead of typing a model name from memory.
 */
public class BackendClient {

    private static final long TIMEOUT_SECONDS = 6;

    private final Gson gson = new Gson();
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .readTimeout(TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .build();

    public interface ModelsCallback {
        /** Called on a background thread; callers must post back to the main thread themselves. */
        void onSuccess(List<String> modelIds);

        void onError(String userFriendlyMessage);
    }

    /** chatCompletionsUrl is the full /v1/chat/completions URL as stored in Settings. */
    public void fetchModels(String chatCompletionsUrl, ModelsCallback callback) {
        String modelsUrl = deriveModelsUrl(chatCompletionsUrl);
        if (modelsUrl == null) {
            callback.onError("That doesn't look like a valid URL.");
            return;
        }

        Request request;
        try {
            request = new Request.Builder().url(modelsUrl).get().build();
        } catch (IllegalArgumentException malformedUrl) {
            callback.onError("That doesn't look like a valid URL.");
            return;
        }

        client.newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(@NonNull Call call, @NonNull IOException e) {
                callback.onError(describeFailure(e, null));
            }

            @Override
            public void onResponse(@NonNull Call call, @NonNull Response response) {
                try (Response r = response) {
                    if (!r.isSuccessful()) {
                        callback.onError(describeFailure(null, r));
                        return;
                    }
                    String body = r.body() != null ? r.body().string() : "";
                    ModelsResponse parsed = gson.fromJson(body, ModelsResponse.class);
                    List<String> ids = new ArrayList<>();
                    if (parsed != null && parsed.data != null) {
                        for (ModelsResponse.ModelInfo info : parsed.data) {
                            if (info != null && info.id != null) {
                                ids.add(info.id);
                            }
                        }
                    }
                    callback.onSuccess(ids);
                } catch (Exception malformedBody) {
                    // The server responded but not with the expected schema - still means it's reachable.
                    callback.onSuccess(new ArrayList<>());
                }
            }
        });
    }

    /** Turns ".../v1/chat/completions" into ".../v1/models"; falls back to appending "/models". */
    @Nullable
    private String deriveModelsUrl(String chatCompletionsUrl) {
        if (chatCompletionsUrl == null || chatCompletionsUrl.trim().isEmpty()) {
            return null;
        }
        String trimmed = chatCompletionsUrl.trim();
        int v1Index = trimmed.indexOf("/v1/");
        if (v1Index >= 0) {
            return trimmed.substring(0, v1Index) + "/v1/models";
        }
        return trimmed.endsWith("/") ? trimmed + "models" : trimmed + "/models";
    }

    private String describeFailure(@Nullable IOException e, @Nullable Response response) {
        if (response != null) {
            return "Backend returned HTTP " + response.code() + ".";
        }
        if (e instanceof java.net.ConnectException || e instanceof java.net.SocketTimeoutException
                || e instanceof java.net.UnknownHostException) {
            return "Can't reach the backend. Make sure WireGuard is connected and the URL is correct.";
        }
        return "Connection failed. Please try again.";
    }
}
