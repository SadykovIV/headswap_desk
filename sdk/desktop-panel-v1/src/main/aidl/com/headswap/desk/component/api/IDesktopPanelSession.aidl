package com.headswap.desk.component.api;

/** Local development API v1. True means the host accepted a request for validation on its active panel. */
interface IDesktopPanelSession {
    boolean requestLaunch(String packageName, String activityName) = 0;
    boolean requestCatalog() = 1;
}
