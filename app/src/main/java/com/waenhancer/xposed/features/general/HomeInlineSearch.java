package com.waenhancer.xposed.features.general;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.Filter;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;

import com.waenhancer.xposed.core.WppCore;
import com.waenhancer.xposed.core.components.FMessageWpp;
import com.waenhancer.xposed.core.components.WaContactWpp;
import com.waenhancer.xposed.core.devkit.Unobfuscator;
import com.waenhancer.xposed.utils.Utils;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * Icon-mode search on the existing home chat list. No SearchFragment is created, no native
 * RecyclerView adapter is replaced, and the existing search-bar surface keeps its glass owner.
 * The filter hook is installed only when the native conversations filter can be resolved.
 */
final class HomeInlineSearch {

    private static final int ANIMATION_MS = 190;
    private static final int QUERY_DEBOUNCE_MS = 90;
    private static final int SEARCH_GAP_DP = 8;

    private final ClassLoader loader;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<Filter, CharSequence> filters = Collections.synchronizedMap(new WeakHashMap<>());
    private final WeakHashMap<Activity, ActivityState> activities = new WeakHashMap<>();
    private final int searchId;
    private final int innerId;
    private final int nativeTextId;
    private final int menuId;
    private final AtomicBoolean filterErrorReported = new AtomicBoolean();
    private volatile String query = "";
    private Runnable pendingFilter;

    HomeInlineSearch(ClassLoader loader) {
        this.loader = loader;
        this.searchId = Utils.getID("my_search_bar", "id");
        this.innerId = Utils.getID("search_bar_inner_layout", "id");
        this.nativeTextId = Utils.getID("search_text", "id");
        this.menuId = Utils.getID("menuitem_search", "id");
    }

    boolean install() {
        if (searchId <= 0 || innerId <= 0 || menuId <= 0) return false;
        try {
            Method publish = Unobfuscator.loadGetFiltersMethod(loader);
            if (publish == null) return false;
            Method perform = null;
            for (Class<?> type = publish.getDeclaringClass(); type != null && type != Filter.class;
                    type = type.getSuperclass()) {
                for (Method candidate : type.getDeclaredMethods()) {
                    if (candidate.getName().equals("performFiltering")
                            && candidate.getParameterCount() == 1
                            && candidate.getParameterTypes()[0] == CharSequence.class
                            && "android.widget.Filter$FilterResults".equals(candidate.getReturnType().getName())) {
                        perform = candidate;
                        break;
                    }
                }
                if (perform != null) break;
            }
            if (perform == null || !Filter.class.isAssignableFrom(publish.getDeclaringClass())) {
                return false;
            }
            XposedBridge.hookMethod(perform, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    try {
                    if (!(param.thisObject instanceof Filter) || param.getResult() == null) return;
                    Filter filter = (Filter) param.thisObject;
                    filters.put(filter, param.args[0] instanceof CharSequence
                            ? param.args[0].toString() : "");
                    String requested = query;
                    if (requested.isEmpty()) return;
                    Object result = param.getResult();
                    Object values;
                    try { values = XposedHelpers.getObjectField(result, "values"); }
                    catch (Throwable ignored) { return; }
                    if (!(values instanceof List<?>)) return;
                    List<?> rows = (List<?>) values;
                    if (!hasChatRows(rows)) return; // Unknown WhatsApp layout: leave results native.
                    List<Object> matching = new ArrayList<>(rows.size());
                    Map<String, String> titles = new HashMap<>();
                    for (Object row : rows) {
                        Object jid = chatJid(row);
                        if (jid == null || matches(jid, requested, titles)) {
                            matching.add(row); // Keep native non-chat headers and separators.
                        }
                    }
                    XposedHelpers.setObjectField(result, "values", matching);
                    XposedHelpers.setIntField(result, "count", matching.size());
                    } catch (Throwable error) {
                        if (filterErrorReported.compareAndSet(false, true)) {
                            XposedBridge.log("[WAEX/HomeInlineSearch] native filter left untouched: " + error);
                        }
                    }
                }
            });

            Class<?> home = WppCore.getHomeActivityClass(loader);
            XposedHelpers.findAndHookMethod(home, "onCreate", android.os.Bundle.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (param.thisObject instanceof Activity) attach((Activity) param.thisObject);
                }
            });
            Method resume = declared(home, "onResume");
            if (resume != null) {
                XposedBridge.hookMethod(resume, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) {
                        if (param.thisObject instanceof Activity) attach((Activity) param.thisObject);
                    }
                });
            }
            Method destroy = declared(home, "onDestroy");
            if (destroy != null) {
                XposedBridge.hookMethod(destroy, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (param.thisObject instanceof Activity) detach((Activity) param.thisObject);
                    }
                });
            }
            XposedHelpers.findAndHookMethod(home, "onPrepareOptionsMenu", Menu.class, new XC_MethodHook() {
                @Override protected void afterHookedMethod(MethodHookParam param) {
                    if (!(param.thisObject instanceof Activity)) return;
                    MenuItem item = ((Menu) param.args[0]).findItem(menuId);
                    if (item == null) return;
                    item.setVisible(true);
                    Activity activity = (Activity) param.thisObject;
                    item.setOnMenuItemClickListener(clicked -> expand(activity));
                }
            });
            Method selected = declared(home, "onOptionsItemSelected", MenuItem.class);
            if (selected != null) {
                XposedBridge.hookMethod(selected, new XC_MethodHook() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        if (!(param.thisObject instanceof Activity) || !(param.args[0] instanceof MenuItem)) return;
                        if (((MenuItem) param.args[0]).getItemId() == menuId
                                && expand((Activity) param.thisObject)) param.setResult(true);
                    }
                });
            }
            keepSearchMenuAvailable();
            return true;
        } catch (Throwable t) {
            XposedBridge.log("[WAEX/HomeInlineSearch] compatibility unavailable; native search retained: " + t);
            return false;
        }
    }

    private static Method declared(Class<?> owner, String name, Class<?>... parameters) {
        try { return owner.getDeclaredMethod(name, parameters); }
        catch (NoSuchMethodException ignored) { return null; }
    }

    /** WhatsApp creates the search action only for certain page IDs on some builds. */
    private void keepSearchMenuAvailable() {
        try {
            Method prepare = Unobfuscator.loadAddOptionSearchBarMethod(loader);
            Field page = Unobfuscator.loadGetCurrentPageInHomeField(loader);
            if (page.getType() != int.class) return;
            page.setAccessible(true);
            XposedBridge.hookMethod(prepare, new XC_MethodHook() {
                @Override protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                    Object owner = Modifier.isStatic(param.method.getModifiers())
                            ? (param.args.length > 0 ? param.args[0] : null) : param.thisObject;
                    if (owner == null) return;
                    int original = page.getInt(owner);
                    param.setObjectExtra("inline-page-owner", owner);
                    param.setObjectExtra("inline-page-original", original);
                    page.setInt(owner, 1);
                }

                @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                    Object owner = param.getObjectExtra("inline-page-owner");
                    Object original = param.getObjectExtra("inline-page-original");
                    if (owner != null && original instanceof Integer) {
                        page.setInt(owner, (Integer) original);
                    }
                }
            });
        } catch (Throwable ignored) {
            // Some builds already include the icon. Leave their menu lifecycle unchanged.
        }
    }

    private void attach(Activity activity) {
        ActivityState state = activities.get(activity);
        if (state != null) {
            scheduleScan(state);
            return;
        }
        View root = activity.getWindow() == null ? null : activity.getWindow().getDecorView();
        if (root == null) return;
        state = new ActivityState(activity, root);
        activities.put(activity, state);
        final ActivityState installed = state;
        state.scan = () -> scan(installed);
        state.layoutListener = () -> scheduleScan(installed);
        root.getViewTreeObserver().addOnGlobalLayoutListener(state.layoutListener);
        root.getViewTreeObserver().addOnPreDrawListener(new ViewTreeObserver.OnPreDrawListener() {
            @Override public boolean onPreDraw() {
                if (root.getViewTreeObserver().isAlive()) {
                    root.getViewTreeObserver().removeOnPreDrawListener(this);
                }
                scan(installed);
                // A newly collapsed native bar needs another layout before presentation.
                return installed.rows.isEmpty();
            }
        });
        scheduleScan(state);
    }

    private void detach(Activity activity) {
        ActivityState state = activities.remove(activity);
        if (state == null) return;
        if (state.layoutListener != null && state.root.getViewTreeObserver().isAlive()) {
            state.root.getViewTreeObserver().removeOnGlobalLayoutListener(state.layoutListener);
        }
        main.removeCallbacks(state.scan);
        collapse(state, false);
        for (SearchRow row : state.rows.values()) row.restore();
        state.rows.clear();
    }

    private void scheduleScan(ActivityState state) {
        if (state.scheduled) return;
        state.scheduled = true;
        main.postDelayed(state.scan, 32);
    }

    private void scan(ActivityState state) {
        state.scheduled = false;
        if (!state.root.isAttachedToWindow() || state.activity.isFinishing()) return;
        View pager = state.root.findViewById(Utils.getID("pager_holder", "id"));
        if (pager == null) return;
        List<View> found = new ArrayList<>();
        collect(pager, found, 0);
        for (View nativeRow : found) {
            if (state.rows.containsKey(nativeRow) || !(nativeRow instanceof FrameLayout)) continue;
            View inner = nativeRow.findViewById(innerId);
            if (inner == null || !inner.isAttachedToWindow()) continue;
            SearchRow row = new SearchRow((FrameLayout) nativeRow, inner, nativeTextId);
            state.rows.put(nativeRow, row);
            row.configure();
        }
        if (state.active != null) state.active.positionEditor();
    }

    private void collect(View view, List<View> found, int depth) {
        if (depth > 13 || found.size() >= 6) return;
        if (view.getId() == searchId) {
            found.add(view);
            return;
        }
        if (!(view instanceof ViewGroup)) return;
        ViewGroup group = (ViewGroup) view;
        for (int i = 0; i < group.getChildCount(); i++) collect(group.getChildAt(i), found, depth + 1);
    }

    /** Returns false if native search must handle the click because no supported pill is present. */
    private boolean expand(Activity activity) {
        ActivityState state = activities.get(activity);
        if (state == null) {
            attach(activity);
            state = activities.get(activity);
        }
        if (state == null) return false;
        scan(state);
        if (state.active != null) {
            focus(state.active.editor);
            return true;
        }
        SearchRow target = visibleRow(state);
        if (target == null) return false;
        state.active = target;
        target.row.animate().cancel();
        target.row.setVisibility(View.VISIBLE);
        target.positionEditor();
        target.row.post(target::positionEditor);
        target.row.setAlpha(0f);
        target.row.setTranslationY(-dp(activity, 7));
        target.row.animate().alpha(1f).translationY(0).setDuration(ANIMATION_MS).start();
        if (target.nativeLabel != null) target.nativeLabel.setAlpha(0f);
        target.editor.setVisibility(View.VISIBLE);
        focus(target.editor);
        registerBack(state);
        return true;
    }

    private SearchRow visibleRow(ActivityState state) {
        SearchRow best = null;
        int maxVisible = 0;
        Rect rect = new Rect();
        for (SearchRow row : state.rows.values()) {
            View parent = row.row.getParent() instanceof View ? (View) row.row.getParent() : null;
            if (parent == null || !parent.getGlobalVisibleRect(rect)) continue;
            int width = rect.width();
            if (width > maxVisible) {
                best = row;
                maxVisible = width;
            }
        }
        return best;
    }

    private void focus(EditText editor) {
        editor.requestFocus();
        editor.post(() -> {
            InputMethodManager keyboard = (InputMethodManager) editor.getContext()
                    .getSystemService(Context.INPUT_METHOD_SERVICE);
            if (keyboard != null) keyboard.showSoftInput(editor, InputMethodManager.SHOW_IMPLICIT);
        });
    }

    private void collapse(ActivityState state, boolean animate) {
        SearchRow target = state.active;
        if (target == null) return;
        state.active = null;
        unregisterBack(state);
        InputMethodManager keyboard = (InputMethodManager) state.activity
                .getSystemService(Context.INPUT_METHOD_SERVICE);
        if (keyboard != null) keyboard.hideSoftInputFromWindow(target.editor.getWindowToken(), 0);
        target.editor.clearFocus();
        target.editor.setText("");
        if (target.nativeLabel != null) target.nativeLabel.setAlpha(1f);
        if (animate) {
            target.row.animate().cancel();
            target.row.animate().alpha(0f).translationY(-dp(state.activity, 7))
                    .setDuration(ANIMATION_MS).withEndAction(() -> {
                        if (state.active != target) target.row.setVisibility(View.GONE);
                    }).start();
        } else {
            target.row.animate().cancel();
            target.row.setVisibility(View.GONE);
            target.row.setAlpha(1f);
            target.row.setTranslationY(0f);
        }
    }

    private void updateQuery(String value) {
        query = value == null ? "" : value;
        if (pendingFilter != null) main.removeCallbacks(pendingFilter);
        pendingFilter = () -> {
            Map<Filter, CharSequence> snapshot;
            synchronized (filters) { snapshot = new HashMap<>(filters); }
            for (Map.Entry<Filter, CharSequence> item : snapshot.entrySet()) {
                try { item.getKey().filter(item.getValue()); }
                catch (Throwable ignored) { /* Unknown native filter: leave its list intact. */ }
            }
        };
        main.postDelayed(pendingFilter, QUERY_DEBOUNCE_MS);
    }

    private static boolean hasChatRows(List<?> rows) {
        for (Object row : rows) if (chatJid(row) != null) return true;
        return false;
    }

    private static Object chatJid(Object row) {
        if (row == null) return null;
        for (String name : new String[]{"A00", "A01"}) {
            try {
                Object jid = XposedHelpers.getObjectField(row, name);
                if (jid != null && XposedHelpers.findMethodExactIfExists(jid.getClass(), "getRawString") != null) {
                    return jid;
                }
            } catch (Throwable ignored) { }
        }
        return null;
    }

    private static boolean matches(Object jid, String requested, Map<String, String> titles) {
        try {
            Object raw = XposedHelpers.callMethod(jid, "getRawString");
            if (!(raw instanceof String)) return true;
            String rawJid = (String) raw;
            String title = titles.get(rawJid);
            if (title == null) {
                FMessageWpp.UserJid user = new FMessageWpp.UserJid(jid);
                title = WppCore.getContactName(user);
                if (title.isEmpty() || "Whatsapp Contact".equals(title)) {
                    WaContactWpp contact = WaContactWpp.getWaContactFromJid(user);
                    if (contact != null && contact.getDisplayName() != null) title = contact.getDisplayName();
                }
                titles.put(rawJid, title);
            }
            return HomeInlineSearchMatcher.matches(requested, title, rawJid, rawJid);
        } catch (Throwable ignored) {
            return true; // Unknown row identity must never disappear due to our filter.
        }
    }

    private static int dp(Context context, int value) {
        return (int) (value * context.getResources().getDisplayMetrics().density + .5f);
    }

    private static final class ActivityState {
        final Activity activity;
        final View root;
        Runnable scan;
        final WeakHashMap<View, SearchRow> rows = new WeakHashMap<>();
        ViewTreeObserver.OnGlobalLayoutListener layoutListener;
        SearchRow active;
        boolean scheduled;
        android.window.OnBackInvokedCallback back;

        ActivityState(Activity activity, View root) {
            this.activity = activity;
            this.root = root;
        }
    }

    private final class SearchRow {
        final FrameLayout row;
        final View inner;
        final View nativeLabel;
        final View touchGuard;
        final EditText editor;
        final int nativeMargin;

        SearchRow(FrameLayout row, View inner, int textId) {
            this.row = row;
            this.inner = inner;
            View text = textId > 0 ? row.findViewById(textId) : null;
            this.nativeLabel = text;
            this.touchGuard = new View(row.getContext());
            this.editor = new EditText(row.getContext());
            this.nativeMargin = row.getLayoutParams() instanceof ViewGroup.MarginLayoutParams
                    ? ((ViewGroup.MarginLayoutParams) row.getLayoutParams()).topMargin : 0;
        }

        void configure() {
            editor.setSingleLine(true);
            editor.setTextSize(16);
            editor.setTextColor(Color.WHITE);
            editor.setHintTextColor(0xff8696a0);
            editor.setHint("Search conversations");
            editor.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
            editor.setImeOptions(android.view.inputmethod.EditorInfo.IME_ACTION_SEARCH);
            editor.setBackground(new ColorDrawable(Color.TRANSPARENT));
            editor.setPadding(0, 0, 0, 0);
            editor.setVisibility(View.GONE);
            touchGuard.setClickable(true);
            touchGuard.setContentDescription("Search conversations");
            touchGuard.setOnClickListener(v -> focus(editor));
            // This layer sits above WhatsApp's native search click targets, preventing the
            // SearchFragment transition while leaving the native glass pill underneath.
            row.addView(touchGuard, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.START));
            row.addView(editor, new FrameLayout.LayoutParams(1, 1, Gravity.TOP | Gravity.START));
            editor.addTextChangedListener(new TextWatcher() {
                @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) { }
                @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                    updateQuery(s.toString());
                }
                @Override public void afterTextChanged(Editable s) { }
            });
            row.setOnClickListener(v -> focus(editor));
            if (row.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) row.getLayoutParams();
                lp.topMargin = nativeMargin + dp(row.getContext(), SEARCH_GAP_DP);
                row.setLayoutParams(lp);
            }
            row.setVisibility(View.GONE);
        }

        void positionEditor() {
            if (inner.getWidth() == 0 || inner.getHeight() == 0) return;
            int[] origin = new int[2];
            int[] innerPosition = new int[2];
            row.getLocationInWindow(origin);
            inner.getLocationInWindow(innerPosition);
            int start = dp(row.getContext(), 54);
            int end = dp(row.getContext(), 36);
            int available = inner.getWidth() - start - end;
            if (available <= 0) return;
            FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) editor.getLayoutParams();
            int left = innerPosition[0] - origin[0] + start;
            int top = innerPosition[1] - origin[1];
            if (lp.width != available || lp.height != inner.getHeight()
                    || lp.leftMargin != left || lp.topMargin != top) {
                lp.width = available;
                lp.height = inner.getHeight();
                lp.leftMargin = left;
                lp.topMargin = top;
                editor.setLayoutParams(lp);
            }
            FrameLayout.LayoutParams guard = (FrameLayout.LayoutParams) touchGuard.getLayoutParams();
            int guardLeft = innerPosition[0] - origin[0];
            if (guard.width != inner.getWidth() || guard.height != inner.getHeight()
                    || guard.leftMargin != guardLeft || guard.topMargin != top) {
                guard.width = inner.getWidth();
                guard.height = inner.getHeight();
                guard.leftMargin = guardLeft;
                guard.topMargin = top;
                touchGuard.setLayoutParams(guard);
            }
        }

        void restore() {
            row.animate().cancel();
            if (nativeLabel != null) nativeLabel.setAlpha(1f);
            row.removeView(editor);
            row.removeView(touchGuard);
            if (row.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) {
                ViewGroup.MarginLayoutParams lp = (ViewGroup.MarginLayoutParams) row.getLayoutParams();
                lp.topMargin = nativeMargin;
                row.setLayoutParams(lp);
            }
        }
    }

    private void registerBack(ActivityState state) {
        if (Build.VERSION.SDK_INT < 33 || state.back != null) return;
        try {
            state.back = () -> collapse(state, true);
            state.activity.getOnBackInvokedDispatcher().registerOnBackInvokedCallback(
                    android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT, state.back);
        } catch (Throwable ignored) { state.back = null; }
    }

    private void unregisterBack(ActivityState state) {
        if (Build.VERSION.SDK_INT < 33 || state.back == null) return;
        try { state.activity.getOnBackInvokedDispatcher().unregisterOnBackInvokedCallback(state.back); }
        catch (Throwable ignored) { }
        state.back = null;
    }
}
