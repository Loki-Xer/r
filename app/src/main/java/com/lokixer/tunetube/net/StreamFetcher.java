package com.lokixer.tunetube.net;

import android.util.Base64;
import android.util.LruCache;

import com.lokixer.tunetube.player.AudioPrep;

import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Gets a playable audio link for a YouTube video. Tries snapany.com first,
 * falls back to savetube.vip. Both are third-party services outside our
 * control, so this can break if they change.
 *
 * Speed: the slow part of snapany is finding its "secret key" (loading the
 * homepage, then scanning JS files). That key rarely changes, so it's kept
 * in memory once found, turning every play after the first into a single
 * fast request instead of four or five.
 */
public class StreamFetcher {

    private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");

    private static final String UA_MOBILE =
            "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 "
                    + "(KHTML, like Gecko) Chrome/133.0.0.0 Mobile Safari/537.36";

    // Cached across the whole app session
    private static volatile String cachedSecretKey;
    private static volatile String cachedCookies;

    // Remembers resolved audio links, so replaying a track is instant
    private static final LruCache<String, String> urlCache = new LruCache<>(15);

    /** Must be called on a background thread. Returns null if nothing worked. */
    public static String getAudioUrl(String videoId) throws Exception {
        String cached = urlCache.get(videoId);
        if (cached != null) return cached;

        String url = "https://youtube.com/watch?v=" + videoId;
        String result;
        try {
            result = fromSnapAny(videoId, url);
        } catch (Exception e) {
            result = null;
        }
        if (result == null) {
            result = fromSaveTube(url);
        }
        if (result != null) urlCache.put(videoId, result);
        return result;
    }

    /** Call this if a cached link turns out to be dead, so the next play refetches it. */
    public static void invalidate(String videoId) {
        urlCache.remove(videoId);
    }

    // ---------------- snapany ----------------

    private static String fromSnapAny(String videoId, String videoUrl) throws Exception {
        if (cachedSecretKey == null) {
            loadSnapAnySession();
        }

        try {
            return snapAnyExtract(videoUrl, cachedSecretKey, cachedCookies);
        } catch (Exception firstTry) {
            // the key may have expired: refetch once and try again
            loadSnapAnySession();
            return snapAnyExtract(videoUrl, cachedSecretKey, cachedCookies);
        }
    }

    /** Loads snapany's homepage and scans its scripts for the signing key. Slow, so cached. */
    private static synchronized void loadSnapAnySession() throws Exception {
        Request homeReq = new Request.Builder()
                .url("https://snapany.com")
                .header("User-Agent", Net.USER_AGENT)
                .build();

        String html;
        String cookies;
        try (Response homeRes = Net.client.newCall(homeReq).execute()) {
            html = homeRes.body() != null ? homeRes.body().string() : "";
            StringBuilder cb = new StringBuilder();
            for (String header : homeRes.headers("Set-Cookie")) {
                if (cb.length() > 0) cb.append("; ");
                cb.append(header.split(";")[0]);
            }
            cookies = cb.toString();
        }

        List<String> jsFiles = new ArrayList<>();
        Matcher scriptMatcher = Pattern.compile("src=\"(/_next/static/chunks/[^\"]+\\.js)\"").matcher(html);
        while (scriptMatcher.find()) jsFiles.add(scriptMatcher.group(1));

        String secretKey = null;
        for (String file : jsFiles) {
            Request jsReq = new Request.Builder()
                    .url("https://snapany.com" + file)
                    .header("User-Agent", Net.USER_AGENT)
                    .header("Cookie", cookies)
                    .build();
            try (Response jsRes = Net.client.newCall(jsReq).execute()) {
                String js = jsRes.body() != null ? jsRes.body().string() : "";
                Matcher keyMatcher = Pattern.compile("(?:=|:)\\s*[\"']([A-Za-z0-9_-]{43})[\"']").matcher(js);
                if (keyMatcher.find()) {
                    secretKey = keyMatcher.group(1);
                    break;
                }
            }
        }
        if (secretKey == null) throw new Exception("snapany secret key not found");

        cachedSecretKey = secretKey;
        cachedCookies = cookies;
    }

    private static String snapAnyExtract(String videoUrl, String secretKey, String cookies) throws Exception {
        String timestamp = String.valueOf(System.currentTimeMillis());
        String locale = "en";
        String footer = hmacSha256Hex(secretKey, videoUrl + locale + timestamp);

        JSONObject body = new JSONObject();
        body.put("link", videoUrl);

        Request extractReq = new Request.Builder()
                .url("https://api.snapany.com/v1/extract/post")
                .post(RequestBody.create(body.toString(), JSON))
                .header("User-Agent", Net.USER_AGENT)
                .header("Cookie", cookies)
                .header("Accept", "*/*")
                .header("Origin", "https://snapany.com")
                .header("Referer", "https://snapany.com/")
                .header("Accept-Language", locale)
                .header("g-timestamp", timestamp)
                .header("g-timezone", "Asia/Calcutta")
                .header("g-footer", footer)
                .build();

        try (Response res = Net.client.newCall(extractReq).execute()) {
            if (res.body() == null) throw new Exception("empty snapany response");
            JSONObject data = new JSONObject(res.body().string());
            JSONArray medias = data.optJSONArray("medias");
            if (medias == null) throw new Exception("no medias from snapany");

            String anyMedia = null;
            for (int i = 0; i < medias.length(); i++) {
                JSONObject media = medias.getJSONObject(i);
                String u = media.optString("resource_url", null);
                if (u == null || u.isEmpty()) continue;
                if ("audio".equals(media.optString("media_type"))) return u;
                if (anyMedia == null) anyMedia = u; // a video: AudioPrep turns it into audio later
            }
            if (anyMedia != null) return anyMedia;
        }
        throw new Exception("no media from snapany");
    }

    // ---------------- savetube (fallback) ----------------

    private static String fromSaveTube(String videoUrl) throws Exception {
        Request cdnReq = new Request.Builder()
                .url("https://media.savetube.vip/api/random-cdn")
                .header("User-Agent", UA_MOBILE)
                .build();

        String cdn;
        try (Response cdnRes = Net.client.newCall(cdnReq).execute()) {
            JSONObject json = new JSONObject(cdnRes.body().string());
            cdn = json.getString("cdn");
        }

        JSONObject infoBody = new JSONObject();
        infoBody.put("url", videoUrl);

        Request infoReq = new Request.Builder()
                .url("https://" + cdn + "/v2/info")
                .post(RequestBody.create(infoBody.toString(), JSON))
                .header("User-Agent", UA_MOBILE)
                .header("Referer", "https://save-tube.com/")
                .build();

        JSONObject info;
        try (Response infoRes = Net.client.newCall(infoReq).execute()) {
            JSONObject infoJson = new JSONObject(infoRes.body().string());
            info = decodeSaveTube(infoJson.getString("data"));
        }

        JSONObject downloadBody = new JSONObject();
        downloadBody.put("downloadType", "audio");
        downloadBody.put("quality", "128");
        downloadBody.put("key", info.getString("key"));

        Request downloadReq = new Request.Builder()
                .url("https://" + cdn + "/download")
                .post(RequestBody.create(downloadBody.toString(), JSON))
                .header("Content-Type", "application/json")
                .header("User-Agent", UA_MOBILE)
                .header("Referer", "https://save-tube.com/")
                .build();

        try (Response downloadRes = Net.client.newCall(downloadReq).execute()) {
            JSONObject result = new JSONObject(downloadRes.body().string());
            return result.getJSONObject("data").getString("downloadUrl");
        }
    }

    private static JSONObject decodeSaveTube(String base64Data) throws Exception {
        String secretKeyHex = "C5D58EF67A7584E4A29F6C35BBC4EB12";
        byte[] data = Base64.decode(base64Data, Base64.DEFAULT);

        byte[] iv = new byte[16];
        System.arraycopy(data, 0, iv, 0, 16);
        byte[] content = new byte[data.length - 16];
        System.arraycopy(data, 16, content, 0, content.length);

        byte[] key = hexToBytes(secretKeyHex);

        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(iv));
        byte[] decrypted = cipher.doFinal(content);

        return new JSONObject(new String(decrypted, "UTF-8"));
    }

    private static byte[] hexToBytes(String hex) {
        int len = hex.length();
        byte[] result = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            result[i / 2] = (byte) ((Character.digit(hex.charAt(i), 16) << 4)
                    + Character.digit(hex.charAt(i + 1), 16));
        }
        return result;
    }

    private static String hmacSha256Hex(String key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key.getBytes("UTF-8"), "HmacSHA256"));
        byte[] result = mac.doFinal(data.getBytes("UTF-8"));
        StringBuilder sb = new StringBuilder();
        for (byte b : result) sb.append(String.format(Locale.US, "%02x", b));
        return sb.toString();
    }
}
