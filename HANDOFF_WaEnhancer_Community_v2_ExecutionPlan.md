# HANDOFF — WaEnhancer Community — v2

**Status:** decisões aprovadas, consolidadas e distribuídas por ambiente de execução  
**Data:** 2 de agosto de 2026  
**Finalidade:** documento de transferência para implementação controlada entre ChatGPT, Claude Code e Codex CLI, com fronteiras explícitas para evitar conflito de contexto  
**Idioma principal do projeto:** inglês no código, commits e interface-base; documentação pode ter português e inglês  

---

## 1. Regra principal deste handoff

Todas as decisões descritas neste documento foram aprovadas. Elas devem ser tratadas como requisitos do projeto, e não como sugestões abertas para rediscussão durante a implementação.

A implementação deve priorizar, nesta ordem:

1. preservação de dados e compatibilidade;
2. segurança sem quebra funcional;
3. estabilidade no WhatsApp suportado;
4. desempenho;
5. acabamento visual;
6. novas funcionalidades.

A parte mais perigosa do projeto é a migração de preferências, providers, backups e dados privados. Nenhuma medida de segurança deve ser aplicada de forma brusca se houver risco de quebrar a leitura das configurações pelo processo do WhatsApp, perder mensagens do `Deleted for Me`, impedir importações antigas ou deixar o módulo sem iniciar.

A segurança deve ser implementada por migrações graduais, transacionais, testáveis e reversíveis. Não se deve “corrigir tudo de uma vez” apagando estruturas antigas antes de comprovar que as novas funcionam.

---

## 1A. Distribuição obrigatória por ambiente e modelo

### 1A.1 Princípio de execução

Este projeto não deve ser desenvolvido por alternância frequente entre ferramentas, dispositivos ou modelos. Cada bloco abaixo deve permanecer, tanto quanto possível, em um único ambiente até atingir seu gate de conclusão.

A prioridade é reduzir:

- perda de contexto;
- mudanças contraditórias;
- refatorações concorrentes;
- duplicação de trabalho;
- conflitos de branch;
- decisões diferentes tomadas por agentes distintos;
- risco de uma IA avançar para uma área crítica sem conhecer todas as invariantes.

Não dividir uma mesma migração crítica em pequenas partes espalhadas entre ChatGPT, Claude Code e Codex CLI. Quando houver revisão por outro modelo, a revisão deve ocorrer somente após o bloco de implementação estar completo, com build verde, testes registrados e diff estabilizado. Não deve existir ping-pong de edição entre dois agentes.

### 1A.2 Nomes dos ambientes neste documento

- **ChatGPT App — GPT-5.6 Sol:** conversa no aplicativo ChatGPT, trabalhando pelo GitHub conectado e por ciclos manuais de GitHub Actions. É o ambiente principal para trabalho amplo, visual e não destrutivo.
- **Claude Code — Anthropic:** ambiente da Anthropic, e não um “cloud genérico”. Deve ser usado para blocos críticos que exigem contexto amplo, revisão rigorosa e raciocínio de segurança.
- **Claude Code — Opus 5:** responsável preferencial pelas migrações de dados, armazenamento, IPC e auditorias críticas.
- **Claude Code — Sonnet 5:** pode ser usado para ciclos de compilação, correções mecânicas e falhas de build dentro do mesmo ambiente da Anthropic, sem mover o projeto só por esse motivo.
- **Codex CLI — Terra:** pode executar implementação local extensa, buscas globais, Gradle, testes, ADB e correções repetitivas. Não deve ser o responsável primário pela arquitetura da migração de preferências ou IPC.
- **Codex CLI — Sol:** reservado para revisão crítica ou investigação difícil dentro do ecossistema Codex, mas não é a primeira escolha para os blocos de segurança definidos para Opus 5.

### 1A.3 Regra anti-overreach

Cada agente deve ler a seção de fases e executar somente o bloco atribuído ao seu ambiente. Ao concluir seu bloco, deve produzir:

1. lista de commits realizados;
2. arquivos alterados;
3. testes executados;
4. riscos conhecidos;
5. invariantes que o próximo bloco deve preservar;
6. estado exato da branch e último commit;
7. um handoff incremental curto para o próximo agente.

O agente não deve “adiantar” o bloco seguinte apenas porque encontrou arquivos relacionados. Em especial, a conversa no ChatGPT App deve parar antes de iniciar a separação real entre armazenamento público e privado, mudanças profundas de provider/Tasker ou migrations do banco do `Deleted for Me`.

### 1A.4 Estratégia de branches

Usar uma branch principal de integração e branches grandes por bloco, evitando branches por microtarefa:

```text
integration/community
block-a-foundation-visual
block-b-safe-backup-v1
block-c-critical-storage-ipc
block-d-deleted-for-me
block-f-future-glass-ui
```

Cada branch de bloco deve ser concluída, testada e documentada antes do merge. Não manter duas IAs editando a mesma branch ao mesmo tempo.

### 1A.5 Regra para falhas de build

Falhas de build não justificam, por si só, trocar de ambiente.

- Se o bloco estiver no Claude Code, usar Sonnet 5 para os loops de Gradle/R8/manifest dentro da mesma cópia local.
- Se o bloco estiver no Codex CLI, usar Terra para os loops de build e Sol apenas quando houver falha realmente difícil ou revisão crítica.
- Se o bloco estiver no ChatGPT App, usar GitHub Actions manual enquanto os erros forem pontuais; migrar temporariamente para um CLI somente quando houver uma sequência longa de erros que exija execução local repetida.

A troca de ambiente deve ocorrer apenas no gate entre blocos, salvo emergência técnica documentada.

---

## 2. Repositório de origem e base técnica

### 2.1 Origem

Repositório upstream analisado:

- `mubashardev/WaEnhancer`

Referências relevantes:

- versão estável: tag/ref `1.7.0`;
- commit de release estável observado: `433a1c630bc1286f2db3c657a63f477aa0aa426d`;
- versão beta usada como fonte seletiva: `1.7.1-beta-4`;
- master auditado durante o planejamento: `0ce9ebe7ee9bf2fec160c466265f0901cbfba959`.

### 2.2 Estratégia de base

A base do fork deve ser a versão estável `1.7.0`.

Não fazer merge global, rebase amplo ou cherry-pick indiscriminado da beta. A beta introduziu simultaneamente:

- personalização útil da barra inferior;
- correções de CSS;
- sistema Pro;
- Helper externo;
- carregamento dinâmico de DEX e biblioteca nativa;
- licenciamento;
- Firebase;
- novos providers e bridges;
- refatorações extensas de UI e armazenamento.

A abordagem correta é portar manualmente as melhorias úteis e auditáveis da beta para a base estável, preservando o comportamento comprovadamente funcional da `1.7.0`.

### 2.3 Licença

O upstream utiliza GPLv3. O fork deve:

- continuar compatível com GPLv3;
- preservar avisos de copyright e atribuição;
- identificar claramente que é uma versão modificada e independente;
- publicar o código-fonte correspondente aos APKs distribuídos;
- não sugerir afiliação oficial com WhatsApp ou com o mantenedor original;
- documentar todas as alterações relevantes.

### 2.4 Nome inicial do fork

Nome de trabalho aprovado:

- **WaEnhancer Community**

Application ID de trabalho:

- `com.waenhancer.community`

O ID pode ser refinado antes da primeira release, mas não deve permanecer igual ao pacote oficial se o APK for assinado com outra chave. O fork precisa coexistir com o upstream e não deve fingir ser uma atualização assinada pelo autor original.

---

## 3. Diretrizes de segurança e confiança

### 3.1 Remoção completa do sistema Pro e do Helper

O fork não deve depender de:

- `com.waex.helper`;
- `ProHelper`;
- `LicenseManager`;
- `LicenseActivity`;
- `ProFeaturesActivity`;
- `PillDesignPro`;
- `libpro_native.so`;
- `appendDexPath`;
- caminhos persistidos de APK ou bibliotecas do Helper;
- verificação de assinatura, assinatura Pro, planos, “Limited Free” ou expiração de licença;
- código fechado carregado no processo do módulo ou do WhatsApp.

A remoção deve incluir UI, preferências, manifest, queries, código, ProGuard, recursos e lógica de fallback. Não basta esconder opções visuais.

O código atualmente carrega o APK do Helper, injeta DEX no classloader e tenta executar uma biblioteca nativa externa. Isso não comprova malware, mas cria uma superfície de confiança inaceitável para um fork auditável. O fork deve conter localmente todo o código que executa.

Não copiar, extrair ou reempacotar código fechado do Helper. Recursos visuais equivalentes devem ser reimplementados do zero.

### 3.2 Remoção do Firebase

O Firebase existente é usado para duas finalidades:

- Firebase Analytics;
- Firebase Crashlytics.

O app possui consentimento para “Share Anonymous Crash Logs” e habilita ou desabilita a coleta em runtime. A build beta também torna a inclusão do Firebase condicional à presença de `google-services.json`.

Decisão aprovada para o fork:

- remover Firebase Analytics;
- remover Firebase Crashlytics;
- remover `google-services.json` da arquitetura;
- remover plugins e dependências Firebase do Gradle;
- remover `FirebaseInitProvider` do manifest;
- remover a tela e preferência de consentimento do Firebase;
- não transmitir logs, métricas ou falhas para servidores externos;
- manter diagnóstico local e exportável, com redação de dados sensíveis.

### 3.3 Telemetria e rede

O fork deve funcionar sem telemetria. Toda chamada de rede deve ter finalidade explícita e visível, como:

- consultar releases do próprio fork;
- baixar uma atualização iniciada pelo usuário;
- abrir documentação.

Não incluir tokens de GitHub no APK. Qualquer string compilada no APK deve ser considerada pública.

### 3.4 Regra de não regressão por “hardening”

Medidas de segurança não podem quebrar silenciosamente:

- `XSharedPreferences`;
- preferências lidas pelo processo do WhatsApp;
- configurações já existentes;
- importação de backups antigos;
- o banco do `Deleted for Me`;
- mídias já preservadas;
- integração com WhatsApp e WhatsApp Business;
- atualizações do próprio fork.

Toda migração crítica deve seguir o padrão:

1. detectar versão e estrutura antiga;
2. criar snapshot local;
3. validar leitura da origem;
4. escrever na nova estrutura sem apagar a antiga;
5. validar contagens, hashes e tipos;
6. alternar a leitura para a nova estrutura;
7. manter fallback por pelo menos uma release estável;
8. somente depois oferecer limpeza da estrutura antiga.

Nenhuma migração pode remover dados antes de confirmação de sucesso.

---

## 4. GitHub Actions e publicação de releases

### 4.1 Trigger obrigatório

O workflow de build/release deve ser executado **somente manualmente**.

O YAML deve usar exclusivamente:

```yaml
on:
  workflow_dispatch:
```

Não adicionar triggers para:

- `push`;
- `pull_request`;
- tags;
- branches;
- `schedule`;
- publicação de release;
- alterações em arquivos.

### 4.2 Resultado do workflow

O workflow deve:

1. fazer checkout do commit selecionado;
2. configurar JDK 17;
3. validar Gradle Wrapper;
4. decodificar o keystore em diretório temporário;
5. compilar a variante release;
6. assinar o APK com a chave configurada;
7. executar verificação de assinatura;
8. verificar application ID, versionCode e versionName;
9. calcular SHA-256;
10. criar uma GitHub Release;
11. anexar o APK diretamente à GitHub Release;
12. incluir o SHA-256 no corpo da release;
13. apagar os arquivos temporários de assinatura ao final.

### 4.3 Proibição de artifacts

Não usar:

- `actions/upload-artifact`;
- artifacts temporários do GitHub Actions;
- upload separado de build intermediária.

O APK deve ser publicado diretamente como asset da GitHub Release. O checksum deve ser escrito no corpo da release, evitando um asset adicional desnecessário. Os arquivos automáticos de source code gerados pelo próprio GitHub podem permanecer.

### 4.4 Secrets de assinatura

Usar exatamente os seguintes nomes de secrets:

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

Valor esperado atualmente para o alias:

```text
batteryremapper-oneui
```

Mesmo sendo um nome originado de outro projeto, deve ser utilizado exatamente enquanto o keystore existente mantiver esse alias. Não renomear o alias sem gerar/migrar conscientemente a chave.

Mapeamento esperado:

```text
KEYSTORE_BASE64   -> conteúdo do keystore codificado em Base64
KEYSTORE_PASSWORD -> senha do keystore
KEY_ALIAS         -> alias da chave, atualmente batteryremapper-oneui
KEY_PASSWORD      -> senha da chave
```

### 4.5 Segurança do workflow

O workflow deve:

- usar `permissions: contents: write` apenas no job de release;
- nunca imprimir secrets;
- não executar `set -x` em etapas com secrets;
- criar o keystore em `$RUNNER_TEMP`;
- remover o arquivo no bloco `if: always()`;
- verificar que a assinatura final corresponde ao certificado esperado;
- falhar se o APK estiver unsigned ou assinado com debug key;
- impedir downgrade acidental de versionCode;
- preferir actions oficiais e versões fixadas por commit SHA ou versões estáveis verificadas no momento da implementação;
- não aceitar download/execução de scripts remotos não auditados.

### 4.6 Inputs manuais recomendados

O `workflow_dispatch` pode receber:

- `version_name`;
- `version_code`;
- `tag_name`;
- `release_title`;
- `prerelease`;
- `release_notes` ou opção para usar `CHANGELOG.md`.

O workflow deve validar consistência entre inputs e `gradle.properties`. Divergência deve falhar em vez de publicar um APK rotulado incorretamente.

---

## 5. Arquitetura de preferências e migração segura

### 5.1 Problema atual

O projeto mistura no mesmo conjunto de preferências:

- configuração visual;
- flags de hooks;
- caminhos internos;
- status de licença;
- possível keybox e material privado;
- caches;
- dados usados pelo processo do WhatsApp.

O exportador atual percorre `SharedPreferences.getAll()` e grava tudo. Isso permitiu exportar material de keybox, certificados, chave privada e caminhos do Helper.

### 5.2 Arquitetura desejada

Separar conceitualmente:

#### `public_config`

Somente chaves necessárias aos hooks do WhatsApp, sem segredos:

- flags booleanas de recursos;
- parâmetros visuais;
- limites numéricos;
- opções de comportamento;
- nome de preset;
- CSS autorizado.

#### `private_config`

Dados que não precisam ser lidos diretamente pelo processo do WhatsApp:

- tokens locais;
- segredo da integração Tasker;
- metadados de segurança;
- estado de migração;
- credenciais ou material criptográfico, quando inevitável;
- informações internas do updater.

#### bancos e arquivos privados

- banco do `Deleted for Me`;
- banco de histórico;
- mídias preservadas;
- logs locais;
- snapshots de migração.

### 5.3 Migração gradual obrigatória

Não substituir imediatamente todo o mecanismo de preferências.

Fases internas da migração:

1. **Inventário:** catalogar todas as chaves e quem as lê.
2. **Schema:** criar `PreferenceSchema` com tipo, default, mínimo, máximo, step, sensibilidade e versão.
3. **Leitura compatível:** `SafePrefs` deve aceitar valores legados como `Integer`, `Float` ou `String` quando apropriado.
4. **Shadow write:** por uma versão, escrever na estrutura nova e antiga.
5. **Dual read:** preferir nova, usar antiga como fallback.
6. **Validação:** comparar valores efetivos e registrar divergências localmente.
7. **Switch:** remover dependência de leitura antiga somente após testes.
8. **Cleanup opcional:** nunca automático na primeira release estável.

### 5.4 Preferências compartilhadas com WhatsApp

A longo prazo, evitar um arquivo inteiro world-readable. A alternativa desejada é um provider read-only que exponha somente chaves públicas para os UIDs autorizados.

Entretanto, isso deve ser implementado após estabilizar a base. Até lá:

- nunca armazenar segredos no arquivo lido via `XSharedPreferences`;
- manter compatibilidade com o mecanismo antigo;
- validar que o WhatsApp recebe todas as alterações;
- oferecer fallback controlado.

---

## 6. Backup e restauração

### 6.1 Novo formato

Substituir o JSON bruto por um formato versionado.

Formato conceitual de configuração:

```json
{
  "schemaVersion": 1,
  "appVersion": "1.8.0-alpha1",
  "createdAt": "2026-08-02T22:00:00-03:00",
  "settings": {
    "floating_bottom_bar": true,
    "floating_bottom_bar_radius": 22
  }
}
```

### 6.2 Allowlist

A exportação de configuração deve usar allowlist explícita. Nunca exportar automaticamente:

- keybox;
- chaves privadas;
- certificados privados;
- tokens;
- secrets;
- caminhos de APK ou biblioteca;
- caminhos absolutos do dispositivo;
- dados de licença;
- IDs de instalação;
- caches;
- heartbeat;
- logs de falha completos;
- configurações internas de classloader.

### 6.3 Importação transacional

A importação deve:

1. ler e validar o arquivo sem alterar preferências;
2. impor limite de tamanho;
3. validar versão de schema;
4. validar tipos e intervalos;
5. ignorar chaves desconhecidas;
6. migrar nomes antigos;
7. ajustar valores fora de faixa de forma previsível;
8. criar snapshot da configuração atual;
9. aplicar tudo em uma única transação;
10. reiniciar apenas os componentes necessários;
11. apresentar relatório de importação.

Nunca apagar todas as preferências antes de concluir a validação.

### 6.4 Full backup com `Deleted for Me`

O fluxo de exportação deve oferecer um backup completo contendo:

- configurações permitidas;
- mensagens do `Deleted for Me`;
- metadados das mídias preservadas;
- arquivos de mídia, quando selecionado;
- versão do schema dos bancos;
- hashes de integridade.

Estrutura conceitual:

```text
WaEnhancerBackup.waebackup
├── manifest.json
├── config.json
├── deleted_for_me.jsonl ou deleted_for_me.sqlite
├── media_manifest.json
└── media/
```

Opções na UI:

- `Settings` — ligado por padrão;
- `Deleted for Me messages` — ligado por padrão;
- `Deleted media` — selecionável, com estimativa de tamanho;
- `Local diagnostics` — desligado por padrão.

O backup completo contém informações privadas e deve, por padrão, ser criptografado por senha. A criptografia deve usar formato autenticado e versionado, por exemplo AES-256-GCM com chave derivada por Argon2id ou PBKDF2 robusto quando Argon2id não estiver disponível.

Não usar apenas uma chave vinculada ao Android Keystore para backups portáteis, porque isso impediria restauração em outro aparelho. O Android Keystore pode proteger caches locais, mas o arquivo exportável precisa de senha do usuário.

### 6.5 Compatibilidade de backups antigos

O importador deve reconhecer:

- JSON legado do upstream;
- backups do fork anteriores;
- full backup novo.

Ao importar JSON legado:

- ignorar permanentemente material sensível;
- avisar que segredos não foram importados;
- normalizar valores de slider;
- não reintroduzir caminhos do Helper;
- manter um relatório local da migração.

---

## 7. Providers, IPC e componentes exportados

### 7.1 `DeletedMessagesProvider`

O provider atual é exportado e oferece operações muito amplas, incluindo mídia, preferências e sincronização. O fork deve dividir responsabilidades.

Arquitetura desejada:

#### `ConfigBridgeProvider`

- somente leitura;
- somente chaves de `public_config`;
- nenhuma leitura genérica de qualquer chave;
- nenhum `put_preference` genérico;
- validação de UID chamador.

#### `DeletedDataProvider`

- operações específicas de mensagens e mídia;
- nenhuma operação arbitrária;
- mídia externa read-only, salvo endpoint de ingestão autenticado e estritamente validado;
- validação de caminhos canônicos;
- IDs e extensões sanitizados;
- validação de UID.

Chamadores aceitos:

- UID do próprio módulo;
- UID atual de `com.whatsapp`;
- UID atual de `com.whatsapp.w4b`, quando suportado.

Nunca confiar apenas no package name enviado por Intent.

### 7.2 Autoridades

Todas as authorities devem derivar de `BuildConfig.APPLICATION_ID`. Não manter constantes fixas como `com.waenhancer.provider` se o manifest usa `${applicationId}.provider`.

### 7.3 Activities e receivers exportados

Auditar todos os componentes. Default:

- `android:exported="false"`;
- exportar somente quando existir caso de uso externo documentado;
- usar permissões signature quando aplicável;
- validar calling UID;
- preferir Intents explícitos.

---

## 8. Integração Tasker

A automação de envio não pode aceitar broadcasts arbitrários de qualquer aplicativo.

Requisitos:

- integração desligada por padrão;
- segredo/token aleatório gerado por instalação;
- allowlist de pacotes autorizados;
- Intents explícitos;
- validação de UID quando possível;
- rate limit;
- deduplicação;
- histórico local;
- opção de exigir confirmação antes do envio;
- conteúdo de mensagens nunca deve aparecer em logs de diagnóstico sem consentimento explícito.

A compatibilidade com perfis Tasker existentes deve ser preservada por uma fase de migração. Durante uma versão, pode existir um modo legado claramente marcado como inseguro e desligado por padrão, apenas para facilitar a transição.

---

## 9. Atualizador do aplicativo

### 9.1 Fonte

O updater deve consultar somente releases do repositório do fork.

### 9.2 Verificações obrigatórias

Antes de instalar:

- HTTPS válido;
- URL pertencente ao release esperado;
- SHA-256 compatível com o publicado;
- application ID esperado;
- certificado de assinatura esperado;
- versionCode maior que o instalado, salvo ação explícita de downgrade;
- tamanho plausível do arquivo;
- APK parseável.

### 9.3 Instalação root

Não usar `pm install -d` por padrão. Downgrade deve ser uma ação avançada e explícita.

Para a primeira versão do fork, priorizar o instalador padrão do Android. A instalação root pode ser reintroduzida após auditoria, com confirmação e verificações completas.

---

## 10. Floating Bottom Bar

### 10.1 Objetivo

Portar a personalização da beta para a base estável, mantendo o glassmorphism funcional e removendo qualquer dependência Pro.

### 10.2 Controles aprovados

- habilitar/desabilitar barra flutuante;
- glassmorphism;
- cor de preenchimento;
- raio;
- margem inferior;
- margem horizontal;
- offset vertical do FAB;
- opacidade do glass;
- tamanho do ícone;
- tamanho do texto;
- padding vertical;
- espaçamento ícone–label;
- modo de esconder ao rolar;
- reset exclusivo da barra;
- presets.

### 10.3 Correção dos sliders

Criar `PreferenceSchema` centralizado. Antes de chamar `Slider.setValue()`:

- converter tipo legado;
- aplicar clamp;
- alinhar ao `stepSize`;
- persistir a normalização somente após a tela abrir com sucesso.

O raio deve aceitar até 64 dp ou oferecer `Fully rounded`, calculado como metade da altura real. Um valor legado de 64 nunca pode causar crash em slider limitado a 48.

### 10.4 Altura da pílula

Adicionar:

```text
Pill Height
- Automatic
- Manual
```

No modo automático, usar ícone + texto + spacing + padding. No modo manual, permitir aproximadamente 48–96 dp, sujeito a teste visual.

### 10.5 FAB

Modos aprovados:

- `Default`;
- `Minimal`;
- `Hidden`.

`Minimal` deve permitir:

- tamanho;
- raio;
- opacidade;
- cor do fundo;
- cor do ícone;
- margem lateral;
- offset vertical.

`Hidden` deve usar `View.GONE` e impedir que a lógica da barra continue reposicionando um FAB invisível.

### 10.6 Indicador da aba selecionada

Adicionar seção `Selected Tab Indicator`:

- largura: automática ou manual;
- altura: automática ou manual;
- raio;
- padding horizontal;
- padding vertical;
- offset vertical;
- cor;
- opacidade;
- mostrar/ocultar indicador.

Resolver preferencialmente o ID interno do active indicator do Material Navigation. Se não existir, aplicar o fundo ao icon container. O default deve preservar o comportamento atual.

### 10.7 Presets aprovados

- `Stable Glass` — equilíbrio visual e maior compatibilidade;
- `Compact` — altura e espaçamentos reduzidos;
- `Accessibility` — alvos maiores, texto maior e contraste reforçado;
- `Advanced Glass` — preset futuro, ligado ao tema Glass completo descrito no Bloco F.

---

## 11. CSS e sistema de temas

### 11.1 Correção do parser

Portar as regras ProGuard/R8 que preservam jStyleParser e evitam `NoSuchFieldException: FUNCTION`.

Manter e aprimorar:

- cache compilado do CSS;
- hash por CSS e versão do WhatsApp;
- mapas por resource ID;
- cache de views processadas;
- limpeza de caches antigos.

### 11.2 Segurança e rollback do CSS

Adicionar:

- validação antes de salvar;
- botão `Test theme`;
- timeout e rollback automático;
- último CSS válido;
- safe mode após crashes repetidos;
- opção de iniciar sem customizações;
- relatório de seletores não resolvidos;
- limite de tamanho;
- limite de imagens e memória;
- limpeza manual de cache.

### 11.3 Element Inspector

Criar uma feature de desenvolvimento chamada `Element Inspector`.

Fluxo proposto:

1. usuário abre WaEnhancer Community;
2. ativa `Identify UI Elements`;
3. escolhe `Open WhatsApp`;
4. o WhatsApp abre em modo de inspeção temporário;
5. um toque longo seleciona a view sob o dedo;
6. aparece um painel/overlay com informações;
7. o usuário pode copiar um seletor ou criar uma regra inicial.

Informações permitidas:

- resource entry name;
- resource ID hexadecimal;
- package do recurso;
- classe completa da View;
- bounds;
- visibility;
- alpha;
- parent chain resumida;
- content description, com redação quando sensível;
- seletor CSS sugerido;
- se o ID é estável, dinâmico ou não resolvido.

Ações:

- `Copy ID`;
- `Copy class`;
- `Copy selector`;
- `Add to Custom CSS`;
- `Inspect parent`;
- `Inspect child`;
- `Exit inspector`.

Regras de privacidade:

- não copiar automaticamente conteúdo de mensagens;
- não exibir número, nome ou texto de conversa sem uma ação explícita;
- redigir texto por padrão;
- não gravar a hierarquia completa em disco;
- encerrar o modo após timeout, force-stop ou reinício;
- não manter hooks globais quando o inspector estiver desligado.

Implementação preferida:

- overlay dentro da própria janela/processo do WhatsApp;
- não depender de `SYSTEM_ALERT_WINDOW`;
- hook temporário em eventos de toque ou long-click;
- borda de destaque desenhada sobre a View selecionada;
- sessão protegida por token e flag de curta duração.

### 11.4 Tema Advanced Glass / iOS Glass

No Bloco F, criar um tema próprio e totalmente aberto inspirado na linguagem visual Glass do iOS.

Nomes de trabalho:

- `Advanced Glass` para o preset;
- `iOS Glass` ou `Liquid Glass` para a engine/tema, conforme nomenclatura final.

Regras:

- não copiar código, assets ou implementação do `PillDesignPro` fechado;
- desenvolver do zero;
- usar blur real quando suportado;
- fallback elegante para translucidez sem blur;
- bordas luminosas sutis;
- sobreposição de highlights;
- contraste adaptativo;
- tratamento específico para modo claro e escuro;
- reduzir overdraw e recomposição;
- permitir desligar animações;
- respeitar redução de movimento/acessibilidade;
- funcionar em barra, dialogs, cards e áreas selecionadas sem transformar toda a UI em vidro ilegível.

O Claude Code da Anthropic pode usar, como referência de design, uma skill local chamada `iOS Glass`, caso esteja disponível. Essa skill não deve se tornar dependência de build nem fonte de código fechado; serve apenas como guia visual e arquitetural.

### 11.5 Tema global por cor de acento

A seleção de cor existente está quebrada: escolher Blue, Cyan, Purple etc. não propaga corretamente e o WhatsApp continua verde.

Implementar uma engine semântica de tokens, não uma simples substituição cega de todos os pixels verdes.

Tokens mínimos:

- `primary`;
- `onPrimary`;
- `primaryContainer`;
- `onPrimaryContainer`;
- `secondary`;
- `surface`;
- `surfaceVariant`;
- `onSurface`;
- `outline`;
- `link`;
- `selection`;
- `fab`;
- `activeIndicator`;
- `unreadBadge`;
- `outgoingBubble`;
- `incomingBubble`;
- `onOutgoingBubble`;
- `onIncomingBubble`;
- `success`;
- `warning`;
- `error`.

Presets iniciais:

- Green;
- Blue;
- Cyan;
- Purple;
- Orange;
- Red;
- Pink;
- Monet/Automatic, quando disponível.

A engine deve:

- mudar a cor de acento do módulo e do WhatsApp injetado;
- recolorir botões, FAB, active indicator, toggles, badges, links e elementos relevantes;
- oferecer opção de aplicar às bolhas;
- calcular `onColor` claro ou escuro automaticamente;
- ajustar tons quando o contraste for insuficiente;
- manter contraste mínimo equivalente a WCAG 4.5:1 para texto normal e 3:1 para elementos grandes/controles quando tecnicamente aplicável;
- testar light, dark e AMOLED;
- animar a transição apenas depois da engine estável.

Precedência:

1. personalização específica do usuário;
2. override específico de bolha;
3. preset global;
4. cor original do WhatsApp.

Assim, o usuário pode escolher tema Cyan global e ainda definir manualmente uma cor específica para a bolha de saída.

---

## 12. Ícones das configurações

### 12.1 Problema

Na lista de configurações, apenas algumas entradas mostram ícone, como `Custom Privacy`. As demais deveriam mostrar ícones próprios, por exemplo:

- Ghost Mode com fantasma;
- Privacy com escudo/cadeado;
- Calls com telefone;
- Media com imagem/download;
- Customization com paleta;
- Tasker/Automation com automação;
- Deleted for Me com lixeira/arquivo;
- General com sliders;
- About com informação.

### 12.2 Fontes a auditar

- `app/src/main/res/raw/waex_settings_map.json`;
- `WdsSettingsTileRenderer.java`;
- `WdsSettingsNavigator.java`;
- `SettingsInjector.java`;
- `FeatureCatalog.java`;
- resolução dinâmica de drawables e tints.

### 12.3 Solução

Criar um `SettingsIconRegistry` central:

```text
screenId/prefKey -> drawable resource -> fallback vector
```

Requisitos:

- ícone para cada categoria e sub-screen;
- fallback visível, nunca espaço vazio silencioso;
- tint baseado no tema;
- contraste correto;
- dimensões consistentes;
- cache de drawables;
- diagnóstico quando um nome de drawable não for resolvido;
- testes para todos os IDs do JSON.

### 12.4 Ícone de entrada do WaEnhancer

Não usar o mesmo ícone de engrenagem das configurações nativas do WhatsApp.

Ícone aprovado como direção visual:

- sliders/tune com pequeno brilho/sparkle;
- alternativa: varinha/sparkle;
- nunca uma engrenagem idêntica à do WhatsApp.

O título também deve ser inequívoco, como `WaEnhancer Community`, evitando duas entradas visualmente chamadas apenas de “Settings”.

---

## 13. Deleted for Me

### 13.1 Decisão de produto

A função será mantida e expandida.

Objetivos:

- preservar mensagens que o próprio usuário apagou para si;
- manter esses dados após atualizações;
- permitir backup e restauração;
- preservar mídias quando o recurso estiver habilitado;
- permitir exclusão definitiva pelo usuário dentro do `Deleted for Me`.

### 13.2 Persistência em updates

Atualização normal do APK não deve apagar dados internos. A aplicação deve usar migrações versionadas de banco.

Requisitos:

- schemaVersion do banco;
- migrações incrementais;
- backup/snapshot antes de migrações destrutivas;
- transação única;
- `PRAGMA integrity_check` após migração;
- comparação de contagem de registros;
- nenhuma `fallbackToDestructiveMigration`;
- nenhuma limpeza automática de banco na atualização.

### 13.3 Migração do pacote upstream para o fork

Como o fork terá outro application ID e assinatura, os dados do pacote oficial não serão herdados automaticamente.

Criar um assistente de migração:

- opção preferida: exportar backup no app antigo e importar no fork;
- opção avançada com root: copiar snapshot do banco e mídia do pacote antigo;
- nunca copiar banco enquanto estiver aberto sem checkpoint;
- force-stop controlado antes do snapshot;
- copiar para diretório temporário;
- verificar integridade;
- importar registros, não substituir cegamente o banco novo;
- deduplicar por ID/chave composta;
- manter arquivo de origem até confirmação.

### 13.4 Deleted media

Quando habilitado:

- interceptar a exclusão antes de a mídia ficar inacessível;
- copiar a mídia para diretório privado do fork;
- registrar tipo MIME, tamanho, hash, mensagem associada, timestamp e nome seguro;
- usar nome interno por hash/UUID, não nome fornecido externamente;
- evitar path traversal;
- deduplicar por SHA-256;
- aplicar quota configurável;
- exibir uso de armazenamento;
- permitir exclusão individual ou em lote;
- excluir arquivo somente quando nenhuma mensagem referenciar o mesmo conteúdo;
- nunca expor arquivos por provider sem validação.

UI do `Deleted for Me`:

- abas `Messages` e `Media`;
- filtro por chat, data e tipo;
- busca local;
- preview seguro;
- opção `Restore/export media`;
- opção `Delete permanently` com confirmação;
- indicador de tamanho e retenção.

### 13.5 Backup

Mensagens devem ser incluídas por padrão no full backup. Mídias podem ser opcionais devido ao tamanho, mas a UI deve deixar claro quando estão ou não incluídas.

---

## 14. Diagnóstico local

Substituir telemetria por um sistema local:

- ring buffer com tamanho máximo;
- categorias de log;
- timestamps;
- versão do módulo e do WhatsApp;
- status dos hooks;
- falhas por feature;
- redaction de números, nomes, JIDs, mensagens e caminhos privados;
- botão `Export diagnostic report`;
- preview antes de compartilhar;
- opção de incluir stack traces;
- nunca incluir keybox, tokens ou conteúdo de mensagens automaticamente.

Adicionar `Safe Mode`:

- iniciar sem CSS;
- iniciar sem temas;
- iniciar somente com features essenciais;
- detectar crash loop;
- reativação manual por grupos.

---

## 15. Plano de implementação por blocos e fases

A distribuição abaixo é obrigatória. Ela foi desenhada para manter blocos grandes em ambientes únicos e evitar alternância excessiva entre agentes.

## Bloco A — ChatGPT App com GPT-5.6 Sol

### Escopo geral

Este é o primeiro e maior bloco não destrutivo. Deve ser realizado em uma nova conversa no ChatGPT App, usando este handoff como especificação principal. O trabalho deve ocorrer pelo GitHub conectado, com commits pequenos e GitHub Actions exclusivamente manual para validação.

Este bloco reúne as antigas Fases 0, 1, 3 e a parte visual não crítica da Fase 4. Não deve entrar na migração real de armazenamento nem em mudanças profundas de IPC.

### Fase A0 — fundação e governança

- criar fork da tag `1.7.0`;
- preservar histórico e licença;
- renomear app e package;
- configurar JDK 17;
- criar workflow manual de release;
- configurar assinatura com os quatro secrets;
- publicar APK diretamente em Releases;
- não usar Actions artifacts;
- criar `SECURITY.md`, `PRIVACY.md`, `MIGRATIONS.md` e `ARCHITECTURE.md`;
- registrar baseline de performance e screenshots da 1.7.0.

**Gate A0:** APK release assinado, instalável, módulo carregando e sem alterações funcionais grandes.

### Fase A1 — remoção Pro, Helper e Firebase

- remover todas as classes e recursos Pro;
- remover Helper, DEX externo e `.so` externo;
- remover queries e paths do Helper;
- remover Firebase e consentimento;
- limpar Gradle, manifest e ProGuard;
- manter glass clássico aberto;
- executar busca estática por referências proibidas.

Termos que devem retornar zero resultados relevantes:

```text
com.waex.helper
ProHelper
LicenseManager
PillDesignPro
appendDexPath
libpro_native.so
FirebaseAnalytics
FirebaseCrashlytics
```

**Gate A1:** app funciona sem Helper instalado e não realiza telemetria.

### Fase A2 — Floating Bottom Bar e acabamento visual principal

- portar activity e preview da beta;
- corrigir crash de sliders com clamp e schema local dos controles;
- remover designs Pro;
- manter glassmorphism;
- adicionar altura automática/manual;
- adicionar `Fully rounded`;
- FAB `Default`, `Minimal` e `Hidden`;
- indicador selecionado configurável;
- presets `Stable Glass`, `Compact` e `Accessibility`;
- reset exclusivo;
- live preview confiável;
- corrigir jStyleParser e regras ProGuard do CSS;
- adicionar rollback básico de CSS e safe mode inicial;
- implementar `SettingsIconRegistry`;
- corrigir ícones ausentes nas configurações;
- substituir a engrenagem redundante do WaEnhancer;
- implementar a primeira versão da engine semântica de accent color;
- corrigir presets Green, Blue, Cyan, Purple, Orange, Red e Pink;
- preservar overrides específicos das bolhas.

**Gate A2:** barra funcional em Android 16, gestos e três botões; CSS simples funciona; ícones aparecem; presets alteram a UI; ausência de regressão perceptível em relação à 1.7.0.

### Limite explícito do Bloco A

O ChatGPT App deve parar antes de:

- separar fisicamente `public_config` e `private_config`;
- substituir XSharedPreferences por provider;
- reescrever providers exportados;
- alterar o protocolo do Tasker;
- migrar bancos do `Deleted for Me`;
- incluir mídia binária em backups;
- criar migrations destrutivas ou irreversíveis.

Pode preparar interfaces, schemas, testes e documentação, mas não executar essas migrações críticas.

---

## Bloco B — ChatGPT App com GPT-5.6 Sol

### Escopo geral

Este bloco permanece no mesmo ambiente do Bloco A, idealmente na mesma conversa ou em uma continuação com handoff incremental. O objetivo é corrigir o vazamento do export atual sem alterar ainda a arquitetura interna de armazenamento.

### Fase B1 — backup seguro v1 sem migração de storage

- criar `BackupCodec` versionado;
- implementar allowlist explícita de configurações exportáveis;
- excluir keybox, certificados, chaves privadas, tokens, paths, licença, caches e dados internos;
- validar o JSON inteiro antes de qualquer escrita;
- adicionar schema versionado;
- normalizar tipos e limites;
- criar snapshot da configuração atual antes de importar;
- usar aplicação transacional das preferências atuais;
- manter o mesmo mecanismo de armazenamento existente;
- criar testes unitários de exportação, importação, clamp, tipos errados e JSON legado;
- produzir relatório de importação;
- adicionar aviso explícito de privacidade na UI.

### Regra de segurança do Bloco B

Este bloco corrige o vazamento sem mexer na ponte que já funciona. Não implementar ainda:

- `public_config` e `private_config` físicos;
- shadow write entre storages;
- dual read cross-storage;
- provider read-only substituindo preferências;
- migração de segredos;
- backup de banco ou mídia.

**Gate B1:** export novo não contém segredos; JSON legado é importado sem reintroduzir dados sensíveis; configurações atuais continuam sendo lidas pelo WhatsApp exatamente como antes.

---

## Bloco C — Claude Code da Anthropic com Opus 5

### Escopo geral

Este é o bloco crítico de arquitetura de segurança e dados. Deve ser executado de ponta a ponta no Claude Code, usando Opus 5 como modelo principal. Não delegar sua arquitetura ao Terra.

O bloco deve começar somente após A e B estarem merged, com build release verde e testes de backup v1 passando.

### Fase C1 — inventário integral de preferências e armazenamento

- mapear todas as chaves;
- identificar todos os leitores e escritores;
- classificar cada chave como pública, privada, cache, segredo, runtime ou obsoleta;
- mapear processos, UIDs e dependências de XSharedPreferences;
- documentar invariantes antes de editar;
- produzir matriz `key -> writer -> reader -> process -> sensitivity -> migration`.

**Executado.** O inventário resultante está no [Anexo A](#anexo-a--inventário-c1-executado), que é a fonte da qual
`PreferenceSchema` e a allowlist de backup são derivados na Fase C2. O manifesto das
funções removidas por falta de implementação está na secção 5 do anexo.

### Fase C2 — migração segura para armazenamento público e privado

- criar `PreferenceSchema` definitivo;
- criar `SafePrefs`;
- implementar `public_config` e `private_config`;
- manter dual read e shadow write durante transição;
- preferir nova estrutura, com fallback legado;
- nunca apagar a origem na primeira versão;
- validar equivalência de valores;
- registrar divergências localmente e de forma redigida;
- adicionar rollback;
- separar segredos do arquivo acessível ao processo do WhatsApp;
- manter compatibilidade com backups antigos.

### Fase C3 — providers, broadcasts e Tasker

- dividir responsabilidades do provider;
- criar authorities dinâmicas;
- remover get/put genérico de preferências;
- validar calling UID;
- restringir mídia e dados;
- proteger paths canônicos;
- revisar todas as activities, receivers e services exportados;
- migrar Tasker para token por instalação, allowlist e intents explícitos;
- manter modo legado temporário, inseguro e desligado por padrão;
- implementar rate limit, deduplicação e histórico local;
- garantir que conteúdo de mensagem não vaze em logs.

### Fase C4 — hardening do updater e permissões

- reduzir permissões amplas;
- revisar `allowBackup`;
- verificar SHA-256 e certificado do updater;
- remover downgrade automático;
- revisar componentes exportados;
- criar testes de UID, provider, Tasker e importação.

### Regras do Bloco C

- Opus 5 é responsável pela arquitetura e pela implementação principal.
- Sonnet 5 pode ser usado somente para loops mecânicos de build no mesmo Claude Code, sem redesenhar a arquitetura.
- Não alternar edições com ChatGPT ou Codex durante o bloco.
- Ao final, fazer uma única consolidação e entregar branch pronta para review.

**Gate C:** configurações preservadas em upgrade; nenhum segredo permanece no storage público; provider rejeita UID não autorizado; Tasker rejeita chamadas inválidas; backups legados continuam importáveis; rollback testado.

---

## Bloco D — Codex CLI com Terra, seguido de uma auditoria única com Opus 5

### Escopo geral

Este bloco implementa o `Deleted for Me` completo, incluindo banco, mídia e full backup. A implementação pode ser iniciada e conduzida no Codex CLI com Terra porque exige trabalho local extenso, Gradle, SQLite, testes, arquivos binários, ADB e ciclos repetitivos.

A arquitetura deve seguir integralmente as decisões deste handoff e as invariantes produzidas pelo Bloco C. Terra não deve redesenhar a migração de preferências ou o IPC já estabilizados.

### Fase D1 — banco e migrations

- inventariar schema atual;
- criar migrations incrementais;
- snapshot antes de migration;
- transações;
- `PRAGMA integrity_check`;
- contagem e checksums;
- nenhuma migration destrutiva automática;
- preservar dados em updates;
- assistente de migração do pacote upstream.

### Fase D2 — Deleted Media

- capturar e preservar mídia quando habilitado;
- armazenamento privado;
- hash, UUID, MIME, tamanho e metadata segura;
- deduplicação;
- quota e retenção;
- UI Messages/Media;
- exclusão definitiva controlada;
- validação de paths;
- provider mínimo e autorizado.

### Fase D3 — full backup e restore

- incluir mensagens por padrão;
- incluir mídia opcionalmente;
- manifest e hashes;
- backup criptografado por senha;
- restore transacional;
- deduplicação;
- relatório final;
- teste de corrupção e rollback.

### Revisão do Bloco D

Após Terra concluir implementação, build, testes e documentação, congelar o diff. Em seguida, executar uma única revisão completa no Claude Code com Opus 5.

A revisão Opus deve focar em:

- perda de dados;
- migrations;
- consistência transacional;
- criptografia do backup;
- path traversal;
- exposição por provider;
- concorrência;
- comportamento em upgrade, downgrade e restauração.

Correções resultantes devem ser aplicadas em um único ciclo final, preferencialmente no mesmo ambiente da revisão ou devolvidas ao Terra como uma lista fechada. Não fazer ping-pong contínuo.

**Gate D:** atualizar APK, migrar banco, exportar, restaurar e preservar mídia sem perder registros; auditoria Opus aprovada; testes de corrupção e rollback passando.

---

## Bloco E — estabilização de builds, releases e compatibilidade

### Regra de ambiente

Este não é um bloco de arquitetura. Ele deve permanecer no ambiente que já possui o clone e o contexto do bloco anterior.

- Após Bloco C no Claude Code: usar Sonnet 5 para Gradle, R8, manifest merger, resources e correções mecânicas.
- Após Bloco D no Codex CLI: usar Terra para Gradle, R8, testes, ADB e logcat; usar Sol somente para falha realmente difícil.

Não transferir o projeto apenas para corrigir um build.

### Escopo

- release build;
- R8 e ProGuard;
- assinatura;
- manifest merger;
- resources;
- compatibilidade de WhatsApp;
- performance;
- CI manual;
- checksums;
- release notes;
- testes em aparelho.

**Gate E:** release reproduzível, assinada, publicada manualmente, sem regressões de runtime e com matriz de testes preenchida.

---

## Bloco F — futuro visual e recursos avançados no Claude Code da Anthropic

### Escopo geral

Este bloco é posterior à estabilização de armazenamento, IPC e `Deleted for Me`. Deve ocorrer no Claude Code da Anthropic porque pode usar skills locais de design e manter o contexto visual concentrado em um só ambiente.

### Fase F1 — Advanced Glass / iOS Glass / Liquid Glass

- desenvolver do zero;
- não copiar Helper ou assets Pro;
- usar skill local `iOS Glass` apenas como referência;
- blur real com fallback;
- highlights, bordas, refração simulada e contraste adaptativo;
- acessibilidade e redução de movimento;
- presets `Advanced Glass` e variantes;
- integração com tokens de tema;
- testes de desempenho e overdraw.

Modelo recomendado:

- Sonnet 5 pode conduzir a implementação visual e iterativa dentro do Claude Code;
- Opus 5 pode realizar uma única revisão final de arquitetura, acessibilidade e performance;
- não alternar continuamente entre os dois.

### Fase F2 — Element Inspector

- modo temporário de inspeção;
- seleção por toque/long-press;
- overlay local;
- resource ID, classe, bounds, parent chain e seletor sugerido;
- redaction de conteúdo sensível;
- copiar seletor e criar regra CSS;
- nenhum overlay global permanente;
- nenhum log de mensagens.

### Fase F3 — aba `You` e recursos futuros

- aba real com avatar;
- navegação ao perfil;
- compatibilidade com ViewPager, Meta AI e abas ocultas;
- indicador animado;
- editor visual de tema;
- perfis retrato/paisagem;
- quick toggle de presets.

**Gate F:** recursos visuais não degradam performance, acessibilidade ou estabilidade; nenhuma dependência fechada; todas as features podem ser desligadas individualmente.

---

## Regra final de passagem entre blocos

A passagem deve ocorrer somente nesta ordem:

```text
Bloco A — ChatGPT App / Sol
    ↓
Bloco B — ChatGPT App / Sol
    ↓
Bloco C — Claude Code / Opus 5
    ↓
Bloco D — Codex CLI / Terra + revisão única Opus 5
    ↓
Bloco E — estabilização no ambiente atual
    ↓
Bloco F — Claude Code / skills visuais
```

Não iniciar Bloco D antes de C estar merged. Não iniciar F antes de C e D estarem estabilizados.

## 16. Aba `You` futura

CSS não consegue criar uma nova aba funcional. Esse recurso exigirá código de hook.

Especificação futura:

- adicionar item real ao menu inferior;
- foto de perfil circular como ícone;
- fallback para avatar genérico;
- título `You` ou tradução local;
- clique abre perfil/configurações pessoais;
- não interferir no ViewPager existente;
- preservar índices das abas do WhatsApp;
- compatibilidade com abas ocultadas e Meta AI;
- nunca assumir IDs fixos sem descoberta/fallback.

Implementar somente depois de estabilizar barra, CSS e compatibilidade.

---

## 17. Testes obrigatórios

### 17.1 Unidade

- clamp e step dos sliders;
- conversão Integer/Float/String;
- allowlist de backup;
- rejeição de segredo;
- migração de nomes antigos;
- JSON inválido;
- schema incompatível;
- rollback de importação;
- cálculo de contraste;
- geração de tokens de tema;
- deduplicação de mídia;
- validação de path;
- validação de UID.

### 17.2 Instrumentação

- abrir todas as telas de settings;
- validar todos os ícones do JSON;
- mudar cada preset de cor;
- alternar light/dark;
- abrir personalização com valores legados fora da faixa;
- testar safe mode;
- exportar e restaurar full backup;
- migrar banco.

### 17.3 Aparelho principal de referência

- Galaxy S25 Ultra;
- Android 16;
- One UI 8.5/ambiente equivalente do usuário;
- WhatsApp `2.26.27.85` como baseline inicial;
- LSPosed/ambiente root existente.

Testar:

- navegação por gestos;
- três botões;
- rotação;
- fonte grande;
- Chats;
- Updates;
- Communities;
- Calls;
- Meta AI;
- FAB;
- scroll hide;
- soft reboot;
- restart do WhatsApp;
- import/export;
- CSS simples e complexo;
- ausência do Helper;
- upgrade entre versões do fork.

### 17.4 Performance

Comparar com a `1.7.0`:

- tempo de inicialização;
- tempo de troca de abas;
- frames perdidos;
- memória;
- tempo de parse/aplicação CSS;
- hooks registrados;
- consultas SQLite;
- tempo de backup;
- tamanho do banco e mídia.

Uma feature não deve ser mantida se criar regressão perceptível sem benefício proporcional.

---

## 18. Organização de commits e ownership

Evitar commits gigantes, mas também evitar microcommits que espalhem uma feature entre agentes. Cada commit deve pertencer claramente ao bloco ativo.

### Blocos A e B — ChatGPT App / Sol

1. `chore: initialize community fork from 1.7.0`
2. `build: add manual signed GitHub release workflow`
3. `refactor: remove pro licensing and external helper loader`
4. `refactor: remove firebase analytics and crash reporting`
5. `feat: port floating bottom bar customization`
6. `fix: clamp and migrate bottom bar slider values`
7. `feat: add fab modes and selected indicator controls`
8. `fix: preserve css parser classes and add basic rollback`
9. `fix: restore settings icon registry and unique entry icon`
10. `feat: implement semantic accent theme presets`
11. `security: add safe allowlisted configuration export`
12. `security: add transactional legacy configuration import`

### Bloco C — Claude Code / Opus 5

13. `security: inventory and classify preference storage`
14. `security: introduce public and private preference stores`
15. `security: add dual-read shadow-write migration`
16. `security: harden providers and dynamic authorities`
17. `security: authenticate tasker and broadcast IPC`
18. `security: reduce permissions and harden updater`

### Bloco D — Codex CLI / Terra

19. `feat: version deleted-for-me storage and migrations`
20. `feat: add upstream deleted-data migration assistant`
21. `feat: preserve deleted media with quota and deduplication`
22. `feat: add encrypted full backup and transactional restore`
23. `test: add deleted-data corruption and rollback coverage`

### Bloco F — Claude Code / Anthropic

24. `feat: add advanced glass visual system`
25. `feat: add secure UI element inspector`
26. `feat: add profile you tab`

Cada commit deve compilar. Commits de migration devem incluir testes no mesmo commit ou imediatamente no seguinte antes de outras mudanças. Nenhum agente deve reescrever em massa commits de um bloco anterior sem documentar a razão e obter uma baseline de regressão.

## 19. Critérios da primeira alpha

Nome de trabalho:

```text
WaEnhancer Community 1.8.0-alpha1
```

A alpha só deve ser publicada quando tiver:

- base estável 1.7.0;
- package e assinatura próprios;
- Helper completamente removido;
- Pro/licença removidos;
- Firebase removido;
- workflow exclusivamente manual;
- APK publicado em GitHub Releases;
- nenhum Actions artifact;
- assinatura verificada;
- exportação sem segredos;
- importação transacional;
- CSS sem `NoSuchFieldException: FUNCTION`;
- personalizador da barra funcional;
- FAB Default/Minimal/Hidden;
- indicador selecionado configurável;
- settings icons corrigidos;
- ícone exclusivo do WaEnhancer;
- diagnóstico local básico;
- documentação GPL e de segurança.

O `Deleted for Me` com mídia pode entrar em alpha posterior se a migração não estiver suficientemente segura. Nunca apressar essa parte para cumprir número de versão.

---

## 20. Decisões que não devem ser revertidas sem pedido explícito

- basear o fork na `1.7.0`, não na beta inteira;
- portar melhorias da beta seletivamente;
- remover Helper e sistema Pro por completo;
- não carregar DEX ou `.so` externos fechados;
- remover Firebase;
- usar workflow manual apenas;
- publicar APK em GitHub Releases;
- não usar Actions artifacts;
- usar os quatro secrets informados;
- manter GPLv3 e atribuição;
- corrigir exportação insegura;
- proteger providers e Tasker;
- não deixar segurança quebrar compatibilidade;
- manter `Deleted for Me`;
- preservar mensagens e mídias em updates;
- incluir mensagens em full backup;
- adicionar Deleted Media;
- manter FAB Default/Minimal/Hidden;
- adicionar indicador selecionado configurável;
- adicionar Element Inspector;
- corrigir ícones das configurações;
- substituir a engrenagem redundante do WaEnhancer;
- implementar presets de cor realmente funcionais;
- preservar customização granular de bolhas;
- criar Stable Glass, Compact, Accessibility e Advanced Glass;
- desenvolver iOS/Liquid Glass do zero no Bloco F;
- manter a aba `You` como feature futura de código, não CSS;
- executar Blocos A e B no ChatGPT App com GPT-5.6 Sol;
- executar a migração crítica de preferências, storage, providers, broadcasts e Tasker no Claude Code com Opus 5;
- não atribuir essa arquitetura crítica ao Terra;
- executar `Deleted for Me` no Codex CLI com Terra somente após o Bloco C, seguido de uma única auditoria Opus 5;
- usar Sonnet 5 ou Terra para loops de build dentro do ambiente atual, sem transferências desnecessárias;
- usar Claude Code da Anthropic, e não um cloud genérico, para a futura skill de iOS/Liquid Glass;
- impedir que qualquer agente ultrapasse o limite do bloco atribuído.

---

## 21. Checklist final para o agente que receber este handoff

Antes de editar código:

- [ ] confirmar checkout exato da tag `1.7.0`;
- [ ] criar branch de trabalho;
- [ ] rodar build limpa com JDK 17;
- [ ] registrar testes baseline;
- [ ] inventariar references Pro/Helper/Firebase;
- [ ] inventariar todas as chaves de preferências somente quando o Bloco C estiver ativo;
- [ ] localizar e modificar bancos/providers somente nos Blocos C ou D;
- [ ] verificar application ID final;
- [ ] não inserir secrets em arquivos versionados;
- [ ] não usar workflow automático;
- [ ] não usar upload-artifact;
- [ ] preservar banco e arquivos existentes;
- [ ] implementar migrations em passos pequenos;
- [ ] gerar APK release apenas após verificação de assinatura;
- [ ] documentar cada mudança que afete dados ou segurança;
- [ ] confirmar qual bloco está ativo e qual ambiente é o owner;
- [ ] não iniciar tarefas pertencentes ao bloco seguinte;
- [ ] não permitir dois agentes editando a mesma branch simultaneamente;
- [ ] registrar último commit, testes e invariantes antes do handoff;
- [ ] manter A e B no ChatGPT App;
- [ ] reservar C para Claude Code com Opus 5;
- [ ] iniciar D somente após C estar merged e validado;
- [ ] realizar somente uma revisão Opus consolidada após a implementação Terra do Bloco D;
- [ ] manter correções de build no ambiente atual sempre que possível.

Este documento é a especificação-base do fork.

---

## Anexo A — Inventário C1 executado

Este anexo é o resultado da Fase C1 do Bloco C, incorporado ao plano. Ele deixou de ser
um arquivo separado (`BLOCK_C_INVENTORY.md`) para que a especificação-base e o
inventário que a Fase C2 consome vivam no mesmo documento.

Base: `459acdb8670f8d7469e2138f87452429afb78f7c` (merge dos Blocos A+B em `integration/community`).
Este documento é o inventário exigido pela Fase C1 do plano de execução. Ele é a
fonte da qual `PreferenceSchema` e a allowlist de backup são derivados na Fase C2.

### 1. Método
A superfície autoritativa de *configuração do usuário* é o que a UI declara:

- atributos `key` das telas em `app/src/main/res/xml/` (standalone e `embedded_settings_*`);
- entradas de `app/src/main/res/raw/waex_settings_map.json`;
- chaves da barra flutuante, declaradas em código em `BottomBarPreferenceSchema`
  porque `BottomBarCustomizationActivity` é uma Activity com sliders, não um `PreferenceScreen`.

O tipo armazenado é derivado do widget que declara a chave. Leitores e escritores são
localizados por varredura dos fontes Java, excluindo os arquivos de catálogo
(`FeatureCatalog`, `BackupCodec`, `BottomBarPreferenceSchema`, `SettingsIconRegistry`),
que apenas citam chaves sem consumi-las.

Total de chaves que armazenam valor: **181**. Além delas, 27 entradas de UI são
categorias ou itens de navegação e não persistem nada.

### 2. Classificação
| Classe | Chaves | Regra |
|---|---:|---|
| `public` | 147 | lida por um hook dentro do processo do WhatsApp e não carrega segredo |
| `private` | 15 | lida apenas no processo do app do módulo, ou caminho absoluto local do aparelho |
| `secret` | 3 | conteúdo é segredo do usuário; nunca pode ficar em arquivo world-readable |
| `cache` | 1 | estado regenerável; nunca exportado |
| `obsolete` | 15 | declarada na UI sem nenhuma implementação; removida (ver secção 5) |

### 3. Segredos no armazenamento público — achado principal do C1
Três chaves guardam segredo do usuário no arquivo de preferências padrão, que
`WppXposed` torna **world-readable** (`getDefaultSharedPreferencesMode` é hookado para
`MODE_WORLD_READABLE`) para que `XSharedPreferences` as leia do processo do WhatsApp.
Além do arquivo em si, `HookProvider` é `exported="true"` sem permissão e atende
`get_all_preferences` a qualquer aplicativo instalado.

| Chave | Conteúdo | Lida em | Consumidor |
|---|---|---|---|
| `assemblyai_key` | chave de API AssemblyAI fornecida pelo usuário | processo do WhatsApp | AudioTranscript.java |
| `bootloader_spoofer_xml` | keybox XML importado, contendo chave privada e cadeia de certificados | processo do WhatsApp | HookBL.java |
| `groq_api_key` | chave de API Groq fornecida pelo usuário | processo do WhatsApp | AudioTranscript.java |

Contrato alvo: essas três saem do `public_config` e passam a ser lidas sob demanda
por chamada validada por UID, sem nunca serem gravadas no arquivo world-readable.

### 4. Matriz `key -> writer -> reader -> process -> sensitivity -> migration`
`process`: `wa` = processo do WhatsApp hookado; `app` = processo do app do módulo;
`app+wa` = ambos.

| Chave | Tipo | Writer | Reader | Processo | Sensibilidade | Migração |
|---|---|---|---|---|---|---|
| `stamp_copied_message` | boolean | — | Others.java | wa | cache | private_config; not exported |
| `add_status_reply_menu_item` | boolean | — | — | none | obsolete | remove from UI; never re-read |
| `always_typing_global_type` | string | — | — | none | obsolete | remove from UI; never re-read |
| `call_recording_calls_tab_menu` | boolean | — | — | none | obsolete | remove from UI; never re-read |
| `delete_message_file` | boolean | — | — | none | obsolete | remove from UI; never re-read |
| `delete_message_file_sent` | boolean | — | — | none | obsolete | remove from UI; never re-read |
| `disable_status_swipe_up` | boolean | — | — | none | obsolete | remove from UI; never re-read |
| `enable_spy` | boolean | — | — | none | obsolete | remove from UI; never re-read |
| `file_size_spoofer` | boolean | — | — | none | obsolete | remove from UI; never re-read |
| `message_bomber` | boolean | — | — | none | obsolete | remove from UI; never re-read |
| `pro_status_splitter` | boolean | — | — | none | obsolete | remove from UI; never re-read |
| `send_audio_as_voice_status` | boolean | — | — | none | obsolete | remove from UI; never re-read |
| `status_bottom_play_pause_button` | boolean | — | — | none | obsolete | remove from UI; never re-read |
| `status_video_fast_gesture` | boolean | — | — | none | obsolete | remove from UI; never re-read |
| `status_video_fast_speed` | string | — | — | none | obsolete | remove from UI; never re-read |
| `statuscomposer` | boolean | — | — | none | obsolete | remove from UI; never re-read |
| `always_typing_contacts` | string | — | PrivacyFragment.java | app | private | private_config; exported when user-owned |
| `always_typing_global` | boolean | — | MainActivity.java<br>SmartTypingTileService.java<br>+1 | app | private | private_config; exported when user-owned |
| `always_typing_global_mode` | string | — | PrivacyFragment.java | app | private | private_config; exported when user-owned |
| `always_typing_global_target` | string | — | PrivacyFragment.java | app | private | private_config; exported when user-owned |
| `call_recording_path` | string | — | RecordingsFragment.java<br>CallRecording.java | app+wa | private | private_config; exported when user-owned |
| `floating_bottom_bar_icon_label_spacing` | float | — | BottomBarCustomizationActivity.java | app | private | private_config; exported when user-owned |
| `floating_bottom_bar_icon_size` | float | — | BottomBarCustomizationActivity.java | app | private | private_config; exported when user-owned |
| `floating_bottom_bar_minimal_fab_margin` | float | — | BottomBarCustomizationActivity.java | app | private | private_config; exported when user-owned |
| `floating_bottom_bar_text_size` | float | — | BottomBarCustomizationActivity.java | app | private | private_config; exported when user-owned |
| `folder_theme` | string | — | TextEditorActivity.java<br>WallpaperView.java<br>+1 | app+wa | private | private_config; exported when user-owned |
| `thememode` | string | — | App.java<br>BasePreferenceFragment.java | app | private | private_config; exported when user-owned |
| `verify_blocked_contact` | boolean | App.java | — | app | private | private_config; exported when user-owned |
| `video_call_screen_rec` | boolean | — | MediaFragment.java | app | private | private_config; exported when user-owned |
| `wae_color_mode` | string | — | BaseActivity.java | app | private | private_config; exported when user-owned |
| `wallpaper_file` | string | — | WallpaperView.java | app | private | private_config; exported when user-owned |
| `admin_emoji` | string | — | GroupAdmin.java | wa | public | public_config; shadow-write + dual-read |
| `admin_grp` | boolean | — | GroupAdmin.java | wa | public | public_config; shadow-write + dual-read |
| `alertsticker` | boolean | — | Stickers.java | wa | public | public_config; shadow-write + dual-read |
| `always_online` | boolean | — | MainActivity.java<br>AlwaysOnlineTileService.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `ampm` | boolean | — | CustomTime.java | wa | public | public_config; shadow-write + dual-read |
| `animation_emojis` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `animation_list` | string | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `antidisappearing` | boolean | — | ChatLimit.java | wa | public | public_config; shadow-write + dual-read |
| `antieditmessages` | boolean | — | ShowEditMessage.java | wa | public | public_config; shadow-write + dual-read |
| `antirevoke` | string | — | AntiRevoke.java | wa | public | public_config; shadow-write + dual-read |
| `antirevokestatus` | string | — | AntiRevoke.java | wa | public | public_config; shadow-write + dual-read |
| `audio_transcription` | boolean | — | Others.java<br>AudioTranscript.java | wa | public | public_config; shadow-write + dual-read |
| `audio_type` | string | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `auto_status_forward` | boolean | — | AutoStatusForward.java | wa | public | public_config; shadow-write + dual-read |
| `auto_status_forward_rules_pref` | string | — | EmbeddedBasePreferenceFragment.java | wa | public | public_config; shadow-write + dual-read |
| `autonext_status` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `blueonreply` | boolean | — | BasePreferenceFragment.java<br>SeenTick.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `bootloader_spoofer` | boolean | — | WppXposed.java<br>FeatureLoader.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `bootloader_spoofer_custom` | boolean | — | HookBL.java | wa | public | public_config; shadow-write + dual-read |
| `broadcast_tag` | boolean | — | TagMessage.java | wa | public | public_config; shadow-write + dual-read |
| `bubble_color` | boolean | — | BubbleColors.java | wa | public | public_config; shadow-write + dual-read |
| `bypass_version_check` | boolean | — | FeatureLoader.java | wa | public | public_config; shadow-write + dual-read |
| `call_block_contacts` | string | — | BasePreferenceFragment.java<br>EmbeddedBasePreferenceFragment.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `call_info` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `call_privacy` | string | — | MainActivity.java<br>BlockCallsTileService.java<br>+4 | app+wa | public | public_config; shadow-write + dual-read |
| `call_recording_blacklist` | string | — | MediaFragment.java<br>CallRecording.java | app+wa | public | public_config; shadow-write + dual-read |
| `call_recording_enable` | boolean | — | MediaFragment.java<br>CallRecording.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `call_recording_mode` | string | — | MediaFragment.java<br>CallRecording.java | app+wa | public | public_config; shadow-write + dual-read |
| `call_recording_toast` | boolean | — | CallRecording.java | wa | public | public_config; shadow-write + dual-read |
| `call_recording_whitelist` | string | — | MediaFragment.java<br>CallRecording.java | app+wa | public | public_config; shadow-write + dual-read |
| `call_type` | string | — | CallPrivacy.java | wa | public | public_config; shadow-write + dual-read |
| `call_white_contacts` | string | — | BasePreferenceFragment.java<br>EmbeddedBasePreferenceFragment.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `calltype` | boolean | CallType.java | — | wa | public | public_config; shadow-write + dual-read |
| `change_dpi` | string | — | BottomSheetHelper.java<br>CustomView.java | app+wa | public | public_config; shadow-write + dual-read |
| `changecolor` | boolean | — | BasePreferenceFragment.java<br>CustomThemeV2.java<br>+3 | app+wa | public | public_config; shadow-write + dual-read |
| `changecolor_mode` | string | — | BasePreferenceFragment.java<br>CustomThemeV2.java<br>+2 | app+wa | public | public_config; shadow-write + dual-read |
| `channels` | boolean | — | NoticeCenter.java<br>BasePreferenceFragment.java<br>+2 | app+wa | public | public_config; shadow-write + dual-read |
| `chatfilter` | string | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `copystatus` | boolean | — | CopyStatus.java | wa | public | public_config; shadow-write + dual-read |
| `css_theme` | string | — | CustomView.java | wa | public | public_config; shadow-write + dual-read |
| `custom_filters` | boolean | — | BasePreferenceFragment.java<br>BubbleColors.java<br>+4 | app+wa | public | public_config; shadow-write + dual-read |
| `custom_privacy_type` | string | — | CustomPrivacy.java | wa | public | public_config; shadow-write + dual-read |
| `customize_supported_versions` | boolean | — | SupportedVersionsActivity.java<br>FeatureLoader.java | app+wa | public | public_config; shadow-write + dual-read |
| `disable_ads` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `disable_defemojis` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `disable_expiration` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `disable_profile_status` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `disable_sensor_proximity` | boolean | — | MainActivity.java<br>ProximitySensorSwitchTileService.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `dotonline` | boolean | — | BasePreferenceFragment.java<br>ShowOnline.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `doubletap2like` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `download_local` | string | — | Utils.java | wa | public | public_config; shadow-write + dual-read |
| `download_video_note` | boolean | — | BasePreferenceFragment.java<br>DownloadVideoNote.java | app+wa | public | public_config; shadow-write + dual-read |
| `downloadstatus` | boolean | — | BasePreferenceFragment.java<br>StatusDownload.java | app+wa | public | public_config; shadow-write + dual-read |
| `downloadviewonce` | boolean | — | BasePreferenceFragment.java<br>DownloadViewOnce.java | app+wa | public | public_config; shadow-write + dual-read |
| `filter_group_members_messages` | boolean | — | GeneralFragment.java<br>FeatureLoader.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `filter_items` | string | FilterItemsActivity.java | CustomizationFragment.java<br>Others.java | app+wa | public | public_config; shadow-write + dual-read |
| `filtergroups` | boolean | BasePreferenceFragment.java<br>EmbeddedBasePreferenceFragment.java | FilterGroups.java | app+wa | public | public_config; shadow-write + dual-read |
| `filterseen` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar` | boolean | BottomBarCustomizationActivity.java | LocalDiagnostics.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_bottom_margin` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_fab_offset` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_glass_opacity` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_horizontal_margin` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_indicator_height` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_indicator_offset` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_indicator_opacity` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_indicator_padding_horizontal` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_indicator_padding_vertical` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_indicator_radius` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_indicator_width` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_manual_height` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_minimal_fab_opacity` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_minimal_fab_radius` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_minimal_fab_size` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_padding_vertical` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floating_bottom_bar_radius` | float | — | BottomBarCustomizationActivity.java<br>FloatingBottomBar.java | app+wa | public | public_config; shadow-write + dual-read |
| `floatingmenu` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `force_english` | boolean | — | App.java<br>BasePreferenceFragment.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `force_restore_backup_feature` | boolean | — | BackupRestore.java | wa | public | public_config; shadow-write + dual-read |
| `freezelastseen` | boolean | — | MainActivity.java<br>BasePreferenceFragment.java<br>+2 | app+wa | public | public_config; shadow-write + dual-read |
| `ghostmode` | boolean | — | MainActivity.java<br>MenuHome.java | app+wa | public | public_config; shadow-write + dual-read |
| `ghostmode_r` | boolean | — | PrivacyFragment.java<br>CustomPrivacy.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `ghostmode_t` | boolean | — | PrivacyFragment.java<br>CustomPrivacy.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `go_to_first_message` | boolean | — | ChatScrollButtons.java | wa | public | public_config; shadow-write + dual-read |
| `google_translate` | boolean | — | GoogleTranslate.java | wa | public | public_config; shadow-write + dual-read |
| `hide_seen_view` | boolean | — | HideSeenView.java | wa | public | public_config; shadow-write + dual-read |
| `hideaudioseen` | boolean | — | HideSeen.java | wa | public | public_config; shadow-write + dual-read |
| `hideonceseen` | boolean | — | HideSeen.java | wa | public | public_config; shadow-write + dual-read |
| `hideread` | boolean | BasePreferenceFragment.java<br>EmbeddedBasePreferenceFragment.java | MainActivity.java<br>StealthReadTicksTileService.java<br>+2 | app+wa | public | public_config; shadow-write + dual-read |
| `hideread_group` | boolean | — | HideSeen.java | wa | public | public_config; shadow-write + dual-read |
| `hidereceipt` | boolean | — | MainActivity.java<br>HideDeliveredTileService.java<br>+3 | app+wa | public | public_config; shadow-write + dual-read |
| `hidestatusview` | boolean | — | MainActivity.java<br>StealthStatusViewingTileService.java<br>+3 | app+wa | public | public_config; shadow-write + dual-read |
| `hidetabs` | string_set | — | HideTabs.java | wa | public | public_config; shadow-write + dual-read |
| `hidetag` | boolean | — | TagMessage.java | wa | public | public_config; shadow-write + dual-read |
| `igstatus` | boolean | — | BasePreferenceFragment.java<br>HideTabs.java<br>+3 | app+wa | public | public_config; shadow-write + dual-read |
| `imagequality` | boolean | — | MediaQuality.java | wa | public | public_config; shadow-write + dual-read |
| `lite_mode` | boolean | — | FileSelectPreference.java<br>BasePreferenceFragment.java<br>+6 | app+wa | public | public_config; shadow-write + dual-read |
| `lockedchats_enhancer` | boolean | — | LockedChatsEnhancer.java | wa | public | public_config; shadow-write + dual-read |
| `media_preview` | boolean | — | MediaPreview.java | wa | public | public_config; shadow-write + dual-read |
| `menuwicon` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `message_device_source` | boolean | — | MessageDeviceSourceStore.java<br>Others.java | wa | public | public_config; shadow-write + dual-read |
| `metaai` | boolean | — | HideTabsPreference.java<br>HideTabs.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `newchat` | boolean | — | NewChat.java<br>MenuHome.java | wa | public | public_config; shadow-write + dual-read |
| `novaconfig` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `oldstatus` | boolean | — | BasePreferenceFragment.java<br>Others.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `open_settings_mode` | string | — | BasePreferenceFragment.java<br>Utils.java | app+wa | public | public_config; shadow-write + dual-read |
| `open_waex` | string | — | BasePreferenceFragment.java<br>ProviderSharedPreferences.java<br>+3 | app+wa | public | public_config; shadow-write + dual-read |
| `pinnedlimit` | boolean | — | PinnedLimit.java | wa | public | public_config; shadow-write + dual-read |
| `proximity_audios` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `remove_status_bottom_tile` | boolean | — | BasePreferenceFragment.java<br>EmbeddedBasePreferenceFragment.java | app+wa | public | public_config; shadow-write + dual-read |
| `remove_status_heart_button` | boolean | — | BasePreferenceFragment.java<br>EmbeddedBasePreferenceFragment.java | app+wa | public | public_config; shadow-write + dual-read |
| `remove_status_quick_reactions` | boolean | — | BasePreferenceFragment.java<br>EmbeddedBasePreferenceFragment.java | app+wa | public | public_config; shadow-write + dual-read |
| `removechannel_rec` | boolean | — | BasePreferenceFragment.java<br>Channels.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `removeforwardlimit` | boolean | — | ShareLimit.java | wa | public | public_config; shadow-write + dual-read |
| `removeseemore` | boolean | — | ChatLimit.java | wa | public | public_config; shadow-write + dual-read |
| `restartbutton` | boolean | — | MenuHome.java | wa | public | public_config; shadow-write + dual-read |
| `revokeallmessages` | boolean | — | ChatLimit.java | wa | public | public_config; shadow-write + dual-read |
| `secondstotime` | string | — | CustomTime.java | wa | public | public_config; shadow-write + dual-read |
| `seentick` | string | — | SeenTick.java | wa | public | public_config; shadow-write + dual-read |
| `segundos` | boolean | — | CustomTime.java | wa | public | public_config; shadow-write + dual-read |
| `selectable_message` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `send_video_as_video_note` | boolean | — | VideoNoteAttachment.java | wa | public | public_config; shadow-write + dual-read |
| `separategroups` | boolean | — | BasePreferenceFragment.java<br>FeatureLoader.java<br>+4 | app+wa | public | public_config; shadow-write + dual-read |
| `separategroups_counter_type` | string | — | SeparateGroup.java | wa | public | public_config; shadow-write + dual-read |
| `show_dndmode` | boolean | — | MainActivity.java<br>MenuHome.java | app+wa | public | public_config; shadow-write + dual-read |
| `show_freezeLastSeen` | boolean | — | BasePreferenceFragment.java<br>EmbeddedBasePreferenceFragment.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `show_hidereceipt` | boolean | — | MenuHome.java | wa | public | public_config; shadow-write + dual-read |
| `show_home_menu` | string | — | MenuHome.java | wa | public | public_config; shadow-write + dual-read |
| `show_hook_toast` | boolean | — | BasePreferenceFragment.java<br>FeatureLoader.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `showbiohome` | boolean | — | CustomToolbar.java | wa | public | public_config; shadow-write + dual-read |
| `shownamehome` | boolean | — | CustomToolbar.java | wa | public | public_config; shadow-write + dual-read |
| `showonline` | boolean | — | Others.java | wa | public | public_config; shadow-write + dual-read |
| `showonlinetext` | boolean | — | BasePreferenceFragment.java<br>ShowOnline.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `status_style` | string | — | BasePreferenceFragment.java<br>Others.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `tasker` | boolean | — | Tasker.java | wa | public | public_config; shadow-write + dual-read |
| `toast_viewed_message` | boolean | — | ToastViewer.java | wa | public | public_config; shadow-write + dual-read |
| `toast_viewed_status` | boolean | — | ToastViewer.java | wa | public | public_config; shadow-write + dual-read |
| `toastdeleted` | boolean | — | AntiRevoke.java | wa | public | public_config; shadow-write + dual-read |
| `transcription_provider` | string | — | AudioTranscript.java | wa | public | public_config; shadow-write + dual-read |
| `typearchive` | string | — | CustomToolbar.java<br>HideChat.java | wa | public | public_config; shadow-write + dual-read |
| `update_check` | boolean | — | FeatureLoader.java | wa | public | public_config; shadow-write + dual-read |
| `video_maxfps` | boolean | — | MediaQuality.java | wa | public | public_config; shadow-write + dual-read |
| `video_real_resolution` | boolean | — | MediaQuality.java | wa | public | public_config; shadow-write + dual-read |
| `videoquality` | boolean | — | MediaQuality.java | wa | public | public_config; shadow-write + dual-read |
| `viewonce` | boolean | — | ViewOnce.java | wa | public | public_config; shadow-write + dual-read |
| `wae_color_preset` | string | — | BaseActivity.java<br>LocalDiagnostics.java<br>+1 | app+wa | public | public_config; shadow-write + dual-read |
| `wallpaper` | boolean | — | BasePreferenceFragment.java<br>WallpaperView.java<br>+2 | app+wa | public | public_config; shadow-write + dual-read |
| `assemblyai_key` | string | — | AudioTranscript.java | wa | secret | private_config; UID-validated provider read |
| `bootloader_spoofer_xml` | string | — | HookBL.java | wa | secret | private_config; UID-validated provider read |
| `groq_api_key` | string | — | AudioTranscript.java | wa | secret | private_config; UID-validated provider read |

### 5. Manifesto de remoção — funções sem implementação
As chaves abaixo são declaradas nas telas de Preferences (standalone e/ou embedded),
aparecem em `waex_settings_map.json` e têm strings traduzidas, mas **nenhum leitor em
todo o código Java**. São resquícios de UI de funções cuja implementação era fechada
(Pro/Helper) ou que nunca foram portadas da beta para a base estável 1.7.0.

Elas são removidas da UI no Bloco C para o app parar de prometer o que não faz.
**Este manifesto existe para permitir a reimplementação futura**: cada entrada registra
o texto exato que a função prometia ao usuário, as telas onde aparecia e os recursos de
string envolvidos. Nada aqui foi reimplementado; tudo precisa ser reescrito do zero,
sem copiar código fechado.

#### `add_status_reply_menu_item` (boolean)

- **Prometia:** Add option to menu to open reply
- **Descrição:** Adds a "Reply" item in the playback three-dots menu to expand the reply panel manually
- **Telas:** embedded_settings_status.xml
- **Strings:** `add_status_reply_menu_item`, `add_status_reply_menu_item_sum`

#### `always_typing_global_type` (string)

- **Prometia:** Global Simulated Status Kind
- **Descrição:** —
- **Telas:** fragment_privacy.xml
- **Strings:** `always_typing_global_type_title`

#### `call_recording_calls_tab_menu` (boolean)

- **Prometia:** (texto literal no XML)
- **Descrição:** —
- **Telas:** embedded_settings_calls.xml, fragment_media.xml
- **Strings:** `call_recording_calls_tab_menu`, `call_recording_calls_tab_menu_summary`

#### `delete_message_file` (boolean)

- **Prometia:** Delete message media file
- **Descrição:** Add a menu option to delete downloaded media files from user storage.
- **Telas:** embedded_settings_conversation.xml, preference_general_conversation.xml
- **Strings:** `delete_message_file`, `delete_message_file_sum`

#### `delete_message_file_sent` (boolean)

- **Prometia:** Also apply for my sent messages
- **Descrição:** Show the delete option for your own sent media messages as well.
- **Telas:** embedded_settings_conversation.xml, preference_general_conversation.xml
- **Strings:** `delete_message_file_sent`, `delete_message_file_sent_sum`

#### `disable_status_swipe_up` (boolean)

- **Prometia:** Disable Swipe Up to reply
- **Descrição:** Disables the swipe up gesture that opens the reply bottom sheet in status view screen
- **Telas:** embedded_settings_status.xml
- **Strings:** `disable_status_swipe_up`, `disable_status_swipe_up_sum`

#### `enable_spy` (boolean)

- **Prometia:** Enable Spy Tool
- **Descrição:** Logs internal events to Xposed log for debugging.
- **Telas:** embedded_settings_status.xml, fragment_general.xml
- **Strings:** `enable_spy`, `enable_spy_sum`

#### `file_size_spoofer` (boolean)

- **Prometia:** File Size Spoofer
- **Descrição:** Add a button in the send preview screen to spoof the displayed file size shown to the recipient
- **Telas:** embedded_settings_media.xml, fragment_media.xml
- **Strings:** `file_size_spoofer`, `file_size_spoofer_sum`

#### `message_bomber` (boolean)

- **Prometia:** Message Bomber
- **Descrição:** Send multiple messages to a contact in rapid succession.
- **Telas:** embedded_settings_conversation.xml, preference_general_conversation.xml
- **Strings:** `message_bomber`, `message_bomber_sum`

#### `pro_status_splitter` (boolean)

- **Prometia:** Status Video Splitter
- **Descrição:** Split long videos into 30/60/90 second clips for seamless posting on WhatsApp Status.
- **Telas:** embedded_settings_status.xml, fragment_media.xml
- **Strings:** `pro_status_splitter`, `pro_status_splitter_sum`

#### `send_audio_as_voice_status` (boolean)

- **Prometia:** Send Audio as Voice Status
- **Descrição:** Enables picking and uploading local audio files as voice statuses
- **Telas:** embedded_settings_status.xml
- **Strings:** `send_audio_as_voice_status`, `send_audio_as_voice_status_sum`

#### `status_bottom_play_pause_button` (boolean)

- **Prometia:** Show play/pause button in bottom bar
- **Descrição:** Adds a play/pause toggle button next to the heart reaction button at the bottom of status playback
- **Telas:** embedded_settings_status.xml
- **Strings:** `status_bottom_play_pause_button`, `status_bottom_play_pause_button_sum`

#### `status_video_fast_gesture` (boolean)

- **Prometia:** Long press to fast forward/rewind
- **Descrição:** Long press the right side of a video status to fast-forward, or the left side to fast-rewind
- **Telas:** embedded_settings_status.xml
- **Strings:** `status_video_fast_gesture`, `status_video_fast_gesture_sum`

#### `status_video_fast_speed` (string)

- **Prometia:** Playback speed
- **Descrição:** Choose the playback speed during fast forward/rewind
- **Telas:** embedded_settings_status.xml
- **Strings:** `status_video_fast_speed`, `status_video_fast_speed_sum`

#### `statuscomposer` (boolean)

- **Prometia:** Custom colors for text status
- **Descrição:** Press and hold on the color selector in the status to customize it
- **Telas:** embedded_settings_status.xml, fragment_customization.xml
- **Strings:** `custom_colors_for_text_status`, `custom_colors_for_text_status_sum`


### 6. Superfície IPC e componentes exportados

Levantada de `app/src/main/AndroidManifest.xml` no commit base.

#### 6.1 Providers

| Componente | Authority | `exported` | Permissão | Validação de caller | Operações |
|---|---|---|---|---|---|
| `xposed.bridge.providers.HookProvider` | `${applicationId}.hookprovider` | `true` | nenhuma | **nenhuma** | `get_preference`, `get_all_preferences`, `put_preference`, `remove_preference`, `clear_preferences`, `getHookBinder` |
| `provider.DeletedMessagesProvider` | `${applicationId}.provider` | `true` | nenhuma | **nenhuma** | `insert` em `deleted_messages`, `get_preference`, `put_preference`, `log_tasker_event` |
| `androidx.core.content.FileProvider` | `${applicationId}.fileprovider` | `false` | — | grants por URI | leitura de arquivos declarados em `xml/file_paths.xml` |

`HookProvider.call()` abre com `Binder.clearCallingIdentity()` e nunca consulta
`Binder.getCallingUid()`. Qualquer aplicativo instalado pode ler todas as preferências do
módulo, reescrevê-las ou apagá-las por inteiro, e obter o `HookBinder`.

`DeletedMessagesProvider` aceita `insert` de mensagens e `put_preference` sem qualquer
verificação, e `log_tasker_event` grava número de destino e prévia de mensagem no
histórico local a pedido de qualquer chamador.

#### 6.2 Receivers

| Componente | Ação | `exported` | Permissão | Validação |
|---|---|---|---|---|
| `receivers.TaskerMessageSentReceiver` | `com.waenhancer.MESSAGE_SENT` | `true` | nenhuma | **nenhuma** |
| `receivers.WAFReceiver` | `com.facebook.GET_PHONE_ID`, `android.support.customtabs.action.CustomTabsService` | `true` | nenhuma | corpo vazio |
| `Tasker.SenderMessageBroadcastReceiver` (registrado em runtime no processo do WhatsApp) | `com.waenhancer.MESSAGE_SENT`, `com.waenhancer.MESSAGE_SENT_INTERNAL` | `RECEIVER_EXPORTED` | nenhuma | **nenhuma** |

#### 6.3 Broadcasts emitidos

| Ação | Emissor | Explícito? | Conteúdo |
|---|---|---|---|
| `com.waenhancer.MESSAGE_RECEIVED` | `Tasker.hookReceiveMessage` | **não** | número de telefone, nome do contato, **texto completo da mensagem recebida** |
| `com.waenhancer.EVENT` | `Tasker.sendTaskerEvent` | **não** | nome, número, evento |
| `com.waenhancer.MESSAGE_SENT_INTERNAL` | `TaskerMessageSentReceiver.forwardBroadcast` | sim (`setPackage`) | número, mensagem |

Os dois primeiros são broadcasts implícitos sem permissão. Qualquer aplicativo que
declare um receiver para essas ações recebe o conteúdo de todas as mensagens que chegam
ao WhatsApp enquanto a integração Tasker estiver ligada.

As strings de ação são literais `com.waenhancer.*` fixas, e não derivadas de
`BuildConfig.APPLICATION_ID`, ao contrário das authorities.

#### 6.4 Serviços e activities

- `xposed.bridge.service.BridgeService` — `exported="true"`, sem permissão.
- Dez `TileService` de Quick Settings — `exported="true"` com
  `android:permission="android.permission.BIND_QUICK_SETTINGS_TILE"`, que é o contrato
  correto do sistema.
- Activities `exported="true"` sem filtro que as justifique: `EmbeddedSettingsActivity`,
  `RecordingsActivity`, `ChangelogActivity`, `SupportedVersionsActivity`.
  `MainActivity` e `ForceStartActivity` têm `LAUNCHER`/`VIEW` e permanecem exportadas.

#### 6.5 Permissões declaradas

`INTERNET`, `QUERY_ALL_PACKAGES`, `POST_NOTIFICATIONS`,
`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`, `SYSTEM_ALERT_WINDOW`, `READ_CONTACTS`,
`RECORD_AUDIO`, `REQUEST_INSTALL_PACKAGES`, `READ_EXTERNAL_STORAGE`,
`WRITE_EXTERNAL_STORAGE`, `READ_MEDIA_IMAGES`, `READ_MEDIA_AUDIO`, `READ_MEDIA_VIDEO`,
`MANAGE_EXTERNAL_STORAGE`.

`android:allowBackup="true"` — o backup do Android copia o arquivo de preferências,
incluindo hoje os três segredos da secção 3.

#### 6.6 Processos e UIDs

| Processo | UID | Como lê as preferências |
|---|---|---|
| app do módulo (`com.waenhancer.community`) | UID próprio | `PreferenceManager.getDefaultSharedPreferences` |
| WhatsApp hookado (`com.whatsapp`) | UID do WhatsApp | `XSharedPreferences` sobre o arquivo world-readable |
| WhatsApp Business (`com.whatsapp.w4b`) | UID próprio do w4b | idem |
| qualquer app de terceiro | qualquer UID | arquivo world-readable **e** `HookProvider` exportado |

A última linha é a exposição que o Bloco C precisa fechar.

### 7. Contrato atual, invariantes e contrato alvo

#### 7.1 Contrato atual

Existe um único store: o `SharedPreferences` padrão do app, tornado world-readable por
hook. Todo consumidor — UI do módulo, hooks no processo do WhatsApp, providers
exportados, backup e diagnóstico — lê e escreve nesse mesmo arquivo, sem distinção de
sensibilidade e sem validação de chamador.

#### 7.2 Invariantes que qualquer migração deve preservar

1. O processo do WhatsApp continua obtendo todas as chaves `public` a cada
   `reload()`, sem depender de o app do módulo estar em execução.
2. Alterar uma configuração na UI continua refletindo no WhatsApp após reinício,
   pelo mesmo mecanismo de notificação já existente.
3. Nenhuma configuração existente do usuário é perdida em upgrade.
4. Backups legados seguros continuam importáveis.
5. Nenhuma escrita destrutiva ocorre antes de validação integral; `clear()` não é
   usado como etapa de migração nem de importação.
6. As invariantes pós-Codex de A+B seguem valendo: nenhum XML referencia classes Pro
   removidas, targets runtime usam `BuildConfig.APPLICATION_ID`, salvar tema inativo
   não o ativa, o menu CSS não tem fall-through, URLs usam `igorcv88/WaEnhancerX`.

#### 7.3 Contrato alvo

Dois stores:

- **`public_config`** — arquivo world-readable, exclusivamente chaves classificadas
  `public`. É o único arquivo que `XSharedPreferences` passa a ler.
- **`private_config`** — `MODE_PRIVATE`, acessível apenas ao UID do módulo. Recebe
  `private`, `secret`, `cache`, `runtime`.

Segredos exigidos por hooks são obtidos sob demanda por chamada de provider validada por
UID, mantidos apenas em memória no processo do WhatsApp e nunca gravados em
`public_config`.

#### 7.4 Migração, compatibilidade e rollback

Sequência obrigatória, conforme secção 3.4 do plano de execução:

1. detectar a estrutura antiga e sua versão;
2. gravar snapshot em `files/migration_snapshots/`;
3. validar leitura da origem;
4. escrever em `public_config`/`private_config` **sem apagar** o store legado;
5. validar contagens e tipos por chave;
6. alternar a leitura para a nova estrutura;
7. manter dual-read com fallback legado por pelo menos uma release estável;
8. só então oferecer limpeza, nunca automática.

**Rollback:** enquanto o store legado existir, restaurá-lo é apenas voltar a preferi-lo
na leitura; o snapshot cobre o caso de escrita parcial. **Downgrade:** uma versão
anterior do módulo continua lendo o store legado intacto, portanto um downgrade não
perde configuração. **Falha:** se a migração não completar, a leitura permanece no store
legado e a tentativa é registrada localmente de forma redigida.

