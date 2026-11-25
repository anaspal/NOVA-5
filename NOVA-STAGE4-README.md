# NOVA Stage 4 — Arabic Conversation

## Changes
- Expanded local Arabic command phrase matching with common Palestinian colloquial variants.
- Added a 12-second follow-up conversation window after the NOVA wake word or a recognized command.
- During that short window, the user can issue another supported command without repeating “NOVA”.
- Existing command executor, call handling, notification listener, and app navigation implementation are retained.

## Important limitations
- This is rule-based local phrase matching, not a general-purpose Arabic language model.
- Multi-action compound requests are not yet orchestrated as reliable sequential workflows.
- Android security screens, permission prompts, password entry, and some app-specific interfaces still require user interaction.
- Always-on recognition depends on Android background microphone restrictions, device battery management, and the installed speech recognition service.
- APK build and real-device tests are intentionally deferred to the final stage.
