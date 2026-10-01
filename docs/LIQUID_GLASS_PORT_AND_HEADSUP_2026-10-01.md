# Liquid Glass no Community e viabilidade de heads-up

Inspeção de código em 01/10/2026. Base do Community: `4f4d0c64fff855508289e08ee064b5ff3261b2c4`.

## Resultado desta rodada

A implementação deixa de limitar a configuração à barra flutuante e ao botão de descer. Foram acrescentados adapters opt-in para cabeçalhos/toolbar de seleção, busca/filtros, FABs, composição, citações/rascunhos de voz, bolhas, cards de informação e painéis. Todos começam desligados. A barra e o scroll button continuam no caminho existente.

Esta é uma implementação experimental para teste físico. A lista de adapters descreve código disponível, não cobertura visual comprovada em todas as versões do WhatsApp. A inspeção não incluiu o APK instalado no telefone. Nomes ausentes deixam as Views nativas; o factory de bolhas precisa de um único resultado estrutural.

Não há alteração de SystemUI, configuração de escopo para SystemUI, workflow novo ou disparo de workflow nesta rodada.

## Fontes comparadas

| Fonte e snapshot | O que foi encontrado no código/documentação | Decisão para o Community |
| --- | --- | --- |
| [WaThemer](https://github.com/ayane-04/wathemer/tree/d39b293a47244b4bee4f8c9ec72be564c14513fa), GPL-3.0 | `GlassConversation`, `GlassBubbles`, `GlassPanels`, `GlassContactInfo`, `GlassSearch`, `GlassToolbars`; renderer próprio; wallpaper/underlay e captura com RenderNode; adaptações extensas de geometria e lifecycle | Referência principal para os pontos de integração e o anchor de bolhas. A presente adaptação reaproveita o renderer do Community e os nomes sem importar o módulo inteiro. |
| [QWEA0/Liquid-Glass-Android](https://github.com/QWEA0/Liquid-Glass-Android/tree/16afe4d8b5d08a370d3f8c50b08d7f5c6eb5da4d), MIT | `LiquidGlassView`, `BackdropCapture`, `HardwareBackdropBlur`, `WindowStackBackdrop`; engine Views com caminhos por API. A captura de janelas é do mesmo processo, e o próprio código exclui janelas de outros processos | Continuar aproveitando a matemática óptica já portada em `LiquidLens`. `WindowStackBackdrop` é referência útil para uma evolução de menus sobre múltiplas janelas. |
| [Abdullajon1881/LiquidGlass](https://github.com/Abdullajon1881/LiquidGlass/tree/72ad05c49628ea2270116b2943629cd81c0cc496), Apache-2.0 | Core, Compose e Views; `BackdropRecorder`, `LiquidGlassProviderLayout`, `GlassViewController`, renderer AGSL/tiers. O README consultado informa publicação Maven configurada, ainda sem primeiro release Central | Alternativa para uma futura captura compartilhada em GPU. Não foi adicionada como dependência nesta rodada. |
| [liuran001/WeChat-LiquidGlass](https://github.com/liuran001/WeChat-LiquidGlass) | README público descreve injeção LSPosed de navbar, integração com pager e controles nativos | Referência complementar de navegação. Não amplia por si só a cobertura do WhatsApp. |
| [Kyant0/AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass) e [shayann07/liquidglass](https://github.com/shayann07/liquidglass) | Alternativas centradas em Compose | O Community injeta Views existentes. A troca de toolkit aumentaria o escopo sem resolver automaticamente a descoberta das Views hospedeiras. Não foram portadas. |

### Correções de premissas

O WaThemer realmente tem mais pontos de integração do que a barra. Contudo, a refração transmitida usa em grande parte o wallpaper/underlay. Não se deve interpretar a lista de superfícies como prova de que cada uma refrata qualquer conteúdo dinâmico do aplicativo. Seu código também modifica margens/padding da conversa para deixar mensagens passarem sob controles, mudança que precisa ser portada e verificada separadamente.

`GlassViewController` não transforma automaticamente um ViewGroup arbitrário por uma chamada de configuração. A integração documentada pede chamadas em `dispatchDraw`, attach/detach e touch. Em LSPosed isso exige hooks adicionais ou um drawable/host que execute a integração. Seu provider também precisa gravar o conteúdo; apontar para a raiz não cria uma gravação por si só.

Os testes de matemática anunciados por uma engine ajudam a verificar fórmulas. Eles não provam ausência de crashes de classloader, clipping, problemas de teclado ou desempenho na injeção em WhatsApp/One UI.

## Implementação no Community

### Arquitetura

`LiquidGlassSettings.Surface` acrescenta oito categorias independentes. `GlassSurfaceCatalog` mapeia nomes semânticos presentes no código do WaThemer. Roots de página, `entry`, `bottom_nav_container` e `scroll_bottom` não são alvos desse catálogo.

`AppLiquidGlass` mantém os backgrounds das Views nativas, sem reparenting ou mudança de layout/IME. `clearFullBleed` foi removido integralmente: nenhum fundo de filho é apagado por tamanho. O desligamento preserva padding/tint mode atuais e updates nativos de tint, inclusive clear explícito. Rebind nativo é adotado sem sobrescrever o background novo ou empilhar wrappers.

As bolhas continuam usando DexKit com o anchor `Unreachable code: direction=` e retorno Drawable, recusando zero/vários candidatos. O wrapper encaminha state, level, visibility, hotspot/bounds, layout direction/RTL, auto-mirroring e tint/filter ao drawable que fornece a máscara. Após descoberta, cada consumidor usa o provider de sua própria janela.

`GlassMaterialDrawable` mantém o programa AGSL compilado. `LiquidLens.updateMaterialUniforms` atualiza **todos** os uniforms quando material imutável/geometria/density muda, eliminando o key parcial. Compilação AGSL inválida desativa somente aquele programa; falhas de input/matrix/recording têm retry separado. O fallback usa `GlassSpec.withoutOptics`, com fill mínimo de 72%, sem depender da capacidade BlurView/RenderScript que este drawable não implementa. Texto/ícones continuam fora do efeito.

`SharedGlassBackdrop` agora usa gravação primária GPU em RenderNode, uma vez por token Choreographer/window/frame durante pre-draw, compartilhada por todas as superfícies da janela. Cada material grava seu recorte e aplica o AGSL via RenderEffect; não há readback GPU→bitmap. Software permanece fallback a no máximo 10 Hz/400.000 pixels. GPU/software têm backoff independente e circuito temporário de até 30 segundos, recuperável; erro transitório não desliga a Session para sempre.

Exclusões pertencem ao provider. Um hook fixo em `ViewGroup.drawChild`, ativo somente durante capture, substitui os hooks progressivos em `View.draw`: percorre filhos sem reutilizar display lists contendo vidro, reaplicando matriz/alpha/scroll/clip. Um guard fixo em `RippleDrawable.draw` mantém camadas estáticas e omite a animação durante gravação GPU para impedir retargeting do animador HWUI. Se esses guards não puderem ser instalados, a captura não é publicada. Os logs reportam métodos instalados, contagem de filhos/exclusões e tempo inclusivo de captura. Esse tempo soma subtrees; não é tempo exclusivo total.

O orçamento pertence ao provider e reinicia somente quando muda o token real de frame. O custo inicial é proporcional à resolução de saída e aos taps de blur/dispersion/composição da máscara. Há tiers normal/motion/conserving, com limites de contagem 24/12/8. Scroll/layout marca motion; misses persistentes em FrameMetrics reduzem o tier com histerese; battery saver/thermal moderate+ reduz para conserving. Deadline, draw duration e GPU duration são observados quando disponíveis, com agregados a cada 300 frames. Os pesos/limites **ainda precisam de calibração no S25 Ultra**: não são medições desse aparelho.

A descoberta faz uma travessia inicial, cacheia resource IDs semânticos e acompanha filhos adicionados/backgrounds alterados. GlobalLayout reconcilia bindings/candidatos conhecidos; não faz walk completo periódico a cada 300 ms. DexKit de bolhas continua fail-closed.

`WindowStackBackdrop` usa WindowInspector (API 29+) e tokens de parentesco para compor Activity, dialogs e subwindows anteriores até a janela alvo, inserindo FLAG_DIM_BEHIND antes de cada camada. Base application vem antes de dialogs; irmãos mantêm ordem de registro. Windows acima e de outro app token são omitidas. Vidro de janela inferior usa a gravação própria já publicada naquele frame: popup sobre dialog inclui o panel inferior e dim, sem ciclo entre providers. Topologia incompleta/cíclica volta à raiz própria. Sampling mantém `getLocationOnScreen`.

### Correções da revisão

| Ponto | Mudança | Verificação disponível |
|---|---|---|
| 1 | Budget idempotente por token Choreographer | JUnit: duas Sessions/tokens e count cap |
| 2 | Retry/cooldown independentes GPU/software | JUnit: falha transitória, backoff e recuperação |
| 3 | RenderNode compartilhado por frame; software continua 10 Hz | Compilação de API; GPU físico pendente |
| 4 | Trabalho ponderado, tiers e FrameMetrics/power/thermal | JUnit de custo/tier/histerese; calibração S25 pendente |
| 5 | Sets por provider e cleanup | Inspeção de lifecycle; runtime físico pendente |
| 6 | Hook fixo de subtree, guard de ripple e counters | Compilação; custo/classes concretas no aparelho pendentes |
| 7 | Descoberta inicial/incremental; cache de resource IDs | Inspeção; churn RecyclerView físico pendente |
| 8 | Remoção integral de clearFullBleed | Inspeção: nenhum adapter apaga fundos de filhos |
| 9 | Programa reutilizado; todos os uniforms atualizados | Compilação; mudança visual de parâmetros pendente |
| 10 | Sampling recuperável separado da compilação | Retry testado; falhas GPU reais pendentes |
| 11 | Propagação nativa ampliada; matriz física abaixo | Compilação; drawables stateful reais pendentes |
| 12 | Stack de janelas com dim e vidro inferior | JUnit de ordem/isolamento/ciclo; composição física pendente |
| Inline | Fallback sem óptica com piso de 72% | JUnit em GlassSpecTest |

### Limitações concretas desta versão

- Navbar/scroll button mantêm sua engine anterior. Novos providers não unificam esse pipeline.
- A composição cobre janelas normais do mesmo app token, não SurfaceFlinger transforms, window blur-behind, teclado/system bars de outros processos, SurfaceView/vídeo protegido ou ordem arbitrária de janelas especiais. API anterior a 29 usa somente a raiz própria.
- ViewGroup customizado que não passe por drawChild, animação legacy/outline shadow e efeitos nativos complexos exigem validação. Matriz/alpha/scroll/clip não equivalem a toda operação de HWUI. Fallback protege de exceções; não prova equivalência visual. Guards de ripple não substituem teste real de toque/seleção.
- Descoberta incremental cobre addView/addViewInLayout/attachViewToParent e setBackgroundDrawable. Versões que anexem rows por outros caminhos internos podem exigir adapters adicionais. Não há polling da árvore inteira como compensação silenciosa.
- Software fallback pode atrasar até 100 ms. API anterior a 33 ou captura/óptica indisponível recebe material sem blur com piso de opacidade; não se promete refração equivalente.
- Pais opacos/ausência de underlay podem continuar produzindo material pouco visível. Não foram portadas mudanças de geometria/wallpaper do WaThemer sem validar os targets. Remover a heurística evita dano funcional; alguns alvos podem continuar opacos.

## Notificações reais / heads-up

### Referências com código disponível

[Iconify, snapshot `738bfc1aa07adf5eb31d1ea5c98d5216102a89a5`](https://github.com/Mahmud0808/Iconify/tree/738bfc1aa07adf5eb31d1ea5c98d5216102a89a5), GPL-3.0, contém [HeadsUpBlur.kt](https://github.com/Mahmud0808/Iconify/blob/738bfc1aa07adf5eb31d1ea5c98d5216102a89a5/app/src/main/java/com/drdisagree/iconify/xposed/modules/quicksettings/HeadsUpBlur.kt). É a referência mais direta encontrada para o popup real, não uma imitação em overlay.

O arquivo observa `HeadsUpManagerImpl`/`BaseHeadsUpManager`/`HeadsUpManager`, `onHeadsUpStateChanged`, `ExpandableNotificationRow`, `ActivatableNotificationView.startAppearAnimation` e `NotificationBackgroundView`. Usa `mBackgroundNormal`, restauração no detach, transição quando QS expande e tratamento de `updateBackgroundRadii`/`setCustomBackground`. Há guards para LayerDrawable, porque o caminho nativo pode assumir esse tipo ao atualizar cantos.

O blur vem de `getViewRootImpl().createBackgroundBlurDrawable()` e configura radius/cor/cantos. Essa parte aproveita a composição do sistema. Não há AGSL de refração nesse hook. O código é referência AOSP; não foi demonstrada compatibilidade com o firmware Samsung atual.

[OneUIX, snapshot `b2c46dbfb26d390b91c20c02ff0cdc40c552a6fa`](https://github.com/SoClear/OneUIX/tree/b2c46dbfb26d390b91c20c02ff0cdc40c552a6fa), AGPL-3.0, é mais relevante para lifecycle e diferenças Samsung. Seu [Notification.kt](https://github.com/SoClear/OneUIX/blob/b2c46dbfb26d390b91c20c02ff0cdc40c552a6fa/hook/src/main/java/io/github/soclear/oneuix/hook/systemui/Notification.kt) contém ajustes de notificações, inclusive branches de versão One UI, mas a inspeção não encontrou nele um renderer heads-up Liquid Glass pronto.

[HyperLight](https://github.com/KiminonawaResa/HyperLight), snapshot público `4bf98b6f4c3fd9ae87fe5233d35e8cccc188c25d`, anuncia Liquid Glass e ajustes de SystemUI/heads-up no HyperOS. A árvore pública consultada contém README, README_EN, ícone e gitignore. Não foi encontrado código-fonte de hooks/renderer para portar. Deve ser tratado como referência visual/de produto, sem concluir que é um doador de código open source.

### Por que o port para SystemUI é outro problema

O wallpaper e a árvore do WhatsApp estão no mesmo processo do módulo injetado. Uma heads-up de SystemUI costuma estar sobre uma janela de outro processo. Gravar somente a árvore da SystemUI não fornece os pixels do aplicativo que estão por baixo dela.

O `BackgroundBlurDrawable` permite que o compositor desfoque camadas por baixo. Ele não dá automaticamente ao RuntimeShader uma textura desses pixels para deslocar/refratar. Colocar RenderEffect no texto/card da notificação filtra a própria notificação, que não é o efeito desejado.

Portanto, há dois níveis de viabilidade:

1. Blur real, tint, cantos e highlights em heads-up nativa: há código de referência concreto. A adaptação depende das classes/métodos do firmware e da disponibilidade de blur do compositor.
2. Liquid Glass com refração do aplicativo por baixo: hipótese viável de engenharia, ainda sem prova no S25/One UI 9. Requer uma fonte de backdrop entre processos disponível por APIs/permissões apropriadas, respeitando conteúdo protegido, mais sincronização e orçamento de GPU. Captura de tela/bitmap contínua não pode ser assumida como solução aceitável sem medir latência, consumo e realimentação.

No Samsung é preciso identificar o modo exato de popup. O popup heads-up detalhado e o popup breve/Edge Lighting podem ter caminhos de implementação diferentes. Os hooks AOSP acima são candidatos, não confirmação de que a notificação cinza observada passa por eles.

A próxima investigação física deve ser observacional: identificar a classe real do card, sequência attach/show/dismiss, bounds/cantos/background/tint, disponibilidade do blur drawable, comportamento com teclado, QS e lockscreen. Não é necessário alterar texto, conteúdo, decisão de entrega ou privacidade das notificações para obter isso. O módulo de notificações deve ficar separado do Community, com escopo SystemUI apenas quando essa implementação for solicitada.

## Validação executada

- `git diff --check`: passou.
- Compilação Java 17 dos arquivos novos/alterados de configuração, renderer, hook e activity contra classes Android 36 e DexKit: passou. Foram usados stubs para as dependências internas/AndroidX/Material não instaladas e para símbolos de runtime Kotlin; o callback XC_MethodHook veio do fonte real do XposedBridge. Isso é uma verificação de sintaxe/tipos/API, não uma build Gradle do aplicativo.
- JUnit 4.13.2 (compilado do tag r4.13.2): 62 testes passaram em `LiquidGlassSettingsTest`, `AppGlassSettingsTest`, `SharedGlassBackdropBudgetTest`, `GlassSpecTest` e `GlassWindowOrderTest`, com o FakeSharedPreferences existente.
- Build APK/Gradle completa: não executada; `./gradlew --version` tentou baixar `gradle-8.14.5-bin.zip` e falhou com `Network is unreachable`. JDK 21/SDK/dependências completos não estão preparados nesse ambiente.
- Teste visual, AGSL em GPU, desempenho e firmware físico: pendentes. Nenhum workflow foi disparado.

## Aceitação física antes de tratar como estável

Começar com toolbars, compositor e painéis, depois bolhas/cards. Registrar versão exata do WhatsApp, firmware, refresh rate e energia. A build APK completa e esta matriz continuam **pendentes**, não são resultados de testes executados.

| Cenário físico | Verificar |
|---|---|
| Enviada/recebida; agrupamento first/middle/last | Cauda, cantos, padding, máscara sem vazamento |
| Seleção/pressed, reactions, disappearing indicator | State/level/hotspot e transições nativas |
| Swipe-to-reply parcial, quoted message, voz/draft | Tradução, máscara/clipping, composição preservados |
| Mídia/imagens/vídeo e mensagens longas | Silhueta nativa; sem retargeting de ripple/crash |
| RTL e claro/escuro | Mirroring, outline, ícones e legibilidade |
| Mudanças de material/opacity/density | Uniforms/fallback atualizados sem shader antigo |
| Dialog + popup sobre dialog; abrir/fechar repetidamente | Dialog inferior/dim no backdrop; nenhum ciclo |
| Scroll 60/120 Hz; teclado; animação de layout | Backdrop GPU sincronizado, sem fotografia atrasada |
| Battery saver/thermal; saída de conserving | Tier reduz/recupera; comparar com switches desligados |
| Falha isolada/repetida; fonte volta a existir | Retry/cooldown recupera sem reiniciar Activity |
| Rebind/tint alterado ou limpo/padding alterado; toggle off | Estado nativo preservado, sem wrapper duplicado |
| Broadcast, FAB, Calls/Groups/Home; background/resume | Funcionalidade e layout preservados |

Coletar FrameMetrics draw/GPU/misses e counters de capture antes/depois, sem conteúdo de mensagens. Comparar CPU/memória/temperatura/bateria em condições iguais. Se composer continuar plano por falta de underlay ou pai opaco, o próximo adapter precisa identificar o backing layer exato; não reintroduzir remoção geométrica genérica.
