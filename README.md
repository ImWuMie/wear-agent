# WearAgent

**English** | [中文](README_zh.md)

<p align="center">
  <img src="docs/screenshot_chat.png" width="200" alt="Chat with Markdown and LaTeX rendering">
  <img src="docs/screenshot_request.png" width="200" alt="Request: what was sent to the model">
  <img src="docs/screenshot_context.png" width="200" alt="Context usage">
  <img src="docs/screenshot_settings.png" width="200" alt="Settings">
</p>

A lightweight AI chat client for Wear OS round-screen watches. Talk to any OpenAI-compatible API (or Anthropic) right from your wrist, with streaming replies, thinking traces, and Markdown/LaTeX rendering — all within the constraints of a watch: small screen, limited memory.

## Features

### Chat

- **Streaming chat** — SSE streaming for OpenAI Completions / Responses and Anthropic Messages APIs
- **Thinking traces** — reasoning models stream their chain of thought into a collapsible block inside the reply bubble; it auto-expands while thinking, auto-collapses when the body starts, and is persisted with the message
- **Markdown + LaTeX** — CommonMark with GFM tables/strikethrough; `$..$`, `$$..$$`, `\(..\)` and `\[..\]` formulas rendered inline or as blocks via JLaTeXMath, with literal dollars and code spans left untouched
- **Token usage** — each reply shows `↓ out, ↑ in (cached) · t/s`
- **Message actions** — long-press a bubble for a fullscreen action page: copy, select text, regenerate (works on user messages too), edit, delete

### Tools

The model can call tools on its own; each is off by default and toggled in Settings with its privacy note.

- **Web search** — Parallel's public search service, no extra API key; results are saved locally with the reply
- **Fetch** — arbitrary method, headers and body against any URL; redirects are not followed, the response is size-capped
- **Live tool blocks** — each call shows as a collapsible block in the reply bubble (running → completed/failed), summarised by the tool itself and expandable to its result

### Pages

Swipe the chat sideways to walk outward through what was sent:

- **Request** — one row per user / assistant / tool item, in the order the model saw them, with call/result pairing and a `U`/`A`/`T` role chip. Expand a row for the full payload and result; long-press one to jump back to the message it came from
- **Context usage** — a stacked bar of the model's context window: system prompt, system tools, messages, and free space, each with its token count and share, over turn count, elapsed time and completion tokens

### Watch UI

- **Round-screen native** — official Wear Compose Material 3, with curved-list item scaling at the bezel
- **Pull-up drawer** — a drag handle hovers 9dp above the screen bottom; pull up for the input bar, pull further for Settings
- **Curved or flat lists** — switchable, plus round-fit width and LaTeX width-fit options

### Platform

- **Endpoint profiles** — multiple endpoints, each with its own API type, key and model; model list fetched from the provider
- **System prompts** — a master switch plus a list of named prompts you can create, edit and delete, sent in the protocol-appropriate slot
- **Context window** — per-endpoint size, chosen from common presets, driving the context bar
- **Session log** — append-only one JSON object per line (JSONL); a killed process replays the file, nothing lives only in memory
- **Foreground service** — one turn runs in a foreground service with a notification Stop action; stopping persists partial output
- **i18n** — Chinese (default) and English

## Build

Requires JDK 17+, Android SDK 36.

```bash
./gradlew :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

On Windows, use `gradlew.bat` instead of `./gradlew`.

Huawei Watch blocks ADB installation through its package installer. On those watches only, temporarily disable the installer, install the APK, then enable the installer again. Restore the installer even if installation fails.

```bash
adb shell pm disable-user --user 0 com.android.packageinstaller
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell pm enable --user 0 com.android.packageinstaller
```

### Release signing

`assembleRelease` uses the release keystore when it is configured, and falls back to the debug
key otherwise (so local builds still work). Configure it locally with `keystore.properties`
(gitignored; copy `keystore.properties.example`):

```properties
storeFile=keystore/wearagent.jks
storePassword=...
keyAlias=wearagent
keyPassword=...
```

CI passes the same values as environment variables (`SIGNING_STORE_FILE`,
`SIGNING_STORE_PASSWORD`, `SIGNING_KEY_ALIAS`, `SIGNING_KEY_PASSWORD`), populated from the
`KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD` repository secrets. The
release workflow refuses to publish when the keystore is missing, and verifies the resulting APK
is not debug-signed.

Keep the keystore backed up: losing it means you can no longer update an installed release under
the same package name.

## Setup

1. Open the app → pull up the drawer → Settings
2. **Model → Endpoints → New endpoint**
3. Fill endpoint URL (e.g. `https://api.example.com`) and API key
4. Fetch model list → pick a model (or type one manually)
5. **Model → Context window** → pick the model's context window
6. Optionally **Model → Tools** to enable web search / fetch, and **Model → System prompt** to set one
7. Pull up → type → send

## License

This program is free software: you can redistribute it and/or modify it under the terms of the GNU Affero General Public License as published by the Free Software Foundation, either version 3 of the License, or (at your option) any later version. See [LICENSE](LICENSE).
