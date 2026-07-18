package com.linuxkernel44.llmstudio;

import android.app.Application;

public class LlmStudioApp extends Application {

    @Override
    public void onCreate() {
        super.onCreate();
        // Dynamic color vs. the fixed AMOLED theme is chosen per-Activity (see ThemeUtils), since
        // it depends on a user preference that can be toggled at runtime in Settings - applying
        // dynamic color unconditionally to every Activity here would override that choice.
    }
}
