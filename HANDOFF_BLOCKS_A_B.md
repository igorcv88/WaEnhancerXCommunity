# WaEnhancer Community — Handoff dos Blocos A e B

> **Addendum pós-review obrigatório:** leia também `REVIEW_FIXES_A_B.md`. Ele registra os fixes aplicados após o Codex Reviewer, o run final `30838456180`, a ordem recomendada de agentes e os prompts atualizados. Em caso de divergência, o addendum prevalece.

## Estado entregue

Branch: `block-a-foundation-visual`

Base de integração: `integration/community`

Base histórica selecionada: upstream estável `1.7.0`, commit `433a1c630bc1286f2db3c657a63f477aa0aa426d`.

PR: `#1`

Versão de desenvolvimento: `1.8.0-alpha1` (`versionCode 18001`).

Application ID: `com.waenhancer.community`.

Os Blocos A e B atribuídos ao ChatGPT App foram implementados. Nenhuma alteração deliberada foi feita no split definitivo de storage/IPC, no protocolo Tasker v2 ou na migração estrutural do banco de `Deleted for Me`; esses temas pertencem aos blocos posteriores.

## Implementação concluída

### Fundação e distribuição

- Fork ancorado na versão estável 1.7.0, sem usar a `master` beta como base funcional.
- Branches `integration/community` e `block-a-foundation-visual` estabelecidas.
- Rebranding para WaEnhancer Community e `com.waenhancer.community`.
- Workflow final de release exclusivamente manual por `workflow_dispatch`.
- Release publica o APK diretamente no GitHub Release, sem upload de artifact do Actions.
- Assinatura exige exatamente os secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` e `KEY_PASSWORD`.
- O workflow rejeita certificado Android Debug, compara o certificado do APK com o alias do keystore, valida package/version e calcula SHA-256.
- Release não possui fallback para a chave debug.
- Documentação adicionada: `README.md`, `SECURITY.md`, `PRIVACY.md`, `MIGRATIONS.md` e `ARCHITECTURE.md`.

### Remoção do sistema fechado

- Removidos Firebase Analytics, Firebase Crashlytics, Google Services e dependências relacionadas.
- Removidos Helper, licenciamento, sistema Pro, AIDL, loaders externos de DEX/`.so`, activities/layouts/classes e regras ProGuard associadas.
- Removidos tokens públicos compilados no APK e autenticação desnecessária para endpoints públicos do GitHub.
- Removido material criptográfico embutido e o fallback de keybox presente na base.
- Endpoints de changelog/release foram redirecionados para `igorcv88/WaEnhancerX`.
- Funcionalidades anteriormente bloqueadas por licença foram mantidas abertas quando havia implementação local segura; dependências exclusivas do Helper foram removidas ou degradadas de forma explícita.

### Barra inferior flutuante e personalização visual

- Editor dedicado de configuração da barra inferior flutuante.
- Schema centralizado com defaults, faixas, steps, clamp e conversão de valores legados.
- Controles de margem, altura, raio, largura, padding, tamanho de ícone, tamanho de texto, espaçamento, opacidade, cores e indicador.
- Preservação do comportamento visual original quando indicador/barra customizada estão desativados.
- Modos distintos de ocultação ao rolar, incluindo escopo das tabs principais e escopo amplo.
- Presets e valores padrão conservadores para evitar alteração visual inesperada.

### CSS seguro

- Validação de tamanho e conteúdo antes de aceitar `style.css`.
- Último CSS válido preservado.
- Safe mode manual e contador de falhas.
- Fluxo `Test theme` temporário por dois minutos, com expiração e restauração automática.
- Falhas de CSS não devem impedir a inicialização normal do módulo.

### Engine semântica de cores

- Tokens semânticos gerados a partir de uma cor de destaque, em vez de substituição indiscriminada de pixels verdes.
- Presets green, blue, cyan, purple, orange, red e pink.
- Tokens para primary/onPrimary, containers, superfícies, links, seleção, FAB, indicador ativo, badge, bubbles e estados.
- Ajustes de contraste para texto e controles.
- Testes unitários cobrindo presets, completude dos tokens e relações mínimas de contraste.

### Backup seguro v1

- Exportação por allowlist; não usa mais dump bruto de `SharedPreferences.getAll()`.
- Chaves desconhecidas e sensíveis não são exportadas/importadas.
- Arquivo com schema/versionamento e limite de tamanho.
- Importação valida JSON, tipos, faixas e conteúdo inteiro antes de alterar preferências.
- Snapshot local criado antes da aplicação.
- Aplicação em uma única transação `commit`, sem `clear()` destrutivo e sem estado parcial em caso de falha.
- Importação legada preservada apenas quando pode ser convertida com segurança para o schema permitido.

### Diagnóstico local

- Relatório local, limitado em tamanho e sem upload automático.
- Redação de JIDs, telefones, campos de mensagem e caminhos privados antes da persistência/compartilhamento.
- Preview explícito antes de compartilhar.
- Testes unitários para redação e truncamento.

## Validação executada

GitHub Actions inicial dos Blocos A+B: run `30801556300`.

Validação final após os fixes do Codex Reviewer: run `30838456180`.

Resultados finais:

- auditoria estática de referências Helper/Pro/Firebase: passou;
- auditoria de material criptográfico embutido: passou;
- auditoria do backup transacional/allowlist: passou;
- auditoria do workflow manual-only: passou;
- auditoria de XMLs contra classes Pro removidas: passou;
- auditoria de targets runtime contra o application ID antigo: passou;
- auditoria de URLs malformadas pelo rebrand: passou;
- auditoria de tema inativo e fall-through do editor CSS: passou;
- `assembleWhatsappDebug`: passou;
- `assembleWhatsappRelease` com R8: passou;
- `testWhatsappDebugUnitTest`: passou;
- release assinada com os secrets do repositório: passou;
- `apksigner verify --verbose --print-certs`: passou;
- certificado do APK igual ao certificado do alias configurado: passou;
- rejeição de chave debug: passou;
- package `com.waenhancer.community`: validado;
- `versionCode 18001`: validado;
- `versionName 1.8.0-alpha1`: validado;
- SHA-256 do APK calculado durante o job.

Os workflows e triggers temporários usados para transformação/auditoria foram removidos após a execução verde. O único workflow de distribuição que deve permanecer é o workflow manual de release.

## Limite de validação

O CI prova compilação, minificação, testes JVM, assinatura e invariantes estáticos. Ele não substitui smoke tests físicos em LSPosed/WhatsApp. Antes de merge para uma linha de release, executar em dispositivo:

- instalação/upgrade e abertura do app;
- ativação no LSPosed para WhatsApp e WhatsApp Business aplicáveis;
- inicialização do WhatsApp com todas as funções novas desligadas;
- abertura de todas as telas standalone e embedded de Preferences;
- abertura do companion app pelo menu injetado;
- ativação e desativação da barra flutuante;
- presets claro/escuro e cor customizada;
- salvar tema ativo e tema inativo, confirmando que somente o ativo altera o CSS global;
- executar Save, Test, Rollback e Safe Mode separadamente;
- teste temporário de CSS, expiração e safe mode;
- exportação, importação válida, importação inválida e rollback;
- diagnóstico com preview e verificação manual de redação;
- validação dos links de GitHub e Telegram;
- reinício do WhatsApp após alterações que exigem restart.

## Prompts e ordem de agentes

Os prompts atualizados e a ordem recomendada estão integralmente em `REVIEW_FIXES_A_B.md`.

Resumo vinculante:

1. revisão independente de A+B no Claude Code da Anthropic com Opus 5;
2. merge de A+B somente após aprovação e smoke test aplicável;
3. nova sessão Claude Code/Opus 5 para o Bloco C, derivada do estado merged;
4. Codex CLI/Terra somente no Bloco D, após C merged e validado.
