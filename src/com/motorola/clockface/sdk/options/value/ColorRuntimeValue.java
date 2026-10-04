package com.motorola.clockface.sdk.options.value;

import android.os.Parcel;
import android.os.Parcelable;

/** Wire-compatible with Motorola's class: ARGB picked from the custom colour panel. */
public class ColorRuntimeValue implements Parcelable {
    public final boolean isStaticColor;
    public final int value;

    public ColorRuntimeValue(boolean isStaticColor, int value) {
        this.isStaticColor = isStaticColor;
        this.value = value;
    }

    protected ColorRuntimeValue(Parcel in) {
        isStaticColor = in.readByte() != 0;
        value = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeByte((byte) (isStaticColor ? 1 : 0));
        out.writeInt(value);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ColorRuntimeValue> CREATOR = new Creator<>() {
        @Override
        public ColorRuntimeValue createFromParcel(Parcel in) {
            return new ColorRuntimeValue(in);
        }

        @Override
        public ColorRuntimeValue[] newArray(int size) {
            return new ColorRuntimeValue[size];
        }
    };
}
