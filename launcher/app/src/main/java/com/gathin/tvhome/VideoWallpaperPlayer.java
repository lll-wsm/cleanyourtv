package com.gathin.tvhome;

import android.content.Context;
import android.graphics.SurfaceTexture;
import android.graphics.Bitmap;
import android.media.MediaPlayer;
import android.util.Log;
import android.view.Gravity;
import android.view.TextureView;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * A video behind Home instead of the picture collection (config video_wallpaper).
 *
 * <p>The film lives where the pictures do, in Android/data/&lt;package&gt;/files/videos/ (no permission, pushed over
 * ADB), is silent, and fills the screen. More than one file plays one after another; one file loops.
 *
 * <p>A TextureView rather than a SurfaceView on purpose: its frames are drawn as part of the window, so it sits in
 * the view hierarchy at index 0 and every other view paints over it in the usual order. A SurfaceView's surface is
 * composited behind the window instead, which needs the root, the time-of-day backdrop and the window background to
 * be transparent at once, and on this Amlogic firmware that still left the film hidden behind the backdrop.
 *
 * <p>The decoder is the scarce thing on a TV: the player is released the moment Home stops being in front (see
 * MainActivity.onPause), so opening a streaming app finds the TV's one or two hardware decoders free.
 */
final class VideoWallpaperPlayer {
    private static final String TAG = "TvHome";
    private static final String FOLDER = "videos";
    private static final String EXTENSION = ".mp4";

    private final android.content.Context context;
    private final FrameLayout root;
    private final TextureView view;
    private final View shade;
    private final int dim;
    private final List<File> files;
    /** Called when the film cannot play, so Home can put its backdrop and pictures back. */
    Runnable onFailed;

    private MediaPlayer player;
    private SurfaceTexture texture;
    private int index;
    private boolean playing;
    private boolean closed;
    /** Set by a failed decode: the folder stays as it was, but this run falls back to the pictures. */
    private boolean failed;

    VideoWallpaperPlayer(Context context, FrameLayout root, int dim, List<File> films) {
        this.context = context;
        this.root = root;
        this.dim = Math.max(0, Math.min(90, dim));
        this.files = films;
        this.shade = new View(context);
        this.shade.setBackgroundColor(0xFF000000);
        this.shade.setAlpha(this.dim / 100f);
        this.shade.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        this.view = new TextureView(context);
        this.view.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        this.view.setSurfaceTextureListener(new TextureView.SurfaceTextureListener() {
            @Override
            public void onSurfaceTextureAvailable(SurfaceTexture surface, int width, int height) {
                texture = surface;
                if (playing) attach();
            }

            @Override
            public void onSurfaceTextureSizeChanged(SurfaceTexture surface, int width, int height) {
            }

            @Override
            public boolean onSurfaceTextureDestroyed(SurfaceTexture surface) {
                texture = null;
                release();
                return true;
            }

            @Override
            public void onSurfaceTextureUpdated(SurfaceTexture surface) {
            }
        });
    }

    /** The films in the folder, by name; empty when the theme has video_wallpaper off or nothing was pushed. */
    static List<File> videos(Context context) {
        File dir = new File(context.getExternalFilesDir(null), FOLDER);
        File[] found = dir.listFiles((d, name) -> name.toLowerCase().endsWith(EXTENSION));
        if (found == null) return Collections.emptyList();
        List<File> list = new ArrayList<>(Arrays.asList(found));
        Collections.sort(list, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return list;
    }

    static boolean hasVideo(Context context) {
        return !videos(context).isEmpty();
    }

    /** True while a film actually plays; false leaves Home on its pictures. */
    boolean start() {
        if (closed || failed || files.isEmpty() || playing) return playing;
        playing = true;
        index = 0;
        if (view.getParent() == null) {
            // Index 0: the film is the backdrop, and every other view in the layout paints over it.
            root.addView(view, 0, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.FILL));
        }
        if (shade.getParent() == null && dim > 0) {
            // Over the film, under everything Home draws: a bright clip would otherwise wash the clock out.
            root.addView(shade, 1, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.FILL));
        }
        view.setVisibility(View.VISIBLE);
        shade.setVisibility(dim > 0 ? View.VISIBLE : View.GONE);
        // The film covers the screen, so a plain colour underneath is only overdraw.
        root.setBackground(null);
        if (texture != null) attach();
        return true;
    }

    /** Stops and frees the decoder at once, and hands the plain colour back so Home is not left with a hole. */
    void stop() {
        if (!playing) return;
        playing = false;
        release();
        view.setVisibility(View.GONE);
        shade.setVisibility(View.GONE);
        // A still of the film while Home is in the back: a settings panel over Home draws it behind the panel, and
        // an empty TextureView there reads as a black desktop.
        if (!files.isEmpty()) {
            Bitmap still = ThumbCache.load(context, files.get(index));
            if (still != null) {
                root.setBackground(new android.graphics.drawable.BitmapDrawable(root.getResources(), still));
            }
        }
    }

    /** Memory pressure: the film comes back on the next start, so give the decoder up now. */
    void releaseForMemoryPressure() {
        if (playing) release();
    }

    void close() {
        closed = true;
        playing = false;
        release();
        if (view.getParent() != null) root.removeView(view);
        if (shade.getParent() != null) root.removeView(shade);
    }

    private void attach() {
        release();
        if (texture == null) return;
        File file = files.get(index);
        MediaPlayer next = new MediaPlayer();
        try {
            next.setDataSource(file.getAbsolutePath());
            next.setSurface(new android.view.Surface(texture));
            next.setVolume(0f, 0f);
            next.setLooping(files.size() == 1);
            next.setOnCompletionListener(mp -> {
                if (!playing) return;
                index = (index + 1) % files.size();
                attach();
            });
            next.setOnErrorListener((mp, what, extra) -> {
                Log.w(TAG, "video wallpaper could not play " + file.getName() + " (" + what + "/" + extra + ")");
                failed = true;
                player = null;
                stop();
                if (onFailed != null) onFailed.run();
                return true;
            });
            next.setOnPreparedListener(mp -> {
                if (playing) mp.start();
            });
            next.prepareAsync();
            player = next;
        } catch (Exception e) {
            Log.w(TAG, "video wallpaper could not open " + file.getName(), e);
            failed = true;
            next.release();
            stop();
            if (onFailed != null) onFailed.run();
        }
    }

    private void release() {
        if (player == null) return;
        try {
            player.release();
        } catch (Exception ignored) {
        }
        player = null;
    }
}
