package dev.lcdclock;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;
import android.view.Display;
import android.view.SurfaceControlViewHost;
import android.view.WindowManager;

import com.motorola.clockface.sdk.session.ClockFaceSessionId;
import com.motorola.clockface.sdk.session.CreateClockFaceSessionResult;

/**
 * IClockFaceSessionService. The host hands us a display id and size; we build a
 * SurfaceControlViewHost on that display, put the clock view in it, and return its
 * SurfacePackage, which the host embeds in its own SurfaceView.
 */
public class LcdSessionService extends Service {
    static final String ACTION = "com.motorola.service.action.CLOCK_FACE_SESSION_SERVICE";

    private final Handler main = new Handler(Looper.getMainLooper());

    /** ClockFaceSessionViewHostConfiguration, read inline. */
    record ViewConfig(int displayId, int widthPx, int heightPx, Configuration preview) {
        static ViewConfig read(Parcel in) {
            if (in.readInt() == 0) {
                return null;
            }
            return new ViewConfig(in.readInt(), in.readInt(), in.readInt(),
                    in.readParcelable(Configuration.class.getClassLoader(), Configuration.class));
        }
    }

    private final Binder binder = new Binder() {
        {
            attachInterface(null, Binders.CLOCK_FACE_SESSION_SERVICE);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            if (code != 1) { // create(controller, request, config, future)
                return super.onTransact(code, data, reply, flags);
            }
            data.enforceInterface(Binders.CLOCK_FACE_SESSION_SERVICE);
            IBinder sessionController = data.readStrongBinder();
            ClockFaceSessionId id = data.readInt() != 0 // CreateClockFaceSessionRequest{sessionId}
                    ? data.readParcelable(ClockFaceSessionId.class.getClassLoader(), ClockFaceSessionId.class)
                    : null;
            ViewConfig config = ViewConfig.read(data);
            IBinder future = Binders.readFutureOrigin(data);
            main.post(() -> create(sessionController, id, config, future));
            return true;
        }
    };

    private void create(IBinder sessionController, ClockFaceSessionId id, ViewConfig config, IBinder future) {
        Log.d(Binders.TAG, "create " + id + " " + config);
        try {
            if (id == null || config == null) {
                throw new IllegalArgumentException("Missing session id or view configuration");
            }
            Display display = getSystemService(DisplayManager.class).getDisplay(config.displayId());
            if (display == null) {
                throw new IllegalStateException("No display found for id: " + config.displayId());
            }
            Context context;
            try {
                // Same window context Motorola's own clock faces use.
                context = createWindowContext(display, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null);
            } catch (RuntimeException e) {
                context = createDisplayContext(display);
            }
            if (config.preview() != null) {
                context = context.createConfigurationContext(config.preview());
            }
            SurfaceControlViewHost viewHost = new SurfaceControlViewHost(context, display, new Binder());
            LcdSession session = new LcdSession(context, viewHost, id, sessionController,
                    config.widthPx(), config.heightPx(), config.preview() != null);
            Binders.completeFuture(future,
                    new CreateClockFaceSessionResult(session.binder, viewHost.getSurfacePackage()));
        } catch (Throwable t) {
            Log.e(Binders.TAG, "Failed to create session " + id, t);
            Binders.failFuture(future, t);
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return ACTION.equals(intent.getAction()) ? binder : null;
    }
}
