package com.headswap.desk.component.api;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

/** A temporary connection to the HeadSwap window that launched this Activity. Do not persist or forward it. */
public final class ProfileSession {
    private final IDesktopPanelSession remote;
    private final int capabilities;

    private ProfileSession(IDesktopPanelSession remote, int capabilities) {
        this.remote = remote;
        this.capabilities = capabilities;
    }

    /** Returns null on standalone launch, a malformed or incompatible envelope, or a missing/dead endpoint. */
    @SuppressWarnings("deprecation") // Read the two primitive values with strict type checks.
    public static ProfileSession from(Intent intent) {
        try {
            if (intent == null || !ProfileContract.ACTION.equals(intent.getAction())) return null;
            Bundle envelope = intent.getBundleExtra(ProfileContract.EXTRA_SESSION);
            if (envelope == null) return null;
            envelope.setClassLoader(null); // The envelope uses only framework primitive values and a Binder.
            IBinder binder = envelope.getBinder(ProfileContract.BINDER_KEY);
            if (binder == null || !binder.isBinderAlive() || !IDesktopPanelSession.DESCRIPTOR.equals(binder.getInterfaceDescriptor())) return null;
            return fromWire(envelope.get(ProfileContract.API_KEY), envelope.get(ProfileContract.CAPABILITIES_KEY),
                    IDesktopPanelSession.Stub.asInterface(binder));
        } catch (RemoteException | RuntimeException invalidEnvelope) {
            return null;
        }
    }

    static ProfileSession fromWire(Object major, Object flags, IDesktopPanelSession remote) {
        if (!Integer.valueOf(ProfileContract.API_MAJOR).equals(major) || !(flags instanceof Integer) || remote == null) return null;
        return new ProfileSession(remote, (int) flags);
    }

    public int getApiMajor() { return ProfileContract.API_MAJOR; }

    /** Raw mask advertised by the peer. Use supports() before exposing an operation. */
    public int getCapabilities() { return capabilities; }

    /** Only one implemented SDK capability may be requested. An advertised unknown, reserved or composite bit is insufficient. */
    public boolean supports(int capability) {
        boolean implemented = capability == ProfileContract.CAP_LAUNCH_APP || capability == ProfileContract.CAP_OPEN_CATALOG;
        return implemented && (capabilities & capability) != 0;
    }

    public boolean canLaunchApps() { return supports(ProfileContract.CAP_LAUNCH_APP); }
    public boolean canOpenCatalog() { return supports(ProfileContract.CAP_OPEN_CATALOG); }
    public boolean canUseMicrophone() { return supports(ProfileContract.CAP_MICROPHONE_INPUT); }
    public boolean canUseVoiceChannel() { return supports(ProfileContract.CAP_VOICE_CHANNEL); }

    /** Returns true if HeadSwap accepted the request for validation. The actual launch can still fail. */
    public boolean requestLaunch(ComponentName target) {
        if (!canLaunchApps() || target == null) return false;
        try {
            return remote.requestLaunch(target.getPackageName(), target.getClassName());
        } catch (RemoteException | RuntimeException unavailable) {
            return false;
        }
    }

    /** Ask the host to show its own application catalog. A rejected or expired session returns false. */
    public boolean openCatalog() {
        if (!canOpenCatalog()) return false;
        try {
            return remote.requestCatalog();
        } catch (RemoteException | RuntimeException unavailable) {
            return false;
        }
    }
}
