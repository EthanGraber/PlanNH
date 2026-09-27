package com.sbancuz.plannh.gui;

import net.minecraft.util.StatCollector;

/** GT's 16 bus/hatch dye colors (gregtech.api.enums.Dyes), in the order a multiblock checks them. */
public final class DyeColors {

    private static final int[] RGB = { 0x202020, 0xff0000, 0x00ff00, 0x604000, 0x0020ff, 0x800080, 0x00ffff, 0xc0c0c0,
        0x808080, 0xffc0c0, 0x80ff80, 0xffff00, 0x6080ff, 0xff00ff, 0xff8000, 0xffffff };
    /** Vanilla's localized dye names, same order. */
    private static final String[] KEYS = { "black", "red", "green", "brown", "blue", "purple", "cyan", "silver", "gray",
        "pink", "lime", "yellow", "lightBlue", "magenta", "orange", "white" };

    private DyeColors() {}

    /** Opaque ARGB for dye {@code dye} (0 = black). */
    public static int argb(final int dye) {
        return 0xFF000000 | RGB[Math.floorMod(dye, RGB.length)];
    }

    public static String name(final int dye) {
        return StatCollector.translateToLocal("item.fireworksCharge." + KEYS[Math.floorMod(dye, KEYS.length)]);
    }
}
