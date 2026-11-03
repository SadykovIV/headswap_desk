package com.headswap.desk.component.api;

import android.os.Bundle;

/** Local development API v1. True means the host accepted a request for validation on its active panel. */
interface IDesktopPanelSession {
    boolean requestLaunch(String packageName, String activityName) = 0;
    boolean requestCatalog() = 1;
    Bundle getNowPlaying() = 2;
    boolean sendMediaCommand(int command) = 3;
    boolean setDashboardLayout(boolean musicVisible, boolean musicOnRight, boolean targetVisible, int dockHeightPx) = 4;
    boolean sendDashboardAction(int action) = 5;
}
