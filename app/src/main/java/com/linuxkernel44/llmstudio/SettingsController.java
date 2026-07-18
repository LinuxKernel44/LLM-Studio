package com.linuxkernel44.llmstudio;

import android.text.InputType;
import android.text.TextUtils;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.google.android.material.snackbar.Snackbar;
import com.linuxkernel44.llmstudio.data.KokoroModelManager;
import com.linuxkernel44.llmstudio.data.ProfileEntity;
import com.linuxkernel44.llmstudio.data.SettingsManager;
import com.linuxkernel44.llmstudio.databinding.ActivitySettingsBinding;
import com.linuxkernel44.llmstudio.network.BackendClient;
import com.linuxkernel44.llmstudio.repository.ConversationRepository;
import com.linuxkernel44.llmstudio.repository.ProfileRepository;
import com.linuxkernel44.llmstudio.speech.VoiceModelHelper;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * All of the Settings screen's actual behavior (profile CRUD, test connection, model picker, save,
 * clear history), extracted out of any one Activity so both {@link SettingsActivity} (classic
 * View screen) and {@link GlassSettingsActivity} (Liquid Glass theme) can drive the exact same
 * {@link ActivitySettingsBinding} form fields without duplicating this logic in two places.
 */
public class SettingsController {

    /** Reports a theme change so the host Activity can decide whether to recreate/redirect. */
    public interface ThemeChangeListener {
        void onThemeModeChanged(int oldMode, int newMode);
    }

    private final AppCompatActivity activity;
    private final ActivitySettingsBinding binding;
    private final ThemeChangeListener themeChangeListener;
    private final SettingsManager settingsManager;
    private final ProfileRepository profileRepository;
    private final ConversationRepository conversationRepository;
    private final BackendClient backendClient = new BackendClient();

    /** Null when the device has no guaranteed on-device recognizer to query (pre-API 33 / unsupported). */
    private VoiceModelHelper voiceModelHelper;
    private final KokoroModelManager kokoroModelManager = new KokoroModelManager();
    private int selectedKokoroSpeakerId;

    private int appliedThemeMode;

    /** Populated by "Test connection" / "Choose model"; used so picking a model doesn't always re-fetch. */
    private List<String> cachedModelIds = new ArrayList<>();

    /** Mirrors the profile dropdown's current items, in display order. */
    private List<ProfileEntity> profilesCache = new ArrayList<>();
    private long currentProfileId = SettingsManager.NO_ID;

    public SettingsController(AppCompatActivity activity, ActivitySettingsBinding binding,
                               ThemeChangeListener themeChangeListener) {
        this.activity = activity;
        this.binding = binding;
        this.themeChangeListener = themeChangeListener;
        this.settingsManager = new SettingsManager(activity);
        this.profileRepository = new ProfileRepository(activity);
        this.conversationRepository = new ConversationRepository(activity);
        this.appliedThemeMode = settingsManager.getThemeMode();
    }

    /** Wires every field/button; call once after the binding's views exist. */
    public void setup() {
        binding.groupInputMode.check(settingsManager.isContinuousListening()
                ? R.id.radioContinuous : R.id.radioPushToTalk);

        binding.groupVoiceLanguage.check(SettingsManager.VOICE_LANGUAGE_FRENCH.equals(settingsManager.getVoiceLanguageTag())
                ? R.id.radioLanguageFrench : R.id.radioLanguageEnglish);
        setupVoiceModelControls();

        loadSpeechRateIntoSlider(selectedVoiceLocale());
        binding.sliderSpeechRate.addOnChangeListener((slider, value, fromUser) ->
                binding.textSpeechRateLabel.setText(activity.getString(R.string.settings_speech_rate_label, value)));

        setupKokoroControls();

        int themeRadioId;
        if (appliedThemeMode == SettingsManager.THEME_MODE_AMOLED) {
            themeRadioId = R.id.radioThemeAmoled;
        } else if (appliedThemeMode == SettingsManager.THEME_MODE_LIQUID_GLASS) {
            themeRadioId = R.id.radioThemeGlass;
        } else if (appliedThemeMode == SettingsManager.THEME_MODE_OLED_LIQUID_GLASS) {
            themeRadioId = R.id.radioThemeOledGlass;
        } else {
            themeRadioId = R.id.radioThemeDynamic;
        }
        binding.groupTheme.check(themeRadioId);
        binding.switchResumeConversation.setChecked(settingsManager.isResumeLastConversation());

        setupProfileControls();

        binding.buttonSave.setOnClickListener(v -> saveSettings());
        binding.buttonClearHistory.setOnClickListener(v -> clearHistory());
        binding.buttonTestConnection.setOnClickListener(v -> testConnection());
        binding.buttonSelectModel.setOnClickListener(v -> onSelectModelClicked());
    }

    /** Frees the recognizer used for model checks; call from the host Activity's onDestroy. */
    public void release() {
        if (voiceModelHelper != null) {
            voiceModelHelper.destroy();
            voiceModelHelper = null;
        }
    }

    // ---------------------------------------------------------------------------------------------
    // On-device voice model status + download. These models belong to the system speech service and
    // are separate from keyboard/system language packs, so we query and download them explicitly.
    // ---------------------------------------------------------------------------------------------

    private void setupVoiceModelControls() {
        // Single listener for the whole radio group - the model status, speech-rate slider, and
        // Kokoro availability all need to react to a language switch, and RadioGroup only allows
        // one listener.
        binding.groupVoiceLanguage.setOnCheckedChangeListener((group, checkedId) -> {
            loadSpeechRateIntoSlider(selectedVoiceLocale());
            refreshVoiceModelStatus();
            refreshKokoroAvailability();
        });

        if (!VoiceModelHelper.isSupported(activity)) {
            binding.textOnDeviceStatus.setText(R.string.settings_on_device_unavailable);
            binding.buttonDownloadVoiceModel.setVisibility(View.GONE);
            return;
        }
        voiceModelHelper = new VoiceModelHelper(activity);
        binding.buttonDownloadVoiceModel.setOnClickListener(v -> downloadVoiceModel());
        refreshVoiceModelStatus();
    }

    /** Reloads the slider (and its label) to show the given language's own saved speech rate. */
    private void loadSpeechRateIntoSlider(Locale locale) {
        float rate = settingsManager.getSpeechRate(locale.toLanguageTag());
        binding.sliderSpeechRate.setValue(rate);
        binding.textSpeechRateLabel.setText(activity.getString(R.string.settings_speech_rate_label, rate));
    }

    /** The language currently selected in the radio group (not necessarily the saved one). */
    private Locale selectedVoiceLocale() {
        return Locale.forLanguageTag(binding.groupVoiceLanguage.getCheckedRadioButtonId() == R.id.radioLanguageFrench
                ? SettingsManager.VOICE_LANGUAGE_FRENCH : SettingsManager.VOICE_LANGUAGE_ENGLISH_US);
    }

    private void refreshVoiceModelStatus() {
        if (voiceModelHelper == null) {
            return;
        }
        Locale locale = selectedVoiceLocale();
        binding.textOnDeviceStatus.setText(R.string.settings_voice_model_checking);

        voiceModelHelper.checkSupport(locale, new VoiceModelHelper.SupportCallback() {
            @Override
            public void onSupportResult(List<String> installed, List<String> downloadable, List<String> pending) {
                activity.runOnUiThread(() -> {
                    if (matchesLanguage(installed, locale)) {
                        binding.textOnDeviceStatus.setText(activity.getString(
                                R.string.settings_voice_model_installed, String.join(", ", installed)));
                        binding.buttonDownloadVoiceModel.setVisibility(View.GONE);
                    } else if (matchesLanguage(pending, locale)) {
                        binding.textOnDeviceStatus.setText(activity.getString(
                                R.string.settings_voice_model_pending, String.join(", ", pending)));
                        binding.buttonDownloadVoiceModel.setVisibility(View.GONE);
                    } else {
                        binding.textOnDeviceStatus.setText(R.string.settings_voice_model_missing);
                        binding.buttonDownloadVoiceModel.setVisibility(View.VISIBLE);
                    }
                });
            }

            @Override
            public void onError(String message) {
                activity.runOnUiThread(() -> {
                    binding.textOnDeviceStatus.setText(message);
                    binding.buttonDownloadVoiceModel.setVisibility(View.VISIBLE);
                });
            }
        });
    }

    /** The recognizer may report "fr" where we asked for "fr-FR" (or vice versa), so compare loosely. */
    private static boolean matchesLanguage(List<String> tags, Locale locale) {
        String language = locale.getLanguage();
        for (String tag : tags) {
            if (tag != null && Locale.forLanguageTag(tag).getLanguage().equals(language)) {
                return true;
            }
        }
        return false;
    }

    private void downloadVoiceModel() {
        if (voiceModelHelper == null) {
            return;
        }
        binding.buttonDownloadVoiceModel.setEnabled(false);
        voiceModelHelper.triggerDownload(selectedVoiceLocale(), new VoiceModelHelper.DownloadCallback() {
            @Override
            public void onProgress(int percent) {
                activity.runOnUiThread(() ->
                        binding.textOnDeviceStatus.setText(activity.getString(R.string.settings_download_progress, percent)));
            }

            @Override
            public void onCompleted() {
                activity.runOnUiThread(() -> {
                    binding.buttonDownloadVoiceModel.setEnabled(true);
                    binding.textOnDeviceStatus.setText(R.string.settings_download_done);
                    binding.buttonDownloadVoiceModel.setVisibility(View.GONE);
                });
            }

            @Override
            public void onScheduled() {
                activity.runOnUiThread(() -> {
                    binding.buttonDownloadVoiceModel.setEnabled(true);
                    Snackbar.make(binding.getRoot(), R.string.settings_download_started, Snackbar.LENGTH_LONG).show();
                });
            }

            @Override
            public void onFailed(String message) {
                activity.runOnUiThread(() -> {
                    binding.buttonDownloadVoiceModel.setEnabled(true);
                    binding.textOnDeviceStatus.setText(message);
                });
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Kokoro local neural TTS: English-only, so it's forced off (and disabled here) whenever the
    // French radio above is selected - ChatViewModel enforces the same rule independently too.
    // ---------------------------------------------------------------------------------------------

    private void setupKokoroControls() {
        List<String> voiceNames = Arrays.asList(KokoroModelManager.VOICE_NAMES);
        binding.dropdownKokoroVoice.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, voiceNames));
        selectedKokoroSpeakerId = clampSpeakerId(settingsManager.getKokoroSpeakerId());
        binding.dropdownKokoroVoice.setText(voiceNames.get(selectedKokoroSpeakerId), false);
        binding.dropdownKokoroVoice.setOnItemClickListener((AdapterView<?> parent, View view, int position, long id) ->
                selectedKokoroSpeakerId = position);

        binding.groupTtsEngine.check(settingsManager.isUseKokoroTts() ? R.id.radioTtsKokoro : R.id.radioTtsSystem);
        binding.buttonDownloadKokoro.setOnClickListener(v -> downloadKokoroModel());

        refreshKokoroAvailability();
    }

    private static int clampSpeakerId(int id) {
        return Math.max(0, Math.min(id, KokoroModelManager.VOICE_NAMES.length - 1));
    }

    /** Kokoro-en-v0_19 only has English voices, so everything here is gated on the language radio. */
    private void refreshKokoroAvailability() {
        boolean isEnglish = !SettingsManager.VOICE_LANGUAGE_FRENCH.equals(
                selectedVoiceLocale().toLanguageTag());

        binding.radioTtsKokoro.setEnabled(isEnglish);
        if (!isEnglish) {
            if (binding.groupTtsEngine.getCheckedRadioButtonId() == R.id.radioTtsKokoro) {
                binding.groupTtsEngine.check(R.id.radioTtsSystem);
            }
            binding.textKokoroStatus.setText(R.string.settings_kokoro_french_unavailable);
            binding.buttonDownloadKokoro.setVisibility(View.GONE);
            binding.layoutKokoroVoice.setVisibility(View.GONE);
            return;
        }

        binding.layoutKokoroVoice.setVisibility(View.VISIBLE);
        if (KokoroModelManager.isModelReady(activity)) {
            binding.textKokoroStatus.setText(R.string.settings_kokoro_ready);
            binding.buttonDownloadKokoro.setVisibility(View.GONE);
        } else {
            binding.textKokoroStatus.setText(R.string.settings_kokoro_not_downloaded);
            binding.buttonDownloadKokoro.setVisibility(View.VISIBLE);
        }
    }

    private void downloadKokoroModel() {
        binding.buttonDownloadKokoro.setEnabled(false);
        kokoroModelManager.download(activity, new KokoroModelManager.DownloadCallback() {
            @Override
            public void onProgress(int percent) {
                binding.textKokoroStatus.setText(activity.getString(R.string.settings_kokoro_downloading, percent));
            }

            @Override
            public void onExtracting() {
                binding.textKokoroStatus.setText(R.string.settings_kokoro_extracting);
            }

            @Override
            public void onCompleted() {
                binding.buttonDownloadKokoro.setEnabled(true);
                binding.textKokoroStatus.setText(R.string.settings_kokoro_download_done);
                binding.buttonDownloadKokoro.setVisibility(View.GONE);
                Snackbar.make(binding.getRoot(), R.string.settings_kokoro_download_done, Snackbar.LENGTH_SHORT).show();
            }

            @Override
            public void onError(String message) {
                binding.buttonDownloadKokoro.setEnabled(true);
                binding.textKokoroStatus.setText(message);
            }
        });
    }

    // ---------------------------------------------------------------------------------------------
    // Profile management: a dropdown of all profiles, plus new/rename/delete, all backed by Room.
    // ---------------------------------------------------------------------------------------------

    private void setupProfileControls() {
        profileRepository.observeProfiles().observe(activity, this::onProfilesChanged);

        binding.dropdownProfile.setOnItemClickListener((parent, view, position, id) -> {
            ProfileEntity selected = profilesCache.get(position);
            profileRepository.setActiveProfile(selected.id);
            loadProfileIntoFields(selected);
        });

        binding.buttonNewProfile.setOnClickListener(v -> showNewProfileDialog());
        binding.buttonRenameProfile.setOnClickListener(v -> showRenameProfileDialog());
        binding.buttonDeleteProfile.setOnClickListener(v -> showDeleteProfileDialog());
    }

    private void onProfilesChanged(List<ProfileEntity> profiles) {
        profilesCache = profiles;
        binding.buttonDeleteProfile.setEnabled(profiles.size() > 1);
        if (profiles.isEmpty()) {
            return;
        }
        long activeId = profileRepository.getActiveProfileId();
        ProfileEntity active = null;
        List<String> names = new ArrayList<>();
        for (ProfileEntity profile : profiles) {
            names.add(profile.name);
            if (profile.id == activeId) {
                active = profile;
            }
        }
        if (active == null) {
            active = profiles.get(0);
        }
        binding.dropdownProfile.setAdapter(new ArrayAdapter<>(activity, android.R.layout.simple_list_item_1, names));
        binding.dropdownProfile.setText(active.name, false);
        loadProfileIntoFields(active);
    }

    private void loadProfileIntoFields(ProfileEntity profile) {
        currentProfileId = profile.id;
        binding.editEndpointUrl.setText(profile.endpointUrl);
        binding.editModelName.setText(profile.modelName);
        binding.editSystemPrompt.setText(profile.systemPrompt);
        binding.layoutEndpointUrl.setError(null);
        binding.textConnectionStatus.setVisibility(View.GONE);
        // A different server likely serves different models - the previous profile's cached list
        // would otherwise let "Choose model" show stale entries without a fresh Test/fetch.
        cachedModelIds = new ArrayList<>();
    }

    private void showNewProfileDialog() {
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setHint(R.string.settings_profile_new_name_hint);
        applyDialogInputPadding(input);

        new AlertDialog.Builder(activity)
                .setTitle(R.string.settings_profile_new_title)
                .setView(input)
                .setPositiveButton(R.string.action_ok, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        profileRepository.createProfile(name, () -> { });
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showRenameProfileDialog() {
        ProfileEntity current = findCurrentProfile();
        if (current == null) {
            return;
        }
        EditText input = new EditText(activity);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setText(current.name);
        input.setSelection(input.getText().length());
        applyDialogInputPadding(input);

        new AlertDialog.Builder(activity)
                .setTitle(R.string.settings_profile_rename_title)
                .setView(input)
                .setPositiveButton(R.string.action_ok, (dialog, which) -> {
                    String name = input.getText().toString().trim();
                    if (!name.isEmpty()) {
                        profileRepository.renameProfile(current.id, name);
                    }
                })
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private void showDeleteProfileDialog() {
        if (profilesCache.size() <= 1) {
            Snackbar.make(binding.getRoot(), R.string.settings_profile_delete_last_error, Snackbar.LENGTH_SHORT).show();
            return;
        }
        ProfileEntity current = findCurrentProfile();
        if (current == null) {
            return;
        }
        new AlertDialog.Builder(activity)
                .setTitle(R.string.settings_profile_delete_title)
                .setMessage(activity.getString(R.string.settings_profile_delete_message, current.name))
                .setPositiveButton(R.string.action_delete, (dialog, which) ->
                        profileRepository.deleteProfile(current.id, () -> { }))
                .setNegativeButton(R.string.action_cancel, null)
                .show();
    }

    private ProfileEntity findCurrentProfile() {
        for (ProfileEntity profile : profilesCache) {
            if (profile.id == currentProfileId) {
                return profile;
            }
        }
        return null;
    }

    private void applyDialogInputPadding(EditText input) {
        int paddingPx = (int) (20 * activity.getResources().getDisplayMetrics().density);
        input.setPadding(paddingPx, paddingPx / 2, paddingPx, paddingPx / 2);
    }

    private void saveSettings() {
        String url = binding.editEndpointUrl.getText() == null ? "" : binding.editEndpointUrl.getText().toString().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            binding.layoutEndpointUrl.setError(activity.getString(R.string.settings_endpoint_error));
            return;
        }
        binding.layoutEndpointUrl.setError(null);

        String model = binding.editModelName.getText() == null ? "" : binding.editModelName.getText().toString().trim();
        String systemPrompt = binding.editSystemPrompt.getText() == null ? "" : binding.editSystemPrompt.getText().toString();

        ProfileEntity current = findCurrentProfile();
        if (current != null) {
            current.endpointUrl = url;
            current.modelName = model;
            current.systemPrompt = TextUtils.isEmpty(systemPrompt) ? "" : systemPrompt;
            profileRepository.updateProfile(current);
        }

        settingsManager.setContinuousListening(binding.groupInputMode.getCheckedRadioButtonId() == R.id.radioContinuous);
        settingsManager.setResumeLastConversation(binding.switchResumeConversation.isChecked());
        String voiceLanguageTag = binding.groupVoiceLanguage.getCheckedRadioButtonId() == R.id.radioLanguageFrench
                ? SettingsManager.VOICE_LANGUAGE_FRENCH : SettingsManager.VOICE_LANGUAGE_ENGLISH_US;
        settingsManager.setVoiceLanguageTag(voiceLanguageTag);
        settingsManager.setSpeechRate(voiceLanguageTag, binding.sliderSpeechRate.getValue());
        settingsManager.setUseKokoroTts(binding.groupTtsEngine.getCheckedRadioButtonId() == R.id.radioTtsKokoro);
        settingsManager.setKokoroSpeakerId(selectedKokoroSpeakerId);

        int selectedRadioId = binding.groupTheme.getCheckedRadioButtonId();
        int newThemeMode;
        if (selectedRadioId == R.id.radioThemeAmoled) {
            newThemeMode = SettingsManager.THEME_MODE_AMOLED;
        } else if (selectedRadioId == R.id.radioThemeGlass) {
            newThemeMode = SettingsManager.THEME_MODE_LIQUID_GLASS;
        } else if (selectedRadioId == R.id.radioThemeOledGlass) {
            newThemeMode = SettingsManager.THEME_MODE_OLED_LIQUID_GLASS;
        } else {
            newThemeMode = SettingsManager.THEME_MODE_DYNAMIC;
        }
        settingsManager.setThemeMode(newThemeMode);

        Snackbar.make(binding.getRoot(), R.string.settings_saved, Snackbar.LENGTH_SHORT).show();

        int oldMode = appliedThemeMode;
        appliedThemeMode = newThemeMode;
        if (oldMode != newThemeMode) {
            themeChangeListener.onThemeModeChanged(oldMode, newThemeMode);
        }
    }

    /** Hits GET /v1/models on whatever URL is currently typed (not necessarily saved yet). */
    private void testConnection() {
        String url = currentEndpointUrlOrShowError();
        if (url == null) {
            return;
        }
        binding.buttonTestConnection.setEnabled(false);
        binding.textConnectionStatus.setVisibility(View.VISIBLE);
        binding.textConnectionStatus.setTextColor(androidx.core.content.ContextCompat.getColor(activity, R.color.status_success));
        binding.textConnectionStatus.setText(R.string.settings_testing_connection);

        backendClient.fetchModels(url, new BackendClient.ModelsCallback() {
            @Override
            public void onSuccess(List<String> modelIds) {
                activity.runOnUiThread(() -> {
                    binding.buttonTestConnection.setEnabled(true);
                    cachedModelIds = modelIds;
                    binding.textConnectionStatus.setTextColor(androidx.core.content.ContextCompat.getColor(activity, R.color.status_success));
                    binding.textConnectionStatus.setText(modelIds.isEmpty()
                            ? activity.getString(R.string.settings_test_success_no_models)
                            : activity.getString(R.string.settings_test_success_with_models, modelIds.size()));
                });
            }

            @Override
            public void onError(String userFriendlyMessage) {
                activity.runOnUiThread(() -> {
                    binding.buttonTestConnection.setEnabled(true);
                    cachedModelIds = new ArrayList<>();
                    int errorColor = com.google.android.material.color.MaterialColors.getColor(
                            binding.textConnectionStatus, android.R.attr.colorError);
                    binding.textConnectionStatus.setTextColor(errorColor);
                    binding.textConnectionStatus.setText(userFriendlyMessage);
                });
            }
        });
    }

    /** Opens a picker over the cached model list, fetching first if nothing has been fetched yet. */
    private void onSelectModelClicked() {
        if (!cachedModelIds.isEmpty()) {
            showModelPickerDialog(cachedModelIds);
            return;
        }
        String url = currentEndpointUrlOrShowError();
        if (url == null) {
            return;
        }
        binding.buttonSelectModel.setEnabled(false);
        Snackbar.make(binding.getRoot(), R.string.settings_select_model_fetching, Snackbar.LENGTH_SHORT).show();

        backendClient.fetchModels(url, new BackendClient.ModelsCallback() {
            @Override
            public void onSuccess(List<String> modelIds) {
                activity.runOnUiThread(() -> {
                    binding.buttonSelectModel.setEnabled(true);
                    cachedModelIds = modelIds;
                    if (modelIds.isEmpty()) {
                        Snackbar.make(binding.getRoot(), R.string.settings_select_model_empty, Snackbar.LENGTH_LONG).show();
                    } else {
                        showModelPickerDialog(modelIds);
                    }
                });
            }

            @Override
            public void onError(String userFriendlyMessage) {
                activity.runOnUiThread(() ->
                        Snackbar.make(binding.getRoot(), userFriendlyMessage, Snackbar.LENGTH_LONG).show());
            }
        });
    }

    private void showModelPickerDialog(List<String> modelIds) {
        CharSequence[] items = modelIds.toArray(new CharSequence[0]);
        new AlertDialog.Builder(activity)
                .setTitle(R.string.settings_select_model_title)
                .setItems(items, (dialog, which) -> binding.editModelName.setText(modelIds.get(which)))
                .show();
    }

    @Nullable
    private String currentEndpointUrlOrShowError() {
        String url = binding.editEndpointUrl.getText() == null ? "" : binding.editEndpointUrl.getText().toString().trim();
        if (!url.startsWith("http://") && !url.startsWith("https://")) {
            binding.layoutEndpointUrl.setError(activity.getString(R.string.settings_endpoint_error));
            return null;
        }
        binding.layoutEndpointUrl.setError(null);
        return url;
    }

    private void clearHistory() {
        // Deletes every conversation (and, via the foreign key cascade, all their messages).
        // ChatViewModel's conversation-list watcher will notice the active conversation is gone
        // and spin up a fresh empty one automatically the next time the chat screen is visible.
        conversationRepository.deleteAll();
        Snackbar.make(binding.getRoot(), R.string.settings_saved, Snackbar.LENGTH_SHORT).show();
    }
}
