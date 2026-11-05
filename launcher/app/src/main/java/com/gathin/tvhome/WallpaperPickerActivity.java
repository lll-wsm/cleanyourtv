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
            if (cached == null && videos) cached = cachedThumb(file);
            if (cached != null) {
                holder.image.setImageBitmap(cached);
            } else {
                holder.image.setTag(file.getAbsolutePath());
                decoder.execute(() -> {
                    Bitmap bitmap = thumbnail(file, videos);
                    if (bitmap == null) return;
                    thumbs.put(file.getAbsolutePath(), bitmap);
                    if (videos) storeThumb(file, bitmap);
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

    /** A frame out of a film, or the picture itself, small enough for a 150dp cell. */
    private Bitmap thumbnail(File file, boolean videos) {
        try {
            if (!videos) {
                BitmapFactory.Options bounds = new BitmapFactory.Options();
                bounds.inJustDecodeBounds = true;
                BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
                int sample = 1;
                while (bounds.outWidth / (sample * 2) >= 320) sample *= 2;
                BitmapFactory.Options options = new BitmapFactory.Options();
                options.inSampleSize = sample;
                return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
            }
            MediaMetadataRetriever retriever = new MediaMetadataRetriever();
            try {
                retriever.setDataSource(file.getAbsolutePath());
                long duration = 0;
                try {
                    duration = Long.parseLong(retriever.extractMetadata(
                            MediaMetadataRetriever.METADATA_KEY_DURATION));
                } catch (Exception ignored) {
                }
                // Three points along the film and the brightest wins. One frame is often black for a reason that
                // is in the clip itself: a fade-in at the start, and on a short clip every seek lands on the same
                // keyframe, which is the black one at zero.
                long micros = duration * 1000L;
                long[] points = micros > 0
                        ? new long[]{(long) (micros * 0.10), (long) (micros * 0.45), (long) (micros * 0.80)}
                        : new long[]{0, 1_000_000, 2_000_000};
                Bitmap best = null;
                int bestLuma = -1;
                for (long at : points) {
                    Bitmap frame = retriever.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                    if (frame == null) continue;
                    int luma = luma(frame);
                    if (luma > bestLuma) {
                        if (best != null) best.recycle();
                        best = frame;
                        bestLuma = luma;
                    } else {
                        frame.recycle();
                    }
                }
                return best;
            } finally {
                retriever.release();
            }
        } catch (Exception e) {
            return null;
        }
    }

    /** Mean brightness of a frame, sampled on a coarse grid: enough to tell a black frame from a picture. */
    private static int luma(Bitmap frame) {
        int total = 0;
        int count = 0;
        for (int y = 0; y < 9; y++) {
            int py = Math.min(frame.getHeight() - 1, frame.getHeight() * y / 9);
            for (int x = 0; x < 16; x++) {
                int px = Math.min(frame.getWidth() - 1, frame.getWidth() * x / 16);
                int colour = frame.getPixel(px, py);
                total += (((colour >> 16) & 0xFF) + ((colour >> 8) & 0xFF) + (colour & 0xFF)) / 3;
                count++;
            }
        }
        return count == 0 ? 0 : total / count;
    }

    /** The thumbnails kept between openings, named after the film and stamped with its mtime.
     *
     *  <p>They sit in the package's files root rather than a folder of their own on purpose: a folder the app
     *  creates is owned by the app, and {@code adb push} cannot write into it (the TV's FUSE refuses the chown), so
     *  a cache that can be seeded over ADB has to live one level up, where the shell already owns the directory. */
    private File thumbFile(File film) {
        return new File(getExternalFilesDir(null), "thumb-" + film.getName() + ".jpg");
    }

    private Bitmap cachedThumb(File film) {
        File file = thumbFile(film);
        if (file == null || !file.isFile() || file.length() == 0) return null;
        // A re-encoded film has a newer timestamp than its thumbnail: decode again rather than show the old one.
        if (file.lastModified() < film.lastModified()) return null;
        return BitmapFactory.decodeFile(file.getAbsolutePath());
    }

    private void storeThumb(File film, Bitmap bitmap) {
        File file = thumbFile(film);
        if (file == null) return;
        try (java.io.FileOutputStream stream = new java.io.FileOutputStream(file)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream);
        } catch (Exception ignored) {
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
