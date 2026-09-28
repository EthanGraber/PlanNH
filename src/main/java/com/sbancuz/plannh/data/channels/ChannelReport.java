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

/** Per machine pool, the channel layout in every {@link Mode}, plus what the summary displays. */
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
     * A machine group, or the chart's ungrouped nodes of one machine type.
     *
     * @param group     null for ungrouped nodes
     * @param capacity  0 for no limit
     * @param dedicated channels with one per catalyst set, the no-sharing baseline
     */
    public record MachineReport(@Nonnull String handler, @Nonnull String machine, @Nullable String group, int capacity,
        @Nonnull List<PlanRecipe> recipes, @Nonnull Map<Ingredient, String> names,
        @Nonnull Map<Mode, Solution> solutions, @Nonnull List<Finding> findings, int dedicated) {

        /** Without priority, {@link Mode#NONE}; else the ordered layout with fewer machines, then blocks. */
        public Solution solution(final boolean priority) {
            if (!priority) return solutions.get(Mode.NONE);
            final Solution circuit = solutions.get(Mode.CIRCUIT);
            final Solution color = solutions.get(Mode.COLOR);
            if (color.machines() != circuit.machines()) return color.machines() < circuit.machines() ? color : circuit;
            final int colorBlocks = color.total()
                .blocks();
            final int circuitBlocks = circuit.total()
                .blocks();
            return colorBlocks < circuitBlocks ? color : circuit;
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
        /** Shouldn't exist, but a few GT recipes always conflict and have nothing to tell them apart. */
        INHERENT
    }

    /**
     * @param victim    the plan recipe it replaces, or -1 when it needs several recipes' inputs
     * @param scale     {@link Kind#TOLERATED} only: how many of the plan recipe it is ("64", "1/9")
     * @param timeRatio time per unit of output against the plan recipe's, 0 if unknown
     */
    public record Finding(@Nonnull Kind kind, int victim, @Nonnull String hijacker,
        @Nonnull List<Set<Ingredient>> needs, boolean plan, @Nonnull String scale, long eut, double timeRatio) {}
}
