# NOVA – Stage 3: Apps, Navigation & Notifications

## Added in this stage
- Open installed launcher apps by spoken app name (Arabic/English label matching), with aliases for WhatsApp, YouTube, Telegram, Instagram, Chrome, Camera and Settings.
- Android global BACK and HOME actions through the existing `InCallAccessibilityService` after the user explicitly enables the service in Android Settings.
- Read the newest active notification aloud only after the user explicitly enables NOVA under Android Notification Access settings and asks NOVA to read notifications.
- Expanded Arabic/Palestinian colloquial command phrases for app launch, navigation and notification reading.
- Added a setup shortcut for Android Notification Listener settings.
- Version updated to 10.4 (versionCode 13). Application ID remains unchanged.

## Example commands
- "نوفا افتحي واتساب" / "نوفا شغلي يوتيوب"
- "نوفا ارجعي للخلف"
- "نوفا روحي عالرئيسية"
- "نوفا اقري لي الإشعارات" / "نوفا شو عندي إشعارات؟"

## Required user-granted settings
1. Enable NOVA's Accessibility service for BACK/HOME navigation.
2. Enable NOVA under Notification Access to read notifications.
3. Keep the app's existing microphone permission and NOVA foreground service enabled for voice commands.

Notification content is not continuously spoken or uploaded. NOVA reads one newest readable active notification only after a direct voice request. Some apps hide notification text or Android may restrict access; those notifications cannot be read.

## Verification
- Pure-Java parser test: 7/7 command parsing cases passed.
- AndroidManifest, accessibility config and strings XML parse checks passed.
- Full APK build/device testing intentionally deferred per project decision: APK build is the final step after feature stages.

## Known limitations
- App launching depends on Android exposing the target app's launcher activity and its visible label/alias.
- Accessibility navigation works only while the user-enabled service is connected.
- Notification reading requires Notification Access and only reads notification text Android exposes.
- Palestinian dialect coverage is rule-based at this stage, not a general conversational language model.
