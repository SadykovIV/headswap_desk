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

    @Test public void mediaRequiresItsOwnBitAndOnlyForwardsKnownCommands() {
        int[] calls = {0};
        IDesktopPanelSession music = new IDesktopPanelSession.Default() {
            @Override public boolean sendMediaCommand(int command) {
                calls[0]++;
                return command == ProfileContract.MEDIA_NEXT;
            }
        };
        ProfileSession plain = ProfileSession.fromWire(1, 3, music);
        assertFalse(plain.canUseMedia());
        assertNull(plain.getNowPlaying());
        assertFalse(plain.sendMediaCommand(ProfileContract.MEDIA_NEXT));
        ProfileSession allowed = ProfileSession.fromWire(1, 3 | ProfileContract.CAP_MEDIA, music);
        assertTrue(allowed.canUseMedia());
        assertFalse(allowed.canUseMicrophone());
        assertFalse(allowed.canUseVoiceChannel());
        assertFalse(allowed.sendMediaCommand(87));
        assertEquals(0, calls[0]);
        assertTrue(allowed.sendMediaCommand(ProfileContract.MEDIA_NEXT));
        assertEquals(1, calls[0]);
        assertNull(allowed.getNowPlaying());
    }

    @Test public void metadataIsBoundedAndDoesNotCarryHostObjects() {
        assertNull(ProfileNowPlaying.fromFields("", "Artist", true, null));
        assertNull(ProfileNowPlaying.fromFields("Track", "Artist", "yes", null));
        ProfileNowPlaying track = ProfileNowPlaying.fromFields("  Y\u202E\nZ", "A".repeat(300), false, "content://secret");
        assertEquals("YZ", track.getTitle());
        assertEquals(ProfileNowPlaying.MAX_LABEL_LENGTH, track.getArtist().length());
        assertFalse(track.isPlaying());
        assertNull(track.getArtwork());
        String almostFull = "x".repeat(ProfileNowPlaying.MAX_LABEL_LENGTH - 1);
        assertEquals(almostFull, ProfileNowPlaying.fromFields(almostFull + "🎵", "", true, null).getTitle());
    }

    @Test public void chromeDoesNotImplyMediaAndReportsOneCompleteBoundedLayout() {
        int[] calls = {0};
        IDesktopPanelSession dock = new IDesktopPanelSession.Default() {
            @Override public boolean setDashboardLayout(boolean music, boolean right, boolean target, int px) {
                calls[0]++;
                return !music && right && !target && px == 0;
            }
            @Override public boolean sendDashboardAction(int action) { return action == ProfileContract.DASH_BACK; }
        };
        assertFalse(ProfileSession.fromWire(1, ProfileContract.CAP_MEDIA, dock).setDashboardLayout(true, false, true, 39));
        ProfileSession chrome = ProfileSession.fromWire(1, ProfileContract.CAP_PROFILE_CHROME, dock);
        assertTrue(chrome.canUseProfileChrome());
        assertFalse(chrome.canUseMedia());
        assertFalse(chrome.canUseMicrophone());
        assertFalse(chrome.setDashboardLayout(true, false, true, -1));
        assertFalse(chrome.setDashboardLayout(true, false, true, Integer.MAX_VALUE));
        assertEquals(0, calls[0]);
        assertTrue(chrome.setDashboardLayout(false, true, false, 0));
        assertEquals(1, calls[0]);
        assertTrue(chrome.sendDashboardAction(ProfileContract.DASH_BACK));
        assertFalse(chrome.sendDashboardAction(66));
    }
    @Test public void notificationActionRequiresItsOwnNegotiatedCapability() {
        int[] calls = {0};
        IDesktopPanelSession remote = new IDesktopPanelSession.Default() {
            @Override public boolean sendDashboardAction(int action) {
                calls[0]++;
                return action == ProfileContract.DASH_NOTIFICATIONS;
            }
        };
        assertFalse(ProfileSession.fromWire(1, ProfileContract.CAP_PROFILE_CHROME, remote).openNotifications());
        assertEquals(0, calls[0]);
        ProfileSession notifications = ProfileSession.fromWire(1, ProfileContract.CAP_NOTIFICATIONS, remote);
        assertTrue(notifications.openNotifications());
        assertFalse(notifications.canUseMedia());
        assertFalse(notifications.canUseMicrophone());
        assertEquals(1, calls[0]);
    }

}
