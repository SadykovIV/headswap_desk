package com.headswap.desk.component.api;

/** Public manifest and launch-envelope names for a desktop profile Activity. */
public final class ProfileContract {
    private ProfileContract() {}

    public static final String ACTION = "com.headswap.desk.action.DESKTOP_COMPONENT";
    public static final String META_API_MAJOR = "com.headswap.desk.component.API_MAJOR";
    public static final String META_PROFILE = "com.headswap.desk.component.PROFILE";
    public static final String META_MODE_ID = "com.headswap.desk.component.MODE_ID";
    public static final String META_DASHBOARD = "com.headswap.desk.component.DASHBOARD";
    public static final String META_PROFILE_CHROME = "com.headswap.desk.component.PROFILE_CHROME";
    /** Optional HTTPS page where this publisher distributes updates to this exact APK. */
    public static final String META_UPDATE_PAGE = "com.headswap.desk.component.UPDATE_PAGE";
    public static final String PROFILE_ANDROID_DESKTOP = "android-desktop";
    public static final int API_MAJOR = 1;

    public static final String EXTRA_SESSION = "com.headswap.desk.extra.DESKTOP_SESSION";
    public static final String BINDER_KEY = "session";
    public static final String API_KEY = "apiMajor";
    public static final String CAPABILITIES_KEY = "capabilities";

    public static final int CAP_LAUNCH_APP = 1;
    public static final int CAP_OPEN_CATALOG = 1 << 1;
    /** Reserved for future negotiated microphone access. There is no recording API in v1. */
    public static final int CAP_MICROPHONE_INPUT = 1 << 2;
    /** Reserved for a future voice channel. There is no audio transport API in v1. */
    public static final int CAP_VOICE_CHANNEL = 1 << 3;
    /** The user allowed this profile's visible music panel to read and control the active player. */
    public static final int CAP_MEDIA = 1 << 4;
    public static final int MEDIA_PREVIOUS = 1;
    public static final int MEDIA_PLAY_PAUSE = 2;
    public static final int MEDIA_NEXT = 3;
    /** The Activity supplies its own dock under the host's navigation window. */
    public static final int CAP_PROFILE_CHROME = 1 << 5;
    public static final int DASH_BACK = 1;
    public static final int DASH_NAVIGATION = 2;
    public static final int DASH_PHONE_TOGGLE = 3;
}
