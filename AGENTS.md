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

## Code style

Formatting is `kotlin.code.style=official`: 4-space indent, trailing commas on wrapped
argument/parameter lists. There is no configured formatter — do not reformat files you did not
otherwise touch. Long lines exist (some exceed 100 columns); follow the surrounding file.

### File size and structure

**Keep files short. One file is one concern.** No file should grow past roughly 300 lines; when a
file mixes distinct responsibilities, split it before adding to it. `MessageBubble.kt` (434 lines)
is the current worst case and a standing candidate for another split.

- Split by **dependency direction or concern, not by line count**. `markdown/` is the worked
  example: `MarkdownText` (render) → `MarkdownParse` (CommonMark → blocks) → `MarkdownMath` (LaTeX
  extraction), with `LatexImage` (raster) shared by the render layer. A 667-line file became four
  files that each have one reason to change.
- Separate protocols/providers, one implementation per file (`providers/`). Separate UI pieces that
  are independently testable or reused (`chat/`, `markdown/`).
- Prefer **adding a file over growing an existing one**. If a new feature does not obviously belong
  to an existing concern, it gets its own file.
- Split shared internals as `internal`, not `public`; do not widen the module's API to enable a
  split.

### Comments

**Write as few comments as possible, and only where they carry weight.** Assume the reader knows
Kotlin and Compose; do not narrate what the code plainly says, and do not add a comment just to
have one.

- Comment a **non-obvious decision, invariant, or constraint** — something the code cannot show by
  itself: a workaround for a library bug, a deliberate ordering, a security boundary, a reason a
  seemingly simpler approach is wrong.
- Do **not** comment straightforward control flow, obvious naming, or restate a signature. No
  section-divider banners, no changelog notes in code, no commented-out code.
- Prefer no comment over a redundant one. If a comment and the code disagree, delete the comment.
- Existing files run about 4–8% comment lines; treat that as the ceiling, not a target. Match the
  surrounding file when editing it.

## Project conventions
- **User-facing strings live in resources**, both `res/values/` (Chinese, the default) and
  `res/values-en/`. Never hard-code user-visible text.
- **Settings are declared, not wired by hand.** Add a page in
  `presentation/settings/pages/` with `settingsPage(...)` and reference it in the
  `SettingsPageFiles` manifest in `SettingsRegistry.kt`; the registry handles navigation and
  ordering. Adding a child page to an existing category file needs no manifest change.
- **Persisted settings are additive.** `SettingsStore` encodes endpoint profiles as JSON and reads
  missing keys with defaults, so an added field stays backward compatible with existing installs.
  The JSONL session format is likewise read defensively; do not break existing session files.

### Commit messages

**Keep them short: a one-line subject, plus a few bullets only when the change is not obvious.**
The subject names the change; the body shows the key points, not a narrative.

- Subject: `type(scope): summary` — `feat` / `fix` / `refactor` / `docs` / `test` / `chore`.
  Imperative, lowercase, no trailing period.
- Body: at most a handful of bullets covering what changed and, for a fix, the **root cause** — not
  every touched file. Skip the body entirely when the subject is self-explanatory.
- No verification logs, no file inventories, no restating the diff. A reader should get the point in
  a few seconds.

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
