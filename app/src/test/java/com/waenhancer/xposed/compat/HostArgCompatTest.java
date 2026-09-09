package com.waenhancer.xposed.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class HostArgCompatTest {

    @Test
    public void primitiveTypesMatchBoxedRuntimeArguments() {
        Object[] args = {"x", 42, 3L, 1.5f, true};

        assertEquals(1, HostArgCompat.findIndexOfType(args, int.class));
        assertEquals(2, HostArgCompat.findIndexOfType(args, long.class));
        assertEquals(3, HostArgCompat.findIndexOfType(args, float.class));
        assertEquals(4, HostArgCompat.findIndexOfType(args, boolean.class));
    }

    @Test
    public void nullAndMissingArgumentsFailOpen() {
        Object[] args = {null, "value"};

        assertEquals(-1, HostArgCompat.findIndexOfType(args, int.class));
        assertNull(HostArgCompat.numberAt(args, 0));
        assertNull(HostArgCompat.numberAt(args, 5));
        assertFalse(HostArgCompat.isInstance(int.class, null));
    }

    @Test
    public void classArgumentsAlsoHonorPrimitiveBoxing() {
        Object[] args = {int.class, String.class};

        assertEquals(0, HostArgCompat.findIndexOfType(args, Integer.class));
        assertEquals(0, HostArgCompat.findIndexOfType(args, int.class));
        assertEquals(1, HostArgCompat.findIndexOfType(args, String.class));
    }

    @Test
    public void ordinalMinusOneReturnsLastTypedArgument() {
        Object[] args = {"first", 1, "second", "last"};

        assertEquals("first", HostArgCompat.getArg(args, String.class, 0));
        assertEquals("second", HostArgCompat.getArg(args, String.class, 1));
        assertEquals("last", HostArgCompat.getArg(args, String.class, -1));
        assertNull(HostArgCompat.getArg(args, Double.class, 0));
        assertTrue(HostArgCompat.isInstance(int.class, 7));
    }
}
