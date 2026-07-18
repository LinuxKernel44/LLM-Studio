package com.linuxkernel44.llmstudio.data;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.apache.commons.compress.compressors.bzip2.BZip2CompressorInputStream;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import okhttp3.ResponseBody;

/**
 * Downloads and unpacks the Kokoro-en-v0_19 on-device TTS model (sherpa-onnx's build of it) into
 * app-private storage. The archive is ~320MB, so it is fetched at runtime rather than bundled with
 * the app; everything after download runs fully offline (see KokoroTtsEngine).
 */
public class KokoroModelManager {

    private static final String DOWNLOAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/tts-models/kokoro-en-v0_19.tar.bz2";
    private static final String MODEL_DIR_NAME = "kokoro-en-v0_19";

    // The archive (~320 MB) and its extracted contents (~380 MB) briefly coexist on disk during
    // extraction, so require a bit of headroom above their combined peak before starting - failing
    // fast with a clear message beats a cryptic IOException part-way through a long download.
    private static final long REQUIRED_FREE_BYTES = 800L * 1024 * 1024;

    /** In display order, matching sid 0..10 as documented by sherpa-onnx for this model. */
    public static final String[] VOICE_NAMES = {
            "af", "af_bella", "af_nicole", "af_sarah", "af_sky",
            "am_adam", "am_michael", "bf_emma", "bf_isabella", "bm_george", "bm_lewis"
    };

    public interface DownloadCallback {
        void onProgress(int percent);

        /** Fired once the download hits 100% and the (slow, ~1-3 min) bzip2 extraction begins. */
        void onExtracting();

        void onCompleted();

        void onError(String message);
    }

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final OkHttpClient client = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    public static File modelDir(Context context) {
        return new File(context.getFilesDir(), MODEL_DIR_NAME);
    }

    /** True once every file sherpa-onnx's OfflineTts needs is present on disk. */
    public static boolean isModelReady(Context context) {
        File dir = modelDir(context);
        return new File(dir, "model.onnx").isFile()
                && new File(dir, "voices.bin").isFile()
                && new File(dir, "tokens.txt").isFile()
                && new File(dir, "espeak-ng-data").isDirectory();
    }

    public void download(Context context, DownloadCallback callback) {
        Context appContext = context.getApplicationContext();
        executor.execute(() -> {
            File targetDir = modelDir(appContext);
            File archiveFile = new File(appContext.getCacheDir(), "kokoro-en-v0_19.tar.bz2");
            long freeBytes = appContext.getFilesDir().getUsableSpace();
            if (freeBytes < REQUIRED_FREE_BYTES) {
                long freeMb = freeBytes / (1024 * 1024);
                long neededMb = REQUIRED_FREE_BYTES / (1024 * 1024);
                mainHandler.post(() -> callback.onError(
                        "Not enough free storage to install the voice model (need ~" + neededMb
                                + " MB, only " + freeMb + " MB free). Free up some space and try again."));
                return;
            }
            try {
                downloadFile(archiveFile, callback);
                mainHandler.post(callback::onExtracting);
                extractArchive(archiveFile, targetDir);
                mainHandler.post(callback::onCompleted);
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("Kokoro model download failed: " + e.getMessage()));
            } finally {
                //noinspection ResultOfMethodCallIgnored
                archiveFile.delete();
            }
        });
    }

    private void downloadFile(File target, DownloadCallback callback) throws IOException {
        // getCacheDir() returns a path but Android doesn't guarantee the directory physically
        // exists yet, so a FileOutputStream on it can fail with ENOENT - create it first.
        File parent = target.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        Request request = new Request.Builder().url(DOWNLOAD_URL).build();
        try (Response response = client.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                throw new IOException("HTTP " + response.code());
            }
            ResponseBody body = response.body();
            if (body == null) {
                throw new IOException("Empty response body");
            }
            long total = body.contentLength();
            try (InputStream in = body.byteStream();
                 OutputStream out = new FileOutputStream(target)) {
                byte[] buffer = new byte[64 * 1024];
                long readSoFar = 0;
                int lastReportedPercent = -1;
                int read;
                while ((read = in.read(buffer)) != -1) {
                    out.write(buffer, 0, read);
                    readSoFar += read;
                    if (total > 0) {
                        int percent = (int) (readSoFar * 100 / total);
                        if (percent != lastReportedPercent) {
                            lastReportedPercent = percent;
                            int finalPercent = percent;
                            mainHandler.post(() -> callback.onProgress(finalPercent));
                        }
                    }
                }
            }
        }
    }

    /** The archive's entries are all nested under a single "kokoro-en-v0_19/" folder; that prefix
     *  is stripped so the extracted files land directly inside targetDir. */
    private void extractArchive(File archiveFile, File targetDir) throws IOException {
        if (targetDir.exists()) {
            deleteRecursive(targetDir);
        }
        //noinspection ResultOfMethodCallIgnored
        targetDir.mkdirs();
        try (InputStream fileIn = new FileInputStream(archiveFile);
             BZip2CompressorInputStream bzIn = new BZip2CompressorInputStream(fileIn);
             TarArchiveInputStream tarIn = new TarArchiveInputStream(bzIn)) {
            TarArchiveEntry entry;
            while ((entry = tarIn.getNextTarEntry()) != null) {
                String relativePath = stripTopFolder(entry.getName());
                if (relativePath.isEmpty()) {
                    continue;
                }
                File outFile = new File(targetDir, relativePath);
                if (entry.isDirectory()) {
                    //noinspection ResultOfMethodCallIgnored
                    outFile.mkdirs();
                    continue;
                }
                File parent = outFile.getParentFile();
                if (parent != null) {
                    //noinspection ResultOfMethodCallIgnored
                    parent.mkdirs();
                }
                try (OutputStream out = new FileOutputStream(outFile)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    while ((read = tarIn.read(buffer)) != -1) {
                        out.write(buffer, 0, read);
                    }
                }
            }
        }
    }

    private static String stripTopFolder(String entryName) {
        int slash = entryName.indexOf('/');
        return slash >= 0 ? entryName.substring(slash + 1) : entryName;
    }

    private static void deleteRecursive(File file) {
        if (file.isDirectory()) {
            File[] children = file.listFiles();
            if (children != null) {
                for (File child : children) {
                    deleteRecursive(child);
                }
            }
        }
        //noinspection ResultOfMethodCallIgnored
        file.delete();
    }
}
