package com.headswap.desk.qa;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Standalone test-APK process. Uses the published v1 wire contract, without loading host code. */
public final class DesktopPanelFixture extends Activity {
    private IBinder session;
    private final BroadcastReceiver command = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent request) {
            String action = request.getStringExtra("command");
            Parcel data = Parcel.obtain(); Parcel reply = Parcel.obtain();
            String result;
            try {
                data.writeInterfaceToken("com.headswap.desk.component.api.IDesktopPanelSession");
                int method;
                if ("launch".equals(action)) {
                    method = 0;
                    data.writeString(request.getStringExtra("package"));
                    data.writeString(request.getStringExtra("activity"));
                } else if ("phone".equals(action)) {
                    method = 5; data.writeInt(3); // sendDashboardAction(DASH_PHONE_TOGGLE)
                } else if ("layout".equals(action)) {
                    method = 4; data.writeInt(1); data.writeInt(0); data.writeInt(0); data.writeInt(80);
                } else throw new IllegalArgumentException("Unknown fixture command");
                if (session == null || !session.transact(IBinder.FIRST_CALL_TRANSACTION + method, data, reply, 0))
                    throw new IllegalStateException("Missing panel session");
                reply.readException(); result = action + " accepted=" + (reply.readInt() != 0);
            } catch (Exception error) { result = action + " error=" + error; }
            finally { data.recycle(); reply.recycle(); }
            try { writeReport("panel-command.txt", result); }
            catch (Exception error) { throw new RuntimeException(error); }
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        Bundle envelope = getIntent().getBundleExtra("com.headswap.desk.extra.DESKTOP_SESSION");
        session = envelope == null ? null : envelope.getBinder("session");
        TextView label = new TextView(this);
        label.setText("QA external panel — real Binder caller UID " + android.os.Process.myUid());
        label.setTextSize(24); label.setTextColor(0xffffffff); label.setBackgroundColor(0xff193b64);
        setContentView(label);
        registerReceiver(command, new IntentFilter("com.headswap.desk.test.PANEL_COMMAND"), Context.RECEIVER_EXPORTED);
        try { writeReport("panel-ready.txt", "ready display=" + getDisplay().getDisplayId()); }
        catch (Exception error) { throw new RuntimeException(error); }
    }
    @Override public void onDestroy() { unregisterReceiver(command); super.onDestroy(); }
    private void writeReport(String name, String value) throws java.io.IOException {
        try (FileOutputStream stream = new FileOutputStream(new File(getFilesDir(), name))) {
            stream.write(value.getBytes(StandardCharsets.UTF_8));
        }
    }
}
