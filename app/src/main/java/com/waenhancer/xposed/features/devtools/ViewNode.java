package com.waenhancer.xposed.features.devtools;

import android.content.res.Resources;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Converte uma View em ProbeNode. Deliberadamente burro: toda decisão vive no ViewProbe, que é
 * testável. A única regra aqui é usar getGlobalVisibleRect, e não getHitRect, para que uma view
 * recortada pelo scroll não seja acertada.
 */
public final class ViewNode implements ProbeNode {

    private final View view;
    private final Rect visible = new Rect();
    private final boolean onScreen;

    private ViewNode(View view) {
        this.view = view;
        this.onScreen = view.getGlobalVisibleRect(visible);
    }

    public static ProbeNode of(View view) {
        return view == null ? null : new ViewNode(view);
    }

    @Override public int left() { return onScreen ? visible.left : 0; }

    @Override public int top() { return onScreen ? visible.top : 0; }

    @Override public int right() { return onScreen ? visible.right : 0; }

    @Override public int bottom() { return onScreen ? visible.bottom : 0; }

    @Override public boolean visible() {
        return onScreen && view.getVisibility() == View.VISIBLE;
    }

    @Override public float alpha() { return view.getAlpha(); }

    @Override public List<ProbeNode> children() {
        if (!(view instanceof ViewGroup)) return Collections.emptyList();
        ViewGroup group = (ViewGroup) view;
        List<ProbeNode> kids = new ArrayList<>(group.getChildCount());
        for (int i = 0; i < group.getChildCount(); i++) {
            kids.add(new ViewNode(group.getChildAt(i)));
        }
        return kids;
    }

    @Override public ProbeNode parent() {
        return view.getParent() instanceof View ? new ViewNode((View) view.getParent()) : null;
    }

    @Override public String entryName() {
        int id = view.getId();
        if (id == View.NO_ID) return null;
        try {
            return view.getResources().getResourceEntryName(id);
        } catch (Resources.NotFoundException e) {
            return null;
        }
    }

    @Override public String resourcePackage() {
        int id = view.getId();
        if (id == View.NO_ID) return null;
        try {
            return view.getResources().getResourcePackageName(id);
        } catch (Resources.NotFoundException e) {
            return null;
        }
    }

    @Override public int id() { return view.getId(); }

    @Override public String className() { return view.getClass().getName(); }

    @Override public String contentDescription() {
        CharSequence description = view.getContentDescription();
        return description == null ? null : description.toString();
    }

    /** A View original, para o overlay desenhar a borda de destaque sobre ela. */
    public View view() { return view; }
}
