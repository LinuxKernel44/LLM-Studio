package com.linuxkernel44.llmstudio.ui;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.color.MaterialColors;
import com.linuxkernel44.llmstudio.R;
import com.linuxkernel44.llmstudio.data.ConversationEntity;

import java.util.ArrayList;
import java.util.List;

/** The drawer's conversation list - one row per conversation, most-recent-first (see ConversationDao). */
public class ConversationAdapter extends RecyclerView.Adapter<ConversationAdapter.ConversationViewHolder> {

    public interface Listener {
        void onConversationClicked(ConversationEntity conversation);

        void onConversationMenuClicked(View anchor, ConversationEntity conversation);
    }

    private List<ConversationEntity> conversations = new ArrayList<>();
    private long activeConversationId = -1;
    private final Listener listener;

    public ConversationAdapter(Listener listener) {
        this.listener = listener;
    }

    public void submitList(List<ConversationEntity> newConversations) {
        List<ConversationEntity> old = conversations;
        DiffUtil.DiffResult diffResult = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override
            public int getOldListSize() {
                return old.size();
            }

            @Override
            public int getNewListSize() {
                return newConversations.size();
            }

            @Override
            public boolean areItemsTheSame(int oldItemPosition, int newItemPosition) {
                return old.get(oldItemPosition).id == newConversations.get(newItemPosition).id;
            }

            @Override
            public boolean areContentsTheSame(int oldItemPosition, int newItemPosition) {
                return old.get(oldItemPosition).title.equals(newConversations.get(newItemPosition).title);
            }
        });
        conversations = newConversations;
        diffResult.dispatchUpdatesTo(this);
    }

    /** Re-binds the whole list to refresh the highlighted row; conversation counts are small enough
     *  (personal chat history) that this is simpler and cheap compared to tracking old/new positions. */
    public void setActiveConversationId(long id) {
        if (activeConversationId == id) {
            return;
        }
        activeConversationId = id;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ConversationViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_conversation, parent, false);
        return new ConversationViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ConversationViewHolder holder, int position) {
        ConversationEntity conversation = conversations.get(position);
        holder.textTitle.setText(conversation.title);

        boolean isActive = conversation.id == activeConversationId;
        int backgroundColor = isActive
                ? MaterialColors.getColor(holder.itemView, com.google.android.material.R.attr.colorSecondaryContainer)
                : android.graphics.Color.TRANSPARENT;
        holder.itemView.setBackgroundColor(backgroundColor);

        holder.itemView.setOnClickListener(v -> listener.onConversationClicked(conversation));
        holder.buttonMenu.setOnClickListener(v -> listener.onConversationMenuClicked(v, conversation));
    }

    @Override
    public int getItemCount() {
        return conversations.size();
    }

    static class ConversationViewHolder extends RecyclerView.ViewHolder {
        final TextView textTitle;
        final ImageButton buttonMenu;

        ConversationViewHolder(@NonNull View itemView) {
            super(itemView);
            textTitle = itemView.findViewById(R.id.textConversationTitle);
            buttonMenu = itemView.findViewById(R.id.buttonConversationMenu);
        }
    }
}
