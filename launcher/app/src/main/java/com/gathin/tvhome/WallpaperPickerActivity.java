package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * A grid of thumbnails to pick one wallpaper: the pictures in files/wallpapers, or a frame out of each film in
 * files/videos. The text picker this replaces only listed names, which the viewer could not map back to a picture.
 *
 * <p>A pick is written as a preference (wallpaper_choice or video_choice) and switches the wallpaper mode to a
 * single one, so choosing a picture means "this one and no rotation"; the settings' mode row puts rotation back.
 */
public class WallpaperPickerActivity extends Activity {
    static final String EXTRA_KIND = "kind";
    static final String KIND_STILLS = "stills";
    static final String KIND_VIDEOS = "videos";

    private final ExecutorService decoder = Executors.newFixedThreadPool(3);
    private final Handler main = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> thumbs = new LruCache<>(48);

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        boolean videos = KIND_VIDEOS.equals(getIntent().getStringExtra(EXTRA_KIND));
        List<File> files = videos ? VideoWallpaperPlayer.videos(this) : WallpaperRotation.images(
                new File(getExternalFilesDir(null), "wallpapers"));

        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(0xFF0B0B0D);

        TextView title = new TextView(this);
        title.setText(videos ? R.string.pick_video : R.string.pick_wallpaper);
        title.setTextColor(0xFFFFFFFF);
        title.setTextSize(26);
        title.setPadding(dp(40), dp(24), dp(40), dp(8));
        root.addView(title, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP));

        RecyclerView grid = new RecyclerView(this);
        grid.setLayoutManager(new GridLayoutManager(this, 5));
        grid.setPadding(dp(24), dp(72), dp(24), dp(24));
        grid.setClipToPadding(false);
        grid.setAdapter(new Adapter(files, videos));
        root.addView(grid, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        if (files.isEmpty()) {
            TextView empty = new TextView(this);
            empty.setText(R.string.pick_empty);
            empty.setTextColor(0xFFAAAAAA);
            empty.setTextSize(18);
            empty.setGravity(Gravity.CENTER);
            root.addView(empty, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        setContentView(root);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        decoder.shutdownNow();
        thumbs.evictAll();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void pick(File file, boolean videos) {
        SharedPreferences prefs = getSharedPreferences(Config.SETTINGS_NAME, MODE_PRIVATE);
        SharedPreferences.Editor edit = prefs.edit();
        if (videos) {
            edit.putString("video_choice", file.getName());
        } else {
            edit.putString(Config.WALLPAPER_CHOICE_KEY, file.getName());
        }
        // Picking decides what is on screen: a picture turns the films off and a film turns them on, so the choice
        // is visible without a second trip to the video row.
        edit.putBoolean("video_wallpaper", videos);
        // Choosing one thing means one thing on screen: the mode row brings the rotation back.
        edit.putString("wallpaper_mode", "single");
        // The same revision the settings page bumps, or Home would not rebuild on the way back and the old
        // wallpaper would stay up.
        edit.putLong(Config.SETTINGS_REVISION_KEY, prefs.getLong(Config.SETTINGS_REVISION_KEY, 0) + 1);
        edit.apply();
        finish();
    }

    private final class Adapter extends RecyclerView.Adapter<Holder> {
        private final List<File> files;
        private final boolean videos;

        Adapter(List<File> files, boolean videos) {
            this.files = new ArrayList<>(files);
            this.videos = videos;
        }

        @Override
        public Holder onCreateViewHolder(ViewGroup parent, int viewType) {
            FrameLayout cell = new FrameLayout(WallpaperPickerActivity.this);
            cell.setLayoutParams(new RecyclerView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    dp(150)));
            cell.setPadding(dp(8), dp(8), dp(8), dp(8));
            cell.setFocusable(true);
            cell.setClickable(true);
            cell.setBackgroundColor(0xFF16161A);

            ImageView image = new ImageView(WallpaperPickerActivity.this);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setContentDescription(null);
            cell.addView(image, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

            TextView name = new TextView(WallpaperPickerActivity.this);
            name.setTextColor(0xFFFFFFFF);
            name.setTextSize(11);
            name.setSingleLine(true);
            name.setGravity(Gravity.CENTER);
            name.setBackgroundColor(0x99000000);
            cell.addView(name, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
            return new Holder(cell, image, name);
        }

        @Override
        public void onBindViewHolder(Holder holder, int position) {
            File file = files.get(position);
            holder.name.setText(file.getName().replaceFirst("\\.[^.]+$", ""));
            holder.image.setImageBitmap(null);
            Bitmap cached = thumbs.get(file.getAbsolutePath());
            if (cached == null) cached = ThumbCache.load(WallpaperPickerActivity.this, file);
            if (cached != null) {
                holder.image.setImageBitmap(cached);
            } else {
                holder.image.setTag(file.getAbsolutePath());
                decoder.execute(() -> {
                    Bitmap bitmap = ThumbCache.make(WallpaperPickerActivity.this, file, videos);
                    if (bitmap == null) return;
                    thumbs.put(file.getAbsolutePath(), bitmap);
                    main.post(() -> {
                        if (file.getAbsolutePath().equals(holder.image.getTag())) {
                            holder.image.setImageBitmap(bitmap);
                        }
                    });
                });
            }
            holder.itemView.setOnClickListener(v -> pick(file, videos));
        }

        @Override
        public int getItemCount() {
            return files.size();
        }
    }

    private static final class Holder extends RecyclerView.ViewHolder {
        final ImageView image;
        final TextView name;

        Holder(View item, ImageView image, TextView name) {
            super(item);
            this.image = image;
            this.name = name;
        }
    }
}
