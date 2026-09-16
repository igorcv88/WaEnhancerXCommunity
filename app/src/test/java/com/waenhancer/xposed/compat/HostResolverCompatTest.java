package com.waenhancer.xposed.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import org.junit.Test;

import java.lang.reflect.Field;

public class HostResolverCompatTest {

    private interface DelegateContract { }

    private static class JidBase { }

    private static final class UserJid extends JidBase { }

    private static final class DelegateImpl implements DelegateContract {
        @SuppressWarnings("unused")
        private UserJid jid;
    }

    private static class BaseOwner {
        @SuppressWarnings("unused")
        private DelegateContract delegate;
    }

    private static final class Owner extends BaseOwner {
        @SuppressWarnings("unused")
        private static DelegateImpl staticDelegate;

        @SuppressWarnings("unused")
        private Object unrelatedObject;
    }

    @Test
    public void implementationMatchesInterfaceTypedStorageField() {
        Field field = HostResolverCompat.findAssignableStorageField(
                Owner.class, DelegateImpl.class);

        assertNotNull(field);
        assertEquals("delegate", field.getName());
        assertEquals(DelegateContract.class, field.getType());
    }

    @Test
    public void delegateJidLookupFindsAssignableInstanceField() {
        Field field = HostResolverCompat.findAssignableInstanceField(
                DelegateImpl.class, JidBase.class);

        assertNotNull(field);
        assertEquals("jid", field.getName());
        assertEquals(UserJid.class, field.getType());
    }
}
