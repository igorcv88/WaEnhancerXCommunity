package com.waenhancer.theme;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** The loop guard that keeps a pane's recording from containing the pane itself. */
public class GlassPaneGraphTest {

    private final Map<String, String> parents = new HashMap<>();
    private final GlassPaneGraph.Tree<String> tree = parents::get;

    private void child(String parent, String... children) {
        for (String c : children) parents.put(c, parent);
    }

    /** decor > { holder > headerPane, coordinator > listHost > list, footer > composerPane } */
    private void conversation() {
        child("decor", "holder", "coordinator", "footer");
        child("holder", "headerPane");
        child("coordinator", "listHost");
        child("listHost", "list");
        child("footer", "composerPane");
    }

    @Test
    public void siblingSourceIsAllowed() {
        conversation();
        assertNull(GlassPaneGraph.refusal(tree, "composerPane", "listHost", Collections.emptyList()));
        assertNull(GlassPaneGraph.refusal(tree, "headerPane", "coordinator", Collections.emptyList()));
    }

    @Test
    public void ancestorSourceIsRefused() {
        conversation();
        assertNotNull(GlassPaneGraph.refusal(tree, "composerPane", "footer", Collections.emptyList()));
        assertNotNull(GlassPaneGraph.refusal(tree, "composerPane", "decor", Collections.emptyList()));
        assertNotNull(GlassPaneGraph.refusal(tree, "composerPane", "composerPane", Collections.emptyList()));
    }

    @Test
    public void underlayOnlyPaneIsAlwaysSafe() {
        conversation();
        assertNull(GlassPaneGraph.refusal(tree, "headerPane", null, Collections.emptyList()));
    }

    @Test
    public void paneInsideSourceThatRecordsThisPaneIsRefused() {
        conversation();
        child("list", "rowPane");
        List<GlassPaneGraph.Pane<String>> live = new ArrayList<>(Arrays.asList(
                new GlassPaneGraph.Pane<>("composerPane", "listHost"),
                new GlassPaneGraph.Pane<>("rowPane", "footer")));
        assertNotNull(GlassPaneGraph.refusal(tree, "composerPane", "listHost", live));
    }

    @Test
    public void indirectChainBackToThisPaneIsRefused() {
        conversation();
        child("list", "rowPane");
        child("holder", "chipPane");
        // composer records listHost, which holds rowPane; rowPane records holder, which holds
        // chipPane; chipPane records footer, which holds composer: a three-step loop.
        List<GlassPaneGraph.Pane<String>> live = new ArrayList<>(Arrays.asList(
                new GlassPaneGraph.Pane<>("rowPane", "holder"),
                new GlassPaneGraph.Pane<>("chipPane", "footer")));
        assertNotNull(GlassPaneGraph.refusal(tree, "composerPane", "listHost", live));
    }

    @Test
    public void paneInsideSourceThatRecordsElsewhereIsAllowed() {
        conversation();
        child("list", "rowPane");
        List<GlassPaneGraph.Pane<String>> live = new ArrayList<>(Arrays.asList(
                new GlassPaneGraph.Pane<>("rowPane", null),
                new GlassPaneGraph.Pane<>("headerPane", "coordinator")));
        assertNull(GlassPaneGraph.refusal(tree, "composerPane", "listHost", live));
    }
}
