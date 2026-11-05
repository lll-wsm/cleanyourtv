package com.gathin.tvhome;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The thumbnails the picker shows, kept as JPEGs beside the films and pictures they come from.
 *
 * <p>Persisted on purpose: decoding a frame out of a 4K film takes about a second on this class of box, so
 * nineteen of them meant the grid filled in over twenty seconds, every time it opened. A thumbnail is written once
 * and read back as a file afterwards.
 *
 * <p>They sit in the package's files root rather than a folder of their own because a folder the app creates
 * belongs to the app, and {@code adb push} cannot write into it (the TV's FUSE refuses the chown), so a cache that
 * can be pre-seeded over ADB has to live one level up.
 *
 * <p>A film is re-read when its own timestamp is newer than the thumbnail's, so dropping a new file in — or
 * replacing one with the same name — gets a fresh thumbnail without anything to configure. The scan runs on a low
 * priority worker started by Home, so the picker is usually instant even the first time.
 */
final class ThumbCache {
    private static final String TAG = "TvHome";
    private static final int WIDTH = 480;

    private static final ExecutorService WORKER = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "thumb-cache");
        thread.setPriority(Thread.MIN_PRIORITY);
        return thread;
    });

    private ThumbCache() {
    }

    static File fileFor(Context context, File source) {
        return new File(context.getExternalFilesDir(null), "thumb2-" + source.getName() + ".jpg");
    }

    /** The stored thumbnail, or null when there is none or it is older than the file it was made from. */
    static Bitmap load(Context context, File source) {
        File thumb = fileFor(context, source);
        if (!thumb.isFile() || thumb.length() == 0) return null;
        if (thumb.lastModified() < source.lastModified()) return null;
        return BitmapFactory.decodeFile(thumb.getAbsolutePath());
    }

    /** Reads one thumbnail now, storing it, for the picker's fallback when the background pass has not run yet. */
    static Bitmap make(Context context, File source, boolean film) {
        Bitmap bitmap = film ? frame(source) : picture(source);
        if (bitmap != null) store(context, source, bitmap);
        return bitmap;
    }

    /** Fills in every missing thumbnail in the background; cheap when they are all there. */
    static void ensure(Context context) {
        WORKER.execute(() -> {
            Context app = context.getApplicationContext();
            try {
                for (File film : VideoWallpaperPlayer.videos(app)) {
                    if (load(app, film) == null) make(app, film, true);
                }
                for (File picture : WallpaperRotation.images(new File(app.getExternalFilesDir(null), "wallpapers"))) {
                    if (load(app, picture) == null) make(app, picture, false);
                }
            } catch (Exception e) {
                Log.w(TAG, "thumbnail pass stopped", e);
            }
        });
    }

    private static void store(Context context, File source, Bitmap bitmap) {
        File thumb = fileFor(context, source);
        try (FileOutputStream stream = new FileOutputStream(thumb)) {
            bitmap.compress(Bitmap.CompressFormat.JPEG, 85, stream);
        } catch (Exception e) {
            Log.w(TAG, "could not store a thumbnail for " + source.getName(), e);
        }
    }

    /** A picture, sampled down to thumbnail size on the way in. */
    private static Bitmap picture(File file) {
        try {
            BitmapFactory.Options bounds = new BitmapFactory.Options();
            bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getAbsolutePath(), bounds);
            int sample = 1;
            while (bounds.outWidth / (sample * 2) >= WIDTH) sample *= 2;
            BitmapFactory.Options options = new BitmapFactory.Options();
            options.inSampleSize = sample;
            return BitmapFactory.decodeFile(file.getAbsolutePath(), options);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * The brightest of three frames along the film. One frame is often black for a reason inside the clip: the
     * start of a fade, and on a short film every seek lands on the same keyframe, which is the black one at zero.
     */
    private static Bitmap frame(File film) {
        MediaMetadataRetriever retriever = new MediaMetadataRetriever();
        try {
            retriever.setDataSource(film.getAbsolutePath());
            long duration = 0;
            try {
                duration = Long.parseLong(retriever.extractMetadata(
                        MediaMetadataRetriever.METADATA_KEY_DURATION));
            } catch (Exception ignored) {
            }
            long micros = duration * 1000L;
            // Exact frames, not the nearest keyframe: a short film's only keyframe is the black one at zero, so a
            // keyframe seek answers with black whatever time is asked for.
            long[] points = micros > 0
                    ? new long[]{(long) (micros * 0.35), (long) (micros * 0.65)}
                    : new long[]{1_000_000, 2_000_000};
            Bitmap best = null;
            int bestLuma = -1;
            for (long at : points) {
                Bitmap candidate = retriever.getFrameAtTime(at, MediaMetadataRetriever.OPTION_CLOSEST);
                if (candidate == null) continue;
                int luma = luma(candidate);
                if (luma > bestLuma) {
                    if (best != null) best.recycle();
                    best = candidate;
                    bestLuma = luma;
                } else {
                    candidate.recycle();
                }
            }
            return best;
        } catch (Exception e) {
            Log.w(TAG, "could not read a frame from " + film.getName(), e);
            return null;
        } finally {
            try {
                retriever.release();
            } catch (Exception ignored) {
            }
        }
    }

    /** Mean brightness on a coarse grid: enough to tell a black frame from a picture. */
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
}
