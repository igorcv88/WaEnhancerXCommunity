package com.waenhancer.xposed.features.customization;

import android.app.Activity;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.ListView;

import com.waenhancer.xposed.features.listeners.ConversationItemListener;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/** Temporary, read-only tracing of the native gesture path in a test conversation. */
final class BubbleTouchTrace {
    private static final String TAG = "WaEXBubbleTouch";
    private static final long WINDOW_MS = 60_000;
    private static final int MAX_LINES = 600;
    private static final int MAX_NODES = 96;
    private static final int MAX_HOOKS = 128;

    private final Activity activity;
    private final View decor;
    private final Supplier<String> glassScope;
    private final Runnable onClose;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final long deadline = SystemClock.uptimeMillis() + WINDOW_MS;
    private final Map<Method, XC_MethodHook.Unhook> hooks = new HashMap<>();
    private final Runnable expire = this::close;
    private View row;
    private ListView list;
    private Object adapter;
    private int gesture;
    private int lines;
    private boolean closed;

    static boolean enabled() {
        return Log.isLoggable(TAG, Log.DEBUG);
    }

    BubbleTouchTrace(Activity activity, View decor, Supplier<String> glassScope, Runnable onClose) {
        this.activity = activity;
        this.decor = decor;
        this.glassScope = glassScope;
        this.onClose = onClose;
    }

    boolean owns(Activity candidate) {
        return activity == candidate;
    }

    void start() {
        if (!observe(activity.getClass(), "dispatchTouchEvent", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.thisObject != activity) return;
                safely(() -> {
                    MotionEvent event = (MotionEvent) param.args[0];
                    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) selectRow(event);
                    if (row != null && traceAction(event))
                        emit("activity dispatch enter action=" + event.getActionMasked());
                });
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject != activity) return;
                safely(() -> {
                    MotionEvent event = (MotionEvent) param.args[0];
                    if (row != null && traceAction(event))
                        emit("activity dispatch exit action=" + event.getActionMasked() + outcome(param));
                });
            }
        }, MotionEvent.class)) {
            emit("activity hook unavailable");
            close();
            return;
        }
        handler.postDelayed(expire, WINDOW_MS);
        emit("armed scope=" + glassScope.get());
    }

    private void selectRow(MotionEvent event) {
        row = null;
        View candidate = decor.findViewById(android.R.id.list);
        if (!(candidate instanceof ListView) || !candidate.isShown()) return;
        list = (ListView) candidate;
        int[] origin = new int[2];
        list.getLocationOnScreen(origin);
        int x = (int) event.getRawX() - origin[0];
        int y = (int) event.getRawY() - origin[1];
        if (x < 0 || x >= list.getWidth() || y < 0 || y >= list.getHeight()) return;
        int position = list.pointToPosition(x, y);
        if (position == ListView.INVALID_POSITION) return;
        row = list.getChildAt(position - list.getFirstVisiblePosition());
        if (row == null) return;
        gesture++;
        emit("down scope=" + glassScope.get() + " row=" + identity(row));
        observeView(list);
        observeAdapter();
        snapshot(row, new int[]{MAX_NODES});
    }

    private void observeAdapter() {
        adapter = ConversationItemListener.unwrapBaseAdapter(list.getAdapter());
        if (adapter == null) return;
        observe(adapter.getClass(), "getView", new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                safely(() -> {
                    if (param.thisObject == adapter && row != null && param.args[1] == row)
                        emit("bind enter row=" + describe(row));
                });
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                safely(() -> {
                    if (param.thisObject != adapter || row == null) return;
                    Object result = param.getResult();
                    if (result == row || param.args[1] == row)
                        emit("bind exit row=" + describe(row) + " returned=" + identity(result) + outcome(param));
                });
            }
        }, int.class, View.class, ViewGroup.class);
    }

    private void snapshot(View view, int[] remaining) {
        if (remaining[0]-- <= 0) return;
        emit("node " + describe(view));
        observeView(view);
        if (view instanceof ViewGroup) {
            ViewGroup group = (ViewGroup) view;
            for (int i = 0; i < group.getChildCount() && remaining[0] > 0; i++)
                snapshot(group.getChildAt(i), remaining);
        }
    }

    private void observeView(View view) {
        Class<?> type = view.getClass();
        observeTouch(type, "dispatchTouchEvent", MotionEvent.class);
        observeTouch(type, "onTouchEvent", MotionEvent.class);
        observeTouch(type, "performClick");
        observeTouch(type, "performLongClick");
        observeTouch(type, "performLongClick", float.class, float.class);
        if (view instanceof ViewGroup) {
            observeTouch(type, "onInterceptTouchEvent", MotionEvent.class);
            observeTouch(type, "requestDisallowInterceptTouchEvent", boolean.class);
        }
    }

    private void observeTouch(Class<?> type, String name, Class<?>... signature) {
        observe(type, name, new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                safely(() -> recordTouch(param, name, false));
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                safely(() -> recordTouch(param, name, true));
            }
        }, signature);
    }

    private void recordTouch(XC_MethodHook.MethodHookParam param, String method, boolean after) {
        View view = (View) param.thisObject;
        if (row == null || (view != list && !belongsToRow(view))) return;
        Object arg = param.args.length == 0 ? null : param.args[0];
        if (arg instanceof MotionEvent && !traceAction((MotionEvent) arg)) return;
        String details = arg instanceof MotionEvent ? " action=" + ((MotionEvent) arg).getActionMasked()
                : arg instanceof Boolean ? " disallow=" + arg : "";
        emit(method + (after ? " exit " : " enter ") + identity(view) + details
                + " pressed=" + view.isPressed() + " selected=" + view.isSelected()
                + (after ? outcome(param) : ""));
    }

    void visual(View owner, Drawable drawable, String stage) {
        safely(() -> {
            if (row == null || !belongsToRow(owner)) return;
            emit(stage + " owner=" + identity(owner) + " drawable=" + drawable(drawable));
        });
    }

    private boolean belongsToRow(View view) {
        for (View current = view; current != null;) {
            if (current == row) return true;
            ViewParent parent = current.getParent();
            current = parent instanceof View ? (View) parent : null;
        }
        return false;
    }

    private boolean observe(Class<?> type, String name, XC_MethodHook hook, Class<?>... signature) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                Method method = current.getDeclaredMethod(name, signature);
                if (hooks.containsKey(method)) return true;
                if (hooks.size() >= MAX_HOOKS) return false;
                hooks.put(method, XposedBridge.hookMethod(method, hook));
                return true;
            } catch (NoSuchMethodException ignored) {
                // Continue with the inherited native implementation.
            } catch (RuntimeException | LinkageError ignored) {
                return false;
            }
        }
        return false;
    }

    private void safely(Runnable action) {
        if (closed || Looper.myLooper() != handler.getLooper()) return;
        try {
            if (lines >= MAX_LINES || SystemClock.uptimeMillis() >= deadline || !enabled()) {
                close();
                return;
            }
            action.run();
        } catch (RuntimeException | LinkageError ignored) {
            // Diagnostics must leave the original call and its result intact.
        }
    }

    void close() {
        if (closed) return;
        closed = true;
        handler.removeCallbacks(expire);
        for (XC_MethodHook.Unhook hook : hooks.values()) {
            try {
                hook.unhook();
            } catch (RuntimeException | LinkageError ignored) {
                // Closed callbacks are inert even if the runtime cannot unhook them.
            }
        }
        hooks.clear();
        row = null;
        list = null;
        adapter = null;
        onClose.run();
    }

    private void emit(String value) {
        if (lines >= MAX_LINES || SystemClock.uptimeMillis() >= deadline) return;
        lines++;
        Log.d(TAG, "gesture=" + gesture + " " + value);
    }

    private static boolean traceAction(MotionEvent event) {
        int action = event.getActionMasked();
        return action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL;
    }

    private static String describe(View view) {
        return identity(view) + " id=" + resource(view) + " parent=" + identity(view.getParent())
                + " bounds=" + view.getLeft() + "," + view.getTop() + "," + view.getRight() + "," + view.getBottom()
                + " visibility=" + view.getVisibility() + " enabled=" + view.isEnabled()
                + " clickable=" + view.isClickable() + " longClickable=" + view.isLongClickable()
                + " hasClick=" + view.hasOnClickListeners() + " listeners=" + listeners(view)
                + " background=" + drawable(view.getBackground()) + " foreground=" + drawable(view.getForeground());
    }

    private static String resource(View view) {
        try {
            return view.getResources().getResourceEntryName(view.getId());
        } catch (RuntimeException ignored) {
            return Integer.toHexString(view.getId());
        }
    }

    private static String listeners(View view) {
        try {
            Object info = XposedHelpers.getObjectField(view, "mListenerInfo");
            if (info == null) return "none";
            return "click/" + identity(XposedHelpers.getObjectField(info, "mOnClickListener"))
                    + ",long/" + identity(XposedHelpers.getObjectField(info, "mOnLongClickListener"))
                    + ",touch/" + identity(XposedHelpers.getObjectField(info, "mOnTouchListener"));
        } catch (RuntimeException | LinkageError ignored) {
            return "unavailable";
        }
    }

    private static String drawable(Drawable drawable) {
        if (drawable == null) return "none";
        Rect bounds = drawable.getBounds();
        return identity(drawable) + "/callback:" + identity(drawable.getCallback())
                + "/state:" + java.util.Arrays.hashCode(drawable.getState())
                + "/bounds:" + bounds.toShortString() + "/alpha:" + drawable.getAlpha()
                + (drawable instanceof android.graphics.drawable.ColorDrawable
                        ? "/transparent:" + (((android.graphics.drawable.ColorDrawable) drawable).getColor() >>> 24 == 0)
                        : "");
    }

    private static String identity(Object value) {
        return value == null ? "none" : value.getClass().getName() + "@"
                + Integer.toHexString(System.identityHashCode(value));
    }

    private static String outcome(XC_MethodHook.MethodHookParam param) {
        if (param.hasThrowable()) return " threw=true";
        Object result = param.getResult();
        return result instanceof Boolean ? " result=" + result : "";
    }
}
