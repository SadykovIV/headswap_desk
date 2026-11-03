package com.headswap.desk.component.api;

import org.junit.Test;

import static org.junit.Assert.*;

public class ProfileSessionTest {
    private final IDesktopPanelSession endpoint = new IDesktopPanelSession.Default();

    @Test public void missingOrIncompatibleConstituentsCannotCreateASession() {
        assertNull(ProfileSession.from(null));
        assertNull(ProfileSession.fromWire(null, 3, endpoint));
        assertNull(ProfileSession.fromWire("1", 3, endpoint));
        assertNull(ProfileSession.fromWire(2, 3, endpoint));
        assertNull(ProfileSession.fromWire(1, null, endpoint));
        assertNull(ProfileSession.fromWire(1, "3", endpoint));
        assertNull(ProfileSession.fromWire(1, 3, null));
    }

    @Test public void onlyImplementedSingleBitsCanBeUsedEvenWhenPeerAdvertisesMore() {
        int mask = 3 | ProfileContract.CAP_MICROPHONE_INPUT | ProfileContract.CAP_VOICE_CHANNEL | 0x100;
        ProfileSession session = ProfileSession.fromWire(1, mask, endpoint);
        assertNotNull(session);
        assertEquals(1, session.getApiMajor());
        assertEquals(mask, session.getCapabilities());
        assertTrue(session.canLaunchApps());
        assertTrue(session.canOpenCatalog());
        assertFalse(session.supports(3));
        assertFalse(session.supports(0));
        assertFalse(session.supports(0x100));
        assertFalse(session.canUseMicrophone());
        assertFalse(session.canUseVoiceChannel());
        assertFalse(session.requestLaunch(null));
    }

    @Test public void unsupportedOrFailedRequestsReturnFalse() {
        IDesktopPanelSession catalog = new IDesktopPanelSession.Default() {
            @Override public boolean requestCatalog() { return true; }
        };
        assertTrue(ProfileSession.fromWire(1, ProfileContract.CAP_OPEN_CATALOG, catalog).openCatalog());
        assertFalse(ProfileSession.fromWire(1, ProfileContract.CAP_LAUNCH_APP, catalog).openCatalog());
        assertFalse(ProfileSession.fromWire(1, 0, catalog).openCatalog());
        IDesktopPanelSession closed = new IDesktopPanelSession.Default() {
            @Override public boolean requestCatalog() { throw new SecurityException("Revoked"); }
        };
        assertFalse(ProfileSession.fromWire(1, ProfileContract.CAP_OPEN_CATALOG, closed).openCatalog());
    }
}
