# WaEnhancer Community — Handoff dos Blocos A e B

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

GitHub Actions final: run `30801556300`.

Resultados:

- auditoria estática de referências Helper/Pro/Firebase: passou;
- auditoria de material criptográfico embutido: passou;
- auditoria do backup transacional/allowlist: passou;
- auditoria do workflow manual-only: passou;
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
- ativação e desativação da barra flutuante;
- presets claro/escuro e cor customizada;
- teste temporário de CSS, expiração e safe mode;
- exportação, importação válida, importação inválida e rollback;
- diagnóstico com preview e verificação manual de redação;
- reinício do WhatsApp após alterações que exigem restart.

## Prompt para o próximo responsável — Bloco C

Você é o responsável pelo Bloco C do projeto WaEnhancer Community no repositório `igorcv88/WaEnhancerX`.

Comece pela PR `#1`, branch `block-a-foundation-visual`, com base de integração `integration/community`. Leia integralmente `HANDOFF_WaEnhancer_Community_v2_ExecutionPlan.md`, `HANDOFF_BLOCKS_A_B.md`, `ARCHITECTURE.md`, `MIGRATIONS.md`, `SECURITY.md` e `PRIVACY.md` antes de editar qualquer arquivo.

Considere os Blocos A e B congelados, salvo correção de defeito demonstrável. A implementação entregue está em `1.8.0-alpha1` (`18001`), application ID `com.waenhancer.community`, baseada no upstream estável 1.7.0 commit `433a1c630bc1286f2db3c657a63f477aa0aa426d`. Não reintroduza Firebase, Helper, licenciamento, sistema Pro, DEX/`.so` externo, token público compilado, material criptográfico embutido ou workflow automático de release.

Execute somente o escopo designado ao Bloco C no plano autoritativo. Respeite rigorosamente as fronteiras de ownership de storage, IPC, providers, Tasker e banco `Deleted for Me`. Não altere formatos persistidos ou contratos entre processos sem migração explícita, versionada, transacional, testada e documentada. Preserve compatibilidade de leitura quando exigida pelo plano e nunca use `clear()` antes da validação completa.

Antes de implementar:

1. faça inventário dos stores, authorities, providers, broadcasts, serviços, receivers e consumidores cross-process existentes;
2. escreva o contrato alvo e a matriz de migração/rollback;
3. identifique quais chaves pertencem ao app, ao processo do WhatsApp e às integrações externas;
4. defina testes de compatibilidade e falha antes de modificar produção.

Durante a implementação:

- trabalhe em branch própria derivada do head aprovado dos Blocos A+B;
- mantenha commits pequenos e temáticos;
- use JDK 17;
- não publique artifacts no Actions;
- não acione releases automaticamente;
- não misture mudanças visuais dos Blocos A+B com storage/IPC;
- trate entradas externas como não confiáveis;
- use allowlists, permissões mínimas, authorities derivadas do application ID e validação de caller quando aplicável;
- registre decisões e gotchas no handoff do Bloco C.

Critérios mínimos de entrega:

- builds debug e release verdes;
- testes unitários e de migração verdes;
- auditoria de authorities e componentes exportados;
- teste de upgrade a partir do estado produzido pelos Blocos A+B;
- rollback ou recuperação documentados;
- nenhum dado sensível em logs/backups;
- nenhuma regressão no workflow manual de release;
- smoke test em dispositivo LSPosed documentado, ou declaração explícita do que não pôde ser testado.

Ao final, abra uma PR contra `integration/community`, descreva exatamente o que mudou, o que não mudou, os contratos migrados, as evidências de testes e os riscos residuais. Não faça merge automático.

## Prompt para revisão independente dos Blocos A+B

Faça uma revisão independente, adversarial e baseada em evidências da PR `#1` do repositório `igorcv88/WaEnhancerX`, comparando `block-a-foundation-visual` contra `integration/community`.

Leia integralmente `HANDOFF_WaEnhancer_Community_v2_ExecutionPlan.md` e `HANDOFF_BLOCKS_A_B.md`. Não aceite o handoff como prova: confirme cada afirmação no diff, na árvore final e nos logs do GitHub Actions run `30801556300`.

Objetivos da revisão:

1. verificar aderência completa ao escopo dos Blocos A e B e apontar requisitos ausentes ou implementados apenas superficialmente;
2. confirmar ausência real de Firebase, Helper, licenciamento, sistema Pro, loaders de DEX/`.so`, AIDL associado, tokens compilados, material criptográfico embutido e referências residuais em código, recursos, manifest, Gradle, ProGuard e assets;
3. revisar o workflow manual de release, secrets, tratamento do keystore, verificação de certificado, package/version, SHA-256, ausência de debug fallback, ausência de triggers automáticos e ausência de upload de artifacts;
4. revisar a barra inferior flutuante quanto a defaults, faixas, clamp, persistência, restauração do layout original, modos de scroll, lifecycle, reflection/hook fragility e compatibilidade com mudanças do WhatsApp;
5. revisar a engine semântica de cores quanto a cobertura de tokens, contraste, claro/escuro, alpha, custom color, fallback e ausência de substituição global insegura;
6. revisar CSS quanto a validação, limite de tamanho, safe mode, last-known-good, teste temporário, expiração, crash loops e caminhos de recuperação;
7. revisar backup v1 quanto a allowlist, schema, tipos, ranges, unknown keys, sensitive keys, limite de arquivo, snapshot, atomicidade, rollback, compatibilidade legada e possibilidade de partial writes;
8. revisar diagnóstico quanto a coleta mínima, persistência, limites, redação, preview, compartilhamento e vazamento por exceções/logs paralelos;
9. procurar regressões funcionais introduzidas pela remoção de Pro/Helper, especialmente chamadas que agora retornam sucesso sem executar, telas mortas, manifest inconsistente, recursos órfãos e comportamento silenciosamente degradado;
10. avaliar segurança Android: componentes exportados, permissions, FileProvider/ContentProvider authorities, intent spoofing, path traversal, URI grants e superfícies cross-process, sem avançar para a reestruturação pertencente ao Bloco C.

Execute pelo menos:

- busca estática por termos proibidos e por blocos PEM;
- `./gradlew --no-daemon clean assembleWhatsappDebug assembleWhatsappRelease testWhatsappDebugUnitTest` com JDK 17;
- inspeção do APK release com `apksigner`, `aapt` e análise do manifest final;
- comparação do certificado do APK com o alias do keystore de teste, sem expor secrets;
- testes unitários adicionais para qualquer edge case encontrado;
- testes negativos de backup e CSS;
- revisão manual das mudanças de Gradle/Manifest/ProGuard;
- quando houver dispositivo LSPosed disponível, smoke test com todas as funções novas desligadas e depois ativadas individualmente.

Formato obrigatório da resposta:

- veredito: `APROVAR`, `APROVAR COM RESSALVAS` ou `SOLICITAR ALTERAÇÕES`;
- achados ordenados por severidade, cada um com arquivo/linha, impacto, evidência e correção proposta;
- requisitos do handoff não comprovados;
- testes executados e resultados;
- riscos residuais específicos de hooks/versões do WhatsApp;
- checklist final de merge.

Não faça mudanças no código durante a primeira passagem. Primeiro entregue o relatório de revisão. Só implemente correções após seleção explícita dos achados.