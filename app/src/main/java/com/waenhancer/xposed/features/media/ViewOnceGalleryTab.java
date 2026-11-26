package com.waenhancer.xposed.features.media;

import android.app.Activity;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.util.LruCache;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.FrameLayout;
import android.widget.GridView;
import android.widget.ImageView;
import android.widget.TextView;

import com.waenhancer.R;
import com.waenhancer.library.core.ChatJids;
import com.waenhancer.library.core.InlineBinder;
import com.waenhancer.xposed.core.WppCore;

import java.util.ArrayList;
import java.util.List;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedHelpers;

/**
 * Adds a "View once" chip to WhatsApp's per-contact media screen ("All media":
 * {@code com.whatsapp.gallery.ui.MediaGalleryActivity}, chips in {@code chip_layout}, content in
 * {@code media_hub_fragment_container}; observed on 2.26.38.74 with the Element Inspector).
 *
 * <p>The chip shows this contact's kept view-once photos and videos, separately from WhatsApp's
 * Photos/Videos lists, in a grid drawn over WhatsApp's content area. WhatsApp's own lists,
 * queries and database are not changed. Selecting any native chip hides the grid again. Every
 * step fails closed: an unidentifiable screen, contact or account leaves the screen native.</p>
 */
final class ViewOnceGalleryTab {
    private static final String ACTIVITY = "com.whatsapp.gallery.ui.MediaGalleryActivity";
    private static final String TAG_CHIP = "waex_view_once_chip";
    private static final String TAG_GRID = "waex_view_once_grid";
    private static final LruCache<String, Bitmap> THUMBS = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override
        protected int sizeOf(String key, Bitmap value) {
            return value.getByteCount();
        }
    };

    private ViewOnceGalleryTab() { }

    static void install() {
        XposedHelpers.findAndHookMethod(Activity.class, "onPostResume", new XC_MethodHook() {
            @Override
            protected void afterHookedMethod(MethodHookParam param) {
                Activity activity = (Activity) param.thisObject;
                if (!ACTIVITY.equals(activity.getClass().getName())) return;
                View decor = activity.getWindow().getDecorView();
                decor.post(() -> attach(activity));
            }
        });
    }

    private static void attach(Activity activity) {
        try {
            View root = activity.getWindow().getDecorView();
            ViewGroup chips = find(root, "chip_layout");
            ViewGroup content = find(root, "media_hub_fragment_container");
            if (chips == null || content == null || chips.getChildCount() == 0) return;
            if (chips.findViewWithTag(TAG_CHIP) != null) return;
            String chat = chatJid(activity);
            String own = MediaLibraryHook.ownJid();
            if (chat == null || own == null) return;
            Context context = activity;
            MediaLibraryHook.WORKER.execute(() -> {
                List<Entry> entries = load(context, own, chat);
                if (entries == null || entries.isEmpty()) return; // no tab without items
                MediaLibraryHook.MAIN.post(() -> addChip(activity, chips, content, own, chat, entries));
            });
        } catch (Throwable ignored) {
            // Fail closed: the screen stays native.
        }
    }

    /** The contact of this media screen: a JID in the intent, else the open conversation. */
    private static String chatJid(Activity activity) {
        try {
            Bundle extras = activity.getIntent() == null ? null : activity.getIntent().getExtras();
            if (extras != null) {
                List<Object> values = new ArrayList<>();
                for (String key : extras.keySet()) values.add(extras.get(key));
                String found = ChatJids.first(values);
                if (found != null) return found;
            }
        } catch (Throwable ignored) {
        }
        try {
            var current = WppCore.getCurrentUserJid();
            if (current != null && !current.isNull()) {
                String user = current.getUserRawString();
                return user != null ? user : current.getPhoneRawString();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    private static final class Entry {
        final InlineBinder.Ref ref;
        final InlineBinder.Answer answer;
        final boolean video;

        Entry(InlineBinder.Ref ref, InlineBinder.Answer answer, boolean video) {
            this.ref = ref;
            this.answer = answer;
            this.video = video;
        }
    }

    private static List<Entry> load(Context context, String own, String chat) {
        Bundle extras = new Bundle();
        extras.putString("own_jid", own);
        extras.putString("chat_jid", chat);
        extras.putBoolean("view_once", true);
        Bundle result;
        try {
            result = context.getContentResolver().call(MediaLibraryHook.PROVIDER, "list_chat", null, extras);
        } catch (Throwable failure) {
            return null;
        }
        if (result == null) return null;
        ArrayList<String> uuids = result.getStringArrayList("uuids");
        ArrayList<String> hashes = result.getStringArrayList("sha256");
        ArrayList<String> kinds = result.getStringArrayList("kinds");
        if (uuids == null || hashes == null || kinds == null || uuids.size() != hashes.size()) return null;
        List<Entry> entries = new ArrayList<>();
        InlineBinder.Ref ref = new InlineBinder.Ref(context.getPackageName(), own, chat, null, "tab", false);
        for (int i = 0; i < uuids.size(); i++) {
            entries.add(new Entry(ref, new InlineBinder.Answer(uuids.get(i), hashes.get(i)), "video".equals(kinds.get(i))));
        }
        return entries;
    }

    private static void addChip(Activity activity, ViewGroup chips, ViewGroup content, String own, String chat,
                                List<Entry> entries) {
        if (activity.isFinishing() || chips.findViewWithTag(TAG_CHIP) != null) return;
        View template = chips.getChildAt(chips.getChildCount() - 1);
        TextView chip = new TextView(activity);
        chip.setTag(TAG_CHIP);
        chip.setText(MediaLibraryHook.text(R.string.media_library_tab_view_once, "View once"));
        chip.setGravity(Gravity.CENTER);
        chip.setMinHeight(template.getHeight());
        if (template instanceof TextView) {
            TextView native0 = (TextView) template;
            chip.setTextColor(native0.getTextColors());
            chip.setTextSize(TypedValue.COMPLEX_UNIT_PX, native0.getTextSize());
            chip.setTypeface(native0.getTypeface());
            chip.setPadding(native0.getPaddingLeft(), native0.getPaddingTop(), native0.getPaddingRight(),
                    native0.getPaddingBottom());
        }
        Drawable background = template.getBackground();
        if (background != null && background.getConstantState() != null) {
            chip.setBackground(background.getConstantState().newDrawable().mutate());
        }
        ViewGroup.LayoutParams params = template.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                ? new ViewGroup.MarginLayoutParams((ViewGroup.MarginLayoutParams) template.getLayoutParams())
                : new ViewGroup.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, template.getHeight());
        params.width = ViewGroup.LayoutParams.WRAP_CONTENT;

        GridView grid = new GridView(activity);
        grid.setTag(TAG_GRID);
        grid.setNumColumns(4);
        int gap = (int) (2 * activity.getResources().getDisplayMetrics().density);
        grid.setHorizontalSpacing(gap);
        grid.setVerticalSpacing(gap);
        grid.setBackgroundColor(resolveBackground(content));
        grid.setVisibility(View.GONE);
        grid.setAdapter(new Adapter(activity, entries));
        grid.setOnItemClickListener((parent, view, position, id) -> MediaLibraryHook.WORKER.execute(() ->
                MediaLibraryHook.openGallery(activity, chat, null, null, entries.get(position).answer.uuid)));
        content.addView(grid, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        chip.setOnClickListener(v -> {
            boolean show = grid.getVisibility() != View.VISIBLE;
            grid.setVisibility(show ? View.VISIBLE : View.GONE);
            chip.setSelected(show);
            chip.setAlpha(show ? 1f : 0.85f);
        });
        // Any native chip hides our grid; the native chip still handles its own tap.
        for (int i = 0; i < chips.getChildCount(); i++) {
            chips.getChildAt(i).setOnTouchListener((v, event) -> {
                if (event.getActionMasked() == MotionEvent.ACTION_UP) {
                    grid.setVisibility(View.GONE);
                    chip.setSelected(false);
                    chip.setAlpha(0.85f);
                }
                return false;
            });
        }
        chip.setAlpha(0.85f);
        chips.addView(chip, params);
    }

    private static int resolveBackground(View content) {
        View probe = content;
        while (probe != null) {
            Drawable drawable = probe.getBackground();
            if (drawable instanceof android.graphics.drawable.ColorDrawable) {
                return ((android.graphics.drawable.ColorDrawable) drawable).getColor();
            }
            probe = probe.getParent() instanceof View ? (View) probe.getParent() : null;
        }
        return Color.BLACK;
    }

    private static ViewGroup find(View root, String idName) {
        int id = root.getResources().getIdentifier(idName, "id", root.getContext().getPackageName());
        if (id == 0) return null;
        View view = root.findViewById(id);
        return view instanceof ViewGroup ? (ViewGroup) view : null;
    }

    private static final class Adapter extends BaseAdapter {
        private final Activity activity;
        private final List<Entry> entries;

        Adapter(Activity activity, List<Entry> entries) {
            this.activity = activity;
            this.entries = entries;
        }

        @Override
        public int getCount() {
            return entries.size();
        }

        @Override
        public Object getItem(int position) {
            return entries.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            FrameLayout cell = convertView instanceof FrameLayout ? (FrameLayout) convertView : newCell(parent);
            ImageView image = (ImageView) cell.getChildAt(0);
            TextView badge = (TextView) cell.getChildAt(1);
            Entry entry = entries.get(position);
            badge.setVisibility(entry.video ? View.VISIBLE : View.GONE);
            String key = InlineBinder.thumbnailKey(entry.ref, entry.answer);
            cell.setTag(key);
            Bitmap cached = THUMBS.get(key);
            image.setImageBitmap(cached);
            if (cached == null) {
                MediaLibraryHook.WORKER.execute(() -> {
                    Bitmap bitmap = MediaLibraryHook.thumbnail(activity, entry.ref, entry.answer);
                    if (bitmap == null) return;
                    THUMBS.put(key, bitmap);
                    MediaLibraryHook.MAIN.post(() -> {
                        if (key.equals(cell.getTag())) image.setImageBitmap(bitmap); // recycled cells skip
                    });
                });
            }
            return cell;
        }

        private FrameLayout newCell(ViewGroup parent) {
            FrameLayout cell = new FrameLayout(parent.getContext()) {
                @Override
                protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
                    super.onMeasure(widthMeasureSpec, widthMeasureSpec);
                }
            };
            ImageView image = new ImageView(parent.getContext());
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setBackgroundColor(0xFF202020);
            cell.addView(image, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT));
            TextView badge = new TextView(parent.getContext());
            badge.setText("▶");
            badge.setTextColor(Color.WHITE);
            badge.setShadowLayer(4, 0, 0, Color.BLACK);
            cell.addView(badge, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.BOTTOM | Gravity.START));
            return cell;
        }
    }
}
