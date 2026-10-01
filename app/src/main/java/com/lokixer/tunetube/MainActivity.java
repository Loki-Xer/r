package com.lokixer.tunetube;

import android.Manifest;
import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.PopupMenu;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.target.CustomTarget;
import com.bumptech.glide.request.transition.Transition;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

import com.lokixer.tunetube.data.Library;
import com.lokixer.tunetube.data.Playlist;
import com.lokixer.tunetube.data.SearchHistory;
import com.lokixer.tunetube.data.VideoItem;
import com.lokixer.tunetube.net.StreamFetcher;
import com.lokixer.tunetube.net.YoutubeSearch;
import com.lokixer.tunetube.player.AudioPrep;
import com.lokixer.tunetube.player.MusicPlayer;
import com.lokixer.tunetube.ui.Adapters.CardAdapter;
import com.lokixer.tunetube.ui.Adapters.PlaylistAdapter;
import com.lokixer.tunetube.ui.Adapters.RecentAdapter;
import com.lokixer.tunetube.ui.Adapters.RecentTrackAdapter;
import com.lokixer.tunetube.ui.Adapters.SuggestionAdapter;
import com.lokixer.tunetube.ui.Adapters.TrackAdapter;
import com.lokixer.tunetube.ui.WaveSeekBar;

import androidx.activity.OnBackPressedCallback;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.ColorUtils;
import androidx.palette.graphics.Palette;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;

public class MainActivity extends AppCompatActivity implements MusicPlayer.Listener {

    private static final int FIRST_BATCH = 6;

    private View root, homePage, searchPage, libraryPage, nowPlayingOverlay;
    private BottomNavigationView nav;

    // Home
    private TextView homeGreeting, homeClearAll, homeSearchEmpty;
    private View homePlayedSection;
    private CardAdapter cardAdapter;
    private RecentAdapter homeSearchAdapter;

    // Search
    private ImageView backBtn, clearBtn;
    private TextView statusText, clearAll, searchTitle, recentTitle;
    private View recentPanel;
    private EditText searchInput;
    private ProgressBar progress;
    private RecyclerView resultsList, recentList, suggestionsList;
    private TrackAdapter searchAdapter;
    private RecentTrackAdapter recentAdapter;
    private SuggestionAdapter suggestionAdapter;
    private final Handler suggestionHandler = new Handler(Looper.getMainLooper());
    private Runnable suggestionRunnable;
    private int suggestionId = 0;

    // Library
    private TextView libTitle, tabFavorites, tabPlaylists, tabHistory, tabDownloads, historyCount, historyEmpty, favCount, favEmpty, playlistEmpty,
            detailName, detailCount, detailEmpty, playAllFav, downloadCount, downloadEmpty;
    private View libTabs, libFavorites, libPlaylists, libHistory, libDetail, libDownloads;
    private TrackAdapter favAdapter, detailAdapter, historyAdapter, downloadAdapter;
    private PlaylistAdapter playlistAdapter;
    private static final int TAB_HISTORY = 0, TAB_FAVORITES = 1, TAB_PLAYLISTS = 2, TAB_DOWNLOADS = 3;
    private int libTab = TAB_HISTORY;
    private boolean openDownloadsFirst;
    private String openPlaylistId;

    // Mini player
    private View miniPlayer, miniProgressFill;
    private ImageView miniThumb, miniPlayPause, miniHeart;
    private TextView miniTitle, miniArtist;
    private ProgressBar miniLoading;

    // Now Playing
    private ImageView nowPlayingArt, nowPlayingPlayPause, nowPlayingHeart;
    private TextView nowPlayingTitle, nowPlayingArtist, nowPlayingPosition, nowPlayingDuration;
    private WaveSeekBar nowPlayingSeek;
    private ProgressBar nowPlayingLoading;
    private TextView nowPlayingDownload, nowPlayingRepeat;
    private static final String DOWNLOAD_CHANNEL = "downloads";
    private ValueAnimator loadingPulse;
    private int npTint;
    private ValueAnimator npTintAnim;

    private SearchHistory history;
    private Library library;
    private MusicPlayer musicPlayer;
    private OnBackPressedCallback backCallback;

    private boolean searchMode = false;
    private boolean loading = false;
    private int searchId = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        ensureDownloadChannel();
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 9101);
        }

        bindViews();

        history = new SearchHistory(this);
        library = Library.get(this);
        musicPlayer = MusicPlayer.get(this);
        musicPlayer.setListener(this);
        musicPlayer.restoreLastSession();
        YoutubeSearch.warmUp();

        npTint = ContextCompat.getColor(this, R.color.bg);

        setupLists();
        setupSearch();
        setupPlayerControls();
        setupLibraryControls();

        backCallback = new OnBackPressedCallback(false) {
            @Override
            public void handleOnBackPressed() {
                if (nowPlayingOverlay.getVisibility() == View.VISIBLE) {
                    closeNowPlaying();
                } else if (searchMode) {
                    exitSearchMode();
                } else if (openPlaylistId != null) {
                    closePlaylist();
                }
                updateBack();
            }
        };
        getOnBackPressedDispatcher().addCallback(this, backCallback);

        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_home) showHome();
            else if (id == R.id.nav_search) showSearchPage();
            else if (id == R.id.nav_library) {
                // Library always opens on History (or Downloads when starting offline).
                libTab = openDownloadsFirst ? TAB_DOWNLOADS : TAB_HISTORY;
                openDownloadsFirst = false;
                showLibrary();
            }
            return true;
        });
        if (musicPlayer.isOffline() && !library.getDownloads().isEmpty()) {
            openDownloadsFirst = true;
            nav.setSelectedItemId(R.id.nav_library);
        } else {
            nav.setSelectedItemId(R.id.nav_home);
        }

        refreshLibraryUi();
        restorePlayerUi();

        // Android 13+ needs this permission for the music notification to appear
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
    }

    @Override
    protected void onPause() {
        if (musicPlayer != null) musicPlayer.savePlaybackState();
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        if (musicPlayer != null) musicPlayer.savePlaybackState();
        if (musicPlayer.getListener() == this) musicPlayer.setListener(null);
        super.onDestroy();
    }

    private void bindViews() {
        root = findViewById(R.id.root);
        homePage = findViewById(R.id.homePage);
        searchPage = findViewById(R.id.searchPage);
        libraryPage = findViewById(R.id.libraryPage);
        nowPlayingOverlay = findViewById(R.id.nowPlayingOverlay);
        nav = findViewById(R.id.bottomNav);

        homeGreeting = findViewById(R.id.homeGreeting);
        homeClearAll = findViewById(R.id.homeClearAll);
        homeSearchEmpty = findViewById(R.id.homeSearchEmpty);
        homePlayedSection = findViewById(R.id.homePlayedSection);

        searchTitle = findViewById(R.id.searchTitle);
        searchInput = findViewById(R.id.searchInput);
        backBtn = findViewById(R.id.backBtn);
        clearBtn = findViewById(R.id.clearBtn);
        clearAll = findViewById(R.id.clearAll);
        recentTitle = findViewById(R.id.recentTitle);
        recentPanel = findViewById(R.id.recentPanel);
        progress = findViewById(R.id.progress);
        statusText = findViewById(R.id.statusText);
        resultsList = findViewById(R.id.resultsList);
        recentList = findViewById(R.id.recentList);
        suggestionsList = findViewById(R.id.suggestionsList);

        libTitle = findViewById(R.id.libTitle);
        libTabs = findViewById(R.id.libTabs);
        libFavorites = findViewById(R.id.libFavorites);
        libPlaylists = findViewById(R.id.libPlaylists);
        libHistory = findViewById(R.id.libHistory);
        tabHistory = findViewById(R.id.tabHistory);
        tabDownloads = findViewById(R.id.tabDownloads);
        historyCount = findViewById(R.id.historyCount);
        historyEmpty = findViewById(R.id.historyEmpty);
        libDownloads = findViewById(R.id.libDownloads);
        downloadCount = findViewById(R.id.downloadCount);
        downloadEmpty = findViewById(R.id.downloadEmpty);
        libDetail = findViewById(R.id.libDetail);
        tabFavorites = findViewById(R.id.tabFavorites);
        tabPlaylists = findViewById(R.id.tabPlaylists);
        favCount = findViewById(R.id.favCount);
        favEmpty = findViewById(R.id.favEmpty);
        playAllFav = findViewById(R.id.playAllFav);
        playlistEmpty = findViewById(R.id.playlistEmpty);
        detailName = findViewById(R.id.detailName);
        detailCount = findViewById(R.id.detailCount);
        detailEmpty = findViewById(R.id.detailEmpty);

        miniPlayer = findViewById(R.id.miniPlayer);
        miniProgressFill = findViewById(R.id.miniProgressFill);
        miniThumb = findViewById(R.id.miniThumb);
        miniTitle = findViewById(R.id.miniTitle);
        miniArtist = findViewById(R.id.miniArtist);
        miniPlayPause = findViewById(R.id.miniPlayPause);
        miniHeart = findViewById(R.id.miniHeart);
        miniLoading = findViewById(R.id.miniLoading);

        nowPlayingArt = findViewById(R.id.nowPlayingArt);
        nowPlayingTitle = findViewById(R.id.nowPlayingTitle);
        nowPlayingArtist = findViewById(R.id.nowPlayingArtist);
        nowPlayingSeek = findViewById(R.id.nowPlayingSeek);
        nowPlayingPosition = findViewById(R.id.nowPlayingPosition);
        nowPlayingDuration = findViewById(R.id.nowPlayingDuration);
        nowPlayingPlayPause = findViewById(R.id.nowPlayingPlayPause);
        nowPlayingHeart = findViewById(R.id.nowPlayingHeart);
        nowPlayingLoading = findViewById(R.id.nowPlayingLoading);
        nowPlayingDownload = findViewById(R.id.nowPlayingDownload);
        nowPlayingRepeat = findViewById(R.id.nowPlayingRepeat);
    }

    // ==================== setup ====================

    /**
     * @param inPlaylist true for the open-playlist list
     * @param single     true = tapping plays only that song and then continues with
     *                   suggested songs; false = the whole list is the queue
     */
    private TrackAdapter.Listener trackListener(final boolean inPlaylist, final boolean single) {
        return new TrackAdapter.Listener() {
            @Override
            public void onPlay(List<VideoItem> all, int position) {
                if (single) {
                    musicPlayer.playSingle(all.get(position));
                } else if (inPlaylist && openPlaylistId != null) {
                    Playlist p = library.getPlaylist(openPlaylistId);
                    if (p != null) musicPlayer.playPlaylist(p, position);
                } else {
                    musicPlayer.playQueue(all, position);
                }
            }

            @Override
            public void onFavorite(VideoItem item) {
                toggleFavorite(item);
            }

            @Override
            public void onMore(VideoItem item, View anchor) {
                showTrackMenu(item, anchor, inPlaylist);
            }
        };
    }

    private void setupLists() {
        // Search results
        resultsList.setLayoutManager(new LinearLayoutManager(this));
        resultsList.setHasFixedSize(true);
        resultsList.setItemViewCacheSize(10);
        final TrackAdapter.Listener plain = trackListener(false, true);
        searchAdapter = new TrackAdapter(library, new TrackAdapter.Listener() {
            @Override
            public void onPlay(List<VideoItem> all, int position) {
                // Remember the song you searched for and played (Spotify-style).
                history.addTrack(all.get(position));
                plain.onPlay(all, position);
            }

            @Override
            public void onFavorite(VideoItem item) {
                plain.onFavorite(item);
            }

            @Override
            public void onMore(VideoItem item, View anchor) {
                plain.onMore(item, anchor);
            }
        });
        resultsList.setAdapter(searchAdapter);

        // Recent searches (inside the search page)
        recentList.setLayoutManager(new LinearLayoutManager(this));
        recentAdapter = new RecentTrackAdapter(new RecentTrackAdapter.Listener() {
            @Override
            public void onPick(VideoItem item) {
                history.addTrack(item);
                musicPlayer.playSingle(item);
                exitSearchMode();
                searchInput.setText("");
            }

            @Override
            public void onRemove(VideoItem item) {
                history.removeTrack(item.videoId);
                refreshRecent();
            }
        });
        recentList.setAdapter(recentAdapter);
        suggestionsList.setLayoutManager(new LinearLayoutManager(this));

        suggestionAdapter = new SuggestionAdapter(query -> {
            searchInput.setText(query);
            searchInput.setSelection(query.length());
            doSearch();
        });
        suggestionsList.setAdapter(suggestionAdapter);

        // Home: recently played cards
        RecyclerView homePlayedList = findViewById(R.id.homePlayedList);
        homePlayedList.setLayoutManager(
                new LinearLayoutManager(this, LinearLayoutManager.HORIZONTAL, false));
        cardAdapter = new CardAdapter((all, position) -> musicPlayer.playSingle(all.get(position)));
        homePlayedList.setAdapter(cardAdapter);

        // Home: search history
        RecyclerView homeSearchList = findViewById(R.id.homeSearchList);
        homeSearchList.setLayoutManager(new LinearLayoutManager(this));
        homeSearchList.setNestedScrollingEnabled(false);
        homeSearchAdapter = new RecentAdapter(new RecentAdapter.Listener() {
            @Override
            public void onPick(String query) {
                nav.setSelectedItemId(R.id.nav_search);
                searchInput.setText(query);
                searchInput.setSelection(query.length());
                doSearch();
            }

            @Override
            public void onRemove(String query) {
                history.remove(query);
                refreshHome();
            }
        });
        homeSearchList.setAdapter(homeSearchAdapter);

        homeClearAll.setOnClickListener(v -> {
            history.clear();
            refreshHome();
        });

        // Library lists
        RecyclerView favList = findViewById(R.id.favList);
        favList.setLayoutManager(new LinearLayoutManager(this));
        favAdapter = new TrackAdapter(library, trackListener(false, false));
        favList.setAdapter(favAdapter);

        RecyclerView historyList = findViewById(R.id.historyList);
        historyList.setLayoutManager(new LinearLayoutManager(this));
        historyAdapter = new TrackAdapter(library, trackListener(false, true));
        historyList.setAdapter(historyAdapter);

        RecyclerView downloadList = findViewById(R.id.downloadList);
        downloadList.setLayoutManager(new LinearLayoutManager(this));
        downloadAdapter = new TrackAdapter(library, trackListener(false, true));
        downloadList.setAdapter(downloadAdapter);

        RecyclerView detailList = findViewById(R.id.detailList);
        detailList.setLayoutManager(new LinearLayoutManager(this));
        detailAdapter = new TrackAdapter(library, trackListener(true, false));
        detailList.setAdapter(detailAdapter);

        RecyclerView playlistList = findViewById(R.id.playlistList);
        playlistList.setLayoutManager(new GridLayoutManager(this, 2));
        playlistAdapter = new PlaylistAdapter(new PlaylistAdapter.Listener() {
            @Override
            public void onOpen(Playlist playlist) {
                openPlaylist(playlist.id);
            }

            @Override
            public void onMore(Playlist playlist, View anchor) {
                showPlaylistMenu(playlist, anchor);
            }
        });
        playlistList.setAdapter(playlistAdapter);
    }

    private void setupSearch() {
        searchInput.setOnFocusChangeListener((v, hasFocus) -> {
            if (hasFocus) enterSearchMode();
        });
        searchInput.setOnClickListener(v -> enterSearchMode());
        searchInput.setOnEditorActionListener((v, actionId, event) -> {
            if (actionId == EditorInfo.IME_ACTION_SEARCH) {
                doSearch();
                return true;
            }
            return false;
        });
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int a) { }
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { }

            @Override
            public void afterTextChanged(Editable s) {
                clearBtn.setVisibility(s.length() > 0 ? View.VISIBLE : View.GONE);
                if (searchMode) updateSearchSuggestions(s.toString());
            }
        });
        clearBtn.setOnClickListener(v -> {
            searchInput.setText("");
            searchInput.requestFocus();
            enterSearchMode();
        });
        clearAll.setOnClickListener(v -> {
            history.clearTracks();
            refreshRecent();
        });
        backBtn.setOnClickListener(v -> {
            exitSearchMode();
            updateBack();
        });
    }

    private void setupPlayerControls() {
        // Mini player: buttons act, tapping anywhere else opens Now Playing
        miniPlayPause.setOnClickListener(v -> musicPlayer.togglePlayPause());
        miniHeart.setOnClickListener(v -> {
            VideoItem c = musicPlayer.getCurrent();
            if (c != null) toggleFavorite(c);
        });
        miniPlayer.setOnClickListener(v -> openNowPlaying());

        findViewById(R.id.nowPlayingClose).setOnClickListener(v -> closeNowPlaying());
        nowPlayingPlayPause.setOnClickListener(v -> musicPlayer.togglePlayPause());
        findViewById(R.id.nowPlayingPrev).setOnClickListener(v -> musicPlayer.previous());
        findViewById(R.id.nowPlayingNext).setOnClickListener(v -> musicPlayer.next());
        nowPlayingDownload.setOnClickListener(v -> {
            VideoItem c = musicPlayer.getCurrent();
            if (c != null) downloadSong(c);
        });
        nowPlayingRepeat.setOnClickListener(v -> {
            musicPlayer.toggleRepeatCurrent();
            updateRepeatButton();
        });
        nowPlayingHeart.setOnClickListener(v -> {
            VideoItem c = musicPlayer.getCurrent();
            if (c != null) toggleFavorite(c);
        });
        findViewById(R.id.nowPlayingAddPlaylist).setOnClickListener(v -> {
            VideoItem c = musicPlayer.getCurrent();
            if (c != null) showPlaylistPicker(c);
        });

        nowPlayingSeek.setListener(new WaveSeekBar.Listener() {
            @Override
            public void onSeekStart() { }

            @Override
            public void onSeekPreview(long positionMs) {
                nowPlayingPosition.setText(formatTime(positionMs));
            }

            @Override
            public void onSeekEnd(long positionMs) {
                musicPlayer.seekTo(positionMs);
            }
        });
    }

    private void setupLibraryControls() {
        tabFavorites.setOnClickListener(v -> {
            libTab = TAB_FAVORITES;
            applyLibraryMode();
        });
        tabPlaylists.setOnClickListener(v -> {
            libTab = TAB_PLAYLISTS;
            applyLibraryMode();
        });
        tabHistory.setOnClickListener(v -> {
            libTab = TAB_HISTORY;
            applyLibraryMode();
        });
        tabDownloads.setOnClickListener(v -> {
            libTab = TAB_DOWNLOADS;
            applyLibraryMode();
        });
        findViewById(R.id.clearHistoryBtn).setOnClickListener(v -> {
            if (library.getHistory().isEmpty()) return;
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Clear history?")
                    .setMessage("Your play history will be removed. Favorites and playlists stay.")
                    .setPositiveButton("Clear", (d, w) -> {
                        library.clearHistory();
                        refreshLibraryUi();
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
        });
        playAllFav.setOnClickListener(v -> playFavorites(library.getFavorites()));
        findViewById(R.id.newPlaylistBtn).setOnClickListener(v -> promptName(
                "New playlist", "", "Create", name -> {
                    library.createPlaylist(name);
                    refreshLibraryUi();
                    toast("Created \"" + name + "\"");
                }));
        findViewById(R.id.detailBack).setOnClickListener(v -> {
            closePlaylist();
            updateBack();
        });
        findViewById(R.id.detailPlay).setOnClickListener(v -> {
            Playlist p = library.getPlaylist(openPlaylistId);
            if (p != null) musicPlayer.playPlaylist(p);
        });
        findViewById(R.id.detailDelete).setOnClickListener(v -> {
            Playlist p = library.getPlaylist(openPlaylistId);
            if (p != null) confirmDelete(p);
        });
    }

    // ==================== pages ====================

    private void showHome() {
        homePage.setVisibility(View.VISIBLE);
        searchPage.setVisibility(View.GONE);
        libraryPage.setVisibility(View.GONE);
        homeGreeting.setText(greeting());
        refreshHome();
    }

    private void showSearchPage() {
        homePage.setVisibility(View.GONE);
        searchPage.setVisibility(View.VISIBLE);
        libraryPage.setVisibility(View.GONE);
        updateStatus();
    }

    private void showLibrary() {
        homePage.setVisibility(View.GONE);
        searchPage.setVisibility(View.GONE);
        libraryPage.setVisibility(View.VISIBLE);
        refreshLibraryUi();
        applyLibraryMode();
    }

    private String greeting() {
        int h = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        if (h < 12) return "Good morning";
        if (h < 17) return "Good afternoon";
        return "Good evening";
    }

    private void updateChrome() {
        nav.setVisibility(searchMode ? View.GONE : View.VISIBLE);
        boolean hasTrack = musicPlayer.getCurrent() != null;
        miniPlayer.setVisibility(hasTrack && !searchMode ? View.VISIBLE : View.GONE);
    }

    private void updateBack() {
        backCallback.setEnabled(nowPlayingOverlay.getVisibility() == View.VISIBLE
                || searchMode || openPlaylistId != null);
    }

    // ==================== home ====================

    private void refreshHome() {
        List<String> searches = history.getAll();
        homeSearchAdapter.setItems(searches);
        homeSearchEmpty.setVisibility(searches.isEmpty() ? View.VISIBLE : View.GONE);
        homeClearAll.setVisibility(searches.isEmpty() ? View.GONE : View.VISIBLE);

        List<VideoItem> played = library.getRecent();
        cardAdapter.setItems(played);
        homePlayedSection.setVisibility(played.isEmpty() ? View.GONE : View.VISIBLE);
    }

    // ==================== search mode ====================

    private void enterSearchMode() {
        if (searchMode) return;
        searchMode = true;
        updateBack();

        searchTitle.setVisibility(View.GONE);
        backBtn.setVisibility(View.VISIBLE);
        resultsList.setVisibility(View.GONE);
        suggestionsList.setVisibility(View.GONE);
        statusText.setVisibility(View.GONE);
        updateChrome();
        refreshRecent();

        searchInput.requestFocus();
        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.showSoftInput(searchInput, InputMethodManager.SHOW_IMPLICIT);
    }

    private void exitSearchMode() {
        if (!searchMode) return;
        searchMode = false;

        InputMethodManager imm = getSystemService(InputMethodManager.class);
        if (imm != null) imm.hideSoftInputFromWindow(searchInput.getWindowToken(), 0);
        searchPage.requestFocus();

        searchTitle.setVisibility(View.VISIBLE);
        backBtn.setVisibility(View.GONE);
        recentPanel.setVisibility(View.GONE);
        suggestionsList.setVisibility(View.GONE);
        resultsList.setVisibility(View.VISIBLE);
        updateChrome();
        updateStatus();
        updateBack();
    }

    private void refreshRecent() {
        String typed = searchInput.getText().toString().trim().toLowerCase(Locale.ROOT);
        List<VideoItem> shown = new ArrayList<>();
        for (VideoItem v : history.getTracks()) {
            if (typed.isEmpty()
                    || v.title.toLowerCase(Locale.ROOT).contains(typed)
                    || (v.author != null && v.author.toLowerCase(Locale.ROOT).contains(typed))) {
                shown.add(v);
            }
        }
        recentAdapter.setItems(shown);
        clearAll.setVisibility(typed.isEmpty() ? View.VISIBLE : View.GONE);
        recentTitle.setText("Recent searches");
        recentList.setVisibility(View.VISIBLE);
        suggestionAdapter.setItems(new ArrayList<>());
        recentPanel.setVisibility(searchMode && !shown.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void updateSearchSuggestions(String text) {
        final String query = text == null ? "" : text.trim();

        // Cancel the previous debounce timer and network request.
        if (suggestionRunnable != null) {
            suggestionHandler.removeCallbacks(suggestionRunnable);
        }
        YoutubeSearch.cancelSuggestions();

        // Invalidate every older request, including a request that is
        // already on its way back to the UI.
        final int myId = ++suggestionId;

        if (query.isEmpty()) {
            suggestionAdapter.setItems(new ArrayList<>());
            suggestionsList.setVisibility(View.GONE);
            refreshRecent();
            return;
        }

        suggestionRunnable = () -> {
            final int requestId = myId;

            new Thread(() -> {
                try {
                    List<String> suggestions =
                            YoutubeSearch.getSuggestions(query);

                    runOnUiThread(() -> {
                        // Ignore an old response.
                        if (requestId != suggestionId
                                || !searchMode
                                || isFinishing()) {
                            return;
                        }

                        if (suggestions == null
                                || suggestions.isEmpty()) {
                            suggestionAdapter.setItems(new ArrayList<>());
                            suggestionsList.setVisibility(View.GONE);
                            recentPanel.setVisibility(View.GONE);
                            return;
                        }

                        recentTitle.setText("Suggestions");
                        clearAll.setVisibility(View.GONE);

                        recentAdapter.setItems(new ArrayList<>());
                        recentList.setVisibility(View.GONE);

                        suggestionAdapter.setItems(suggestions);
                        suggestionsList.setVisibility(View.VISIBLE);
                        recentPanel.setVisibility(View.VISIBLE);
                    });

                } catch (Exception ignored) {
                    runOnUiThread(() -> {
                        if (requestId != suggestionId) {
                            return;
                        }

                        suggestionAdapter.setItems(new ArrayList<>());
                        suggestionsList.setVisibility(View.GONE);
                    });
                }
            }).start();
        };

        // Wait until the user pauses typing before making the request.
        suggestionHandler.postDelayed(
                suggestionRunnable,
                250
        );
    }

    private void updateStatus() {
        boolean show = !searchMode && !loading && searchAdapter.getItemCount() == 0;
        statusText.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // ==================== searching ====================

    private void doSearch() {
        String query = searchInput.getText().toString().trim();
        if (query.isEmpty()) return;

        // Stop autocomplete immediately when a real search starts.
        if (suggestionRunnable != null) suggestionHandler.removeCallbacks(suggestionRunnable);
        suggestionId++;
        YoutubeSearch.cancelSuggestions();

        history.add(query);
        refreshHome();
        exitSearchMode();

        final int myId = ++searchId;
        YoutubeSearch.cancelCurrent();

        loading = true;
        progress.setVisibility(View.VISIBLE);
        searchAdapter.setItems(new ArrayList<>());
        updateStatus();

        new Thread(() -> {
            try {
                List<VideoItem> results = YoutubeSearch.search(query);
                runOnUiThread(() -> {
                    if (myId != searchId || isFinishing()) return;
                    loading = false;
                    progress.setVisibility(View.GONE);

                    int first = Math.min(FIRST_BATCH, results.size());
                    searchAdapter.setItems(new ArrayList<>(results.subList(0, first)));
                    resultsList.scrollToPosition(0);

                    statusText.setText("No results found");
                    updateStatus();

                    if (results.size() > first) {
                        List<VideoItem> rest =
                                new ArrayList<>(results.subList(first, results.size()));
                        resultsList.post(() -> {
                            if (myId == searchId) searchAdapter.addItems(rest);
                        });
                    }
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    if (myId != searchId || isFinishing()) return;
                    loading = false;
                    progress.setVisibility(View.GONE);
                    searchAdapter.setItems(new ArrayList<>());
                    statusText.setText("Couldn't load results.\nCheck your internet and try again.");
                    updateStatus();
                });
            }
        }).start();
    }

    // ==================== favorites / playlists ====================

    private void playFavorites(List<VideoItem> tracks) {
        if (tracks.isEmpty()) {
            toast("Nothing to play yet");
            return;
        }
        musicPlayer.playQueue(tracks, 0);
    }

    private void toggleFavorite(VideoItem item) {
        boolean now = library.toggleFavorite(item);
        toast(now ? "Added to favorites" : "Removed from favorites");
        refreshLibraryUi();
    }

    private void showTrackMenu(VideoItem item, View anchor, boolean inPlaylist) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Add to playlist");
        menu.getMenu().add(0, 2, 1, library.isFavorite(item.videoId)
                ? "Remove from favorites" : "Add to favorites");
        menu.getMenu().add(0, 5, 2, library.isDownloaded(item.videoId)
                ? "Delete download" : "Download song");
        if (inPlaylist && openPlaylistId != null) {
            menu.getMenu().add(0, 3, 2, "Remove from this playlist");
        }
        menu.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1:
                    showPlaylistPicker(item);
                    return true;
                case 2:
                    toggleFavorite(item);
                    return true;
                case 5:
                    if (library.isDownloaded(item.videoId)) {
                        AudioPrep.deleteDownloaded(MainActivity.this, item.videoId);
                        library.removeDownload(item.videoId);
                        refreshLibraryUi();
                        toast("Download deleted");
                    } else {
                        downloadSong(item);
                    }
                    return true;
                case 3:
                    library.removeFromPlaylist(openPlaylistId, item.videoId);
                    refreshLibraryUi();
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private void downloadSong(VideoItem item) {
        if (library.isDownloaded(item.videoId) || AudioPrep.isDownloaded(this, item.videoId)) {
            toast("Already downloaded");
            updateDownloadButton();
            return;
        }
        showDownloadNotification(item, "Downloading…", 0, false);
        toast("Downloading…");
        new Thread(() -> {
            try {
                String url = StreamFetcher.getAudioUrl(item.videoId);
                if (url == null) throw new Exception("No audio stream");
                AudioPrep.download(this, item.videoId, url);
                library.addDownload(item);
                runOnUiThread(() -> { refreshLibraryUi(); updateDownloadButton(); toast("Downloaded"); });
                showDownloadNotification(item, "Download complete", 100, true);
            } catch (Exception e) {
                showDownloadNotification(item, "Download failed", 0, true);
                runOnUiThread(() -> toast("Download failed"));
            }
        }).start();
    }

    private void ensureDownloadChannel() {
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null && nm.getNotificationChannel(DOWNLOAD_CHANNEL) == null) {
                nm.createNotificationChannel(new NotificationChannel(
                        DOWNLOAD_CHANNEL, "Downloads", NotificationManager.IMPORTANCE_LOW));
            }
        }
    }

    private void showDownloadNotification(VideoItem item, String text, int progress, boolean finished) {
        ensureDownloadChannel();
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (nm == null) return;
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, DOWNLOAD_CHANNEL)
                .setSmallIcon(R.drawable.ic_music_note)
                .setContentTitle(item.title == null ? "Song download" : item.title)
                .setContentText(text)
                .setOngoing(!finished)
                .setOnlyAlertOnce(true)
                .setPriority(NotificationCompat.PRIORITY_LOW);
        if (!finished) b.setProgress(0, 0, true);
        else b.setProgress(100, progress, false).setAutoCancel(true);
        nm.notify(2000 + Math.abs(item.videoId.hashCode() % 1000), b.build());
    }

    private void updateDownloadButton() {
        VideoItem c = musicPlayer == null ? null : musicPlayer.getCurrent();
        if (nowPlayingDownload == null || c == null) return;
        boolean downloaded = library != null && (library.isDownloaded(c.videoId) || AudioPrep.isDownloaded(this, c.videoId));
        nowPlayingDownload.setText("");
        nowPlayingDownload.setCompoundDrawablesWithIntrinsicBounds(0, downloaded ? R.drawable.ic_download_done : R.drawable.ic_download, 0, 0);
        nowPlayingDownload.setContentDescription(downloaded ? "Downloaded" : "Download song");
        nowPlayingDownload.setEnabled(!downloaded);
        nowPlayingDownload.setAlpha(downloaded ? 0.60f : 1f);
    }

    private void updateRepeatButton() {
        if (nowPlayingRepeat != null && musicPlayer != null) {
            boolean repeat = musicPlayer.isRepeatCurrent();
            nowPlayingRepeat.setText("");
            nowPlayingRepeat.setCompoundDrawablesWithIntrinsicBounds(0, R.drawable.ic_repeat, 0, 0);
            nowPlayingRepeat.setAlpha(repeat ? 1f : 0.75f);
            nowPlayingRepeat.setBackgroundResource(repeat ? R.drawable.bg_action_on : R.drawable.bg_action);
            nowPlayingRepeat.setContentDescription(repeat ? "Repeat song on" : "Repeat song off");
        }
    }

    /** "Add to playlist": choose an existing playlist, or make a new one. */
    private void showPlaylistPicker(VideoItem item) {
        List<Playlist> playlists = library.getPlaylists();
        if (playlists.isEmpty()) {
            promptNewPlaylistWith(item);
            return;
        }

        String[] names = new String[playlists.size() + 1];
        names[0] = "＋  New playlist";
        for (int i = 0; i < playlists.size(); i++) {
            Playlist p = playlists.get(i);
            names[i + 1] = p.name + "  •  " + p.tracks.size();
        }

        new MaterialAlertDialogBuilder(this)
                .setTitle("Add to playlist")
                .setItems(names, (dialog, which) -> {
                    if (which == 0) {
                        promptNewPlaylistWith(item);
                    } else {
                        Playlist p = playlists.get(which - 1);
                        boolean added = library.addToPlaylist(p.id, item);
                        toast(added ? "Added to " + p.name : "Already in " + p.name);
                        refreshLibraryUi();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptNewPlaylistWith(VideoItem item) {
        promptName("New playlist", "", "Create", name -> {
            Playlist p = library.createPlaylist(name);
            library.addToPlaylist(p.id, item);
            toast("Added to " + name);
            refreshLibraryUi();
        });
    }

    private void showPlaylistMenu(Playlist playlist, View anchor) {
        PopupMenu menu = new PopupMenu(this, anchor);
        menu.getMenu().add(0, 1, 0, "Play");
        menu.getMenu().add(0, 2, 1, playlist.loop ? "Loop: On" : "Loop: Off");
        menu.getMenu().add(0, 3, 2, "Rename");
        menu.getMenu().add(0, 4, 3, "Delete");
        menu.setOnMenuItemClickListener(mi -> {
            switch (mi.getItemId()) {
                case 1:
                    musicPlayer.playPlaylist(playlist);
                    return true;
                case 2:
                    library.setPlaylistLoop(playlist.id, !playlist.loop);
                    toast(!playlist.loop ? "Playlist loop enabled" : "Playlist loop disabled");
                    refreshLibraryUi();
                    return true;
                case 3:
                    promptName("Rename playlist", playlist.name, "Save", name -> {
                        library.renamePlaylist(playlist.id, name);
                        refreshLibraryUi();
                    });
                    return true;
                case 4:
                    confirmDelete(playlist);
                    return true;
                default:
                    return false;
            }
        });
        menu.show();
    }

    private void confirmDelete(Playlist playlist) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Delete playlist?")
                .setMessage("\"" + playlist.name + "\" will be removed. Your songs are not deleted from anywhere else.")
                .setPositiveButton("Delete", (d, w) -> {
                    library.deletePlaylist(playlist.id);
                    if (playlist.id.equals(openPlaylistId)) closePlaylist();
                    refreshLibraryUi();
                    updateBack();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void promptName(String title, String initial, String positive, Consumer<String> onOk) {
        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setHint("Playlist name");
        input.setText(initial);
        input.setSelection(input.getText().length());

        FrameLayout box = new FrameLayout(this);
        int pad = dp(24);
        box.setPadding(pad, dp(8), pad, 0);
        box.addView(input, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        AlertDialog dialog = new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setView(box)
                .setPositiveButton(positive, (d, w) -> {
                    String name = input.getText().toString().trim();
                    if (name.isEmpty()) {
                        toast("Give it a name first");
                    } else {
                        onOk.accept(name);
                    }
                })
                .setNegativeButton("Cancel", null)
                .create();
        if (dialog.getWindow() != null) {
            dialog.getWindow().setSoftInputMode(
                    WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE);
        }
        dialog.show();
        input.requestFocus();
    }

    // ==================== library page ====================

    private void openPlaylist(String id) {
        openPlaylistId = id;
        applyLibraryMode();
        refreshLibraryUi();
        updateBack();
    }

    private void closePlaylist() {
        openPlaylistId = null;
        applyLibraryMode();
    }

    private void applyLibraryMode() {
        boolean detail = openPlaylistId != null;
        libTitle.setVisibility(detail ? View.GONE : View.VISIBLE);
        libTabs.setVisibility(detail ? View.GONE : View.VISIBLE);
        libFavorites.setVisibility(!detail && libTab == TAB_FAVORITES ? View.VISIBLE : View.GONE);
        libPlaylists.setVisibility(!detail && libTab == TAB_PLAYLISTS ? View.VISIBLE : View.GONE);
        libHistory.setVisibility(!detail && libTab == TAB_HISTORY ? View.VISIBLE : View.GONE);
        libDownloads.setVisibility(!detail && libTab == TAB_DOWNLOADS ? View.VISIBLE : View.GONE);
        libDetail.setVisibility(detail ? View.VISIBLE : View.GONE);

        styleTab(tabFavorites, libTab == TAB_FAVORITES);
        styleTab(tabPlaylists, libTab == TAB_PLAYLISTS);
        styleTab(tabHistory, libTab == TAB_HISTORY);
        styleTab(tabDownloads, libTab == TAB_DOWNLOADS);
    }

    private void styleTab(TextView tab, boolean selected) {
        tab.setBackgroundResource(selected ? R.drawable.bg_chip_on : R.drawable.bg_chip_off);
        tab.setTextColor(ContextCompat.getColor(this,
                selected ? R.color.text : R.color.nav_inactive));
    }

    /** Call after anything changes in favorites / playlists / history. */
    private void refreshLibraryUi() {
        List<VideoItem> favs = library.getFavorites();
        favAdapter.setItems(favs);
        favCount.setText(favs.size() + (favs.size() == 1 ? " song" : " songs"));
        favEmpty.setVisibility(favs.isEmpty() ? View.VISIBLE : View.GONE);
        playAllFav.setVisibility(favs.isEmpty() ? View.GONE : View.VISIBLE);

        refreshHistoryUi();

        List<Playlist> playlists = library.getPlaylists();
        playlistAdapter.setItems(playlists);
        playlistEmpty.setVisibility(playlists.isEmpty() ? View.VISIBLE : View.GONE);

        if (openPlaylistId != null) {
            Playlist p = library.getPlaylist(openPlaylistId);
            if (p == null) {
                closePlaylist();
            } else {
                detailName.setText(p.name);
                int n = p.tracks.size();
                detailCount.setText(n + (n == 1 ? " song" : " songs")
                        + (p.loop ? " • Loop on" : ""));
                detailAdapter.setItems(new ArrayList<>(p.tracks));
                detailEmpty.setVisibility(n == 0 ? View.VISIBLE : View.GONE);
            }
        }

        searchAdapter.notifyDataSetChanged(); // hearts in the search results
        refreshHome();
        updateHearts();
        syncCurrentTrack();
    }

    private void refreshHistoryUi() {
        List<VideoItem> played = library.getHistory();
        historyAdapter.setItems(played);
        List<VideoItem> downloads = library.getDownloads();
        if (downloadAdapter != null) downloadAdapter.setItems(downloads);
        if (downloadCount != null) downloadCount.setText(downloads.size() + " downloaded");
        if (downloadEmpty != null) downloadEmpty.setVisibility(downloads.isEmpty() ? View.VISIBLE : View.GONE);
        historyCount.setText(played.size() + (played.size() == 1 ? " song" : " songs"));
        historyEmpty.setVisibility(played.isEmpty() ? View.VISIBLE : View.GONE);
    }

    private void syncCurrentTrack() {
        VideoItem c = musicPlayer.getCurrent();
        String id = c == null ? null : c.videoId;
        searchAdapter.setCurrentId(id);
        favAdapter.setCurrentId(id);
        historyAdapter.setCurrentId(id);
        detailAdapter.setCurrentId(id);
        if (downloadAdapter != null) downloadAdapter.setCurrentId(id);
    }

    private void updateHearts() {
        VideoItem c = musicPlayer.getCurrent();
        boolean fav = c != null && library.isFavorite(c.videoId);
        int icon = fav ? R.drawable.ic_favorite : R.drawable.ic_favorite_border;
        miniHeart.setImageResource(icon);
        nowPlayingHeart.setImageResource(icon);
        miniHeart.setColorFilter(ContextCompat.getColor(this,
                fav ? R.color.heart : R.color.nav_inactive));
        nowPlayingHeart.setColorFilter(ContextCompat.getColor(this,
                fav ? R.color.heart : R.color.text));
    }

    // ==================== Now Playing ====================

    private void openNowPlaying() {
        if (musicPlayer.getCurrent() == null) return;
        nowPlayingOverlay.setVisibility(View.VISIBLE);
        nowPlayingOverlay.setTranslationY(root.getHeight());
        nowPlayingOverlay.animate().translationY(0).setDuration(300)
                .setInterpolator(new DecelerateInterpolator()).start();
        updateBack();
    }

    private void closeNowPlaying() {
        nowPlayingOverlay.animate().translationY(root.getHeight()).setDuration(250)
                .withEndAction(() -> {
                    nowPlayingOverlay.setVisibility(View.GONE);
                    updateBack();
                }).start();
    }

    /** When the app is reopened (e.g. from the notification) while music is playing. */
    private void restorePlayerUi() {
        VideoItem c = musicPlayer.getCurrent();
        if (c == null) return;
        onTrackChanged(c);
        onArtwork(musicPlayer.getArtworkUrl());
        onStateChanged(musicPlayer.isPlaying(), musicPlayer.isLoading());
        onProgress(musicPlayer.getCurrentPosition(), musicPlayer.getDuration());
    }

    private void loadNowPlayingArt(String url) {
        if (url == null || url.isEmpty()) return;
        Glide.with(this).asBitmap().load(url).into(new CustomTarget<Bitmap>() {
            @Override
            public void onResourceReady(@NonNull Bitmap bitmap,
                                        @Nullable Transition<? super Bitmap> transition) {
                nowPlayingArt.setImageBitmap(bitmap);
                Palette.from(bitmap).maximumColorCount(12).generate(palette -> {
                    if (palette == null) return;
                    int bg = ContextCompat.getColor(MainActivity.this, R.color.bg);
                    int c = palette.getVibrantColor(palette.getDominantColor(bg));
                    animateNowPlayingBackground(ColorUtils.blendARGB(bg, c, 0.55f));
                });
            }

            @Override
            public void onLoadCleared(@Nullable Drawable placeholder) { }
        });
    }

    private void animateNowPlayingBackground(int target) {
        final int bg = ContextCompat.getColor(this, R.color.bg);
        if (npTintAnim != null) npTintAnim.cancel();
        npTintAnim = ValueAnimator.ofObject(new ArgbEvaluator(), npTint, target);
        npTintAnim.setDuration(600);
        npTintAnim.addUpdateListener(a -> {
            npTint = (int) a.getAnimatedValue();
            nowPlayingOverlay.setBackground(new GradientDrawable(
                    GradientDrawable.Orientation.TOP_BOTTOM, new int[]{npTint, bg}));
        });
        npTintAnim.start();
    }

    private static String formatTime(long ms) {
        long totalSeconds = ms / 1000;
        return String.format(Locale.US, "%d:%02d", totalSeconds / 60, totalSeconds % 60);
    }

    private void setLoadingUi(boolean isLoading) {
        miniLoading.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        miniPlayPause.setVisibility(isLoading ? View.INVISIBLE : View.VISIBLE);
        nowPlayingLoading.setVisibility(isLoading ? View.VISIBLE : View.GONE);
        nowPlayingPlayPause.setVisibility(isLoading ? View.INVISIBLE : View.VISIBLE);

        // Modern, subtle loading treatment: keep the new song artwork visible
        // and gently pulse it while the audio is being prepared. This avoids
        // flashing a blank player and makes a manual Next transition obvious.
        if (isLoading) {
            if (loadingPulse == null) {
                loadingPulse = ValueAnimator.ofFloat(0.58f, 1.0f);
                loadingPulse.setDuration(850);
                loadingPulse.setRepeatMode(ValueAnimator.REVERSE);
                loadingPulse.setRepeatCount(ValueAnimator.INFINITE);
                loadingPulse.addUpdateListener(a -> {
                    float alpha = (float) a.getAnimatedValue();
                    nowPlayingArt.setAlpha(alpha);
                    miniThumb.setAlpha(Math.max(0.72f, alpha));
                });
            }
            if (!loadingPulse.isRunning()) loadingPulse.start();
        } else {
            if (loadingPulse != null) loadingPulse.cancel();
            nowPlayingArt.setAlpha(1f);
            miniThumb.setAlpha(1f);
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private void toast(String msg) {
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
    }

    // ==================== MusicPlayer.Listener ====================

    @Override
    public void onTrackChanged(VideoItem item) {
        runOnUiThread(() -> {
            miniTitle.setText(item.title);
            miniArtist.setText(item.author);
            nowPlayingTitle.setText(item.title);
            nowPlayingArtist.setText(item.author);
            Glide.with(miniThumb).load(item.thumbnail).into(miniThumb);

            nowPlayingSeek.setPosition(0, 0);
            nowPlayingPosition.setText("0:00");
            nowPlayingDuration.setText("0:00");
            ViewGroup.LayoutParams lp = miniProgressFill.getLayoutParams();
            lp.width = 0;
            miniProgressFill.setLayoutParams(lp);

            updateChrome();
            refreshHistoryUi();
            syncCurrentTrack();
            updateHearts();
            updateDownloadButton();
            updateRepeatButton();
            refreshHome();
        });
    }

    @Override
    public void onArtwork(String url) {
        runOnUiThread(() -> loadNowPlayingArt(url));
    }

    @Override
    public void onStateChanged(boolean isPlaying, boolean isLoading) {
        runOnUiThread(() -> {
            setLoadingUi(isLoading);
            int icon = isPlaying ? R.drawable.ic_pause : R.drawable.ic_play;
            miniPlayPause.setImageResource(icon);
            nowPlayingPlayPause.setImageResource(icon);
            nowPlayingSeek.setWaving(isPlaying);
        });
    }

    @Override
    public void onProgress(long positionMs, long durationMs) {
        runOnUiThread(() -> {
            if (durationMs <= 0) return;

            ViewGroup.LayoutParams lp = miniProgressFill.getLayoutParams();
            int parentWidth = miniPlayer.getWidth();
            if (parentWidth > 0) {
                lp.width = (int) (parentWidth * ((float) positionMs / durationMs));
                miniProgressFill.setLayoutParams(lp);
            }

            nowPlayingSeek.setPosition(positionMs, durationMs);
            nowPlayingPosition.setText(formatTime(positionMs));
            nowPlayingDuration.setText(formatTime(durationMs));
        });
    }

    @Override
    public void onError(String message) {
        runOnUiThread(() -> {
            setLoadingUi(false);
            toast(message);
        });
    }
}
