package com.example.callhelper.nova;

/** أمر NOVA بعد التحليل اللغوي. */
public final class NovaCommand {
    public enum Type {
        WAKE_ONLY, FLASHLIGHT_ON, FLASHLIGHT_OFF, TIME, BATTERY,
        SET_ALARM, OPEN_APP, READ_NOTIFICATIONS, VOLUME_UP, VOLUME_DOWN,
        BACK, HOME, CLICK_TEXT, TYPE_TEXT, SCROLL_DOWN, SCROLL_UP,
        RECENTS, OPEN_NOTIFICATIONS, QUICK_SETTINGS,
        STOP_LISTENING, RESUME_LISTENING, CONFIRM_SEND, MULTI_STEP,
        CALL_CONTACT, SCREENSHOT, LOCK_SCREEN, OPEN_WIFI, OPEN_BLUETOOTH, HELP, REPEAT_LAST, UNKNOWN
    }
    public final Type type;
    public final String originalText;
    public final String argument;
    public NovaCommand(Type type, String originalText, String argument) {
        this.type=type; this.originalText=originalText==null?"":originalText;
        this.argument=argument==null?"":argument;
    }
}
