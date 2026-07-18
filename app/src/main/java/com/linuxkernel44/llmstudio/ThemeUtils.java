package com.linuxkernel44.llmstudio;

import android.app.Activity;

import com.google.android.material.color.DynamicColors;
import com.linuxkernel44.llmstudio.data.SettingsManager;

/**
 * Chooses between Material You dynamic color and the fixed AMOLED-black/purple theme, based on the
 * user's Settings choice. Must be called as the very first thing in each Activity's onCreate,
 * before super.onCreate() - both setTheme() and DynamicColors.applyToActivityIfAvailable() only
 * take effect if applied before the activity's window/views are created.
 */
public final class ThemeUtils {

    private ThemeUtils() {
    }

    public static void applyTheme(Activity activity) {
        SettingsManager settingsManager = new SettingsManager(activity);
        if (settingsManager.isAmoledTheme()) {
            activity.setTheme(R.style.Theme_LLMStudio_Amoled);
        } else {
            DynamicColors.applyToActivityIfAvailable(activity);
        }
    }
}
