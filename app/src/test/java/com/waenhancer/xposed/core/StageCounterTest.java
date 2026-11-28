package com.waenhancer.xposed.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class StageCounterTest {
    @Test
    public void firstHitIsLoggedAtOnceAndTotalsAreRateLimited() {
        AtomicLong now = new AtomicLong(1_000);
        List<String> log = new ArrayList<>();
        StageCounter counter = new StageCounter("Rows", 60_000, now::get, log::add);
        for (int i = 0; i < 500; i++) counter.hit("row_dispatched");
        counter.hit("lookup_failed");
        assertEquals(2, log.size()); // one "reached" line per stage, no summary yet
        assertTrue(log.get(0).contains("row_dispatched reached"));
        now.addAndGet(60_000);
        counter.hit("row_dispatched");
        assertEquals(3, log.size());
        assertEquals("[WAEX] Rows stages {lookup_failed=1, row_dispatched=501}", log.get(2));
        assertEquals(501, counter.count("row_dispatched"));
    }

    @Test
    public void unchangedTotalsAreNotLoggedAgain() {
        AtomicLong now = new AtomicLong(0);
        List<String> log = new ArrayList<>();
        StageCounter counter = new StageCounter("Tab", 10, now::get, log::add);
        counter.hit("chips_empty");
        now.addAndGet(100);
        counter.summarize(true);
        int lines = log.size();
        now.addAndGet(100);
        counter.summarize(true);
        assertEquals(lines, log.size());
    }
}
