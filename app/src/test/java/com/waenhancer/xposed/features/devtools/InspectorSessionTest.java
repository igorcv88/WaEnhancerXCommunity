package com.waenhancer.xposed.features.devtools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * A sessão de inspeção é a única coisa que separa "o módulo tem um hook de toque disponível" de
 * "o módulo está lendo toques do WhatsApp agora". Cada caso abaixo é uma forma de ela ficar
 * ligada quando deveria estar desligada.
 */
public class InspectorSessionTest {

    private static final long T0 = 1_000_000L;

    @Test
    public void anArmedSessionIsActive() {
        InspectorSession session = InspectorSession.armed("abc", T0);
        assertTrue(session.isActive(T0));
    }

    @Test
    public void aSessionIsStillActiveOneMillisecondBeforeTheTimeout() {
        InspectorSession session = InspectorSession.armed("abc", T0);
        assertTrue(session.isActive(T0 + InspectorSession.IDLE_TIMEOUT_MILLIS - 1));
    }

    @Test
    public void aSessionIsDeadExactlyAtTheTimeout() {
        InspectorSession session = InspectorSession.armed("abc", T0);
        assertFalse(session.isActive(T0 + InspectorSession.IDLE_TIMEOUT_MILLIS));
    }

    /** Cada seleção renova os 10 minutos: o timeout é de inatividade, não um prazo duro. */
    @Test
    public void touchingExtendsTheDeadline() {
        InspectorSession session = InspectorSession.armed("abc", T0).touched(T0 + 60_000L);
        assertTrue(session.isActive(T0 + InspectorSession.IDLE_TIMEOUT_MILLIS + 1));
    }

    /** Renovar uma sessão já morta não a ressuscita — só um novo armamento faz isso. */
    @Test
    public void touchingAnExpiredSessionDoesNotReviveIt() {
        long afterDeath = T0 + InspectorSession.IDLE_TIMEOUT_MILLIS + 1;
        InspectorSession session = InspectorSession.armed("abc", T0).touched(afterDeath);
        assertFalse(session.isActive(afterDeath));
    }

    @Test
    public void theTokenMustMatch() {
        InspectorSession session = InspectorSession.armed("abc", T0);
        assertTrue(session.matches("abc"));
        assertFalse(session.matches("abd"));
        assertFalse(session.matches(null));
    }

    @Test
    public void theExpiredSessionIsNeverActiveAndMatchesNothing() {
        assertFalse(InspectorSession.expired().isActive(T0));
        assertFalse(InspectorSession.expired().matches("abc"));
    }

    @Test
    public void theIdleTimeoutIsTenMinutes() {
        assertEquals(10 * 60 * 1000L, InspectorSession.IDLE_TIMEOUT_MILLIS);
    }
}
