# Agent guidelines

- Read this file, `ARCHITECTURE.md`, and `handoff.md` before changing the repository. Use English for reasoning, code documentation, commits, PRs, and user-facing replies regardless of the user's language.
- Ask the user when required facts are missing or a choice depends on their preference. Do not invent device evidence or silently choose a product/architecture change under uncertainty. Continue independent work while awaiting answers.
- Preserve the Community runtime: repository-built executable code, one Xposed entrypoint, feature-local compatibility failure handling, and the existing public/private preference boundary. Follow `ARCHITECTURE.md` for storage, IPC, backups, and security invariants.
- Audit upstream changes against this fork before porting them. Record source SHAs, adaptations, exclusions, and pending decisions in `handoff.md` or a linked audit. Keep hooks version-aware and distinguish static validation from device validation.
- Reuse an existing related open PR and its branch. Otherwise create a focused PR targeting the current default branch. PRs must be ready for review (`draft: false`); mark a related draft ready when updating it. Do not merge without instruction.
- Keep GitHub Actions manual-only. Do not dispatch workflows unless explicitly requested. Run available local checks appropriate to the change and report blocked checks honestly.
- Update `handoff.md` at the end of every task with the current branch/PR, changes and reasons, validation, unresolved issues, and concrete next steps. Keep durable guidance here and changing state in the handoff. `CLAUDE.md` is a symlink to this file.
