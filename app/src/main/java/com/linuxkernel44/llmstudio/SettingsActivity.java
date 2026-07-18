package com.linuxkernel44.llmstudio;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsAnimationCompat;
import androidx.core.view.WindowInsetsCompat;

import com.linuxkernel44.llmstudio.data.SettingsManager;
import com.linuxkernel44.llmstudio.databinding.ActivitySettingsBinding;

import java.util.List;

public class SettingsActivity extends AppCompatActivity {

    private ActivitySettingsBinding binding;
    private SettingsManager settingsManager;
    private SettingsController controller;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        settingsManager = new SettingsManager(this);
        int themeMode = settingsManager.getThemeMode();
        if (!SettingsManager.isLiquidGlassFamily(themeMode)) {
            ThemeUtils.applyTheme(this);
        }
        super.onCreate(savedInstanceState);

        if (SettingsManager.isLiquidGlassFamily(themeMode)) {
            // Same transparent-redirect pattern as MainActivity/GlassMainActivity.
            startActivity(new Intent(this, GlassSettingsActivity.class));
            finish();
            return;
        }

        binding = ActivitySettingsBinding.inflate(getLayoutInflater());
        setContentView(binding.getRoot());
        setupImeAwareInsets();
        setSupportActionBar(binding.toolbar);
        if (getSupportActionBar() != null) {
            getSupportActionBar().setDisplayHomeAsUpEnabled(true);
        }

        controller = new SettingsController(this, binding, (oldMode, newMode) -> {
            if (SettingsManager.isLiquidGlassFamily(newMode)) {
                startActivity(new Intent(this, GlassSettingsActivity.class));
                finish();
            } else {
                boolean wasAmoledDisplay = oldMode == SettingsManager.THEME_MODE_AMOLED;
                boolean nowAmoledDisplay = newMode == SettingsManager.THEME_MODE_AMOLED;
                if (wasAmoledDisplay != nowAmoledDisplay) {
                    recreate();
                }
            }
        });
        controller.setup();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (controller != null) {
            controller.release();
        }
    }

    /** Same IME-aware inset handling as MainActivity - see its javadoc for the full rationale. */
    private void setupImeAwareInsets() {
        View root = binding.settingsRoot;

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
    public boolean onSupportNavigateUp() {
        getOnBackPressedDispatcher().onBackPressed();
        return true;
    }
}
