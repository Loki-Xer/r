package com.lokixer.tunetube.net;

import android.util.LruCache;

import com.lokixer.tunetube.data.VideoItem;

import okhttp3.Call;
import okhttp3.Request;
import okhttp3.Response;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.IOException;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Java version of the Node.js yts() function, tuned for speed. */
public class YoutubeSearch {

    // Remembers the last 20 searches in memory,
    // so a repeated search is instant.
    private static final LruCache<String, List<VideoItem>> cache =
            new LruCache<>(20);

    private static volatile Call currentCall;
    private static volatile Call suggestionCall;

    /** Opens the connection to YouTube early,
     * so the first search is faster.
     */
    public static void warmUp() {
        new Thread(() -> {
            try {
                Request request = new Request.Builder()
                        .url("https://m.youtube.com/")
                        .head()
                        .header("User-Agent", Net.USER_AGENT)
                        .build();

                Net.client.newCall(request).execute().close();

            } catch (Exception ignored) {
            }
        }).start();
    }

    /** Stops the running search. */
    public static void cancelCurrent() {
        Call call = currentCall;

        if (call != null) {
            call.cancel();
        }
    }

    /** Cancels the currently running autocomplete request. */
    public static void cancelSuggestions() {
        Call call = suggestionCall;

        if (call != null) {
            call.cancel();
        }

        suggestionCall = null;
    }

    /**
     * Returns YouTube search suggestions.
     *
     * Must be called on a background thread.
     */
    public static List<String> getSuggestions(String query)
            throws Exception {

        query = query == null ? "" : query.trim();

        if (query.isEmpty()) {
            return new ArrayList<>();
        }

        /*
         * YouTube suggestion endpoint.
         *
         * client=firefox works with the response:
         *
         * [
         *   "super",
         *   [
         *      "superman",
         *      "super bheem",
         *      "superstar"
         *   ]
         * ]
         */
        String url =
                "https://suggestqueries.google.com/complete/search"
                        + "?client=firefox"
                        + "&ds=yt"
                        + "&q="
                        + URLEncoder.encode(query, "UTF-8");

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", Net.USER_AGENT)
                .header(
                        "Accept",
                        "application/json,text/plain,*/*"
                )
                .header(
                        "Accept-Language",
                        "en-US,en;q=0.9"
                )
                .build();

        Call call = Net.client.newCall(request);

        suggestionCall = call;

        try (Response response = call.execute()) {

            if (!response.isSuccessful()
                    || response.body() == null) {

                throw new IOException(
                        "HTTP " + response.code()
                );
            }

            String body = response.body().string();

            if (body == null || body.trim().isEmpty()) {
                return new ArrayList<>();
            }

            /*
             * Response format:
             *
             * [
             *   "super",
             *   [
             *      "superman",
             *      "super bheem",
             *      "superstar"
             *   ]
             * ]
             */
            JSONArray root = new JSONArray(body);

            JSONArray suggestions =
                    root.optJSONArray(1);

            List<String> result =
                    new ArrayList<>();

            if (suggestions == null) {
                return result;
            }

            for (int i = 0;
                 i < suggestions.length();
                 i++) {

                String value =
                        suggestions.optString(i, "")
                                .trim();

                if (!value.isEmpty()
                        && !result.contains(value)) {

                    result.add(value);
                }
            }

            return result;

        } finally {

            /*
             * Only clear the reference if this is
             * still the current suggestion request.
             */
            if (suggestionCall == call) {
                suggestionCall = null;
            }
        }
    }

    /** Must be called on a background thread. */
    public static List<VideoItem> search(String query)
            throws Exception {

        String key =
                query.toLowerCase(Locale.ROOT);

        List<VideoItem> cached =
                cache.get(key);

        if (cached != null) {
            return cached;
        }

        String url =
                "https://m.youtube.com/results?search_query="
                        + URLEncoder.encode(
                                query,
                                "UTF-8"
                        );

        Request request = new Request.Builder()
                .url(url)
                .header("User-Agent", Net.USER_AGENT)
                .header(
                        "Accept-Language",
                        "en-US,en;q=0.9"
                )
                .build();

        Call call =
                Net.client.newCall(request);

        currentCall = call;

        String html;

        try (Response response =
                     call.execute()) {

            if (!response.isSuccessful()
                    || response.body() == null) {

                throw new IOException(
                        "HTTP " + response.code()
                );
            }

            html =
                    response.body().string();
        }

        JSONObject data =
                extractInitialData(html);

        if (data == null) {
            throw new Exception(
                    "Failed to parse YouTube data."
            );
        }

        List<VideoItem> results =
                new ArrayList<>();

        JSONObject sectionList =
                walk(
                        data,
                        "contents",
                        "twoColumnSearchResultsRenderer",
                        "primaryContents",
                        "sectionListRenderer"
                );

        JSONArray sections =
                sectionList == null
                        ? null
                        : sectionList.optJSONArray(
                                "contents"
                        );

        if (sections == null) {
            return results;
        }

        for (int i = 0;
             i < sections.length();
             i++) {

            JSONObject section =
                    sections.optJSONObject(i);

            if (section == null) {
                continue;
            }

            JSONObject itemSection =
                    section.optJSONObject(
                            "itemSectionRenderer"
                    );

            if (itemSection == null) {
                continue;
            }

            JSONArray items =
                    itemSection.optJSONArray(
                            "contents"
                    );

            if (items == null) {
                continue;
            }

            for (int j = 0;
                 j < items.length();
                 j++) {

                JSONObject item =
                        items.optJSONObject(j);

                if (item == null) {
                    continue;
                }

                JSONObject v =
                        item.optJSONObject(
                                "videoRenderer"
                        );

                if (v == null) {
                    continue;
                }

                String videoId =
                        v.optString(
                                "videoId",
                                ""
                        );

                if (videoId.isEmpty()) {
                    continue;
                }

                results.add(
                        new VideoItem(
                                text(v, "title"),
                                videoId,
                                "https://youtu.be/"
                                        + videoId,
                                "https://i.ytimg.com/vi/"
                                        + videoId
                                        + "/mqdefault.jpg",
                                text(
                                        v,
                                        "lengthText"
                                ),
                                text(
                                        v,
                                        "viewCountText"
                                ),
                                text(
                                        v,
                                        "publishedTimeText"
                                ),
                                text(
                                        v,
                                        "ownerText"
                                )
                        )
                );
            }
        }

        if (!results.isEmpty()) {
            cache.put(
                    key,
                    results
            );
        }

        return results;
    }

    private static JSONObject extractInitialData(
            String html
    ) throws Exception {

        final String marker =
                "ytInitialData";

        int idx = 0;

        while ((idx =
                html.indexOf(
                        marker,
                        idx
                )) >= 0) {

            int p =
                    idx + marker.length();

            while (
                    p < html.length()
                            && (
                            html.charAt(p) == ' '
                                    || html.charAt(p) == '"'
                                    || html.charAt(p) == ']'
                    )
            ) {
                p++;
            }

            if (
                    p < html.length()
                            && html.charAt(p) == '='
            ) {

                p++;

                while (
                        p < html.length()
                                && html.charAt(p) == ' '
                ) {
                    p++;
                }

                if (
                        p < html.length()
                                && html.charAt(p) == '{'
                ) {

                    return new JSONObject(
                            new JSONTokener(
                                    html.substring(p)
                            )
                    );
                }
            }

            idx += marker.length();
        }

        return null;
    }

    private static JSONObject walk(
            JSONObject obj,
            String... path
    ) {

        JSONObject current = obj;

        for (String key : path) {

            if (current == null) {
                return null;
            }

            current =
                    current.optJSONObject(key);
        }

        return current;
    }

    private static String text(
            JSONObject parent,
            String key
    ) {

        JSONObject t =
                parent.optJSONObject(key);

        if (t == null) {
            return "";
        }

        JSONArray runs =
                t.optJSONArray("runs");

        if (
                runs != null
                        && runs.length() > 0
        ) {

            JSONObject first =
                    runs.optJSONObject(0);

            if (first != null) {

                return first.optString(
                        "text",
                        ""
                );
            }
        }

        return t.optString(
                "simpleText",
                ""
        );
    }
}
