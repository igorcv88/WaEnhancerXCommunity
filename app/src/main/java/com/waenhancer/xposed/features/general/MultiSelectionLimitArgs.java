package com.waenhancer.xposed.features.general;

/**
 * Argument rewrite for WhatsApp's {@code MultiSelectionLimitInfo} constructor.
 *
 * <p>Ported from Dev4Mod/WaEnhancer commit efdd903 (GPL-3.0). WhatsApp 2.26.42 moved the
 * forward limit away from the first argument; the first argument can be a string resource
 * ID ({@code 0x7f......}). The limit is therefore the first {@code int} argument whose top
 * byte is zero, i.e. not a packaged resource ID. Boolean arguments are the "blocked" flags
 * (highly-forwarded, groups, multiple channels) and are cleared. Constructors without an
 * {@code int} parameter are left untouched.</p>
 */
public final class MultiSelectionLimitArgs {
    private MultiSelectionLimitArgs() {
    }

    public static boolean hasIntParameter(Class<?>[] types) {
        for (Class<?> type : types) {
            if (type == int.class) return true;
        }
        return false;
    }

    /** @return the rewritten limit index, or -1 when no plausible limit argument exists. */
    public static int apply(Class<?>[] types, Object[] args, int limit) {
        if (types == null || args == null || types.length != args.length) return -1;
        int limitIndex = -1;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == int.class && args[i] instanceof Integer
                    && (((Integer) args[i]) >>> 24) == 0) {
                limitIndex = i;
                break;
            }
        }
        if (limitIndex < 0) return -1;
        args[limitIndex] = limit;
        for (int i = 0; i < types.length; i++) {
            if (types[i] == boolean.class && args[i] instanceof Boolean) args[i] = Boolean.FALSE;
        }
        return limitIndex;
    }
}
