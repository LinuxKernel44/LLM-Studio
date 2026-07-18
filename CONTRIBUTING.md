# Contributing

Thanks for your interest in LLM Studio! This is a personal project, but issues
and pull requests are welcome.

## Reporting bugs / requesting features

Open an issue using one of the templates. For bugs, the more detail about your
device, Android version, the theme/voice/TTS settings in use, and your
llama.cpp server setup, the better.

## Development setup

1. Clone the repo and open it in a recent Android Studio.
2. Android Studio will populate `local.properties` with your SDK path on first
   sync (this file is git-ignored and must never be committed).
3. Build and run:
   ```
   ./gradlew assembleDebug
   ```

The project targets `compileSdk 37` / `minSdk 27` and uses the Gradle
toolchain pinned to JDK 21.

## Code style

- Java for the app's core (MVVM: `AndroidViewModel` + `LiveData`); Kotlin only
  for the Compose-based Liquid Glass screens. Please keep business logic in the
  shared `ChatViewModel`/repositories rather than duplicating it per UI stack.
- 4-space indentation; see [`.editorconfig`](.editorconfig).
- Match the surrounding code's naming and comment style. Comments should explain
  *why* something non-obvious is done, not restate the code.
- The app's own UI text stays in **English (US)**. Only speech recognition and
  text-to-speech are localized (English / French).

## Before opening a pull request

- Make sure `./gradlew assembleDebug` succeeds (CI runs this on every PR).
- Never commit `local.properties`, signing keystores, or any secret - see the
  entries already listed in [`.gitignore`](.gitignore).
- Do not bundle model weights or other large binaries; the Kokoro voice model
  is downloaded at runtime, not committed.

## License of contributions

This project is licensed under [CC BY-NC 4.0](LICENSE). By submitting a
contribution, you agree that it is licensed under the same terms. Third-party
components keep their own (permissive) licenses - see
[THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md).
