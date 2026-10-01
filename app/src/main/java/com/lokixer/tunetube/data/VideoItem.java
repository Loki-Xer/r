package com.lokixer.tunetube.data;

import org.json.JSONObject;

public class VideoItem {
    public final String title;
    public final String videoId;
    public final String url;
    public final String thumbnail;
    public final String duration;
    public final String views;
    public final String publishedTime;
    public final String author;

    public VideoItem(String title, String videoId, String url, String thumbnail,
                     String duration, String views, String publishedTime, String author) {
        this.title = title;
        this.videoId = videoId;
        this.url = url;
        this.thumbnail = thumbnail;
        this.duration = duration;
        this.views = views;
        this.publishedTime = publishedTime;
        this.author = author;
    }

    public JSONObject toJson() {
        try {
            JSONObject o = new JSONObject();
            o.put("title", title);
            o.put("videoId", videoId);
            o.put("url", url);
            o.put("thumbnail", thumbnail);
            o.put("duration", duration);
            o.put("views", views);
            o.put("publishedTime", publishedTime);
            o.put("author", author);
            return o;
        } catch (Exception e) {
            return new JSONObject();
        }
    }

    public static VideoItem fromJson(JSONObject o) {
        return new VideoItem(
                o.optString("title"), o.optString("videoId"), o.optString("url"),
                o.optString("thumbnail"), o.optString("duration"), o.optString("views"),
                o.optString("publishedTime"), o.optString("author"));
    }
}
