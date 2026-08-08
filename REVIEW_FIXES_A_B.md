# WaEnhancer Community — Addendum pós-review dos Blocos A+B

Este documento complementa `HANDOFF_BLOCKS_A_B.md` e prevalece quando houver divergência sobre o estado pós-review.

## Estado final

- PR: `#1`
- Branch: `block-a-foundation-visual`
- Base: `integration/community`
- Validação pós-review: GitHub Actions run `30838456180`
- Application ID: `com.waenhancer.community`
- Versão: `1.8.0-alpha1` (`18001`)

## Avaliação dos comentários do Codex Reviewer

Os quatro comentários foram considerados válidos e aplicados. Nenhum foi descartado como incorreto.

1. **Classes de Preference removidas ainda referenciadas em XML — válido.**
   - As referências não estavam apenas nos três arquivos citados pelo Codex.
   - Foram removidas de todos os XMLs, inclusive telas embutidas.
   - `ProSwitchPreference` foi substituída por `rikka.material.preference.MaterialSwitchPreference`.
   - `ProPreferenceCategory` foi substituída por `PreferenceCategory`.

2. **Targets runtime ainda usando `com.waenhancer` — válido.**
   - Todos os targets explícitos encontrados em `Utils`, `FeatureLoader`, `ShowEditMessage` e `UpdateChecker` passaram a usar `BuildConfig.APPLICATION_ID`.
   - Os nomes das classes continuam no namespace Java `com.waenhancer`, intencionalmente; somente o package instalado é `com.waenhancer.community`.

3. **Salvar tema inativo ativava CSS global — válido.**
   - O editor agora recebe e respeita a chave da preferência de tema selecionado.
   - Tema ativo: valida, atualiza o estado CSS seguro e grava o arquivo.
   - Tema inativo: valida e grava somente o `style.css`, sem alterar `custom_css`, last-known-good, safe mode ou teste temporário.

4. **URLs com nome de exibição em vez do slug do repositório — válido.**
   - GitHub passou a usar `igorcv88/WaEnhancerX`.
   - Telegram voltou ao endereço válido `https://t.me/WaEnhancerX`.
   - Links do Home e About, API de contributors, issues e releases foram normalizados.

## Defeito adicional corrigido durante a análise

O `switch` de `TextEditorActivity.onOptionsItemSelected()` usava cases sem interrupção. Assim, uma ação como **Save** podia continuar executando Test Theme, rollback, safe mode, exit, clear, import e export. O fluxo foi convertido para cases sem fall-through, cada ação retornando imediatamente.

## Validação pós-review

O run `30838456180` passou integralmente:

- zero referências XML às classes Pro removidas;
- zero URLs malformadas pelo rebrand;
- zero targets runtime explícitos usando o application ID antigo;
- salvamento de tema inativo sem ativação global;
- ausência de fall-through no menu CSS;
- `assembleWhatsappDebug`;
- `assembleWhatsappRelease` com R8;
- `testWhatsappDebugUnitTest`;
- release assinada com os secrets reais;
- `apksigner verify`;
- certificado do APK igual ao alias do keystore;
- rejeição de certificado Android Debug;
- package, versionCode e versionName validados;
- SHA-256 calculado.

Os workflows, scripts e triggers temporários usados para aplicar e validar os fixes foram removidos. O workflow permanente continua sendo apenas o workflow manual de release.

## Ordem recomendada de agentes

1. **Revisão independente de A+B:** Claude Code da Anthropic com **Opus 5**, em sessão exclusivamente de revisão.
2. Corrigir somente achados aprovados, repetir gates e smoke test físico.
3. Fazer merge de A+B em `integration/community` somente após aprovação.
4. **Bloco C:** nova sessão do Claude Code da Anthropic com **Opus 5**, iniciada do estado merged.
5. **Bloco D:** Codex CLI com **Terra**, somente após C merged e validado.

Sol não deve ser o único reviewer de A+B porque foi o autor principal desses blocos. Terra já forneceu revisão automática e está reservado ao Bloco D. Sonnet 5 pode auxiliar em loops mecânicos de build dentro do Claude Code, mas não deve substituir Opus 5 na revisão crítica nem na arquitetura do Bloco C.

---

## Prompt atualizado — reviewer independente dos Blocos A+B

Faça uma revisão independente, adversarial e baseada em evidências da PR `#1` do repositório `igorcv88/WaEnhancerX`, comparando `block-a-foundation-visual` contra `integration/community`.

Trabalhe em modo **review-only**. Não altere código na primeira passagem e não inicie o Bloco C.

Leia integralmente:

- `HANDOFF_WaEnhancer_Community_v2_ExecutionPlan.md`;
- `HANDOFF_BLOCKS_A_B.md`;
- `REVIEW_FIXES_A_B.md`;
- `ARCHITECTURE.md`;
- `MIGRATIONS.md`;
- `SECURITY.md`;
- `PRIVACY.md`.

Não aceite os handoffs ou a descrição da PR como prova. Confirme cada afirmação no diff, na árvore final, no APK e nos logs. Use o run pós-review `30838456180` como evidência auxiliar, não como substituto da inspeção.

### Escopo obrigatório

1. Verificar aderência integral aos Blocos A+B do plano autoritativo.
2. Confirmar ausência real de Firebase, Google Services, Helper, licenciamento, sistema Pro, AIDL associado, loaders externos de DEX/`.so`, tokens compilados e material criptográfico embutido.
3. Procurar referências residuais em Java/Kotlin, XML, Manifest, Gradle, version catalog, assets, native libraries, ProGuard/R8, strings e resources.
4. Revisar o workflow de release quanto a `workflow_dispatch` exclusivo, secrets, keystore temporário, ausência de debug fallback, certificado, package/version, SHA-256, publicação direta em GitHub Releases e ausência de Actions artifacts.
5. Revisar a barra flutuante quanto a defaults conservadores, faixas, clamp, migração legada, restauração do layout nativo, indicador desligado por padrão, modos distintos de scroll e fragilidade de hooks.
6. Revisar a engine semântica de cores quanto a tokens, contraste, claro/escuro, alpha, presets, custom color, fallback e ausência de substituição indiscriminada de pixels/resources.
7. Revisar CSS quanto a limite, validação, last-known-good, safe mode, contador de falhas, teste temporário, expiração, crash recovery e rollback.
8. Revisar backup v1 quanto a allowlist, schema, tipos, ranges, unknown/sensitive keys, limite, snapshot, atomicidade, rollback, compatibilidade legada e ausência de partial writes.
9. Revisar diagnóstico quanto a coleta mínima, limites, redação, preview, compartilhamento explícito e ausência de upload automático.
10. Procurar regressões funcionais após a remoção de Pro/Helper: telas mortas, resources órfãos, componentes inconsistentes, permissões desnecessárias e caminhos que retornam sucesso sem executar.
11. Avaliar segurança Android: componentes exportados, permissions, providers, authorities, intent spoofing, URI grants, path traversal e superfícies cross-process, sem implementar ainda a reestruturação do Bloco C.

### Regressões pós-Codex que devem ser verificadas explicitamente

- nenhum XML referencia `ProSwitchPreference` ou `ProPreferenceCategory` removidas;
- todos os targets runtime do app usam `BuildConfig.APPLICATION_ID`/`com.waenhancer.community`, mantendo apenas nomes de classe no namespace Java legado;
- salvar um tema não selecionado não altera o CSS ativo nem os estados last-known-good/safe-mode/test;
- `TextEditorActivity.onOptionsItemSelected()` não possui fall-through;
- links de GitHub usam `igorcv88/WaEnhancerX` e nenhum URL contém `WaEnhancer Community` como slug;
- links do Telegram são sintaticamente válidos;
- telas standalone e embedded de Preferences abrem sem `ClassNotFoundException`.

### Execução mínima

- buscas estáticas pelos termos proibidos, blocos PEM, classes Pro removidas, package antigo em targets runtime e URLs malformadas;
- `./gradlew --no-daemon clean assembleWhatsappDebug assembleWhatsappRelease testWhatsappDebugUnitTest` com JDK 17;
- inspeção do APK com `apksigner`, `aapt` e Manifest final;
- comparação do certificado do APK com o alias do keystore sem expor secrets;
- testes negativos de backup e CSS;
- testes adicionais para os edge cases identificados;
- smoke test físico em LSPosed, quando houver dispositivo disponível.

No smoke test, valide instalação limpa/upgrade, abertura de todas as telas de Preferences standalone e embedded, abertura do app pelo menu injetado, links do Home/About, tema ativo/inativo, Save/Test/Rollback/Safe Mode isoladamente, expiração do teste CSS, barra flutuante e restauração do layout nativo.

### Formato obrigatório

- veredito: `APROVAR`, `APROVAR COM RESSALVAS` ou `SOLICITAR ALTERAÇÕES`;
- achados ordenados por severidade;
- arquivo/linha, impacto, evidência e correção proposta;
- requisitos não comprovados;
- testes executados e resultados;
- riscos residuais específicos de hooks e versões do WhatsApp;
- checklist final de merge.

Não faça mudanças no código durante a primeira passagem. Primeiro entregue somente o relatório. Correções devem ocorrer apenas depois de seleção explícita dos achados.

---

## Prompt atualizado — próximo responsável pelo Bloco C

Você é o responsável pelo Bloco C do WaEnhancer Community no repositório `igorcv88/WaEnhancerX`, trabalhando no **Claude Code da Anthropic com Opus 5**.

Não comece implementação enquanto os Blocos A+B não estiverem revisados, aprovados e merged em `integration/community`. Não derive o Bloco C de uma PR ainda aberta.

Ao iniciar, crie uma branch própria a partir do commit merged e registre esse SHA. Leia integralmente:

- `HANDOFF_WaEnhancer_Community_v2_ExecutionPlan.md`;
- `HANDOFF_BLOCKS_A_B.md`;
- `REVIEW_FIXES_A_B.md`;
- `ARCHITECTURE.md`;
- `MIGRATIONS.md`;
- `SECURITY.md`;
- `PRIVACY.md`;
- relatório final da revisão independente de A+B.

Considere A+B congelados, salvo defeito demonstrável que bloqueie C. Não reintroduza Firebase, Helper, licenciamento, Pro, loaders externos de DEX/`.so`, tokens compilados, material criptográfico embutido ou workflow automático de release.

### Escopo do Bloco C

Execute de ponta a ponta o bloco crítico de arquitetura de segurança e dados definido no plano. Opus 5 é responsável pela arquitetura e implementação principal. Sonnet 5 pode ser usado somente para loops mecânicos de Gradle/R8/Manifest no mesmo ambiente, sem redesenhar decisões. Não delegue a arquitetura ao Terra e não alterne edições com ChatGPT ou Codex durante o bloco.

Antes de editar:

1. inventarie todas as chaves, stores, readers, writers, processos, UIDs, providers, authorities, broadcasts, services, receivers e consumidores cross-process;
2. produza a matriz `key -> writer -> reader -> process -> sensitivity -> migration`;
3. classifique dados como público, privado, cache, segredo, runtime ou obsoleto;
4. documente contratos atuais, invariantes, contrato alvo, compatibilidade, migração, rollback e falhas;
5. defina testes antes de modificar produção.

Durante a implementação:

- introduza `PreferenceSchema` definitivo e `SafePrefs` conforme o plano;
- implemente separação pública/privada e migração dual-read/shadow-write sem perda;
- derive authorities do application ID;
- valide UID/caller e trate entradas externas como não confiáveis;
- endureça providers, broadcasts e Tasker conforme o protocolo definido;
- preserve importação de backups legados seguros;
- nunca use `clear()` antes de validação integral;
- não exponha segredos no store público, logs, diagnostics ou backups;
- mantenha commits pequenos e temáticos;
- não misture mudanças visuais não relacionadas;
- não publique Actions artifacts nem acione release automaticamente.

Preserve explicitamente os fixes pós-Codex:

- nenhum XML pode voltar a referenciar classes Pro removidas;
- targets runtime devem continuar usando `BuildConfig.APPLICATION_ID`;
- salvar tema inativo não pode ativá-lo;
- o menu CSS não pode reintroduzir fall-through;
- URLs devem usar slugs válidos.

### Gate C mínimo

- configurações preservadas em upgrade;
- nenhum segredo no storage público;
- provider rejeita UID não autorizado;
- Tasker e broadcasts rejeitam chamadas inválidas;
- backups legados continuam importáveis;
- rollback testado;
- debug/release/R8 e testes de migração verdes;
- Manifest e componentes exportados auditados;
- smoke test LSPosed documentado, ou limitação explícita;
- nenhuma regressão no workflow manual de release.

Ao final, faça uma única consolidação, abra PR contra `integration/community`, liste commits, arquivos, contratos e stores alterados, migrations, evidências de upgrade/rollback, testes e riscos residuais. Não faça merge automático e não inicie o Bloco D.
