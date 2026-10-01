package com.lokixer.tunetube.data;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

public class Playlist {
    public final String id;
    public String name;
    /** Loop this playlist when the last track finishes. */
    public boolean loop;
    public final List<VideoItem> tracks = new ArrayList<>();

    public Playlist(String id, String name) {
        this.id = id;
        this.name = name;
    }

    public boolean contains(String videoId) {
        for (VideoItem t : tracks) if (t.videoId.equals(videoId)) return true;
        return false;
    }

    public JSONObject toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("id", id);
            o.put("name", name);
            o.put("loop", loop);
            JSONArray arr = new JSONArray();
            for (VideoItem t : tracks) arr.put(t.toJson());
            o.put("tracks", arr);
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static Playlist fromJson(JSONObject o) {
        Playlist p = new Playlist(o.optString("id"), o.optString("name"));
        p.loop = o.optBoolean("loop", false);
        JSONArray arr = o.optJSONArray("tracks");
        if (arr != null) {
            for (int i = 0; i < arr.length(); i++) {
                JSONObject t = arr.optJSONObject(i);
                if (t != null) p.tracks.add(VideoItem.fromJson(t));
            }
        }
        return p;
    }
}
