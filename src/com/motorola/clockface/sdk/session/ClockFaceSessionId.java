package com.motorola.clockface.sdk.session;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Wire-compatible with Motorola's class of the same name. The host writes it with
 * writeParcelable(), so the fully-qualified name and field order must match.
 */
public class ClockFaceSessionId implements Parcelable {
    public final String uniqueId;
    public final String templateId;

    public ClockFaceSessionId(String uniqueId, String templateId) {
        this.uniqueId = uniqueId;
        this.templateId = templateId;
    }

    protected ClockFaceSessionId(Parcel in) {
        uniqueId = in.readString();
        templateId = in.readString();
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(uniqueId);
        out.writeString(templateId);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    @Override
    public String toString() {
        return "ClockFaceSessionId{" + uniqueId + ", " + templateId + "}";
    }

    public static final Creator<ClockFaceSessionId> CREATOR = new Creator<>() {
        @Override
        public ClockFaceSessionId createFromParcel(Parcel in) {
            return new ClockFaceSessionId(in);
        }

        @Override
        public ClockFaceSessionId[] newArray(int size) {
            return new ClockFaceSessionId[size];
        }
    };
}
