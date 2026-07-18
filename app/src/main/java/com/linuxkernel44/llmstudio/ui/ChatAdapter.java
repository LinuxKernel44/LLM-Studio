package com.linuxkernel44.llmstudio.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.linuxkernel44.llmstudio.R;
import com.linuxkernel44.llmstudio.data.ChatMessageEntity;
import com.linuxkernel44.llmstudio.data.MessageRole;

import java.util.ArrayList;
import java.util.List;

/** Two view types: user bubbles (right-aligned) and assistant bubbles (left-aligned). */
public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.MessageViewHolder> {

    private static final int VIEW_TYPE_USER = 0;
    private static final int VIEW_TYPE_ASSISTANT = 1;

    private List<ChatMessageEntity> messages = new ArrayList<>();

    public void submitList(List<ChatMessageEntity> newMessages) {
        List<ChatMessageEntity> old = messages;
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return old.size();
            }

            @Override
            public int getNewListSize() {
                return newMessages.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return old.get(oldItemPosition).id == newMessages.get(newItemPosition).id;
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                ChatMessageEntity a = old.get(oldItemPosition);
                ChatMessageEntity b = newMessages.get(newItemPosition);
                return a.content.equals(b.content) && a.isStreaming == b.isStreaming;
            }
        });
        messages = newMessages;
        diffResult.dispatchUpdatesTo(this);
    }

    @Override
    public int getItemViewType(int position) {
        return messages.get(position).role == MessageRole.USER ? VIEW_TYPE_USER : VIEW_TYPE_ASSISTANT;
    }

    @NonNull
    @Override
    public MessageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layoutRes = viewType == VIEW_TYPE_USER ? R.layout.item_message_user : R.layout.item_message_assistant;
        View view = LayoutInflater.from(parent.getContext()).inflate(layoutRes, parent, false);
        return new MessageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull MessageViewHolder holder, int position) {
        ChatMessageEntity message = messages.get(position);
        String text = message.content;
        if (message.isStreaming && text.isEmpty()) {
            text = holder.itemView.getContext().getString(R.string.status_thinking);
        }
        holder.textMessageBody.setText(text);
    }

    @Override
    public int getItemCount() {
        return messages.size();
    }

    static class MessageViewHolder extends RecyclerView.ViewHolder {
        final TextView textMessageBody;

        MessageViewHolder(@NonNull View itemView) {
            super(itemView);
            textMessageBody = itemView.findViewById(R.id.textMessageBody);
        }
    }
}
