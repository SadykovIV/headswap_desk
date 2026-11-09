package com.headswap.desk.qa;

import android.app.Activity;
import android.graphics.PixelFormat;
import android.media.ImageReader;
import android.os.Binder;
import android.os.Bundle;
import android.os.IBinder;
import android.os.Parcel;
import android.os.Process;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;

/** Test-only deliberate capability exposure: the real foreign Binder caller must still be rejected. */
public final class FullscreenCallerFixture extends Activity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        String result;
        Bundle extra = getIntent().getBundleExtra("qa-private-service");
        IBinder service = extra == null ? null : extra.getBinder("service");
        try (ImageReader images = ImageReader.newInstance(128,128,PixelFormat.RGBA_8888,2)) {
            Parcel data=Parcel.obtain(), reply=Parcel.obtain();
            try {
                data.writeInterfaceToken("com.headswap.desk.dex.IDexUserService");
                data.writeTypedObject(images.getSurface(),0); data.writeStrongBinder(new Binder());
                data.writeInt(128);data.writeInt(128);data.writeInt(128);
                if (service == null || !service.transact(IBinder.FIRST_CALL_TRANSACTION+19,data,reply,0))
                    throw new IllegalStateException("Private transaction absent");
                reply.readException();
                result="UNEXPECTED_ACCEPTED uid="+Process.myUid()+" display="+reply.readInt();
            } catch (SecurityException expected) { result="DENIED uid="+Process.myUid(); }
            finally { data.recycle();reply.recycle(); }
        } catch (Exception error) { result="OTHER_ERROR "+error; }
        TextView label=new TextView(this);label.setText(result);setContentView(label);
        try (FileOutputStream out=new FileOutputStream(new File(getFilesDir(),"fullscreen-caller.txt"))) {
            out.write(result.getBytes(StandardCharsets.UTF_8));
        } catch (Exception error) { throw new RuntimeException(error); }
    }
}
