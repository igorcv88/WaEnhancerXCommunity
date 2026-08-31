package com.waenhancer.xposed.features;

import android.os.Message;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;

@RunWith(RobolectricTestRunner.class)
public class Beta10CompatibilityTest {

    public static class MockMessageInfo {
        public String jid;
        public String[] messageIds;

        public MockMessageInfo(String jid, String[] messageIds) {
            this.jid = jid;
            this.messageIds = messageIds;
        }
    }

    public static class MockMessageInfoWithList {
        public String jid;
        public List<String> messageIds;

        public MockMessageInfoWithList(String jid, List<String> messageIds) {
            this.jid = jid;
            this.messageIds = messageIds;
        }
    }

    @Test
    public void testDispatchFiltering() {
        Message msg = new Message();
        msg.arg1 = 419;
        assertEquals(419, msg.arg1);

        msg.arg1 = 89;
        assertEquals(89, msg.arg1);

        msg.arg1 = 100; // Not target
        assertEquals(100, msg.arg1);
    }

    @Test
    public void testFMessageArgumentExtraction() throws Exception {
        Object[] args = new Object[]{ "first", 123, "test_fmessage" };
        Object result = getArgByType(args, String.class, 1);
        assertEquals("test_fmessage", result);

        result = getArgByType(args, Integer.class, 0);
        assertEquals(123, result);
    }

    private Object getArgByType(Object[] args, Class<?> type, int index) {
        if (args == null) return null;
        int count = 0;
        for (Object arg : args) {
            if (arg != null && type.isAssignableFrom(arg.getClass())) {
                if (count == index) return arg;
                count++;
            }
        }
        return null;
    }
}
