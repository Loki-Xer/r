package com.lokixer.tunetube.data;

import android.content.Context;
import android.content.SharedPreferences;

import com.lokixer.tunetube.player.AudioPrep;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/** Favorites, playlists and recently played tracks, saved on the phone. */
public class Library {

    private static final int MAX_RECENT = 20;
    private static final int MAX_HISTORY = 200;
    private static Library instance;

    public static synchronized Library get(Context context) {
        if (instance == null) instance = new Library(context.getApplicationContext());
        return instance;
    }

    private final SharedPreferences prefs;
    private final Context context;
    private final List<VideoItem> favorites = new ArrayList<>();
    private final List<Playlist> playlists = new ArrayList<>();
    private final List<VideoItem> recent = new ArrayList<>();
    private final List<VideoItem> history = new ArrayList<>();
    private final List<VideoItem> downloads = new ArrayList<>();

    private Library(Context context) {
        this.context = context.getApplicationContext();
        prefs = context.getSharedPreferences("tunetube_library", Context.MODE_PRIVATE);
        loadTracks("favorites", favorites);
        loadTracks("recent", recent);
        loadTracks("history", history);
        loadTracks("downloads", downloads);
        try {
            JSONArray arr = new JSONArray(prefs.getString("playlists", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                playlists.add(Playlist.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
    }

    private void loadTracks(String key, List<VideoItem> out) {
        try {
            JSONArray arr = new JSONArray(prefs.getString(key, "[]"));
            for (int i = 0; i < arr.length(); i++) {
                out.add(VideoItem.fromJson(arr.getJSONObject(i)));
            }
        } catch (Exception ignored) {
        }
    }

    private JSONArray tracksToJson(List<VideoItem> list) {
        JSONArray arr = new JSONArray();
        for (VideoItem v : list) arr.put(v.toJson());
        return arr;
    }

    private void save() {
        JSONArray pl = new JSONArray();
        for (Playlist p : playlists) pl.put(p.toJson());
        prefs.edit()
                .putString("favorites", tracksToJson(favorites).toString())
                .putString("recent", tracksToJson(recent).toString())
                .putString("history", tracksToJson(history).toString())
                .putString("downloads", tracksToJson(downloads).toString())
                .putString("playlists", pl.toString())
                .apply();
    }

    // ---------- favorites ----------

    public boolean isFavorite(String videoId) {
        for (VideoItem v : favorites) if (v.videoId.equals(videoId)) return true;
        return false;
    }

    /** Returns true if the track is now a favorite. */
    public boolean toggleFavorite(VideoItem item) {
        for (int i = 0; i < favorites.size(); i++) {
            if (favorites.get(i).videoId.equals(item.videoId)) {
                favorites.remove(i);
                save();
                return false;
            }
        }
        favorites.add(0, item);
        save();
        return true;
    }

    public List<VideoItem> getFavorites() {
        return new ArrayList<>(favorites);
    }

    // ---------- playlists ----------

    public List<Playlist> getPlaylists() {
        return new ArrayList<>(playlists);
    }

    public Playlist getPlaylist(String id) {
        for (Playlist p : playlists) if (p.id.equals(id)) return p;
        return null;
    }

    public Playlist createPlaylist(String name) {
        Playlist p = new Playlist(UUID.randomUUID().toString(), name);
        playlists.add(0, p);
        save();
        return p;
    }

    public void renamePlaylist(String id, String name) {
        Playlist p = getPlaylist(id);
        if (p != null) {
            p.name = name;
            save();
        }
    }

    public void deletePlaylist(String id) {
        playlists.removeIf(p -> p.id.equals(id));
        save();
    }

    public void setPlaylistLoop(String id, boolean loop) {
        Playlist p = getPlaylist(id);
        if (p != null) {
            p.loop = loop;
            save();
        }
    }

    /** Returns false if the track was already in the playlist. */
    public boolean addToPlaylist(String playlistId, VideoItem item) {
        Playlist p = getPlaylist(playlistId);
        if (p == null || p.contains(item.videoId)) return false;
        p.tracks.add(item);
        save();
        return true;
    }

    public void removeFromPlaylist(String playlistId, String videoId) {
        Playlist p = getPlaylist(playlistId);
        if (p == null) return;
        p.tracks.removeIf(t -> t.videoId.equals(videoId));
        save();
    }

    // ---------- recently played ----------

    public List<VideoItem> getRecent() {
        return new ArrayList<>(recent);
    }

    public void addRecent(VideoItem item) {
        recent.removeIf(v -> v.videoId.equals(item.videoId));
        recent.add(0, item);
        while (recent.size() > MAX_RECENT) recent.remove(recent.size() - 1);
        save();
    }

    // ---------- play history (every song played, newest first) ----------

    public List<VideoItem> getHistory() {
        return new ArrayList<>(history);
    }

    /** Adds a played song to the top. A song repeated twice in a row is saved only once. */
    public void addHistory(VideoItem item) {
        if (!history.isEmpty() && history.get(0).videoId.equals(item.videoId)) return;
        history.add(0, item);
        while (history.size() > MAX_HISTORY) history.remove(history.size() - 1);
        save();
    }

    public void clearHistory() {
        history.clear();
        save();
    }

    // ---------- downloads ----------

    public List<VideoItem> getDownloads() {
        List<VideoItem> out = new ArrayList<>();
        for (VideoItem item : downloads) {
            if (AudioPrep.isDownloaded(context, item.videoId)) out.add(item);
        }
        return out;
    }

    public void addDownload(VideoItem item) {
        if (item == null) return;
        downloads.removeIf(v -> v.videoId.equals(item.videoId));
        downloads.add(0, item);
        save();
    }

    public void removeDownload(String videoId) {
        downloads.removeIf(v -> v.videoId.equals(videoId));
        save();
    }

    public boolean isDownloaded(String videoId) {
        return AudioPrep.isDownloaded(context, videoId);
    }

}
