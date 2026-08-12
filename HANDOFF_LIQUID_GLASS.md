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
| `theme/GlassSurface.java` | **O helper da Fase 2.** Embrulha uma View num host com captura, resolve a lente ou o fallback, e possui as invariantes das duas colunas. Qualquer superfície nova passa por aqui |
| `theme/GlassBudget.java` | Quanto vidro o processo pode pagar: elegibilidade por `Kind` e teto de lentes vivas. Java puro, assertável em teste |
| `theme/GlassTargetProbe.java` | Se uma View achada na árvore do WhatsApp é mesmo a superfície procurada. Geometria pura, assertável sem aparelho |
| `config/LiquidGlassSettings.java` | Em quais superfícies o tema está ligado e qual material elas resolvem. A linha da barra é o seletor de estilo dela, não um booleano paralelo |
| `activities/LiquidGlassActivity.java` | A página do tema: Styles → General → Liquid Glass. Uma linha por superfície já medida |
| `customization/ConversationInputGlass.java` | A 2ª superfície: a cápsula de input da conversa. Descoberta por nome e, na falha, por estrutura |
| `customization/FloatingBottomBar.java` | O hook. Hoje só a geometria da barra, o morph e o FAB; o material é do `GlassSurface` |
| `ui/helpers/BottomSheetHelper.java` | Único outro consumidor hoje (usa `GlassRenderer`, não a lente) |
| `tools/glass_profile.py` | O instrumento de medição. **Use antes de mudar qualquer coisa** |

### Parâmetros atuais (variante `LIQUID`)

`GlassSpec.Variant.LIQUID`: `fillScale 0.26`, `blurRadius 5`, `opacidade recomendada 10%`,
`lensStrength 1.00`, `rimWidthDp 20`, `dispersion 0.55`, `specular 0.60`, `innerShadow 0.26`,
`adaptive`, `morphing`.

`LiquidLens`: `LIGHT (0.45, 0.89)`, `MAX_DISPLACEMENT 0.62`, `MAX_BEVEL_FRACTION 0.26`,
`MAX_SATURATION 1.55`, `HAIRLINE_DP 0.95` (limitado a 1.5–5px).

> Este bloco estava desatualizado até 2026-08-11: listava os valores anteriores à Fase 1, que as
> próprias notas da Fase 1 mais abaixo contradiziam. Quem mexer nos parâmetros atualiza aqui.

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

## Fase 1.1 — a cápsula lida como seção transversal de tubo (2026-08-11)

A Fase 1 mediu a **variação** da borda ao longo do comprimento e acertou nela. O que ela não mediu
foi o **perfil vertical**: quanto a borda pesa em relação ao corpo. Sobre uma região preta e
homogênea, a barra estava sendo lida como relevo — borda clara em cima, faixa escura no centro,
borda clara embaixo.

### A medição que faltava

`python tools/glass_profile.py <captura> --column 900 --from 2820 --to 3070 --step 2`, numa coluna
sem ícone, sobre lista escura:

| região | luma | extensão |
|---|---:|---:|
| fundo | 15 | — |
| **pico da borda superior** | **78** | 2px |
| rampa para dentro | 78→17 | 28px |
| **corpo, perfeitamente plano** | **17** | 130px |
| rampa para fora | 17→70 | 26px |
| **pico da borda inferior** | **70** | 2px |

O corpo ficava **2 luma acima do fundo**. A barra não era uma lâmina com bordas iluminadas: era
apenas as duas bordas, quase simétricas (78 e 70), com um buraco entre elas. Contraste borda/corpo
de 4,6:1, e 54% da altura ocupada pelas duas rampas. Uma varredura horizontal na borda devolvia
35.1 coluna após coluna — o próprio veredito do instrumento, "uniforme demais para vidro".

### Causas e mudanças

1. **As duas bandas eram largas e quase iguais.** `bandW` era `0.6 * uBevel` (≈35px), `lit 0.36` e
   `away 0.28` sob `specular 0.75`. → `bandW 0.34`, `lit 0.20`, `away 0.10`, `specular 0.42`.
2. **A borda inferior brilhava tanto quanto a superior.** Uma superfície voltada para baixo não
   capta reflexo especular de uma luz acima dela. → `floorDamp = 1 - 0.82 * n.y²`, aplicado ao
   hairline e à banda oposta. O que fecha a borda de baixo agora é a sombra interna mais a sombra
   projetada curta do host.
3. **A modulação do perímetro só existia nos cantos.** Era função de `atan(n.y, n.x)`, e a normal
   é constante ao longo de um trecho reto — daí os pontos escuros pontuais interrompendo uma borda
   uniforme. → `phase = atan(pn.y, pn.x)` sobre a posição normalizada pela meia-altura, que varre
   continuamente também nos trechos retos; `patches` usa duas harmônicas incomensuráveis.
4. **O corpo resolvia para o preto do fundo.** O ganho do corpo era multiplicativo, e multiplicar
   preto não produz nada. → `uAmbient`, uma adição plana (`0.15 * specular`, teto `0.07`).
5. **A refração arrastava a lista verticalmente nos trechos retos.** → peso `mix(0.38, 1.0,
   abs(n.x))`: a refração se concentra nas extremidades arredondadas, onde comprime o fundo contra
   a curva, em vez de deslocá-lo em bloco no meio da barra.

`ADVANCED` e `CLEAR` desceram junto (`specular` 0.50→0.28 e 0.85→0.40) para preservar o contrato
testado de que `LIQUID` é a variante opticamente mais ativa.

### O que essa rodada errou, medido no aparelho

O relevo sumiu, e junto com ele sumiu o vidro. Mesma coluna, build da Fase 1.1:

| região | luma |
|---|---:|
| fundo | 15 |
| pico da borda superior | 40 |
| **corpo, `(26,33,39)` chapado por 190px** | **32** |
| borda inferior | 31 |

A cápsula inteira virou **um único RGB do topo à base**. Duas causas, e as duas são instrutivas:

1. **`uAmbient` era o instrumento errado, por construção.** Somar uma constante a todos os pixels
   levanta o corpo e a borda na mesma medida — não muda a razão entre eles, só a comprime. A 0.063
   ela colocou 16 luma num corpo que valia 17, e virou ~76% do "tint cinza". O que separa uma
   lâmina escura de um fundo escuro são *diferenças* (borda, franja, sombra projetada), nunca um
   offset. **Removida, com o comentário no shader explicando por que não voltar.**
2. **A refração foi cortada justamente onde fica a maior parte da borda.** O piso `mix(0.38, …)`
   nos trechos retos matou a franja cromática em quase todo o contorno — e a franja é
   proporcional a esse deslocamento, porque as amostras por canal são offsets dele. Piso para
   `0.80`. Junto: `sep` do hairline era `hw * 0.35 * dispersion` ≈ **0.6px**, sub-pixel, então o
   antialiasing remisturava os três canais em branco e a borda não conseguia carregar cor
   nenhuma por mais que `dispersion` subisse. → `hw * 0.90`.

Também tinha um erro de julgamento meu: cortei a refração dos trechos retos para eliminar um
arrasto vertical que **o usuário nunca reclamou**. O relatório dele era sobre o relevo; o arrasto
era incômodo meu.

### Fase 1.2 — reequilíbrio

Fill baixo, dispersão alta, borda assimétrica preservada: `fillScale 0.50→0.26`,
`opacidade recomendada 20→10%` (piso do slider), `blurRadius 6→5`, `dispersion 0.34→0.55`,
`specular 0.42→0.60`, `innerShadow 0.22→0.26`, `MAX_SATURATION 1.28→1.55`, `lit 0.20→0.26`,
piso do `hairGain` `0.20→0.26`.

`floorDamp` e `patches` continuam intactos — foram eles que resolveram o relevo, e é por existirem
que o lado iluminado pode voltar a ser forte sem trazer o tubo de volta. Uma borda superior só
8 luma acima do corpo não é borda nenhuma.

### Medição final da Fase 1.2 — validada no aparelho

Captura estática, lista escura, `--column 900 --from 2825 --to 3050 --step 3`:

| região | luma | build original | build cinza |
|---|---:|---:|---:|
| fundo | 15.2 | 15 | 15 |
| **pico da borda superior** | **31.7** | 78 | 40 |
| rampa para dentro | **15px** | 28px | — |
| **corpo (165px chapado)** | **16.6** | 17 | 32 |
| **borda inferior** | **17.1** | 70 | 31 |

Varredura ao longo do comprimento, `--edge 300 1150`:

| borda | min | max | variação |
|---|---:|---:|---:|
| superior (`--band 2838 2862`) | 25.7 | 190.6 | **164.9** |
| inferior (`--band 3018 3036`) | 17.1 | 21.1 | **4.0** |

Os três critérios:

1. **Relevo eliminado.** Razão topo/base era `78:70 = 1,11:1` — praticamente simétrica, que é a
   definição de seção transversal. Agora `31,7:17,1 = 1,85:1`. A borda inferior fica entre 0,5 e
   4,5 luma acima do corpo: separação, não highlight.
2. **Contraste interno.** Borda/corpo caiu de `4,6:1` para `1,9:1` sobre fundo escuro — e sobe a
   `11:1` sobre conteúdo claro. O material virou adaptativo em vez de constante, que era o pedido.
3. **Sem veil.** Corpo de volta a 16,6 (era 32 no build cinza), mantendo a lâmina.

A borda superior continua fortemente cromática — `(185,200,59)`, `(58,164,154)`, `(140,210,148)`
na metade com conteúdo atrás, caindo para `(15,28,34)` na metade escura.

> Registro honesto: os 164.9 de variação **não são ganho desta rodada** — a Fase 1 já media
> 106–194 nesse critério. O que a Fase 1.2 fez foi preservar essa variação enquanto removia o
> relevo vertical. Antes as duas coisas estavam acopladas, e foi por isso que a primeira tentativa
> de remover o relevo levou a variação junto.

**A Fase 1 está encerrada.** Os desvios restantes estão abaixo do que o instrumento distingue de
ruído de JPEG.

### Regras do material (transferíveis para qualquer superfície)

Destiladas de três rodadas de medição. Valem para toda superfície da Fase 2, não só para a barra.

1. **Vidro é feito de diferenças, nunca de offsets.** Somar constante a todos os pixels não muda
   razão nenhuma, só comprime. Todo termo tem que ser função de `d`, `t` ou `n`.
2. **Nenhum termo pode variar com a altura do pixel na superfície.** Gradiente descendo o corpo é
   sombreamento de domo, e domo lê como plástico moldado por mais transparente que esteja.
3. **A borda inferior não é highlight.** Superfície voltada para baixo não capta especular de luz
   acima. `floorDamp` faz isso; sem ele, borda clara em cima + borda clara embaixo = tubo.
4. **A modulação do perímetro precisa ser paramétrica na posição, não na normal.** A normal é
   constante num trecho reto: qualquer coisa presa a `atan(n.y, n.x)` só varia nos cantos, e isso
   aparece como entalhes escuros pontuais numa borda uniforme.
5. **A franja cromática é proporcional ao deslocamento da refração.** Cortar refração numa região
   apaga a cor da borda ali. E a separação de canais precisa ter ≥1–2px reais, ou o antialiasing
   remistura os três em branco.
6. **Fill baixo, dispersão alta.** Cada ponto de fill é um wash chapado, e wash chapado é a única
   coisa que garantidamente não parece vidro.
7. **A borda deve ser discreta sobre fundo escuro e forte sobre conteúdo claro.** Se ela tem o
   mesmo brilho nos dois casos, é um traço desenhado, não um evento óptico. Medir os dois.

> Nota sobre o slider: `floating_bottom_bar_glass_opacity` é `35, min 10, max 100, passo 5`. O
> piso de 10% vale para **todas** as variantes; não é default por variante. Baixá-lo para 0 é
> possível, mas `GlassSpec` deriva `contentColor` do fill composto, então em 0 os ícones perdem a
> garantia de contraste sobre um avatar claro passando atrás.

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

> **Pré-requisito satisfeito em 2026-08-11.** A Fase 1 está encerrada e medida (ver Fase 1.2). A
> barra flutuante é a referência de material: qualquer superfície nova tem que medir como ela, e
> `tools/glass_profile.py` é como se prova isso. Não comece a espalhar antes de ler "O que a
> Fase 1.1 errou" — os dois erros lá são exatamente os que se repetem ao portar para uma
> superfície nova.

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

1. ~~Extrair de `FloatingBottomBar` o padrão "embrulhar View em host com blur + aplicar lente"
   para um helper reutilizável.~~ **Feito e medido em 2026-08-11 — ver abaixo.**
2. Descoberta de Views resiliente a versão. Siga `FAB_CLASS_CANDIDATES`: lista de candidatos,
   todos tentados, nenhum obrigatório. IDs de recurso por nome, nunca por valor.
3. Um spec por tipo de superfície. Uma app bar não quer os mesmos parâmetros de uma cápsula
   flutuante — mas todos saem de `GlassSpec`, sem uma segunda cópia da aritmética.
4. ~~Orçamento de performance antes de espalhar.~~ **Feito: `GlassBudget`, teto de 3 lentes.**

### Etapa 1 — o helper, extraído e medido (2026-08-11)

`theme/GlassSurface.java` e `theme/GlassBudget.java`. A barra virou o primeiro consumidor: perdeu
`setupBlurView`, `applyCaptureBlur`, `sampleBackdrop`, `applyAdaptedColors`, `pillCornerRadiusPx`,
`createGlassOutlineShape` e cinco `WeakHashMap` (viraram um, `glassSurfaces`). Ficou com o que é
dela: `BottomBarGeometry`, margens, `LiquidMorph` e o FAB.

**`GlassSurface`** possui as invariantes que antes só existiam como comentário no meio da barra:

| | com lente | em camadas |
|---|---|---|
| overlay da captura | transparente | `spec.fillColor` |
| background da captura | nenhum | pilha de `GlassRenderer` |
| raio da captura | `CAPTURE_BLUR_RADIUS` | o da biblioteca |
| clip de outline | desligado | ligado |
| elevação | 5+2dp | 12+8dp |

Mais: `setBlurEnabled` continua keyed pelo spec e **nunca** `false` com raio >0; `applyCaptureBlur`
continua dirigido pelo **retorno** de `LiquidLens.apply()`; o raio de canto é clampado a
`min(pedido, altura/2)` para todo mundo. Duas regras novas, que a extração obrigou a explicitar: a
**pintura segue a intenção e o raio da captura segue o resultado** (só divergem no aparelho cujo
driver recusa o AGSL); e um pedido de lente ainda não medido **mantém** o slot do orçamento,
enquanto um recusado pelo driver o devolve.

**`GlassBudget`** decide se a lente é acessível, em Java puro para ser assertável sem aparelho.
`Kind.STATIC_CHROME` pode receber lente; `Kind.LAYERED` nunca, em nenhum orçamento — o custo
recusado ali é por frame, não por superfície. Teto de `MAX_LENSED_SURFACES = 3` enquanto as
superfícies estão sendo trazidas uma a uma; a barra gasta uma. Subir esse número é decisão para
depois que todas estiverem medidas e nenhuma ainda se mexendo. `grant` é idempotente por holder
(senão uma barra que faz três layouts consome os três slots) e os holders são fracos.

**Medição — três capturas 1440×3120, a barra em y 2841–3032.** O critério era não mexer em nada:

| região | Fase 1.2 | pós-extração (captura 2, lista escura) |
|---|---:|---:|
| fundo | 15.2 | 15.2 |
| pico da borda superior | 31.7 | **31.7** |
| rampa para dentro | 15px | 15px |
| corpo chapado | 16.6 | 16.6–17.1 |
| borda inferior | 17.1 | 17.1 |
| razão topo/base | 1,85:1 | **1,85:1** |

Varredura ao longo do comprimento (`--edge 300 1150`): borda inferior sobre lista escura varia
**5.5** (era 4.0 — abaixo do que o instrumento distingue de ruído de JPEG); borda superior varia
**144.5** na captura com conteúdo claro atrás (`min 27.8 → max 172.3`), dentro da faixa que a
Fase 1 já media conforme a captura (106.7, 185.3, 193.8). A mesma coluna `x=900` lê topo **31.7**
sobre lista escura e **58.3** sobre conteúdo claro, com o corpo idêntico nas duas: é a regra 7
medida de novo depois da extração.

> Duas armadilhas do instrumento, encontradas aqui e que valem para as próximas superfícies. Uma
> coluna "sobre lista escura" tem que ser escolhida na captura, não assumida: `x=900` na captura 1
> atravessa uma linha de mensagem clara, e o corpo sobe de 16.6 a 60 ao longo de 60px — isso é o
> corpo rastreando o fundo, não borda. E `--edge` perto dos cantos arredondados amostra pixels
> **fora** da cápsula: um máximo de 248.9 na banda inferior da captura 3 era texto branco da lista
> em `x=301`, onde a curva do canto já terminou.

Validação de código: 193 testes (eram 184; +9 de `GlassBudgetTest`), compilação Java, lintVital
Release, R8/resource shrinking e assinatura do APK passaram.

### Etapa 2 — a barra de input da conversa, e o Liquid Glass virando tema (2026-08-12)

A Fase 2 deixou de ser "espalhar o material da barra" e virou **um tema**, com uma página própria:
tab **Styles → General → Liquid Glass** (`LiquidGlassActivity`, aberta pela `Preference`
`liquid_glass_surfaces`). Cada linha é uma superfície já medida; uma superfície não medida não
aparece, para a página nunca oferecer um interruptor cujo resultado ninguém olhou.

**A barra não tem booleano próprio nessa página.** Ela já tinha um seletor de estilo antes de o tema
existir, e dois controles sobre um mesmo estado é como uma tela de configurações começa a se
contradizer — a página diria Liquid enquanto o editor da barra diz Frost. Então a linha da barra
**é** o seletor: `LiquidGlassSettings.isBarLiquid` / `setBarLiquid` leem e escrevem
`floating_bottom_bar_glass_variant`. Ligar seleciona Liquid (e liga `floating_bottom_bar_glass`, e
adota a opacidade que o estilo foi desenhado para ter); desligar restaura o estilo anterior, guardado
em `liquid_glass_bar_previous_variant` — inclusive quando esse estilo foi escolhido no editor da
barra, que agora avisa via `rememberBarVariant`. Quatorze testes em `LiquidGlassSettingsTest` cobrem
exatamente essa concordância, que é invisível até alguém abrir as duas telas.

**Material único.** Toda superfície do tema resolve `Variant.LIQUID` pela mesma chamada
`GlassRenderer.resolveFor` que a barra faz, com a opacidade lida de
`floating_bottom_bar_glass_opacity`. Um segundo slider que tivesse de concordar com o primeiro seria
uma segunda definição do mesmo material.

#### A superfície: `ConversationInputGlass`

Descoberta em duas camadas. Nomes primeiro (`main_entry_container`, `input_layout_content`,
`entry_container`, `conversation_entry_container`, `main_entry_container_holder` — todos tentados,
nenhum obrigatório, sempre por `getIdentifier` via `Utils.getID`), e **estrutura como decisão**: se
nenhum nome resolve, sobe-se a partir de `entry` até o primeiro ancestral que tenha background, não
contenha mic/enviar e passe `GlassTargetProbe.isInputRow`. O container que *desenha* a cápsula é, por
definição, o que precisa perder o background para a lente ter o que refratar — achá-lo pelo que
desenha acerta o alvo por dois motivos ao mesmo tempo.

`GlassTargetProbe` é aritmética pura sobre números puros (36–80dp de altura, ≥55% da largura da tela,
razão ≥3:1), com oito testes. É a metade da descoberta que se prova sem aparelho, e é a que decide
entre a cápsula e o rodapé inteiro — errar aqui põe o mic dentro do painel e parece bug de shader.

`GlassSurface.wrap()` (não `install()`), `Kind.STATIC_CHROME` (2ª das 3 lentes),
`cornerRadiusDp(1000)` clampado pelo helper a metade da altura. Soltura por
`onViewDetachedFromWindow`: sem isso, três conversas empilhadas gastam o orçamento inteiro em
superfícies que ninguém está olhando. Descoberta com retry limitado (6 × 150ms), porque a geometria é
o que identifica a linha e o primeiro frame depois de `RESUMED` não a tem de forma confiável.

#### Zero divergências de parâmetro, e por quê

A etapa pedia justificar cada parâmetro que divergisse de `LIQUID`. Nenhum diverge. A aritmética que
já existe absorve a diferença de altura (previsões a 3.5x, h≈48dp contra os ≈64dp da barra):

| | barra | input | quem escala |
|---|---:|---:|---|
| bevel | 58px | 43,7px | `min(rimWidthDp·d, h·0.26)` |
| refract | 36,1px | 27,1px | `lensStrength·0.62·bevel` |
| corpo chapado | 108px | 80,6px | consequência do bevel |
| hairline | 3,3px | 3,3px | dp fixo, por projeto |
| blur no shader | 12,25px | 12,25px | independe da altura |

Uma segunda cópia de qualquer uma dessas contas seria a duplicação que a etapa 1 acabou de eliminar.

#### O que ainda NÃO foi medido

**Nada desta etapa foi visto num aparelho.** Não havia dispositivo conectado (`adb devices` vazio), e
o shader só se verifica no aparelho. Os números acima são previsões da aritmética, não medições, e
duas divergências já são esperadas e precisam de medição antes de virarem parâmetro:

1. **Borda superior proporcionalmente mais forte.** O hairline é 2,0% da altura aqui contra 1,5% na
   barra. Se a razão topo/base estourar acima de 1,7:1 por cima, o culpado é este, e a correção é um
   hairline em fração da altura.
2. **Extremidades são semicírculos completos** (raio = h/2), então a região onde
   `mix(0.80, 1.0, |n.x|)` concentra refração é proporcionalmente maior que na barra. Previsão: mais
   compressão nas pontas. Se virar smear, o parâmetro é `MAX_DISPLACEMENT`, e só então ele deixa de
   ser constante e vira propriedade do spec.

Como medir, quando houver aparelho:

```bash
# perfil vertical, coluna escolhida NA captura sobre wallpaper liso
python tools/glass_profile.py <captura> --column <x> --from <y0> --to <y1> --step 2
# borda ao longo do comprimento — recuar x0/x1 em >=84px de cada ponta
python tools/glass_profile.py <captura> --edge <x0> <x1> --band <y0> <y1>
```

> **Terceira armadilha do instrumento, específica desta superfície.** O raio é metade da altura, então
> a curva do canto ocupa ~84px de cada lado a 3.5x — o dobro do que ocupava na barra. Amostrar
> `--edge` dentro disso lê pixels de fora da cápsula, que foi o falso 248.9 da etapa 1.

Duas capturas obrigatórias, wallpaper claro e escuro: a regra 7 só se prova nas duas. Critério de
aceite: topo/base ≥ 1,7:1, borda inferior ≤ ~5 luma acima do corpo, corpo chapado sobre fundo liso,
borda superior variando ≥ 60 luma sobre fundo heterogêneo.

Um item conhecido a olhar na primeira captura: `GlassSurface` desliga `clipChildren` apenas no pai
imediato do host. Se o rodapé acima dele ainda recortar, a sombra curta que fecha a borda inferior
some — e a borda inferior é justamente o critério mais sensível.

Validação de código: 215 testes (eram 193; +8 de `GlassTargetProbeTest`, +14 de
`LiquidGlassSettingsTest`), compilação Java, lintVital Release, R8/resource shrinking e assinatura do
APK passaram.

#### Primeira medição no aparelho — três defeitos, uma causa raiz (2026-08-12)

O primeiro build no aparelho pareceu "embaçado", com halo em volta dos ícones e um fantasma da
câmera que ficava para trás quando o WhatsApp a escondia ao começar a digitar. Medido antes de tocar
em qualquer coisa, na captura da conversa:

| medida | valor |
|---|---|
| corpo, `--column 700 --from 1600 --to 1820` | `(32,43,49)` por 130px |
| corpo, `--edge 260 900 --band 1700 1740` | `(32,43,49)` **idêntico bit a bit** por 520px |
| fundo (wallpaper) | 15.2 |
| rastro acima do clipe, `y=1668` | 41 → 49 → 41 num arco de ~40px |

Duas leituras decidem tudo. Borrar um wallpaper de rabiscos deixa variação de alguns luma; **520
pixels do mesmo RGB não é backdrop borrado, é cor sólida**. E um arco simétrico de 8 luma centrado
num ícone, acima da tinta dele, é uma **cópia borrada do próprio ícone**.

**Causa raiz: o alvo estava dentro da árvore capturada.** A biblioteca de blur captura desenhando a
raiz inteira num bitmap e pula **apenas a própria view de captura**. O alvo é *irmão* dela dentro do
host, não filho — então ele era desenhado na captura, e uma cópia borrada do conteúdo da superfície
aparecia por baixo do conteúdo nítido. Daí o halo, e daí o fantasma da câmera sobreviver ao ícone.
(O sumiço da câmera ao digitar é comportamento do próprio WhatsApp; nosso era o fantasma.)

Os outros dois caem junto:

1. **Corpo chapado.** `blurRoot` foi escolhido por adivinhação de nome e caiu numa subárvore que
   contém o rodapé mas **não** desenha o wallpaper nem a lista. A captura ficou sendo só o
   `frameClearDrawable` — o fundo da janela, sólido — mais os nossos próprios ícones. Refratar uma
   cor chapada produz uma cor chapada.
2. **Fill aplicado duas vezes** no caminho em camadas: `GlassRenderer.background()` já carrega
   `spec.fillColor` na base, e `setOverlayColor(spec.fillColor)` o aplicava de novo por baixo. É a
   superfície lendo com o dobro da opacidade configurada — a mesma regra que o javadoc do
   `GlassSurface` já enunciava para o background do alvo, uma camada mais para dentro. Vale para
   **toda** superfície não-lente, a barra inclusive.

**A correção.** `GlassSurface.CaptureExcludedHost`: o host distingue a passada de captura pelo
canvas — a biblioteca desenha num canvas de software, e uma view numa janela acelerada é desenhada
num de hardware, então *canvas de software em janela de hardware* é a captura, e o host não desenha
nada nela. Janela genuinamente por software fica de fora da regra, porque lá o teste não distingue
os dois casos e uma superfície que nunca desenha é pior que um halo.

Isso também **muda o contrato do `blurRoot`**: conter o próprio host deixou de ser problema, e a
descoberta parou de precisar adivinhar um ancestral que desenhe o conteúdo sem conter a superfície —
para a maioria das superfícies esse ancestral não existe. A linha de input agora captura
`android.R.id.content` inteiro, que garantidamente desenha wallpaper e lista.

> **Regra 8 do material, aprendida aqui.** A superfície não pode estar dentro da própria captura.
> Vale para toda superfície da Fase 2, e o sintoma é diagnóstico: halo simétrico em volta do
> conteúdo *dela*, não do fundo.

Nada disso foi reverificado no aparelho ainda. O log de instalação agora imprime `fill`, `blur`,
`fallback`, o alvo, o `blurRoot` e o `status()` da lente, para a próxima captura ser conferível
contra um fato em vez de contra uma dedução.

#### Segunda medição — o halo morreu, a linha de input foi descartada (2026-08-12)

`CaptureExcludedHost` funcionou, e é medido: em `y=1668`, acima da tinta do clipe, a linha ficou
**41 constante** ao longo de todo o trecho (era 41→49→41), e a tinta em `y=1700` assenta sobre 41
exato, sem saia (era 55/64/112/92/95/93/67/56/48). Fantasma da câmera resolvido junto.

O corpo, porém, continuou `(32,43,49)` — **byte a byte igual ao build anterior**, imune tanto à
correção do fill duplicado quanto à troca do `blurRoot`. Só uma coisa explica imunidade a duas
correções mais um halo que sobreviveu a ~1/10 da sua força: **a cápsula é desenhada por um filho do
alvo, a ~0.9 de alpha, e o nosso vidro estava atrás dela o tempo todo.** Daí a terceira correção,
`GlassSurface.takeOverFullBleedDescendants()`: o takeover de background agora alcança descendentes
que cobrem ≥85% da superfície, restaurando-os no detach. Um background em algo menor é estilo do
próprio elemento — um badge, um estado selecionado, um anel de avatar — e não é nosso para remover.

> **Regra 9 do material.** A view que o hook acha não é necessariamente a view que desenha. Se a
> superfície é imune às suas correções, procure quem está pintando por cima antes de mexer em mais
> um parâmetro.

**A linha de input foi removida**, e o motivo é medido, não estético:

| região | variação de luma |
|---|---:|
| atrás da linha de input (só wallpaper) | **48,9** |
| onde flutua o botão scroll-to-bottom | 203,5 |
| chips inline "Yesterday" / "unread" | 235,3 |
| lista de mensagens em geral | 235,0 |

O WhatsApp preenche a lista com padding para que os balões parem **acima** da cápsula, então atrás
dela só existe wallpaper — e wallpaper é fixo à janela: rolar a conversa não muda um pixel ali. O
backdrop nem varia (48,9, abaixo dos 60 do critério) nem se move. É a tela de chamadas da Fase 1,
permanente. Pagar uma captura por frame por um backdrop constante não se justifica.

> **Critério de seleção da Fase 2, que vale para toda superfície daqui em diante:** só recebe lente
> a superfície que tem conteúdo heterogêneo **se movendo** atrás dela. Todo o resto é um painel
> tingido que custa um shader. Medir os dois lados com `--edge`/patch antes de escrever o hook.

**Cabeçalho da conversa: recusado pelo mesmo critério.** A lista não passa por baixo dele; começa
abaixo. Vidro ali mostra wallpaper estático. E ele carrega a foto e o nome: uma borda refrativa de
~12dp encostada num avatar circular de ~40dp compete com o anel do avatar na mesma escala, que lê
como erro de renderização em vez de material.

**Chips de data / "unread": recusados por ora, e a razão corrige uma recomendação minha.** Os que
aparecem na conversa são **linhas inline da lista**, não pills fixos — rolam com o conteúdo, logo são
`Kind.LAYERED`, que nunca recebe lente em orçamento nenhum. O pill de data realmente fixo é outro, o
transitório que aparece durante a rolagem.

#### Etapa 3 — o botão scroll-to-bottom

`ConversationScrollButtonGlass`. Var 203 atrás, mudando a cada frame de fling; a superfície mais
barata da tela; e a **primeira redonda** — raio = metade da altura nos dois eixos, então o rim curva
por todo o contorno em vez de só nas pontas. Regime óptico novo, a medir em vez de assumir.

Descoberta: 5 nomes candidatos (todos tentados, nenhum obrigatório) e, na falha, varredura estrutural
por forma + posição. `GlassTargetProbe.isRoundButton` (28–72dp, desvio do quadrado ≤18%, 8 testes)
mais três testes de posição que a geometria não dá: à direita de 60% da tela, **acima do topo do
`entry`** — senão o botão de mic passa em tudo, e ele é a resposta errada duas vezes: pertence à
linha de input e não tem nada atrás — e **fora de qualquer container que rola**, porque uma view que
rola com o conteúdo é `LAYERED` por definição e passaria por baixo da recusa do orçamento, que é
informado do `Kind` em vez de perguntá-lo.

**Primeira tentativa no aparelho: nenhum log, nenhum efeito.** A causa era de projeto e eu deveria
tê-la previsto: **o botão não existe quando a conversa abre.** Ele só é criado quando a lista sai do
fim, e a descoberta consultava a árvore 8 vezes a cada 250ms depois do `RESUMED` — dois segundos
procurando uma view que ainda não tinha sido criada, e depois desistia para sempre. Superfície
transitória não se busca por polling; espera-se a árvore avisar.

Trocado por um `OnGlobalLayoutListener` no decor, com throttle de 300ms e scan pulado enquanto já
houver superfície viva. O listener fica pela vida da tela em vez de se desregistrar no primeiro
sucesso, porque o botão é destruído e recriado toda vez que a lista chega ao fim e sai dele.

Duas correções de instrumentação junto, porque o silêncio não distinguia quatro falhas diferentes
(pref desligada, módulo não carregado, descoberta falhou, driver recusou o shader):

- A linha de boot passou a ser emitida **antes** do check da pref, como a `glass boot` da barra. A
  ausência dela agora significa uma coisa só: esta feature não rodou neste processo.
- `dumpCandidatesOnce`: na primeira falha de descoberta, uma varredura única despeja toda view de
  20–96dp, mais qualquer uma cujo nome contenha `scroll`/`unseen`/`jump`, com nome de recurso,
  classe, bounds, visibilidade, se está dentro de container que rola e se tem background. Custa uma
  caminhada de árvore na vida do processo e converte adivinhação de nome em fato.

**Segunda tentativa: a descoberta acertou, a superfície não.** O watcher achou o botão — a captura
agora lê conteúdo real, dá para reconhecer a foto atrás dela borrada — mas a sequência no aparelho
foi: botão original → círculo branco em volta → **retângulo borrado de arestas duras** no lugar dele.
Medido na captura: aresta esquerda dura em `x=1272`, superior em `y=2779`, ~168×161px (48×46dp), do
tamanho e na posição do botão. Dois defeitos independentes:

1. **A pintura seguia a intenção, não o resultado.** A regra antiga — "a pintura segue a intenção e o
   raio da captura segue o resultado" — apoiava-se em intenção e resultado só divergirem no aparelho
   cujo driver recusa o shader. Divergem também numa superfície **ainda não medida**, e ali a pintura
   com lente (sem background, sem fill, sem clip de outline, porque o shader é dono dos três) deixa a
   captura na tela como um retângulo borrado sem forma. A barra nunca mostrou isso porque a lente
   dela roda. Agora `paint`, `applyCaptureBlur` e `applyElevation` seguem todos o **resultado**: uma
   superfície esperando a lente recebe o visual em camadas, que é uma superfície que funciona, e
   troca quando a lente de fato roda.
2. **O host não seguia a visibilidade do alvo.** Visibilidade é do alvo, e o host é uma view que o app
   hospedeiro nunca ouviu falar: quando o WhatsApp esconde o botão, o alvo some dentro de um wrapper
   que fica exatamente onde estava, e o que sobra na tela é um painel de vidro com nada dentro. A
   barra resolveu isso hookando a única classe cuja visibilidade lhe interessava; uma superfície
   descoberta por forma não tem classe para hookar, então `GlassSurface.followTargetVisibility()`
   espelha o estado por um `OnPreDrawListener` — pre-draw e não layout, porque ir a `GONE` não
   necessariamente relayouta o host. Alpha vai junto, para um botão que faz fade-in não chegar atrás
   de um vidro já em força total.

> **Regra 10 do material.** Uma superfície embrulhada tem dois estados que o wrapper não herda de
> graça: a forma, que só existe quando a lente roda, e a visibilidade, que continua sendo do alvo.

**Terceira tentativa: o log resolveu, e expôs um runaway.** O despejo diagnóstico deu o nome real —
**`scroll_bottom`**, `android.widget.FrameLayout` 168×138 — depois de cinco chutes errados
(`scroll_to_bottom_btn`, `scroll_to_bottom`, `conversation_scroll_to_bottom`, `scroll_down_btn`,
`unseen_messages_indicator`). Mas antes disso o log mostrou a feature embrulhando, a cada 300ms, o
botão de vídeo, o de chamada, o `menuitem_overflow` e depois o mesmo `Z.oj0` vinte vezes, até
`lensedSurfaces=3/3`. Os botões do cabeçalho sumiram da tela.

Três causas, todas de descoberta e nenhuma de material:

1. **O teste de posição aprovava o canto superior direito inteiro.** "À direita de 60% da tela" vale
   para vídeo, chamada e overflow, todos redondos e do tamanho certo. Faltava o outro eixo:
   `TOP_EDGE_FRACTION 0.45` — este botão flutua na metade de baixo da área de mensagens.
2. **O teste do rodapé não excluía nada, e parecia que sim.** `footerTop=2997` num `screen=1440x2992`:
   com o teclado fechado o `entry` fica no limite da tela, e um limite igual ou além do fim do display
   aprova tudo. Agora um valor fora de `(0, altura)` é descartado e o teste se declara ausente, que é
   honesto; um teste que não exclui nada é pior que nenhum, porque lê como um.
3. **A superfície embrulhava o próprio embrulho.** O host herda os LayoutParams do alvo, logo tem o
   mesmo tamanho e a mesma posição — e uma descoberta que identifica por forma e posição identifica o
   host com igual entusiasmo. `GlassSurface.isGlassHost()` agora existe para a descoberta reconhecer o
   próprio trabalho, a varredura não desce dentro de um host, e a feature passou a possuir **uma**
   superfície: enquanto houver uma viva e anexada, nem escaneia.

> **Regra 11 do material.** Descoberta por forma tem de reconhecer o próprio host, e quem embrulha
> tem de possuir no máximo uma superfície por vez. Sem as duas, o wrapper vira alvo do próprio
> wrapper e o orçamento de lentes some em segundos.

Uma quarta, de instrumentação: a linha de install só consegue dizer `deferred: surface not measured
yet (0x0)`, porque nada foi medido no instante do wrap — e foi exatamente o que os vinte registros
diziam. Agora há uma linha `settled`, emitida uma vez por superfície no primeiro layout com largura,
que reporta o `status()` real da lente.

**Quarta tentativa: o runaway morreu, a lente rodou, e o alvo estava errado.** Uma única instalação,
como planejado — e no `input_attach_button`, o botão de clipe da barra de input. Mas a linha nova
entregou o primeiro dado positivo desta etapa:

```
settled lensed=true host=144x144 status=active: 144x144 bevel=37.44px refract=23.2128px
        dispersion=0.55 hair=2.85px blur=10.5px
```

**A lente roda numa geometria pequena e redonda.** Nunca tinha sido observado. 144px é 1/6,7 da
largura da barra flutuante (960px) e o motor não caiu para fallback; o bisel escalou para 37,44px
contra 49,92px da barra, ou seja, ele está limitado pela altura da superfície e não pelo pedido em dp
da variante — que é exatamente o que `LiquidLens` deveria fazer numa superfície redonda, e agora está
medido em vez de suposto.

Sobre o alvo errado: **não existe teste de forma que separe os dois.** O clipe é redondo, tem 144px,
fica à direita de 60% da largura e bem abaixo de 45% da altura. Ele passa em todos os critérios
legitimamente, porque é geometricamente indistinguível do alvo. Acrescentar um sexto teste seria
ajustar limiares até que este aparelho, nesta versão, nesta conversa, respondesse certo.

A falha real estava em **quando** o fallback estrutural rodava: ele só era chamado quando o nome não
resolvia, e este botão não existe na maior parte do tempo — só enquanto a lista está rolada para
cima. O fallback era portanto invocado quase exclusivamente sob a única condição que garante que a
resposta certa não está na árvore. E uma busca obrigada a devolver algo devolve.

A busca estrutural foi **removida**. A identidade agora vem só do nome do recurso; forma e posição
continuam, mas rebaixadas ao que sempre deveriam ter sido: a confirmação de que a view já foi
medida e está onde este botão fica, não a decisão de quem ela é. Não achar nada virou a resposta
ordinária, não uma falha a contornar.

> **Regra 12 do material.** Um fallback de descoberta que só roda quando o alvo nomeado está ausente
> é chamado justamente quando a resposta certa não está na árvore. Se o alvo é transiente, o fallback
> não é uma rede de segurança: é um gerador de falsos positivos com cobertura quase total.

215 testes, 0 falhas; Java, lintVital Release, R8 e assinatura passaram. Falta medir o material no
alvo certo.

**Quinta tentativa: alvo certo, view errada.** O botão correto foi encontrado, e o vidro apareceu —
como um anel oblongo em volta do botão cinza, que continuou opaco por cima. Medido na captura:

| | posição absoluta | tamanho |
|---|---|---|
| vidro (host) | x 1271–1433, y 2776–2913 | **162 × 137** |
| disco cinza original | x 1306–1404, y 2808–2906 | **98 × 98** |

O `scroll_bottom` é uma **caixa de toque** de 168×138 com folga para sombra; o botão que desenha é um
filho de 98×98 encostado no canto inferior direito dela. Três sintomas, uma causa:

1. **O cinza sobreviveu.** `takeOverTargetBackground` pegou o fundo do FrameLayout, que é nulo. O
   disco é pintado pelo filho, e `takeOverFullBleedDescendants` recusou-o por regra correta: 98×98 em
   168×138 cobre **41%**, abaixo dos 85% exigidos. O limiar certo, aplicado à view errada.
2. **O vidro ficou oblongo.** `cornerRadiusDp(1000)` limita ao meio da altura (69px) contra uma
   largura de 168 — um estádio, não um círculo.
3. **A franja cromática explodiu.** `bevel=37.44px` derivou de 138px de altura e foi esfregada sobre
   um disco de 98px: mais de um terço da largura, daí o arco-íris preenchendo o anel inteiro em vez
   de um fio na borda.

Nenhum deles é do material. `findButton` agora desce do nome até a view que desenha — o maior
descendente com forma de botão redondo que tenha `background` ou seja `ImageView`, até 3 níveis — e
despeja a subárvore uma vez para que a escolha seja conferível contra o aparelho em vez de deduzida.

> **Regra 13 do material.** A view que o *nome do recurso* aponta não é necessariamente a que tem a
> forma. Um id de botão costuma nomear o alvo de toque, com folga para sombra e badge; embrulhar a
> caixa em vez do disco erra o raio, o tamanho do bisel e o dono do fundo de uma vez só — e os três
> parecem defeitos ópticos independentes.

Um segundo relato, **não medido**: o vidro aparece em força total alguns instantes antes do botão
materializar. Hipótese a verificar, não conclusão — se o WhatsApp anima a entrada com `Animation`
antiga em vez de `ViewPropertyAnimator`, `getAlpha()` permanece 1.0 durante o fade e o espelho de
visibilidade da `regra 10` não tem o que espelhar. Provável que mude sozinho ao embrulhar o filho,
que é justamente quem anima; medir depois de conferir o alvo.

215 testes, 0 falhas; build de release limpo.

### Etapa 3 aceita opticamente — e a linha ciano, que era do helper (2026-08-12)

**O botão passou.** Circular, 98×98, cinza original eliminado, chevron sobre vidro. Perfil vertical
medido em `x=1320`, fora do chevron:

| | valor |
|---|---|
| borda superior (pico) | 41,1 |
| corpo | 16,4 |
| borda inferior | 20,1 |
| **razão topo/base** | **2,0 : 1** (critério ≥ 1,7) |
| **base acima do corpo** | **3,7 luma** (critério ≤ ~5) |

Sem relevo, corpo chapado. A geometria circular não exigiu nenhum parâmetro divergente — só o alvo
certo.

**A linha verde.** Amostrando o aro de 15 em 15 graus, o arco superior destoa do resto:

| ângulo | rgb | G−R |
|---|---|---|
| 240° | (60, 87, 72) | +27 |
| 255° | (55, 89, 88) | **+34** |
| 270° | (42, 69, 76) | +27 |
| resto do aro | ~(11, 22, 28) | +9 a +11 |

O perfil radial em 270°, canal a canal, mostra **duas faixas empilhadas**: uma neutra em r=45–48,
pico (54,56,53) — o especular, que é `warm = (1.0, 0.995, 0.98)`, correto — e uma ciano em r=49–52,
pico (44,80,92), **fora** dela. Os picos por canal caem em R=47, G=49, B=50: separação de 1,5–3px,
exatamente o que a regra 5 pede. **A dispersão está certa.** O que está errado é qual flanco fica
exposto.

Primeira hipótese, **descartada pelo usuário antes de ser aplicada**: escala. A hairline é absoluta
(`hair=2.85px` idêntico na barra e no botão), então ocupa 4,4% da dimensão menor na barra e 8,7% no
botão, e um fio de 4px num arco de 150px lê como mancha. Plausível, e falso — a mesma linha aparece
**na barra flutuante**. Um relato do aparelho matou uma hipótese que a aritmética sustentava; é o
método funcionando.

A causa real é assimetria de **fundo**, não de amplitude. Os três taps eram simétricos em torno de
`-hw` e com amplitude igual, o que parecia certo e não era: o que está atrás deles não é simétrico.

```
R em d = -(hw + sep) = -4,26   → cai sobre o corpo iluminado, some no especular
G em d = -hw         = -2,85
B em d = -(hw - sep) = -1,44   → tenda alcança d = +1,41, TRANSBORDA o contorno
```

O flanco azul encosta no fundo quase preto, onde é a única coisa brilhante em pixels ao redor; o
vermelho nunca aparece. A borda então franja só frio, e uma franja de um só matiz é uma linha
colorida, não dispersão. A conta bate com a medida: `hairGain ≈ 0.26` (fundo escuro já amortece),
`col += hair * 0.26` dá +66 em 0–255 sobre uma base de 20 — o pico B medido é 76.

Correção: deslocar a hairline inteira para dentro em um `sep`, de modo que o canal mais externo
tenha o pico no centro da própria hairline em vez de cavalgar o contorno. A separação entre canais
fica intacta, que é o que carrega a cor.

> **Regra 14 do material.** Dois flancos de uma franja com a mesma amplitude não têm a mesma
> visibilidade se o que está atrás deles for diferente. O flanco interno compete com o corpo
> iluminado; o externo, com o fundo. Simetria na fórmula não é simetria na percepção — e o resultado
> é uma borda que franja um matiz só.

**Não medido no aparelho.** 215 testes, 0 falhas; build de release limpo.

### Medição da correção da regra 14 — pendência fechada, resultado negativo (2026-08-12)

Três capturas do aparelho (`Screenshot.jpg`, `Screenshot (1).jpg`, `Screenshot (2).jpg`, todas
1440×3120, nenhuma comitada — seguem a mesma regra de qualquer captura anterior). A primeira e a
segunda mostram o botão scroll-to-bottom sobre conteúdo real; a terceira mostra a barra flutuante
sobre a lista de conversas.

**Botão — coluna central, `x=1355` (fora do chevron), `--column 1355 --from 2800 --to 2935`:**

| região | valor |
|---|---:|
| pico da borda superior | **67,5** (y=2811) |
| corpo (plano, `y=2895..2910`) | **16,4** |
| pico da borda inferior | **39,2** (y=2922) |
| razão topo/base | **1,72 : 1** (critério ≥ 1,7 — passa, no limite) |
| base acima do corpo | **22,8 luma** (critério ≤ ~5 — **reprova**, 4,5× o teto) |

A razão passa por pouco porque a borda inferior também subiu, não porque ficou contida. Na medição
da Etapa 3 (pré-correção da regra 14) a borda inferior era 20,1 e ficava 3,7 acima do corpo — dentro
do critério. Agora ela é quase o dobro (39,2) e a distância ao corpo é 6× maior. **Regra 3 do
material** (a borda de baixo não é highlight) está sendo violada de novo, e não estava antes desta
mudança.

**A linha verde continua lá**, só que mudou de flanco. Varredura horizontal `--edge 1280 1430 --band
2800 2825`: entre `x=1322` e `x=1378` (56px de um arco de ~110px, centrados no topo) o pico por
coluna é dominado por verde — `(43,75,64)` em x=1328, `(60,90,88)` em x=1346, G−R chegando a **+34**.
Uma varredura fina canal a canal em `x=1340`, `y=2795..2825`, confirma que a dispersão em si está
intacta — os três canais separam por ~2px como a regra 5 pede (`B` no pico em y=2812, `G` em y=2814,
`R` em y=2816) — mas só B e G ficam visíveis contra o fundo escuro; R cai sobre o corpo iluminado e
some no specular, exatamente a assimetria que a regra 14 documentou. **A correção deslocou o grupo
inteiro de taps por um `sep` em bloco**, o que preserva o espaçamento entre canais e troca qual canal
fica por dentro — mas não muda a causa: o flanco que cai sobre o corpo aceso sempre vai desaparecer,
não importa qual canal seja. Antes era o vermelho contra o fundo e o azul sumindo no corpo (franja
fria); agora é o azul/verde contra o fundo e o vermelho sumindo no corpo (franja ainda fria, só que
com outro canal ausente). É a mesma reprovação, deslocada.

Hipótese para a próxima rodada, **não aplicada** (não dá para testar sem aparelho): o deslocamento em
bloco também empurrou o footprint total da hairline para fora, o que é o candidato mais provável para
o ganho na borda inferior (39,2 vs 20,1) — os três *tents* se sobrepõem mais fora do contorno em vez
de ficarem centrados nele. Corrigir a assimetria de visibilidade provavelmente exige mexer em
`bgLuma`/`hairGain` por canal (ou pelo menos por flanco), não só recentralizar os três taps juntos.

**Barra — coluna `x=900` e `x=1000`, fora do pill de destaque da aba:** pico da borda superior
25,0–31,2 (varia com o x, como esperado pela regra 7), corpo plano em 16,6, borda inferior 17,1 (em
`x=900`, quase indistinguível do corpo). Razão 1,7–1,85:1, base acima do corpo ≈0,5 luma — dentro dos
dois critérios, e coerente com a linha de base já validada da Fase 1.2 (31,7 / 16,6 / 17,1). A barra
não mostrou o mesmo agravamento do botão nestas capturas. Uma varredura `--edge 300 1150 --band 2838
2862` cruzou por dentro do pill verde-escuro da aba "mensagens" (mesmo y=2845 em toda coluna, uma
assinatura de gradiente de UI, não de ruído por pixel) — não serve como medida da hairline e não deve
ser reaproveitada sem excluir esse intervalo de x.

**Veredito do portão: não passa.** O critério do usuário era `topo/base >= 1,7:1` (o botão passa,
raspando), `borda inferior <= ~5 luma acima do corpo` (o botão reprova em 22,8) e `variação >= 60 ao
longo do topo sobre fundo heterogêneo` (não medido de forma limpa em nenhuma das duas capturas — a
varredura do botão ficou dentro de uma faixa estreita demais para avaliar continuidade, e a da barra
ficou contaminada pelo pill). **Nenhuma superfície nova foi iniciada** — nem cabeçalho de
configurações, nem cabeçalho de chamadas — porque a pendência que este ciclo deveria fechar continua
aberta, com um efeito colateral medido que não existia antes (regra 3 violada no botão).

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
