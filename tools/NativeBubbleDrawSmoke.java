import com.waenhancer.theme.NativeBubbleDrawLedger;

/** JVM-only smoke check; no Android SDK or external test runner required. */
public final class NativeBubbleDrawSmoke {
    public static void main(String[] args) {
        NativeBubbleDrawLedger<Object> drawn = new NativeBubbleDrawLedger<>();
        Object bubble = new Object(), quote = new Object(), replaced = new Object();
        if (drawn.saw(bubble)) throw new AssertionError("an empty frame retained ownership");
        drawn.mark(bubble);
        drawn.mark(quote);
        drawn.mark(bubble);
        if (!drawn.saw(bubble) || !drawn.saw(quote) || drawn.saw(replaced)) {
            throw new AssertionError("draw identity tracking lost the actual factory");
        }
        NativeBubbleDrawLedger<String> identicalValues = new NativeBubbleDrawLedger<>();
        String next = new String("factory"), old = new String("factory");
        identicalValues.mark(next);
        if (identicalValues.saw(old)) throw new AssertionError("equal text is not equal drawable identity");
        System.out.println("PASS NativeBubbleDrawSmoke: empty/replaced/multiple/identity");
    }
}
