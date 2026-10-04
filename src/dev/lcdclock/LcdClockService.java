package dev.lcdclock;

import android.app.Service;
import android.content.Intent;
import android.os.Binder;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.RemoteException;
import android.util.Log;

import com.motorola.clockface.sdk.session.ClockFaceSessionId;

/**
 * IClockFaceService. The host connects, then announces each clock face it is about to
 * show; we answer by asking for a session, which makes the host bind LcdSessionService.
 */
public class LcdClockService extends Service {
    static final String ACTION = "com.motorola.service.action.CLOCK_FACE_SERVICE";

    private final Handler main = new Handler(Looper.getMainLooper());
    private IBinder controller;

    private final Binder binder = new Binder() {
        {
            attachInterface(null, Binders.CLOCK_FACE_SERVICE);
        }

        @Override
        protected boolean onTransact(int code, Parcel data, Parcel reply, int flags) throws RemoteException {
            switch (code) {
                case 1: { // connected(IClockFaceServiceController)
                    data.enforceInterface(Binders.CLOCK_FACE_SERVICE);
                    IBinder c = data.readStrongBinder();
                    main.post(() -> controller = c);
                    return true;
                }
                case 2: // disconnected()
                    data.enforceInterface(Binders.CLOCK_FACE_SERVICE);
                    main.post(() -> controller = null);
                    return true;
                case 3: { // clockFaceStarted(ClockFaceStartedEvent{sessionId})
                    data.enforceInterface(Binders.CLOCK_FACE_SERVICE);
                    ClockFaceSessionId id = data.readInt() != 0
                            ? data.readParcelable(ClockFaceSessionId.class.getClassLoader(), ClockFaceSessionId.class)
                            : null;
                    Log.d(Binders.TAG, "clockFaceStarted " + id);
                    main.post(() -> Binders.createClockFaceSession(controller, id));
                    return true;
                }
                default:
                    return super.onTransact(code, data, reply, flags);
            }
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return ACTION.equals(intent.getAction()) ? binder : null;
    }
}
