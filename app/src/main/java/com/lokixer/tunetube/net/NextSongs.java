package com.lokixer.tunetube.net;

import com.lokixer.tunetube.data.VideoItem;

import okhttp3.Request;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Java version of the Node.js getNextVideos() function.
 *
 * Opens the YouTube watch page of a song, reads the "ytInitialData" JSON and collects
 * the suggested (up next) videos. Must be called on a background thread.
 */
public class NextSongs {

    private static final Pattern ID_PATTERN =
            Pattern.compile("(?:youtu\\.be/|v=)([\\w-]{11})");
    private static final Pattern INITIAL_DATA =
            Pattern.compile("ytInitialData\\s*=\\s*");

    /** Returns the suggested songs for the given video url / id (current song is not included). */
    public static List<VideoItem> getNextVideos(String url) throws Exception {
        final String currentId = extractId(url);

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", Net.USER_AGENT)
                .header("Accept-Language", "en-US,en;q=0.9")
                // skips the EU cookie-consent page, which has no video data
                .header("Cookie", "CONSENT=YES+1; SOCS=CAI")
                .build();

        String html;
        try (Response response = Net.client.newCall(request).execute()) {
            if (!response.isSuccessful() || response.body() == null) {
                throw new IOException("HTTP " + response.code());
            }
            html = response.body().string();
        }

        // ---- extract the ytInitialData JSON (same brace-matching as the JS version) ----
        Matcher m = INITIAL_DATA.matcher(html);
        if (!m.find()) throw new Exception("Could not find ytInitialData");

        int start = m.end();
        int depth = 0;
        int end = -1;
        boolean inStr = false;
        boolean esc = false;
        for (int i = start; i < html.length(); i++) {
            char c = html.charAt(i);
            if (inStr) {
                if (esc) esc = false;
                else if (c == '\\') esc = true;
                else if (c == '"') inStr = false;
                continue;
            }
            if (c == '"') {
                inStr = true;
            } else if (c == '{') {
                depth++;
            } else if (c == '}' && --depth == 0) {
                end = i + 1;
                break;
            }
        }
        if (end < 0) throw new Exception("Could not read ytInitialData");

        JSONObject initialData = new JSONObject(html.substring(start, end));

        // ---- walk the tree and collect videos ----
        List<VideoItem> videos = new ArrayList<>();
        walk(initialData, currentId, new HashSet<>(), videos);

        // The first item is the same song as the current one, so drop it
        if (!videos.isEmpty()) videos.remove(0);
        return videos;
    }

    /** Video id from a youtu.be / watch?v= link (or null). */
    public static String extractId(String url) {
        if (url == null) return null;
        Matcher m = ID_PATTERN.matcher(url);
        return m.find() ? m.group(1) : null;
    }

    private static void walk(Object node, String currentId, Set<String> seen, List<VideoItem> out) {
        if (node instanceof JSONArray) {
            JSONArray arr = (JSONArray) node;
            for (int i = 0; i < arr.length(); i++) walk(arr.opt(i), currentId, seen, out);
            return;
        }
        if (!(node instanceof JSONObject)) return;
        JSONObject o = (JSONObject) node;

        // Classic renderers
        JSONObject r = firstObject(o, "videoRenderer", "compactVideoRenderer",
                "gridVideoRenderer", "endScreenVideoRenderer");
        if (r != null && !r.optString("videoId").isEmpty()) {
            String channel = simpleOrRuns(r.optJSONObject("ownerText"));
            if (channel == null) channel = runs(r.optJSONObject("shortBylineText"));
            String views = simpleText(r.optJSONObject("viewCountText"));
            if (views == null) views = simpleText(r.optJSONObject("shortViewCountText"));

            add(out, seen, currentId,
                    r.optString("videoId"),
                    simpleOrRuns(r.optJSONObject("title")),
                    channel,
                    simpleText(r.optJSONObject("lengthText")),
                    views,
                    simpleText(r.optJSONObject("publishedTimeText")));
        }

        // New "lockup" renderer (what the watch page uses now)
        JSONObject l = o.optJSONObject("lockupViewModel");
        if (l != null && !l.optString("contentId").isEmpty()) {
            String type = l.optString("contentType", "");
            // skip playlists / mixes: they are not a single playable video
            if (type.isEmpty() || type.contains("VIDEO")) {
                JSONObject meta = l.optJSONObject("metadata");
                meta = meta == null ? null : meta.optJSONObject("lockupMetadataViewModel");
                if (meta == null) meta = new JSONObject();

                String views = null;
                String published = null;
                JSONObject cmv = meta.optJSONObject("metadata");
                cmv = cmv == null ? null : cmv.optJSONObject("contentMetadataViewModel");
                JSONArray rows = cmv == null ? null : cmv.optJSONArray("metadataRows");
                if (rows != null) {
                    for (int i = 0; i < rows.length(); i++) {
                        JSONObject row = rows.optJSONObject(i);
                        JSONArray parts = row == null ? null : row.optJSONArray("metadataParts");
                        if (parts == null) continue;
                        for (int j = 0; j < parts.length(); j++) {
                            JSONObject p = parts.optJSONObject(j);
                            if (p == null) continue;
                            JSONObject textObj = p.optJSONObject("text");
                            String t = textObj == null ? null : textObj.optString("content", null);
                            if (t == null || t.isEmpty()) continue;
                            String label = p.optString("accessibilityLabel", "");
                            if (label.toLowerCase().contains("views") || t.toLowerCase().contains("views")) {
                                views = t;
                            }
                            if (t.toLowerCase().contains("ago")) published = t;
                        }
                    }
                }

                add(out, seen, currentId,
                        l.optString("contentId"),
                        content(meta.optJSONObject("title")),
                        lockupChannel(meta),
                        lockupDuration(l),
                        views,
                        published);
            }
        }

        Iterator<String> keys = o.keys();
        while (keys.hasNext()) walk(o.opt(keys.next()), currentId, seen, out);
    }

    private static void add(List<VideoItem> out, Set<String> seen, String currentId,
                            String id, String title, String channel, String duration,
                            String views, String published) {
        if (id == null || id.length() != 11) return;   // real video ids are 11 characters
        if (id.equals(currentId) || !seen.add(id)) return;
        if (title == null || title.isEmpty()) return;  // nothing useful to show

        out.add(new VideoItem(
                title,
                id,
                "https://www.youtube.com/watch?v=" + id,
                "https://i.ytimg.com/vi/" + id + "/mqdefault.jpg",
                nz(duration), nz(views), nz(published), nz(channel)));
    }

    // ---------- small JSON helpers ----------

    private static JSONObject firstObject(JSONObject o, String... keys) {
        for (String k : keys) {
            JSONObject v = o.optJSONObject(k);
            if (v != null) return v;
        }
        return null;
    }

    private static String simpleText(JSONObject t) {
        if (t == null) return null;
        String s = t.optString("simpleText", "");
        return s.isEmpty() ? null : s;
    }

    private static String runs(JSONObject t) {
        if (t == null) return null;
        JSONArray arr = t.optJSONArray("runs");
        if (arr == null) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject run = arr.optJSONObject(i);
            if (run != null) sb.append(run.optString("text", ""));
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private static String simpleOrRuns(JSONObject t) {
        String s = simpleText(t);
        return s != null ? s : runs(t);
    }

    private static String content(JSONObject t) {
        if (t == null) return null;
        String s = t.optString("content", "");
        return s.isEmpty() ? null : s;
    }

    private static String lockupChannel(JSONObject meta) {
        JSONObject image = meta.optJSONObject("image");
        JSONObject d = image == null ? null : image.optJSONObject("decoratedAvatarViewModel");
        JSONObject a = d == null ? null : d.optJSONObject("avatar");
        JSONObject av = a == null ? null : a.optJSONObject("avatarViewModel");
        String label = av == null ? "" : av.optString("a11yLabel", "");
        if (!label.isEmpty()) return label.replaceFirst("^Go to channel ", "");

        // fallback: first line under the title is usually the channel name
        JSONObject cmv = meta.optJSONObject("metadata");
        cmv = cmv == null ? null : cmv.optJSONObject("contentMetadataViewModel");
        JSONArray rows = cmv == null ? null : cmv.optJSONArray("metadataRows");
        if (rows != null && rows.length() > 0) {
            JSONObject row = rows.optJSONObject(0);
            JSONArray parts = row == null ? null : row.optJSONArray("metadataParts");
            JSONObject p = parts == null ? null : parts.optJSONObject(0);
            JSONObject textObj = p == null ? null : p.optJSONObject("text");
            String t = textObj == null ? "" : textObj.optString("content", "");
            if (!t.isEmpty() && !t.toLowerCase().contains("views") && !t.toLowerCase().contains("ago")) {
                return t;
            }
        }
        return null;
    }

    private static String lockupDuration(JSONObject l) {
        JSONObject ci = l.optJSONObject("contentImage");
        JSONObject tv = ci == null ? null : ci.optJSONObject("thumbnailViewModel");
        JSONArray overlays = tv == null ? null : tv.optJSONArray("overlays");
        JSONObject first = overlays == null ? null : overlays.optJSONObject(0);
        JSONObject bottom = first == null ? null : first.optJSONObject("thumbnailBottomOverlayViewModel");
        JSONArray badges = bottom == null ? null : bottom.optJSONArray("badges");
        JSONObject b0 = badges == null ? null : badges.optJSONObject(0);
        JSONObject badge = b0 == null ? null : b0.optJSONObject("thumbnailBadgeViewModel");
        String text = badge == null ? "" : badge.optString("text", "");
        return text.isEmpty() ? null : text;
    }

    private static String nz(String s) {
        return s == null ? "" : s;
    }
}
