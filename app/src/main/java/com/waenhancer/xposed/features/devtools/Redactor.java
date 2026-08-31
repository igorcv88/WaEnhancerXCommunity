package com.waenhancer.xposed.features.devtools;

import java.util.regex.Pattern;

/**
 * Redige o único campo de texto que o inspector exibe.
 *
 * <p>A regra desta classe é assimétrica de propósito: um rótulo legítimo redigido por engano
 * custa uma inconveniência; um nome de contato exibido por engano é o que o §11.3 do plano
 * proíbe. Na dúvida, redige.</p>
 */
public final class Redactor {

    public static final String REDACTED = "‹redigido›";

    /** Acima disto é frase, e frase no WhatsApp costuma ser mensagem. */
    public static final int MAX_PLAIN_LENGTH = 40;

    /** Sete ou mais dígitos, ignorando separadores, é telefone. */
    private static final Pattern PHONE = Pattern.compile(".*(\\d[\\s\\-()+]*){7,}.*");

    private static final Pattern JID = Pattern.compile(".*@(s\\.whatsapp\\.net|g\\.us|c\\.us|broadcast).*");

    private Redactor() {
    }

    public static String redact(String raw) {
        if (raw == null || raw.isEmpty()) return "";
        if (raw.length() > MAX_PLAIN_LENGTH) return REDACTED;
        if (JID.matcher(raw).matches()) return REDACTED;
        if (PHONE.matcher(raw).matches()) return REDACTED;
        return raw;
    }
}
