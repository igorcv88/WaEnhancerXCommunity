# HANDOFF — Fase F3 (parte 2) — Editor visual de tema

**Estado:** spec aprovada, **nenhum código escrito**
**Data:** 12 de agosto de 2026
**Origem:** `HANDOFF_WaEnhancer_Community_v2_ExecutionPlan.md`, §11.5 e Fase F3 do Bloco F
**Ambiente:** Claude Code — Anthropic (Bloco F)

Parte 2 de duas da Fase F3. A aba `You` está em `HANDOFF_F3_YOU_TAB.md`, com a lista do que
ficou fora da fase inteira.

---

## 1. O que já existe, e por isso o que esta fase *não* é

**A engine de tokens do §11.5 está pronta.** `SemanticTheme.generate(accent, dark)`
(`app/src/main/java/com/waenhancer/theme/SemanticTheme.java`, 215 linhas) já deriva os 20 tokens
que o §11.5 lista, a partir de uma cor de acento, e já aplica os pisos de contraste que o plano
pede: `ensureTextContrast(…, 4.5)` para texto e `ensureControlContrast(…, 3.0)` para controles e
elementos grandes.

Esta fase **não** constrói engine de cor. Ela expõe a que existe, e preenche o buraco abaixo.

**O buraco:** existe **uma só** preferência de tema hoje — `wae_color_preset`
(`PreferenceSchema.java`, linha 286). O usuário escolhe um preset e a engine deriva tudo. Não há
nenhum caminho para override individual. Mas a regra de precedência do §11.5 exige um:

> 1. personalização específica do usuário; 2. override específico de bolha; 3. preset global;
> 4. cor original do WhatsApp.

e o próprio §11.5 dá o exemplo: "o usuário pode escolher tema Cyan global e ainda definir
manualmente uma cor específica para a bolha de saída". **O override não é enfeite do editor; é
requisito não implementado.**

---

## 2. Decisões aprovadas

| Questão | Decisão | Motivo |
|---|---|---|
| Alcance dos overrides | **Subconjunto curado + seção "Avançado"** | `onPrimaryContainer` e `surfaceVariant` não significam nada para quem só quer mudar a bolha. A tela abre com o que tem significado; os 20 continuam alcançáveis. |
| Preview | **Maquete estática de conversa** dentro do editor | Vê o efeito sem sair da tela e sem depender do WhatsApp aberto. Mesmo padrão de `BottomBarPreviewModel`, que já tem teste comparando preview e render real. |
| Override fora do piso de contraste | **Avisar, nunca corrigir em silêncio** | Ver §5. |
| Overrides por modo claro/escuro | **Separados** | Ver §4. |

**Subconjunto curado (visível por padrão):** `primary` (acento), `outgoingBubble`,
`incomingBubble`, `fab`, `unreadBadge`, `link`.
**Avançado (recolhido):** os 20 do §11.5.

---

## 3. Componentes

### `ThemeResolution` — o núcleo, livre de tipos Android
A única peça com lógica de verdade, e portanto a única totalmente testável na JVM. Assinatura
conceitual:

```
Tokens resolve(String presetName, boolean dark, Map<String,Integer> overrides)
```

Implementa a precedência do §11.5: parte de `SemanticTheme.fromPreset(preset, dark)` e aplica os
overrides por cima. Tokens desconhecidos no mapa são **ignorados**, não propagados — o mesmo
princípio do §6.3 do plano-mestre para importação ("ignorar chaves desconhecidas").

**Invariante de compatibilidade:** com o mapa de overrides vazio, `resolve()` devolve exatamente
o que `SemanticTheme.fromPreset()` devolve hoje. Um usuário que nunca abrir o editor não vê
diferença nenhuma. É isso que torna esta fase aditiva e dispensa migração de preferências.

### `ThemeEditorActivity`
Segue o padrão de `BottomBarCustomizationActivity` e `LiquidGlassActivity`, que já existem: tela
dedicada, não embedded settings. Seletor de preset, color picker por token, a maquete de preview,
reset por token e reset geral.

### `ThemePreviewModel`
Descreve a maquete — cabeçalho, bolha de entrada, bolha de saída, FAB, badge, link — como dados,
e devolve a cor de cada elemento **perguntando ao `ThemeResolution`**, nunca recalculando.
É a mesma disciplina que a F1 impôs quando `BottomBarPreviewModel.resolvedFillColor()` passou a
delegar ao `GlassSpec`: preview e resultado real não podem ter duas matemáticas. O teste compara
contra o `ThemeResolution`, então divergência falha o build.

### `ContrastAdvisor`
Dado um token sobrescrito e o token de fundo com que ele forma par, devolve a razão de contraste
medida e se ela passa do piso aplicável (4.5 para texto, 3.0 para controle). Usa
`SemanticTheme.contrastRatio()`, que já existe. Livre de Android, testável.

---

## 4. Armazenamento

**Uma pref nova, não vinte:** `wae_theme_overrides`, `Type.STRING`, `Sensitivity.PUBLIC_SETTING`,
`Store.PUBLIC`, contendo um JSON pequeno. `Store.PUBLIC` é necessário porque quem pinta o
WhatsApp é o hook dentro do processo dele; não há segredo nenhum aqui, então isso continua
compatível com o §5.4 ("nunca armazenar segredos no arquivo lido via `XSharedPreferences`").

**Overrides são separados por modo claro/escuro:**

```json
{ "light": { "outgoingBubble": "#D9FDD3" }, "dark": { "outgoingBubble": "#005C4B" } }
```

O motivo é que uma cor escolhida sobre branco costuma ficar ilegível sobre um fundo quase preto.
Um único mapa compartilhado faria o usuário consertar o modo claro e quebrar o escuro sem
perceber — e a engine não pode salvá-lo, porque um override é exatamente a instrução de não
derivar. Custa um JSON maior; evita uma armadilha silenciosa.

**Leitura é defensiva:** JSON malformado, cor inválida ou token desconhecido resultam em
**nenhum override**, não em exceção. O tema é lido dentro do processo do WhatsApp; uma exceção
aqui é o WhatsApp abrindo errado.

**Backup:** `wae_theme_overrides` entra na allowlist de exportação do §6.2 — é configuração
pública, sem segredo. Anotar quando o backup for revisitado.

---

## 5. Contraste: avisar, não corrigir

A engine garante contraste no que **deriva**. Um override é, por definição, o usuário dizendo
para não derivar. As duas coisas colidem, e a resolução é deliberada:

**Quando um override cai abaixo do piso, o editor mostra o aviso com a razão medida
("2,1:1 — abaixo de 4,5:1") e um botão de um toque que aplica `ensureTextContrast` ou
`ensureControlContrast`. A cor escolhida não é alterada sozinha.**

Corrigir em silêncio faria o color picker parecer quebrado — o usuário escolhe um tom e a tela
mostra outro, sem explicação. O §11.5 pede para "ajustar tons quando o contraste for
insuficiente", e isso está honrado: o ajuste existe, é a mesma função da engine, e fica a um
toque. O que não existe é o ajuste acontecer pelas costas.

---

## 6. Testes

Projeto com **JUnit 4 puro, sem Robolectric e sem Mockito** (`app/build.gradle`, linhas 216-217).
Esta fase é a mais testável do Bloco F, porque quase toda a lógica é aritmética de cor.

| Suíte | O que fixa |
|---|---|
| `ThemeResolutionTest` | overrides vazios devolvem exatamente `SemanticTheme.fromPreset()` (o invariante do §3); override vence o preset; token desconhecido é ignorado; overrides de `light` não vazam para `dark` |
| `ThemeOverrideStoreTest` | JSON malformado devolve mapa vazio, não lança; cor inválida é descartada sozinha sem derrubar as válidas; ida e volta preserva os valores |
| `ContrastAdvisorTest` | razão medida bate com `SemanticTheme.contrastRatio()`; piso 4.5 para texto e 3.0 para controle; a correção sugerida de fato passa do piso |
| `ThemePreviewModelTest` | cada elemento da maquete pinta com o token que o `ThemeResolution` devolve — divergência entre preview e resolução falha o build |

**Sem teste, e dito:** a aplicação dos tokens dentro do WhatsApp (`CustomThemeV2`, 655 linhas de
hook) e a aparência real. Verificação em aparelho no §7.

---

## 7. Como verificar

```bash
./gradlew :app:compileWhatsappDebugJavaWithJavac :app:testWhatsappDebugUnitTest
```

Em aparelho:

- trocar de preset repinta acento, FAB, badge e links no WhatsApp;
- um override de `outgoingBubble` sobrevive à troca de preset (é a precedência do §11.5);
- alternar claro/escuro usa o mapa de overrides certo;
- reset por token volta ao valor derivado, e reset geral volta ao estado de quem nunca abriu o
  editor;
- desinstalar/reinstalar com backup restaurado preserva os overrides;
- **teste de não regressão obrigatório:** com `wae_theme_overrides` ausente, o app se comporta
  exatamente como antes desta fase.

---

## 8. Riscos

1. **A maquete pode divergir do WhatsApp real.** Ela é nossa, o WhatsApp é dele. Mitigação: a
   maquete mostra só os elementos cujo token é aplicado por `CustomThemeV2`; não inventar
   elementos que o hook não pinta.
2. **Override ilegível aplicado de verdade.** O aviso do §5 informa mas não impede. Aceito e
   deliberado — é a escolha do usuário. O reset geral é a saída.
3. **`CustomThemeV2` não cobre todos os 20 tokens.** Provável. Um token editável que nenhum hook
   consome é um controle que não faz nada. **Auditar quais tokens o `CustomThemeV2` realmente
   aplica antes de montar a seção "Avançado", e marcar na UI os que ainda não têm efeito** — em
   vez de oferecer vinte controles dos quais alguns são inertes.
4. **Crescimento da pref.** JSON com dois mapas de 20 tokens é pequeno, mas a leitura defensiva
   do §4 deve impor um limite de tamanho, como o §6.3 exige para importação.

---

## 9. Gate

- as quatro suítes do §6 verdes;
- o invariante de compatibilidade verificado: sem `wae_theme_overrides`, nenhuma diferença
  observável;
- a auditoria do risco 3 feita, e a seção "Avançado" refletindo o resultado dela;
- a feature liga e desliga individualmente, como o Gate F exige.
