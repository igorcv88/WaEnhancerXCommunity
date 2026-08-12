# Theme Editor Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Uma tela que deixa o usuário escolher o preset global de cor e sobrescrever tokens individuais por cima dele, com preview e aviso de contraste — implementando a regra de precedência do §11.5 que hoje não existe.

**Architecture:** A engine de cor **já está pronta** (`SemanticTheme`). Esta fase acrescenta uma camada de override: uma resolução pura e testável (`ThemeResolution`), uma serialização defensiva (`ThemeOverrideStore`), um consultor de contraste (`ContrastAdvisor`), e uma Activity dedicada no molde de `BottomBarCustomizationActivity`.

**Tech Stack:** Java 17, Android, `org.json` (já no classpath de teste), JUnit 4 (sem Robolectric, sem Mockito).

**Spec:** `HANDOFF_F3_THEME_EDITOR.md`

## Global Constraints

- **Invariante de compatibilidade:** com overrides vazios, a resolução devolve **exatamente** `SemanticTheme.fromPreset()`. Quem nunca abrir o editor não vê diferença nenhuma. Isto é testado, não presumido.
- **Uma pref, não vinte:** `wae_theme_overrides`, `Type.STRING`, `Sensitivity.PUBLIC_SETTING`, `Store.PUBLIC`.
- **Overrides separados por modo:** `{"light":{…},"dark":{…}}`.
- **Leitura defensiva:** JSON malformado, cor inválida ou token desconhecido resultam em **nenhum override**, nunca em exceção. Isto roda dentro do processo do WhatsApp.
- **Contraste:** avisar, nunca corrigir em silêncio.
- **Limite de tamanho** na desserialização, como o §6.3 do plano-mestre exige para importação.
- **Não reimplementar aritmética de cor.** `SemanticTheme` já tem `contrastRatio`, `ensureTextContrast`, `ensureControlContrast`, `blend`, `bestTextColor`.
- **Build/teste:** `./gradlew :app:compileWhatsappDebugJavaWithJavac :app:testWhatsappDebugUnitTest`

---

# PARTE A — Opus 5

**Por que Opus:** esta parte é armazenamento e preferências, que o §1A.2 atribui explicitamente ao Opus. A pref é lida **de dentro do processo do WhatsApp**: um parser que lance aqui não é um erro de tela, é o WhatsApp abrindo errado. E o invariante de compatibilidade — "sem overrides, nada muda" — é a diferença entre uma fase aditiva e uma que exige migração de preferências sob o §5.3. Toda a lógica desta parte é pura e testável.

**Entrega da Parte A:** três classes puras testadas, a pref registrada, e **nenhuma UI**. O app compila e se comporta exatamente como antes.

---

### Task A1: `ThemeResolution` — a precedência do §11.5

**Files:**
- Create: `app/src/main/java/com/waenhancer/theme/ThemeResolution.java`
- Test: `app/src/test/java/com/waenhancer/theme/ThemeResolutionTest.java`

**Interfaces:**
- Consumes: `SemanticTheme.fromPreset(String, boolean)`, `SemanticTheme.Tokens`.
- Produces: `ThemeResolution.resolve(String presetName, boolean dark, Map<String,Integer> overrides)` → `SemanticTheme.Tokens`; `ThemeResolution.CURATED` (`List<String>`); `ThemeResolution.ALL_TOKENS` (`List<String>`).

**Antes de implementar:** abrir `app/src/main/java/com/waenhancer/theme/SemanticTheme.java` e confirmar a API real de `Tokens` (`get(String)`, `asMap()`) e de `fromPreset`. Usar os nomes reais.

- [ ] **Step 1: Write the failing test**

```java
package com.waenhancer.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * A precedência do §11.5: personalização do usuário vence preset, que vence a cor original.
 *
 * <p>O primeiro teste é o mais importante do arquivo: ele é o que torna esta fase aditiva. Se
 * ele falhar, todo usuário que nunca abriu o editor tem o tema alterado por uma atualização,
 * e isso vira uma migração de preferências sob o §5.3 em vez de uma feature nova.</p>
 */
public class ThemeResolutionTest {

    private static final int MAGENTA = 0xFFFF00FF;

    /** O invariante de compatibilidade. */
    @Test
    public void withNoOverridesResolutionEqualsThePresetExactly() {
        for (String preset : SemanticTheme.presets().keySet()) {
            for (boolean dark : new boolean[] { false, true }) {
                assertEquals("preset " + preset + " dark=" + dark,
                        SemanticTheme.fromPreset(preset, dark).asMap(),
                        ThemeResolution.resolve(preset, dark, Collections.emptyMap()).asMap());
            }
        }
    }

    @Test
    public void anEmptyMapAndANullMapBehaveTheSame() {
        assertEquals(ThemeResolution.resolve("Blue", false, Collections.emptyMap()).asMap(),
                ThemeResolution.resolve("Blue", false, null).asMap());
    }

    @Test
    public void anOverrideBeatsThePreset() {
        Map<String, Integer> overrides = new HashMap<>();
        overrides.put("outgoingBubble", MAGENTA);
        SemanticTheme.Tokens resolved = ThemeResolution.resolve("Blue", false, overrides);
        assertEquals(MAGENTA, resolved.get("outgoingBubble"));
    }

    /** Sobrescrever um token não pode alterar os outros: nada é rederivado. */
    @Test
    public void anOverrideDoesNotDisturbTheOtherTokens() {
        Map<String, Integer> overrides = new HashMap<>();
        overrides.put("outgoingBubble", MAGENTA);
        SemanticTheme.Tokens base = ThemeResolution.resolve("Blue", false, Collections.emptyMap());
        SemanticTheme.Tokens resolved = ThemeResolution.resolve("Blue", false, overrides);
        for (String token : ThemeResolution.ALL_TOKENS) {
            if (token.equals("outgoingBubble")) continue;
            assertEquals("token " + token, base.get(token), resolved.get(token));
        }
    }

    /** Chave desconhecida é ignorada, como o §6.3 manda fazer na importação. */
    @Test
    public void anUnknownTokenIsIgnored() {
        Map<String, Integer> overrides = new HashMap<>();
        overrides.put("notAToken", MAGENTA);
        assertEquals(ThemeResolution.resolve("Blue", false, Collections.emptyMap()).asMap(),
                ThemeResolution.resolve("Blue", false, overrides).asMap());
    }

    @Test
    public void anUnknownPresetFallsBackWithoutThrowing() {
        assertNotEquals(0, ThemeResolution.resolve("NoSuchPreset", false, Collections.emptyMap())
                .get("primary"));
    }

    @Test
    public void theCuratedListIsASubsetOfAllTokens() {
        assertTrue(ThemeResolution.ALL_TOKENS.containsAll(ThemeResolution.CURATED));
    }

    /** Os seis do §2 da spec, e nessa ordem. */
    @Test
    public void theCuratedListIsTheSixMeaningfulTokens() {
        assertEquals(java.util.Arrays.asList(
                "primary", "outgoingBubble", "incomingBubble", "fab", "unreadBadge", "link"),
                ThemeResolution.CURATED);
    }

    /** Os 20 do §11.5. */
    @Test
    public void allTokensCoversWhatTheEngineProduces() {
        assertEquals(SemanticTheme.fromPreset("Blue", false).asMap().keySet(),
                new java.util.HashSet<>(ThemeResolution.ALL_TOKENS));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testWhatsappDebugUnitTest --tests "*ThemeResolutionTest"`
Expected: FAIL — `ThemeResolution` não existe.

- [ ] **Step 3: Write minimal implementation**

```java
package com.waenhancer.theme;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * A precedência de tema do §11.5: override do usuário vence preset global.
 *
 * <p>Não deriva nada. Um override é, por definição, a instrução de não derivar — rederivar os
 * tokens vizinhos a partir de uma cor escolhida à mão desfaria a escolha do usuário no token
 * seguinte.</p>
 */
public final class ThemeResolution {

    /** O que a tela mostra sem pedir "Avançado": os tokens com significado óbvio. */
    public static final List<String> CURATED = Collections.unmodifiableList(Arrays.asList(
            "primary", "outgoingBubble", "incomingBubble", "fab", "unreadBadge", "link"));

    public static final List<String> ALL_TOKENS = Collections.unmodifiableList(
            new ArrayList<>(SemanticTheme.fromPreset("Green", false).asMap().keySet()));

    private ThemeResolution() {
    }

    public static SemanticTheme.Tokens resolve(String presetName, boolean dark,
            Map<String, Integer> overrides) {
        SemanticTheme.Tokens base = SemanticTheme.fromPreset(presetName, dark);
        if (overrides == null || overrides.isEmpty()) return base;

        Map<String, Integer> resolved = new java.util.LinkedHashMap<>(base.asMap());
        for (Map.Entry<String, Integer> entry : overrides.entrySet()) {
            // Token desconhecido é ignorado, não propagado: mesmo princípio do §6.3 para import.
            if (!resolved.containsKey(entry.getKey())) continue;
            if (entry.getValue() == null) continue;
            resolved.put(entry.getKey(), entry.getValue());
        }
        return new SemanticTheme.Tokens(resolved);
    }
}
```

> **Mudança obrigatória em `SemanticTheme`:** o construtor de `Tokens` é **privado**
> (`SemanticTheme.java`, linha 17), então `ThemeResolution` não consegue instanciar. Adicionar
> um factory à própria `Tokens`, e usá-lo em vez de duplicar a classe:
>
> ```java
> public static Tokens of(Map<String, Integer> values) {
>     return new Tokens(new LinkedHashMap<>(values));
> }
> ```
>
> A cópia defensiva importa: `Tokens` promete um mapa imutável, e receber um `Map` do chamador
> sem copiar quebraria essa promessa.
>
> **Cuidado com `Tokens.get()`:** ele **lança** `IllegalArgumentException` em token desconhecido
> (linha 21-25), não devolve zero. É o comportamento certo — falha alto em vez de pintar preto —
> mas significa que qualquer consulta a um nome de token errado derruba a tela em vez de produzir
> uma cor estranha. Só consultar nomes vindos de `ALL_TOKENS`.
>
> **Nomes de preset são minúsculos** (`"green"`, `"blue"`, `"cyan"`, `"purple"`, `"orange"`,
> `"red"`, `"pink"`). `presetColor` normaliza a entrada com `toLowerCase`, então `"Blue"`
> funciona; mas ao iterar `presets().keySet()` as chaves vêm minúsculas.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testWhatsappDebugUnitTest --tests "*ThemeResolutionTest"`
Expected: PASS, 9 testes.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/waenhancer/theme/ThemeResolution.java app/src/test/java/com/waenhancer/theme/ThemeResolutionTest.java app/src/main/java/com/waenhancer/theme/SemanticTheme.java
git commit -m "feat: add theme override resolution with §11.5 precedence

Resolution derives nothing: an override is by definition the instruction
not to derive, so rederiving neighbouring tokens would undo the user's
choice one token later.

The first test is the compatibility invariant — with no overrides,
resolution equals fromPreset exactly. It is what makes this phase
additive instead of a preference migration under §5.3."
```

---

### Task A2: `ThemeOverrideStore` — serialização que não derruba o WhatsApp

**Files:**
- Create: `app/src/main/java/com/waenhancer/theme/ThemeOverrideStore.java`
- Test: `app/src/test/java/com/waenhancer/theme/ThemeOverrideStoreTest.java`

**Interfaces:**
- Consumes: `ThemeResolution.ALL_TOKENS` (A1), `org.json`.
- Produces: `ThemeOverrideStore.parse(String json, boolean dark)` → `Map<String,Integer>` (nunca null); `ThemeOverrideStore.serialize(Map<String,Integer> light, Map<String,Integer> dark)` → `String`; `ThemeOverrideStore.MAX_JSON_LENGTH` (`8192`).

- [ ] **Step 1: Write the failing test**

```java
package com.waenhancer.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import java.util.HashMap;
import java.util.Map;

import org.junit.Test;

/**
 * Esta string é lida de dentro do processo do WhatsApp. Cada caso abaixo que lançasse seria o
 * WhatsApp abrindo errado, não uma tela do módulo com defeito.
 */
public class ThemeOverrideStoreTest {

    private static final int MAGENTA = 0xFFFF00FF;
    private static final int CYAN = 0xFF00FFFF;

    @Test
    public void aRoundTripPreservesEachModeSeparately() {
        Map<String, Integer> light = new HashMap<>();
        light.put("outgoingBubble", MAGENTA);
        Map<String, Integer> dark = new HashMap<>();
        dark.put("outgoingBubble", CYAN);

        String json = ThemeOverrideStore.serialize(light, dark);

        assertEquals(MAGENTA, (int) ThemeOverrideStore.parse(json, false).get("outgoingBubble"));
        assertEquals(CYAN, (int) ThemeOverrideStore.parse(json, true).get("outgoingBubble"));
    }

    /** O motivo de separar os modos: consertar o claro não pode quebrar o escuro. */
    @Test
    public void lightOverridesDoNotLeakIntoDark() {
        Map<String, Integer> light = new HashMap<>();
        light.put("fab", MAGENTA);
        String json = ThemeOverrideStore.serialize(light, new HashMap<>());
        assertTrue(ThemeOverrideStore.parse(json, true).isEmpty());
    }

    @Test
    public void malformedJsonYieldsNoOverridesInsteadOfThrowing() {
        assertTrue(ThemeOverrideStore.parse("{not json", false).isEmpty());
        assertTrue(ThemeOverrideStore.parse("[]", false).isEmpty());
        assertTrue(ThemeOverrideStore.parse("", false).isEmpty());
        assertTrue(ThemeOverrideStore.parse(null, false).isEmpty());
    }

    @Test
    public void parseNeverReturnsNull() {
        assertNotNull(ThemeOverrideStore.parse(null, false));
    }

    /** Uma cor inválida é descartada sozinha; as válidas do mesmo mapa sobrevivem. */
    @Test
    public void anInvalidColourIsDroppedWithoutLosingTheValidOnes() {
        String json = "{\"light\":{\"fab\":\"nope\",\"link\":\"#FF00FFFF\"}}";
        Map<String, Integer> parsed = ThemeOverrideStore.parse(json, false);
        assertEquals(1, parsed.size());
        assertEquals(CYAN, (int) parsed.get("link"));
    }

    @Test
    public void anUnknownTokenIsDropped() {
        String json = "{\"light\":{\"notAToken\":\"#FF00FFFF\"}}";
        assertTrue(ThemeOverrideStore.parse(json, false).isEmpty());
    }

    /** Limite de tamanho, como o §6.3 exige para importação. */
    @Test
    public void anOversizedPayloadIsRejectedEntirely() {
        StringBuilder huge = new StringBuilder("{\"light\":{");
        while (huge.length() <= ThemeOverrideStore.MAX_JSON_LENGTH) {
            huge.append("\"link\":\"#FF00FFFF\",");
        }
        huge.append("}}");
        assertTrue(ThemeOverrideStore.parse(huge.toString(), false).isEmpty());
    }

    @Test
    public void serializingEmptyMapsProducesAParsableEmptyResult() {
        String json = ThemeOverrideStore.serialize(new HashMap<>(), new HashMap<>());
        assertTrue(ThemeOverrideStore.parse(json, false).isEmpty());
        assertTrue(ThemeOverrideStore.parse(json, true).isEmpty());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testWhatsappDebugUnitTest --tests "*ThemeOverrideStoreTest"`
Expected: FAIL — `ThemeOverrideStore` não existe.

- [ ] **Step 3: Write minimal implementation**

```java
package com.waenhancer.theme;

import org.json.JSONObject;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Serializa os overrides de tema numa única preferência.
 *
 * <p>Uma pref e não vinte, e por modo claro/escuro: uma cor escolhida sobre branco costuma ficar
 * ilegível sobre quase preto, e um mapa único faria o usuário consertar um modo e quebrar o
 * outro sem perceber.</p>
 *
 * <p>A leitura é defensiva até o exagero porque acontece dentro do processo do WhatsApp: nada
 * aqui pode lançar.</p>
 */
public final class ThemeOverrideStore {

    /** Dois mapas de 20 tokens cabem folgados; acima disto é payload adulterado. */
    public static final int MAX_JSON_LENGTH = 8192;

    private static final String LIGHT = "light";
    private static final String DARK = "dark";

    private ThemeOverrideStore() {
    }

    public static Map<String, Integer> parse(String json, boolean dark) {
        if (json == null || json.isEmpty() || json.length() > MAX_JSON_LENGTH) {
            return Collections.emptyMap();
        }
        try {
            JSONObject root = new JSONObject(json);
            JSONObject mode = root.optJSONObject(dark ? DARK : LIGHT);
            if (mode == null) return Collections.emptyMap();

            Map<String, Integer> result = new LinkedHashMap<>();
            for (String token : ThemeResolution.ALL_TOKENS) {
                String raw = mode.optString(token, null);
                if (raw == null || raw.isEmpty()) continue;
                Integer colour = parseColour(raw);
                if (colour != null) result.put(token, colour);
            }
            return result;
        } catch (Throwable malformed) {
            // Uma exceção aqui é o WhatsApp abrindo errado. Sem overrides é sempre melhor.
            return Collections.emptyMap();
        }
    }

    public static String serialize(Map<String, Integer> light, Map<String, Integer> dark) {
        JSONObject root = new JSONObject();
        try {
            root.put(LIGHT, toJson(light));
            root.put(DARK, toJson(dark));
        } catch (Throwable ignored) {
            return "";
        }
        return root.toString();
    }

    private static JSONObject toJson(Map<String, Integer> overrides) throws Exception {
        JSONObject object = new JSONObject();
        if (overrides == null) return object;
        for (Map.Entry<String, Integer> entry : overrides.entrySet()) {
            if (entry.getValue() == null) continue;
            if (!ThemeResolution.ALL_TOKENS.contains(entry.getKey())) continue;
            object.put(entry.getKey(), String.format(Locale.US, "#%08X", entry.getValue()));
        }
        return object;
    }

    private static Integer parseColour(String raw) {
        String value = raw.trim();
        if (!value.startsWith("#") || (value.length() != 7 && value.length() != 9)) return null;
        try {
            long parsed = Long.parseLong(value.substring(1), 16);
            if (value.length() == 7) parsed |= 0xFF000000L;
            return (int) parsed;
        } catch (NumberFormatException notAColour) {
            return null;
        }
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testWhatsappDebugUnitTest --tests "*ThemeOverrideStoreTest"`
Expected: PASS, 8 testes.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/waenhancer/theme/ThemeOverrideStore.java app/src/test/java/com/waenhancer/theme/ThemeOverrideStoreTest.java
git commit -m "feat: serialise theme overrides per light/dark mode

One preference rather than twenty, split by mode: a colour picked
against white is often unreadable against near black, and a shared map
would let the user fix one mode while silently breaking the other.

Parsing is defensive to the point of excess because it happens inside
WhatsApp's process — nothing here may throw, and a size limit rejects
tampered payloads as §6.3 requires for import."
```

---

### Task A3: `ContrastAdvisor` — medir e sugerir, nunca aplicar

**Files:**
- Create: `app/src/main/java/com/waenhancer/theme/ContrastAdvisor.java`
- Test: `app/src/test/java/com/waenhancer/theme/ContrastAdvisorTest.java`

**Interfaces:**
- Consumes: `SemanticTheme.contrastRatio`, `ensureTextContrast`, `ensureControlContrast`.
- Produces: `ContrastAdvisor.advise(String token, int colour, int background)` → `Advice`; `Advice#ratio()`, `#floor()`, `#passes()`, `#suggestion()`; `ContrastAdvisor.backgroundTokenFor(String token)` → `String`.

- [ ] **Step 1: Write the failing test**

```java
package com.waenhancer.theme;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * O contraste de um override.
 *
 * <p>A engine garante contraste no que deriva; um override é o usuário dizendo para não derivar.
 * O consultor mede e sugere — a correção só acontece se o usuário tocar nela.</p>
 */
public class ContrastAdvisorTest {

    private static final int WHITE = 0xFFFFFFFF;
    private static final int BLACK = 0xFF000000;
    private static final int PALE_YELLOW = 0xFFFFF9C4;

    @Test
    public void theMeasuredRatioMatchesTheEngine() {
        ContrastAdvisor.Advice advice = ContrastAdvisor.advise("link", BLACK, WHITE);
        assertEquals(SemanticTheme.contrastRatio(BLACK, WHITE), advice.ratio(), 0.001);
    }

    /** Texto: piso 4.5. */
    @Test
    public void aTextTokenUsesTheTextFloor() {
        assertEquals(4.5, ContrastAdvisor.advise("link", BLACK, WHITE).floor(), 0.001);
    }

    /** Controle e elemento grande: piso 3.0. */
    @Test
    public void aControlTokenUsesTheControlFloor() {
        assertEquals(3.0, ContrastAdvisor.advise("fab", BLACK, WHITE).floor(), 0.001);
    }

    @Test
    public void blackOnWhitePasses() {
        assertTrue(ContrastAdvisor.advise("link", BLACK, WHITE).passes());
    }

    @Test
    public void paleYellowOnWhiteFails() {
        assertFalse(ContrastAdvisor.advise("link", PALE_YELLOW, WHITE).passes());
    }

    /** A sugestão não é decorativa: ela tem que de fato passar do piso. */
    @Test
    public void theSuggestionActuallyClearsTheFloor() {
        ContrastAdvisor.Advice advice = ContrastAdvisor.advise("link", PALE_YELLOW, WHITE);
        assertTrue(SemanticTheme.contrastRatio(advice.suggestion(), WHITE) >= advice.floor());
    }

    /** Quando já passa, a sugestão é a própria cor: nada a corrigir. */
    @Test
    public void aPassingColourIsItsOwnSuggestion() {
        ContrastAdvisor.Advice advice = ContrastAdvisor.advise("link", BLACK, WHITE);
        assertEquals(BLACK, advice.suggestion());
    }

    @Test
    public void bubbleTokensAreMeasuredAgainstTheirOwnBubble() {
        assertEquals("outgoingBubble", ContrastAdvisor.backgroundTokenFor("onOutgoingBubble"));
        assertEquals("incomingBubble", ContrastAdvisor.backgroundTokenFor("onIncomingBubble"));
    }

    @Test
    public void mostTokensAreMeasuredAgainstSurface() {
        assertEquals("surface", ContrastAdvisor.backgroundTokenFor("link"));
        assertEquals("surface", ContrastAdvisor.backgroundTokenFor("fab"));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testWhatsappDebugUnitTest --tests "*ContrastAdvisorTest"`
Expected: FAIL — `ContrastAdvisor` não existe.

- [ ] **Step 3: Write minimal implementation**

Pontos obrigatórios: `TEXT_FLOOR = 4.5`, `CONTROL_FLOOR = 3.0`; tokens de texto (`link`, `onPrimary`, `onSurface`, `onPrimaryContainer`, `onOutgoingBubble`, `onIncomingBubble`) usam o piso de texto, o resto usa o de controle; `suggestion()` chama `SemanticTheme.ensureTextContrast` ou `ensureControlContrast` conforme o piso, e devolve a cor original quando já passa. `Advice` é imutável.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testWhatsappDebugUnitTest --tests "*ContrastAdvisorTest"`
Expected: PASS, 9 testes.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/waenhancer/theme/ContrastAdvisor.java app/src/test/java/com/waenhancer/theme/ContrastAdvisorTest.java
git commit -m "feat: measure override contrast and suggest a fix

Measures and suggests; never applies. Silent correction would make the
colour picker look broken — the user picks one tone and the screen shows
another. The engine's own correction stays one tap away."
```

---

### Task A4: registrar a pref e auditar o consumo de tokens

**Files:**
- Modify: `app/src/main/java/com/waenhancer/config/PreferenceSchema.java`
- Create: `docs/theme-token-coverage.md`

**Interfaces:**
- Produces: a chave `wae_theme_overrides`; a lista auditada de quais tokens `CustomThemeV2` realmente aplica.

- [ ] **Step 1: Register the preference**

Em `PreferenceSchema.java`, na posição alfabética (perto de `wae_color_preset`, linha 286):

```java
add(entries, "wae_theme_overrides", Type.STRING, Sensitivity.PUBLIC_SETTING, Store.PUBLIC);
```

- [ ] **Step 2: Audit which tokens are actually applied**

Este passo é o risco 3 da spec, e ele decide o conteúdo da UI da Parte B. Ler `app/src/main/java/com/waenhancer/xposed/features/customization/CustomThemeV2.java` (655 linhas) e produzir `docs/theme-token-coverage.md` com uma tabela:

| token | aplicado por `CustomThemeV2`? | onde |
|---|---|---|

**Um token editável que nenhum hook consome é um controle que não faz nada.** A Parte B usa esta tabela para marcar na UI os tokens ainda sem efeito, em vez de oferecer vinte controles dos quais alguns são inertes.

- [ ] **Step 3: Run the full suite**

Run: `./gradlew :app:compileWhatsappDebugJavaWithJavac :app:testWhatsappDebugUnitTest`
Expected: PASS.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/waenhancer/config/PreferenceSchema.java docs/theme-token-coverage.md
git commit -m "feat: register theme override preference and audit token coverage

The audit decides the editor's UI: a token no hook consumes is a control
that does nothing, so the advanced section marks those instead of
offering twenty controls of which some are inert."
```

---

## Handoff da Parte A para a Parte B

Invariantes que a Parte B **não pode** quebrar:

1. sem overrides, a resolução é idêntica ao preset — não introduzir um default que escreva overrides na primeira abertura da tela;
2. `ThemeOverrideStore.parse` nunca lança e nunca devolve null;
3. contraste é **avisado**, nunca aplicado sozinho;
4. o preview pergunta ao `ThemeResolution`; não recalcula cor.

---

# PARTE B — Sonnet 5

**Por que Sonnet:** daqui para baixo é UI — uma Activity no molde de duas que já existem, color pickers, uma maquete de preview e o fio dos avisos. Toda a aritmética de cor e toda a serialização já estão prontas e travadas por teste. É iteração visual, que é o que o §1A.2 atribui ao Sonnet.

---

### Task B1: `ThemePreviewModel` — a maquete

**Files:**
- Create: `app/src/main/java/com/waenhancer/theme/ThemePreviewModel.java`
- Test: `app/src/test/java/com/waenhancer/theme/ThemePreviewModelTest.java`

**Interfaces:**
- Consumes: `ThemeResolution` (A1).
- Produces: `ThemePreviewModel.of(SemanticTheme.Tokens)` → modelo com `headerColour()`, `outgoingBubbleColour()`, `outgoingTextColour()`, `incomingBubbleColour()`, `incomingTextColour()`, `fabColour()`, `badgeColour()`, `linkColour()`.

- [ ] **Step 1: Write the failing test**

```java
package com.waenhancer.theme;

import static org.junit.Assert.assertEquals;

import java.util.Collections;

import org.junit.Test;

/**
 * O preview e o resultado real não podem ter duas matemáticas.
 *
 * <p>É a mesma disciplina que a F1 impôs quando BottomBarPreviewModel passou a delegar ao
 * GlassSpec: se a maquete recalcular cor por conta própria, ela mente, e o usuário só descobre
 * depois de aplicar.</p>
 */
public class ThemePreviewModelTest {

    @Test
    public void everyElementPaintsWithTheResolvedToken() {
        SemanticTheme.Tokens tokens = ThemeResolution.resolve("Purple", true, Collections.emptyMap());
        ThemePreviewModel preview = ThemePreviewModel.of(tokens);

        assertEquals(tokens.get("primary"), preview.headerColour());
        assertEquals(tokens.get("outgoingBubble"), preview.outgoingBubbleColour());
        assertEquals(tokens.get("onOutgoingBubble"), preview.outgoingTextColour());
        assertEquals(tokens.get("incomingBubble"), preview.incomingBubbleColour());
        assertEquals(tokens.get("onIncomingBubble"), preview.incomingTextColour());
        assertEquals(tokens.get("fab"), preview.fabColour());
        assertEquals(tokens.get("unreadBadge"), preview.badgeColour());
        assertEquals(tokens.get("link"), preview.linkColour());
    }

    /** Um override tem que aparecer na maquete, senão o preview não serve para nada. */
    @Test
    public void anOverrideReachesThePreview() {
        int magenta = 0xFFFF00FF;
        SemanticTheme.Tokens tokens = ThemeResolution.resolve("Purple", true,
                Collections.singletonMap("fab", magenta));
        assertEquals(magenta, ThemePreviewModel.of(tokens).fabColour());
    }
}
```

- [ ] **Step 2: Run, implement, run**

Implementação trivial: cada acessor devolve `tokens.get("<token>")`. Nenhuma aritmética.
Run: `./gradlew :app:testWhatsappDebugUnitTest --tests "*ThemePreviewModelTest"` → PASS.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/waenhancer/theme/ThemePreviewModel.java app/src/test/java/com/waenhancer/theme/ThemePreviewModelTest.java
git commit -m "feat: describe the theme preview mock-up from resolved tokens

Every accessor asks ThemeResolution; none recalculates. Same discipline
as BottomBarPreviewModel delegating to GlassSpec in F1: a preview with
its own arithmetic lies, and the user finds out after applying."
```

---

### Task B2: `ThemeEditorActivity`

**Files:**
- Create: `app/src/main/java/com/waenhancer/activities/ThemeEditorActivity.java`
- Create: `app/src/main/res/layout/activity_theme_editor.xml`
- Modify: `app/src/main/java/com/waenhancer/activities/MainActivity.java` (entrada para a tela)
- Modify: `app/src/main/AndroidManifest.xml` (`android:exported="false"`, como o §7.3 exige)

**Interfaces:**
- Consumes: `ThemeResolution`, `ThemeOverrideStore`, `ContrastAdvisor`, `ThemePreviewModel`, `docs/theme-token-coverage.md`.

- [ ] **Step 1: Build the screen**

Seguir o padrão de `BottomBarCustomizationActivity` e `LiquidGlassActivity`. Estrutura:

1. seletor de preset (`SemanticTheme.presets()`);
2. a maquete de preview, no topo e sempre visível ao rolar;
3. os seis tokens de `ThemeResolution.CURATED`, cada um com swatch e color picker;
4. `Avançado`, recolhido, com os 20 de `ThemeResolution.ALL_TOKENS` — **os tokens marcados na auditoria da Task A4 como não consumidos aparecem com um rótulo dizendo isso**;
5. reset por token e reset geral;
6. o modo claro/escuro editado segue o modo atual do sistema, com um alternador para editar o outro sem trocar o tema do aparelho.

- [ ] **Step 2: Wire the contrast warning**

Ao escolher uma cor, chamar `ContrastAdvisor.advise(token, colour, resolved.get(ContrastAdvisor.backgroundTokenFor(token)))`. Se `!passes()`, mostrar a razão medida formatada (`"2,1:1 — abaixo de 4,5:1"`) e um botão que aplica `suggestion()`. **A cor escolhida não muda sozinha.**

- [ ] **Step 3: Persist**

Gravar com `ThemeOverrideStore.serialize(light, dark)` na pref `wae_theme_overrides`. Reset geral grava `""`, não um JSON de mapas vazios — assim o invariante de compatibilidade volta ao estado literal de quem nunca abriu a tela.

- [ ] **Step 4: Verify on device**

- trocar preset repinta acento, FAB, badge e links no WhatsApp;
- override de `outgoingBubble` **sobrevive à troca de preset** (é a precedência do §11.5);
- alternar claro/escuro usa o mapa certo;
- reset por token volta ao derivado; reset geral volta ao estado original;
- **não regressão:** com `wae_theme_overrides` ausente, o app se comporta exatamente como antes desta fase.

- [ ] **Step 5: Commit**

```bash
git add -A
git commit -m "feat: add theme editor screen

Curated tokens first, the twenty behind Advanced, with tokens no hook
consumes labelled as such from the coverage audit. Reset-all writes an
empty string rather than empty maps, so the compatibility invariant
returns to the literal state of a user who never opened the screen."
```

---

### Task B3: Gate

- [ ] **Step 1: Full build and suite**

Run: `./gradlew :app:compileWhatsappDebugJavaWithJavac :app:testWhatsappDebugUnitTest`
Expected: PASS, sem regressão.

- [ ] **Step 2: Verify the compatibility invariant explicitly**

Desinstalar, instalar limpo, **não abrir o editor**, e confirmar que o tema é idêntico ao da versão anterior. Este é o teste que decide se a fase foi aditiva.

- [ ] **Step 3: Add the preference to the backup allowlist**

`wae_theme_overrides` é configuração pública sem segredo e deve entrar na allowlist de exportação do §6.2. Se a allowlist ainda não existir no código, registrar a pendência em `HANDOFF_F3_THEME_EDITOR.md` em vez de inventar o mecanismo aqui.

- [ ] **Step 4: Write the completion handoff**

Acrescentar `#### Estado da F3 editor de tema — implementada` a `HANDOFF_F3_THEME_EDITOR.md`, incluindo o resultado da auditoria da Task A4: quais tokens ainda não têm efeito, porque isso é a próxima dívida da engine de tema.

- [ ] **Step 5: Commit**

```bash
git add HANDOFF_F3_THEME_EDITOR.md
git commit -m "docs: record theme editor implementation state and token coverage gaps"
```
