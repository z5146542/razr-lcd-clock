package com.motorola.clockface.sdk.options;

import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;

/** Wire-compatible with Motorola's class: how the host frames the clock face. */
public class ClockFaceTemplateAttributesDefinition implements Parcelable {
    public static final int STYLE_LOCKED_AOD = 1;
    public static final int STYLE_LOCKED = 2;
    public static final int STYLE_UNLOCK = 3;

    public final int style;
    public final boolean showStatusBar;
    public final boolean lightStatusBar;
    public final boolean showNavBar;
    public final boolean lightNavBar;

    public ClockFaceTemplateAttributesDefinition(int style, boolean showStatusBar, boolean lightStatusBar,
            boolean showNavBar, boolean lightNavBar) {
        this.style = style;
        this.showStatusBar = showStatusBar;
        this.lightStatusBar = lightStatusBar;
        this.showNavBar = showNavBar;
        this.lightNavBar = lightNavBar;
    }

    protected ClockFaceTemplateAttributesDefinition(Parcel in) {
        style = in.readInt();
        showStatusBar = in.readByte() != 0;
        lightStatusBar = in.readByte() != 0;
        showNavBar = in.readByte() != 0;
        lightNavBar = in.readByte() != 0;
        in.readParcelable(Icon.class.getClassLoader(), Icon.class);
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeInt(style);
        out.writeByte((byte) (showStatusBar ? 1 : 0));
        out.writeByte((byte) (lightStatusBar ? 1 : 0));
        out.writeByte((byte) (showNavBar ? 1 : 0));
        out.writeByte((byte) (lightNavBar ? 1 : 0));
        out.writeParcelable(null, flags); // background
    }

    @Override
    public int describeContents() {
        return 0;
    }

    public static final Creator<ClockFaceTemplateAttributesDefinition> CREATOR = new Creator<>() {
        @Override
        public ClockFaceTemplateAttributesDefinition createFromParcel(Parcel in) {
            return new ClockFaceTemplateAttributesDefinition(in);
        }

        @Override
        public ClockFaceTemplateAttributesDefinition[] newArray(int size) {
            return new ClockFaceTemplateAttributesDefinition[size];
        }
    };
}
