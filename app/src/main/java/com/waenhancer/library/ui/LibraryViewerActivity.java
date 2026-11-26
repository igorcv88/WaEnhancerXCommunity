package com.waenhancer.library.ui;

import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.bumptech.glide.Glide;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.waenhancer.R;
import com.waenhancer.library.android.MediaLibraryRuntime;
import com.waenhancer.library.core.LibraryItem;
import com.waenhancer.library.core.MediaLibrary;
import com.waenhancer.provider.MediaLibraryProvider;

import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;

/**
 * Opens one library item at a time: photos with pinch/double-tap zoom, videos with the platform
 * player (streamed from the module's own provider, so no copy is written anywhere). A horizontal
 * fling moves to the previous/next item of the list that opened the viewer. Not exported.
 */
public final class LibraryViewerActivity extends AppCompatActivity {
    public static final String EXTRA_IDS = "ids";
    public static final String EXTRA_INDEX = "index";

    private MediaLibrary library;
    private ArrayList<String> ids;
    private int index;
    private FrameLayout stage;
    private TextView caption;
    private ZoomImageView image;
    private VideoView video;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ids = getIntent().getStringArrayListExtra(EXTRA_IDS);
        index = savedInstanceState != null ? savedInstanceState.getInt(EXTRA_INDEX)
                : getIntent().getIntExtra(EXTRA_INDEX, 0);
        try {
            library = MediaLibraryRuntime.get(this);
        } catch (Exception failure) {
            finish();
            return;
        }
        if (ids == null || ids.isEmpty()) {
            finish();
            return;
        }
        index = Math.max(0, Math.min(index, ids.size() - 1));
        stage = new FrameLayout(this);
        stage.setBackgroundColor(Color.BLACK);
        caption = new TextView(this);
        caption.setTextColor(Color.WHITE);
        caption.setBackgroundColor(0x99000000);
        int pad = (int) (12 * getResources().getDisplayMetrics().density);
        caption.setPadding(pad, pad, pad, pad);
        FrameLayout root = new FrameLayout(this);
        root.addView(stage, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        root.addView(caption, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM));
        setContentView(root);
        show();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putInt(EXTRA_INDEX, index);
    }

    private void step(int delta) {
        int next = index + delta;
        if (next < 0 || next >= ids.size()) return;
        index = next;
        show();
    }

    private void show() {
        if (video != null) video.stopPlayback();
        stage.removeAllViews();
        image = null;
        video = null;
        LibraryItem item = library.get(ids.get(index));
        if (item == null || !item.isAvailable()) {
            caption.setText(R.string.media_library_item_unavailable);
            return;
        }
        StringBuilder text = new StringBuilder();
        text.append(index + 1).append('/').append(ids.size());
        if (item.messageTimestamp > 0) {
            text.append(" · ").append(DateFormat.getDateTimeInstance(DateFormat.MEDIUM, DateFormat.SHORT)
                    .format(new Date(normalizeMillis(item.messageTimestamp))));
        }
        if (item.caption != null) text.append('\n').append(item.caption);
        caption.setText(text);
        if (item.isVideo()) {
            video = new VideoView(this);
            FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER);
            stage.addView(video, params);
            MediaController controller = new MediaController(this);
            controller.setAnchorView(video);
            video.setMediaController(controller);
            video.setVideoURI(MediaLibraryProvider.itemUri(item.uuid));
            video.setOnErrorListener((mp, what, extra) -> {
                Toast.makeText(this, R.string.media_library_cannot_play, Toast.LENGTH_LONG).show();
                return true;
            });
            video.setOnPreparedListener(mp -> video.start());
            attachSwipe(video);
        } else {
            image = new ZoomImageView(this);
            stage.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            try {
                Glide.with(this).load(library.fileOf(item)).diskCacheStrategy(DiskCacheStrategy.NONE)
                        .into(new com.bumptech.glide.request.target.CustomViewTarget<ImageView, Drawable>(image) {
                            @Override
                            public void onResourceReady(Drawable resource,
                                                        @Nullable com.bumptech.glide.request.transition.Transition<? super Drawable> transition) {
                                ((ZoomImageView) view).setDrawable(resource);
                            }

                            @Override
                            protected void onResourceCleared(@Nullable Drawable placeholder) {
                                view.setImageDrawable(null);
                            }

                            @Override
                            public void onLoadFailed(@Nullable Drawable errorDrawable) {
                                caption.setText(R.string.media_library_item_unavailable);
                            }
                        });
            } catch (java.io.IOException invalid) {
                caption.setText(R.string.media_library_item_unavailable);
            }
        }
    }

    /** WhatsApp stores message times in milliseconds; older rows may be seconds. */
    static long normalizeMillis(long timestamp) {
        return timestamp < 100_000_000_000L ? timestamp * 1000L : timestamp;
    }

    private void attachSwipe(View view) {
        GestureDetector detector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onFling(@Nullable MotionEvent e1, MotionEvent e2, float vx, float vy) {
                if (Math.abs(vx) > Math.abs(vy) * 1.5f && Math.abs(vx) > 800) {
                    step(vx < 0 ? 1 : -1);
                    return true;
                }
                return false;
            }
        });
        view.setOnTouchListener((v, event) -> {
            if (event.getActionMasked() == MotionEvent.ACTION_UP) v.performClick();
            return detector.onTouchEvent(event) || v.onTouchEvent(event);
        });
    }

    @Override
    protected void onPause() {
        if (video != null) video.pause();
        super.onPause();
    }

    /** Matrix-based zoom: pinch, double tap, pan while zoomed, fling to change item at 1x. */
    private final class ZoomImageView extends ImageView {
        private final Matrix matrix = new Matrix();
        private float baseScale = 1f;
        private float zoom = 1f;
        private final ScaleGestureDetector scaler;
        private final GestureDetector gestures;

        ZoomImageView(android.content.Context context) {
            super(context);
            setScaleType(ScaleType.MATRIX);
            scaler = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
                @Override
                public boolean onScale(ScaleGestureDetector detector) {
                    float factor = detector.getScaleFactor();
                    float next = Math.max(1f, Math.min(6f, zoom * factor));
                    factor = next / zoom;
                    zoom = next;
                    matrix.postScale(factor, factor, detector.getFocusX(), detector.getFocusY());
                    clamp();
                    return true;
                }
            });
            gestures = new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                @Override
                public boolean onDoubleTap(MotionEvent e) {
                    if (zoom > 1.01f) fit();
                    else {
                        zoom = 2.5f;
                        matrix.postScale(2.5f, 2.5f, e.getX(), e.getY());
                        clamp();
                    }
                    return true;
                }

                @Override
                public boolean onScroll(@Nullable MotionEvent e1, MotionEvent e2, float dx, float dy) {
                    if (zoom <= 1.01f) return false;
                    matrix.postTranslate(-dx, -dy);
                    clamp();
                    return true;
                }

                @Override
                public boolean onFling(@Nullable MotionEvent e1, MotionEvent e2, float vx, float vy) {
                    if (zoom <= 1.01f && Math.abs(vx) > Math.abs(vy) * 1.5f && Math.abs(vx) > 800) {
                        step(vx < 0 ? 1 : -1);
                        return true;
                    }
                    return false;
                }
            });
        }

        void setDrawable(Drawable drawable) {
            setImageDrawable(drawable);
            post(this::fit);
        }

        private void fit() {
            Drawable d = getDrawable();
            if (d == null || getWidth() == 0 || d.getIntrinsicWidth() <= 0) return;
            baseScale = Math.min(getWidth() / (float) d.getIntrinsicWidth(), getHeight() / (float) d.getIntrinsicHeight());
            zoom = 1f;
            matrix.reset();
            matrix.postScale(baseScale, baseScale);
            matrix.postTranslate((getWidth() - d.getIntrinsicWidth() * baseScale) / 2f,
                    (getHeight() - d.getIntrinsicHeight() * baseScale) / 2f);
            setImageMatrix(matrix);
        }

        private void clamp() {
            Drawable d = getDrawable();
            if (d == null) return;
            RectF bounds = new RectF(0, 0, d.getIntrinsicWidth(), d.getIntrinsicHeight());
            matrix.mapRect(bounds);
            float dx = 0;
            float dy = 0;
            if (bounds.width() <= getWidth()) dx = (getWidth() - bounds.width()) / 2f - bounds.left;
            else if (bounds.left > 0) dx = -bounds.left;
            else if (bounds.right < getWidth()) dx = getWidth() - bounds.right;
            if (bounds.height() <= getHeight()) dy = (getHeight() - bounds.height()) / 2f - bounds.top;
            else if (bounds.top > 0) dy = -bounds.top;
            else if (bounds.bottom < getHeight()) dy = getHeight() - bounds.bottom;
            matrix.postTranslate(dx, dy);
            setImageMatrix(matrix);
        }

        @Override
        protected void onSizeChanged(int w, int h, int oldw, int oldh) {
            super.onSizeChanged(w, h, oldw, oldh);
            fit();
        }

        @Override
        public boolean performClick() {
            return super.performClick();
        }

        @Override
        public boolean onTouchEvent(MotionEvent event) {
            boolean handled = scaler.onTouchEvent(event);
            handled |= gestures.onTouchEvent(event);
            return handled || super.onTouchEvent(event) || true;
        }
    }
}
