package com.headswap.desk.dex;

import org.junit.Test;
import java.nio.charset.StandardCharsets;
import static org.junit.Assert.*;

public class DisplayHelperRendezvousTest {
    private final String token = "00112233445566778899aabbccddeeff";
    private final DisplayHelperRendezvous record = new DisplayHelperRendezvous(10123, 512, 1241, 7, 80900, false, token);

    private ManualAccessPolicy.Identity owner(int uid, long ticks, String main) {
        return ManualAccessPolicy.parseIdentity(512, "Uid:\t" + uid + "\t" + uid + "\t" + uid + "\t" + uid,
                (main + "\u0000").getBytes(StandardCharsets.UTF_8),
                "512 (pid) S" + " 0".repeat(18) + " " + ticks);
    }

    @Test public void onlyTheSameBootAppUserAndProcessMayRecover() {
        assertTrue(record.matchesOwner(10123, owner(10123, 1241, ManualAccessPolicy.PACKAGE), 7));
        assertFalse(record.matchesOwner(10123, owner(10123, 1242, ManualAccessPolicy.PACKAGE), 7));
        assertFalse(record.matchesOwner(10123, owner(10123, 1241, ManualAccessPolicy.PACKAGE + ":service"), 7));
        assertFalse(record.matchesOwner(10123, owner(10124, 1241, ManualAccessPolicy.PACKAGE), 7));
        assertFalse(record.matchesOwner(10123, owner(10123, 1241, ManualAccessPolicy.PACKAGE), 8));
    }

    @Test public void heartbeatBindsPermissionScopeAndProvesFreshSession() {
        DisplayHelperRendezvous decoded = DisplayHelperRendezvous.parse(record.encode());
        assertNotNull(decoded);
        assertFalse(decoded.panel);
        assertTrue(decoded.matchesHeartbeat(512, 80901, record.fingerprint()));
        assertFalse(decoded.matchesHeartbeat(512, 80899, record.fingerprint()));
        assertFalse(decoded.matchesHeartbeat(511, 80901, record.fingerprint()));
        DisplayHelperRendezvous broader = new DisplayHelperRendezvous(10123, 512, 1241, 7, 80900, true, token);
        assertFalse(broader.matchesHeartbeat(512, 80901, record.fingerprint()));
        DisplayHelperRendezvous replacement = new DisplayHelperRendezvous(10123, 512, 1241, 7, 81000, false,
                DisplayHelperRendezvous.newToken());
        assertFalse(replacement.matchesHeartbeat(512, 81001, record.fingerprint()));
        assertFalse(record.fingerprint().contains(token));
    }

    @Test public void rejectIncompleteSpoofedAndMalformedTickets() {
        assertNull(DisplayHelperRendezvous.parse(""));
        assertNull(DisplayHelperRendezvous.parse(record.encode()+" extra"));
        assertNull(DisplayHelperRendezvous.parse(record.encode().replace("ime", "anything")));
        assertNull(DisplayHelperRendezvous.parse(record.encode().replace(token, "secret")));
        assertNull(DisplayHelperRendezvous.parse(record.encode().replace("10123", "010123")));
        assertNull(DisplayHelperRendezvous.parse(record.encode().repeat(8)));
    }
}
