package com.headswap.desk.embed;

import android.view.Surface;

/** Signature-protected companion API. Handles never identify arbitrary Android displays. */
interface IEmbeddedDisplayGateway {
    int version() = 0;
    int create(in Surface surface, IBinder ownerToken, int parentDisplayId, int width, int height, int density) = 1;
    void resize(int handle, int width, int height, int density) = 2;
    boolean launch(int handle, String packageName) = 3;
    void touch(int handle, int action, float x, float y) = 4;
    void back(int handle) = 5;
    /** Returns after the shell producer has released the Surface. */
    void release(int handle) = 6;
    /** -1 unknown, 0 absent, 1 present, 2 top, scoped to the handle's selected package. */
    int taskState(int handle, String packageName) = 7;
    /** Optional bitmask. Bit 0: bounded child buffers up to 2x size and density.
     * Bit 3: base geometry up to 1920x1080 / 96..640 DPI; 2x child up to 3840x2160 / 1280 DPI. */
    int capabilities() = 8;
    /** Capability bit 1: up to two contacts in acknowledged child buffer coordinates. */
    void touchPoints(int handle, int action, int actionIndex, in int[] pointerIds, in float[] xs, in float[] ys) = 9;
    /** Capability bit 2: empty Binder whose death proves this child producer process exited. */
    IBinder producerLifetime(int handle) = 10;
}
