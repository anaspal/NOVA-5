# NOVA – Stage 5: Advanced Hands-Free Phone Control

## Base
This stage continues the supplied CallHelper/NOVA project without rebuilding its architecture.
Application version: 10.5 (versionCode 14).

## Implemented in source
- Accessibility text/description lookup with exact-match-only selection; ambiguous matches are rejected.
- Clicks are performed through `ACTION_CLICK`, with a gesture fallback only for the uniquely identified visible element.
- Voice text entry targets the focused editable field only; password fields are rejected.
- Scroll up/down uses scrollable accessibility nodes first and a screen gesture fallback.
- Global actions: Back, Home, Recents, Notifications shade, Quick Settings.
- Arabic command parser additions for click, typing, scroll, recent apps, notification shade, quick settings, stop/resume, and short multi-step requests.
- Multi-step execution is asynchronous, with a transition delay after navigation/app launch.
- Message sending is separated from ordinary clicks. A click request for a label containing “إرسال/Send” creates a short-lived confirmation request. A separate “أؤكد إرسال الرسالة” command then checks for a non-empty focused editable draft and a unique Send control before attempting the click.
- Pausing commands keeps a wake-only recognition loop active so the user can say “نوفا، استأنفي الاستماع”. This is not a full microphone shutdown; it is a command pause while wake-word recognition remains active.
- Fixed the missing conversation-window fields in `NovaSpeechLoop`.

## Important limitations
- Accessibility only sees what the foreground app exposes. Some apps/custom controls do not expose useful labels or editable nodes.
- Text input requires the intended field to already be focused.
- Message recipient identity cannot be verified reliably across all third-party messaging apps. The user must visually verify the recipient and draft before speaking the separate confirmation phrase.
- Android may deny gestures/global actions on protected/system surfaces. NOVA does not bypass lock screens, passwords, permissions, or security restrictions.
- Speech recognition uses Android's available recognition provider. On-device recognition is selected only when Android reports it is available; otherwise the device provider may use network services despite the offline preference.
- Android/OEM background microphone restrictions and battery policies may stop or limit continuous recognition.

## Required user setup
1. Grant microphone permission.
2. Grant camera permission for the existing flashlight feature.
3. Enable NOVA under Android Accessibility settings.
4. Grant Notification Access if notification reading is needed.
5. On Android 13+, allow notifications for NOVA so its foreground-service status remains visible.
6. Keep NOVA's foreground service enabled while using voice control.

## Validation status
- Parser-only Java compilation and command parsing tests were run locally.
- XML files were parsed successfully.
- A full Android Gradle build and device-level Accessibility/SpeechRecognizer tests were not possible in this environment because neither Gradle/Gradle Wrapper nor Android SDK (`android.jar`) is available here.
- No APK was generated.

## Stage 5.1 safety review follow-up
- Typing now refuses to replace a non-empty focused field. It writes only when the active editable field is empty, preventing accidental loss of an existing draft.
- Saying “click Send/إرسال” no longer creates a generic time-only confirmation. NOVA captures the active app package and exact draft text, and requires the same app and unchanged draft at confirmation time. It also requires one uniquely exposed Send control before preparing confirmation.
- The confirmation context expires after 30 seconds and is cleared after confirmation or a detected context mismatch.
- Scroll target selection now prefers the largest visible, enabled scrollable accessibility node instead of blindly choosing the first scrollable node in the tree.

### Additional validation
- 11 parser assertions passed, covering click, text entry, scroll, recents, notification shade, quick settings, pause/resume, send-confirmation intent, and a two-step command.
- Android XML parsing passed for `AndroidManifest.xml`, `accessibility_service_config.xml`, and `strings.xml`.
- Full Android compilation and device validation remain outstanding because this environment has no Android SDK or Gradle installation.

### Safety boundary
NOVA cannot reliably read and verify the recipient identity in every third-party messaging app. The user must still visually verify the recipient and message before issuing the separate confirmation phrase. The captured package and draft check prevents stale confirmation across app changes or draft edits, but it is not a universal recipient-verification mechanism.
