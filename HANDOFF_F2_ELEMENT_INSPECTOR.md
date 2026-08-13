# HANDOFF — Fase F2 — Element Inspector

**Estado:** spec aprovada, **nenhum código escrito**
**Data:** 12 de agosto de 2026
**Origem:** `HANDOFF_WaEnhancer_Community_v2_ExecutionPlan.md`, §11.3 e Fase F2 do Bloco F
**Ambiente:** Claude Code — Anthropic (Bloco F)

Este documento é a especificação de implementação da F2. Ele não descreve nada que já exista
no repositório. A F1 (Liquid Glass) está fechada e commitada; ver `HANDOFF_LIQUID_GLASS.md`.

---

## 1. O que a F2 é

Um modo temporário de inspeção que roda **dentro do processo do WhatsApp** e responde a uma
pergunta só: *qual é o seletor CSS que atinge esta view que estou tocando?*

O §11.3 do plano-mestre lista o fluxo, as informações permitidas, as ações e as regras de
privacidade. Este documento não os repete — ele registra as **decisões** que o §11.3 deixou em
aberto, e o motivo de cada uma.

---

## 2. A descoberta que molda o desenho

O motor de CSS (`app/src/main/java/com/waenhancer/xposed/features/customization/CustomView.java`,
1307 linhas) não aceita CSS genérico. `buildRuleMaps()` impõe um dialeto específico:

```css
.com_whatsapp_HomeActivity #conversations_row_contact_name { ... }
 └─ classe da Activity,        └─ resource entry name, resolvido por
    pontos trocados por `_`       Utils.getID(name, "id")
```

Fatos lidos direto de `buildRuleMaps()` (linhas 240-330), que a implementação **deve** honrar:

- a primeira parte com `className` vira `CachedRuleItem.targetActivityClassName`, e o motor
  descarta a regra se a Activity atual não for instância dela;
- `#android_*` resolve por reflexão em `android.R.id`, não por `Utils.getID` — logo
  `android.R.id.content` se escreve `#android_content`;
- toda regra é indexada por `resolvedId`; **um seletor cujo id não resolve é descartado em
  silêncio** — é precisamente essa falha silenciosa que o Inspector existe para eliminar;
- existe um segundo índice, `leafMapIds`, para o último id da cadeia de descendentes.

**Invariante:** o seletor emitido pelo Inspector tem que casar com este parser. Um seletor
oferecido que o motor descarta é o pior defeito possível desta feature.

---

## 3. Decisões aprovadas

| Questão | Decisão | Motivo |
|---|---|---|
| Ponte para o Custom CSS | **Clipboard** | Nenhuma superfície de IPC nova. Consequência: `Add to Custom CSS` copia um bloco de regra para colar, não insere sozinho. |
| Alcance | **Qualquer Activity do WhatsApp** | As telas menos documentadas (Settings, visualizador de mídia, status) são justamente onde o CSS falha. |
| Gesto de seleção | **Handle flutuante alterna Navegar/Selecionar** | O WhatsApp já usa long-press (selecionar mensagem, menu de contexto). Consumir o long-press custaria o gesto nativo nas telas mais visadas. Em `Selecionar`, um toque simples basta — mais preciso que long-press. |
| Timeout | **10 min de inatividade, renovado a cada seleção** | Não interrompe trabalho real e não fica armado por esquecimento. |
| Acoplamento do overlay | **Janela própria via `WindowManager.addView`** | Ver §4. |

---

## 4. Abordagem: janela própria, zero mutação da árvore

Uma segunda janela `TYPE_APPLICATION` ancorada no token do Activity em foreground. Não é
`SYSTEM_ALERT_WINDOW` e não exige permissão — atende ao §11.3 literalmente ("overlay dentro da
própria janela/processo do WhatsApp").

**Por que não hookar `Activity.dispatchTouchEvent`:** o handle e o painel também precisam
receber toque, então seria preciso hit-testar as bounds do próprio overlay antes de decidir
passar ou consumir — reimplementando à mão o que o `WindowManager` já faz. E o hook fica no
caminho de todo toque do app mesmo com a sessão desarmada.

**Por que não envolver o content root num `ViewGroup` interceptor:** muda a árvore que o
Inspector está tentando medir, e colide com a propriedade `parent` do `CustomView`, que já
remove views do pai e as envolve num `FrameLayout` novo (`setRuleInView()`, linhas 530-550).

**A alternância de modo é uma flag, não código de roteamento de toque:**

- `NAVIGATE` → `FLAG_NOT_TOUCHABLE` ligada: todo toque atravessa, o WhatsApp funciona normal;
- `PICK` → flag desligada: a janela consome tudo, e um toque simples seleciona.

---

## 5. Componentes

Todos novos, em `com.waenhancer.xposed.features.devtools`. **Nenhum toca em `CustomView.java`.**

### `InspectorSession`
Máquina de estados, livre de tipos Android. Token, instante de expiração, modo. API:
`arm(token, now)`, `touch(now)`, `isActive(now)`, `expire()`. Toda a aritmética de timeout vive
aqui, não espalhada em `Handler`s.

### `ProbeNode` (interface) + `ViewProbe`
`ViewProbe` **não recebe `View`**. Opera sobre `ProbeNode`: bounds, id, entry name, classe,
visibilidade, alpha, filhos, pai. Um adaptador de ~12 linhas converte `View` → `ProbeNode`
dentro do processo do WhatsApp. Sem essa divisão o hit-test — a parte mais fácil de errar da
fase — fica sem teste, porque o projeto não tem Robolectric (ver §9). Mesmo padrão de
`GlassSpec`/`GlassRenderer` na F1, pelo mesmo motivo.

**Regra do hit-test:** percorrer os filhos de trás para frente (ordem de desenho), descartar
`GONE`, `INVISIBLE` e alpha zero, descer na folha mais profunda cujo `getGlobalVisibleRect()`
contenha o ponto. É o `getGlobalVisibleRect` — e não `getHitRect` — que faz o clipping ser
respeitado, evitando acertar uma view cortada pelo scroll.

Saída: `InspectedView` imutável com entry name, id hex, package do recurso, classe completa,
bounds, visibility, alpha, cadeia de pais resumida e o **veredito de estabilidade**.

### `SelectorBuilder`
Converte `InspectedView` na string do dialeto do §2.

**Veredito de estabilidade:**

| Condição | Veredito | Comportamento |
|---|---|---|
| entry name resolve no pacote `com.whatsapp` | **estável** | seletor mira a própria view |
| resolve, mas em outro pacote | **dinâmico** | provável de mudar entre versões; avisa |
| `View.NO_ID` ou `NotFoundException` | **não resolvido** | sobe até o ancestral estável mais próximo e **o painel diz que o seletor mira o pai, não a view tocada** |

Um seletor que aponta para o alvo errado sem avisar é pior do que nenhum seletor.

### `InspectorOverlay`
A única classe com UI Android. Gerencia a janela, desenha a borda de destaque sobre as bounds,
hospeda o handle e o painel. Amarrada ao ciclo do Activity pelo `WppCore.addListenerActivity`
que já existe: `RESUMED` cria/reancora, `PAUSED`/`DESTROYED` remove.

### `InspectorFeature`
O `Feature` do registry. Só observa a pref e cria/destrói o overlay.

---

## 6. Ciclo da sessão

1. `MainActivity` do módulo tem `Identify UI Elements`.
2. Ao ligar, o módulo gera token aleatório e escreve `inspector_session`
   (`Type.STRING`, `Store.PUBLIC`) com token + expiração, e oferece `Open WhatsApp`.
3. `ProviderSharedPreferences` já registra um `ContentObserver` que chama `reload()`
   (`app/src/main/java/com/waenhancer/xposed/bridge/client/ProviderSharedPreferences.java`,
   linha 60) — **armar e desarmar propaga em tempo real, sem reiniciar o WhatsApp**. Nenhum
   canal novo foi inventado.
4. Sai por `Exit inspector`, por timeout, ou por morte do processo. Nos três casos o módulo
   reescreve a pref para vazia.

**Invariante:** com a pref vazia, nenhuma janela é criada e nenhum listener extra existe.
É o "nenhum overlay global permanente" do §11.3.

`inspector_session` é `Store.PUBLIC` por necessidade (é lida dentro do WhatsApp) e contém
apenas token e timestamp — nenhum segredo, o que a mantém compatível com o §5.4 do
plano-mestre ("nunca armazenar segredos no arquivo lido via `XSharedPreferences`").

---

## 7. Privacidade — regras duras

**O `ViewProbe` nunca lê `TextView.getText()`. Não existe flag que ligue isso.** Texto de uma
view no WhatsApp é conteúdo de mensagem, nome de contato ou número. O §11.3 proíbe.

`contentDescription` é lido, mas passa por um redator: telefone, JID (`@s.whatsapp.net`,
`@g.us`) ou string com mais de ~40 caracteres viram `‹redigido›`, com um botão `Revelar` que
exige toque explícito e não persiste. O campo carrega coisas legítimas ("Botão de anexar"),
então redigir tudo o tornaria inútil.

**Nada é persistido.** Nenhuma captura vai para disco — a `InspectedView` vive em memória
enquanto o painel está aberto e morre com ele. A escolha do clipboard tornou isso barato: não
existe fila para persistir. A única coisa que toca o disco no fluxo inteiro é a pref
`inspector_session`.

**Consequência das duas regras acima:** o clipboard nunca recebe texto de conteúdo, só
identificadores e seletores.

---

## 8. Ações do painel

Todas via `ClipboardManager` do processo do WhatsApp (app em foreground, escrita permitida).

| Ação | Vai para o clipboard |
|---|---|
| `Copy ID` | `conversations_row_contact_name` |
| `Copy class` | `com.whatsapp.TextEmojiLabel` |
| `Copy selector` | `.com_whatsapp_HomeActivity #conversations_row_contact_name` |
| `Add to Custom CSS` | o seletor já como bloco: `…{\n  \n}` |
| `Inspect parent` | — sobe um nível, sem novo toque |
| `Inspect child` | — desce; com vários filhos, lista para escolher |
| `Exit inspector` | — desarma a sessão e remove a janela |

Toda cópia mostra um `Toast` confirmando o que foi copiado: um clipboard silencioso é
indistinguível de um botão quebrado.

---

## 9. Testes

O projeto tem **JUnit 4 puro, sem Robolectric e sem Mockito** (`app/build.gradle`, linhas
216-217). Isso é uma restrição de arquitetura, não um detalhe — é o que força a divisão
`ProbeNode`/`ViewProbe` do §5.

| Suíte | O que fixa |
|---|---|
| `InspectorSessionTest` | arma; expira em 10 min de ociosidade; `touch()` renova; token errado não ativa; sessão expirada não reativa sem novo armamento |
| `ViewProbeTest` | folha mais profunda vence; irmão desenhado depois vence o anterior no mesmo ponto; `GONE`/`INVISIBLE`/alpha zero são pulados; view cortada por scroll não é acertada; ponto fora de tudo devolve nulo, não a raiz |
| `SelectorBuilderTest` | id estável gera seletor de um nível; id não resolvido sobe para o ancestral e marca que subiu; pontos da classe da Activity viram `_`; `android.R.id.content` vira `#android_content` |
| `RedactorTest` | telefone, `@s.whatsapp.net`, `@g.us` e string longa são redigidos; `"Attach"` passa intacto |

**Limite honesto:** a validação do seletor contra o `CSSFactory` real **não roda na JVM**,
porque `Utils.getID()` precisa do `Resources` do WhatsApp. Ela vira auto-verificação em runtime
dentro do processo: o `SelectorBuilder` gera, resolve o id, e se o id resolvido não for o da
view inspecionada o painel marca o seletor como **não verificado** em vez de fingir confiança.
Isto fica registrado como pendência de teste em aparelho, não como coisa resolvida — mesma
disciplina que a F1 usou com o shader AGSL.

---

## 10. Riscos

1. **`WindowLeaked`** — o modo de falha número um da abordagem do §4. Remover a janela em
   `PAUSED` é obrigatório; uma Activity que morre sem passar por `PAUSED` (crash, force-stop)
   deixa o rastro no log. Mitigação: `WeakReference` para o Activity e remoção defensiva no
   `RESUMED` seguinte.
2. **O `CustomView` muta a árvore.** O Inspector lê a árvore já modificada pelo CSS ativo —
   comportamento correto, mas um seletor capturado pode mirar um `FrameLayout` que o próprio
   CSS criou. O veredito **não resolvido** para views sem entry name já cobre esse caso.
3. **Ids obfuscados entre versões do WhatsApp** — fora do controle desta fase; o veredito de
   estabilidade é o que o usuário tem.
4. **Multi-window / split-screen** pode desancorar a janela. Aceito: reancorar no `RESUMED`
   seguinte.

---

## 11. Fora de escopo, explicitamente

Editar CSS dentro do WhatsApp; preview ao vivo da regra; histórico persistido de capturas;
inspecionar a hierarquia de outro app; qualquer forma de dump da árvore inteira.

---

## 12. Como verificar

```bash
./gradlew :app:compileWhatsappDebugJavaWithJavac :app:testWhatsappDebugUnitTest
```

Em aparelho, o que os testes JVM não alcançam:

- o seletor emitido realmente casa com `buildRuleMaps()` (a auto-verificação do §9);
- a janela não vaza ao trocar de Activity, ao girar a tela e ao entrar em split-screen;
- `FLAG_NOT_TOUCHABLE` de fato deixa o WhatsApp utilizável no modo `Navegar`;
- a sessão expira sozinha em 10 min e não deixa listener nenhum ativo depois disso.

---

## 13. Gate F2

- todas as suítes do §9 verdes;
- a sessão desarmada não cria janela nem registra listener (verificável no aparelho);
- nenhuma leitura de `getText()` em lugar nenhum do pacote `devtools`;
- a feature liga e desliga individualmente, como o Gate F exige.

---

## 14. Estado da F2 — Parte A implementada

**Branch:** `feat/f2-element-inspector` (a partir de `master` @ `ac0b6a23`).
**Escopo entregue:** Parte A do `PLAN_F2_ELEMENT_INSPECTOR.md`, tarefas A1–A5. Nenhum hook
instalado, nenhuma janela criada. O app compila e se comporta exatamente como antes.

### Commits

| Commit | Tarefa | Conteúdo |
|---|---|---|
| `975366b9` | A1 | `InspectorSession` — token e expiração por inatividade |
| `e2335054` | A2 | `Redactor` — redação de contentDescription |
| `775048d2` | A3 | `ProbeNode` + `ViewProbe` — hit-test sem tipos Android |
| `62599763` | A4 | `InspectedView` + `SelectorBuilder` — o dialeto do `CustomView` |
| `5637e7a2` | A5 | pref `inspector_session` no `PreferenceSchema` |

### Testes

`./gradlew :app:compileWhatsappDebugJavaWithJavac :app:testWhatsappDebugUnitTest` → **BUILD
SUCCESSFUL**, 31 suítes, **0 falhas e 0 erros**, sem regressão nas pré-existentes. As quatro
suítes novas somam 38 testes: `InspectorSessionTest` (8), `RedactorTest` (9), `ViewProbeTest`
(10), `SelectorBuilderTest` (11), mais `InspectorPrefContractTest` (1).

Grep de privacidade — `getText()` e `getPrimaryClip()` sobre `features/devtools/`: **nenhum
resultado**. Grep de `^import android` sobre o mesmo diretório: **nenhum resultado**, que é a
forma mais forte da invariante — as classes da Parte A não conseguem tocar em Android nem por
acidente.

### Dois desvios do plano, ambos deliberados

1. **A5 usa a API real do `PreferenceSchema`.** O plano escreveu o teste contra
   `PreferenceSchema.find(key)` com acessores `type()` / `store()` / `sensitivity()`. A classe
   real expõe `entry(key)` e campos públicos `final`. A nota do próprio plano na Task A5 manda
   adaptar o teste, não a classe — foi o que foi feito.
2. **O Javadoc de `InspectedView` não escreve o nome do acessor proibido.** A redação original
   citava `getText()` em prosa, o que fazia o grep do Gate F2 (§13, e Task B5 Step 2) acusar um
   resultado para sempre. Um gate que sempre acusa deixa de ser gate. O comentário diz a mesma
   coisa sem disparar o grep, e explica por quê.

### Invariantes que a Parte B não pode quebrar

1. nenhuma classe de `devtools` lê texto de view (`getText()` e equivalentes) — o Gate é um grep
   literal, então também não escreva o nome em comentário;
2. nada é persistido além de `inspector_session`;
3. `ViewProbe` recebe bounds **recortadas** (`getGlobalVisibleRect`), não nominais — o adaptador
   `ViewNode` (B1) é o responsável, e é a única razão de uma linha rolada para fora não ser
   acertada;
4. `InspectorSession` é imutável; `touched()` devolve instância nova e **não ressuscita** sessão
   morta;
5. o seletor de `SelectorBuilder` é a saída final: a Parte B **exibe e copia**, nunca reescreve.

### Riscos que sobram para a Parte B

- **O seletor ainda não foi validado contra o motor de verdade.** `SelectorBuilderTest` prova
  que a string casa com o dialeto lido em `CustomView.buildRuleMaps()` (linhas 244-334), não que
  o motor a aplique. A confirmação é a da Task B4 Step 3: colar a regra e ver efeito. Se falhar,
  o defeito é do `SelectorBuilder`, não do CSS.
- **`Stability.DYNAMIC` pode ser raro na prática.** Recursos de biblioteca (Material, AndroidX)
  são mesclados na tabela do app no build, então `getResourcePackageName` tende a devolver
  `com.whatsapp` mesmo para eles, classificando-os como `STABLE`. A classificação erra para o
  lado seguro (emite o seletor) e não afeta correção — mas o rótulo mostrado no painel pode ser
  otimista.
- **Nada da Parte B é coberto por teste JVM.** Por isso a matriz de aparelho do §12 é
  obrigatória, não opcional.
