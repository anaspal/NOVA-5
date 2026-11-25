# NOVA — NOVA Stage 1

## Included
- Opt-in NOVA switch in the existing NOVA screen (disabled by default).
- Continuous/restarted Arabic speech-recognition sessions while the NOVA foreground service is active.
- Preference for Android on-device speech recognition when available; otherwise requests offline recognition from the installed recognizer where supported.
- Wake phrase detection for `نوفا` / `NOVA`.
- Local Arabic command normalization and initial intent parsing.
- Pauses NOVA listening during incoming-call ringing and resumes after the phone returns to idle.
- Existing call voice-template capture and call-announcement code remain in place.

## Important limitations
- Stage 1 recognizes the wake phrase and classifies supported command intents. It does **not** execute phone actions yet.
- This implementation uses Android `SpeechRecognizer` in short, repeated sessions. It is not a dedicated low-power acoustic hotword model; battery use and recognition reliability must be tested on the target phone.
- On-device Arabic recognition depends on the installed Android speech service and downloaded language models. `EXTRA_PREFER_OFFLINE` is a preference, not a guarantee. On Android 12+ the app selects the on-device recognizer when Android reports one is available.
- The app requires microphone permission and the existing foreground microphone service to be running.

## Parser smoke test
The pure-Java command parser was compiled and checked with 14 assertions covering wake-word matching and initial Arabic command classes.

## Android build
A full APK build was not run in this environment because neither Gradle nor an Android SDK (`android.jar`) is installed. Build with the repository's existing GitHub Actions workflow or a local Android/Gradle toolchain before installing on a device.
