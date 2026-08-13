package com.waenhancer.xposed.features.devtools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import com.waenhancer.config.PreferenceSchema;

import org.junit.Test;

/**
 * A sessão do inspector é lida de dentro do processo do WhatsApp, então a chave precisa ser
 * PUBLIC. Ela não guarda segredo nenhum — só token e expiração — o que a mantém compatível com
 * o §5.4 do plano-mestre.
 */
public class InspectorPrefContractTest {

    @Test
    public void theSessionKeyIsRegisteredAsAPublicString() {
        PreferenceSchema.Entry entry = PreferenceSchema.entry("inspector_session");
        assertNotNull("inspector_session must be registered in PreferenceSchema", entry);
        assertEquals(PreferenceSchema.Type.STRING, entry.type);
        assertEquals(PreferenceSchema.Store.PUBLIC, entry.store);
        assertEquals(PreferenceSchema.Sensitivity.PUBLIC_SETTING, entry.sensitivity);
    }
}
