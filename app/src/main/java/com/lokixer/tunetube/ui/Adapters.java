package com.lokixer.tunetube.ui;

import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.resource.drawable.DrawableTransitionOptions;

import com.lokixer.tunetube.R;
import com.lokixer.tunetube.data.Library;
import com.lokixer.tunetube.data.Playlist;
import com.lokixer.tunetube.data.VideoItem;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

/** Every RecyclerView adapter of the app in one file. */
public final class Adapters {

    private Adapters() { }

    /** A list of songs: cover, title, heart (favorite) and a "more" menu. */
    public static class TrackAdapter extends RecyclerView.Adapter<TrackAdapter.Holder> {

        public interface Listener {
            void onPlay(List<VideoItem> all, int position);
            void onFavorite(VideoItem item);
            void onMore(VideoItem item, View anchor);
        }

        private final List<VideoItem> items = new ArrayList<>();
        private final Library library;
        private final Listener listener;
        private String currentId;

        public TrackAdapter(Library library, Listener listener) {
            this.library = library;
            this.listener = listener;
        }

        public void setItems(List<VideoItem> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        public void addItems(List<VideoItem> more) {
            int start = items.size();
            items.addAll(more);
            notifyItemRangeInserted(start, more.size());
        }

        public List<VideoItem> getItems() {
            return new ArrayList<>(items);
        }

        public void setCurrentId(String id) {
            if (id == null ? currentId == null : id.equals(currentId)) return;
            currentId = id;
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_video, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            VideoItem item = items.get(position);
            holder.title.setText(item.title);

            String sub = item.author;
            if (item.duration != null && !item.duration.isEmpty()) {
                sub = sub + "  •  " + item.duration;
            }
            holder.author.setText(sub);

            Glide.with(holder.thumb)
                    .load(item.thumbnail)
                    .transition(DrawableTransitionOptions.withCrossFade(120))
                    .into(holder.thumb);

            boolean playing = item.videoId.equals(currentId);
            holder.playing.setVisibility(playing ? View.VISIBLE : View.GONE);
            holder.title.setTextColor(ContextCompat.getColor(holder.itemView.getContext(),
                    playing ? R.color.accent : R.color.text));

            boolean fav = library.isFavorite(item.videoId);
            holder.heart.setImageResource(fav ? R.drawable.ic_favorite : R.drawable.ic_favorite_border);
            holder.heart.setColorFilter(ContextCompat.getColor(holder.itemView.getContext(),
                    fav ? R.color.heart : R.color.nav_inactive));

            holder.itemView.setOnClickListener(v -> {
                int pos = holder.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) listener.onPlay(getItems(), pos);
            });
            holder.heart.setOnClickListener(v -> listener.onFavorite(item));
            holder.more.setOnClickListener(v -> listener.onMore(item, v));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class Holder extends RecyclerView.ViewHolder {
            final ImageView thumb, playing, heart, more;
            final TextView title, author;

            Holder(View v) {
                super(v);
                thumb = v.findViewById(R.id.itemThumb);
                playing = v.findViewById(R.id.itemPlaying);
                heart = v.findViewById(R.id.itemHeart);
                more = v.findViewById(R.id.itemMore);
                title = v.findViewById(R.id.itemTitle);
                author = v.findViewById(R.id.itemAuthor);
            }
        }
    }

    /** Big square cards in a horizontal row (used for "Recently played" on Home). */
    public static class CardAdapter extends RecyclerView.Adapter<CardAdapter.Holder> {

        public interface Listener {
            void onPlay(List<VideoItem> all, int position);
        }

        private final List<VideoItem> items = new ArrayList<>();
        private final Listener listener;

        public CardAdapter(Listener listener) {
            this.listener = listener;
        }

        public void setItems(List<VideoItem> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_card, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            VideoItem item = items.get(position);
            holder.title.setText(item.title);
            holder.author.setText(item.author);
            Glide.with(holder.thumb).load(item.thumbnail).into(holder.thumb);
            holder.itemView.setOnClickListener(v -> {
                int pos = holder.getBindingAdapterPosition();
                if (pos != RecyclerView.NO_POSITION) listener.onPlay(new ArrayList<>(items), pos);
            });
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class Holder extends RecyclerView.ViewHolder {
            final ImageView thumb;
            final TextView title, author;

            Holder(View v) {
                super(v);
                thumb = v.findViewById(R.id.cardThumb);
                title = v.findViewById(R.id.cardTitle);
                author = v.findViewById(R.id.cardAuthor);
            }
        }
    }

    public static class PlaylistAdapter extends RecyclerView.Adapter<PlaylistAdapter.Holder> {

        public interface Listener {
            void onOpen(Playlist playlist);
            void onMore(Playlist playlist, View anchor);
        }

        private final List<Playlist> items = new ArrayList<>();
        private final Listener listener;

        public PlaylistAdapter(Listener listener) {
            this.listener = listener;
        }

        public void setItems(List<Playlist> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_playlist, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            Playlist p = items.get(position);
            holder.name.setText(p.name);
            int n = p.tracks.size();
            holder.count.setText(n + (n == 1 ? " song" : " songs"));

            float d = holder.itemView.getResources().getDisplayMetrics().density;
            if (n > 0) {
                holder.cover.setPadding(0, 0, 0, 0);
                holder.cover.clearColorFilter();
                Glide.with(holder.cover).load(p.tracks.get(0).thumbnail).into(holder.cover);
            } else {
                Glide.with(holder.cover).clear(holder.cover);
                int pad = (int) (18 * d);
                holder.cover.setPadding(pad, pad, pad, pad);
                holder.cover.setImageResource(R.drawable.ic_queue_music);
                holder.cover.setColorFilter(ContextCompat.getColor(
                        holder.itemView.getContext(), R.color.nav_inactive));
            }

            holder.itemView.setOnClickListener(v -> listener.onOpen(p));
            holder.more.setOnClickListener(v -> listener.onMore(p, v));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class Holder extends RecyclerView.ViewHolder {
            final ImageView cover, more;
            final TextView name, count;

            Holder(View v) {
                super(v);
                cover = v.findViewById(R.id.plCover);
                more = v.findViewById(R.id.plMore);
                name = v.findViewById(R.id.plName);
                count = v.findViewById(R.id.plCount);
            }
        }
    }

    /** Songs you searched for and played, shown in the Search page like Spotify. */
    public static class RecentTrackAdapter extends RecyclerView.Adapter<RecentTrackAdapter.Holder> {

        public interface Listener {
            void onPick(VideoItem item);
            void onRemove(VideoItem item);
        }

        private final List<VideoItem> items = new ArrayList<>();
        private final Listener listener;

        public RecentTrackAdapter(Listener listener) {
            this.listener = listener;
        }

        public void setItems(List<VideoItem> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_recent_track, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            VideoItem item = items.get(position);
            holder.title.setText(item.title);
            holder.author.setText(item.author);
            Glide.with(holder.thumb).load(item.thumbnail).into(holder.thumb);
            holder.itemView.setOnClickListener(v -> listener.onPick(item));
            holder.remove.setOnClickListener(v -> listener.onRemove(item));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class Holder extends RecyclerView.ViewHolder {
            final ImageView thumb, remove;
            final TextView title, author;

            Holder(View itemView) {
                super(itemView);
                thumb = itemView.findViewById(R.id.recentThumb);
                title = itemView.findViewById(R.id.recentTrackTitle);
                author = itemView.findViewById(R.id.recentTrackAuthor);
                remove = itemView.findViewById(R.id.recentTrackRemove);
            }
        }
    }

    public static class RecentAdapter extends RecyclerView.Adapter<RecentAdapter.Holder> {

        public interface Listener {
            void onPick(String query);
            void onRemove(String query);
        }

        private final List<String> items = new ArrayList<>();
        private final Listener listener;

        public RecentAdapter(Listener listener) {
            this.listener = listener;
        }

        public void setItems(List<String> newItems) {
            items.clear();
            items.addAll(newItems);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            View view = LayoutInflater.from(parent.getContext())
                    .inflate(R.layout.item_recent, parent, false);
            return new Holder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            String query = items.get(position);
            holder.text.setText(query);
            holder.itemView.setOnClickListener(v -> listener.onPick(query));
            holder.remove.setOnClickListener(v -> listener.onRemove(query));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        static class Holder extends RecyclerView.ViewHolder {
            final TextView text;
            final ImageView remove;

            Holder(View itemView) {
                super(itemView);
                text = itemView.findViewById(R.id.recentText);
                remove = itemView.findViewById(R.id.recentRemove);
            }
        }
    }

    public static class SuggestionAdapter extends RecyclerView.Adapter<SuggestionAdapter.Holder> {

        public interface Listener {
            void onPick(String query);
        }

        private final List<String> items = new ArrayList<>();
        private final Listener listener;

        public SuggestionAdapter(Listener listener) {
            this.listener = listener;
        }

        public void setItems(List<String> newItems) {
            items.clear();
            if (newItems != null) items.addAll(newItems);
            notifyDataSetChanged();
        }

        @NonNull
        @Override
        public Holder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(parent.getContext());
            row.setLayoutParams(new RecyclerView.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, dp(parent, 48)));
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding(dp(parent, 20), 0, dp(parent, 16), 0);
            row.setClickable(true);
            row.setFocusable(true);

            ImageView icon = new ImageView(parent.getContext());
            icon.setLayoutParams(new LinearLayout.LayoutParams(dp(parent, 22), dp(parent, 22)));
            icon.setPadding(dp(parent, 2), dp(parent, 2), dp(parent, 2), dp(parent, 2));
            icon.setImageResource(com.lokixer.tunetube.R.drawable.ic_search);
            icon.setColorFilter(parent.getContext().getColor(com.lokixer.tunetube.R.color.nav_inactive));
            row.addView(icon);

            TextView text = new TextView(parent.getContext());
            LinearLayout.LayoutParams textParams = new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textParams.setMarginStart(dp(parent, 14));
            text.setLayoutParams(textParams);
            text.setTextColor(parent.getContext().getColor(com.lokixer.tunetube.R.color.text));
            text.setTextSize(15);
            text.setMaxLines(1);
            row.addView(text);

            return new Holder(row, text);
        }

        @Override
        public void onBindViewHolder(@NonNull Holder holder, int position) {
            String query = items.get(position);
            holder.text.setText(query);
            holder.itemView.setOnClickListener(v -> listener.onPick(query));
        }

        @Override
        public int getItemCount() {
            return items.size();
        }

        private static int dp(View view, int value) {
            return Math.round(value * view.getResources().getDisplayMetrics().density);
        }

        static class Holder extends RecyclerView.ViewHolder {
            final TextView text;
            Holder(View itemView, TextView text) {
                super(itemView);
                this.text = text;
            }
        }
    }
}
