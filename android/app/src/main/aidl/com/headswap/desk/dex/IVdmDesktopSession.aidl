package com.headswap.desk.dex;
import android.view.Surface;
/** One producer generation. Native resources remain in the shell owner. */
interface IVdmDesktopSession {
    int createDisplay(in Surface surface, String name, int width, int height, int density, int flags);
    void resizeDisplay(int displayId, int width, int height, int density);
    void releaseDisplay(int displayId);
    int displayGroup(int displayId);
    boolean ownsDisplay(int displayId);
    /** Empty death-only Binder: safe to give component clients without control methods. */
    IBinder getLifetime();
    void close();
}
