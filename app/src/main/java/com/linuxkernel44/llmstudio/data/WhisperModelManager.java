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
 * Downloads and unpacks the sherpa-onnx build of OpenAI Whisper (base, multilingual) plus the Silero
 * VAD model, into app-private storage, for on-device speech-to-text (see WhisperSttEngine). The
 * Whisper archive is ~200MB so it's fetched at runtime rather than bundled; everything after
 * download runs fully offline. Mirrors {@link KokoroModelManager}'s download/extract flow.
 */
public class WhisperModelManager {

    private static final String WHISPER_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/sherpa-onnx-whisper-base.tar.bz2";
    private static final String VAD_URL =
            "https://github.com/k2-fsa/sherpa-onnx/releases/download/asr-models/silero_vad.onnx";

    private static final String MODEL_DIR_NAME = "sherpa-onnx-whisper-base";
    private static final String VAD_FILE_NAME = "silero_vad.onnx";

    // Whisper archive (~207MB) + its extracted contents (~250MB) briefly coexist during extraction;
    // require headroom above that combined peak before starting, failing fast with a clear message.
    private static final long REQUIRED_FREE_BYTES = 700L * 1024 * 1024;

    public interface DownloadCallback {
        void onProgress(int percent);

        /** Fired once the download hits 100% and the (slow) bzip2 extraction begins. */
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

    public static File encoderFile(Context context) {
        return new File(modelDir(context), "base-encoder.int8.onnx");
    }

    public static File decoderFile(Context context) {
        return new File(modelDir(context), "base-decoder.int8.onnx");
    }

    public static File tokensFile(Context context) {
        return new File(modelDir(context), "base-tokens.txt");
    }

    public static File vadFile(Context context) {
        return new File(context.getFilesDir(), VAD_FILE_NAME);
    }

    /** True once every file WhisperSttEngine needs (Whisper encoder/decoder/tokens + VAD) is present. */
    public static boolean isModelReady(Context context) {
        return encoderFile(context).isFile()
                && decoderFile(context).isFile()
                && tokensFile(context).isFile()
                && vadFile(context).isFile();
    }

    public void download(Context context, DownloadCallback callback) {
        Context appContext = context.getApplicationContext();
        executor.execute(() -> {
            File targetDir = modelDir(appContext);
            File archiveFile = new File(appContext.getCacheDir(), "sherpa-onnx-whisper-base.tar.bz2");
            long freeBytes = appContext.getFilesDir().getUsableSpace();
            if (freeBytes < REQUIRED_FREE_BYTES) {
                long freeMb = freeBytes / (1024 * 1024);
                long neededMb = REQUIRED_FREE_BYTES / (1024 * 1024);
                mainHandler.post(() -> callback.onError(
                        "Not enough free storage to install the speech-to-text model (need ~" + neededMb
                                + " MB, only " + freeMb + " MB free). Free up some space and try again."));
                return;
            }
            try {
                // Big Whisper archive first (progress is reported against it), then the tiny VAD file.
                downloadFile(WHISPER_URL, archiveFile, callback);
                downloadFile(VAD_URL, vadFile(appContext), null);
                mainHandler.post(callback::onExtracting);
                extractArchive(archiveFile, targetDir);
                mainHandler.post(callback::onCompleted);
            } catch (Exception e) {
                mainHandler.post(() -> callback.onError("Speech-to-text model download failed: " + e.getMessage()));
            } finally {
                //noinspection ResultOfMethodCallIgnored
                archiveFile.delete();
            }
        });
    }

    /** callback may be null (used for the small VAD file, where progress isn't worth reporting). */
    private void downloadFile(String url, File target, DownloadCallback callback) throws IOException {
        // getCacheDir()/getFilesDir() return a path but Android doesn't guarantee the directory
        // physically exists yet, so a FileOutputStream on it can fail with ENOENT - create it first.
        File parent = target.getParentFile();
        if (parent != null) {
            //noinspection ResultOfMethodCallIgnored
            parent.mkdirs();
        }
        Request request = new Request.Builder().url(url).build();
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
                    if (callback != null && total > 0) {
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

    /** The archive's entries are all nested under a single "sherpa-onnx-whisper-base/" folder; that
     *  prefix is stripped so the extracted files land directly inside targetDir. */
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
