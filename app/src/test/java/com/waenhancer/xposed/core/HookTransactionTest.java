package com.waenhancer.xposed.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class HookTransactionTest {
    @Test
    public void rollbackUndoesInReverseOrder() {
        List<String> undone = new ArrayList<>();
        HookTransaction tx = new HookTransaction();
        tx.onRollback(() -> undone.add("first"));
        tx.onRollback(() -> undone.add("second"));
        tx.onRollback(() -> undone.add("third"));
        tx.rollback();
        assertEquals(List.of("third", "second", "first"), undone);
    }

    @Test
    public void failingUndoDoesNotKeepTheRestInstalled() {
        List<String> undone = new ArrayList<>();
        HookTransaction tx = new HookTransaction();
        tx.onRollback(() -> undone.add("first"));
        tx.onRollback(() -> { throw new IllegalStateException("unhook failed"); });
        tx.onRollback(() -> undone.add("third"));
        tx.rollback();
        assertEquals(List.of("third", "first"), undone);
    }

    @Test
    public void committedTransactionIsNotRolledBack() {
        List<String> undone = new ArrayList<>();
        HookTransaction tx = new HookTransaction();
        tx.onRollback(() -> undone.add("hook"));
        tx.commit();
        tx.rollback();
        assertEquals(List.of(), undone);
        assertThrows(IllegalStateException.class, () -> tx.onRollback(() -> {}));
    }

    @Test
    public void adoptedPartRollsBackWithTheWhole() {
        List<String> undone = new ArrayList<>();
        HookTransaction whole = new HookTransaction();
        whole.onRollback(() -> undone.add("tab-icon"));
        HookTransaction badge = new HookTransaction();
        badge.onRollback(() -> undone.add("badge-1"));
        badge.onRollback(() -> undone.add("badge-2"));
        whole.adopt(badge);
        whole.onRollback(() -> undone.add("tab-list"));
        // A failure after adoption (publishing the tab) removes everything, newest first.
        whole.rollback();
        assertEquals(List.of("tab-list", "badge-2", "badge-1", "tab-icon"), undone);
        assertThrows(IllegalStateException.class, () -> badge.onRollback(() -> {}));
    }

    @Test
    public void optionalPartFailureRemovesOnlyThatPart() {
        // Separate Groups: a badge failure rolls back the badge, the tab keeps its hooks.
        List<String> undone = new ArrayList<>();
        HookTransaction whole = new HookTransaction();
        whole.onRollback(() -> undone.add("tab-icon"));
        HookTransaction badge = new HookTransaction();
        badge.onRollback(() -> undone.add("badge-1"));
        badge.rollback();
        whole.commit();
        assertEquals(List.of("badge-1"), undone);
    }

    @Test
    public void midInstallFailureLeavesNothingInstalled() {
        // Each step installs a hook, the third throws: the two installed ones must be removed.
        List<String> installed = new ArrayList<>();
        HookTransaction tx = new HookTransaction();
        try {
            for (String step : new String[]{"max-items", "icon", "fragment"}) {
                if (step.equals("fragment")) throw new IllegalStateException("fragment resolver failed");
                installed.add(step);
                tx.onRollback(() -> installed.remove(step));
            }
            tx.commit();
        } catch (IllegalStateException failure) {
            tx.rollback();
        }
        assertEquals(List.of(), installed);
    }

    /** Hooks are live once registered; their callbacks act only after the commit. */
    @Test
    public void callbacksActOnlyAfterCommit() {
        HookGate gate = new HookGate();
        assertEquals(false, gate.isOpen());
        gate.open();
        assertEquals(true, gate.isOpen());
    }

    @Test
    public void transactionReportsCommitThroughItsGate() {
        HookTransaction tx = new HookTransaction();
        tx.onRollback(() -> {});
        assertEquals(false, tx.committed());
        tx.commit();
        assertEquals(true, tx.committed());
    }

    @Test
    public void rolledBackTransactionNeverActs() {
        HookTransaction tx = new HookTransaction();
        tx.onRollback(() -> {});
        tx.rollback();
        assertEquals(false, tx.committed());
    }

    /** An adopted part (the badge hooks) acts with the whole, not on its own. */
    @Test
    public void adoptedPartFollowsTheAdoptingCommit() {
        HookTransaction whole = new HookTransaction();
        HookTransaction badge = new HookTransaction();
        badge.onRollback(() -> {});
        whole.adopt(badge);
        assertEquals(false, badge.committed());
        whole.commit();
        assertEquals(true, badge.committed());
    }

    @Test
    public void adoptedPartOfARolledBackWholeNeverActs() {
        HookTransaction whole = new HookTransaction();
        HookTransaction badge = new HookTransaction();
        whole.adopt(badge);
        whole.rollback();
        assertEquals(false, badge.committed());
    }
}
