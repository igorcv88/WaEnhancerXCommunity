# WaEnhancer Community — Handoff: Liquid Glass

## Estado entregue

- **Base:** `578d0e9f` em `master`, sincronizada com `origin/master`
- **Branch:** nenhuma. Todo o trabalho de vidro está em `master`; as cinco branches antigas
  (`block-c-critical-storage-ipc`, `fix/bottom-bar-runtime-controls`,
  `fix/bottom-bar-slider-scaling-and-glass`, `fix/bottom-bar-geometry-and-liquid-glass`,
  `fix/glass-lens-diagnostics`) foram apagadas após verificação de que cada commit delas era
  alcançável a partir de `master`
- **Remote:** `https://github.com/igorcv88/WaEnhancerXCommunity.git` (o repo foi movido; a URL
  do remote foi atualizada)
- **Build:** `BUILD SUCCESSFUL`, 184 testes, 0 falhas
- **Árvore:** limpa

> **Aviso sobre o log.** O commit `14b3292a` tem o título "feat: implement modular home menu
> customization and dynamic bottom bar configuration features" e **não faz nada disso**. Ele
> contém a reescrita do shader `LiquidLens` e três imagens de referência. O título está errado,
> o conteúdo não. Já estava publicado, então foi deixado em paz em vez de reescrito — o merge
> `578d0e9f` registra o que realmente entrou.

## O que já está feito

A barra flutuante deixou de parecer um "bump de plástico". Duas causas, ambas medidas antes de
serem tocadas:

- **`fres` (Schlick–Fresnel) estava preso em 0.04 em todo lugar.** A normal de um painel fino
  nunca inclina o suficiente para levantar o termo (`N.z` nunca caía abaixo de 0.835 por causa
  do fator `* 0.22`). O brilho da borda do lado oposto à luz era `opposite * 0.16` com
  `opposite ∝ fres` — máximo de **0.006**. Era a borda de baixo inexistente.
- **`gloss` (Blinn-Phong, expoente 48) estava invertido.** Avaliado contra aquela mesma normal
  quase plana, o pico caía **no meio do corpo** (+0.053) e **zero na borda de baixo**. Um brilho
  que cobre o corpo e decai para baixo *é* sombreamento de domo.

Ambos foram removidos (junto com `height`, `tilt`, `N`, `L`, `H`) e substituídos por: um
_hairline_ dimensionado em dp presente em todo o contorno, duas faixas de borda alargadas de
12px para 60% do bevel, e um ganho plano de corpo. O shader passou a carregar uma regra:
**nenhum termo pode variar com a distância vertical dentro da superfície.**

### Números medidos

| | referência | antes | depois (simulado) |
|---|---|---|---|
| borda de cima (pico) | 209–255 | 120, plana | 255 |
| **borda de baixo (pico)** | **140–255** | **30 (ausente)** | **195** |
| corpo | plano ~36 | curva 30→46→16 | plano 33 |

## Mapa do código

| Arquivo | Papel |
|---|---|
| `theme/GlassSpec.java` | **Fonte única de verdade.** Resolve variante + tema + capacidades → cores e tamanhos. Sem tipos de View. Quem pinta qualquer superfície resolve um spec aqui primeiro |
| `theme/LiquidLens.java` | O passe óptico AGSL. `apply(view, spec, radiusPx, density)` em qualquer View que já desenhe o backdrop. Exige API 33+ |
| `theme/GlassRenderer.java` | Fallback em camadas para quem não tem `RuntimeShader` |
| `theme/LiquidMorph.java` | Blob com mola crítica sob a tab ativa. **Hoje desativado sob a lente** (ver ponto 5) |
| `theme/BackdropSampler.java` | Cor média atrás da superfície, insumo de `GlassSpec.adaptTo()` |
| `customization/FloatingBottomBar.java` | O hook. Cria o `BlurView`, aplica a lente, gerencia elevação e geometria |
| `ui/helpers/BottomSheetHelper.java` | Único outro consumidor hoje (usa `GlassRenderer`, não a lente) |
| `tools/glass_profile.py` | O instrumento de medição. **Use antes de mudar qualquer coisa** |

### Parâmetros atuais (variante `LIQUID`)

`GlassSpec.Variant.LIQUID`: `fillScale 0.30`, `blurRadius 6`, `opacidade recomendada 14%`,
`lensStrength 1.00`, `rimWidthDp 22`, `dispersion 0.42`, `specular 1.00`, `innerShadow 0.55`,
`adaptive`, `morphing`.

`LiquidLens`: `LIGHT (0.45, 0.89)`, `MAX_DISPLACEMENT 0.62`, `MAX_BEVEL_FRACTION 0.34`,
`MAX_SATURATION 1.25`, `HAIRLINE_DP 0.95` (limitado a 1.5–5px).

`FloatingBottomBar`: `LENSED_ELEVATION_DP 5` + `LENSED_TRANSLATION_Z_DP 2` (7dp combinados).

## Fase 1 — concluída e validada no aparelho (2026-08-11)

Os itens 1–7 descritos abaixo foram concluídos depois da redação original deste handoff. A seção
permanece como registro do diagnóstico e dos critérios que orientaram a implementação; os verbos
no futuro nela não representam trabalho ainda pendente.

- O hairline agora amostra a luminância do backdrop para dentro da borda e recebe variação de
  baixa frequência ao longo do perímetro. Sobre conteúdo escuro ele recua; sobre conteúdo claro
  concentra luz localmente.
- O ganho plano do corpo passou de aditivo para multiplicativo, `MAX_SATURATION` foi para `1.60`,
  `ADAPTIVE_TINT_WEIGHT` para `0.55` e o `fillScale` de `LIQUID` para `0.18`.
- A espessura caiu para `MAX_BEVEL_FRACTION 0.26`, `specular 0.75` e `innerShadow 0.31`. O último
  fica um centésimo acima de `ADVANCED` para preservar o contrato testado entre variantes.
- O item ativo virou um segundo `sdRoundRect` dentro do shader. Ele tinge e reforça localmente a
  refração e o specular; `LiquidMorph` continua sendo a mola, mas sob a lente publica seus
  uniformes em vez de pintar uma bolha externa.
- O press atualiza `uPress` por 220ms. O `RuntimeShader` e o `RenderEffect` permanecem instalados;
  somente os uniformes mudam por frame. `spec.animate` continua respeitando redução de movimento.

### Medição final

Quatro capturas 1440×3120 do aparelho foram medidas com `tools/glass_profile.py`, usando
`x=300..1150`, faixa superior `y=2835..2880`, faixa inferior `y=2990..3040` e passo 10. As
capturas ficam apenas no disco local porque contêm contatos, fotos e mensagens reais.

| captura | borda superior min→max (variação) | borda inferior min→max (variação) |
|---|---:|---:|
| conversas, conteúdo heterogêneo | 52.1→158.8 (**106.7**) | 53.5→97.3 (43.8) |
| chamadas, fundo predominantemente uniforme | 52.0→101.0 (49.0) | 68.5→89.8 (21.3) |
| grupos, conteúdo heterogêneo | 52.1→245.9 (**193.8**) | 54.1→77.8 (23.7) |
| conversas, conteúdo heterogêneo | 52.1→237.4 (**185.3**) | 53.1→77.8 (24.7) |

O critério do ponto 1 era variar pelo menos 60 de luma ao longo da borda sobre fundo
heterogêneo. As três capturas aplicáveis passaram por 46.7, 133.8 e 125.3 luma de margem. A tela
de chamadas é o controle esperado: sobre fundo quase uniforme a borda permanece contida em vez
de fabricar contraste. As quatro capturas também mostram transmissão localizada das cores do
conteúdo e o item ativo verde integrado ao corpo refrativo, sem a bolha pintada por cima.

Validação de código: 184 testes, compilação Java, lint Release, R8/resource shrinking e assinatura
APK v2 passaram. A expansão para outras superfícies continua sendo a Fase 2.

## Fase 1 — os sete retoques ópticos

Referência: `demo/liquid-glass/liquid-glass-full-app-brief.png`.

### 1 e 4 (mesma causa) — a borda uniforme / o halo branco contínuo

> "A borda está uniforme demais ao redor do componente. No Liquid Glass, a percepção da borda
> deveria vir de lensing: regiões diferentes da borda concentram e desviam luz de formas
> diferentes conforme o fundo." / "o brilho externo está um pouco forte e uniforme… puxa para
> glassmorphism/neumorphism."

**Diagnóstico.** O ganho do hairline hoje é `0.45 + 0.60 * max(facing, 0)`. `facing` só depende
da posição no perímetro, então a borda é uma função lisa e previsível da geometria — uniforme por
construção. Esse hairline contínuo *é* o halo branco reclamado. Fui eu que o coloquei: era a
correção certa para a borda ausente, e agora precisa virar condicional.

**Mudança.** Fazer o hairline ser dirigido pelo fundo, não pela geometria:

1. Amostrar a luminância do backdrop logo para dentro da borda (`content.eval` deslocado por
   `-n * hw * 2`).
2. Escalar o ganho por ela: `hairGain *= mix(0.15, 1.0, smoothstep(0.05, 0.45, bgLuma))`.
   Sobre fundo escuro a borda quase desaparece; sobre conteúdo claro ela acende.
3. Somar uma variação de baixa frequência ao longo do perímetro para os "highlights localizados"
   (uma ou duas funções seno da posição angular, amplitude ~0.25).

**Critério de aceite (mensurável).** Com `tools/glass_profile.py --edge`, o pico da borda deve
**variar ao menos 60 de luma** ao longo do perímetro sobre um fundo heterogêneo.

**Linha de base já aferida** (mesmo screenshot do ponto 3, x de 300 a 1150):

| | min | max | variação |
|---|---|---|---|
| borda de cima | 249.5 | 254.0 | **4.5** |
| borda de baixo | 177.1 | 255.0 | 77.9 |

A borda de cima varia 4.5 e está **saturando em branco** — é a reclamação do "halo contínuo e
uniforme demais", agora com número. Não precisa adivinhar se melhorou: são esses 4.5 que a
mudança tem que levar acima de 60. A borda de baixo já passa, então cuidado para não estragá-la
ao mexer no hairline.

### 2 — o miolo opaco / falta transmissão de cor

> "ele se comporta mais como backgroundBlur + gray overlay… cor, luminosidade e contraste do
> conteúdo subjacente devem contaminar discretamente o vidro."

**Diagnóstico.** Duas causas, e a primeira é minha: `col += warm * 0.07 * uSpec` é um ganho
**aditivo e constante** — literalmente o *gray overlay* reclamado. Aditivo lava a cor em direção
ao branco; multiplicativo preserva as razões de matiz.

**Mudança.**

1. Trocar o ganho aditivo por multiplicativo: `col *= 1.0 + 0.10 * uSpec`.
2. Subir `MAX_SATURATION` de `1.25` para ~`1.6` para o conteúdo de trás transmitir cor.
3. Subir `ADAPTIVE_TINT_WEIGHT` em `GlassSpec` de `0.35` para ~`0.55`.
4. Baixar `fillScale` de `LIQUID` de `0.30` para ~`0.18`.

**Cuidado.** `GlassSpec.MIN_CONTENT_CONTRAST` (3.0) é garantia de legibilidade e há testes em
`GlassSpecTest` em cima disso. Baixar o fill vai empurrar `ensureTextContrast` — rode os testes.

### 3 — falta refração nas bordas — ✅ FEITO e medido

> "Próximo ao perímetro da cápsula, o fundo deveria sofrer um deslocamento óptico muito pequeno…
> O centro pode continuar relativamente estável."

**Entregue.** `uBlur` + `sample9()` em `LiquidLens`, raio `t * uBlur` (zero no contorno, cheio a
um bevel para dentro); `LiquidLens.CAPTURE_BLUR_RADIUS = 1f` e `applyCaptureBlur()` em
`FloatingBottomBar`. `BLUR_DP_PER_UNIT 0.70`, teto `MAX_BLUR_PX 16` — 14.7px num aparelho 3.5x.
A dispersão continua um tap nítido por canal, misturada por `fringe = edge²`: a franja é efeito
de borda, e a borda é onde o raio do blur é ~zero, então borrar R e B custaria 18 amostras para
reproduzir o que o verde já carrega em todo lugar onde a franja não aparece.

`applyCaptureBlur()` é dirigido pelo **retorno** de `LiquidLens.apply()`, não por `isActiveFor()`.
Os dois discordam no único caso que importa: um aparelho cujo driver rejeita o AGSL quer lente,
não pode ter, e ficaria sem o blur do shader **e** sem o da biblioteca. Como `refreshLiquid` roda
a cada passada de layout, a rejeição — que só se descobre no primeiro `apply` — é corrigida na
seguinte.

**Medido no aparelho** (1440×3120, cápsula em y 2841–3032):

| | referência | antes | simulado | medido |
|---|---|---|---|---|
| borda de cima (pico) | 209–255 | 120, plana | 255 | **253.5** |
| borda de baixo (pico) | 140–255 | 30 (ausente) | 195 | **187.0** |
| corpo sobre fundo liso | plano ~36 | curva 30→46→16 | plano 33 | **plano 32.9** (113px) |
| corpo sobre conteúdo | — | plano (invisível) | — | **32.9 → 161.4** |

O critério era o corpo variar ≥20 de luma onde há conteúdo atrás. Variou **128.5**. O controle
que faz esse número significar alguma coisa é a terceira linha: onde o fundo é liso o corpo
continua **exatamente** plano por 113 pixels. Ele não ficou ruidoso, passou a rastrear o fundo.
Sob o FAB verde o corpo entra em `(28, 116, 66)` — verde, não cinza claro — e volta ao neutro
assim que o FAB acaba geometricamente.

**Este é o ponto arquitetural, e é uma armadilha.** A refração **já existe** (`uRefract` ≈ 40px
num aparelho de 1440p) e é invisível. Motivo: o `BlurView` desfoca o backdrop **antes** do
shader. Deslocar pixels de uma papa uniforme produz papa uniforme. **Refração de um fundo já
desfocado não se vê.**

**Mudança.** É a mesma que a prancha pede no ponto 2 ("blur maior e variável, mais forte no
centro, mais fraco nas bordas") — e resolve os dois de uma vez:

1. Reduzir o blur do `BlurView` para o mínimo nas variantes com lente, mantendo-o **habilitado**.
   **Não use `setBlurEnabled(false)`**: o `BlurView` é o que captura o backdrop para dentro da
   View. Sem ele, `content` vem vazio e o shader cai no caminho `ca < 0.01` (tint plano).
   Ver `setupBlurView()` — ele já zera o *overlay* quando há lente; falta fazer o mesmo com o raio.
2. Fazer o blur **dentro** do shader, com raio graduado por `t`: 9 amostras com raio
   `t * maxBlur` — forte no centro, nítido na borda. Aí o deslocamento passa a ter estrutura para
   entortar.
3. Manter o deslocamento pequeno perto da borda (a prancha pede 1–2px de sensação; o valor de
   `MAX_DISPLACEMENT` deve ser reavaliado depois que o blur graduado entrar).

**Custo.** 9 amostras por pixel numa superfície de ~1440×190 é aceitável para uma barra, mas
**não** se multiplicado por muitas superfícies na Fase 2. Ver "Riscos".

### 5 — o item ativo verde parece um botão colado

> "Eu faria o verde se comportar como vidro tingido… sem parecer uma segunda bolha independente."

**O caminho já está documentado** no commit `f486b78b`: *"Reinstating it means making it a second
shape in the lens's distance field so it refracts too, not painting it on top."*

**Mudança.** Adicionar uniformes `uActiveCenter`, `uActiveHalf`, `uActiveRadius`; calcular um
segundo `sdRoundRect`; usar esse campo para (a) um tint verde de alpha baixo, (b) um reforço
local de refração e specular. O verde precisa sair de preenchimento sólido para tingimento.
`LiquidMorph` hoje está desativado sob a lente justamente porque pintar por cima não funciona.

### 6 — reduzir espessura visual

> "O que deve diminuir é a impressão de espessura… menos gradiente escuro, menos relevo, menos
> outline e menos glow." A hitbox pode continuar grande.

**Mudança.** `innerShadow` de `0.55` → ~`0.30`; `specular` de `1.00` → ~`0.75`;
`MAX_BEVEL_FRACTION` de `0.34` → ~`0.26`. Geometria (altura, margens) fica em
`BottomBarGeometry.java` e nas constantes `PILL_*` — **não mexer na hitbox.**

### 7 — dinamismo

> "o highlight/refração mudarem levemente no press, e a região ativa 'escorrer'… Isso pode ser
> extremamente sutil; o objetivo não é fazer gelatina."

**Mudança.** Uniforme `uPress` (0→1) escalando specular e deslocamento; `uActiveCenter` (ponto 5)
interpolado entre tabs pela mola que já existe em `LiquidMorph`. Respeitar `spec.animate` /
`spec.morphing`, que já carregam a decisão de *reduce motion*.

**Cuidado de performance.** `LiquidLens.apply()` reconstrói o `RuntimeShader` quando a chave de
cache muda, e `uPress`/`uActiveCenter` mudam **por frame**. Animar via a chave atual alocaria um
shader por frame. É preciso guardar a referência do `RuntimeShader` e só atualizar uniformes,
mantendo a chave para mudanças estruturais (tamanho, raio, bevel).

## Fase 2 — estender para todo o WhatsApp

Telas na prancha: **1. Conversas · 2. Conversa · 3. Chamadas · 4. Cabeçalho da conversa ·
5. Configurações.**

**A arquitetura já suporta isso.** `GlassSpec` é deliberadamente livre de tipos de View, e
`LiquidLens.apply()` aceita qualquer View que desenhe seu backdrop. Estender é, por superfície:
descobrir a View no processo do WhatsApp → embrulhar num host com `BlurView` → resolver um spec →
aplicar a lente. `BottomSheetHelper` é o precedente (usa o `GlassRenderer`).

**Superfícies candidatas por tela:** barra de busca e chips de filtro (1); barra de input e balões
de mensagem (2); tiles de ação "Ligar/Agendar/Teclado/Favoritos" (3); app bar (4); cartão de
perfil e agrupamentos de linhas (5).

**A fazer, na ordem:**

1. Extrair de `FloatingBottomBar` o padrão "embrulhar View em host com blur + aplicar lente" para
   um helper reutilizável. Hoje essa lógica está entrelaçada com a geometria da barra.
2. Descoberta de Views resiliente a versão. Siga `FAB_CLASS_CANDIDATES`: lista de candidatos,
   todos tentados, nenhum obrigatório. IDs de recurso por nome, nunca por valor.
3. Um spec por tipo de superfície. Uma app bar não quer os mesmos parâmetros de uma cápsula
   flutuante — mas todos saem de `GlassSpec`, sem uma segunda cópia da aritmética.
4. Orçamento de performance antes de espalhar (abaixo).

## Riscos e armadilhas

- **Refração de fundo desfocado é invisível.** A armadilha central. Ver Fase 1, ponto 3.
- **Ganho aditivo é overlay cinza.** Se o vidro parecer "cheio", procure um `col +=` constante.
- **O shader só se verifica no aparelho.** Não há como compilar AGSL no desktop. O ciclo usado
  aqui foi: simular a matemática em Python → compilar o Java → instalar → medir o screenshot com
  `tools/glass_profile.py`. Simulação prevê valores; só o aparelho prova.
- **Custo de overdraw na Fase 2.** Cada superfície com lente é um `RuntimeShader` + captura de
  blur. Muitas superfícies numa lista que rola é a forma mais provável de tornar isso
  inaceitável. `GlassSpec.layerCount()` existe para tornar esse custo testável sem aparelho —
  use-o, e considere limitar a lente a superfícies estáticas (barras, cabeçalhos), deixando o
  `GlassRenderer` para o que rola.
- **API 33+.** `RuntimeShader` só existe do Android 13 em diante. Abaixo disso, `GlassRenderer`.
  `LiquidLens.status()` diz por que a lente recusou — foi o que permitiu diagnosticar isto.
- **Screenshots reescalados.** A referência tem 853px de largura para um aparelho de 1440px:
  fator 1.688. Converta profundidades para pixels do dispositivo antes de comparar com o shader.
- **Contraste.** `MIN_CONTENT_CONTRAST` não é decorativo; há testes em cima dele.

## Como verificar

```bash
./gradlew :app:compileWhatsappDebugJavaWithJavac :app:testWhatsappDebugUnitTest

# perfil vertical: mostra as duas bordas, a queda e o corpo de uma só vez
python tools/glass_profile.py <screenshot> --column <x> --from <y0> --to <y1>

# continuidade da borda ao longo do comprimento
python tools/glass_profile.py <screenshot> --edge <x0> <x1> --band <y0> <y1>
```

Referências em `demo/liquid-glass/`: `navbar-reference-target.png` (alvo contra o qual a borda
foi calibrada), `navbar-before-optics-fix.jpg` (o domo, para comparação),
`liquid-glass-full-app-brief.png` (a especificação das Fases 1 e 2).

> **`demo/` não está mais no repositório.** A pasta é `.gitignore`-ada e foi removida de todo o
> histórico, incluindo as 14 tags: as capturas são de um aparelho real com uma conta real e
> carregam nomes de contato, fotos de perfil e texto de mensagem, o que não tem nada a ver com o
> código e não pertence a um repositório público. Os arquivos continuam **no disco de quem já os
> tinha** — quem clonar do zero não os recebe e vai precisar pedir uma cópia. Os números medidos
> contra eles estão todos registrados acima, que é justamente por isso que estão aqui em vez de
> só nas imagens. Vale o mesmo para qualquer screenshot novo: meça, anote o número, não commite
> a imagem.
