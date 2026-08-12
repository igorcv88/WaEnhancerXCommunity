# You Tab Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Um item `You` no bottom nav do WhatsApp, com a foto de perfil do usuário como ícone, que abre a tela de perfil sem virar uma página do `ViewPager`.

**Architecture:** Uma `Feature` nova com quatro hooks — três com precedente direto em `SeparateGroup.java`, um novo. A aba é um **atalho**, não uma página: nenhum Fragment é criado. A convivência com `HideTabs` é resolvida por prioridade de hook do Xposed, não por ordem de registro.

**Tech Stack:** Java 17, Xposed/LSPosed, DexKit (via `Unobfuscator`), JUnit 4 (sem Robolectric, sem Mockito).

**Spec:** `HANDOFF_F3_YOU_TAB.md`

## Global Constraints

- **`YOU_TAB = 700`.** IDs ocupados: `200` CHATS, `300` STATUS, `500` GROUPS, `1000` Meta AI, `1100` companheira.
- **Inserir sempre no fim da lista.** É o que preserva os índices de todas as abas nativas (§16 do plano-mestre).
- **Falhar desligada.** Cada hook em seu próprio `try/catch` com `XposedBridge.log`, no molde de `SeparateGroup`. Um `Unobfuscator` que lance não pode derrubar o WhatsApp — só faz a aba não aparecer.
- **A aba `You` nunca entra em `hidetabs`.** Escondê-la é desligar a feature.
- **Testes na JVM apenas.** JUnit 4, sem Robolectric e sem Mockito.
- **Pacote:** `com.waenhancer.xposed.features.customization`.
- **Build/teste:** `./gradlew :app:compileWhatsappDebugJavaWithJavac :app:testWhatsappDebugUnitTest`

---

# PARTE A — Opus 5

**Por que Opus:** a única coisa perigosa desta fase não é adicionar a aba — é **não quebrar a navegação existente**. `HideTabs.getNewTabIndex()` assume que a lista de abas só encolhe, e a análise de por que a ordem entre os dois hooks importa, mais a escolha do mecanismo que a garante, é raciocínio sobre invariante compartilhada entre duas features. Errar aqui produz um bug intermitente que depende de escalonamento de thread — o tipo mais caro de encontrar. Também é aqui que fica a política de posicionamento e a guarda de colisão de ID, ambas testáveis.

**Entrega da Parte A:** a política de posicionamento e a de avatar, ambas puras e testadas; a prioridade de hook em `HideTabs` alterada e justificada; **nenhum hook novo instalado**. O app compila e se comporta como antes.

---

### Task A1: `YouTabPlacement` — onde a aba entra, e quando não entra

**Files:**
- Create: `app/src/main/java/com/waenhancer/xposed/features/customization/YouTabPlacement.java`
- Test: `app/src/test/java/com/waenhancer/xposed/features/customization/YouTabPlacementTest.java`

**Interfaces:**
- Consumes: nada.
- Produces: `YouTabPlacement.YOU_TAB` (`700`), `YouTabPlacement.insert(List<Integer> tabs)` → `boolean` (true se inseriu), `YouTabPlacement.wouldCollide(List<Integer> tabs)` → `boolean`.

- [ ] **Step 1: Write the failing test**

```java
package com.waenhancer.xposed.features.customization;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.Test;

/**
 * Onde a aba You entra na lista do WhatsApp.
 *
 * <p>O índice de cada aba nativa é a posição do filho no ViewPager. Qualquer inserção que não
 * seja no fim renumera abas que o resto do app já sabe abrir, e o sintoma é tocar em Calls e
 * cair em Status.</p>
 */
public class YouTabPlacementTest {

    private static List<Integer> tabs(Integer... ids) {
        return new ArrayList<>(Arrays.asList(ids));
    }

    /** Chats=200, Status=300, Calls=400: nenhum deles pode mudar de índice. */
    @Test
    public void insertingGoesToTheEndAndPreservesEveryNativeIndex() {
        List<Integer> tabs = tabs(200, 300, 400);
        assertTrue(YouTabPlacement.insert(tabs));
        assertEquals(Arrays.asList(200, 300, 400, YouTabPlacement.YOU_TAB), tabs);
        assertEquals(0, tabs.indexOf(200));
        assertEquals(1, tabs.indexOf(300));
        assertEquals(2, tabs.indexOf(400));
    }

    /** O hook pode disparar mais de uma vez por recriação da HomeActivity. */
    @Test
    public void insertingTwiceDoesNotDuplicate() {
        List<Integer> tabs = tabs(200, 300);
        assertTrue(YouTabPlacement.insert(tabs));
        assertFalse(YouTabPlacement.insert(tabs));
        assertEquals(Arrays.asList(200, 300, YouTabPlacement.YOU_TAB), tabs);
    }

    @Test
    public void anEmptyListStillReceivesTheTab() {
        List<Integer> tabs = tabs();
        assertTrue(YouTabPlacement.insert(tabs));
        assertEquals(Arrays.asList(YouTabPlacement.YOU_TAB), tabs);
    }

    @Test
    public void aNullListIsIgnoredInsteadOfThrowing() {
        assertFalse(YouTabPlacement.insert(null));
    }

    /**
     * Se uma versão futura do WhatsApp usar 700 para uma aba própria, inserir sequestraria uma
     * aba nativa. Nesse caso a feature aborta em vez de competir.
     */
    @Test
    public void aPreexistingSevenHundredIsACollision() {
        assertTrue(YouTabPlacement.wouldCollide(tabs(200, 300, 700)));
        assertFalse(YouTabPlacement.wouldCollide(tabs(200, 300)));
    }

    @Test
    public void insertingIntoACollidingListDoesNothing() {
        List<Integer> tabs = tabs(200, 700);
        assertFalse(YouTabPlacement.insert(tabs));
        assertEquals(Arrays.asList(200, 700), tabs);
    }

    /** O ID escolhido não pode colidir com nenhum ID conhecido. */
    @Test
    public void theTabIdDoesNotCollideWithKnownIds() {
        assertEquals(700, YouTabPlacement.YOU_TAB);
        assertFalse(Arrays.asList(200, 300, 400, 500, 1000, 1100).contains(YouTabPlacement.YOU_TAB));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testWhatsappDebugUnitTest --tests "*YouTabPlacementTest"`
Expected: FAIL — `YouTabPlacement` não existe.

- [ ] **Step 3: Write minimal implementation**

```java
package com.waenhancer.xposed.features.customization;

import java.util.List;

/**
 * A política de posicionamento da aba You, separada dos hooks para poder ser testada.
 *
 * <p>Inserir no fim não é preferência estética: o índice de cada aba é a posição do filho no
 * ViewPager, e o §16 do plano-mestre exige preservar os índices das abas do WhatsApp. O
 * SeparateGroup insere na posição 1 porque quer a aba de grupos ao lado de Chats; aqui o
 * requisito é o oposto.</p>
 */
public final class YouTabPlacement {

    /** Livre entre os IDs conhecidos: 200 chats, 300 status, 400 calls, 500 grupos, 1000/1100 Meta AI. */
    public static final int YOU_TAB = 700;

    private YouTabPlacement() {
    }

    /**
     * O WhatsApp já usa este ID? Se usar, a feature tem que abortar: inserir sequestraria uma
     * aba nativa em vez de criar uma nova.
     */
    public static boolean wouldCollide(List<Integer> tabs) {
        return tabs != null && tabs.contains(YOU_TAB);
    }

    /** @return true se inseriu; false se já estava lá, se houve colisão, ou se a lista é nula. */
    public static boolean insert(List<Integer> tabs) {
        if (tabs == null || wouldCollide(tabs)) return false;
        tabs.add(YOU_TAB);
        return true;
    }
}
```

Nota: `insert` devolve `false` tanto para colisão quanto para "já inserido", porque `wouldCollide` passa a ser verdadeiro depois da primeira inserção. A distinção entre os dois casos é feita pelo chamador com `wouldCollide` **antes** da primeira inserção — ver Task B1.

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testWhatsappDebugUnitTest --tests "*YouTabPlacementTest"`
Expected: PASS, 7 testes.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/waenhancer/xposed/features/customization/YouTabPlacement.java app/src/test/java/com/waenhancer/xposed/features/customization/YouTabPlacementTest.java
git commit -m "feat: add You tab placement policy

Inserting at the end is not taste: each tab's index is its ViewPager
child position, and §16 requires preserving WhatsApp's tab indices.
Aborts when the id is already present, so a future WhatsApp tab using
700 is not hijacked."
```

---

### Task A2: prioridade de hook em `HideTabs`

**Files:**
- Modify: `app/src/main/java/com/waenhancer/xposed/features/customization/HideTabs.java:73`

**Interfaces:**
- Consumes: nada.
- Produces: a garantia de ordem que a Task B1 depende.

**Contexto obrigatório antes de editar:** `FeatureLoader.plugins()` dispara cada feature com `CompletableFuture.runAsync` num work-stealing pool. **A ordem do array `classes` não determina a ordem de execução.** O mecanismo determinístico é a prioridade do `XC_MethodHook`, que ordena hooks no mesmo método independentemente de quem instalou primeiro.

- [ ] **Step 1: Make the priority explicit**

Em `HideTabs.doHook()`, o hook em `onCreateTabList` (linha 73) passa a declarar prioridade explícita:

```java
XposedBridge.hookMethod(onCreateTabList, new XC_MethodHook(XCallback.PRIORITY_DEFAULT) {
    // Prioridade explícita, e não o default implícito, porque YouTab hooka o mesmo método com
    // PRIORITY_HIGHEST para inserir a aba You antes. Esta feature precisa ver a lista já
    // completa: getNewTabIndex() indexa originalTabs, e originalTabs é sincronizado aqui.
    // Remover esta prioridade reintroduz uma corrida que depende de escalonamento de thread.
    @Override
    @SuppressWarnings("unchecked")
    protected void afterHookedMethod(MethodHookParam param) throws Throwable {
        // corpo inalterado
    }
});
```

Adicionar o import `de.robv.android.xposed.callbacks.XCallback`.

- [ ] **Step 2: Verify nothing else changed**

Run: `git diff app/src/main/java/com/waenhancer/xposed/features/customization/HideTabs.java`
Expected: apenas o import, o argumento de prioridade e o comentário. **Nenhuma linha de lógica.**

- [ ] **Step 3: Build and run the full suite**

Run: `./gradlew :app:compileWhatsappDebugJavaWithJavac :app:testWhatsappDebugUnitTest`
Expected: PASS.

- [ ] **Step 4: Verify on device that nothing regressed**

Com a aba `You` ainda inexistente, `HideTabs` deve funcionar exatamente como antes: esconder Status, esconder Meta AI, e navegar entre as abas restantes sem cair na errada. Este passo existe porque a mudança é num arquivo que já funciona.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/waenhancer/xposed/features/customization/HideTabs.java
git commit -m "refactor: make HideTabs tab-list hook priority explicit

No behaviour change on its own. YouTab will hook the same method at
PRIORITY_HIGHEST to insert its tab, and HideTabs must see the completed
list because getNewTabIndex indexes originalTabs, which is synchronised
here. Feature registration order cannot provide this: plugins() dispatches
features on a work-stealing pool."
```

---

### Task A3: `AvatarPolicy` — quando recarregar, quando desistir

**Files:**
- Create: `app/src/main/java/com/waenhancer/xposed/features/customization/AvatarPolicy.java`
- Test: `app/src/test/java/com/waenhancer/xposed/features/customization/AvatarPolicyTest.java`

**Interfaces:**
- Consumes: nada.
- Produces: `AvatarPolicy.shouldReload(long fileMtime, long cachedMtime)` → `boolean`, `AvatarPolicy.shouldFallback(long fileMtime)` → `boolean`, `AvatarPolicy.NO_FILE` (`0L`).

- [ ] **Step 1: Write the failing test**

```java
package com.waenhancer.xposed.features.customization;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * O ícone da aba é um bitmap recortado, e recortar a cada desenho seria caro. A política de
 * cache é a única parte testável disso, então ela carrega as regras.
 */
public class AvatarPolicyTest {

    @Test
    public void anUnchangedFileIsNotReloaded() {
        assertFalse(AvatarPolicy.shouldReload(1_700_000_000L, 1_700_000_000L));
    }

    @Test
    public void aNewerFileIsReloaded() {
        assertTrue(AvatarPolicy.shouldReload(1_700_000_001L, 1_700_000_000L));
    }

    /** Nada em cache ainda: primeira carga. */
    @Test
    public void anEmptyCacheIsAlwaysReloaded() {
        assertTrue(AvatarPolicy.shouldReload(1_700_000_000L, AvatarPolicy.NO_FILE));
    }

    /**
     * Foto trocada por uma mais antiga (restauração de backup) também tem que recarregar:
     * a regra é "diferente", não "mais novo".
     */
    @Test
    public void anOlderFileIsAlsoReloaded() {
        assertTrue(AvatarPolicy.shouldReload(1_600_000_000L, 1_700_000_000L));
    }

    @Test
    public void aMissingFileFallsBack() {
        assertTrue(AvatarPolicy.shouldFallback(AvatarPolicy.NO_FILE));
        assertFalse(AvatarPolicy.shouldFallback(1_700_000_000L));
    }

    /** Arquivo ausente não deve disparar recarga infinita contra o fallback. */
    @Test
    public void aMissingFileIsNotReloadedOnceCached() {
        assertFalse(AvatarPolicy.shouldReload(AvatarPolicy.NO_FILE, AvatarPolicy.NO_FILE));
    }
}
```

- [ ] **Step 2: Run test to verify it fails**

Run: `./gradlew :app:testWhatsappDebugUnitTest --tests "*AvatarPolicyTest"`
Expected: FAIL — `AvatarPolicy` não existe.

- [ ] **Step 3: Write minimal implementation**

```java
package com.waenhancer.xposed.features.customization;

/**
 * Quando o ícone da aba precisa ser recortado de novo.
 *
 * <p>A chave de cache é o mtime do arquivo, e a comparação é por diferença e não por "mais
 * novo": restaurar um backup pode instalar uma foto com mtime anterior, e um cache que só
 * aceitasse avanço mostraria a foto errada até a próxima recriação da HomeActivity.</p>
 */
public final class AvatarPolicy {

    /** Sentinela para "não há arquivo de foto". */
    public static final long NO_FILE = 0L;

    private AvatarPolicy() {
    }

    public static boolean shouldReload(long fileMtime, long cachedMtime) {
        return fileMtime != cachedMtime;
    }

    public static boolean shouldFallback(long fileMtime) {
        return fileMtime == NO_FILE;
    }
}
```

- [ ] **Step 4: Run test to verify it passes**

Run: `./gradlew :app:testWhatsappDebugUnitTest --tests "*AvatarPolicyTest"`
Expected: PASS, 6 testes.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/waenhancer/xposed/features/customization/AvatarPolicy.java app/src/test/java/com/waenhancer/xposed/features/customization/AvatarPolicyTest.java
git commit -m "feat: add avatar cache policy keyed on file mtime

Comparison is by difference, not by newer: restoring a backup can install
a photo with an earlier mtime, and a cache that only accepted advancing
timestamps would show the wrong photo until the home activity is
recreated."
```

---

## Handoff da Parte A para a Parte B

Invariantes que a Parte B **não pode** quebrar:

1. `YouTabPlacement.insert` sempre no fim; nunca no meio;
2. colisão de ID aborta a feature — não "insere assim mesmo";
3. `HideTabs` mantém `PRIORITY_DEFAULT` explícito, e `YouTab` **tem que** usar `PRIORITY_HIGHEST` no mesmo método;
4. o cache de avatar usa `AvatarPolicy`, não uma comparação inline.

---

# PARTE B — Sonnet 5

**Por que Sonnet:** os quatro hooks são reflexão sobre código ofuscado, e três deles são adaptação de código que já funciona em `SeparateGroup.java`. Nada aqui é testável na JVM; o ciclo é build-instala-observa, com uma matriz de verificação em aparelho. É trabalho mecânico com um exemplar ao lado — exatamente o que o §1A.2 atribui ao Sonnet.

---

### Task B1: `YouTab` — inserção, nome e ícone

**Files:**
- Create: `app/src/main/java/com/waenhancer/xposed/features/customization/YouTab.java`
- Create: `app/src/main/res/drawable/ic_you_tab_fallback.xml`

**Interfaces:**
- Consumes: `YouTabPlacement` (A1), `AvatarPolicy` (A3), `WppCore.getContactPhotoFile`, `Unobfuscator`.
- Produces: a feature (registrada na Task B3).

**Referência obrigatória:** `SeparateGroup.java`, `hookTabList()` (linha 502), `hookTabName()` (linha 325) e `hookTabIcon()` (linha 284). Ler antes de escrever.

- [ ] **Step 1: Implement hookTabList at highest priority**

```java
private void hookTabList() {
    try {
        Method onCreateTabList = Unobfuscator.loadTabListMethod(classLoader);
        // PRIORITY_HIGHEST porque HideTabs hooka o mesmo método em PRIORITY_DEFAULT e precisa
        // ver a lista já com a aba You: o getNewTabIndex() dele indexa originalTabs.
        XposedBridge.hookMethod(onCreateTabList, new XC_MethodHook(XCallback.PRIORITY_HIGHEST) {
            @SuppressWarnings("unchecked")
            @Override
            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                ArrayList<Integer> resultTabs = (ArrayList<Integer>) param.getResult();
                if (resultTabs == null) return;
                if (!inserted && YouTabPlacement.wouldCollide(resultTabs)) {
                    // O WhatsApp passou a usar 700. Abortar em vez de sequestrar a aba dele.
                    collided = true;
                    XposedBridge.log("[WAEX-YouTab] tab id 700 already in use; feature disabled");
                    return;
                }
                if (collided) return;
                inserted = YouTabPlacement.insert(resultTabs) || inserted;
                tabs = resultTabs;
            }
        });
    } catch (Throwable t) {
        XposedBridge.log("[WAEX-YouTab] hookTabList error: " + t);
    }
}
```

- [ ] **Step 2: Implement hookTabName**

Cópia de `SeparateGroup.hookTabName()` trocando `GROUPS` por `YouTabPlacement.YOU_TAB` e a string por `R.string.you_tab`. Adicionar a string em `res/values/strings.xml` e deixá-la traduzível.

- [ ] **Step 3: Implement hookTabIcon with the nested-hook pattern**

Copiar a estrutura de `SeparateGroup.hookTabIcon()` **inclusive o `Unhook` guardado em `param.setObjectExtra("hooked", …)` e desfeito no `afterHookedMethod`**. Esse padrão existe para não deixar um hook de menu ativo permanentemente; preservá-lo é a razão de copiar em vez de reinventar. O ícone vem de `AvatarIcon.current(context)` (Task B2), com fallback para `ic_you_tab_fallback`.

- [ ] **Step 4: Verify on device**

A aba aparece no fim, com rótulo `You` e algum ícone. Tocar nela ainda troca de página — isso é a Task B4.

- [ ] **Step 5: Commit**

```bash
git add app/src/main/java/com/waenhancer/xposed/features/customization/YouTab.java app/src/main/res/drawable/ic_you_tab_fallback.xml app/src/main/res/values/strings.xml
git commit -m "feat: add You tab to the bottom nav

Three hooks adapted from SeparateGroup, which already adds a tab. The
tab-list hook runs at PRIORITY_HIGHEST so HideTabs, at default priority,
always sees the completed list."
```

---

### Task B2: `AvatarIcon` — a foto recortada

**Files:**
- Create: `app/src/main/java/com/waenhancer/xposed/features/customization/AvatarIcon.java`

**Interfaces:**
- Consumes: `AvatarPolicy` (A3), `WppCore.getContactPhotoFile(String jid)`.
- Produces: `AvatarIcon.current(Context)` → `Drawable` (nunca null: cai no fallback).

- [ ] **Step 1: Implement**

Pontos obrigatórios:

- a foto vem de `WppCore.getContactPhotoFile(Utils.getMyNumber())`, que **já** trata o caso próprio tentando `files/me` e `files/me.jpg` (`WppCore.java`, linha 961) — não escrever caminho de arquivo novo;
- `mtime` do arquivo é a chave de cache, consultada por `AvatarPolicy.shouldReload`;
- recorte circular com `RoundedBitmapDrawableFactory` + `setCircular(true)`, redimensionado para o tamanho de ícone de aba;
- qualquer falha de decodificação cai no fallback vetorial, **sem lançar**: uma exceção aqui roda dentro do processo do WhatsApp durante a construção do menu.

- [ ] **Step 2: Verify on device**

1. com foto de perfil: o avatar aparece circular;
2. trocar a foto no WhatsApp e recriar a `HomeActivity`: o ícone atualiza;
3. conta sem foto: o fallback vetorial aparece, **não** um espaço vazio.

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/com/waenhancer/xposed/features/customization/AvatarIcon.java
git commit -m "feat: use the user's profile photo as the You tab icon

WppCore.getContactPhotoFile already resolves the own-photo case, so this
adds no new reflection. Any decode failure falls back to the vector icon
without throwing: this runs inside WhatsApp's process while the menu is
being built."
```

---

### Task B3: `hookTabSelection` — o atalho

**Files:**
- Modify: `app/src/main/java/com/waenhancer/xposed/features/customization/YouTab.java`
- Modify: `app/src/main/java/com/waenhancer/xposed/core/FeatureLoader.java`

**Interfaces:**
- Consumes: `Unobfuscator.loadOnMenuItemSelected`.
- Produces: a feature registrada e funcional.

- [ ] **Step 1: Implement the selection intercept**

```java
private void hookTabSelection() {
    try {
        Method onMenuItemSelected = Unobfuscator.loadOnMenuItemSelected(classLoader);
        XposedBridge.hookMethod(onMenuItemSelected, new XC_MethodHook() {
            @Override
            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                int index = (int) param.args[0];
                if (tabs == null || index < 0 || index >= tabs.size()) return;
                if (tabs.get(index) != YouTabPlacement.YOU_TAB) return;

                openProfile();
                // Cancela a troca de página: a aba You não é uma página.
                param.args[0] = (int) XposedHelpers.callMethod(param.thisObject, "getCurrentItem");
            }
        });
    } catch (Throwable t) {
        XposedBridge.log("[WAEX-YouTab] hookTabSelection error: " + t);
    }
}
```

- [ ] **Step 2: Resolve the profile screen by discovery, not by a hardcoded name**

`openProfile()` deve descobrir o alvo via `Unobfuscator` em vez de fixar um nome de Activity, que muda entre versões. Se a descoberta falhar, **não abrir nada e registrar no log** — melhor um toque sem efeito do que um `ActivityNotFoundException` dentro do WhatsApp.

- [ ] **Step 3: Register the feature**

Adicionar import e entrada no array `classes` de `FeatureLoader.plugins()`. A posição no array é irrelevante (work-stealing pool); a ordem que importa é a prioridade de hook, já resolvida.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/com/waenhancer/xposed/features/customization/YouTab.java app/src/main/java/com/waenhancer/xposed/core/FeatureLoader.java
git commit -m "feat: make the You tab open the profile instead of a page

The selection hook opens the profile and restores the current index, so
no ViewPager page is ever created. The profile target is discovered
rather than hardcoded, and a failed discovery does nothing instead of
throwing inside WhatsApp."
```

---

### Task B4: Gate — a matriz de aparelho

- [ ] **Step 1: Full build and suite**

Run: `./gradlew :app:compileWhatsappDebugJavaWithJavac :app:testWhatsappDebugUnitTest`
Expected: PASS.

- [ ] **Step 2: Device matrix**

Cada linha tem que passar; a coluna do meio é a que pega regressão de índice.

| Cenário | Verificar | Por quê |
|---|---|---|
| `HideTabs` desligado | aba `You` no fim; Chats/Status/Calls abrem o que sempre abriram | linha de base |
| `HideTabs` escondendo Status | navegar entre as abas restantes não cai na errada | é o `getNewTabIndex()` com a lista maior |
| Meta AI desligada | idem, com `1000` e `1100` removidos | dois IDs somem e um entra na mesma lista |
| Sem foto de perfil | fallback vetorial visível | §16 exige o fallback |
| Tocar em `You` | perfil abre **e a página não muda** | é a decisão central da fase |
| Swipe no `ViewPager` | nunca chega a uma página `You` | ela não existe |
| Feature desligada | aba some por completo, sem resíduo | Gate F |

- [ ] **Step 3: Write the completion handoff**

Acrescentar `#### Estado da F3 aba You — implementada` a `HANDOFF_F3_YOU_TAB.md`, no molde da F1: o que foi verificado, em qual aparelho e versão do WhatsApp, o que ficou pendente.

- [ ] **Step 4: Commit**

```bash
git add HANDOFF_F3_YOU_TAB.md
git commit -m "docs: record You tab implementation state and device matrix"
```
