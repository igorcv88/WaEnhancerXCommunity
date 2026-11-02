package com.waenhancer.xposed.features.privacy;

import java.util.HashSet;
import java.util.Set;

/**
 * Null-safe identity matching across WhatsApp's LID and phone-number (PN) namespaces.
 *
 * <p>Adapted from Dev4Mod/WaEnhancer commit 1a06ff0 (GPL-3.0). A contact is matched by its
 * phone JID, its LID or its bare number; absent identities never match each other. Matching by
 * phone number alone treated every LID-only contact (null number) as equal to every LID-only
 * locked chat.</p>
 */
public final class JidIdentitySet {
    private final Set<String> ids = new HashSet<>();

    public void add(String... identities) {
        if (identities == null) return;
        for (String id : identities) {
            if (id != null && !id.isEmpty()) ids.add(id);
        }
    }

    public boolean matchesAny(String... identities) {
        if (identities == null) return false;
        for (String id : identities) {
            if (id != null && !id.isEmpty() && ids.contains(id)) return true;
        }
        return false;
    }

    public boolean isEmpty() {
        return ids.isEmpty();
    }
}
