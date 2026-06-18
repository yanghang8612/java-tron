# AGENTS.md — java-tron

Build: `./gradlew build`. Test: `./gradlew :framework:test` (see CONTRIBUTING.md).

## Knowledge base — read this for client internals

`java-tron-kb` documents this client's internals (TVM, transaction lifecycle, consensus,
resource/staking, storage, networking, crypto, events, shielded, assets) — the layer between
TIPs (proposals) and documentation-en (user docs).

- Local checkout: `../java-tron-kb` (kept beside this repo). Published: <KB repo URL TBD>.
- Start at `../java-tron-kb/INDEX.md`; conventions in `../java-tron-kb/AGENTS.md`.
- Trust: `status: reviewed` pages are human-confirmed; `status: draft` are AI-written —
  verify against this source before relying on them for consensus-critical work.
- **Before changing consensus-critical code** (VM, actuators, consensus, proposal gates), read
  the relevant `../java-tron-kb/invariants/` entries — they record properties that fork the
  chain or lose funds if broken.

## Conventions

- Gradle multi-module: framework, actuator, chainbase, consensus, crypto, common, protocol.
- In consensus paths never call `java.lang.Math` directly — use the `Maths` / `StrictMath`
  wrappers gated by `disableJavaLangMath` (see `../java-tron-kb/invariants/`).
