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
    private static final String MARKER = "WaEXBubbleMode";
    private static final String PROPERTY = "debug.waex.bubbletouch";
    private static final String TAG = "WaEXBubbleTouch";
    private static final long WINDOW_MS = 60_000;
    private static final int MAX_LINES = 600;
    private static final int MAX_NODES = 96;
    private static final int MAX_HOOKS = 128;

    private final BubbleTouchMode mode;
    private final Activity activity;
    private final View decor;
    private final Supplier<String> glassScope;
    private final Runnable onClose;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final long deadline = SystemClock.uptimeMillis() + WINDOW_MS;
    private final Map<Method, XC_MethodHook.Unhook> hooks = new HashMap<>();
    private final Runnable expire = this::close;
    private Method dispatchMethod;
    private final Runnable deferredInstall = this::installDispatch;
    private View row;
    private ListView list;
    private Object adapter;
    private int gesture;
    private int lines;
    private int activityEntries;
    private int activityExits;
    private int listMatches;
    private int nodes;
    private int listenerReads;
    private int formattedCharacters;
    private int adapterResolutions;
    private int adapterHooks;
    private int binds;
    private int rowResolutions;
    private int rowHooks;
    private int rowCallbacks;
    private int errors;
    private boolean closed;

    static BubbleTouchMode configuredMode() {
        return Configuration.MODE;
    }

    // Read once per process so subsequent starts cannot mix experimental conditions.
    private static final class Configuration {
        static final BubbleTouchMode MODE = read();

        private static BubbleTouchMode read() {
            try {
                Class<?> properties = Class.forName("android.os.SystemProperties");
                String value = (String) XposedHelpers.callStaticMethod(properties, "get", PROPERTY, "");
                return BubbleTouchMode.parse(value);
            } catch (ClassNotFoundException | RuntimeException | LinkageError ignored) {
                Log.w(MARKER, "phase=property-unavailable");
                return BubbleTouchMode.UNSET;
            }
        }
    }

    BubbleTouchTrace(BubbleTouchMode mode, Activity activity, View decor,
                     Supplier<String> glassScope, Runnable onClose) {
        this.mode = mode;
        this.activity = activity;
        this.decor = decor;
        this.glassScope = glassScope;
        this.onClose = onClose;
    }

    boolean owns(Activity candidate) {
        return activity == candidate;
    }

    void start() {
        if (mode == BubbleTouchMode.OFF || mode == BubbleTouchMode.INVALID) {
            marker("phase=ready installed=0");
            close();
            return;
        }
        dispatchMethod = resolve(activity.getClass(), "dispatchTouchEvent", MotionEvent.class);
        if (mode == BubbleTouchMode.RESOLVE || dispatchMethod == null) {
            marker("phase=ready installed=0 target=" + dispatchTarget());
            close();
            return;
        }
        handler.postDelayed(expire, WINDOW_MS);
        if (mode == BubbleTouchMode.LATE_EMPTY) {
            marker("phase=scheduled installed=0 target=" + dispatchTarget());
            if (!handler.postDelayed(deferredInstall, 500)) close();
            return;
        }
        installDispatch();
    }

    private void installDispatch() {
        if (closed) return;
        XC_MethodHook hook = mode.callbacks() ? activityObserver() : new XC_MethodHook() {};
        boolean installed = install(dispatchMethod, hook);
        marker("phase=ready installed=" + (installed ? 1 : 0) + " target=" + dispatchTarget());
        if (!installed) close();
    }

    private String dispatchTarget() {
        return dispatchMethod == null ? "unavailable" : dispatchMethod.getDeclaringClass().getName();
    }

    private XC_MethodHook activityObserver() {
        return new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                if (param.thisObject != activity) return;
                safely(() -> {
                    activityEntries++;
                    MotionEvent event = (MotionEvent) param.args[0];
                    if (mode.list() && event.getActionMasked() == MotionEvent.ACTION_DOWN) selectRow(event);
                    if (mode.formats() && row != null && traceAction(event))
                        emit("activity dispatch enter action=" + event.getActionMasked());
                });
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                if (param.thisObject != activity) return;
                safely(() -> {
                    activityExits++;
                    MotionEvent event = (MotionEvent) param.args[0];
                    if (mode.formats() && row != null && traceAction(event))
                        emit("activity dispatch exit action=" + event.getActionMasked() + outcome(param));
                });
            }
        };
    }

    private void selectRow(MotionEvent event) {
        row = null;
        View candidate = decor.findViewById(android.R.id.list);
        if (!(candidate instanceof ListView) || !candidate.isShown()) return;
        list = (ListView) candidate;
        listMatches++;
        if (!mode.row()) return;
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
        if (mode.formats()) emit("down scope=" + glassScope.get() + " row=" + identity(row));
        if (mode.resolvesRowHooks()) observeView(list);
        if (mode.adapter()) observeAdapter();
        if (mode.hierarchy()) snapshot(row, new int[]{MAX_NODES});
    }

    private void observeAdapter() {
        adapter = ConversationItemListener.unwrapBaseAdapter(list.getAdapter());
        if (adapter == null) return;
        Method method = resolve(adapter.getClass(), "getView", int.class, View.class, ViewGroup.class);
        if (method == null) return;
        adapterResolutions++;
        if (mode == BubbleTouchMode.ADAPTER_RESOLVE || hooks.containsKey(method)) return;
        XC_MethodHook hook = mode == BubbleTouchMode.ADAPTER_EMPTY ? new XC_MethodHook() {} : new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                safely(() -> {
                    if (mode == BubbleTouchMode.FULL && param.thisObject == adapter
                            && row != null && param.args[1] == row)
                        emit("bind enter row=" + describe(row, listeners(row)));
                });
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                safely(() -> {
                    if (param.thisObject != adapter || row == null) return;
                    Object result = param.getResult();
                    if (result != row && param.args[1] != row) return;
                    binds++;
                    if (mode == BubbleTouchMode.FULL)
                        emit("bind exit row=" + describe(row, listeners(row))
                                + " returned=" + identity(result) + outcome(param));
                });
            }
        };
        if (install(method, hook)) adapterHooks++;
    }

    private void snapshot(View view, int[] remaining) {
        if (remaining[0]-- <= 0) return;
        nodes++;
        ListenerRefs refs = mode.reflects() ? listeners(view) : null;
        if (mode.reflects()) listenerReads++;
        if (mode.formats()) emit("node " + describe(view, refs));
        if (mode.resolvesRowHooks()) observeView(view);
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
        Method method = resolve(type, name, signature);
        if (method == null) return;
        rowResolutions++;
        if (mode == BubbleTouchMode.ROW_RESOLVE || hooks.containsKey(method)) return;
        XC_MethodHook hook = mode == BubbleTouchMode.ROW_HOOKS ? new XC_MethodHook() {} : new XC_MethodHook() {
            @Override protected void beforeHookedMethod(MethodHookParam param) {
                safely(() -> recordTouch(param, name, false));
            }

            @Override protected void afterHookedMethod(MethodHookParam param) {
                safely(() -> recordTouch(param, name, true));
            }
        };
        if (install(method, hook)) rowHooks++;
    }

    private void recordTouch(XC_MethodHook.MethodHookParam param, String method, boolean after) {
        View view = (View) param.thisObject;
        if (row == null || (view != list && !belongsToRow(view))) return;
        Object arg = param.args.length == 0 ? null : param.args[0];
        if (arg instanceof MotionEvent && !traceAction((MotionEvent) arg)) return;
        rowCallbacks++;
        if (!mode.logs()) return;
        String details = arg instanceof MotionEvent ? " action=" + ((MotionEvent) arg).getActionMasked()
                : arg instanceof Boolean ? " disallow=" + arg : "";
        emit(method + (after ? " exit " : " enter ") + identity(view) + details
                + " pressed=" + view.isPressed() + " selected=" + view.isSelected()
                + (after ? outcome(param) : ""));
    }

    boolean visuallyTraces() {
        return mode == BubbleTouchMode.FULL;
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

    private static Method resolve(Class<?> type, String name, Class<?>... signature) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try {
                return current.getDeclaredMethod(name, signature);
            } catch (NoSuchMethodException ignored) {
                // Continue with the inherited native implementation.
            } catch (RuntimeException | LinkageError ignored) {
                return null;
            }
        }
        return null;
    }

    private boolean install(Method method, XC_MethodHook hook) {
        if (hooks.size() >= MAX_HOOKS) return false;
        try {
            XC_MethodHook.Unhook registered = XposedBridge.hookMethod(method, hook);
            if (registered == null) return false;
            hooks.put(method, registered);
            return true;
        } catch (RuntimeException | LinkageError ignored) {
            return false;
        }
    }

    private void safely(Runnable action) {
        if (closed || Looper.myLooper() != handler.getLooper()) return;
        try {
            if (lines >= MAX_LINES || SystemClock.uptimeMillis() >= deadline) {
                close();
                return;
            }
            action.run();
        } catch (RuntimeException | LinkageError ignored) {
            errors++;
            // Diagnostics must leave the original call and its result intact.
        }
    }

    void close() {
        if (closed) return;
        closed = true;
        handler.removeCallbacks(expire);
        handler.removeCallbacks(deferredInstall);
        if (mode.callbacks()) marker("phase=summary entries=" + activityEntries + " exits=" + activityExits
                + " lists=" + listMatches + " rows=" + gesture + " nodes=" + nodes + " listenerReads=" + listenerReads
                + " formattedChars=" + formattedCharacters + " eventLines=" + (mode.logs() ? lines : 0)
                + " rowResolved=" + rowResolutions + " rowHooks=" + rowHooks + " rowCallbacks=" + rowCallbacks
                + " adapterResolved=" + adapterResolutions
                + " adapterHooks=" + adapterHooks + " selectedRowBinds=" + binds + " errors=" + errors);
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
        String event = "gesture=" + gesture + " " + value;
        formattedCharacters += event.length();
        if (mode.logs()) Log.i(TAG, event);
    }

    private void marker(String value) {
        Log.w(MARKER, "mode=" + mode.name().toLowerCase(java.util.Locale.ROOT).replace('_', '-')
                + " scope=" + glassScope.get() + " " + value);
    }

    private static boolean traceAction(MotionEvent event) {
        int action = event.getActionMasked();
        return action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_UP
                || action == MotionEvent.ACTION_CANCEL;
    }

    private static String describe(View view, ListenerRefs refs) {
        return identity(view) + " id=" + resource(view) + " parent=" + identity(view.getParent())
                + " bounds=" + view.getLeft() + "," + view.getTop() + "," + view.getRight() + "," + view.getBottom()
                + " visibility=" + view.getVisibility() + " enabled=" + view.isEnabled()
                + " clickable=" + view.isClickable() + " longClickable=" + view.isLongClickable()
                + " hasClick=" + view.hasOnClickListeners() + " listeners=" + refs
                + " background=" + drawable(view.getBackground()) + " foreground=" + drawable(view.getForeground());
    }

    private static String resource(View view) {
        try {
            return view.getResources().getResourceEntryName(view.getId());
        } catch (RuntimeException ignored) {
            return Integer.toHexString(view.getId());
        }
    }

    private record ListenerRefs(Object click, Object longClick, Object touch) {
        @Override public String toString() {
            return "click/" + identity(click) + ",long/" + identity(longClick) + ",touch/" + identity(touch);
        }
    }

    private static ListenerRefs listeners(View view) {
        try {
            Object info = XposedHelpers.getObjectField(view, "mListenerInfo");
            if (info == null) return null;
            return new ListenerRefs(XposedHelpers.getObjectField(info, "mOnClickListener"),
                    XposedHelpers.getObjectField(info, "mOnLongClickListener"),
                    XposedHelpers.getObjectField(info, "mOnTouchListener"));
        } catch (RuntimeException | LinkageError ignored) {
            return null;
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
