# Black Captions

A minimal black Android app that shows live captions for whatever is playing on the phone, like videos, podcasts and streams.
It's a no-ads clone of LiveCaptionN. Recognition and translation both run on the phone.

- Tap **Start**, then accept Android's screen-recording prompt. Only the sound is used
- Captions appear in a **floating window** over every app. Drag it by the top bar, resize it from the bottom-right corner,
  **–** shrinks it to a small **CC** bubble (tap the bubble to bring it back), **⏸** pauses and **×** stops.
  The window remembers where you left it
- **Speech language:** pick one from the list. Its model downloads once, usually about 40–50 MB, from
  [Vosk](https://alphacephei.com/vosk/models). About 30 languages are available, including Polish, English, German,
  Spanish, French, Russian, Ukrainian and Chinese. After that it works offline
- **Accurate mode** (Russian for now): download Sber's [GigaAM v3](https://github.com/salute-developers/GigaAM)
  (225 MB, MIT). Live text still appears word by word. When a phrase ends, GigaAM re-reads its audio and replaces the line
  with a much more accurate version that has punctuation and capitals, usually within a second or two. The line is light grey
  until then. Translation runs on the improved text. Runs on the phone through [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx)
- **Translate to:** optional. Uses Google ML Kit on the phone, with about 30 MB per language downloaded on first use. The translation
  shows in white and the original in grey under it
- Text size (S/M/L/XL) and background darkness (40–100%)
- **History:** every finished caption is saved on the phone. Tap a line to copy it, or use **Copy all** or **Clear**
- Pause/Resume and Stop are also in the notification

Some apps block audio capture, often streaming apps with DRM. Phone calls can't be captioned either.
The small Vosk models write lowercase text without punctuation. Accurate mode fixes that where it's available.

It needs the audio recording permission (Android uses it for capturing playback), "Display over other apps", and
screen-recording consent each time it starts. Built with Kotlin, Jetpack Compose, Vosk and ML Kit. It needs Android 10 or newer.

## Building

The Android SDK is not needed locally. Every push to `main` builds a signed APK on GitHub Actions and
publishes it on the **Releases** page. Open that page on your phone and install `BlackCaptions.apk`
(allow "install unknown apps" for your browser).

The signing key is stored in the `KEYSTORE_B64` / `KEYSTORE_PASSWORD` repo secrets. It's the same key as
the other Black apps. Keep a backup of `keystore/release.p12`. It is gitignored.

### Optional local build (Docker)

```sh
docker run --rm -v "$PWD":/project -w /project mingc/android-build-box gradle assembleDebug
```
