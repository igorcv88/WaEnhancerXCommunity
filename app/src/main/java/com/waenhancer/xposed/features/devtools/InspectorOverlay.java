package com.waenhancer.xposed.features.devtools;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;

import com.google.android.material.chip.Chip;
import com.waenhancer.R;

import java.util.List;

/**
 * The inspector's own window: a highlight border plus a bottom panel, anchored to the host
 * Activity's window token so no SYSTEM_ALERT_WINDOW permission is required and WhatsApp's own
 * view tree is never touched.
 *
 * <p>Two windows, not one. The content window (border + panel) alternates between touchable and
 * not-touchable as the mode changes, because that is the whole mechanism behind NAVIGATE letting
 * touches fall through to WhatsApp. But a NOT_TOUCHABLE window also blocks its own children, so
 * the floating drag handle — which must always be movable, in either mode — lives in a second,
 * always-touchable window. {@link #attach()} adds both views; {@link #detach()} removes both.</p>
 */
public class InspectorOverlay {

    public enum Mode { NAVIGATE, PICK }

    private static final int FLAGS_NAVIGATE =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

    private static final int FLAGS_PICK =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

    private static final int HANDLE_FLAGS =
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                    | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

    private static final int HANDLE_SIZE_DP = 48;
    private static final int HIGHLIGHT_STROKE_DP = 3;

    private final Activity activity;
    private final Runnable onExit;
    private final WindowManager windowManager;

    private Mode mode = Mode.NAVIGATE;
    private InspectorSession session = InspectorSession.expired();

    /** The node currently shown in the panel, kept so "inspect parent/child" can navigate. */
    private ProbeNode currentNode;

    // Content window: highlight border + bottom panel. Flag alternates with mode.
    private FrameLayout contentRoot;
    private HighlightView highlightView;
    private View panelView;

    // Handle window: always touchable, never affected by mode.
    private View handleRoot;
    private WindowManager.LayoutParams handleParams;

    public InspectorOverlay(@NonNull Activity activity, @NonNull Runnable onExit) {
        this.activity = activity;
        this.onExit = onExit;
        this.windowManager = activity.getWindowManager();
    }

    public void attach() {
        if (contentRoot != null) return; // already attached

        contentRoot = new FrameLayout(activity);

        highlightView = new HighlightView(activity);
        contentRoot.addView(highlightView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // Full-screen touch catcher below the panel in z-order. It only ever receives events in
        // PICK mode, because in NAVIGATE the whole window is FLAG_NOT_TOUCHABLE and the window
        // manager never delivers input to it at all.
        View touchCatcher = new View(activity);
        touchCatcher.setOnTouchListener(this::onContentTouch);
        contentRoot.addView(touchCatcher, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        panelView = LayoutInflater.from(activity).inflate(R.layout.inspector_panel, contentRoot, false);
        panelView.setVisibility(View.GONE);
        FrameLayout.LayoutParams panelParams = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        panelParams.gravity = Gravity.BOTTOM;
        contentRoot.addView(panelView, panelParams);
        wirePanelActions();

        windowManager.addView(contentRoot, params(FLAGS_NAVIGATE));

        attachHandle();
    }

    private void attachHandle() {
        handleRoot = new View(activity);
        float density = activity.getResources().getDisplayMetrics().density;
        int sizePx = Math.round(HANDLE_SIZE_DP * density);
        handleRoot.setBackgroundColor(Color.argb(160, 33, 150, 243));
        handleRoot.setOnTouchListener(this::onHandleTouch);

        handleParams = new WindowManager.LayoutParams(
                sizePx, sizePx,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                HANDLE_FLAGS,
                PixelFormat.TRANSLUCENT);
        handleParams.token = activity.getWindow().getDecorView().getWindowToken();
        handleParams.gravity = Gravity.TOP | Gravity.START;
        handleParams.x = 0;
        handleParams.y = Math.round(120 * density);

        windowManager.addView(handleRoot, handleParams);
    }

    /** Drags the handle window by updating its own layout params — it never touches the content window. */
    private boolean onHandleTouch(View v, MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                dragStartRawX = event.getRawX();
                dragStartRawY = event.getRawY();
                dragStartX = handleParams.x;
                dragStartY = handleParams.y;
                return true;
            case MotionEvent.ACTION_MOVE:
                handleParams.x = dragStartX + (int) (event.getRawX() - dragStartRawX);
                handleParams.y = dragStartY + (int) (event.getRawY() - dragStartRawY);
                windowManager.updateViewLayout(handleRoot, handleParams);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                // A tap (no meaningful movement) toggles the mode; a drag just repositions.
                float movedX = Math.abs(event.getRawX() - dragStartRawX);
                float movedY = Math.abs(event.getRawY() - dragStartRawY);
                if (movedX < TAP_SLOP_PX && movedY < TAP_SLOP_PX) {
                    setMode(mode == Mode.PICK ? Mode.NAVIGATE : Mode.PICK);
                }
                return true;
            default:
                return false;
        }
    }

    private static final float TAP_SLOP_PX = 16f;
    private float dragStartRawX;
    private float dragStartRawY;
    private int dragStartX;
    private int dragStartY;

    /**
     * PICK-mode selection. See task brief step 2: this fires only while the content window is
     * touchable, always consumes the event, and renews the session on every hit.
     */
    private boolean onContentTouch(View v, MotionEvent event) {
        if (mode != Mode.PICK) return false;
        // Deviation from the brief's snippet, deliberate: every event type is still consumed
        // (returns true below regardless), but only ACTION_DOWN triggers a new hit-test, so a
        // single drag/tap gesture doesn't re-select on MOVE/UP.
        if (event.getActionMasked() != MotionEvent.ACTION_DOWN) return true;

        ProbeNode root = ViewNode.of(activity.getWindow().getDecorView());
        ProbeNode hit = ViewProbe.hit(root, (int) event.getRawX(), (int) event.getRawY());
        if (hit != null) {
            session = session.touched(System.currentTimeMillis());
            show(hit);
        }
        return true; // consume always in PICK
    }

    public void setMode(Mode mode) {
        this.mode = mode;
        if (contentRoot == null) return;
        windowManager.updateViewLayout(contentRoot, params(mode == Mode.PICK ? FLAGS_PICK : FLAGS_NAVIGATE));
    }

    public Mode mode() {
        return mode;
    }

    /** Lets the owner (Task B3) keep the overlay's session in step with the armed pref. */
    public void setSession(InspectorSession session) {
        this.session = session;
    }

    /** Lets the owner (Task B3) read the current session state (for expiry checks). */
    public InspectorSession getSession() {
        return session;
    }

    public void detach() {
        if (contentRoot != null) {
            try {
                windowManager.removeViewImmediate(contentRoot);
            } catch (IllegalArgumentException alreadyGone) {
                // Window already went with the Activity. Not an error.
            }
            contentRoot = null;
        }
        if (handleRoot != null) {
            try {
                windowManager.removeViewImmediate(handleRoot);
            } catch (IllegalArgumentException alreadyGone) {
                // Same as above.
            }
            handleRoot = null;
        }
    }

    private WindowManager.LayoutParams params(int flags) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION,
                flags,
                PixelFormat.TRANSLUCENT);
        // The Activity's own window token is what dispenses with SYSTEM_ALERT_WINDOW.
        lp.token = activity.getWindow().getDecorView().getWindowToken();
        return lp;
    }

    // ---- Selection display ----------------------------------------------------------------

    private void show(ProbeNode node) {
        currentNode = node;
        highlightView.setBounds(node.left(), node.top(), node.right(), node.bottom());
        populatePanel(InspectedView.of(node, activity.getClass().getName()));
        panelView.setVisibility(View.VISIBLE);
    }

    private void populatePanel(InspectedView view) {
        TextView idText = panelView.findViewById(R.id.inspector_id);
        TextView classText = panelView.findViewById(R.id.inspector_class);
        TextView geometryText = panelView.findViewById(R.id.inspector_geometry);
        TextView selectorText = panelView.findViewById(R.id.inspector_selector);
        TextView contextText = panelView.findViewById(R.id.inspector_context_description);
        TextView revealBtn = panelView.findViewById(R.id.inspector_reveal_btn);
        TextView parentChainText = panelView.findViewById(R.id.inspector_parent_chain);

        String entry = view.entryName() != null ? view.entryName() : "(no id)";
        idText.setText(entry + "  " + view.idHex());
        classText.setText(view.className());
        geometryText.setText("stability=" + view.stability()
                + "  targetsAncestor=" + view.targetsAncestor()
                + "  bounds=" + currentNode.left() + "," + currentNode.top()
                + "," + currentNode.right() + "," + currentNode.bottom());
        selectorText.setText(SelectorBuilder.build(view));

        String rawDescription = currentNode.contentDescription();
        String redacted = Redactor.redact(rawDescription);
        contextText.setText("contentDescription: " + redacted);
        boolean wasRedacted = rawDescription != null && !rawDescription.isEmpty()
                && !rawDescription.equals(redacted);
        revealBtn.setVisibility(wasRedacted ? View.VISIBLE : View.GONE);
        revealBtn.setOnClickListener(v -> contextText.setText("contentDescription: " + rawDescription));

        parentChainText.setText("parents: " + String.join(" > ", view.parentChain()));
    }

    private void wirePanelActions() {
        Chip copyId = panelView.findViewById(R.id.inspector_action_copy_id);
        Chip copyClass = panelView.findViewById(R.id.inspector_action_copy_class);
        Chip copySelector = panelView.findViewById(R.id.inspector_action_copy_selector);
        Chip addCss = panelView.findViewById(R.id.inspector_action_add_css);
        Chip parent = panelView.findViewById(R.id.inspector_action_parent);
        Chip child = panelView.findViewById(R.id.inspector_action_child);
        Chip exit = panelView.findViewById(R.id.inspector_action_exit);

        copyId.setOnClickListener(v -> copyFromCurrent("id", InspectedView::entryName));
        copyClass.setOnClickListener(v -> copyFromCurrent("class", InspectedView::className));
        copySelector.setOnClickListener(v -> copyFromCurrent("selector", SelectorBuilder::build));
        addCss.setOnClickListener(v -> copyFromCurrent("CSS rule", SelectorBuilder::ruleBlock));

        parent.setOnClickListener(v -> {
            if (currentNode == null) return;
            ProbeNode up = currentNode.parent();
            if (up != null) show(up);
            else Toast.makeText(activity, "No parent", Toast.LENGTH_SHORT).show();
        });

        child.setOnClickListener(v -> {
            if (currentNode == null) return;
            List<ProbeNode> children = currentNode.children();
            if (children == null || children.isEmpty()) {
                Toast.makeText(activity, "No children", Toast.LENGTH_SHORT).show();
            } else if (children.size() == 1) {
                show(children.get(0));
            } else {
                pickChild(children);
            }
        });

        exit.setOnClickListener(v -> onExit.run());
    }

    private void pickChild(List<ProbeNode> children) {
        CharSequence[] labels = new CharSequence[children.size()];
        for (int i = 0; i < children.size(); i++) {
            ProbeNode c = children.get(i);
            labels[i] = c.entryName() != null ? c.entryName() : c.className();
        }
        new AlertDialog.Builder(activity)
                .setTitle("Choose a child")
                .setItems(labels, (dialog, which) -> show(children.get(which)))
                .show();
    }

    /** Null when nothing has been picked yet — same guard style as the parent/child actions. */
    private InspectedView currentView() {
        if (currentNode == null) return null;
        return InspectedView.of(currentNode, activity.getClass().getName());
    }

    /** Same "no selection yet" guard as the parent/child actions, applied to the four copy actions. */
    private void copyFromCurrent(String what, java.util.function.Function<InspectedView, String> extractor) {
        InspectedView view = currentView();
        if (view == null) {
            Toast.makeText(activity, "Nothing selected", Toast.LENGTH_SHORT).show();
            return;
        }
        InspectorClipboard.copy(activity, "inspector-" + what, extractor.apply(view));
    }

    // ---- Highlight border -------------------------------------------------------------------

    /** Draws a stroked rectangle over the picked view's bounds. Never mutates WhatsApp's tree. */
    private static final class HighlightView extends View {

        private final Paint paint = new Paint();
        private final android.graphics.Rect bounds = new android.graphics.Rect();
        private boolean hasBounds;

        HighlightView(Context context) {
            super(context);
            float density = context.getResources().getDisplayMetrics().density;
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(HIGHLIGHT_STROKE_DP * density);
            paint.setColor(Color.argb(255, 33, 150, 243));
        }

        void setBounds(int left, int top, int right, int bottom) {
            bounds.set(left, top, right, bottom);
            hasBounds = true;
            invalidate();
        }

        @Override
        protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (hasBounds) canvas.drawRect(bounds, paint);
        }
    }
}
