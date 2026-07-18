package com.linuxkernel44.llmstudio.network;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.google.gson.Gson;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;

import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.sse.EventSource;
import okhttp3.sse.EventSourceListener;
import okhttp3.sse.EventSources;

/**
 * Talks to an OpenAI-compatible /v1/chat/completions endpoint using Server-Sent Events.
 * Retrofit has no first-class SSE support, so this uses OkHttp's okhttp-sse extension directly:
 * each "data: {json}" frame the server sends becomes one onEvent() callback with a JSON chunk
 * that carries a small delta of the assistant's reply (streaming token-by-token).
 */
public class StreamingChatClient {

    /** Generous read timeout: llama.cpp can take a while to produce the first token on a big prompt. */
    private static final long CONNECT_TIMEOUT_SECONDS = 10;
    private static final long READ_TIMEOUT_SECONDS = 120;

    private final Gson gson = new Gson();
    private final OkHttpClient client;

    public interface StreamListener {
        void onStart();

        void onTokenReceived(String contentDelta);

        void onComplete();

        /** userFriendlyMessage is already suitable for direct display (e.g. "VPN appears to be down"). */
        void onError(String userFriendlyMessage);
    }

    public StreamingChatClient() {
        client = new OkHttpClient.Builder()
                .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .writeTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                .build();
    }

    /** Returns the EventSource so the caller can cancel() it to abort an in-flight stream. */
    public EventSource streamChatCompletion(String endpointUrl,
                                             @Nullable String modelName,
                                             List<ChatMessagePayload> messages,
                                             StreamListener listener) {
        ChatCompletionRequest requestBody = new ChatCompletionRequest(
                (modelName == null || modelName.trim().isEmpty()) ? null : modelName,
                messages,
                true);
        String json = gson.toJson(requestBody);

        Request request;
        try {
            request = new Request.Builder()
                    .url(endpointUrl)
                    .header("Accept", "text/event-stream")
                    .post(RequestBody.create(json, MediaType.get("application/json")))
                    .build();
        } catch (IllegalArgumentException malformedUrl) {
            listener.onError("Invalid backend URL. Check it in Settings.");
            return null;
        }

        EventSourceListener sseListener = new EventSourceListener() {
            private boolean startedStreaming = false;
            // Guards every terminal callback (onComplete/onError) so exactly one fires per stream.
            // All okhttp-sse callbacks arrive serialized on the same dispatcher thread, so a plain
            // boolean is sufficient here (no synchronization needed).
            private boolean finished = false;

            @Override
            public void onOpen(@NonNull EventSource eventSource, @NonNull Response response) {
                listener.onStart();
            }

            @Override
            public void onEvent(@NonNull EventSource eventSource, @Nullable String id,
                                 @Nullable String type, @NonNull String data) {
                if (finished) {
                    return;
                }
                // llama-server (like OpenAI) terminates the stream with a literal "[DONE]" event.
                if ("[DONE]".equals(data.trim())) {
                    complete();
                    return;
                }
                try {
                    ChatCompletionChunk chunk = gson.fromJson(data, ChatCompletionChunk.class);
                    if (chunk != null && chunk.choices != null && !chunk.choices.isEmpty()) {
                        ChatCompletionChunk.Delta delta = chunk.choices.get(0).delta;
                        if (delta != null && delta.content != null && !delta.content.isEmpty()) {
                            startedStreaming = true;
                            listener.onTokenReceived(delta.content);
                        }
                    }
                } catch (Exception malformedChunk) {
                    // Skip unparseable keep-alive/comment frames rather than aborting the whole reply.
                }
            }

            @Override
            public void onClosed(@NonNull EventSource eventSource) {
                // The server closes the connection immediately after the "[DONE]" event, so without
                // the `finished` guard onComplete() would fire a SECOND time here - re-flushing the
                // reply's trailing text to the TTS engine and speaking the end of the answer twice.
                if (startedStreaming) {
                    complete();
                }
            }

            @Override
            public void onFailure(@NonNull EventSource eventSource, @Nullable Throwable t, @Nullable Response response) {
                if (finished) {
                    return;
                }
                finished = true;
                listener.onError(describeFailure(t, response));
            }

            private void complete() {
                if (finished) {
                    return;
                }
                finished = true;
                listener.onComplete();
            }
        };

        return EventSources.createFactory(client).newEventSource(request, sseListener);
    }

    private String describeFailure(@Nullable Throwable t, @Nullable Response response) {
        if (response != null && !response.isSuccessful()) {
            return "Backend returned an error (HTTP " + response.code() + "). Check the model is loaded on the server.";
        }
        if (t instanceof java.net.ConnectException || t instanceof java.net.SocketTimeoutException
                || t instanceof java.net.UnknownHostException) {
            return "Can't reach the backend. Make sure WireGuard is connected and the URL in Settings is correct.";
        }
        return "Connection lost while talking to the backend. Please try again.";
    }
}
