package com.motorola.clockface.sdk.options.value;

import android.os.Parcel;
import android.os.Parcelable;

/** Wire-compatible with Motorola's class: index of the chosen preset colour. */
public class IndexedRuntimeValue implements Parcelable {
    public final int index;

    public IndexedRuntimeValue(int index) {
        this.index = index;
    }

    protected IndexedRuntimeValue(Parcel in) {
        index = in.readInt();
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(index);
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<IndexedRuntimeValue> CREATOR = new Creator<>() {
        @Override
        public IndexedRuntimeValue createFromParcel(Parcel in) {
            return new IndexedRuntimeValue(in);
        }

        @Override
        public IndexedRuntimeValue[] newArray(int size) {
            return new IndexedRuntimeValue[size];
        }
    };
}
