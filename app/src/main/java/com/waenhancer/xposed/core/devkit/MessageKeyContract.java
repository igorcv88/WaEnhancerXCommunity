package com.waenhancer.xposed.core.devkit;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/**
 * Acceptance rules for the field that holds a message's primary key.
 *
 * <p>The message key identifies rows in WhatsApp's store and in the module's own records
 * (Media Library, anti-revoke archive, receipts). A wrong field silently associates data with
 * another message, so a candidate is accepted only on positive evidence and never by its
 * position among the class's fields.</p>
 */
public final class MessageKeyContract {
    private MessageKeyContract() {}

    /** The evidence-based field must be an instance field declared in the message hierarchy. */
    public static Field requireIdentityField(Field field, Class<?> messageClass) throws NoSuchFieldException {
        if (field == null || messageClass == null) throw new NoSuchFieldException("No message key evidence");
        if (Modifier.isStatic(field.getModifiers())
                || !field.getDeclaringClass().isAssignableFrom(messageClass)) {
            throw new NoSuchFieldException("Message key is not an instance field of the message: " + field);
        }
        return field;
    }

    /**
     * Fallback for hosts without the sender-read evidence: exactly one final instance field of the
     * key type. Builds that keep a second, mutable key field (the original-key slot) still
     * resolve; two final candidates or none fail closed.
     */
    public static Field selectFinalField(List<Field> candidates, Class<?> messageClass)
            throws NoSuchFieldException {
        List<Field> finals = new ArrayList<>();
        if (candidates != null) {
            for (Field field : candidates) {
                int modifiers = field.getModifiers();
                if (Modifier.isFinal(modifiers) && !Modifier.isStatic(modifiers)) finals.add(field);
            }
        }
        if (finals.size() != 1) {
            throw new NoSuchFieldException("Primary message key needs one final field, found " + finals.size());
        }
        return requireIdentityField(finals.get(0), messageClass);
    }
}
