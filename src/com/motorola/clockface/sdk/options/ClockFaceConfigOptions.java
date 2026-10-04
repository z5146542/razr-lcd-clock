package com.motorola.clockface.sdk.options;

import android.graphics.drawable.Icon;
import android.os.Parcel;
import android.os.Parcelable;

/**
 * Wire-compatible subset of Motorola's ClockFaceConfigOptions. The host unparcels the
 * options bundle with its own class loader, so the nested class names
 * (ClockFaceConfigOptions$DynamicColorConfigOptions, ...$ColorGroup) and the field
 * order are what matter here.
 */
public abstract class ClockFaceConfigOptions implements Parcelable {
    public final String uniqueId;
    public boolean supportCustomizationColor;

    protected ClockFaceConfigOptions(String uniqueId) {
        this.uniqueId = uniqueId;
    }

    protected ClockFaceConfigOptions(Parcel in) {
        uniqueId = in.readString();
        in.readParcelable(Icon.class.getClassLoader(), Icon.class);
        in.readString();
        supportCustomizationColor = in.readByte() != 0;
    }

    @Override
    public void writeToParcel(Parcel out, int flags) {
        out.writeString(uniqueId);
        out.writeParcelable(null, flags); // icon
        out.writeString(null); // description
        out.writeByte((byte) (supportCustomizationColor ? 1 : 0));
    }

    @Override
    public int describeContents() {
        return 0;
    }

    /** One preset swatch; four corners so the picker can draw split swatches. */
    public static class ColorGroup implements Parcelable {
        private final int leftTop, leftBottom, rightTop, rightBottom;

        public ColorGroup(int argb) {
            leftTop = leftBottom = rightTop = rightBottom = argb;
        }

        protected ColorGroup(Parcel in) {
            in.readByte();
            leftTop = in.readInt();
            leftBottom = in.readInt();
            rightTop = in.readInt();
            rightBottom = in.readInt();
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            out.writeByte((byte) 0); // androidFwColorRes: these are ARGB values, not resource ids
            out.writeInt(leftTop);
            out.writeInt(leftBottom);
            out.writeInt(rightTop);
            out.writeInt(rightBottom);
        }

        @Override
        public int describeContents() {
            return 0;
        }

        public static final Creator<ColorGroup> CREATOR = new Creator<>() {
            @Override
            public ColorGroup createFromParcel(Parcel in) {
                return new ColorGroup(in);
            }

            @Override
            public ColorGroup[] newArray(int size) {
                return new ColorGroup[size];
            }
        };
    }

    /**
     * Preset colours, shown as swatches in the clock face picker. Motorola's own builder
     * caps this at four, but the host's parcel reader and swatch row take any number.
     */
    public static class DynamicColorConfigOptions extends ClockFaceConfigOptions {
        public final int defaultIndex;
        public final ColorGroup[] colors;

        public DynamicColorConfigOptions(String uniqueId, ColorGroup... colors) {
            super(uniqueId);
            if (colors.length < 1) {
                throw new IllegalArgumentException("need at least one color group");
            }
            this.defaultIndex = 0;
            this.colors = colors;
        }

        protected DynamicColorConfigOptions(Parcel in) {
            super(in);
            defaultIndex = in.readInt();
            colors = in.createTypedArray(ColorGroup.CREATOR);
        }

        @Override
        public void writeToParcel(Parcel out, int flags) {
            super.writeToParcel(out, flags);
            out.writeInt(defaultIndex);
            out.writeTypedArray(colors, flags);
        }

        public static final Creator<DynamicColorConfigOptions> CREATOR = new Creator<>() {
            @Override
            public DynamicColorConfigOptions createFromParcel(Parcel in) {
                return new DynamicColorConfigOptions(in);
            }

            @Override
            public DynamicColorConfigOptions[] newArray(int size) {
                return new DynamicColorConfigOptions[size];
            }
        };
    }
}
