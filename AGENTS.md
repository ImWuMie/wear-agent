# AGENTS.md

WearAgent — an AI chat client for Wear OS round-screen watches, talking to OpenAI-compatible
(Completions / Responses) and Anthropic endpoints. Kotlin, Jetpack Compose for Wear OS.

## Build and verify

JDK 17+ (CI uses 21); the module targets Java 11 bytecode. Android SDK 36.1.

```bash
./gradlew :app:assembleDebug          # gradlew.bat on Windows
./gradlew :app:testDebugUnitTest      # JVM tests, no device needed
./gradlew :app:connectedDebugAndroidTest   # Compose UI tests, needs an emulator/device
```

CI (`.github/workflows/ci.yml`) only builds debug and release APKs — **it runs no tests**. A green
CI run does not mean the tests pass; run them locally.

Instrumented tests need a booted emulator. A Wear OS API 34+ image works. The suite hangs on
Espresso 3.5.0 (`InputManager.getInstance` was removed in API 34); the version catalog pins 3.7.0 —
do not lower it. If a Wear emulator's clock drifts behind the host, TLS validation fails
(`Chain validation failed`) because the endpoint certificate looks not-yet-issued; fix the emulator
clock rather than the app.

## Architecture

```mermaid
flowchart LR
    UI["presentation/<br/>Compose UI"] --> SVC["AgentService<br/>foreground service"]
    SVC --> LOOP["ToolLoop"]
    LOOP --> PROV["providers/<br/>SSE protocols"]
    SVC --> LOG["session/<br/>log + settings"]
    UI -. collects state .-> SVC
```

- `agent/` — protocol and turn logic. `ChatProvider` defines the interface; `providers/` holds one
  SSE implementation per protocol, all extending `SseChatClient` (HTTP, cancellation, SSE framing,
  model-list retry). `ToolLoop` runs one turn across multiple tool rounds. `Transcript` is the
  provider-agnostic history model; each provider converts it to its own wire format.
- `session/` — `SettingsStore` (DataStore) and `SessionLog` (append-only JSONL, one file per
  session). A killed process replays the file; nothing lives only in memory.
- `presentation/` — Wear Compose UI. `chat/` (screen, pull-up drawer, bubbles, action sheets),
  `markdown/` (renderer), `settings/` (page registry + pages).

Keep `agent/` free of Android UI dependencies. `AgentService.kt` is the only file there that imports
`android.*` (it is a `Service`); everything else is plain Kotlin so it stays JVM-testable.

## Rendering pipeline

`presentation/markdown/` is split by dependency direction — `MarkdownText` (render) →
`MarkdownParse` (CommonMark → blocks) → `MarkdownMath` (LaTeX extraction); `LatexImage` (raster) is
shared by the render layer. Shared internals are `internal`, not `public`.

Two invariants that cost real bugs to find:

- **Math is extracted before markdown parsing.** LaTeX escapes (`\\`, `\{`, `\_`) must survive the
  CommonMark pass, so formulas are replaced with opaque markers first and re-inserted afterwards.
  Fenced and inline code are copied verbatim so a literal `$` in code is not treated as a formula.
- **jlatexmath keeps process-wide mutable statics.** `MacroInfo.Commands` is global, so a
  `\newcommand` in one formula would otherwise redefine commands for every formula rendered
  afterwards, across messages and sessions. `renderFormula` snapshots and restores that table
  around each parse. Built-in aliases (`\dfrac`, `\operatorname`, matrix environments) register
  *lazily on first parse*, so `ensureCommandTableInitialized()` forces those classes to load before
  the snapshot — without it the restore deletes the built-ins.

Inline formulas render in TeX text style, display formulas in display style; the stock
`JLatexMathDrawable` hard-codes display style, which inflates inline operators to block height, so
rasterization goes through `TeXIconBuilder` + `AndroidGraphics2D` directly.

## Conventions

- **Comments explain why, not what.** Match the existing density: a doc comment on non-obvious
  declarations, and a note at a non-obvious decision or invariant. Do not narrate straightforward
  code.
- **User-facing strings live in resources**, both `res/values/` (Chinese, the default) and
  `res/values-en/`. Never hard-code user-visible text.
- **Settings are declared, not wired by hand.** Add a page in
  `presentation/settings/pages/` with `settingsPage(...)` and reference it in the
  `SettingsPageFiles` manifest in `SettingsRegistry.kt`; the registry handles navigation and
  ordering. Adding a child page to an existing category file needs no manifest change.
- **Persisted settings are additive.** `SettingsStore` encodes endpoint profiles as JSON and reads
  missing keys with defaults, so an added field stays backward compatible with existing installs.
  The JSONL session format is likewise read defensively; do not break existing session files.
- Long lines exist in this codebase (some exceed 100 columns); follow the surrounding file rather
  than reformatting. There is no configured formatter — do not run one.
- Commit messages state the root cause of a fix, not just the change. See `git log` for the house
  style.

## Security notes

`agent/InsecureTls.kt` backs the per-endpoint "Ignore SSL certificate validation" setting. It
accepts any certificate and hostname, so it also removes interception protection. It must stay an
explicit, per-endpoint, default-off choice, and never be applied to the fixed web-search endpoint
(`tools/WebSearchTool.kt`) or shared with a verifying client.

## Verifying changes

Behavioural changes need a run, not just a compile. Prefer the narrowest proof that exercises the
changed path:

- Protocol / settings / session logic — `:app:testDebugUnitTest` (MockWebServer stands in for the
  endpoint; `okhttp-tls` builds a self-signed HTTPS server for TLS cases).
- Rendering and UI — `:app:connectedDebugAndroidTest` against a booted emulator.
- Anything user-visible — drive the installed debug APK on an emulator and look at it.

Watch for state that survives across renders or turns (macro tables, caches, `sent` lists) and for
values that can look equal while the UI should have changed; these are where bugs have hidden.
