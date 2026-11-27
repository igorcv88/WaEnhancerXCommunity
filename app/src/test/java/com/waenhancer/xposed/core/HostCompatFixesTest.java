package com.waenhancer.xposed.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.waenhancer.xposed.core.devkit.HostDexContracts;
import com.waenhancer.xposed.features.customization.SeparateGroupLimits;

import org.junit.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/** Regressions found on 2.26.46.82: inherited callbacks, moved classes, prop getters. */
public class HostCompatFixesTest {
    // Shape of 2.26.46.82: HomeActivity declares only onCreate/onResume; HomeActivityKt the rest.
    static class Framework { void onPrepareOptionsMenu(Object menu) {} void onCreate(Object state) {} }
    abstract static class HomeActivityKt extends Framework {
        @Override void onPrepareOptionsMenu(Object menu) {}
        abstract void onMenuOpened();
    }
    static class HomeActivity extends HomeActivityKt {
        @Override void onCreate(Object state) {}
        @Override void onMenuOpened() {}
    }
    static class OtherActivity extends HomeActivityKt { @Override void onMenuOpened() {} }

    @Test
    public void inheritedCallbackIsFoundInTheParentThatDeclaresIt() throws Exception {
        Method prepare = HostHooks.nearestDeclared(HomeActivity.class, "onPrepareOptionsMenu", Object.class);
        assertSame(HomeActivityKt.class, prepare.getDeclaringClass());
        assertSame(HomeActivity.class,
                HostHooks.nearestDeclared(HomeActivity.class, "onCreate", Object.class).getDeclaringClass());
    }

    @Test
    public void abstractDeclarationsAreSkipped() throws Exception {
        Method opened = HostHooks.nearestDeclared(HomeActivity.class, "onMenuOpened");
        assertSame(HomeActivity.class, opened.getDeclaringClass());
    }

    @Test
    public void missingCallbackIsAnError() {
        assertThrows(NoSuchMethodException.class, () -> HostHooks.nearestDeclared(HomeActivity.class, "onRestart"));
    }

    @Test
    public void inheritedHookRunsOnlyForTheRequestedActivity() throws Exception {
        Method prepare = HostHooks.nearestDeclared(HomeActivity.class, "onPrepareOptionsMenu", Object.class);
        assertTrue(HostHooks.needsScope(HomeActivity.class, prepare));
        assertTrue(HostHooks.appliesTo(HomeActivity.class, new HomeActivity()));
        // A sibling sharing HomeActivityKt reaches the same hooked method but is not Home.
        assertFalse(HostHooks.appliesTo(HomeActivity.class, new OtherActivity()));
        // Declared by the class itself: the callback is used as is, no wrapper.
        Method create = HostHooks.nearestDeclared(HomeActivity.class, "onCreate", Object.class);
        assertFalse(HostHooks.needsScope(HomeActivity.class, create));
    }

    @Test
    public void movedClassUsesTheNameTheHostDefines() {
        List<String> defined = List.of("com.whatsapp.settings.ui.help.About");
        Class<?> found = HostClassNames.first(HostClassNames.ABOUT,
                name -> defined.contains(name) ? String.class : null);
        assertSame(String.class, found);
        List<String> asked = new ArrayList<>();
        assertNull(HostClassNames.first(HostClassNames.SETTINGS_TAB, name -> { asked.add(name); return null; }));
        assertEquals(HostClassNames.SETTINGS_TAB, asked);
        assertTrue(HostClassNames.ABOUT.contains("com.whatsapp.settings.ui.About"));
        assertTrue(HostClassNames.SETTINGS_TAB.contains("com.whatsapp.settings.ui.main.SettingsTabActivity"));
    }

    @Test
    public void failingGetterDoesNotBlockTheOther() throws Exception {
        AtomicInteger booleanInstalls = new AtomicInteger();
        HostProps.Slot booleans = new HostProps.Slot("boolean", loader -> booleanInstalls.incrementAndGet(), line -> {});
        HostProps.Slot integers = new HostProps.Slot("integer", loader -> { throw new NoSuchMethodException("Unknown IntField"); }, line -> {});
        assertThrows(IllegalStateException.class, () -> integers.require(null));
        booleans.require(null);
        booleans.require(null);
        assertTrue(booleans.active());
        assertFalse(integers.active());
        assertEquals(1, booleanInstalls.get());
        // A remembered failure is reported again without re-running the scan.
        assertThrows(IllegalStateException.class, () -> integers.require(null));
    }

    @Test
    public void dataUsageExemptionKeepsTheStockValueWhenTheScreenIsUnknown() {
        assertTrue(HostProps.overrideApplies(19148, null, false));
        assertFalse(HostProps.overrideApplies(HostProps.DATA_USAGE_EXEMPT, null, false));
        assertFalse(HostProps.overrideApplies(HostProps.DATA_USAGE_EXEMPT, String.class, true));
        assertTrue(HostProps.overrideApplies(HostProps.DATA_USAGE_EXEMPT, String.class, false));
    }

    @Test
    public void propertyKeyIndexRule() {
        assertEquals(1, HostDexContracts.keyParameterIndex(List.of("X.Obj", "int")));
        assertEquals(-1, HostDexContracts.keyParameterIndex(List.of("int", "int")));
        assertEquals(0, HostDexContracts.keyParameterIndex(List.of("java.lang.Integer", "X.Obj")));
        assertEquals(1, HostDexContracts.keyParameterIndex(List.of("java.lang.Integer", "int")));
        assertEquals(-1, HostDexContracts.keyParameterIndex(List.of("long")));
    }

    @Test
    public void navigationLimitMakesRoomButKeepsALargerNativeLimit() {
        assertEquals(6, SeparateGroupLimits.atLeastNavigationItems(5));
        assertEquals(7, SeparateGroupLimits.atLeastNavigationItems(7));
        assertEquals(6, SeparateGroupLimits.atLeastNavigationItems(null));
    }
}
