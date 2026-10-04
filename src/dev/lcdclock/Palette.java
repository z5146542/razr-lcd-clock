package dev.lcdclock;

import android.content.Context;

import com.motorola.clockface.sdk.options.value.ColorRuntimeValue;
import com.motorola.clockface.sdk.options.value.IndexedRuntimeValue;

/** Clock colours: our presets, plus mapping for Motorola's custom colour panel. */
final class Palette {
    /**
     * Red, green, magenta, light blue: the face's swatches in the picker, which has room for
     * four. The first is the default. The picker remembers a choice by its position, so
     * replacing a colour changes it for whoever had that one.
     */
    static final int[] PRESETS = {0xFFFF0000, 0xFF00FF00, 0xFFFF00FF, 0xFF5BC8FF};

    /**
     * Motorola's custom colour panel stores a seed colour per swatch but shows a lighter
     * variant; use what the swatch looks like. The first swatch is "system colour".
     * (MotoClockFace ColorPanelContainerViewExternal mInputColorArray/mDisplayColorArray.)
     */
    private static final int SYSTEM_SEED = 0xFF7ED0FF;
    private static final int[] PANEL_SEEDS = {0xFF165C7D, 0xFFFF8A31, 0xFFBE3554, 0xFFB7EDDF, 0xFF654E78, 0xFF704B00, 0xFF8CD88D};
    private static final int[] PANEL_SHOWN = {0xFF7ED0FF, 0xFFFFB685, 0xFFFFB1BC, 0xFF58DBC3, 0xFFE0B6FF, 0xFFFDBA4B, 0xFF00E555};

    private Palette() {
    }

    static int resolve(Context context, IndexedRuntimeValue preset, ColorRuntimeValue custom) {
        if (custom != null) {
            return panelColor(context, custom.value);
        }
        if (preset != null && preset.index >= 0 && preset.index < PRESETS.length) {
            return PRESETS[preset.index];
        }
        return PRESETS[0];
    }

    private static int panelColor(Context context, int seed) {
        if (seed == SYSTEM_SEED) {
            return context.getColor(android.R.color.system_accent1_200);
        }
        for (int i = 0; i < PANEL_SEEDS.length; i++) {
            if (PANEL_SEEDS[i] == seed) {
                return PANEL_SHOWN[i];
            }
        }
        return seed | 0xFF000000;
    }
}
