package com.waenhancer.xposed.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;

import org.junit.Test;

public class SelfJidPathTest {
    static final class Jid { }
    static final class Other { }

    /** 2.26.38 shape: the manager holds the JID itself. */
    static final class DirectManager { Other a; Jid me; String s; }

    /** 2.26.46 shape: the manager holds a Me record that holds the JID. */
    static final class MeRecord { Object lid; Jid phone; String cc; }
    static final class NestedManager { Runnable r; String s; java.util.List<Object> l; MeRecord me; Other o; }

    static final class TwoJids { Jid a; Jid b; }
    static final class TwoRecords { MeRecord current; MeRecord old; }
    static final class Nothing { Other o; String s; }

    @Test
    public void directFieldIsUsedWhenPresent() throws Exception {
        SelfJidPath path = SelfJidPath.resolve(DirectManager.class, Jid.class);
        assertEquals(1, path.depth());
        DirectManager m = new DirectManager();
        m.me = new Jid();
        assertSame(m.me, path.read(m));
    }

    @Test
    public void jidInsideTheMeRecordIsFollowedAndReadEachTime() throws Exception {
        SelfJidPath path = SelfJidPath.resolve(NestedManager.class, Jid.class);
        assertEquals(2, path.depth());
        assertSame(NestedManager.class, path.owner());
        NestedManager m = new NestedManager();
        assertNull(path.read(m)); // not registered yet
        m.me = new MeRecord();
        m.me.phone = new Jid();
        assertSame(m.me.phone, path.read(m));
        MeRecord replaced = new MeRecord();
        replaced.phone = new Jid();
        m.me = replaced; // WhatsApp replaces the record on re-registration
        assertSame(replaced.phone, path.read(m));
    }

    @Test
    public void ambiguousOrMissingPathsFailClosed() {
        assertNull(SelfJidPath.resolve(TwoJids.class, Jid.class));
        assertNull(SelfJidPath.resolve(TwoRecords.class, Jid.class));
        assertNull(SelfJidPath.resolve(Nothing.class, Jid.class));
        assertNull(SelfJidPath.resolve(null, Jid.class));
    }
}
