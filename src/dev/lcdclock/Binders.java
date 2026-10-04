package dev.lcdclock;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.os.RemoteException;
import android.util.Log;

import com.motorola.clockface.sdk.session.ClockFaceSessionId;

/**
 * Hand-written binder plumbing for Motorola's clock face plugin interfaces
 * (com.motorola.clockface.sdk.*). Every call in that API is oneway, so there are no
 * replies to read. Descriptors and transaction codes are taken from MotoClockFace.
 */
final class Binders {
    static final String TAG = "LcdClock";

    static final String CLOCK_FACE_SERVICE = "com.motorola.clockface.sdk.session.IClockFaceService";
    static final String CLOCK_FACE_SERVICE_CONTROLLER = "com.motorola.clockface.sdk.session.IClockFaceServiceController";
    static final String CLOCK_FACE_SESSION_SERVICE = "com.motorola.clockface.sdk.session.IClockFaceSessionService";
    static final String CLOCK_FACE_SESSION = "com.motorola.clockface.sdk.session.IClockFaceSession";
    static final String ANDROID_FUTURE = "com.motorola.clockface.sdk.misc.IAndroidFuture";

    private Binders() {
    }

    /** IClockFaceServiceController.createClockFaceSession(ClockFaceSessionId), transaction 1. */
    static void createClockFaceSession(IBinder controller, ClockFaceSessionId id) {
        if (controller == null || id == null) {
            Log.w(TAG, "createClockFaceSession: not connected (" + id + ")");
            return;
        }
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(CLOCK_FACE_SERVICE_CONTROLLER);
            writeNullable(data, id);
            controller.transact(1, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException e) {
            Log.w(TAG, "createClockFaceSession failed", e);
        } finally {
            data.recycle();
        }
    }

    /**
     * Reads an AndroidFuture argument. The host always sends an incomplete future, which
     * on the wire is {done=false, IAndroidFuture binder}; we keep only the binder.
     */
    static IBinder readFutureOrigin(Parcel in) {
        if (in.readInt() == 0 || in.readBoolean()) {
            return null;
        }
        return in.readStrongBinder();
    }

    /** IAndroidFuture.complete(AndroidFuture{done, value=result}), transaction 1. */
    static void completeFuture(IBinder origin, Parcelable result) {
        sendFuture(origin, data -> {
            data.writeBoolean(false); // not exceptional
            data.writeValue(result);
        });
    }

    /** Completes the host's future exceptionally so it gives up instead of timing out. */
    static void failFuture(IBinder origin, Throwable error) {
        sendFuture(origin, data -> {
            data.writeBoolean(true); // exceptional
            data.writeBoolean(true); // throwable present
            data.writeBoolean(false); // not a Parcelable throwable
            data.writeString(error.getClass().getName());
            data.writeString(error.getMessage());
            data.writeString("");
            data.writeBoolean(false); // no cause
        });
    }

    private interface FutureBody {
        void write(Parcel data);
    }

    private static void sendFuture(IBinder origin, FutureBody body) {
        if (origin == null) {
            Log.w(TAG, "No future to complete");
            return;
        }
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(ANDROID_FUTURE);
            data.writeInt(1); // non-null AndroidFuture
            data.writeBoolean(true); // done
            body.write(data);
            origin.transact(1, data, null, IBinder.FLAG_ONEWAY);
        } catch (RemoteException e) {
            Log.w(TAG, "Completing future failed", e);
        } finally {
            data.recycle();
        }
    }

    private static void writeNullable(Parcel out, Parcelable p) {
        if (p == null) {
            out.writeInt(0);
        } else {
            out.writeInt(1);
            p.writeToParcel(out, 0);
        }
    }
}
