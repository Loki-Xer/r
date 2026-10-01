# Tune Tube

A music app for Android (Java) by **Loki** (LokiXer). Built by GitHub Actions.

- **Home**: greeting and recently played songs (no search bar)
- **Search**: songs you searched and played show up as recent searches, like Spotify
- **Library** (opens on History): History, Favorites, Playlists (grid), Downloads
  (empty state: "Download songs to listen offline")
- Songs move on to the next one automatically
- Full-screen Now Playing with wavy seek bar and cover-tinted background
- Media notification and lock screen controls

## Project layout

```
app/src/main/java/com/lokixer/tunetube/
  MainActivity.java        screens and navigation
  data/                    VideoItem, Playlist, Library, SearchHistory
  net/                     Net, YoutubeSearch, NextSongs, StreamFetcher
  player/                  MusicPlayer, PlaybackService, AudioPrep
  ui/                      Adapters (all lists), WaveSeekBar, SquareImageView
```
