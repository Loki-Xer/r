package com.lokixer.tunetube.data;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** Remembers the last searches on the phone. */
public class SearchHistory {

    private static final String KEY = "recent_searches";
    private static final int MAX = 10;

    private final SharedPreferences prefs;

    public SearchHistory(Context context) {
        prefs = context.getSharedPreferences("tunetube_prefs", Context.MODE_PRIVATE);
    }

    public List<String> getAll() {
        List<String> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                list.add(arr.getString(i));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    public void add(String query) {
        List<String> list = getAll();
        list.removeIf(s -> s.equalsIgnoreCase(query));
        list.add(0, query);
        while (list.size() > MAX) {
            list.remove(list.size() - 1);
        }
        save(list);
    }

    public void remove(String query) {
        List<String> list = getAll();
        list.removeIf(s -> s.equalsIgnoreCase(query));
        save(list);
    }

    public void clear() {
        save(new ArrayList<>());
    }

    // ---------- songs searched and played (Spotify-style recents) ----------

    private static final String TRACKS_KEY = "recent_search_tracks";
    private static final int MAX_TRACKS = 30;

    public List<VideoItem> getTracks() {
        List<VideoItem> list = new ArrayList<>();
        try {
            JSONArray arr = new JSONArray(prefs.getString(TRACKS_KEY, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                list.add(VideoItem.fromJson(o));
            }
        } catch (Exception ignored) {
        }
        return list;
    }

    /** Newest played song goes first; a repeated song moves back to the top. */
    public void addTrack(VideoItem item) {
        if (item == null || item.videoId == null || item.videoId.isEmpty()) return;
        List<VideoItem> list = getTracks();
        list.removeIf(v -> v.videoId.equals(item.videoId));
        list.add(0, item);
        while (list.size() > MAX_TRACKS) {
            list.remove(list.size() - 1);
        }
        saveTracks(list);
    }

    public void removeTrack(String videoId) {
        List<VideoItem> list = getTracks();
        list.removeIf(v -> v.videoId.equals(videoId));
        saveTracks(list);
    }

    public void clearTracks() {
        saveTracks(new ArrayList<>());
    }

    private void saveTracks(List<VideoItem> list) {
        JSONArray arr = new JSONArray();
        for (VideoItem v : list) arr.put(v.toJson());
        prefs.edit().putString(TRACKS_KEY, arr.toString()).apply();
    }

    private void save(List<String> list) {
        prefs.edit().putString(KEY, new JSONArray(list).toString()).apply();
    }
}
