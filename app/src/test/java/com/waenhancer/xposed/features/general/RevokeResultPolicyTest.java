package com.waenhancer.xposed.features.general;

import org.junit.Test;
import static org.junit.Assert.*;

public class RevokeResultPolicyTest {
    interface Ack {}
    static final class None implements Ack { static final None INSTANCE = new None(); }
    static final class Result {
        final Ack ack;
        final int count;
        Result(int count, Ack ack) { this.count = count; this.ack = ack; }
    }
    static final class Failing { Failing(Ack ack) { throw new IllegalStateException(); } }
    enum Unknown { FIRST, SECOND }
    @Test public void scalarsAreCompatibleWithDeclaredType() {
        assertEquals(Boolean.TRUE, RevokeResultPolicy.blockedMessageRevokeResult(boolean.class, t -> null));
        assertEquals(0, RevokeResultPolicy.blockedMessageRevokeResult(int.class, t -> null));
        assertEquals(0L, RevokeResultPolicy.blockedMessageRevokeResult(long.class, t -> null));
        assertNull(RevokeResultPolicy.blockedMessageRevokeResult(void.class, t -> null));
    }
    @Test public void knownObjectResultUsesAssignableNoneAck() {
        Object value = RevokeResultPolicy.blockedMessageRevokeResult(Result.class, t -> None.INSTANCE);
        assertTrue(value instanceof Result);
        assertSame(None.INSTANCE, ((Result)value).ack);
        assertEquals(0, ((Result)value).count);
    }
    @Test public void unknownObjectAndEnumDelegateToSqlInsteadOfInventingAResult() {
        for (Class<?> type : new Class<?>[]{Object.class, String.class, Unknown.class, char.class})
            assertSame(RevokeResultPolicy.UNSUPPORTED, RevokeResultPolicy.blockedMessageRevokeResult(type, t -> null));
    }
    @Test public void missingOrWrongAckAndThrowingConstructorCannotCrash() {
        assertSame(RevokeResultPolicy.UNSUPPORTED, RevokeResultPolicy.blockedMessageRevokeResult(Result.class, t -> null));
        assertSame(RevokeResultPolicy.UNSUPPORTED, RevokeResultPolicy.blockedMessageRevokeResult(Result.class, t -> new Object()));
        assertSame(RevokeResultPolicy.UNSUPPORTED, RevokeResultPolicy.blockedMessageRevokeResult(Failing.class, t -> None.INSTANCE));
    }
}
