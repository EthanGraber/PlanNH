package com.sbancuz.plannh.gui.summary;

import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.screen.RichTooltip;
import com.sbancuz.plannh.data.channels.ChannelSolver;

/** Text and tooltip styling shared by the channel rows, so their tooltips read alike. */
final class ChannelTip {

    static final String KEY = "plannh.summary.channels.";
    private static final int GAP_PX = 3;

    private ChannelTip() {}

    static String tr(final String key, final Object... args) {
        return StatCollector.translateToLocalFormatted(KEY + key, args);
    }

    /** The lang file holds a {@code .one} and a {@code .many} form. */
    static String plural(final String key, final int n) {
        return tr(key + (n == 1 ? ".one" : ".many"), n);
    }

    /** The lang key stem naming a layout. */
    static String layout(final ChannelSolver.Mode mode) {
        return "layout." + switch (mode) {
            case NONE -> "separate";
            case CIRCUIT -> "circuit";
            case COLOR -> "color";
        };
    }

    static String catalysts(final String name) {
        return name.isEmpty() ? tr("circuitless") : name;
    }

    static String parts(final ChannelSolver.Parts p) {
        return tr("parts", p.buses(), p.quad(), p.normal());
    }

    static void title(final RichTooltip t, final String text) {
        t.addLine(
            IKey.str(text)
                .style(EnumChatFormatting.AQUA, EnumChatFormatting.BOLD));
    }

    static void heading(final RichTooltip t, final String text) {
        t.addLine(
            IKey.str(text)
                .style(EnumChatFormatting.WHITE, EnumChatFormatting.UNDERLINE));
    }

    static void body(final RichTooltip t, final String text) {
        t.addLine(
            IKey.str(text)
                .style(EnumChatFormatting.GRAY));
    }

    static void warn(final RichTooltip t, final String text) {
        t.addLine(
            IKey.str(text)
                .style(EnumChatFormatting.GOLD));
    }

    static void item(final RichTooltip t, final String text) {
        t.addLine(
            IKey.comp(
                IKey.str("• ")
                    .style(EnumChatFormatting.DARK_GRAY),
                IKey.str(text)
                    .style(EnumChatFormatting.GRAY)));
    }

    /** A named choice and what it means; the one in effect is marked. */
    static void option(final RichTooltip t, final String name, final String help, final boolean current) {
        t.addLine(
            current ? IKey.str("▶ " + name)
                .style(EnumChatFormatting.GREEN, EnumChatFormatting.BOLD)
                : IKey.str(name)
                    .style(EnumChatFormatting.WHITE));
        t.addLine(
            IKey.str(help)
                .style(EnumChatFormatting.GRAY));
    }

    static void footer(final RichTooltip t, final String text) {
        t.addLine(
            IKey.str(text)
                .style(EnumChatFormatting.DARK_GRAY, EnumChatFormatting.ITALIC));
    }

    static void gap(final RichTooltip t) {
        t.spaceLine(GAP_PX);
    }
}
