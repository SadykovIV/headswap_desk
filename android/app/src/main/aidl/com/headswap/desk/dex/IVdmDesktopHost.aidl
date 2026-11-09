package com.headswap.desk.dex;
import com.headswap.desk.dex.IVdmDesktopSession;
/** Private authenticated app-to-shell broker. No arbitrary shell execution. */
interface IVdmDesktopHost {
    int protocol();
    int hostPid();
    IVdmDesktopSession openSession(IBinder producerLifetime, int producerPid);
    boolean ownsDisplay(int displayId);
    void shutdown();
}
