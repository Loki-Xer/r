package com.lokixer.tunetube.player;

import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.media.MediaMuxer;

import com.lokixer.tunetube.net.Net;

import okhttp3.Request;
import okhttp3.Response;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.util.Locale;

/**
 * Makes sure what we hand to the player is an AUDIO-ONLY file.
 *
 * If the scraper gave us a real audio link (mp3 / m4a / audio content-type) it is played as is.
 * If it gave us a video file, we download it and copy out just the audio track into a
 * separate audio-only file (no video track left, so Android treats it as music and shows the
 * media notification). This uses only built-in Android APIs, so it needs no extra library.
 */
public class AudioPrep {

    /** Returns an already prepared local audio file, or null if this song is not downloaded locally. */
    public static String getLocalAudio(Context context, String videoId) {
        File dir = new File(context.getCacheDir(), "audio");
        File mp4 = new File(dir, videoId + ".m4a");
        File webm = new File(dir, videoId + ".webm");
        if (mp4.isFile() && mp4.length() > 0) return mp4.getAbsolutePath();
        if (webm.isFile() && webm.length() > 0) return webm.getAbsolutePath();

        // Also accept an app-private persistent offline directory if a future
        // downloader stores files there.
        File offline = new File(context.getFilesDir(), "offline/audio");
        mp4 = new File(offline, videoId + ".m4a");
        webm = new File(offline, videoId + ".webm");
        if (mp4.isFile() && mp4.length() > 0) return mp4.getAbsolutePath();
        if (webm.isFile() && webm.length() > 0) return webm.getAbsolutePath();
        return null;
    }

    /** Returns true when the track is stored in the persistent offline library. */
    public static boolean isDownloaded(Context context, String videoId) {
        return getDownloadedAudio(context, videoId) != null;
    }

    /** Returns the persistent offline file, never the temporary playback cache. */
    public static String getDownloadedAudio(Context context, String videoId) {
        File dir = new File(context.getFilesDir(), "offline/audio");
        File mp4 = new File(dir, videoId + ".m4a");
        File webm = new File(dir, videoId + ".webm");
        File mp3 = new File(dir, videoId + ".mp3");
        if (mp4.isFile() && mp4.length() > 0) return mp4.getAbsolutePath();
        if (webm.isFile() && webm.length() > 0) return webm.getAbsolutePath();
        if (mp3.isFile() && mp3.length() > 0) return mp3.getAbsolutePath();
        return null;
    }

    /**
     * Downloads a track into the persistent offline library. This is separate
     * from prepare(), whose cache is intentionally temporary.
     */
    public static String download(Context context, String videoId, String url) throws Exception {
        File dir = new File(context.getFilesDir(), "offline/audio");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();

        String existing = getDownloadedAudio(context, videoId);
        if (existing != null) return existing;

        File tmp = new File(dir, videoId + ".download");
        Request request = new Request.Builder().url(url)
                .header("User-Agent", Net.USER_AGENT).build();

        String contentType;
        try (Response res = Net.client.newCall(request).execute()) {
            if (!res.isSuccessful() || res.body() == null) throw new Exception("download failed");
            contentType = String.valueOf(res.header("Content-Type", "")).toLowerCase(Locale.US);
            try (InputStream in = res.body().byteStream();
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
        }

        if (isPlainAudio(url, contentType)) {
            String ext = ".m4a";
            String lower = url.toLowerCase(Locale.US);
            if (lower.contains(".mp3")) ext = ".mp3";
            else if (lower.contains(".webm") || contentType.contains("webm")) ext = ".webm";
            File out = new File(dir, videoId + ext);
            if (!tmp.renameTo(out)) {
                copyFile(tmp, out);
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            }
            return out.getAbsolutePath();
        }

        File converted = extractAudio(tmp, dir, videoId);
        //noinspection ResultOfMethodCallIgnored
        tmp.delete();
        return converted.getAbsolutePath();
    }

    private static void copyFile(File from, File to) throws Exception {
        try (InputStream in = new java.io.FileInputStream(from);
             OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
        }
    }

    /** Removes one persistent offline track. */
    public static boolean deleteDownloaded(Context context, String videoId) {
        File dir = new File(context.getFilesDir(), "offline/audio");
        boolean deleted = false;
        for (String ext : new String[]{".m4a", ".webm", ".mp3"}) {
            File f = new File(dir, videoId + ext);
            if (f.exists()) deleted |= f.delete();
        }
        return deleted;
    }

    /** Must be called on a background thread. Returns a URL or a local file path to play. */
    public static String prepare(Context context, String videoId, String url) throws Exception {
        File dir = new File(context.getCacheDir(), "audio");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();

        // already converted earlier? reuse it
        File mp4 = new File(dir, videoId + ".m4a");
        File webm = new File(dir, videoId + ".webm");
        if (mp4.length() > 0) return mp4.getAbsolutePath();
        if (webm.length() > 0) return webm.getAbsolutePath();

        File tmp = new File(dir, videoId + ".tmp");
        String contentType;
        Request request = new Request.Builder().url(url)
                .header("User-Agent", Net.USER_AGENT).build();
        try (Response res = Net.client.newCall(request).execute()) {
            if (!res.isSuccessful() || res.body() == null) throw new Exception("download failed");
            contentType = String.valueOf(res.header("Content-Type", "")).toLowerCase(Locale.US);

            if (isPlainAudio(url, contentType)) {
                return url; // nothing to convert, stream it directly
            }

            // it's a video (or unknown) container: save it, then pull the audio out
            try (InputStream in = res.body().byteStream();
                 OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[64 * 1024];
                int n;
                while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
            }
        }

        try {
            File result = extractAudio(tmp, dir, videoId);
            trimCache(dir, 10);
            return result.getAbsolutePath();
        } catch (Exception e) {
            // Couldn't split out the audio track: fall back to streaming the original link.
            // MusicPlayer disables the video track, so it still plays as music.
            return url;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            tmp.delete();
        }
    }

    private static boolean isPlainAudio(String url, String contentType) {
        String path = url.toLowerCase(Locale.US);
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        if (path.endsWith(".mp3") || path.endsWith(".m4a") || path.endsWith(".aac")
                || path.endsWith(".ogg") || path.endsWith(".opus")) return true;
        return contentType.startsWith("audio/");
    }

    /** Copies the audio track (no re-encoding, so it's fast and lossless) into its own file. */
    private static File extractAudio(File source, File dir, String videoId) throws Exception {
        MediaExtractor extractor = new MediaExtractor();
        MediaMuxer muxer = null;
        File out = null;
        boolean started = false;
        try {
            extractor.setDataSource(source.getAbsolutePath());

            int audioTrack = -1;
            MediaFormat format = null;
            for (int i = 0; i < extractor.getTrackCount(); i++) {
                MediaFormat f = extractor.getTrackFormat(i);
                String mime = f.getString(MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("audio/")) {
                    audioTrack = i;
                    format = f;
                    break;
                }
            }
            if (audioTrack < 0) throw new Exception("no audio track in file");

            String mime = format.getString(MediaFormat.KEY_MIME);
            boolean aac = "audio/mp4a-latm".equals(mime);
            int container = aac ? MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                    : MediaMuxer.OutputFormat.MUXER_OUTPUT_WEBM;
            out = new File(dir, videoId + (aac ? ".m4a" : ".webm"));

            muxer = new MediaMuxer(out.getAbsolutePath(), container);
            int outTrack = muxer.addTrack(format);
            muxer.start();
            started = true;

            extractor.selectTrack(audioTrack);
            ByteBuffer buffer = ByteBuffer.allocate(1024 * 1024);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            while (true) {
                buffer.clear();
                int size = extractor.readSampleData(buffer, 0);
                if (size < 0) break;
                info.offset = 0;
                info.size = size;
                info.presentationTimeUs = extractor.getSampleTime();
                info.flags = extractor.getSampleFlags();
                muxer.writeSampleData(outTrack, buffer, info);
                extractor.advance();
            }
            return out;
        } catch (Exception e) {
            if (out != null) {
                //noinspection ResultOfMethodCallIgnored
                out.delete();
            }
            throw e;
        } finally {
            try {
                if (muxer != null) {
                    if (started) muxer.stop();
                    muxer.release();
                }
            } catch (Exception ignored) {
            }
            extractor.release();
        }
    }

    /** Keeps the cache small: only the newest few converted files stay. */
    private static void trimCache(File dir, int keep) {
        File[] files = dir.listFiles();
        if (files == null || files.length <= keep) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = keep; i < files.length; i++) {
            //noinspection ResultOfMethodCallIgnored
            files[i].delete();
        }
    }
}
