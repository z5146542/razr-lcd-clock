package com.motorola.clockface.sdk.session;

import android.os.IBinder;
import android.os.Parcel;
import android.os.Parcelable;
import android.view.SurfaceControlViewHost;

/**
 * Sent back to the host inside an AndroidFuture via Parcel.writeValue(), so the host
 * resolves it by this exact class name.
 */
public final class CreateClockFaceSessionResult implements Parcelable {
    private final IBinder session;
    private final SurfaceControlViewHost.SurfacePackage surfacePackage;

    public CreateClockFaceSessionResult(IBinder session, SurfaceControlViewHost.SurfacePackage surfacePackage) {
        this.session = session;
        this.surfacePackage = surfacePackage;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeStrongBinder(session);
        out.writeParcelable(surfacePackage, flags);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<CreateClockFaceSessionResult> CREATOR = new Creator<>() {
        @Override
        public CreateClockFaceSessionResult createFromParcel(Parcel in) {
            return new CreateClockFaceSessionResult(in.readStrongBinder(),
                    in.readParcelable(SurfaceControlViewHost.SurfacePackage.class.getClassLoader(),
                            SurfaceControlViewHost.SurfacePackage.class));
        }

        @Override
        public CreateClockFaceSessionResult[] newArray(int size) {
            return new CreateClockFaceSessionResult[size];
        }
    };
}
