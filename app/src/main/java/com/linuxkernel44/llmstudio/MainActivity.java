package com.linuxkernel44.llmstudio;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.MotionEvent;
import android.view.View;
import android.widget.EditText;
import android.widget.PopupMenu;

import androidx.activity.EdgeToEdge;
import androidx.activity.OnBackPressedCallback;
import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.GravityCompat;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.lifecycle.ViewModelProvider;
import androidx.recyclerview.widget.LinearLayoutManager;

import com.google.android.material.color.MaterialColors;
import com.google.android.material.snackbar.Snackbar;
import com.linuxkernel44.llmstudio.data.ChatMessageEntity;
import com.linuxkernel44.llmstudio.data.ConversationEntity;
import com.linuxkernel44.llmstudio.data.SettingsManager;
import com.linuxkernel44.llmstudio.databinding.ActivityMainBinding;
import com.linuxkernel44.llmstudio.ui.ChatAdapter;
import com.linuxkernel44.llmstudio.ui.ConversationAdapter;
import com.linuxkernel44.llmstudio.viewmodel.ChatViewModel;
import com.linuxkernel44.llmstudio.viewmodel.MicState;

import java.util.List;

public class MainActivity extends AppCompatActivity {

    private ActivityMainBinding binding;
    private ChatViewModel viewModel;
    private ChatAdapter chatAdapter;
    private ConversationAdapter conversationAdapter;
    private SettingsManager settingsManager;

    private boolean continuousMode = false;
    private int idleStatusRes = R.string.status_idle_tap;
    private int appliedThemeMode;

    /** RECORD_AUDIO must be requested at runtime; on grant, a continuous-mode tap is re-issued
     *  (push-to-talk is a hold gesture, so the user simply presses again). */
    private final ActivityResultLauncher<String> micPermissionLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), granted -> {
                if (granted) {
                    if (continuousMode) {
                        viewModel.onContinuousToggle();
                    }
                } else {
                    Snackbar.make(binding.getRoot(), R.string.permission_mic_denied, Snackbar.LENGTH_LONG).show();
                }
            });

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        settingsManager = new SettingsManager(this);
        appliedThemeMode = settingsManager.getThemeMode();
        if (!SettingsManager.isLiquidGlassFamily(appliedThemeMode)) {
            ThemeUtils.applyTheme(this);
        }
        super.onCreate(savedInstanceState);

        if (SettingsManager.isLiquidGlassFamily(appliedThemeMode)) {
            // The Liquid Glass theme is a whole separate Compose-rendered Activity (GlassMainActivity);
            // this classic View-based screen just steps out of the way before it would even inflate.
            startActivity(new Intent(this, GlassMainActivity.class));
            finish();
            return;
        }
        EdgeToEdge.enable(this);
        binding = ActivityMainBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setupImeAwareInsets();

        setSupportActionBar(binding.toolbar);

        viewModel = new ViewModelProvider(this).get(ChatViewModel.class);

        setupMessageList();
        setupDrawer();
        setupInputRow();
        observeViewModel();

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (binding.drawerLayout.isDrawerOpen(GravityCompat.START)) {
                    binding.drawerLayout.closeDrawer(GravityCompat.START);
                } else {
                    setEnabled(false);
                    getOnBackPressedDispatcher().onBackPressed();
                }
            }
        });
    }

    @Override
    protected void onResume() {
        super.onResume();
        int currentThemeMode = settingsManager.getThemeMode();
        if (currentThemeMode != appliedThemeMode) {
            if (SettingsManager.isLiquidGlassFamily(currentThemeMode)) {
                // Switched to Liquid Glass from Settings while this screen was in the back stack.
                startActivity(new Intent(this, GlassMainActivity.class));
                finish();
                return;
            }
            // Dynamic <-> AMOLED: the Activity's already-inflated views won't pick up a new theme
            // without recreating.
            recreate();
            return;
        }
        // Re-read the input mode each time the screen returns, so a change made in Settings takes
        // effect immediately, and reconfigure the mic gesture accordingly.
        applyInputMode();
        // Re-read the voice language too - this only affects speech recognition/TTS, never the UI text.
        viewModel.setVoiceLanguage(settingsManager.getVoiceLocale());
        viewModel.setSpeechRate(settingsManager.getSpeechRateForCurrentVoiceLanguage());
        // Kokoro is English-only; French always forces the system engine regardless of the setting.
        boolean wantsKokoro = settingsManager.isUseKokoroTts()
                && SettingsManager.VOICE_LANGUAGE_ENGLISH_US.equals(settingsManager.getVoiceLanguageTag());
        viewModel.setTtsEngine(wantsKokoro, settingsManager.getKokoroSpeakerId());
    }

    /**
     * With edge-to-edge enabled, the window no longer auto-resizes for the IME (windowSoftInputMode
     * is effectively ignored once decorFitsSystemWindows is false), so the keyboard inset has to be
     * applied by hand. Bottom-padding the root ConstraintLayout works because every bottom element
     * (fabMic, textMicStatus, inputRow, recyclerMessages) is constrained relative to "parent", and
     * ConstraintLayout's parent reference already accounts for the parent's own padding - so the
     * whole bottom chain (input row, mic button, status text) rises together and the RecyclerView's
     * 0dp/match-constraints height shrinks to give it room, with no hardcoded heights anywhere.
     *
     * A WindowInsetsAnimationCompat callback additionally drives that padding on every animation
     * frame the system dispatches, so the resize is smooth and frame-synced with the keyboard's own
     * show/hide animation instead of snapping in one jump.
     */
    private void setupImeAwareInsets() {
        View root = binding.main;

        ViewCompat.setOnApplyWindowInsetsListener(root, (v, insets) -> {
            applyBottomInset(v, insets);
            return insets;
        });

        ViewCompat.setWindowInsetsAnimationCallback(root, new WindowInsetsAnimationCompat.Callback(
                WindowInsetsAnimationCompat.Callback.DISPATCH_MODE_STOP) {
            @Override
            public WindowInsetsCompat onProgress(@androidx.annotation.NonNull WindowInsetsCompat insets,
                                                   @androidx.annotation.NonNull List<WindowInsetsAnimationCompat> runningAnimations) {
                applyBottomInset(root, insets);
                return insets;
            }
        });
    }

    private void applyBottomInset(View view, WindowInsetsCompat insets) {
        Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
        Insets ime = insets.getInsets(WindowInsetsCompat.Type.ime());
        view.setPadding(systemBars.left, systemBars.top, systemBars.right, Math.max(systemBars.bottom, ime.bottom));
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Once setSupportActionBar() is used, the ActionBar owns the toolbar's menu lifecycle -
        // inflating it here (instead of calling toolbar.inflateMenu() directly) is what makes it
        // actually survive and show up, rather than being silently cleared on the next menu sync.
        getMenuInflater().inflate(R.menu.menu_main, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_settings) {
            startActivity(new Intent(this, SettingsActivity.class));
            return true;
        } else if (item.getItemId() == R.id.action_new_chat) {
            viewModel.startNewConversation();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    private void setupMessageList() {
        chatAdapter = new ChatAdapter();
        LinearLayoutManager layoutManager = new LinearLayoutManager(this);
        binding.recyclerMessages.setLayoutManager(layoutManager);
        binding.recyclerMessages.setAdapter(chatAdapter);
    }

    private void setupDrawer() {
        binding.toolbar.setNavigationOnClickListener(v -> binding.drawerLayout.openDrawer(GravityCompat.START));

        conversationAdapter = new ConversationAdapter(new ConversationAdapter.Listener() {
            @Override
            public void onConversationClicked(ConversationEntity conversation) {
                viewModel.switchToConversation(conversation.id);
                binding.drawerLayout.closeDrawer(GravityCompat.START);
            }

            @Override
            public void onConversationMenuClicked(View anchor, ConversationEntity conversation) {
                showConversationMenu(anchor, conversation);
            }
        });
        binding.recyclerConversations.setLayoutManager(new LinearLayoutManager(this));
        binding.recyclerConversations.setAdapter(conversationAdapter);

        binding.buttonNewChat.setOnClickListener(v -> {
            viewModel.startNewConversation();
            binding.drawerLayout.closeDrawer(GravityCompat.START);
        });

        binding.buttonDeleteAllConversations.setOnClickListener(v -> showDeleteAllConversationsDialog());
    }

    private void showDeleteAllConversationsDialog() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.drawer_delete_all_chats_title)
                .setMessage(R.string.drawer_delete_all_chats_message)
                .setPositiveButton(R.string.action_delete, (dialog, which) -> {
                    viewModel.deleteAllConversations();
                    binding.drawerLayout.closeDrawer(GravityCompat.START);
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showConversationMenu(View anchor, ConversationEntity conversation) {
        PopupMenu popup = new PopupMenu(this, anchor, Gravity.END);
        popup.getMenu().add(0, 1, 0, R.string.conversation_menu_rename);
        popup.getMenu().add(0, 2, 1, R.string.conversation_menu_delete);
        popup.setOnMenuItemClickListener(item -> {
            if (item.getItemId() == 1) {
                showRenameConversationDialog(conversation);
                return true;
            } else if (item.getItemId() == 2) {
                showDeleteConversationDialog(conversation);
                return true;
            }
            return false;
        });
        popup.show();
    }

    private void showRenameConversationDialog(ConversationEntity conversation) {
        EditText input = new EditText(this);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(conversation.title);
        input.setSelection(input.getText().length());
        int paddingPx = (int) (20 * getResources().getDisplayMetrics().density);
        input.setPadding(paddingPx, paddingPx / 2, paddingPx, paddingPx / 2);

        new AlertDialog.Builder(this)
                .setTitle(R.string.conversation_rename_title)
                .setView(input)
                .setPositiveButton(R.string.action_ok, (dialog, which) ->
                        viewModel.renameConversation(conversation.id, input.getText().toString()))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showDeleteConversationDialog(ConversationEntity conversation) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.conversation_delete_title)
                .setMessage(getString(R.string.conversation_delete_message, conversation.title))
                .setPositiveButton(R.string.action_delete, (dialog, which) -> viewModel.deleteConversation(conversation.id))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void setupInputRow() {
        binding.buttonSend.setOnClickListener(v -> sendManualMessageFromInput());
        binding.editMessage.setOnEditorActionListener((v, actionId, event) -> {
            sendManualMessageFromInput();
            return true;
        });
    }

    private void sendManualMessageFromInput() {
        EditText editText = binding.editMessage;
        String text = editText.getText().toString();
        if (!text.trim().isEmpty()) {
            viewModel.sendManualMessage(text);
            editText.setText("");
        }
    }

    @SuppressLint("ClickableViewAccessibility")
    private void applyInputMode() {
        continuousMode = settingsManager.isContinuousListening();
        viewModel.setContinuousMode(continuousMode);
        idleStatusRes = continuousMode ? R.string.status_idle_tap : R.string.status_idle_hold;

        if (continuousMode) {
            // Tap to start/stop a hands-free loop.
            binding.fabMic.setOnTouchListener(null);
            binding.fabMic.setOnClickListener(v -> {
                if (hasMicPermission()) {
                    viewModel.onContinuousToggle();
                } else {
                    micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
                }
            });
        } else {
            // Push-to-talk: listen while held, send on release.
            binding.fabMic.setOnClickListener(null);
            binding.fabMic.setOnTouchListener((v, event) -> {
                switch (event.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        if (hasMicPermission()) {
                            viewModel.onPushToTalkStart();
                        } else {
                            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        viewModel.onPushToTalkStop();
                        v.performClick();
                        return true;
                    default:
                        return false;
                }
            });
        }

        // Refresh the idle hint immediately if we're currently idle.
        if (viewModel.getMicState().getValue() == MicState.IDLE) {
            binding.textMicStatus.setText(idleStatusRes);
        }
    }

    private boolean hasMicPermission() {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void observeViewModel() {
        viewModel.getMessages().observe(this, this::onMessagesChanged);
        viewModel.getMicState().observe(this, this::onMicStateChanged);
        viewModel.getPartialTranscript().observe(this, this::onPartialTranscriptChanged);
        viewModel.getErrorEvent().observe(this, message ->
                Snackbar.make(binding.getRoot(), message, Snackbar.LENGTH_LONG).show());
        viewModel.getConversations().observe(this, conversationAdapter::submitList);
        viewModel.getCurrentConversationId().observe(this, id -> {
            if (id != null) {
                conversationAdapter.setActiveConversationId(id);
            }
        });
    }

    private void onMessagesChanged(List<ChatMessageEntity> messages) {
        chatAdapter.submitList(messages);
        if (!messages.isEmpty()) {
            binding.recyclerMessages.scrollToPosition(messages.size() - 1);
        }
    }

    private void onMicStateChanged(MicState state) {
        switch (state) {
            case IDLE:
                binding.fabMic.setImageResource(R.drawable.ic_mic_24);
                binding.textMicStatus.setText(idleStatusRes);
                binding.progressProcessing.setVisibility(View.GONE);
                applyFabColors(com.google.android.material.R.attr.colorPrimaryContainer,
                        com.google.android.material.R.attr.colorOnPrimaryContainer);
                break;
            case LISTENING:
                binding.fabMic.setImageResource(R.drawable.ic_stop_24);
                binding.textMicStatus.setText(R.string.status_listening);
                binding.progressProcessing.setVisibility(View.GONE);
                applyFabColors(com.google.android.material.R.attr.colorErrorContainer,
                        com.google.android.material.R.attr.colorOnErrorContainer);
                break;
            case PROCESSING:
                binding.fabMic.setImageResource(R.drawable.ic_stop_24);
                binding.textMicStatus.setText(R.string.status_processing);
                binding.progressProcessing.setVisibility(View.VISIBLE);
                applyFabColors(com.google.android.material.R.attr.colorPrimaryContainer,
                        com.google.android.material.R.attr.colorOnPrimaryContainer);
                break;
            case SPEAKING:
                binding.fabMic.setImageResource(R.drawable.ic_volume_up_24);
                binding.textMicStatus.setText(R.string.status_speaking);
                binding.progressProcessing.setVisibility(View.GONE);
                applyFabColors(com.google.android.material.R.attr.colorTertiaryContainer,
                        com.google.android.material.R.attr.colorOnTertiaryContainer);
                break;
        }
    }

    /** Tints the FAB background and its icon per state so idle/listening/processing/speaking are
     *  visually distinct; icon color always uses the matching "on-" role for guaranteed contrast. */
    private void applyFabColors(int backgroundAttr, int iconAttr) {
        int backgroundColor = MaterialColors.getColor(binding.fabMic, backgroundAttr);
        int iconColor = MaterialColors.getColor(binding.fabMic, iconAttr);
        binding.fabMic.setBackgroundTintList(ColorStateList.valueOf(backgroundColor));
        binding.fabMic.setImageTintList(ColorStateList.valueOf(iconColor));
    }

    private void onPartialTranscriptChanged(String partialText) {
        boolean hasText = partialText != null && !partialText.isEmpty();
        binding.textPartialTranscript.setVisibility(hasText ? View.VISIBLE : View.GONE);
        binding.textPartialTranscript.setText(partialText);
    }
}
