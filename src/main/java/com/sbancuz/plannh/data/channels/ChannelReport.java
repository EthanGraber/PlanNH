package com.sbancuz.plannh.data.channels;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.sbancuz.plannh.data.channels.ChannelSolver.Mode;
import com.sbancuz.plannh.data.channels.ChannelSolver.Solution;
import com.sbancuz.plannh.data.flowchart.Graph;

/**
 * Which plan recipes can share an input channel, per machine type, in every {@link Mode}. Built in-game
 * by an {@link Analyzer} (it needs the live recipe maps), but holds only display strings and solver
 * results, so the summary can read it without touching Minecraft.
 */
public record ChannelReport(@Nonnull List<MachineReport> machines) {

    /** Builds a report from the chart; installed by the GT provider when GregTech is present. */
    @FunctionalInterface
    public interface Analyzer {

        ChannelReport analyze(@Nonnull Graph graph, boolean respectAmounts);
    }

    @Nullable
    private static Analyzer analyzer;

    public static void setAnalyzer(@Nullable final Analyzer a) {
        analyzer = a;
    }

    @Nullable
    public static Analyzer analyzer() {
        return analyzer;
    }

    /**
     * @param handler       the NEI recipe handler name that identifies the machine type, the same key
     *                      machine groups use ({@code Node#handlerName})
     * @param machine       machine name as the chart shows it, for display
     * @param recipes       the machine's distinct plan recipes, indexed like the solver's
     * @param catalystNames display names of the catalyst keys ("#24", "Ruby Lens", ...)
     * @param solutions     one per mode
     * @param findings      conflicts, tolerated variants and unavoidable hijacks, for display
     * @param dedicated     channels needed with one per catalyst set (the no-sharing baseline)
     */
    public record MachineReport(@Nonnull String handler, @Nonnull String machine, @Nonnull List<PlanRecipe> recipes,
        @Nonnull Map<String, String> catalystNames, @Nonnull Map<Mode, Solution> solutions,
        @Nonnull List<Finding> findings, int dedicated) {

        /** "#3 + Ruby Lens"; empty for circuitless, which the GUI labels itself. */
        public String catalystsName(final Set<String> catalysts) {
            final StringBuilder sb = new StringBuilder();
            catalysts.stream()
                .sorted()
                .forEach(k -> {
                    if (!sb.isEmpty()) sb.append(" + ");
                    sb.append(catalystNames.getOrDefault(k, k));
                });
            return sb.toString();
        }
    }

    /** @param catalysts the catalysts the recipe needs present; empty for circuitless */
    public record PlanRecipe(@Nonnull String label, @Nonnull Set<String> catalysts, @Nonnull List<UUID> nodeIds) {}

    public enum Kind {
        /** Keeps two catalysts apart; the solver works around it. */
        CONFLICT,
        /** An exact multiple of the plan recipe: same outputs, same ratios, so harmless. */
        TOLERATED,
        /**
         * Runs on the recipe's inputs with nothing but its own catalysts present, so no channel
         * assignment avoids it; a machine of its own wouldn't either. GT runs whichever match its lookup
         * reaches first, so the other recipe never runs or runs unpredictably. Rare: GT's registration
         * checks keep it out of most machines (none in the chemistry ones), but it exists in the pack
         * (e.g. two canner recipes on the same inputs), and a player wants to know about it.
         */
        INHERENT
    }

    /**
     * @param victim    index of the plan recipe that can be replaced
     * @param hijacker  label of the recipe that can run instead
     * @param needs     catalysts that must be present for it
     * @param plan      the hijacker is itself one of the plan's recipes
     * @param scale     for {@link Kind#TOLERATED}: how many of the plan recipe it is ("64", "1/9")
     * @param eut       the hijacker's EU/t
     * @param timeRatio the hijacker's time per unit of output against the plan recipe's (0 if unknown)
     */
    public record Finding(@Nonnull Kind kind, int victim, @Nonnull String hijacker, @Nonnull Set<String> needs,
        boolean plan, @Nonnull String scale, long eut, double timeRatio) {}

    /**
     * A node's place in the chosen mode's layout: the dye of its color group or bus, and a short tag
     * ("2" for channel 2; "2·3" for the third check in channel 2).
     */
    public record Badge(int dye, @Nonnull String tag) {}

    /**
     * Badges for every analyzed node under {@code mode}. Machine types that need nothing special (one
     * channel and, without ordering, nothing to place) get none.
     */
    public Map<UUID, Badge> badges(final Mode mode) {
        final Map<UUID, Badge> out = new HashMap<>();
        for (final MachineReport m : machines) {
            final Solution s = m.solutions()
                .get(mode);
            if (s == null) continue;
            final int channels = s.channels()
                .size();
            for (int c = 0; c < channels; c++) {
                final ChannelSolver.Channel ch = s.channels()
                    .get(c);
                final boolean ordered = mode != Mode.NONE && ch.checkOrder()
                    .size() > 1;
                if (channels <= 1 && !ordered) continue;
                for (final int r : ch.members()) {
                    final PlanRecipe recipe = m.recipes()
                        .get(r);
                    final int pos = ch.checkOrder()
                        .indexOf(recipe.catalysts());
                    final int dye = mode == Mode.COLOR ? pos : c;
                    final String channel = channels > 1 ? String.valueOf(c + 1) : "";
                    final String tag = !ordered ? channel
                        : channel.isEmpty() ? String.valueOf(pos + 1) : channel + "·" + (pos + 1);
                    for (final UUID id : recipe.nodeIds()) out.put(id, new Badge(dye % ChannelSolver.COLORS, tag));
                }
            }
        }
        return out;
    }
}
