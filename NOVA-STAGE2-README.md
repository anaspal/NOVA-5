# NOVA — Stage 2: Phone commands

## Implemented
- Local Arabic intent parsing remains separate from the existing call voice-template feature.
- Flashlight on/off through Android CameraManager (requires runtime CAMERA permission).
- Time response using the device clock.
- Battery percentage and charging state.
- Media volume up/down.
- Alarm creation request through the installed Android alarm-clock app using ACTION_SET_ALARM; actual silent/direct behavior depends on the clock app and Android implementation.
- Arabic spoken feedback through the existing TextToSpeech service.
- NOVA asks for microphone and camera permissions before enabling voice commands.

## Supported alarm format
Say a command containing a 24-hour numeric time, e.g. `نوفا اضبطي المنبه الساعة 5:12`. Arabic-Indic digits are normalized.

## Not included yet
- Opening arbitrary apps and Accessibility navigation (Stage 3).
- Dedicated low-power always-on hotword model. The current speech recognizer restarts short recognition sessions and may use battery.
- Guaranteed offline Arabic speech recognition; availability depends on the installed speech recognition provider and downloaded language pack.

## Build
The project has a GitHub Actions workflow at `.github/workflows/build.yml` that builds a debug APK and uploads it as the `NOVA-APK` artifact. A local APK build was not possible in the current environment because Gradle and Android SDK are unavailable.
