package com.linuxkernel44.llmstudio.repository;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import androidx.lifecycle.LiveData;

import com.linuxkernel44.llmstudio.data.AppDatabase;
import com.linuxkernel44.llmstudio.data.ProfileDao;
import com.linuxkernel44.llmstudio.data.ProfileEntity;
import com.linuxkernel44.llmstudio.data.SettingsManager;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;

/**
 * CRUD for server profiles (name + endpoint URL + model + system prompt) and bookkeeping for which
 * one is currently active. Profiles are intentionally independent of conversations: switching the
 * active profile only changes which backend NEW messages go to.
 */
public class ProfileRepository {

    private final ProfileDao dao;
    private final SettingsManager settingsManager;
    private final ExecutorService dbExecutor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    public ProfileRepository(Context context) {
        this.dao = AppDatabase.getInstance(context).profileDao();
        this.settingsManager = new SettingsManager(context);
    }

    public LiveData<List<ProfileEntity>> observeProfiles() {
        return dao.observeAll();
    }

    /**
     * Guarantees at least one profile exists and that Settings' "active profile" preference points
     * at a real row, creating a Default profile the very first time the app runs. Runs on a
     * background thread; the resolved id is delivered on the main thread via onResolved.
     */
    public void ensureActiveProfile(Consumer<Long> onResolved) {
        dbExecutor.execute(() -> {
            List<ProfileEntity> all = dao.getAllSync();
            long resolvedId;
            if (all.isEmpty()) {
                ProfileEntity defaultProfile = new ProfileEntity(
                        SettingsManager.DEFAULT_PROFILE_NAME,
                        SettingsManager.DEFAULT_ENDPOINT_URL,
                        "",
                        SettingsManager.DEFAULT_SYSTEM_PROMPT);
                resolvedId = dao.insert(defaultProfile);
            } else {
                long activeId = settingsManager.getActiveProfileId();
                ProfileEntity active = dao.getByIdSync(activeId);
                resolvedId = active != null ? active.id : all.get(0).id;
            }
            settingsManager.setActiveProfileId(resolvedId);
            long finalId = resolvedId;
            mainHandler.post(() -> onResolved.accept(finalId));
        });
    }

    public long getActiveProfileId() {
        return settingsManager.getActiveProfileId();
    }

    /** Synchronous DB read - only call from a background thread (e.g. ChatRepository's executor). */
    public ProfileEntity getActiveProfileSync() {
        long id = settingsManager.getActiveProfileId();
        ProfileEntity profile = dao.getByIdSync(id);
        return profile != null ? profile : dao.getFirstSync();
    }

    public void setActiveProfile(long id) {
        settingsManager.setActiveProfileId(id);
    }

    public void createProfile(String name, Runnable onCreated) {
        dbExecutor.execute(() -> {
            ProfileEntity profile = new ProfileEntity(
                    name, SettingsManager.DEFAULT_ENDPOINT_URL, "", SettingsManager.DEFAULT_SYSTEM_PROMPT);
            long id = dao.insert(profile);
            settingsManager.setActiveProfileId(id);
            mainHandler.post(onCreated);
        });
    }

    public void updateProfile(ProfileEntity profile) {
        dbExecutor.execute(() -> dao.update(profile));
    }

    public void renameProfile(long id, String newName) {
        dbExecutor.execute(() -> {
            ProfileEntity profile = dao.getByIdSync(id);
            if (profile != null) {
                profile.name = newName;
                dao.update(profile);
            }
        });
    }

    /** Deletes a profile; if it was active, switches to whichever profile remains first. */
    public void deleteProfile(long id, Runnable onDeleted) {
        dbExecutor.execute(() -> {
            dao.deleteById(id);
            if (settingsManager.getActiveProfileId() == id) {
                List<ProfileEntity> remaining = dao.getAllSync();
                if (!remaining.isEmpty()) {
                    settingsManager.setActiveProfileId(remaining.get(0).id);
                }
            }
            mainHandler.post(onDeleted);
        });
    }
}
