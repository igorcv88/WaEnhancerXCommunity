package com.waenhancer.xposed.core.devkit;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class MessageKeyContractTest {
    static final class Key {}

    /** Both supported hosts: a mutable original-key slot declared before the final primary key. */
    static class Message {
        Key A0I;
        final Key A0i = new Key();
    }

    static final class SubMessage extends Message {}

    static final class TwoFinal {
        final Key a = new Key();
        final Key b = new Key();
    }

    static final class Unrelated {
        final Key key = new Key();
    }

    static final class WithStatic {
        static final Key SHARED = new Key();
    }

    private static List<Field> fields(Class<?> type, String... names) throws Exception {
        Field[] out = new Field[names.length];
        for (int i = 0; i < names.length; i++) out[i] = type.getDeclaredField(names[i]);
        return Arrays.asList(out);
    }

    @Test
    public void finalFieldWinsRegardlessOfDeclarationOrder() throws Exception {
        Field expected = Message.class.getDeclaredField("A0i");
        assertEquals(expected, MessageKeyContract.selectFinalField(fields(Message.class, "A0I", "A0i"), Message.class));
        assertEquals(expected, MessageKeyContract.selectFinalField(fields(Message.class, "A0i", "A0I"), Message.class));
    }

    @Test
    public void inheritedKeyOfSubclassIsAccepted() throws Exception {
        Field expected = Message.class.getDeclaredField("A0i");
        assertEquals(expected, MessageKeyContract.requireIdentityField(expected, SubMessage.class));
    }

    @Test
    public void ambiguousFinalCandidatesFailClosed() {
        assertThrows(NoSuchFieldException.class, () ->
                MessageKeyContract.selectFinalField(fields(TwoFinal.class, "a", "b"), TwoFinal.class));
    }

    @Test
    public void noCandidateFailsClosed() {
        assertThrows(NoSuchFieldException.class, () ->
                MessageKeyContract.selectFinalField(Collections.emptyList(), Message.class));
        assertThrows(NoSuchFieldException.class, () ->
                MessageKeyContract.selectFinalField(fields(Message.class, "A0I"), Message.class));
    }

    @Test
    public void fieldOutsideMessageHierarchyIsRejected() throws Exception {
        Field foreign = Unrelated.class.getDeclaredField("key");
        assertThrows(NoSuchFieldException.class, () -> MessageKeyContract.requireIdentityField(foreign, Message.class));
    }

    @Test
    public void staticFieldIsRejected() throws Exception {
        Field shared = WithStatic.class.getDeclaredField("SHARED");
        assertThrows(NoSuchFieldException.class, () -> MessageKeyContract.requireIdentityField(shared, WithStatic.class));
        assertThrows(NoSuchFieldException.class, () ->
                MessageKeyContract.selectFinalField(Collections.singletonList(shared), WithStatic.class));
    }
}
