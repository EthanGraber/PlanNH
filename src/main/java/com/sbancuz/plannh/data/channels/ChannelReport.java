package com.sbancuz.plannh.data.channels;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.sbancuz.plannh.data.channels.ChannelProblem.Feed;
import com.sbancuz.plannh.data.channels.ChannelSolver.Mode;
import com.sbancuz.plannh.data.channels.ChannelSolver.Solution;
import com.sbancuz.plannh.data.flowchart.Graph;

/**
 * Which plan recipes can share a machine, per machine pool, in every {@link Mode}. Built by an
 * {@link Analyzer} from the live recipe maps; holds only display data and solver results.
 *
 * @param dyes the 16 bus colors in check order
 */
public record ChannelReport(@Nonnull List<MachineReport> machines, @Nonnull List<Dye> dyes) {

    /** Installed by the GT provider when GregTech is present. */
    @FunctionalInterface
    public interface Analyzer {

        ChannelReport analyze(@Nonnull Graph graph, @Nonnull Feed feed);
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

    public record Dye(int rgb, @Nonnull String name) {}

    /**
     * One pool of machines: a machine group, or the chart's ungrouped nodes of one machine type.
     *
     * @param handler   NEI recipe handler name, the machine type
     * @param group     the machine group's name, or null for ungrouped nodes
     * @param capacity  the machine group's machine count, or 0 for no limit
     * @param recipes   distinct plan recipes, indexed like the solver's
     * @param names     display names of the ingredients the findings and catalysts mention
     * @param dedicated channels with one per catalyst set, the no-sharing baseline
     */
    public record MachineReport(@Nonnull String handler, @Nonnull String machine, @Nullable String group, int capacity,
        @Nonnull List<PlanRecipe> recipes, @Nonnull Map<Ingredient, String> names,
        @Nonnull Map<Mode, Solution> solutions, @Nonnull List<Finding> findings, int dedicated) {

        /**
         * The layout to show: {@link Mode#NONE} without priority, else whichever ordered layout needs
         * fewer machines, then fewer blocks, then circuit order (one bus).
         */
        public Solution solution(final boolean priority) {
            if (!priority) return solutions.get(Mode.NONE);
            final Solution circuit = solutions.get(Mode.CIRCUIT);
            final Solution color = solutions.get(Mode.COLOR);
            if (color.machines() != circuit.machines()) return color.machines() < circuit.machines() ? color : circuit;
            return color.total()
                .blocks()
                < circuit.total()
                    .blocks() ? color : circuit;
        }

        /** "#3 + Ruby Lens"; empty for circuitless. */
        public String catalystsName(final Set<Ingredient.Item> catalysts) {
            return catalysts.stream()
                .map(this::name)
                .sorted()
                .collect(Collectors.joining(" + "));
        }

        /** "#3, Water, Ammonia or Chlorine". */
        public String needsName(final List<Set<Ingredient>> needs) {
            return needs.stream()
                .map(
                    any -> any.stream()
                        .map(this::name)
                        .sorted()
                        .collect(Collectors.joining(" or ")))
                .collect(Collectors.joining(", "));
        }

        private String name(final Ingredient i) {
            return names.getOrDefault(i, i.toString());
        }
    }

    public record PlanRecipe(@Nonnull String label, @Nonnull Set<Ingredient.Item> catalysts,
        @Nonnull List<UUID> nodeIds) {}

    public enum Kind {
        CONFLICT,
        TOLERATED,
        /**
         * Theoretically shouldn't exist, but in practice there are a few GT recpies that always conflict with each other and have no disambiguator.
         */
        INHERENT
    }

    /**
     * @param victim    the plan recipe it runs in place of, or -1 when it needs several recipes' inputs
     * @param hijacker  label of the recipe that can run
     * @param needs     what must be present for it, one set of alternatives per ingredient
     * @param plan      the hijacker is itself a plan recipe
     * @param scale     for {@link Kind#TOLERATED}: how many of the plan recipe it is ("64", "1/9")
     * @param timeRatio its time per unit of output against the plan recipe's, 0 if unknown
     */
    public record Finding(@Nonnull Kind kind, int victim, @Nonnull String hijacker,
        @Nonnull List<Set<Ingredient>> needs, boolean plan, @Nonnull String scale, long eut, double timeRatio) {}
}
