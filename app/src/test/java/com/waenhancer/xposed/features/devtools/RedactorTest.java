package com.waenhancer.xposed.features.devtools;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/**
 * O único texto que o inspector mostra é contentDescription, e ele carrega tanto rótulo de botão
 * quanto nome de contato. Cada caso abaixo que passasse cru seria conteúdo privado numa tela que
 * o usuário abriu para ver nomes de recurso.
 */
public class RedactorTest {

    @Test
    public void aButtonLabelPassesThrough() {
        assertEquals("Attach", Redactor.redact("Attach"));
    }

    @Test
    public void aPhoneNumberIsRedacted() {
        assertEquals(Redactor.REDACTED, Redactor.redact("+55 11 91234-5678"));
    }

    @Test
    public void aBarePhoneNumberIsRedacted() {
        assertEquals(Redactor.REDACTED, Redactor.redact("5511912345678"));
    }

    @Test
    public void aUserJidIsRedacted() {
        assertEquals(Redactor.REDACTED, Redactor.redact("5511912345678@s.whatsapp.net"));
    }

    @Test
    public void aGroupJidIsRedacted() {
        assertEquals(Redactor.REDACTED, Redactor.redact("120363000000000000@g.us"));
    }

    /** Descrição longa é frase, e frase no WhatsApp costuma ser mensagem. */
    @Test
    public void aLongDescriptionIsRedacted() {
        String longText = "a".repeat(Redactor.MAX_PLAIN_LENGTH + 1);
        assertEquals(Redactor.REDACTED, Redactor.redact(longText));
    }

    @Test
    public void aDescriptionExactlyAtTheLimitPassesThrough() {
        String atLimit = "a".repeat(Redactor.MAX_PLAIN_LENGTH);
        assertEquals(atLimit, Redactor.redact(atLimit));
    }

    @Test
    public void nullAndEmptyBecomeEmpty() {
        assertEquals("", Redactor.redact(null));
        assertEquals("", Redactor.redact(""));
    }

    /** Um rótulo curto com dígitos não é telefone: "3 unread" tem que passar. */
    @Test
    public void aShortLabelWithADigitPassesThrough() {
        assertEquals("3 unread", Redactor.redact("3 unread"));
    }
}
