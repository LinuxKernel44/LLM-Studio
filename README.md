# LLM Studio

[![Build](https://github.com/LinuxKernel44/LLM-Studio/actions/workflows/build.yml/badge.svg)](https://github.com/LinuxKernel44/LLM-Studio/actions/workflows/build.yml)
[![License: CC BY-NC 4.0](https://img.shields.io/badge/License-CC%20BY--NC%204.0-lightgrey.svg)](https://creativecommons.org/licenses/by-nc/4.0/)
![Platform](https://img.shields.io/badge/platform-Android-3DDC84.svg)
![minSdk](https://img.shields.io/badge/minSdk-27-blue.svg)

A voice-first Android chat client for a self-hosted [llama.cpp](https://github.com/ggml-org/llama.cpp)
server, reached over your local network (or a VPN tunnel such as WireGuard). Speak to it, hear it
speak back, and keep everything running on hardware you control.

## Features

- **Voice conversations**: on-device speech recognition (guaranteed offline where supported by the
  device) and text-to-speech, in push-to-talk or hands-free continuous-listening mode.
- **Streaming replies** over an OpenAI-compatible `/v1/chat/completions` endpoint (SSE), spoken
  sentence-by-sentence as the reply streams in rather than waiting for the full response.
- **Multi-conversation history** with a Claude-style side drawer: rename, delete, or start a new
  conversation; old conversations are kept even when a fresh one starts on launch.
- **Server profiles**: multiple backend URL/model/system-prompt configurations, switchable
  independently of conversation history.
- **Three themes**: Material You (dynamic color), a fixed AMOLED black/purple theme, and a
  "Liquid Glass" theme (real-time blur/refraction via
  [AndroidLiquidGlass](https://github.com/Kyant0/AndroidLiquidGlass)) with a pure-black "Oled Liquid
  Glass" variant.
- **French and English voice support**: independent speech-rate control per language for
  recognition and speech output. The app's own UI text always stays in English.
- **Local neural TTS**: an optional [Kokoro-82M](https://huggingface.co/hexgrad/Kokoro-82M) voice
  model (via [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)), downloadable in-app, running
  fully on-device with a selectable voice - English only for now, with the system TTS engine
  automatically used for French.

## Architecture

- **Java**, MVVM (`AndroidViewModel` + `LiveData`), with **Kotlin** used only for the Compose-based
  Liquid Glass screens (`GlassMainActivity`, `GlassSettingsActivity`) - both reuse the exact same
  `ChatViewModel`/adapters/repositories as the classic View-based screens, so business logic is
  never duplicated between UI stacks.
- **Room** for persistence: conversations, messages, and server profiles.
- **OkHttp** with manual SSE parsing for streaming chat completions (no full Retrofit stack).
- **AndroidX SpeechRecognizer** (on-device where available) for speech-to-text, and both the
  system `TextToSpeech` engine and a local sherpa-onnx/Kokoro engine for speech output, behind a
  common `TtsEngine` interface.

## Requirements

- Android Studio (AGP 9+, built-in Kotlin support - no `org.jetbrains.kotlin.android` plugin needed)
- `compileSdk 37`, `minSdk 27`
- A running llama.cpp server (`llama-server`) exposing an OpenAI-compatible endpoint, reachable
  from the phone's network

## Getting started

1. Clone the repo and open it in Android Studio.
2. Point `local.properties` at your Android SDK (`sdk.dir=...`) - Android Studio will usually do
   this for you on first sync.
3. Build and run on a device or emulator (`minSdk 27`+; on-device speech recognition needs API 33+
   for the strongest offline guarantees).
4. In the app's Settings, set the backend URL to your llama-server instance, e.g.
   `http://<server-ip>:8081/v1/chat/completions`.

## Backend (llama.cpp server)

The app is a client only - it needs an OpenAI-compatible `/v1/chat/completions`
endpoint to talk to. Any [llama.cpp](https://github.com/ggml-org/llama.cpp)
`llama-server` build works. Minimal example:

```
llama-server --model your-model.gguf --host 0.0.0.0 --port 8081 --jinja
```

- `--host 0.0.0.0` makes it reachable from other devices on your network (not
  just `localhost`); the phone then connects to `http://<server-ip>:8081/...`.
- Over the internet, don't expose the port directly - reach your machine
  through a VPN tunnel such as WireGuard and point the app at the tunnel IP.
- The app can list the models a server has loaded (Settings → Test connection),
  so you don't have to type a model name by hand.

## Notes

- **Native ABIs**: the optional Kokoro local-TTS engine ships prebuilt
  `sherpa-onnx` / ONNX Runtime native libraries for `arm64-v8a` and
  `armeabi-v7a` only. The rest of the app runs on any ABI (including x86/x86_64
  emulators), but Kokoro TTS is unavailable there - the system TTS engine is
  used as usual.
- **Cleartext HTTP** is permitted by the network security config so the app can
  reach a plain-HTTP `llama-server` on your LAN/VPN; there's no cloud service
  involved.

## License

This project's own code is licensed under
[CC BY-NC 4.0](LICENSE) (Creative Commons Attribution-NonCommercial 4.0
International): you're free to use, share, and adapt it, but you must credit
LinuxKernel44 and you may not use it for commercial purposes.

A few components are vendored in or downloaded at runtime under their own
(permissive, Apache-2.0/MIT) licenses and keep those terms regardless of the
project's own license - see [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)
for the full list and license texts.
