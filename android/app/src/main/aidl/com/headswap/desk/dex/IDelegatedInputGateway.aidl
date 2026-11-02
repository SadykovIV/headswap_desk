package com.headswap.desk.dex;

import android.view.InputEvent;

/** Private capability passed only to HeadSwap's non-exported producer service. */
interface IDelegatedInputGateway {
    void registerParent(int displayId, IBinder token, int width, int height);
    void registerChild(int parentId, int displayId, IBinder token, int width, int height);
    void resizeDisplay(int displayId, IBinder token, int width, int height);
    void unregisterDisplay(int displayId, IBinder token);
    void injectInput(int displayId, IBinder token, in InputEvent event, int actionButton);
    int childTaskState(int displayId, IBinder token, String packageName);
    void setPhonePanelOff(int parentId, IBinder token, boolean off);
    void registerFullscreenChild(int parentId, int displayId, IBinder token, int width, int height);
}
