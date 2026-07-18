package com.linuxkernel44.llmstudio.data;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/** Thin wrapper around SharedPreferences for app-wide settings (not tied to a specific profile
 *  or conversation - those live in Room, see ProfileEntity/ConversationEntity). */
public class SettingsManager {

    private static final String PREFS_NAME = "llm_studio_settings";
    private static final String KEY_CONTINUOUS_LISTENING = "continuous_listening";
    private static final String KEY_THEME_MODE = "theme_mode";
    private static final String KEY_ACTIVE_PROFILE_ID = "active_profile_id";
    private static final String KEY_ACTIVE_CONVERSATION_ID = "active_conversation_id";
    private static final String KEY_RESUME_LAST_CONVERSATION = "resume_last_conversation";
    private static final String KEY_VOICE_LANGUAGE_TAG = "voice_language_tag";
    private static final String KEY_SPEECH_RATE_PREFIX = "speech_rate_";
    private static final String KEY_USE_KOKORO_TTS = "use_kokoro_tts";
    private static final String KEY_KOKORO_SPEAKER_ID = "kokoro_speaker_id";

    public static final String DEFAULT_PROFILE_NAME = "Default";
    public static final String DEFAULT_ENDPOINT_URL = "http://10.0.0.1:8081/v1/chat/completions";
    public static final String DEFAULT_SYSTEM_PROMPT = "You are a helpful voice assistant. Keep replies concise and conversational, since they will be read aloud.";
    public static final long NO_ID = -1L;

    public static final int THEME_MODE_DYNAMIC = 0;
    public static final int THEME_MODE_AMOLED = 1;
    /** Restored "base" Liquid Glass: normal Material dark colors, not forced pure black. */
    public static final int THEME_MODE_LIQUID_GLASS = 2;
    /** Same Liquid Glass visuals, but pinned to pure AMOLED black (#000000) - the app's original
     *  single "Liquid Glass" theme before THEME_MODE_LIQUID_GLASS was reintroduced as a lighter base. */
    public static final int THEME_MODE_OLED_LIQUID_GLASS = 3;

    /** True for either Liquid Glass variant - both render MainActivity/SettingsActivity's screens
     *  via the Compose GlassMainActivity/GlassSettingsActivity family instead of the classic Views. */
    public static boolean isLiquidGlassFamily(int mode) {
        return mode == THEME_MODE_LIQUID_GLASS || mode == THEME_MODE_OLED_LIQUID_GLASS;
    }

    /** BCP-47 tags used for both on-device speech recognition and text-to-speech. */
    public static final String VOICE_LANGUAGE_ENGLISH_US = "en-US";
    public static final String VOICE_LANGUAGE_FRENCH = "fr-FR";

    private final SharedPreferences prefs;

    public SettingsManager(Context context) {
        this.prefs = context.getApplicationContext().getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** true = hands-free continuous listening loop; false = push-to-talk (hold the mic). */
    public boolean isContinuousListening() {
        return prefs.getBoolean(KEY_CONTINUOUS_LISTENING, false);
    }

    public void setContinuousListening(boolean enabled) {
        prefs.edit().putBoolean(KEY_CONTINUOUS_LISTENING, enabled).apply();
    }

    /** One of THEME_MODE_DYNAMIC (default), THEME_MODE_AMOLED, or THEME_MODE_LIQUID_GLASS. */
    public int getThemeMode() {
        return prefs.getInt(KEY_THEME_MODE, THEME_MODE_DYNAMIC);
    }

    public void setThemeMode(int mode) {
        prefs.edit().putInt(KEY_THEME_MODE, mode).apply();
    }

    public boolean isAmoledTheme() {
        return getThemeMode() == THEME_MODE_AMOLED;
    }

    /** Liquid Glass (either variant) renders MainActivity's chat screen entirely in Compose (see
     *  GlassMainActivity) instead of the normal XML/View screen - everything else (Settings,
     *  dialogs) is unaffected. */
    public boolean isLiquidGlassTheme() {
        return isLiquidGlassFamily(getThemeMode());
    }

    /** True only for the pure-AMOLED-black variant; false for the base Liquid Glass or any other theme. */
    public boolean isOledLiquidGlassTheme() {
        return getThemeMode() == THEME_MODE_OLED_LIQUID_GLASS;
    }

    public long getActiveProfileId() {
        return prefs.getLong(KEY_ACTIVE_PROFILE_ID, NO_ID);
    }

    public void setActiveProfileId(long id) {
        prefs.edit().putLong(KEY_ACTIVE_PROFILE_ID, id).apply();
    }

    public long getActiveConversationId() {
        return prefs.getLong(KEY_ACTIVE_CONVERSATION_ID, NO_ID);
    }

    public void setActiveConversationId(long id) {
        prefs.edit().putLong(KEY_ACTIVE_CONVERSATION_ID, id).apply();
    }

    /** true = reopening the app continues the last active conversation; false (default) = every
     *  cold start begins a brand-new, empty conversation instead (old ones stay saved in the drawer). */
    public boolean isResumeLastConversation() {
        return prefs.getBoolean(KEY_RESUME_LAST_CONVERSATION, false);
    }

    public void setResumeLastConversation(boolean enabled) {
        prefs.edit().putBoolean(KEY_RESUME_LAST_CONVERSATION, enabled).apply();
    }

    /**
     * Language used for on-device speech recognition and text-to-speech only - the app's own UI
     * text stays English regardless of this setting. Defaults to English (US).
     */
    public String getVoiceLanguageTag() {
        return prefs.getString(KEY_VOICE_LANGUAGE_TAG, VOICE_LANGUAGE_ENGLISH_US);
    }

    public void setVoiceLanguageTag(String tag) {
        prefs.edit().putString(KEY_VOICE_LANGUAGE_TAG, tag).apply();
    }

    public Locale getVoiceLocale() {
        return Locale.forLanguageTag(getVoiceLanguageTag());
    }

    /**
     * TTS playback speed for a given voice language tag: 1.0 = normal (default), 0.5 = half speed,
     * 2.0 = double speed. Kept per-language since a comfortable rate for English speech often isn't
     * for French (or vice versa).
     */
    public float getSpeechRate(String languageTag) {
        return prefs.getFloat(KEY_SPEECH_RATE_PREFIX + languageTag, 1.0f);
    }

    public void setSpeechRate(String languageTag, float rate) {
        prefs.edit().putFloat(KEY_SPEECH_RATE_PREFIX + languageTag, rate).apply();
    }

    /** Convenience: the speech rate for whichever voice language is currently selected. */
    public float getSpeechRateForCurrentVoiceLanguage() {
        return getSpeechRate(getVoiceLanguageTag());
    }

    /**
     * true = speak using the local Kokoro neural voice instead of the system's native TTS.
     * Kokoro-en-v0_19 is English-only, so this is only honored (see ChatViewModel#setTtsEngine)
     * when the voice language is English - French always uses the system engine.
     */
    public boolean isUseKokoroTts() {
        return prefs.getBoolean(KEY_USE_KOKORO_TTS, false);
    }

    public void setUseKokoroTts(boolean enabled) {
        prefs.edit().putBoolean(KEY_USE_KOKORO_TTS, enabled).apply();
    }

    /** Index into KokoroModelManager.VOICE_NAMES (0..10 for Kokoro-en-v0_19). */
    public int getKokoroSpeakerId() {
        return prefs.getInt(KEY_KOKORO_SPEAKER_ID, 0);
    }

    public void setKokoroSpeakerId(int speakerId) {
        prefs.edit().putInt(KEY_KOKORO_SPEAKER_ID, speakerId).apply();
    }
}
