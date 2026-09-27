package com.sbancuz.plannh.gui.summary;

import net.minecraft.client.Minecraft;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.StatCollector;

import com.cleanroommc.modularui.api.drawable.IKey;
import com.cleanroommc.modularui.drawable.Rectangle;
import com.cleanroommc.modularui.screen.RichTooltip;
import com.cleanroommc.modularui.utils.Alignment;
import com.cleanroommc.modularui.value.BoolValue;
import com.cleanroommc.modularui.value.EnumValue;
import com.cleanroommc.modularui.widget.Widget;
import com.cleanroommc.modularui.widgets.CycleButtonWidget;
import com.cleanroommc.modularui.widgets.TextWidget;
import com.sbancuz.plannh.data.channels.ChannelReport;
import com.sbancuz.plannh.data.channels.ChannelSolver;
import com.sbancuz.plannh.data.flowchart.Plan;
import com.sbancuz.plannh.data.flowchart.Summary;
import com.sbancuz.plannh.data.flowchart.Summary.Line;
import com.sbancuz.plannh.gui.DyeColors;
import com.sbancuz.plannh.gui.PlannhColors;

/**
 * The CHANNELS section's widgets: the controls row under its header, then per machine type a heading,
 * one row per channel and a row per kind of finding, each with the detail in its tooltip.
 */
final class ChannelRows {

    private static final String KEY = "plannh.summary.channels.";
    private static final int CHIP = 7;
    private static final int GAP = 3;
    private static final int BUTTON_PAD = 8;

    private ChannelRows() {}

    // region controls

    /** On/off, the priority mode and the amount rule; each re-derives the rows. */
    static Widget<?> controls(final Summary data) {
        final Runnable refresh = () -> data.recompute(Plan.getActiveGraph());

        final CycleButtonWidget enabled = new CycleButtonWidget().stateCount(2)
            .size(buttonWidth(KEY + "enabled.on", KEY + "enabled.off"), SummaryHeader.HEADER_H)
            .stateOverlay(false, IKey.lang(KEY + "enabled.off"))
            .stateOverlay(true, IKey.lang(KEY + "enabled.on"))
            .value(new BoolValue.Dynamic(data::isChannelsEnabled, val -> {
                data.setChannelsEnabled(val);
                refresh.run();
            }));
        for (int state = 0; state < 2; state++) enabled.tooltip(state, ChannelRows::enabledTooltip);

        final String[] modeKeys = new String[ChannelSolver.Mode.values().length];
        for (final ChannelSolver.Mode m : ChannelSolver.Mode.values()) modeKeys[m.ordinal()] = modeKey(m) + ".button";
        final CycleButtonWidget mode = new CycleButtonWidget().size(buttonWidth(modeKeys), SummaryHeader.HEADER_H)
            .value(new EnumValue.Dynamic<>(ChannelSolver.Mode.class, data::getChannelMode, val -> {
                data.setChannelMode(val);
                refresh.run();
            }))
            .setEnabledIf(_ -> data.isChannelsEnabled());
        for (final ChannelSolver.Mode m : ChannelSolver.Mode.values()) {
            mode.stateOverlay(m, IKey.lang(modeKey(m) + ".button"));
            // A cycle button shows its current state's tooltip, so each state describes every mode
            mode.tooltip(m.ordinal(), t -> modeTooltip(t, m));
        }

        final CycleButtonWidget amounts = new CycleButtonWidget().stateCount(2)
            .size(buttonWidth(KEY + "amounts.any.button", KEY + "amounts.one.button"), SummaryHeader.HEADER_H)
            .stateOverlay(false, IKey.lang(KEY + "amounts.any.button"))
            .stateOverlay(true, IKey.lang(KEY + "amounts.one.button"))
            .tooltip(0, t -> amountsTooltip(t, false))
            .tooltip(1, t -> amountsTooltip(t, true))
            .value(new BoolValue.Dynamic(data::isChannelAmounts, val -> {
                data.setChannelAmounts(val);
                refresh.run();
            }))
            .setEnabledIf(_ -> data.isChannelsEnabled());

        return SummaryFlow.row()
            .fullWidth()
            .coverChildrenHeight()
            .paddingLeft(SummaryBody.TEXT_X)
            .childPadding(GAP)
            .collapseDisabledChild()
            .child(enabled)
            .child(mode)
            .child(amounts);
    }

    private static int buttonWidth(final String... keys) {
        int w = 0;
        for (final String k : keys) {
            w = Math.max(w, Minecraft.getMinecraft().fontRenderer.getStringWidth(StatCollector.translateToLocal(k)));
        }
        return Math.max(SummaryHeader.HEADER_H, w + BUTTON_PAD);
    }

    private static void enabledTooltip(final RichTooltip t) {
        Tip.title(t, tr(KEY + "enabled.title"));
        Tip.body(t, tr(KEY + "enabled.help"));
        Tip.footer(t, tr("plannh.summary.mode.switch_hint"));
    }

    private static void modeTooltip(final RichTooltip t, final ChannelSolver.Mode current) {
        Tip.title(t, tr(KEY + "mode.title"));
        for (final ChannelSolver.Mode m : ChannelSolver.Mode.values()) {
            Tip.gap(t);
            Tip.option(t, tr(modeKey(m) + ".name"), tr(modeKey(m) + ".help"), m == current);
        }
        Tip.gap(t);
        Tip.footer(t, tr(KEY + "order"));
        Tip.footer(t, tr("plannh.summary.mode.switch_hint"));
    }

    private static void amountsTooltip(final RichTooltip t, final boolean one) {
        Tip.title(t, tr(KEY + "amounts.title"));
        Tip.gap(t);
        Tip.option(t, tr(KEY + "amounts.any"), tr(KEY + "amounts.any.help"), !one);
        Tip.gap(t);
        Tip.option(t, tr(KEY + "amounts.one"), tr(KEY + "amounts.one.help"), one);
        Tip.gap(t);
        Tip.footer(t, tr("plannh.summary.mode.switch_hint"));
    }

    // endregion
    // region rows

    static Widget<?> machine(final Line.ChannelMachine m) {
        final String machines = plural("stat.machines", m.machines());
        final String blocks = plural(
            "stat.blocks",
            m.parts()
                .blocks());
        // In color mode every channel is a machine of its own, so its channel count says nothing new
        final String stats = m.mode() == ChannelSolver.Mode.COLOR ? String.join("  ·  ", machines, blocks)
            : String.join("  ·  ", plural("stat.channels", m.channels()), machines, blocks);
        final Widget<?> row = SummaryFlow.col()
            .fullWidth()
            .coverChildrenHeight()
            .paddingLeft(SummaryBody.TEXT_X)
            .paddingTop(GAP)
            .hoverBackground(new Rectangle().color(PlannhColors.SUMMARY_ROW_HOVER.getColor()))
            .child(wrapped(m.machine(), PlannhColors.ACCENT_BLUE.getColor()))
            .child(wrapped(stats, PlannhColors.SUMMARY_TEXT_MUTED.getColor()));
        row.tooltipStatic(t -> {
            Tip.title(t, m.machine());
            Tip.body(t, parts(m.parts()));
            Tip.body(t, tr(KEY + "dedicated", m.dedicated()));
            if (!m.channelsMinimal()) Tip.warn(t, tr(KEY + "budget.channels"));
            else if (!m.blocksMinimal()) Tip.warn(t, tr(KEY + "budget.blocks"));
            Tip.gap(t);
            Tip.option(t, tr(modeKey(m.mode()) + ".name"), tr(modeKey(m.mode()) + ".help"), true);
        });
        return row;
    }

    static Widget<?> group(final Line.ChannelGroup g) {
        final boolean ordered = g.mode() != ChannelSolver.Mode.NONE;
        // A channel in color mode is a whole machine holding several colors, and players read "channel"
        // as one color group, so there it is labeled as the machine it is
        final String title = tr(KEY + (g.mode() == ChannelSolver.Mode.COLOR ? "machine" : "channel"), g.index());
        final Widget<?> out;
        if (g.mode() == ChannelSolver.Mode.COLOR) {
            // A bus per catalyst set, so each gets a line with its own color
            final SummaryFlow col = SummaryFlow.col();
            col.fullWidth()
                .coverChildrenHeight()
                .hoverBackground(new Rectangle().color(PlannhColors.SUMMARY_ROW_HOVER.getColor()));
            col.child(chipRow(-1, title, PlannhColors.TEXT_WHITE.getColor()));
            for (int i = 0; i < g.catalysts()
                .size(); i++) {
                col.child(
                    chipRow(
                        g.dyes()
                            .get(i),
                        DyeColors.name(
                            g.dyes()
                                .get(i))
                            + ": "
                            + catalysts(
                                g.catalysts()
                                    .get(i)),
                        PlannhColors.SUMMARY_TEXT.getColor()).paddingLeft(SummaryBody.TEXT_X * 2));
            }
            out = col;
        } else {
            final StringBuilder sb = new StringBuilder(title).append(": ");
            for (int i = 0; i < g.catalysts()
                .size(); i++) {
                if (i > 0) sb.append(ordered ? " > " : ", ");
                sb.append(
                    catalysts(
                        g.catalysts()
                            .get(i)));
            }
            out = chipRow(
                g.dyes()
                    .getFirst(),
                sb.toString(),
                PlannhColors.SUMMARY_TEXT.getColor())
                    .hoverBackground(new Rectangle().color(PlannhColors.SUMMARY_ROW_HOVER.getColor()));
        }
        out.tooltipStatic(t -> {
            Tip.title(t, title);
            if (g.mode() != ChannelSolver.Mode.COLOR) {
                Tip.body(
                    t,
                    tr(
                        KEY + "color",
                        DyeColors.name(
                            g.dyes()
                                .getFirst())));
            }
            Tip.body(t, parts(g.parts()));
            if (ordered) Tip.body(t, tr(KEY + "order"));
            Tip.gap(t);
            Tip.heading(t, tr(KEY + "recipes"));
            for (final String r : g.recipes()) Tip.item(t, r);
        });
        return out;
    }

    static Widget<?> notes(final Line.ChannelNotes n) {
        final String kind = switch (n.kind()) {
            case CONFLICT -> "conflicts";
            case TOLERATED -> "tolerated";
            case INHERENT -> "inherent";
        };
        final int color = switch (n.kind()) {
            case CONFLICT -> PlannhColors.ACCENT_AMBER.getColor();
            case TOLERATED -> PlannhColors.SUMMARY_TEXT_MUTED.getColor();
            case INHERENT -> PlannhColors.ACCENT_RED.getColor();
        };
        final Widget<?> row = SummaryFlow.row()
            .fullWidth()
            .coverChildrenHeight(SummaryBody.LINE_H)
            .paddingLeft(SummaryBody.TEXT_X)
            .hoverBackground(new Rectangle().color(PlannhColors.SUMMARY_ROW_HOVER.getColor()))
            .child(
                wrapped(
                    plural(
                        kind,
                        n.notes()
                            .size()),
                    color));
        row.tooltipStatic(t -> {
            Tip.title(t, tr(KEY + kind + ".title"));
            Tip.body(t, tr(KEY + kind + ".help"));
            Tip.gap(t);
            for (final Summary.ChannelNote note : n.notes()) note(t, n.kind(), note);
        });
        return row;
    }

    private static void note(final RichTooltip t, final ChannelReport.Kind kind, final Summary.ChannelNote n) {
        switch (kind) {
            case CONFLICT -> Tip.item(
                t,
                tr(KEY + "note.conflict", n.victim(), n.hijacker(), catalysts(n.needs()))
                    + (n.plan() ? " " + tr(KEY + "note.plan") : ""));
            case TOLERATED -> Tip.item(
                t,
                tr(KEY + "note.tolerated", n.victim(), n.scale(), n.eut(), String.format("%.3g", n.timeRatio())));
            case INHERENT -> Tip.item(t, tr(KEY + "note.inherent", n.victim(), n.hijacker()));
        }
    }

    // endregion
    // region helpers

    static String modeKey(final ChannelSolver.Mode mode) {
        return KEY + "mode." + switch (mode) {
            case NONE -> "none";
            case CIRCUIT -> "circuit";
            case COLOR -> "color";
        };
    }

    private static Widget<?> chip(final int dye) {
        return new Widget<>().size(CHIP, CHIP)
            .background(new Rectangle().color(DyeColors.argb(dye)));
    }

    /** A chip (none when {@code dye} is negative) and text that wraps in the space left. */
    private static SummaryFlow chipRow(final int dye, final String text, final int color) {
        final SummaryFlow row = SummaryFlow.row();
        row.fullWidth()
            .coverChildrenHeight(SummaryBody.LINE_H)
            .paddingLeft(SummaryBody.TEXT_X)
            .childPadding(GAP)
            .crossAxisAlignment(Alignment.CrossAxis.CENTER);
        if (dye >= 0) row.child(chip(dye));
        row.child(
            new TextWidget<>(IKey.str(text)).color(color)
                .textAlign(Alignment.CenterLeft)
                .expanded());
        return row;
    }

    private static TextWidget<?> wrapped(final String text, final int color) {
        return new TextWidget<>(IKey.str(text)).color(color)
            .textAlign(Alignment.CenterLeft)
            .fullWidth();
    }

    private static String parts(final ChannelSolver.Parts p) {
        return tr(KEY + "parts", p.buses(), p.quad(), p.normal());
    }

    private static String catalysts(final String name) {
        return name.isEmpty() ? tr(KEY + "circuitless") : name;
    }

    /** "1 channel" / "3 channels": the lang file holds a {@code .one} and a {@code .many} form. */
    private static String plural(final String key, final int n) {
        return tr(KEY + key + (n == 1 ? ".one" : ".many"), n);
    }

    private static String tr(final String key, final Object... args) {
        return StatCollector.translateToLocalFormatted(key, args);
    }

    /** Tooltip typography, so every channel tooltip reads the same way. MUI2 wraps long lines itself. */
    private static final class Tip {

        private static final int GAP_PX = 3;

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

        /** A named choice and what it means; the one in effect is marked and highlighted. */
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

    // endregion
}
