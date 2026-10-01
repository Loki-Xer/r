package com.lokixer.tunetube.player;

import android.content.Context;
import android.content.Intent;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import com.lokixer.tunetube.data.Library;
import com.lokixer.tunetube.data.Playlist;
import com.lokixer.tunetube.data.VideoItem;
import com.lokixer.tunetube.net.Net;
import com.lokixer.tunetube.net.NextSongs;
import com.lokixer.tunetube.net.StreamFetcher;

import okhttp3.Request;
import okhttp3.Response;

import androidx.media3.common.AudioAttributes;
import androidx.media3.common.C;
import androidx.media3.common.ForwardingPlayer;
import androidx.media3.common.MediaItem;
import androidx.media3.common.MediaMetadata;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;

import java.util.ArrayList;
import java.util.List;

/**
 * Shared music player.
 *
 * Recommendation pipeline:
 *
 *   current song starts
 *        ↓
 *   scrape fresh YouTube suggestions for THIS song
 *        ↓
 *   choose next song
 *        ↓
 *   fetch next song audio URL
 *        ↓
 *   AudioPrep prepares/converts it if necessary
 *        ↓
 *   next() attaches the already-prepared song immediately
 *        ↓
 *   that song becomes the NEW recommendation seed
 *        ↓
 *   repeat forever
 *
 * The recommendation pipeline is independent of the search-result queue.
 * Therefore the Next button always follows fresh suggestions from the
 * currently playing song.
 */
public class MusicPlayer {

    public interface Listener {
        void onStateChanged(boolean isPlaying, boolean isLoading);
        void onProgress(long positionMs, long durationMs);
        void onTrackChanged(VideoItem item);
        void onArtwork(String url);
        void onError(String message);
    }

    private static MusicPlayer instance;

    private final Context context;
    private final ExoPlayer player;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final android.content.SharedPreferences playbackPrefs;
    private long lastPositionSaveMs;
    private Listener listener;

    // Search queue is still kept for starting a selected search result,
    // but Next NEVER walks this queue. Previous uses the play trail.
    private List<VideoItem> queue = new ArrayList<>();
    private int index = -1;

    private VideoItem current;
    private String loadedId;
    private String artworkUrl;
    private boolean resolving;
    private boolean retried;

    private int activePlayToken;
    private int token;
    private Player sessionPlayer;

    // Played-song trail. Previous walks through this.
    private static final int MAX_TRAIL = 300;
    private final List<VideoItem> trail = new ArrayList<>();
    private int trailPos = -1;

    // ------------------------------------------------------------
    // NEXT RECOMMENDATION / PRELOAD STATE
    // ------------------------------------------------------------

    /**
     * The song selected by the recommendation algorithm for the current song.
     * This is metadata only until nextAudioUrl is available.
     */
    private VideoItem suggestedNext;
    private String suggestedForId;
    private String suggestedAudioUrl;
    private String suggestedArtwork;
    private boolean suggestionLoading;
    private boolean nextPreloading;

    /**
     * Changes whenever the current track changes.
     * Old background recommendation jobs are ignored when their token
     * no longer matches.
     */
    private int recommendationToken;

    /**
     * Prevents two Next operations from starting two recommendation jobs.
     */
    private boolean nextRequestRunning;

    // ------------------------------------------------------------
    // PLAYLIST MODE
    // ------------------------------------------------------------

    private boolean playlistMode;
    private String playlistId;
    private List<VideoItem> playlistTracks = new ArrayList<>();
    private int playlistIndex = -1;
    private boolean playlistLoop;
    private boolean repeatCurrent;
    private boolean playlistOfflineOnly;

    // Exact next playlist item, preloaded without starting playback.
    private VideoItem playlistNext;
    private String playlistNextAudio;
    private String playlistNextArtwork;
    private boolean playlistNextLoading;
    // A manual/automatic advance requested while the exact next playlist
    // track is still preparing. The preload callback consumes it once ready.
    private boolean playlistAdvancePending;

    // When an online playlist reaches its final track, recommendations resume
    // automatically. Offline playlists never leave playlist mode.
    private boolean autoPlayRecommendation;

    public static MusicPlayer get(Context context) {
        if (instance == null) {
            instance = new MusicPlayer(context.getApplicationContext());
        }
        return instance;
    }

    private MusicPlayer(Context context) {
        this.context = context;
        playbackPrefs = context.getSharedPreferences("tunetube_playback", Context.MODE_PRIVATE);

        // Restore saved history for Previous.
        List<VideoItem> saved = Library.get(context).getHistory();
        for (int i = Math.min(saved.size(), MAX_TRAIL) - 1; i >= 0; i--) {
            trail.add(saved.get(i));
        }
        trailPos = trail.size() - 1;

        DefaultLoadControl loadControl = new DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                        2_500,
                        30_000,
                        1_000,
                        1_500
                )
                .build();

        AudioAttributes attrs = new AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .build();

        player = new ExoPlayer.Builder(context)
                .setLoadControl(loadControl)
                .setAudioAttributes(attrs, true)
                .setHandleAudioBecomingNoisy(true)
                .setWakeMode(C.WAKE_MODE_NETWORK)
                .build();

        // Music app: never decode video.
        player.setTrackSelectionParameters(
                player.getTrackSelectionParameters()
                        .buildUpon()
                        .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                        .build()
        );

        player.setRepeatMode(Player.REPEAT_MODE_OFF);

        player.addListener(new Player.Listener() {

            @Override
            public void onIsPlayingChanged(boolean isPlaying) {
                notifyState();

                if (isPlaying) {
                    startProgressUpdates();
                } else {
                    stopProgressUpdates();
                }
            }

            @Override
            public void onPlaybackStateChanged(int state) {
                notifyState();

                if (state == Player.STATE_READY) {
                    retried = false;
                    tickProgress();

                } else if (state == Player.STATE_ENDED) {

                    // Song finished: move on automatically to the next one.
                    // Ignore a stale "ended" event from an item we no longer own.
                    MediaItem endedItem = player.getCurrentMediaItem();

                    boolean ownsEnded = current != null
                            && !resolving
                            && (endedItem == null
                            || endedItem.mediaId == null
                            || current.videoId.equals(endedItem.mediaId));

                    if (ownsEnded) {
                        handler.post(MusicPlayer.this::next);
                    }
                }
            }

            @Override
            public void onPlayerError(PlaybackException error) {
                // A resolved YouTube URL may expire.
                if (!retried && current != null) {
                    retried = true;

                    StreamFetcher.invalidate(current.videoId);

                    loadedId = null;
                    resolveAndPlay();

                } else {
                    resolving = false;
                    notifyState();

                    if (listener != null) {
                        listener.onError(
                                "Couldn't play this track."
                        );
                    }
                }
            }
        });
    }

    public void setListener(Listener l) {
        listener = l;
    }

    public Listener getListener() {
        return listener;
    }

    // ------------------------------------------------------------
    // STATE
    // ------------------------------------------------------------

    public VideoItem getCurrent() {
        return current;
    }

    public String getArtworkUrl() {
        return artworkUrl;
    }

    public boolean isPlaying() {
        return player.isPlaying();
    }

    /**
     * Loading state for the CURRENT audio only.
     * Recommendation/preload work must never hide the play/pause button.
     */
    public boolean isLoading() {
        int state = player.getPlaybackState();
        return resolving
                || state == Player.STATE_BUFFERING
                || (current != null
                    && state == Player.STATE_IDLE
                    && !player.isPlaying());
    }

    public long getDuration() {
        return Math.max(0, player.getDuration());
    }

    public long getCurrentPosition() {
        return Math.max(0, player.getCurrentPosition());
    }

    private void notifyState() {
        if (listener != null) {
            listener.onStateChanged(
                    player.isPlaying(),
                    isLoading()
            );
        }
    }

    private final Runnable progressTick = new Runnable() {
        @Override
        public void run() {
            tickProgress();
            handler.postDelayed(this, 250);
        }
    };

    private void tickProgress() {
        long pos = Math.max(0, player.getCurrentPosition());
        long dur = Math.max(0, player.getDuration());
        if (current != null && System.currentTimeMillis() - lastPositionSaveMs > 1000) {
            savePlaybackPosition(pos);
        }
        if (listener != null) {
            listener.onProgress(pos, dur);
        }
    }

    private void savePlaybackPosition(long position) {
        if (current == null) return;
        lastPositionSaveMs = System.currentTimeMillis();
        playbackPrefs.edit()
                .putString("current_id", current.videoId)
                .putString("current_json", current.toJson().toString())
                .putLong("position_" + current.videoId, Math.max(0, position))
                .apply();
    }

    public void savePlaybackState() {
        savePlaybackPosition(Math.max(0, player.getCurrentPosition()));
        playbackPrefs.edit().putBoolean("was_playing", player.isPlaying()).apply();
    }

    /** Restores the last visible/playing song after the app process was recreated. */
    public void restoreLastSession() {
        if (current != null || player.getCurrentMediaItem() != null) return;
        try {
            String json = playbackPrefs.getString("current_json", null);
            if (json == null || json.isEmpty()) return;
            VideoItem saved = VideoItem.fromJson(new org.json.JSONObject(json));
            if (saved == null) return;
            current = saved;
            artworkUrl = saved.thumbnail;
            if (listener != null) {
                listener.onTrackChanged(saved);
                listener.onArtwork(artworkUrl);
            }
            if (playbackPrefs.getBoolean("was_playing", false)) {
                if (!hasNetwork() && !AudioPrep.isDownloaded(context, saved.videoId)) return;
                resolveAndPlay();
            }
        } catch (Exception ignored) {
        }
    }

    public boolean isOffline() {
        return !hasNetwork();
    }

    private void startProgressUpdates() {
        handler.removeCallbacks(progressTick);
        handler.post(progressTick);
    }

    private void stopProgressUpdates() {
        handler.removeCallbacks(progressTick);
        tickProgress();
    }

    // ------------------------------------------------------------
    // STARTING A SONG
    // ------------------------------------------------------------

    /**
     * Starts a selected search result.
     *
     * IMPORTANT:
     * The search list is only used to start the first song.
     * After that, Next is controlled exclusively by recommendations.
     */
    public void playQueue(List<VideoItem> items, int start) {
        if (items == null
                || start < 0
                || start >= items.size()) {
            return;
        }

        List<VideoItem> source = new ArrayList<>(items);
        if (!hasNetwork()) {
            List<VideoItem> local = new ArrayList<>();
            for (VideoItem v : source) {
                if (AudioPrep.isDownloaded(context, v.videoId)) local.add(v);
            }
            if (local.isEmpty()) {
                if (listener != null) listener.onError("Offline: no downloaded songs are available.");
                return;
            }
            VideoItem requested = source.get(start);
            int localStart = 0;
            for (int i = 0; i < local.size(); i++) {
                if (local.get(i).videoId.equals(requested.videoId)) { localStart = i; break; }
            }
            source = local;
            start = localStart;
        }

        VideoItem target = source.get(start);

        exitPlaylistMode();
        queue = source;
        index = start;

        if (target.videoId.equals(loadedId) && !resolving) {
            current = target;
            togglePlayPause();
            return;
        }

        playCurrent(true);
    }

    public void playSingle(VideoItem item) {
        if (item == null) return;

        List<VideoItem> one = new ArrayList<>();
        one.add(item);

        playQueue(one, 0);
    }

    /** Starts a playlist in strict playlist order. Suggestions are disabled. */
    public void playPlaylist(Playlist playlist, int start) {
        if (playlist == null || playlist.tracks.isEmpty()
                || start < 0 || start >= playlist.tracks.size()) return;

        List<VideoItem> tracks = new ArrayList<>(playlist.tracks);
        boolean offline = !hasNetwork();

        // Offline playlist mode is intentionally local-only. Do not attempt
        // network resolution or jump to recommendations.
        if (offline) {
            List<VideoItem> local = new ArrayList<>();
            for (VideoItem item : tracks) {
                if (AudioPrep.getLocalAudio(context, item.videoId) != null) {
                    local.add(item);
                }
            }
            tracks = local;
            if (tracks.isEmpty()) {
                if (listener != null) listener.onError("No downloaded songs are available offline in this playlist.");
                return;
            }

            VideoItem requested = playlist.tracks.get(start);
            int localStart = -1;
            for (int i = 0; i < tracks.size(); i++) {
                if (tracks.get(i).videoId.equals(requested.videoId)) {
                    localStart = i;
                    break;
                }
            }
            start = localStart >= 0 ? localStart : 0;
        }

        nextRequestRunning = false;
        playlistAdvancePending = false;
        playlistMode = true;
        playlistId = playlist.id;
        playlistTracks = tracks;
        playlistIndex = start;
        playlistLoop = playlist.loop;
        playlistOfflineOnly = offline;
        autoPlayRecommendation = false;

        queue.clear();
        index = -1;
        clearPlaylistPreload();
        VideoItem first = playlistTracks.get(playlistIndex);
        if (playlistOfflineOnly) {
            String local = AudioPrep.getLocalAudio(context, first.videoId);
            if (local == null) {
                stopAtPlaylistEnd();
                if (listener != null) listener.onError("Downloaded playlist track is no longer available offline.");
                return;
            }
            playItem(first, true, local, first.thumbnail);
        } else {
            playItem(first, true);
        }
    }

    public void playPlaylist(Playlist playlist) {
        if (playlist == null) return;
        playPlaylist(playlist, 0);
    }

    private boolean hasNetwork() {
        try {
            ConnectivityManager cm = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (cm == null) return false;
            Network n = cm.getActiveNetwork();
            return n != null && cm.getNetworkCapabilities(n) != null;
        } catch (Exception ignored) {
            return false;
        }
    }

    // ------------------------------------------------------------
    // NEXT / PREVIOUS
    // ------------------------------------------------------------

    /**
     * ALWAYS uses the recommendation generated from the currently playing
     * song. It never simply advances the search-result queue.
     *
     * If the recommendation has already been resolved/prepared, it starts
     * immediately.
     *
     * If the user presses Next before preload finishes, the recommendation
     * request is completed on demand and then played.
     */
    public void next() {
        if (current == null) return;

        // Offline mode never calls the network recommendation pipeline.
        // Move only through tracks that are actually downloaded.
        if (!hasNetwork() && !playlistMode) {
            nextOfflineDownload();
            return;
        }

        // Playlist navigation must never be blocked by a stale recommendation
        // request. Playlist order has priority while playlist mode is active.
        if (playlistMode) {
            nextPlaylistTrack();
            return;
        }

        if (nextRequestRunning) return;

        nextRequestRunning = true;
        notifyState();

        final VideoItem readyItem = suggestedNext;
        final String readyUrl = suggestedAudioUrl;
        final String readyArt = suggestedArtwork;
        final String readyFor = suggestedForId;

        // The recommendation metadata is already known for THIS current song.
        // Switch to it immediately, even when its audio is still preloading.
        // This stops the old song at once and lets the UI show the new title,
        // artwork and loading animation instead of silently waiting.
        if (readyItem != null
                && current.videoId.equals(readyFor)) {

            nextRequestRunning = false;

            clearRecommendationState();

            autoPlayRecommendation = false;
            appendAndPlayRecommended(
                    readyItem,
                    readyUrl,
                    readyArt != null ? readyArt : bestArtwork(readyItem)
            );

            return;
        }

        // No recommendation metadata exists yet. Fetch the recommendation
        // first; once selected, the handler below switches immediately to it.
        final VideoItem item = readyItem;
        final String seedId = current.videoId;

        new Thread(() -> {
            VideoItem selected = item;
            String audio = readyUrl;

            try {
                if (selected == null) {
                    List<VideoItem> suggestions =
                            NextSongs.getNextVideos(
                                    watchUrl(current)
                            );

                    selected = pickSuggestion(suggestions);

                    if (selected == null) {
                        throw new Exception("No recommendation found");
                    }
                }

                // Do NOT wait for the audio download here. Metadata is enough
                // to switch the UI immediately. playItem()/resolveAndPlay()
                // will resolve the new track after the old one is stopped.
                final VideoItem finalItem = selected;
                final String finalAudio = audio;

                handler.post(() -> {
                    nextRequestRunning = false;

                    // User may have changed songs while this was running.
                    // Never attach an old recommendation to a new song.
                    if (current == null
                            || !seedId.equals(current.videoId)) {
                        notifyState();
                        return;
                    }

                    // The recommendation is now the selected destination.
                    // Switch immediately so the old song stops and the UI
                    // shows the new title/artwork + loading indicator while
                    // the stream is being prepared.
                    clearRecommendationState();
                    autoPlayRecommendation = false;
                    appendAndPlayRecommended(
                            finalItem,
                            finalAudio,
                            bestArtwork(finalItem)
                    );
                });

            } catch (Exception ignored) {

                handler.post(() -> {
                    nextRequestRunning = false;
                    notifyState();

                    if (listener != null) {
                        listener.onError(
                                "Couldn't find the next recommended song."
                        );
                    }
                });
            }
        }).start();
    }

    private void nextOfflineDownload() {
        List<VideoItem> downloads = Library.get(context).getDownloads();
        if (downloads.isEmpty()) {
            if (listener != null) listener.onError("Offline: no downloaded songs are available.");
            return;
        }
        int next = 0;
        for (int i = 0; i < downloads.size(); i++) {
            if (downloads.get(i).videoId.equals(current.videoId)) {
                next = (i + 1) % downloads.size();
                break;
            }
        }
        if (downloads.size() > 0 && current != null
                && downloads.get(downloads.size() - 1).videoId.equals(current.videoId)) {
            player.pause();
            notifyState();
            return;
        }
        VideoItem target = downloads.get(next);
        String local = AudioPrep.getDownloadedAudio(context, target.videoId);
        if (local == null) {
            if (listener != null) listener.onError("Downloaded file is missing.");
            return;
        }
        playItem(target, true, local, target.thumbnail);
    }

    private void nextPlaylistTrack() {
        if (!playlistMode || playlistTracks.isEmpty()) return;

        int nextIndex = playlistIndex + 1;
        if (nextIndex >= playlistTracks.size()) {
            if (playlistLoop) {
                nextIndex = 0;
            } else {
                if (playlistOfflineOnly) {
                    stopAtPlaylistEnd();
                    return;
                }

                // Online playlists hand control back to the recommendation
                // algorithm only AFTER the final playlist song has ended.
                autoPlayRecommendation = true;
                playlistMode = false;
                playlistId = null;
                playlistTracks.clear();
                playlistIndex = -1;
                clearPlaylistPreload();
                startRecommendationPipeline(current);
                return;
            }
        }

        final VideoItem target = playlistTracks.get(nextIndex);
        final int targetIndex = nextIndex;

        if (playlistNext != null
                && target.videoId.equals(playlistNext.videoId)
                && playlistNextAudio != null) {
            playlistIndex = targetIndex;
            VideoItem item = playlistNext;
            String audio = playlistNextAudio;
            String art = playlistNextArtwork;
            clearPlaylistPreload();
            playItem(item, true, audio, art);
            return;
        }

        // The exact playlist item is already chosen. If its preload is still
        // running, switch the UI/player to THAT exact item immediately.
        // Do not keep the old song playing and do not wait for the preload
        // callback. playItem() stops the old media item first, updates the
        // title/artwork, and exposes the loading indicator while the stream
        // is resolved.
        if (playlistNextLoading) {
            playlistAdvancePending = false;
            playlistIndex = targetIndex;
            clearPlaylistPreload();
            playItem(target, true);
            return;
        }

        playlistIndex = targetIndex;
        clearPlaylistPreload();
        playItem(target, true);
    }

    public boolean isRepeatCurrent() {
        return repeatCurrent;
    }

    public void toggleRepeatCurrent() {
        repeatCurrent = !repeatCurrent;
        player.setRepeatMode(repeatCurrent ? Player.REPEAT_MODE_ONE : Player.REPEAT_MODE_OFF);
        notifyState();
    }

    private void stopAtPlaylistEnd() {
        playlistMode = false;
        playlistId = null;
        playlistTracks.clear();
        playlistIndex = -1;
        clearPlaylistPreload();
        autoPlayRecommendation = false;
        player.pause();
        player.seekTo(0);
        notifyState();
    }

    private void exitPlaylistMode() {
        playlistAdvancePending = false;
        playlistMode = false;
        playlistId = null;
        playlistTracks.clear();
        playlistIndex = -1;
        playlistLoop = false;
        playlistOfflineOnly = false;
        autoPlayRecommendation = false;
        clearPlaylistPreload();
    }

    private void clearPlaylistPreload() {
        playlistNext = null;
        playlistNextAudio = null;
        playlistNextArtwork = null;
        playlistNextLoading = false;
        playlistAdvancePending = false;
    }

    /** Preloads only the next playlist item; it never starts playback. */
    private void preloadPlaylistNext() {
        if (!playlistMode || playlistTracks.isEmpty()) return;

        int nextIndex = playlistIndex + 1;
        if (nextIndex >= playlistTracks.size()) {
            if (!playlistLoop) return;
            nextIndex = 0;
        }

        final VideoItem target = playlistTracks.get(nextIndex);
        if (target == null) return;

        playlistNext = target;
        playlistNextLoading = true;
        notifyState();

        new Thread(() -> {
            String prepared = AudioPrep.getLocalAudio(context, target.videoId);
            try {
                if (prepared == null && !playlistOfflineOnly) {
                    String url = StreamFetcher.getAudioUrl(target.videoId);
                    if (url != null) {
                        prepared = AudioPrep.prepare(context, target.videoId, url);
                    }
                }
            } catch (Exception ignored) {
            }

            final String finalPrepared = prepared;
            handler.post(() -> {
                if (!playlistMode
                        || current == null
                        || playlistNext == null
                        || !target.videoId.equals(playlistNext.videoId)) return;
                playlistNextLoading = false;
                playlistNextAudio = finalPrepared;
                playlistNextArtwork = bestArtwork(target);
                notifyState();

                // Consume the exact queued playlist item if Next was pressed
                // or the current track ended while it was preparing.
                if (playlistAdvancePending
                        || player.getPlaybackState() == Player.STATE_ENDED) {
                    playlistAdvancePending = false;
                    nextPlaylistTrack();
                }
            });
        }).start();
    }

    /**
     * Previous is history-based and does not ask YouTube for a new
     * recommendation. When the user moves forward again, the new current
     * song starts its own recommendation pipeline.
     */
    public void previous() {
        if (current == null) return;

        if (playlistMode) {
            if (player.getCurrentPosition() > 3000) {
                player.seekTo(0);
                return;
            }
            int previousIndex = playlistIndex - 1;
            if (previousIndex < 0) {
                if (!playlistLoop) {
                    player.seekTo(0);
                    return;
                }
                previousIndex = playlistTracks.size() - 1;
            }
            playlistIndex = previousIndex;
            clearPlaylistPreload();
            playItem(playlistTracks.get(playlistIndex), true);
            return;
        }

        if (player.getCurrentPosition() > 3000
                || trailPos <= 0) {
            player.seekTo(0);
            return;
        }

        trailPos--;

        VideoItem previous = trail.get(trailPos);

        // Do not use the search queue here.
        queue.clear();
        index = -1;

        playItem(previous, false);
    }

    /**
     * Adds a recommendation to the trail and starts it.
     */
    private void appendAndPlayRecommended(
            VideoItem item,
            String preparedAudio,
            String art
    ) {
        queue.clear();
        index = -1;

        playItem(item, true, preparedAudio, art);
    }

    // ------------------------------------------------------------
    // RECOMMENDATION PIPELINE
    // ------------------------------------------------------------

    private static String watchUrl(VideoItem item) {
        return "https://www.youtube.com/watch?v="
                + item.videoId;
    }

    /**
     * Picks the first recommendation that is not the current song and has
     * not appeared recently in the play trail.
     */
    private VideoItem pickSuggestion(List<VideoItem> list) {
        if (list == null || list.isEmpty()) {
            return null;
        }

        int from = Math.max(0, trail.size() - 50);

        for (VideoItem candidate : list) {
            if (candidate == null
                    || candidate.videoId == null
                    || candidate.videoId.isEmpty()) {
                continue;
            }

            if (current != null
                    && candidate.videoId.equals(current.videoId)) {
                continue;
            }

            boolean recentlyPlayed = false;

            for (int i = from; i < trail.size(); i++) {
                if (trail.get(i).videoId.equals(candidate.videoId)) {
                    recentlyPlayed = true;
                    break;
                }
            }

            if (!recentlyPlayed) {
                return candidate;
            }
        }

        // If every result was recently played, still keep music going.
        for (VideoItem candidate : list) {
            if (candidate != null
                    && (current == null
                    || !candidate.videoId.equals(current.videoId))) {
                return candidate;
            }
        }

        return null;
    }

    /**
     * Starts a COMPLETELY FRESH recommendation pipeline for every song.
     *
     * It does not care whether the current song came from:
     * - Search
     * - Previous
     * - Recommendation
     * - Notification Next
     * - Mini player
     */
    private void startRecommendationPipeline(
            final VideoItem seed
    ) {
        if (seed == null) return;

        final int myRecommendationToken =
                ++recommendationToken;

        suggestedNext = null;
        suggestedForId = seed.videoId;
        suggestedAudioUrl = null;
        suggestedArtwork = null;

        suggestionLoading = true;
        nextPreloading = false;

        notifyState();

        new Thread(() -> {

            List<VideoItem> suggestions = null;

            try {
                // FRESH YouTube recommendation request for THIS song.
                suggestions = NextSongs.getNextVideos(
                        watchUrl(seed)
                );
            } catch (Exception ignored) {
            }

            final VideoItem selected =
                    pickSuggestion(suggestions);

            handler.post(() -> {

                // A newer song is playing.
                if (myRecommendationToken != recommendationToken
                        || current == null
                        || !seed.videoId.equals(current.videoId)) {
                    return;
                }

                suggestionLoading = false;

                if (selected == null) {
                    suggestedForId = null;
                    notifyState();

                    if (listener != null) {
                        listener.onError(
                                "No recommended next song found."
                        );
                    }

                    return;
                }

                // Metadata is ready BEFORE the current song ends.
                suggestedNext = selected;
                suggestedForId = seed.videoId;

                // Now preload the actual next audio.
                preloadRecommendedAudio(
                        seed,
                        selected,
                        myRecommendationToken
                );

                notifyState();
            });
        }).start();
    }

    /**
     * Resolves/prepares the exact recommendation selected above.
     *
     * This means Next does not have to:
     *   scrape suggestion -> resolve stream -> prepare
     *
     * at button-press time.
     */
    private void preloadRecommendedAudio(
            final VideoItem seed,
            final VideoItem nextItem,
            final int myRecommendationToken
    ) {
        nextPreloading = true;
        notifyState();

        new Thread(() -> {

            String prepared = null;

            try {
                String audioUrl =
                        StreamFetcher.getAudioUrl(
                                nextItem.videoId
                        );

                if (audioUrl != null) {
                    // If this is a video container, AudioPrep creates an
                    // audio-only local file. If it is already audio, the
                    // resolved URL is reused.
                    prepared = AudioPrep.prepare(
                            context,
                            nextItem.videoId,
                            audioUrl
                    );
                }

            } catch (Exception ignored) {
            }

            final String finalPrepared = prepared;

            handler.post(() -> {

                if (myRecommendationToken != recommendationToken
                        || current == null
                        || !seed.videoId.equals(current.videoId)
                        || suggestedNext == null
                        || !nextItem.videoId.equals(
                                suggestedNext.videoId
                        )) {
                    return;
                }

                nextPreloading = false;

                if (finalPrepared == null) {
                    // Keep metadata. Next can retry stream resolution
                    // on demand without fetching a different recommendation.
                    suggestedAudioUrl = null;
                    suggestedArtwork = nextItem.thumbnail;
                } else {
                    suggestedAudioUrl = finalPrepared;
                    suggestedArtwork = nextItem.thumbnail;
                }

                notifyState();
                if (autoPlayRecommendation && suggestedAudioUrl != null) {
                    next();
                }
            });
        }).start();
    }

    private void clearRecommendationState() {
        ++recommendationToken;

        suggestedNext = null;
        suggestedForId = null;
        suggestedAudioUrl = null;
        suggestedArtwork = null;

        suggestionLoading = false;
        nextPreloading = false;

        notifyState();
    }

    // ------------------------------------------------------------
    // TRACK LOADING
    // ------------------------------------------------------------

    /**
     * Starts a normal track and immediately begins the recommendation
     * pipeline once that track has been attached.
     */
    private void playCurrent(boolean record) {
        if (queue == null
                || index < 0
                || index >= queue.size()) {
            return;
        }

        VideoItem item = queue.get(index);

        playItem(item, record);
    }

    private void playItem(
            VideoItem item,
            boolean record
    ) {
        playItem(item, record, null, null);
    }

    /**
     * Main track transition.
     *
     * The old media item is detached BEFORE resolving the new one.
     * This prevents the old audio from continuing while the UI already
     * shows the new title/artwork.
     */
    private void playItem(
            VideoItem item,
            boolean record,
            String preparedAudio,
            String preparedArtwork
    ) {
        if (item == null) return;

        current = item;
        retried = false;

        // Cancel/ignore all recommendation work belonging to the old song.
        clearRecommendationState();

        artworkUrl = item.thumbnail;

        token++;
        activePlayToken = token;

        player.pause();
        player.stop();
        player.clearMediaItems();

        loadedId = null;

        Library library = Library.get(context);

        library.addRecent(item);

        savePlaybackPosition(0);

        if (record) {
            if (trail.isEmpty()
                    || !trail.get(trail.size() - 1)
                    .videoId.equals(item.videoId)) {

                trail.add(item);

                if (trail.size() > MAX_TRAIL) {
                    trail.remove(0);
                }
            }

            trailPos = trail.size() - 1;

            // Only actual playback transitions are recorded.
            // Recommendation preloading NEVER reaches this code.
            library.addHistory(item);
        }

        if (listener != null) {
            listener.onTrackChanged(item);
            listener.onArtwork(artworkUrl);
        }

        if (preparedAudio != null) {
            // The recommendation was already resolved/prepared.
            final String art =
                    preparedArtwork != null
                            ? preparedArtwork
                            : item.thumbnail;

            handler.post(() -> {
                if (current == null
                        || !item.videoId.equals(current.videoId)) {
                    return;
                }

                artworkUrl = art;

                if (listener != null) {
                    listener.onArtwork(art);
                }

                start(item, preparedAudio, art);

                if (playlistMode) {
                    preloadPlaylistNext();
                } else {
                    startRecommendationPipeline(item);
                }
            });

            return;
        }

        resolveAndPlay();
    }

    /**
     * Resolves the CURRENT song.
     */
    private void resolveAndPlay() {
        final VideoItem item = current;

        if (item == null) return;

        final int myToken = ++token;

        activePlayToken = myToken;
        resolving = true;

        player.pause();

        notifyState();

        new Thread(() -> {

            String url = null;
            String art = item.thumbnail;

            try {
                url = StreamFetcher.getAudioUrl(
                        item.videoId
                );

                if (url != null) {
                    url = AudioPrep.prepare(
                            context,
                            item.videoId,
                            url
                    );
                }

            } catch (Exception ignored) {
                StreamFetcher.invalidate(
                        item.videoId
                );
                url = null;
            }

            if (url != null) {
                art = bestArtwork(item);
            }

            final String finalUrl = url;
            final String finalArt = art;

            handler.post(() -> {

                if (myToken != token) {
                    return;
                }

                resolving = false;

                if (finalUrl == null) {
                    notifyState();

                    if (listener != null) {
                        listener.onError(
                                "Couldn't find an audio stream for this video"
                        );
                    }

                    return;
                }

                // User selected another song while this was downloading.
                if (current == null
                        || !item.videoId.equals(
                                current.videoId
                        )
                        || myToken != activePlayToken) {
                    return;
                }

                artworkUrl = finalArt;

                if (listener != null) {
                    listener.onArtwork(finalArt);
                }

                start(
                        item,
                        finalUrl,
                        finalArt
                );

                if (playlistMode) {
                    preloadPlaylistNext();
                } else {
                    startRecommendationPipeline(item);
                }
            });
        }).start();
    }

    private void start(
            VideoItem item,
            String streamUrl,
            String art
    ) {
        MediaMetadata metadata =
                new MediaMetadata.Builder()
                        .setTitle(item.title)
                        .setDisplayTitle(item.title)
                        .setArtist(item.author)
                        .setArtworkUri(
                                Uri.parse(art)
                        )
                        .build();

        Uri uri = streamUrl.startsWith("/")
                ? Uri.fromFile(
                        new java.io.File(streamUrl)
                )
                : Uri.parse(streamUrl);

        MediaItem mediaItem =
                new MediaItem.Builder()
                        .setMediaId(item.videoId)
                        .setUri(uri)
                        .setMediaMetadata(metadata)
                        .build();

        loadedId = item.videoId;

        player.setMediaItem(mediaItem);
        player.prepare();
        long savedPosition = playbackPrefs.getLong("position_" + item.videoId, 0L);
        if (savedPosition > 0) player.seekTo(savedPosition);
        player.play();

        try {
            context.startService(
                    new Intent(
                            context,
                            PlaybackService.class
                    )
            );
        } catch (Exception ignored) {
        }
    }

    /**
     * Uses maxres artwork if available.
     */
    private static String bestArtwork(
            VideoItem item
    ) {
        String big =
                "https://i.ytimg.com/vi/"
                        + item.videoId
                        + "/maxresdefault.jpg";

        try {
            Request request =
                    new Request.Builder()
                            .url(big)
                            .head()
                            .header(
                                    "User-Agent",
                                    Net.USER_AGENT
                            )
                            .build();

            try (Response response =
                         Net.client.newCall(
                                 request
                         ).execute()) {

                if (response.isSuccessful()) {
                    return big;
                }
            }

        } catch (Exception ignored) {
        }

        return item.thumbnail;
    }

    // ------------------------------------------------------------
    // PLAY / PAUSE / SEEK
    // ------------------------------------------------------------

    public void togglePlayPause() {
        if (resolving
                || current == null) {
            return;
        }

        if (player.getPlaybackState()
                == Player.STATE_IDLE
                && loadedId != null) {
            player.prepare();
        }

        if (player.isPlaying()) {
            player.pause();

        } else {
            if (player.getPlaybackState()
                    == Player.STATE_ENDED) {
                player.seekTo(0);
            }

            player.play();
        }
    }

    public void seekTo(long positionMs) {
        player.seekTo(positionMs);
    }

    // ------------------------------------------------------------
    // MEDIA SESSION / NOTIFICATION
    // ------------------------------------------------------------

    /**
     * Notification Next/Previous buttons use the exact same logic as
     * the in-app buttons.
     */
    public Player sessionPlayer() {
        if (sessionPlayer == null) {

            sessionPlayer =
                    new ForwardingPlayer(player) {

                        @Override
                        public Player.Commands
                        getAvailableCommands() {

                            return super
                                    .getAvailableCommands()
                                    .buildUpon()
                                    .addAll(
                                            Player.COMMAND_SEEK_TO_NEXT,
                                            Player.COMMAND_SEEK_TO_PREVIOUS,
                                            Player.COMMAND_SEEK_TO_NEXT_MEDIA_ITEM,
                                            Player.COMMAND_SEEK_TO_PREVIOUS_MEDIA_ITEM
                                    )
                                    .build();
                        }

                        @Override
                        public boolean
                        isCommandAvailable(
                                int command
                        ) {
                            return getAvailableCommands()
                                    .contains(command);
                        }

                        @Override
                        public boolean
                        hasNextMediaItem() {
                            // The recommendation pipeline is designed
                            // to always find/generate another song.
                            return true;
                        }

                        @Override
                        public boolean
                        hasPreviousMediaItem() {
                            return trailPos > 0;
                        }

                        @Override
                        public void seekToNext() {
                            MusicPlayer.this.next();
                        }

                        @Override
                        public void seekToNextMediaItem() {
                            MusicPlayer.this.next();
                        }

                        @Override
                        public void seekToPrevious() {
                            MusicPlayer.this.previous();
                        }

                        @Override
                        public void seekToPreviousMediaItem() {
                            MusicPlayer.this.previous();
                        }
                    };
        }

        return sessionPlayer;
    }
}
