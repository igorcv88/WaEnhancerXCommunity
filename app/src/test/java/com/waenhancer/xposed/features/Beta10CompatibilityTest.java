package com.waenhancer.xposed.features;

import com.waenhancer.xposed.utils.ReflectionUtils;

import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

public class Beta10CompatibilityTest {

    @Test
    public void getArgFindsTypedArgumentAtNonZeroPosition() {
        Object[] args = new Object[]{"prefix", 123, "target"};

        assertEquals("target", ReflectionUtils.getArg(args, String.class, 1));
        assertEquals(Integer.valueOf(123), ReflectionUtils.getArg(args, Integer.class, 0));
    }

    @Test
    public void getArgUsesOrdinalAmongMatchingArguments() {
        Object[] args = new Object[]{"first", 7, "second", "third"};

        assertEquals("first", ReflectionUtils.getArg(args, String.class, 0));
        assertEquals("second", ReflectionUtils.getArg(args, String.class, 1));
        assertEquals("third", ReflectionUtils.getArg(args, String.class, 2));
    }

    @Test
    public void getArgReturnsNullWhenTypeOrOrdinalIsMissing() {
        Object[] args = new Object[]{"only", 7};

        assertNull(ReflectionUtils.getArg(args, String.class, 1));
        assertNull(ReflectionUtils.getArg(args, Long.class, 0));
        assertNull(ReflectionUtils.getArg(null, String.class, 0));
    }
}
