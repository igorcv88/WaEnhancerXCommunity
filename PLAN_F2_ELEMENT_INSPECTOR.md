# Element Inspector Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Um modo temporário de inspeção dentro do processo do WhatsApp que responde "qual seletor CSS atinge esta view?", sem persistir nada e sem jamais ler conteúdo de mensagem.

**Architecture:** Um núcleo puro (sessão, hit-test, gerador de seletor, redator) livre de tipos Android e coberto por testes JVM, mais uma camada Android fina que só desenha e roteia toque. O overlay é uma janela `TYPE_APPLICATION` própria, ancorada no token do Activity — a árvore de views do WhatsApp **nunca** é modificada.

**Tech Stack:** Java 17, Xposed/LSPosed, JUnit 4 (sem Robolectric, sem Mockito), Gradle.

**Spec:** `HANDOFF_F2_ELEMENT_INSPECTOR.md`

## Global Constraints

- **Nenhum `getText()`.** Nenhuma classe do pacote `devtools` pode chamar `TextView.getText()`, `EditText.getText()` ou equivalente. Verificado por grep no Gate.
- **Nada persistido.** A única escrita em disco do fluxo inteiro é a pref `inspector_session` (token + expiração).
- **Testes na JVM apenas.** JUnit 4, sem Robolectric e sem Mockito (`app/build.gradle`, linhas 216-217). Qualquer classe que precise ser testada não pode importar `android.*`.
- **Pacote:** `com.waenhancer.xposed.features.devtools`.
- **Timeout de sessão:** 10 minutos de **inatividade**, renovado a cada seleção.
- **ID da pref:** `inspector_session`, `Type.STRING`, `Sensitivity.PUBLIC_SETTING`, `Store.PUBLIC`.
- **Build/teste:** `./gradlew :app:compileWhatsappDebugJavaWithJavac :app:testWhatsappDebugUnitTest`
- **Commits:** conventional commits, mensagem em inglês.

---

# PARTE A — Opus 5

**Por que Opus:** é aqui que moram a privacidade (o redator e a proibição de ler texto), a segurança da sessão (token e expiração) e a correção do seletor contra o parser do `CustomView`. Um erro em qualquer um dos três não aparece como bug visual — aparece como vazamento de conteúdo de conversa ou como uma feature que mente sobre o seletor que oferece. Todas as quatro classes desta parte são puras e testáveis, então o resultado é verificável antes de qualquer coisa Android existir.

**Entrega da Parte A:** quatro classes sem uma única importação `android.*`, quatro suítes verdes, e nenhum hook instalado. O app compila e se comporta exatamente como antes.

---

### Task A1: `InspectorSession` — token e expiração

**Files:**
- Create: `app/src/main/java/com/waenhancer/xposed/features/devtools/InspectorSession.java`
- Test: `app/src/test/java/com/waenhancer/xposed/features/devtools/InspectorSessionTest.java`

**Interfaces:**
- Consumes: nada.
- Produces: `InspectorSession.armed(String token, long nowMillis)`, `boolean isActive(long nowMillis)`, `InspectorSession touched(long nowMillis)`, `boolean matches(String token)`, `String token()`, `long expiresAt()`, `InspectorSession.IDLE_TIMEOUT_MILLIS`, `InspectorSession.expired()`.

A classe é **imutável**: `touched()` devolve uma instância nova. É o padrão de imutabilidade das regras do projeto e evita que o overlay e o hook compartilhem estado mutável entre threads.

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testWhatsappDebugUnitTest --tests "*InspectorSessionTest"`
Expected: FAIL — compilação falha, `InspectorSession` não existe.

- [ ] **Step 3: Write minimal implementation**

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testWhatsappDebugUnitTest --tests "*InspectorSessionTest"`
Expected: PASS, 8 testes.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/waenhancer/xposed/features/devtools/InspectorSession.java app/src/test/java/com/waenhancer/xposed/features/devtools/InspectorSessionTest.java
git commit -m "feat: add inspector session with idle timeout

Immutable so the touch hook and the overlay can read it from different
threads without synchronisation. Touching an expired session does not
revive it, so a late touch cannot reopen inspection without a new arm."
```

---

### Task A2: `Redactor` — o que nunca chega à tela

**Files:**
- Create: `app/src/main/java/com/waenhancer/xposed/features/devtools/Redactor.java`
- Test: `app/src/test/java/com/waenhancer/xposed/features/devtools/RedactorTest.java`

**Interfaces:**
- Consumes: nada.
- Produces: `Redactor.redact(String raw)` → `String`; `Redactor.REDACTED` (`"‹redigido›"`); `Redactor.MAX_PLAIN_LENGTH` (`40`).

- [ ] **Step 1: Write the failing test**

```java
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
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testWhatsappDebugUnitTest --tests "*RedactorTest"`
Expected: FAIL — `Redactor` não existe.

- [ ] **Step 3: Write minimal implementation**

```java
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
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testWhatsappDebugUnitTest --tests "*RedactorTest"`
Expected: PASS, 9 testes.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/waenhancer/xposed/features/devtools/Redactor.java app/src/test/java/com/waenhancer/xposed/features/devtools/RedactorTest.java
git commit -m "feat: add redactor for the only text the inspector shows

contentDescription carries both button labels and contact names, so the
rule is deliberately asymmetric: a legitimate label redacted by mistake
costs an inconvenience, a contact name shown by mistake is what §11.3
forbids."
```

---

### Task A3: `ProbeNode` + `ViewProbe` — o hit-test

**Files:**
- Create: `app/src/main/java/com/waenhancer/xposed/features/devtools/ProbeNode.java`
- Create: `app/src/main/java/com/waenhancer/xposed/features/devtools/ViewProbe.java`
- Test: `app/src/test/java/com/waenhancer/xposed/features/devtools/ViewProbeTest.java`

**Interfaces:**
- Consumes: nada.
- Produces:
  - `interface ProbeNode` com `int left()`, `int top()`, `int right()`, `int bottom()`, `boolean visible()`, `float alpha()`, `List<ProbeNode> children()`, `ProbeNode parent()`, `String entryName()`, `String resourcePackage()`, `int id()`, `String className()`, `String contentDescription()`.
  - `ViewProbe.hit(ProbeNode root, int x, int y)` → `ProbeNode` ou `null`.
- Nota para a Parte B: `left/top/right/bottom` são as bounds **já recortadas** (o resultado de `getGlobalVisibleRect`), não as bounds nominais. O adaptador é responsável por isso.

- [ ] **Step 1: Write the failing test**

```java
package com.waenhancer.xposed.features.devtools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Qual view o dedo escolheu. Cada caso errado aqui é o usuário copiando o seletor de outra coisa
 * e concluindo que o motor de CSS está quebrado.
 */
public class ViewProbeTest {

    /** Nó de teste: as bounds já vêm recortadas, como o adaptador entrega. */
    private static final class Node implements ProbeNode {
        final int l, t, r, b;
        boolean visible = true;
        float alpha = 1f;
        String entry;
        final List<ProbeNode> kids = new ArrayList<>();
        ProbeNode parent;

        Node(String entry, int l, int t, int r, int b) {
            this.entry = entry;
            this.l = l; this.t = t; this.r = r; this.b = b;
        }

        Node with(Node... children) {
            for (Node c : children) {
                c.parent = this;
                kids.add(c);
            }
            return this;
        }

        Node hidden() { this.visible = false; return this; }
        Node transparent() { this.alpha = 0f; return this; }

        @Override public int left() { return l; }
        @Override public int top() { return t; }
        @Override public int right() { return r; }
        @Override public int bottom() { return b; }
        @Override public boolean visible() { return visible; }
        @Override public float alpha() { return alpha; }
        @Override public List<ProbeNode> children() { return kids; }
        @Override public ProbeNode parent() { return parent; }
        @Override public String entryName() { return entry; }
        @Override public String resourcePackage() { return "com.whatsapp"; }
        @Override public int id() { return entry == null ? -1 : entry.hashCode(); }
        @Override public String className() { return "android.view.View"; }
        @Override public String contentDescription() { return null; }
    }

    private static String entryOf(ProbeNode node) {
        return node == null ? null : node.entryName();
    }

    @Test
    public void theDeepestLeafWins() {
        Node leaf = new Node("leaf", 10, 10, 90, 90);
        Node root = new Node("root", 0, 0, 100, 100).with(leaf);
        assertEquals("leaf", entryOf(ViewProbe.hit(root, 50, 50)));
    }

    /** Irmãos que se sobrepõem: quem é desenhado depois está por cima. */
    @Test
    public void theLastDrawnSiblingWins() {
        Node under = new Node("under", 0, 0, 100, 100);
        Node over = new Node("over", 0, 0, 100, 100);
        Node root = new Node("root", 0, 0, 100, 100).with(under, over);
        assertEquals("over", entryOf(ViewProbe.hit(root, 50, 50)));
    }

    @Test
    public void anInvisibleChildIsSkipped() {
        Node hidden = new Node("hidden", 0, 0, 100, 100).hidden();
        Node root = new Node("root", 0, 0, 100, 100).with(hidden);
        assertEquals("root", entryOf(ViewProbe.hit(root, 50, 50)));
    }

    @Test
    public void aFullyTransparentChildIsSkipped() {
        Node ghost = new Node("ghost", 0, 0, 100, 100).transparent();
        Node root = new Node("root", 0, 0, 100, 100).with(ghost);
        assertEquals("root", entryOf(ViewProbe.hit(root, 50, 50)));
    }

    /**
     * O caso do getGlobalVisibleRect: a linha rolou para fora da lista, então suas bounds
     * recortadas não contêm mais o ponto, mesmo que as nominais contivessem.
     */
    @Test
    public void aChildClippedOutOfViewIsNotHit() {
        Node scrolledAway = new Node("row", 0, 0, 100, 0);
        Node list = new Node("list", 0, 0, 100, 100).with(scrolledAway);
        assertEquals("list", entryOf(ViewProbe.hit(list, 50, 50)));
    }

    @Test
    public void aPointOutsideEverythingHitsNothing() {
        Node root = new Node("root", 0, 0, 100, 100);
        assertNull(ViewProbe.hit(root, 500, 500));
    }

    @Test
    public void aHiddenParentHidesItsChildren() {
        Node leaf = new Node("leaf", 10, 10, 90, 90);
        Node branch = new Node("branch", 0, 0, 100, 100).hidden().with(leaf);
        Node root = new Node("root", 0, 0, 100, 100).with(branch);
        assertEquals("root", entryOf(ViewProbe.hit(root, 50, 50)));
    }

    @Test
    public void aNullRootHitsNothing() {
        assertNull(ViewProbe.hit(null, 0, 0));
    }

    /** A borda direita/inferior é exclusiva, como Rect.contains. */
    @Test
    public void theRightAndBottomEdgesAreExclusive() {
        Node root = new Node("root", 0, 0, 100, 100);
        assertNull(ViewProbe.hit(root, 100, 50));
        assertEquals("root", entryOf(ViewProbe.hit(root, 99, 99)));
    }

    @Test
    public void siblingsThatDoNotOverlapAreEachHitInTheirOwnArea() {
        Node left = new Node("left", 0, 0, 50, 100);
        Node right = new Node("right", 50, 0, 100, 100);
        Node root = new Node("root", 0, 0, 100, 100).with(left, right);
        assertEquals("left", entryOf(ViewProbe.hit(root, 10, 50)));
        assertEquals("right", entryOf(ViewProbe.hit(root, 90, 50)));
        assertEquals(Arrays.asList(left, right), root.children());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testWhatsappDebugUnitTest --tests "*ViewProbeTest"`
Expected: FAIL — `ProbeNode` e `ViewProbe` não existem.

- [ ] **Step 3: Write minimal implementation**

`ProbeNode.java`:

```java
package com.waenhancer.xposed.features.devtools;

import java.util.List;

/**
 * A árvore de views vista sem depender de android.view.View.
 *
 * <p>Existe para que o hit-test — a parte mais fácil de errar do inspector — seja testável na
 * JVM, já que o projeto não tem Robolectric. O adaptador que converte View em ProbeNode vive na
 * camada Android e é deliberadamente burro.</p>
 *
 * <p>As bounds são as <b>recortadas</b> (o que getGlobalVisibleRect devolve), nunca as nominais.
 * É essa escolha que faz uma linha rolada para fora da lista não ser acertada.</p>
 */
public interface ProbeNode {

    int left();

    int top();

    int right();

    int bottom();

    boolean visible();

    float alpha();

    List<ProbeNode> children();

    ProbeNode parent();

    /** Nome do recurso, ou null quando a view não tem id. */
    String entryName();

    String resourcePackage();

    int id();

    String className();

    String contentDescription();
}
```

`ViewProbe.java`:

```java
package com.waenhancer.xposed.features.devtools;

import java.util.List;

/**
 * Descobre qual view está sob o dedo.
 *
 * <p>Percorre os filhos de trás para frente porque essa é a ordem de desenho: o último filho é
 * o que está por cima, e portanto o que o usuário acha que tocou.</p>
 */
public final class ViewProbe {

    private ViewProbe() {
    }

    public static ProbeNode hit(ProbeNode root, int x, int y) {
        if (root == null) return null;
        if (!isEligible(root) || !contains(root, x, y)) return null;

        List<ProbeNode> children = root.children();
        if (children != null) {
            for (int i = children.size() - 1; i >= 0; i--) {
                ProbeNode found = hit(children.get(i), x, y);
                if (found != null) return found;
            }
        }
        return root;
    }

    private static boolean isEligible(ProbeNode node) {
        return node.visible() && node.alpha() > 0f;
    }

    /** Borda direita e inferior exclusivas, igual a Rect.contains. */
    private static boolean contains(ProbeNode node, int x, int y) {
        return x >= node.left() && x < node.right()
                && y >= node.top() && y < node.bottom();
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testWhatsappDebugUnitTest --tests "*ViewProbeTest"`
Expected: PASS, 10 testes.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/waenhancer/xposed/features/devtools/ProbeNode.java app/src/main/java/com/waenhancer/xposed/features/devtools/ViewProbe.java app/src/test/java/com/waenhancer/xposed/features/devtools/ViewProbeTest.java
git commit -m "feat: add hit-test over an Android-free view tree

ProbeNode exists so the hit-test is testable on the JVM, since the
project has no Robolectric. Bounds are the clipped ones, which is what
makes a row scrolled out of a list miss the point."
```

---

### Task A4: `SelectorBuilder` — o seletor que o motor aceita

**Files:**
- Create: `app/src/main/java/com/waenhancer/xposed/features/devtools/InspectedView.java`
- Create: `app/src/main/java/com/waenhancer/xposed/features/devtools/SelectorBuilder.java`
- Test: `app/src/test/java/com/waenhancer/xposed/features/devtools/SelectorBuilderTest.java`

**Interfaces:**
- Consumes: `ProbeNode` (A3).
- Produces:
  - `enum InspectedView.Stability { STABLE, DYNAMIC, UNRESOLVED }`
  - `InspectedView.of(ProbeNode node, String activityClassName)` → `InspectedView`
  - `InspectedView#entryName()`, `#className()`, `#stability()`, `#idHex()`, `#parentChain()`, `#targetsAncestor()`
  - `SelectorBuilder.build(InspectedView view)` → `String`
  - `SelectorBuilder.ruleBlock(InspectedView view)` → `String`

**Referência obrigatória:** o dialeto vem de `CustomView.buildRuleMaps()` (`app/src/main/java/com/waenhancer/xposed/features/customization/CustomView.java`, linhas ~240-330). Reler antes de implementar.

- [ ] **Step 1: Write the failing test**

```java
package com.waenhancer.xposed.features.devtools;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.Test;

/**
 * O seletor emitido tem que casar com CustomView.buildRuleMaps(). Um seletor que o motor
 * descarta em silêncio é o pior defeito possível desta feature: o usuário cola a regra, nada
 * acontece, e a culpa cai no CSS.
 */
public class SelectorBuilderTest {

    private static final String HOME = "com.whatsapp.HomeActivity";

    private static final class Node implements ProbeNode {
        String entry;
        String pkg = "com.whatsapp";
        String cls = "com.whatsapp.TextEmojiLabel";
        ProbeNode parent;
        final List<ProbeNode> kids = new ArrayList<>();

        Node(String entry) { this.entry = entry; }

        Node under(Node p) { this.parent = p; p.kids.add(this); return this; }

        @Override public int left() { return 0; }
        @Override public int top() { return 0; }
        @Override public int right() { return 100; }
        @Override public int bottom() { return 100; }
        @Override public boolean visible() { return true; }
        @Override public float alpha() { return 1f; }
        @Override public List<ProbeNode> children() { return kids; }
        @Override public ProbeNode parent() { return parent; }
        @Override public String entryName() { return entry; }
        @Override public String resourcePackage() { return pkg; }
        @Override public int id() { return entry == null ? -1 : 0x7f0a0001; }
        @Override public String className() { return cls; }
        @Override public String contentDescription() { return null; }
    }

    /** O caso comum: id do WhatsApp, seletor de um nível, escopado pela Activity. */
    @Test
    public void aStableIdBecomesASingleLevelSelector() {
        InspectedView view = InspectedView.of(new Node("conversations_row_contact_name"), HOME);
        assertEquals(InspectedView.Stability.STABLE, view.stability());
        assertEquals(".com_whatsapp_HomeActivity #conversations_row_contact_name",
                SelectorBuilder.build(view));
        assertFalse(view.targetsAncestor());
    }

    /** Pontos viram underscore porque é assim que o parser lê o nome da classe. */
    @Test
    public void theActivityClassNameUsesUnderscores() {
        InspectedView view = InspectedView.of(new Node("footer"), "com.whatsapp.conversation.ConversationActivity");
        assertEquals(".com_whatsapp_conversation_ConversationActivity #footer",
                SelectorBuilder.build(view));
    }

    /** android.R.id.* não passa por Utils.getID: o parser resolve por reflexão em android.R.id. */
    @Test
    public void androidIdsUseTheAndroidPrefix() {
        Node node = new Node("content");
        node.pkg = "android";
        InspectedView view = InspectedView.of(node, HOME);
        assertEquals(".com_whatsapp_HomeActivity #android_content", SelectorBuilder.build(view));
    }

    /** Id de outro pacote muda entre versões: o seletor sai, mas marcado. */
    @Test
    public void aThirdPartyIdIsDynamic() {
        Node node = new Node("design_bottom_sheet");
        node.pkg = "com.google.android.material";
        InspectedView view = InspectedView.of(node, HOME);
        assertEquals(InspectedView.Stability.DYNAMIC, view.stability());
    }

    /** Sem id não há seletor possível: sobe até o ancestral estável e diz que subiu. */
    @Test
    public void anUnresolvedIdClimbsToTheNearestStableAncestor() {
        Node ancestor = new Node("conversations_row");
        Node anonymous = new Node(null).under(ancestor);
        InspectedView view = InspectedView.of(anonymous, HOME);
        assertEquals(InspectedView.Stability.UNRESOLVED, view.stability());
        assertTrue(view.targetsAncestor());
        assertEquals(".com_whatsapp_HomeActivity #conversations_row", SelectorBuilder.build(view));
    }

    /** Dois níveis anônimos: continua subindo. */
    @Test
    public void climbingSkipsEveryAnonymousLevel() {
        Node ancestor = new Node("conversations_row");
        Node middle = new Node(null).under(ancestor);
        Node anonymous = new Node(null).under(middle);
        InspectedView view = InspectedView.of(anonymous, HOME);
        assertEquals(".com_whatsapp_HomeActivity #conversations_row", SelectorBuilder.build(view));
    }

    /** Nada estável na cadeia inteira: sem seletor, e o painel tem que dizer isso. */
    @Test
    public void aTreeWithNoStableAncestorProducesNoSelector() {
        Node anonymous = new Node(null);
        InspectedView view = InspectedView.of(anonymous, HOME);
        assertEquals("", SelectorBuilder.build(view));
    }

    /** Activity desconhecida: seletor sem escopo continua válido para o parser. */
    @Test
    public void anUnknownActivityProducesAnUnscopedSelector() {
        InspectedView view = InspectedView.of(new Node("footer"), null);
        assertEquals("#footer", SelectorBuilder.build(view));
    }

    @Test
    public void theRuleBlockWrapsTheSelector() {
        InspectedView view = InspectedView.of(new Node("footer"), HOME);
        assertEquals(".com_whatsapp_HomeActivity #footer {\n    \n}",
                SelectorBuilder.ruleBlock(view));
    }

    @Test
    public void anEmptySelectorProducesAnEmptyRuleBlock() {
        assertEquals("", SelectorBuilder.ruleBlock(InspectedView.of(new Node(null), HOME)));
    }

    @Test
    public void theIdIsExposedInHex() {
        InspectedView view = InspectedView.of(new Node("footer"), HOME);
        assertEquals("0x7f0a0001", view.idHex());
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testWhatsappDebugUnitTest --tests "*SelectorBuilderTest"`
Expected: FAIL — `InspectedView` e `SelectorBuilder` não existem.

- [ ] **Step 3: Write minimal implementation**

`InspectedView.java`:

```java
package com.waenhancer.xposed.features.devtools;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Uma view capturada, já reduzida ao que pode ser mostrado.
 *
 * <p>Note o que não está aqui: texto. O ProbeNode nem sequer expõe getText(), e esta classe não
 * tem campo para ele. A ausência é a garantia.</p>
 */
public final class InspectedView {

    public enum Stability {
        /** Id do próprio WhatsApp: o seletor deve durar. */
        STABLE,
        /** Id de biblioteca: existe, mas muda entre versões. */
        DYNAMIC,
        /** Sem id: não há seletor possível para esta view. */
        UNRESOLVED
    }

    private static final String WHATSAPP_PACKAGE = "com.whatsapp";
    private static final String ANDROID_PACKAGE = "android";

    private final String entryName;
    private final String resourcePackage;
    private final String className;
    private final int id;
    private final String activityClassName;
    private final Stability stability;
    private final boolean targetsAncestor;
    private final List<String> parentChain;

    private InspectedView(String entryName, String resourcePackage, String className, int id,
            String activityClassName, Stability stability, boolean targetsAncestor,
            List<String> parentChain) {
        this.entryName = entryName;
        this.resourcePackage = resourcePackage;
        this.className = className;
        this.id = id;
        this.activityClassName = activityClassName;
        this.stability = stability;
        this.targetsAncestor = targetsAncestor;
        this.parentChain = Collections.unmodifiableList(parentChain);
    }

    public static InspectedView of(ProbeNode node, String activityClassName) {
        List<String> chain = new ArrayList<>();
        for (ProbeNode p = node.parent(); p != null; p = p.parent()) {
            chain.add(p.entryName() == null ? p.className() : p.entryName());
        }

        Stability stability = stabilityOf(node);
        if (stability != Stability.UNRESOLVED) {
            return new InspectedView(node.entryName(), node.resourcePackage(), node.className(),
                    node.id(), activityClassName, stability, false, chain);
        }

        // Sem id próprio: sobe até o ancestral mais próximo que tenha um.
        for (ProbeNode p = node.parent(); p != null; p = p.parent()) {
            if (stabilityOf(p) != Stability.UNRESOLVED) {
                return new InspectedView(p.entryName(), p.resourcePackage(), node.className(),
                        p.id(), activityClassName, Stability.UNRESOLVED, true, chain);
            }
        }
        return new InspectedView(null, null, node.className(), node.id(), activityClassName,
                Stability.UNRESOLVED, false, chain);
    }

    private static Stability stabilityOf(ProbeNode node) {
        String entry = node.entryName();
        if (entry == null || entry.isEmpty()) return Stability.UNRESOLVED;
        String pkg = node.resourcePackage();
        if (WHATSAPP_PACKAGE.equals(pkg) || ANDROID_PACKAGE.equals(pkg)) return Stability.STABLE;
        return Stability.DYNAMIC;
    }

    public String entryName() { return entryName; }

    public String resourcePackage() { return resourcePackage; }

    public String className() { return className; }

    public String activityClassName() { return activityClassName; }

    public Stability stability() { return stability; }

    /** True quando o seletor mira um ancestral porque a view tocada não tem id. */
    public boolean targetsAncestor() { return targetsAncestor; }

    public List<String> parentChain() { return parentChain; }

    public String idHex() {
        return entryName == null ? "" : String.format("0x%08x", id);
    }
}
```

`SelectorBuilder.java`:

```java
package com.waenhancer.xposed.features.devtools;

/**
 * Gera a string que CustomView.buildRuleMaps() sabe indexar.
 *
 * <p>O dialeto não é CSS genérico. A primeira parte com className vira o escopo de Activity
 * (CachedRuleItem.targetActivityClassName), os pontos do nome viram underscore, e ids de
 * android.R.id usam o prefixo android_ porque o parser os resolve por reflexão em vez de
 * Utils.getID. Ver CustomView.java, linhas ~240-330.</p>
 */
public final class SelectorBuilder {

    private SelectorBuilder() {
    }

    public static String build(InspectedView view) {
        String entry = view.entryName();
        if (entry == null || entry.isEmpty()) return "";

        String id = "android".equals(view.resourcePackage()) ? "android_" + entry : entry;
        String activity = view.activityClassName();
        if (activity == null || activity.isEmpty()) {
            return "#" + id;
        }
        return "." + activity.replace('.', '_') + " #" + id;
    }

    /** O bloco pronto para colar no editor de CSS. */
    public static String ruleBlock(InspectedView view) {
        String selector = build(view);
        if (selector.isEmpty()) return "";
        return selector + " {\n    \n}";
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testWhatsappDebugUnitTest --tests "*SelectorBuilderTest"`
Expected: PASS, 11 testes.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/waenhancer/xposed/features/devtools/InspectedView.java app/src/main/java/com/waenhancer/xposed/features/devtools/SelectorBuilder.java app/src/test/java/com/waenhancer/xposed/features/devtools/SelectorBuilderTest.java
git commit -m "feat: generate selectors in the dialect CustomView indexes

The CSS engine is not generic: buildRuleMaps scopes by activity class
with dots turned into underscores, resolves android.R.id by reflection
behind an android_ prefix, and silently drops rules whose id does not
resolve. A view with no id climbs to its nearest identified ancestor and
says so, because a selector aiming at the wrong target without warning is
worse than no selector."
```

---

### Task A5: pref de sessão e verificação da Parte A

**Files:**
- Modify: `app/src/main/java/com/waenhancer/config/PreferenceSchema.java` (inserir na ordem alfabética, perto da linha 286)
- Test: `app/src/test/java/com/waenhancer/xposed/features/devtools/InspectorPrefContractTest.java`

**Interfaces:**
- Consumes: nada.
- Produces: a chave `inspector_session` registrada no schema, consumida pela Parte B.

- [ ] **Step 1: Write the failing test**

```java
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
        PreferenceSchema.Entry entry = PreferenceSchema.find("inspector_session");
        assertNotNull("inspector_session must be registered in PreferenceSchema", entry);
        assertEquals(PreferenceSchema.Type.STRING, entry.type());
        assertEquals(PreferenceSchema.Store.PUBLIC, entry.store());
        assertEquals(PreferenceSchema.Sensitivity.PUBLIC_SETTING, entry.sensitivity());
    }
}
```

> **Nota ao implementador:** os nomes `PreferenceSchema.find`, `Entry`, `type()`, `store()` e
> `sensitivity()` são a API esperada. **Antes de escrever este teste, abra
> `app/src/main/java/com/waenhancer/config/PreferenceSchema.java` e use os nomes reais.** Se a
> classe expuser um acessor diferente (por exemplo `getEntry` ou campos públicos), adapte o teste
> — não adapte a classe.

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testWhatsappDebugUnitTest --tests "*InspectorPrefContractTest"`
Expected: FAIL — a chave não está registrada.

- [ ] **Step 3: Register the key**

Em `PreferenceSchema.java`, na posição alfabética correta (entre as chaves iniciadas por `i`), na mesma forma das linhas vizinhas:

```java
add(entries, "inspector_session", Type.STRING, Sensitivity.PUBLIC_SETTING, Store.PUBLIC);
```

- [ ] **Step 4: Run the full suite**

Run: `./gradlew :app:compileWhatsappDebugJavaWithJavac :app:testWhatsappDebugUnitTest`
Expected: PASS, incluindo todas as suítes pré-existentes.

- [ ] **Step 5: Verify the privacy invariant by grep**

```bash
grep -rn "getText()" app/src/main/java/com/waenhancer/xposed/features/devtools/
```

Expected: **nenhum resultado.** Se houver, a Parte A não está pronta.

- [ ] **Step 6: Commit**

```bash
git add app/src/main/java/com/waenhancer/config/PreferenceSchema.java app/src/test/java/com/waenhancer/xposed/features/devtools/InspectorPrefContractTest.java
git commit -m "feat: register inspector_session preference

Store.PUBLIC because the hook reads it inside the WhatsApp process. It
holds only a token and an expiry, so it stays compatible with §5.4's ban
on secrets in the XSharedPreferences-readable file."
```

---

## Handoff da Parte A para a Parte B

Ao terminar a Task A5, produzir o handoff que o §1A.3 do plano-mestre exige: commits, arquivos, testes rodados, riscos, invariantes, estado da branch. As invariantes que a Parte B **não pode** quebrar:

1. nenhuma classe de `devtools` chama `getText()`;
2. nada é persistido além de `inspector_session`;
3. `ViewProbe` recebe bounds **recortadas**, não nominais — o adaptador é responsável;
4. `InspectorSession` é imutável; `touched()` devolve instância nova;
5. o seletor de `SelectorBuilder` é a saída; a Parte B **exibe**, não reescreve.

---

# PARTE B — Sonnet 5

**Por que Sonnet:** daqui para baixo é encanamento Android com um ciclo de build-instala-observa. Nenhuma decisão de privacidade ou de correção de seletor sobrou — todas foram resolvidas e travadas por teste na Parte A. O trabalho é adaptar `View` para `ProbeNode`, gerenciar uma janela e desenhar um painel, que é exatamente o tipo de iteração mecânica que o §1A.2 atribui ao Sonnet.

**Nada nesta parte é testável na JVM.** Por isso a Parte A carregou toda a lógica: se a divisão fosse outra, a maior parte da feature ficaria sem cobertura.

---

### Task B1: `ViewNode` — o adaptador

**Files:**
- Create: `app/src/main/java/com/waenhancer/xposed/features/devtools/ViewNode.java`

**Interfaces:**
- Consumes: `ProbeNode` (A3).
- Produces: `ViewNode.of(View view)` → `ProbeNode`.

- [ ] **Step 1: Implement the adapter**

```java
package com.waenhancer.xposed.features.devtools;

import android.content.res.Resources;
import android.graphics.Rect;
import android.view.View;
import android.view.ViewGroup;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Converte uma View em ProbeNode. Deliberadamente burro: toda decisão vive no ViewProbe, que é
 * testável. A única regra aqui é usar getGlobalVisibleRect, e não getHitRect, para que uma view
 * recortada pelo scroll não seja acertada.
 */
public final class ViewNode implements ProbeNode {

    private final View view;
    private final Rect visible = new Rect();
    private final boolean onScreen;

    private ViewNode(View view) {
        this.view = view;
        this.onScreen = view.getGlobalVisibleRect(visible);
    }

    public static ProbeNode of(View view) {
        return view == null ? null : new ViewNode(view);
    }

    @Override public int left() { return onScreen ? visible.left : 0; }

    @Override public int top() { return onScreen ? visible.top : 0; }

    @Override public int right() { return onScreen ? visible.right : 0; }

    @Override public int bottom() { return onScreen ? visible.bottom : 0; }

    @Override public boolean visible() {
        return onScreen && view.getVisibility() == View.VISIBLE;
    }

    @Override public float alpha() { return view.getAlpha(); }

    @Override public List<ProbeNode> children() {
        if (!(view instanceof ViewGroup)) return Collections.emptyList();
        ViewGroup group = (ViewGroup) view;
        List<ProbeNode> kids = new ArrayList<>(group.getChildCount());
        for (int i = 0; i < group.getChildCount(); i++) {
            kids.add(new ViewNode(group.getChildAt(i)));
        }
        return kids;
    }

    @Override public ProbeNode parent() {
        return view.getParent() instanceof View ? new ViewNode((View) view.getParent()) : null;
    }

    @Override public String entryName() {
        int id = view.getId();
        if (id == View.NO_ID) return null;
        try {
            return view.getResources().getResourceEntryName(id);
        } catch (Resources.NotFoundException e) {
            return null;
        }
    }

    @Override public String resourcePackage() {
        int id = view.getId();
        if (id == View.NO_ID) return null;
        try {
            return view.getResources().getResourcePackageName(id);
        } catch (Resources.NotFoundException e) {
            return null;
        }
    }

    @Override public int id() { return view.getId(); }

    @Override public String className() { return view.getClass().getName(); }

    @Override public String contentDescription() {
        CharSequence description = view.getContentDescription();
        return description == null ? null : description.toString();
    }

    /** A View original, para o overlay desenhar a borda de destaque sobre ela. */
    public View view() { return view; }
}
```

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew :app:compileWhatsappDebugJavaWithJavac`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Verify the privacy invariant still holds**

```bash
grep -rn "getText()" app/src/main/java/com/waenhancer/xposed/features/devtools/
```

Expected: nenhum resultado.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/waenhancer/xposed/features/devtools/ViewNode.java
git commit -m "feat: adapt View to ProbeNode for the inspector hit-test

Uses getGlobalVisibleRect rather than getHitRect so a view clipped by
scrolling is not hit. All decisions stay in ViewProbe, which is tested."
```

---

### Task B2: `InspectorOverlay` — a janela e o modo

**Files:**
- Create: `app/src/main/java/com/waenhancer/xposed/features/devtools/InspectorOverlay.java`
- Create: `app/src/main/res/layout/inspector_panel.xml`

**Interfaces:**
- Consumes: `InspectorSession` (A1), `ViewProbe` (A3), `ViewNode` (B1), `InspectedView` (A4).
- Produces: `new InspectorOverlay(Activity, Runnable onExit)`, `void attach()`, `void detach()`, `void setMode(Mode)`, `enum Mode { NAVIGATE, PICK }`.

- [ ] **Step 1: Build the window skeleton**

O essencial, com os pontos que decidem se a feature vaza janela ou não:

```java
private static final int FLAGS_NAVIGATE =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

private static final int FLAGS_PICK =
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;

private WindowManager.LayoutParams params(int flags) {
    WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION,
            flags,
            PixelFormat.TRANSLUCENT);
    // O token da janela do Activity é o que dispensa SYSTEM_ALERT_WINDOW.
    lp.token = activity.getWindow().getDecorView().getWindowToken();
    return lp;
}

public void setMode(Mode mode) {
    this.mode = mode;
    if (root == null) return;
    windowManager.updateViewLayout(root, params(mode == Mode.PICK ? FLAGS_PICK : FLAGS_NAVIGATE));
}

public void detach() {
    if (root == null) return;
    try {
        windowManager.removeViewImmediate(root);
    } catch (IllegalArgumentException alreadyGone) {
        // A janela já foi levada junto com o Activity. Não é erro.
    }
    root = null;
}
```

**O handle flutuante fica fora da alternância de flag.** Ele é um `View` filho do `root`; em `NAVIGATE` a janela inteira é `NOT_TOUCHABLE`, então o handle também não recebe toque. Solução: **duas janelas** — uma de conteúdo (borda + painel), cuja flag alterna, e uma pequena só do handle, sempre tocável. É a razão de `attach()` adicionar duas views e `detach()` remover as duas.

- [ ] **Step 2: Wire selection**

No `onTouchEvent` da janela de conteúdo, quando `mode == PICK`:

```java
ProbeNode root = ViewNode.of(activity.getWindow().getDecorView());
ProbeNode hit = ViewProbe.hit(root, (int) event.getRawX(), (int) event.getRawY());
if (hit != null) {
    session = session.touched(System.currentTimeMillis());
    show(InspectedView.of(hit, activity.getClass().getName()));
}
return true; // consome sempre em PICK
```

- [ ] **Step 3: Build the panel layout**

`inspector_panel.xml`: um `CardView` no rodapé com os três blocos do §8 da spec (identidade, geometria, contexto), o seletor em `monospace`, e os sete botões. O `contentDescription` é exibido via `Redactor.redact(...)`, com o botão `Revelar` visível apenas quando o valor foi redigido.

- [ ] **Step 4: Verify on device**

Instalar e confirmar, nesta ordem:
1. em `NAVIGATE` o WhatsApp funciona normalmente (rolar, abrir conversa, long-press nativo);
2. em `PICK` um toque seleciona e o WhatsApp não reage;
3. trocar de Activity e voltar não deixa `WindowLeaked` no logcat:
   `adb logcat -d | grep -i "WindowLeaked\|has leaked window"` → nada;
4. girar a tela e entrar em split-screen reancora a janela.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/waenhancer/xposed/features/devtools/InspectorOverlay.java app/src/main/res/layout/inspector_panel.xml
git commit -m "feat: add inspector overlay window and pick mode

A TYPE_APPLICATION window anchored to the activity's window token, so no
SYSTEM_ALERT_WINDOW permission and no mutation of WhatsApp's view tree.
Mode switching is a FLAG_NOT_TOUCHABLE change; the drag handle lives in
its own always-touchable window because the content window goes
untouchable in navigate mode."
```

---

### Task B3: `InspectorFeature` — o hook de ciclo de vida

**Files:**
- Create: `app/src/main/java/com/waenhancer/xposed/features/devtools/InspectorFeature.java`
- Modify: `app/src/main/java/com/waenhancer/xposed/core/FeatureLoader.java` (import + entrada no array `classes` de `plugins()`)

**Interfaces:**
- Consumes: `InspectorSession` (A1), `InspectorOverlay` (B2), a pref `inspector_session` (A5).
- Produces: a feature registrada.

- [ ] **Step 1: Implement the feature**

Pontos obrigatórios:

```java
@Override
public void doHook() throws Throwable {
    if (parseSession(prefs.getString("inspector_session", "")) == null) return; // nada armado: nenhum listener

    WppCore.addListenerActivity((activity, type) -> {
        InspectorSession current = parseSession(prefs.getString("inspector_session", ""));
        if (current == null || !current.isActive(System.currentTimeMillis())) {
            detach();
            return;
        }
        switch (type) {
            case RESUMED -> attachTo(activity);
            case PAUSED, DESTROYED -> detach();
        }
    });
}
```

**A invariante do §6 da spec é esta linha:** com a pref vazia, `doHook` retorna antes de registrar qualquer listener. Não basta o overlay não aparecer — o listener não pode existir.

- [ ] **Step 2: Register the feature**

Em `FeatureLoader.java`, adicionar o import e a entrada no array `classes` de `plugins()`. Ordem no array é irrelevante — as features são carregadas com `CompletableFuture.runAsync` num work-stealing pool, e esta feature não tem dependência de ordem com nenhuma outra.

- [ ] **Step 3: Verify on device**

1. com a inspeção desligada: `adb shell dumpsys activity` não mostra janela extra, e nada do inspector aparece no logcat;
2. armar no módulo → a janela aparece **sem reiniciar o WhatsApp** (é o `ContentObserver` do `ProviderSharedPreferences`);
3. deixar 10 minutos ocioso → a sessão expira e a janela some sozinha;
4. `Exit inspector` → some na hora e a pref volta a vazia.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/waenhancer/xposed/features/devtools/InspectorFeature.java app/src/main/java/com/waenhancer/xposed/core/FeatureLoader.java
git commit -m "feat: register inspector feature with lifecycle hooks

With the preference empty, doHook returns before registering any
listener: the §11.3 requirement is not that the overlay is hidden but
that no permanent hook exists."
```

---

### Task B4: UI do módulo e as sete ações

**Files:**
- Modify: `app/src/main/java/com/waenhancer/activities/MainActivity.java`
- Create: `app/src/main/java/com/waenhancer/xposed/features/devtools/InspectorClipboard.java`

**Interfaces:**
- Consumes: `SelectorBuilder` (A4), `InspectedView` (A4).
- Produces: `InspectorClipboard.copy(Context, String label, String value)`.

- [ ] **Step 1: Implement the clipboard actions**

```java
public static void copy(Context context, String label, String value) {
    if (value == null || value.isEmpty()) {
        Toast.makeText(context, R.string.inspector_nothing_to_copy, Toast.LENGTH_SHORT).show();
        return;
    }
    ClipboardManager clipboard = (ClipboardManager) context.getSystemService(Context.CLIPBOARD_SERVICE);
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value));
    Toast.makeText(context, context.getString(R.string.inspector_copied, label), Toast.LENGTH_SHORT).show();
}
```

O `Toast` não é enfeite: um clipboard silencioso é indistinguível de um botão quebrado.

- [ ] **Step 2: Add the module toggle**

Em `MainActivity`: `Identify UI Elements` gera token com `SecureRandom`, grava `inspector_session` como `token|expiraEm`, e oferece `Open WhatsApp`. Desligar grava `""`.

- [ ] **Step 3: Verify the full loop on device**

Armar → abrir WhatsApp → `PICK` → tocar num nome de conversa → `Copy selector` → colar no `TextEditorActivity` → **a regra tem efeito**. Este é o teste que fecha a feature: se o seletor não pegar, o `SelectorBuilder` está errado, não o CSS.

- [ ] **Step 4: Commit**

```bash
git add -A
git commit -m "feat: add inspector toggle and clipboard actions

Every copy confirms with a Toast, because a silent clipboard is
indistinguishable from a broken button."
```

---

### Task B5: Gate F2

- [ ] **Step 1: Full build and suite**

Run: `./gradlew :app:compileWhatsappDebugJavaWithJavac :app:testWhatsappDebugUnitTest`
Expected: PASS, sem regressão nas suítes pré-existentes.

- [ ] **Step 2: Privacy grep**

```bash
grep -rn "getText()\|getPrimaryClip()" app/src/main/java/com/waenhancer/xposed/features/devtools/
```

Expected: nenhum resultado. (`getPrimaryClip` entra porque **ler** o clipboard nunca é necessário aqui.)

- [ ] **Step 3: Device matrix (§12 da spec)**

- o seletor emitido casa com `buildRuleMaps()` (verificado colando a regra);
- nenhuma `WindowLeaked` ao trocar de Activity, girar e entrar em split-screen;
- `NAVIGATE` deixa o WhatsApp utilizável;
- a sessão expira em 10 min e não deixa listener ativo.

- [ ] **Step 4: Write the completion handoff**

Acrescentar a `HANDOFF_F2_ELEMENT_INSPECTOR.md` uma seção `#### Estado da F2 — implementada`, no molde do que a F1 fez em `HANDOFF_LIQUID_GLASS.md`: o que foi medido, o que ficou pendente, e as invariantes que o próximo bloco tem que preservar.

- [ ] **Step 5: Commit**

```bash
git add HANDOFF_F2_ELEMENT_INSPECTOR.md
git commit -m "docs: record F2 implementation state and remaining device work"
```
