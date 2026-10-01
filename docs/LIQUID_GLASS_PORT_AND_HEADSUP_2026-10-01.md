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

`AppLiquidGlass` acompanha o lifecycle das activities e janelas de Dialog/PopupWindow. A aplicação acontece no slot de background da View. Não há reparenting, inserção de filho em ConstraintLayout, mudança de IDs, listeners, layout params, margens ou IME. Padding e tint são preservados na aplicação e devolvidos no desligamento. Fundos de filhos que cobrem pelo menos 90% de ambos os eixos podem ser removidos em até dois níveis e são restaurados sem sobrescrever um rebind nativo posterior.

As bolhas usam o anchor DexKit `Unreachable code: direction=` com retorno Drawable. Zero ou vários resultados desativam somente esse adapter. O drawable nativo continua fornecendo padding, dimensões, estado e máscara da silhueta. Nada modifica o conteúdo/armazenamento das mensagens.

`GlassMaterialDrawable` usa a mesma função óptica AGSL de `LiquidLens`, extraída para um construtor de shader compartilhado. Somente o background é refratado; texto e ícones continuam sendo desenhados pelo WhatsApp. A máscara DST_IN mantém a forma nativa das bolhas, inclusive cauda e agrupamento quando definidos pelo drawable retornado.

`SharedGlassBackdrop` grava a árvore da activity em bitmap reduzido, compartilhado entre seus consumidores. A gravação é postada fora de onDraw, com intervalos de pelo menos 100 ms e até 400.000 pixels. Hooks de draw excluem as superfícies de vidro da gravação para impedir realimentação/duplicação do próprio texto. A posição do sampling usa `getLocationOnScreen` tanto na origem quanto no consumidor.

O limite por frame é 24 consumidores de shader e 3.000.000 de pixels de saída. Consumidores excedentes recebem o material em camadas. Isso é um limite inicial de engenharia, não um orçamento medido no S25. A resolução do material/capacidades fica em cache por até um segundo, evitando consultas a serviços do sistema por bolha/frame.

Menus e dialogs usam a gravação da activity em primeiro plano. Ainda não há composição integral de várias janelas intermediárias, incluindo seu dim do compositor. Referências fracas e cleanup por detach/ENDED devolvem backgrounds e liberam as gravações.

### Limitações concretas desta versão

- Há um novo caminho de captura compartilhada por software. A navbar e o scroll button existentes mantêm sua captura anterior; não foi unificado todo o pipeline.
- A atualização do backdrop é limitada a 10 Hz enquanto a árvore desenha. A geometria/shader desenha no ritmo da View; o conteúdo capturado pode ficar defasado durante uma rolagem rápida.
- Software Canvas pode recusar hardware bitmaps ou conteúdo de determinadas Views. Um erro encerra a captura dessa sessão e mantém o fallback, com log.
- Android 13+ usa AGSL quando há gravação e canvas acelerado. Em APIs anteriores ou em falhas, os novos adapters usam material translúcido com rim, sem prometer blur/refração equivalentes.
- Fundos sem detalhes, padding que termina a lista antes do compositor e pais opacos podem continuar produzindo material pouco visível. Não foram portadas as alterações de geometria do WaThemer que fazem mensagens atravessarem os headers/compositor.
- Nem todos os cards, todos os modelos de mensagem, campos de pesquisa obfuscados, configurações, mídia ou telas de chamada têm targets resolvidos. Não há repaint genérico de cada row nem alteração das barras de sistema nesta rodada.
- A leitura de posição por drawable pode depender de invalidação das Views no scroll/reciclagem. Swipe-to-reply, animações, seleção, agrupamento e RTL precisam de teste físico.
- A máscara depende da opacidade do drawable nativo. Temas que já deixem esse drawable transparente podem reduzir/apagar o vidro da bolha.
- Desligar as novas categorias e voltar ao WhatsApp restaura os bindings observados. Bolhas ainda não registradas retornam ao drawable original quando a árvore é varrida; novos resultados do factory já vêm nativos.

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
- JUnit 4.13.2 (compilado do tag r4.13.2): 52 testes passaram em `LiquidGlassSettingsTest`, `AppGlassSettingsTest`, `SharedGlassBackdropBudgetTest` e `GlassSpecTest`, com o FakeSharedPreferences existente.
- Build APK/Gradle completa: não executada; `./gradlew --version` tentou baixar `gradle-8.14.5-bin.zip` e falhou com `Network is unreachable`. JDK 21/SDK/dependências completos não estão preparados nesse ambiente.
- Teste visual, AGSL em GPU, desempenho e firmware físico: pendentes. Nenhum workflow foi disparado.

## Aceitação física antes de tratar como estável

Começar com toolbars, compositor e painéis, depois ativar bolhas/cards. Confirmar mensagens enviadas/recebidas de uma e várias linhas, agrupamento e caudas, quote, mídia, voz, seleção, swipe-to-reply, teclado aberto/fechado, rolagem longa e retornos de background. Conferir Broadcast e FAB, além de Calls/Groups/Home, claro/escuro e retorno ao visual anterior ao desligar as novas opções.

Comparar frame time, uso de CPU e memória com os switches desligados. Os logs `[LiquidGlass/App]` distinguem instalação por categoria, factory de bolhas acionado e falha de resolução/captura. Se o compositor continuar plano por não existir conteúdo atrás, a próxima mudança é o port específico de wallpaper/underlay e geometria da conversa; trocar apenas parâmetros do shader não corrige essa causa.
