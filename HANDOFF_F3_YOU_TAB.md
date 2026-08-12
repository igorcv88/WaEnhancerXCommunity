# HANDOFF — Fase F3 (parte 1) — Aba `You`

**Estado:** spec aprovada, **nenhum código escrito**
**Data:** 12 de agosto de 2026
**Origem:** `HANDOFF_WaEnhancer_Community_v2_ExecutionPlan.md`, §16 e Fase F3 do Bloco F
**Ambiente:** Claude Code — Anthropic (Bloco F)

A Fase F3 do plano-mestre empacota cinco features independentes numa linha. Esta spec cobre
**uma**: a aba `You`. O editor visual de tema está em `HANDOFF_F3_THEME_EDITOR.md`.

**Fora desta fase, por decisão:**

- *indicador animado da aba selecionada* — o §10.6 está marcado **REVOGADA**: implementado como
  escrito, produziu um segundo indicador desenhado por cima do nativo e foi removido no Bloco E.
  Reintroduzi-lo sem antes descobrir por que o caminho do active indicator do Material Navigation
  nunca era alcançável repetiria o mesmo defeito;
- *perfis retrato/paisagem* — mexe no schema de prefs (cada chave vira duas) e exige a migração
  gradual do §5.3;
- *quick toggle de presets*.

---

## 1. A decisão que encolheu a fase

O §16 diz **"clique abre perfil/configurações pessoais"**. A Fase F3 diz "aba real com avatar;
navegação ao perfil". As duas leituras cabem no texto, mas custam coisas muito diferentes:

- **página própria** — a aba é uma página do `ViewPager` hospedando um Fragment nosso. Exige
  fornecer esse Fragment em `getTabMethod`, o que exige uma subclasse **nossa** de
  `androidx.fragment.app.Fragment` viva dentro do processo do WhatsApp. Isso depende de um
  detalhe de classloader do LSPosed (o classloader do módulo tem o do app como pai, então
  `Fragment` resolve para a cópia do WhatsApp). Tem tudo para funcionar, mas não está verificado,
  e falharia na base da fase, não numa borda.
- **atalho** — o item aparece no bottom nav com avatar e rótulo, mas tocar nele abre a tela de
  perfil e a página atual não muda.

**Decidido: atalho.** Com isso somem o Fragment próprio, o `getTabMethod`, o spike de classloader
e quase toda a briga com o `ViewPager`. Sobram três ganchos com precedente direto no repositório
e um gancho novo de dez linhas.

**Consequência aceita:** não há como pôr conteúdo nosso nessa aba depois sem voltar ao plano
maior. Se algum dia o quick toggle de presets quiser morar ali, esta decisão precisa ser
reaberta — não estendida.

---

## 2. Precedente: isto não é território novo

`SeparateGroup.java` **já adiciona uma aba** ao WhatsApp — a aba de grupos separados, ID `500`.
Ele insere na lista, nomeia e dá ícone. A aba `You` é a mesma coreografia com um ID novo e sem
a parte de Fragment (porque `SeparateGroup` devolve um `ConversationsFragment`, classe do próprio
WhatsApp, e nunca precisou criar um).

O `Unobfuscator` já resolve tudo que é preciso:

| Método | Resolve | Usado aqui para |
|---|---|---|
| `loadTabListMethod` | a `ArrayList<Integer>` de IDs de aba | inserir o ID novo |
| `loadTabNameMethod` | ID → rótulo | o texto `You` |
| `loadIconTabMethod` | ID → drawable selector | o avatar |
| `loadAddMenuAndroidX` | a criação do `MenuItem` | pôr o ícone no item certo |
| `loadOnMenuItemSelected` | a seleção de aba | interceptar o toque |

IDs de aba conhecidos (`SeparateGroup.java`, linhas 42-44, e `HideTabs.java`):
`200` CHATS, `300` STATUS, `500` GROUPS, `1000` Meta AI, `1100` companheira da Meta AI.
**`YOU_TAB = 700`** — livre, e distante o suficiente das faixas usadas.

---

## 3. Componentes

Uma `Feature` nova, `YouTab`, em `com.waenhancer.xposed.features.customization`.

### `hookTabList`
Insere `YOU_TAB` **no fim** da `ArrayList`, nunca no meio. É o fim que preserva os índices de
todas as abas nativas — requisito literal do §16 ("preservar índices das abas do WhatsApp").
`SeparateGroup` insere na posição 1 porque quer a aba de grupos ao lado de Chats; aqui o
requisito é o oposto.

### `hookTabName`
Devolve `"You"` (traduzível) quando `args[0] == YOU_TAB`. Cópia direta de
`SeparateGroup.hookTabName()`.

### `hookTabIcon`
O mesmo truque de hook aninhado de `SeparateGroup.hookTabIcon()`: hookar `loadAddMenuAndroidX`
**apenas durante** a execução de `loadIconTabMethod`, guardando o `Unhook` em
`param.setObjectExtra("hooked", …)` e desfazendo no `afterHookedMethod`. Esse padrão existe para
não deixar um hook de menu ativo o tempo todo; preservá-lo é a razão de copiá-lo em vez de
reinventá-lo.

### `hookTabSelection` — o único sem precedente
Em `loadOnMenuItemSelected`: se o alvo for `YOU_TAB`, abrir a Activity de perfil e **cancelar a
troca de página**, devolvendo o índice atual. Nenhum Fragment é criado; nenhuma página existe.

### `AvatarIcon`
Carrega a foto, recorta em círculo, cacheia em memória com o mtime do arquivo como chave e
invalida quando ele muda. Fallback para um vetor genérico quando não há foto ou a leitura falha
— o fallback é exigência explícita do §16.

**A foto não custa reflexão nova.** `WppCore.getContactPhotoFile()`
(`app/src/main/java/com/waenhancer/xposed/core/WppCore.java`, linha 961) já trata o caso da foto
própria, tentando `files/me` e `files/me.jpg` quando o JID é o do usuário. `WppCore.getMyName()`
lê `push_name` de `startup_prefs`. A aba `You` consome duas funções que já existem.

---

## 4. Colisão com `HideTabs` — resolvida por ordem explícita

As duas features hookam `loadTabListMethod`. `HideTabs.getNewTabIndex()` **assume que a lista de
abas só encolhe**: ele indexa `originalTabs` (construído em `loadOnTabItemAddMethod`) e caminha
para longe das posições escondidas.

Com uma aba inserida, `originalTabs` passa a conter o ID novo, e a ordem entre os dois hooks
deixa de ser indiferente.

**Ordem obrigatória: `YouTab` antes de `HideTabs`.** Isso vira ordem explícita no
`FeatureRegistry`, **com um comentário dizendo o motivo** — não sorte de ordem de registro.

Nota: esconder a aba `You` é *desligar a feature*, não incluí-la em `hidetabs`. O ID `700` nunca
deve aparecer na lista de abas escondidas; se aparecer, é bug.

---

## 5. Testes

O projeto tem **JUnit 4 puro, sem Robolectric e sem Mockito** (`app/build.gradle`, linhas
216-217). Quase tudo nesta fase é hook sobre código ofuscado, que não roda na JVM. O que é
testável foi deliberadamente extraído para não ficar tudo sem cobertura:

| Suíte | O que fixa |
|---|---|
| `YouTabPlacementTest` | inserir `700` no fim não altera o índice de nenhuma aba nativa; inserir duas vezes não duplica; lista vazia é tratada |
| `AvatarPolicyTest` | `shouldReload(mtimeAtual, mtimeCacheada)`; arquivo ausente cai no fallback; mtime igual não recarrega |

**O que fica sem teste, e isso é dito em vez de escondido:** os quatro hooks, o recorte circular
do bitmap e a interação com o `ViewPager`. São verificáveis só em aparelho (§7).

---

## 6. Riscos

1. **Versões do WhatsApp.** Todo o mecanismo é reflexão sobre código ofuscado, resolvida por
   DexKit e strings. Uma versão nova pode mover qualquer um dos cinco métodos da tabela do §2.
   Mitigação: a feature falha desligada — qualquer `Unobfuscator` que lance impede o registro dos
   hooks e a aba simplesmente não aparece, sem derrubar o WhatsApp. É o mesmo comportamento que
   `SeparateGroup` já tem (`try/catch` por gancho com `XposedBridge.log`).
2. **`700` colidir com um ID de aba futuro do WhatsApp.** Improvável, mas se acontecer a aba
   `You` sequestraria uma aba nativa. Mitigação: no `hookTabList`, se a lista **já** contiver
   `700` antes da nossa inserção, abortar a feature e registrar no log.
3. **Foto de perfil trocada com o app aberto** — o cache por mtime cobre; o pior caso é um ícone
   velho até a próxima recriação da `HomeActivity`.
4. **A tela de perfil aberta pelo atalho varia entre versões.** Descobrir o alvo por
   `Unobfuscator` em vez de fixar um nome de Activity.

---

## 7. Como verificar

```bash
./gradlew :app:compileWhatsappDebugJavaWithJavac :app:testWhatsappDebugUnitTest
```

Em aparelho, o que os testes JVM não alcançam:

- a aba aparece no fim do bottom nav, com avatar circular e rótulo `You`;
- tocar nela abre o perfil **e a página atual não muda**;
- swipe no `ViewPager` nunca chega a uma página `You` (ela não existe);
- com `HideTabs` ativo escondendo Status e/ou Meta AI, a navegação entre as abas restantes
  continua correta e a aba `You` continua no fim;
- sem foto de perfil, o fallback vetorial aparece em vez de um espaço vazio;
- desligar a feature remove a aba por completo, sem resíduo.

---

## 8. Gate

- as duas suítes do §5 verdes;
- a matriz do §7 verificada em aparelho, com e sem `HideTabs`, com e sem Meta AI;
- a feature liga e desliga individualmente, como o Gate F exige;
- nenhuma regressão de índice de aba: Chats, Status e Calls abrem o que abriam antes.
