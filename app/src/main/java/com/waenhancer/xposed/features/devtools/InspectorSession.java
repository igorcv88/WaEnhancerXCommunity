package com.waenhancer.xposed.features.devtools;

/**
 * Quando a inspeção está armada, e até quando.
 *
 * <p>Imutável de propósito: o hook de toque e o overlay leem esta sessão de threads diferentes,
 * e uma instância nova por renovação é mais barata de raciocinar do que sincronização.</p>
 */
public final class InspectorSession {

    /** Dez minutos sem nenhuma seleção encerram a sessão sozinha. */
    public static final long IDLE_TIMEOUT_MILLIS = 10 * 60 * 1000L;

    private static final InspectorSession EXPIRED = new InspectorSession(null, Long.MIN_VALUE);

    private final String token;
    private final long expiresAt;

    private InspectorSession(String token, long expiresAt) {
        this.token = token;
        this.expiresAt = expiresAt;
    }

    public static InspectorSession armed(String token, long nowMillis) {
        if (token == null || token.isEmpty()) return EXPIRED;
        return new InspectorSession(token, nowMillis + IDLE_TIMEOUT_MILLIS);
    }

    /** A sessão sem token: nunca ativa, nunca casa. */
    public static InspectorSession expired() {
        return EXPIRED;
    }

    public boolean isActive(long nowMillis) {
        return token != null && nowMillis < expiresAt;
    }

    /**
     * Renova o prazo a partir de agora. Uma sessão já morta continua morta — renovar não
     * ressuscita, porque isso permitiria um toque tardio reabrir a inspeção sem novo armamento.
     */
    public InspectorSession touched(long nowMillis) {
        if (!isActive(nowMillis)) return this;
        return new InspectorSession(token, nowMillis + IDLE_TIMEOUT_MILLIS);
    }

    public boolean matches(String candidate) {
        return token != null && token.equals(candidate);
    }

    public String token() {
        return token;
    }

    public long expiresAt() {
        return expiresAt;
    }
}
