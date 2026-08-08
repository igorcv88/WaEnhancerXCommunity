# WaEnhancer Community — Handoff do Bloco C

## Estado entregue

- **Base:** `459acdb8670f8d7469e2138f87452429afb78f7c` (merge dos Blocos A+B em `integration/community`)
- **Branch:** `block-c-critical-storage-ipc`
- **Alvo da PR:** `integration/community`
- **Versão:** `1.8.0-alpha1` (`18001`) — inalterada
- **Application ID:** `com.waenhancer.community` — inalterado

`integration/community` havia sido apagada do remoto após o merge de A+B; foi recriada a partir
de `459acdb8` para que a PR tenha o alvo que o plano exige.

## Commits

| SHA | Assunto |
|---|---|
| `8d92a1cc` | security: inventory and classify preference storage |
| `e988129a` | fix: remove orphaned pro and helper user interface |
| `2d8329b2` | fix: stop the css failure counter from disabling the theme features |
| `4f94dfe1` | security: derive the backup allowlist from a definitive preference schema |
| `c184765d` | security: state what a settings export leaves behind |
| `13117171` | security: introduce public and private preference stores |
| `bd2d0991` | security: validate the calling uid on the configuration provider |
| `3f514939` | security: authenticate tasker and broadcast IPC |
| `ed5ab51d` | security: validate the calling uid on the deleted-data provider |
| `dc31dd1f` | security: reduce permissions and harden the updater |
| `580d585b` | docs: record the block C storage, IPC and update contracts |

62 arquivos, +4.996 / −740.

## Defeitos de A+B corrigidos aqui

O Bloco C começou com A+B congelados. O smoke test físico revelou defeitos demonstráveis que
bloqueavam o Gate C, e por decisão explícita do mantenedor foram corrigidos nesta branch.

1. **Funções Pro/Helper ainda visíveis.** As classes tinham sido removidas, mas a UI ficou.
   15 chaves declaradas nas telas standalone e embedded, com strings traduzidas e entrada no
   settings map, sem nenhum leitor em Java; mais duas entradas de busca que ainda anunciavam o
   Helper. Removidas. **Ver secção 5 de `BLOCK_C_INVENTORY.md` para o manifesto completo do que
   foi removido e o que cada função prometia** — é o insumo para reimplementação futura.
2. **Import de backup não alterava nada.** A allowlist tinha sido escrita a partir da prosa do
   plano, não do código: nomeava 43 chaves inexistentes e errava outras. Export e import
   trafegavam um subconjunto pequeno e parcialmente fictício. Agora a allowlist é derivada do
   schema, com aliases para os nomes errados e um relatório que diz "aplicadas X de Y".
3. **`read-only implementation` matando os features de tema.** `CustomThemeV2.doHook()` e
   `CustomView.doHook()` chamavam `edit()` num `XSharedPreferences`. A exceção escapava do
   `doHook()` e desligava os dois features a cada inicialização.
4. **Export descartava segredos em silêncio.** Agora o aviso aparece antes de todo export e
   nomeia explicitamente os segredos que ficaram de fora.

## Contratos, stores e protocolos alterados

### Stores

| Store | Arquivo | Acesso | Conteúdo |
|---|---|---|---|
| público | preferências padrão | world-readable, lido por `XSharedPreferences` | 190 chaves que hooks precisam; nunca segredo |
| privado | `private_config` | `MODE_PRIVATE`, só o UID do módulo | 27 chaves: segredos, cache, estado interno |

`PreferenceSchema` é a fonte única de verdade: 221 entradas, cada uma com **sensibilidade**
(decide exportação) e **store** (decide colocação). O construtor recusa colocar um segredo no
store público.

### Providers

| Authority | Antes | Agora |
|---|---|---|
| `${applicationId}.hookprovider` | exportado, sem permissão, sem checagem; get/put/remove/clear genéricos para qualquer app | UID validado antes de limpar a identidade; leitura só de chaves públicas do schema; escrita só de chaves conhecidas; `clear_preferences` removido; `get_secret` com UID validado |
| `${applicationId}.provider` | exportado, insert e get/put_preference sem checagem | UID validado em toda operação; métodos de preferência removidos |

### Protocolo de automação

- Ações passam a ser `${applicationId}.MESSAGE_SENT` / `.MESSAGE_RECEIVED` / `.EVENT`; a ação
  pré-rebrand continua aceita por uma release.
- Envio exige token por instalação (CSPRNG, store privado, nunca exportado, comparação em tempo
  constante), pacote na allowlist, dedup e rate limit.
- O forward interno para o processo do WhatsApp também carrega e valida o token.
- `MESSAGE_RECEIVED` e `EVENT` deixaram de ser broadcasts implícitos com o texto da mensagem;
  agora são intents explícitos só para pacotes da allowlist, e o corpo só vai com opt-in.
- Modo legado sem autenticação existe por uma release, desligado por padrão.

## Migrations

Migration version 1. Sequência no start: `copyPrivateValues` → geração do token →
`removeMigratedSecrets`. Snapshot antes de escrever, verificação valor a valor, remoção só
depois da cópia verificada e com leitor disponível. `clear()` nunca é usado.

Detalhe completo, incluindo upgrade, downgrade, falha e rollback, em `MIGRATIONS.md`.

## Evidências

**Upgrade.** `PreferenceMigrationTest` cobre a cópia preservando o valor público, o snapshot
escrito antes de qualquer escrita, idempotência, e settings públicos nunca migrando.

**Rollback.** Coberto por teste: restaura do snapshot, não apaga valores que o snapshot não
menciona, e rejeita snapshot corrompido sem danificar o store.

**Downgrade.** Coberto por teste: enquanto a cópia é aditiva, um build que só conhece o store
público encontra tudo.

**Recusa destrutiva.** Dois testes garantem que segredos não saem do store público sem cópia
verificada e sem leitor disponível, e um terceiro que a remoção é recusada se a cópia privada
divergir.

## Testes executados

- `assembleWhatsappDebug` — verde
- `assembleWhatsappRelease` com R8 — verde
- `testWhatsappDebugUnitTest` — **88 testes, 0 falhas** (era 44 na base)
- Auditoria estática: 0 referências a Pro/Helper/Firebase/keybox; 0 blocos PEM; 0 classes Pro em
  XML; 0 `upload-artifact`; workflow exclusivamente `workflow_dispatch`; URLs em
  `igorcv88/WaEnhancerX`; menu CSS sem fall-through; salvar tema inativo ainda não o ativa.

Testes novos: `PreferenceSchemaTest` (7), `PreferenceMigrationTest` (13), `TaskerGuardTest` (14),
`UpdateVerifierTest` (7), `BackupExclusionTest` (3).

## Riscos residuais

1. **Sem smoke test físico do Bloco C.** Não há dispositivo nesta sessão. Precisa validação em
   LSPosed antes de merge para uma linha de release — em especial a migração no primeiro start,
   a leitura de segredos pelo `SecretBridge` e o envio via Tasker com token.
2. **A allowlist de pacotes do Tasker não tem UI ainda.** As chaves existem no schema
   (`tasker_allowed_packages`, `tasker_legacy_unauthenticated`, `tasker_broadcast_message_body`)
   e o guard as consome, mas não há tela para editá-las nem para exibir o token. Sem allowlist
   configurada a integração recusa tudo, que é o default seguro, porém isso torna a integração
   inutilizável até a UI existir. **Este é o item mais urgente do próximo bloco.**
3. **Broadcast não carrega identidade de chamador.** A allowlist de pacotes é defesa em
   profundidade; o token é a fronteira real. Declarado no código e no `SECURITY.md`.
4. **`UpdateVerifier` não está ligado ao fluxo de download.** A verificação existe e está testada,
   mas `UpdateDownloader.installApk` ainda não a chama, porque o checksum publicado precisa vir do
   corpo da release e o parsing disso não foi feito nesta branch. O `-d` do install root já saiu.
5. **Defeitos visuais de A+B seguem abertos**, por decisão de escopo: barra flutuante com sliders
   sem efeito e preview no topo em vez de footer fixo; ícones ausentes no dropdown injetado;
   ícone em branco na entrada WaeX Settings. Pista para os ícones: `WppXposed.mapAllResources()`
   reescreve os campos de `R.*` para IDs do host, e `DesignUtils.getDrawable()` resolve contra os
   recursos do módulo — os dois lados discordam sobre o espaço de ID. Destinados à branch
   `block-ab-stabilization`.
6. **Toggles embedded possivelmente ainda inertes.** O fix do crash de tema (#3) deve resolver o
   grupo Appearance, mas isso precisa de reteste em dispositivo para confirmar.

## Invariantes que o próximo bloco deve preservar

- `PreferenceSchema` é a única fonte de verdade. Toda chave nova entra nele; os testes de guarda
  falham se uma tela declarar chave sem schema, se a allowlist divergir, ou se um alias apontar
  para chave inexistente.
- Um segredo nunca vai para o store público, nunca é exportado, nunca aparece em log ou
  diagnóstico.
- O processo do WhatsApp não escreve preferências diretamente. Use `SafePrefs`.
- Migração nunca apaga antes de verificar; `clear()` não é etapa de migração.
- Todo entry point cross-process valida UID antes de limpar a identidade do Binder.
- Nada de Firebase, Helper, licenciamento, Pro, loaders externos, tokens compilados, material
  criptográfico embutido ou workflow automático de release.
- Invariantes pós-Codex de A+B continuam valendo.
- O banco do `Deleted for Me` não foi tocado; o schema dele pertence ao Bloco D.
