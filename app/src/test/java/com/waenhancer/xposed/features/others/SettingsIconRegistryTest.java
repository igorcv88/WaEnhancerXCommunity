package com.waenhancer.xposed.features.others;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

public class SettingsIconRegistryTest {

    @Test
    public void everyDeclaredMappingHasAnIconName() {
        SettingsIconRegistry.mappings().forEach((screen, icon) -> {
            assertFalse(screen.trim().isEmpty());
            assertFalse(icon.trim().isEmpty());
        });
    }

    @Test
    public void moduleEntryUsesDistinctTuneIcon() {
        assertEquals("ic_waenhancer_entry",
                SettingsIconRegistry.iconName("waenhancer", null));
    }

    @Test
    public void unknownScreenGetsVisibleFallback() {
        assertEquals("ic_general",
                SettingsIconRegistry.iconName("unknown-screen", null));
    }
}
