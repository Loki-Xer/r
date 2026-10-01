package com.lokixer.tunetube.player;

import android.app.PendingIntent;
import android.content.Intent;

import com.lokixer.tunetube.MainActivity;
import com.lokixer.tunetube.R;

import androidx.annotation.Nullable;
import androidx.media3.common.Player;
import androidx.media3.session.DefaultMediaNotificationProvider;
import androidx.media3.session.MediaSession;
import androidx.media3.session.MediaSessionService;

/**
 * Keeps the music alive in the background and shows the media notification
 * (cover art, title, previous / play-pause / next, seek bar) and the lock screen controls.
 */
public class PlaybackService extends MediaSessionService {

    private MediaSession session;

    @Override
    public void onCreate() {
        super.onCreate();

        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        PendingIntent contentIntent = PendingIntent.getActivity(
                this, 0, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        session = new MediaSession.Builder(this, MusicPlayer.get(this).sessionPlayer())
                .setSessionActivity(contentIntent)
                .build();

        // Register the session with this service so Media3 posts the media notification
        // (and promotes the service to foreground) whenever playback starts.
        addSession(session);

        DefaultMediaNotificationProvider provider =
                new DefaultMediaNotificationProvider.Builder(this).build();
        provider.setSmallIcon(R.drawable.ic_music_note);
        setMediaNotificationProvider(provider);
    }

    @Nullable
    @Override
    public MediaSession onGetSession(MediaSession.ControllerInfo controllerInfo) {
        return session;
    }

    @Override
    public void onTaskRemoved(@Nullable Intent rootIntent) {
        // Swiping the app away stops the service, unless music is playing.
        if (session == null) {
            stopSelf();
            return;
        }
        Player p = session.getPlayer();
        if (!p.getPlayWhenReady() || p.getMediaItemCount() == 0) stopSelf();
    }

    @Override
    public void onDestroy() {
        if (session != null) {
            session.release(); // the player itself lives in MusicPlayer, so it isn't released
            session = null;
        }
        super.onDestroy();
    }
}
