package com.sbancuz.plannh.data.channels;

import java.util.Comparator;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

/**
 * The input to {@link ChannelSolver}: the plan recipes run by one machine type, and every way another
 * recipe in the machine's recipe map could run in their place.
 * <p>
 * Catalysts are the inputs a recipe needs present but doesn't use up: its programmed circuit, or a
 * lens, mold and the like. They are opaque string keys here; a recipe's {@code catalysts} is the set it
 * needs, empty for circuitless recipes. The machine finds recipes with the same catalysts in the same
 * check, so everything about ordering is decided per catalyst set, not per recipe. This class knows
 * nothing about Minecraft, so the solver can be tested headless.
 *
 * @param recipes  distinct plan recipes, indexed by position
 * @param hijacks  per recipe (same index), the recipes that could run on its inputs instead
 * @param keyOrder display/tie-break order of catalyst keys (e.g. circuits by number)
 */
public record ChannelProblem(@Nonnull List<Recipe> recipes, @Nonnull List<List<Hijack>> hijacks,
    @Nonnull Comparator<String> keyOrder) {

    public ChannelProblem {
        if (hijacks.size() != recipes.size()) {
            throw new IllegalArgumentException("need one hijack list per recipe");
        }
        for (int i = 0; i < recipes.size(); i++) {
            final Set<String> catalysts = recipes.get(i)
                .catalysts();
            for (final Hijack h : hijacks.get(i)) {
                if (catalysts.containsAll(h.needs())) {
                    throw new IllegalArgumentException(
                        "recipe " + i + " is hijacked with only its own catalysts present; report it, don't solve it");
                }
            }
        }
    }

    /**
     * @param catalysts   the catalysts this recipe needs present; empty for a circuitless recipe
     * @param fluidInputs distinct fluid inputs, which sizes the fluid hatches
     * @param needsBus    whether it has any item input or catalyst
     */
    public record Recipe(@Nonnull Set<String> catalysts, int fluidInputs, boolean needsBus) {}

    /**
     * Another recipe B that can run on a plan recipe's inputs once its catalysts are present. Only
     * hijacks that depend on the channel's contents belong here; ones that happen with nothing but the
     * victim's own catalysts, or that are harmless exact multiples, are the caller's to report.
     *
     * @param needs      catalyst keys B needs present: its own catalysts plus any plan catalyst it
     *                   consumes
     * @param fluidsOnly B also runs on the victim's fluids alone, without its items; only such hijacks
     *                   cross into another colored bus
     * @param planRecipe index of the plan recipe B is, or -1. A plan recipe runs for real, so the
     *                   controller caches it and retries it before any circuit order
     */
    public record Hijack(@Nonnull Set<String> needs, boolean fluidsOnly, int planRecipe) {}

    /** Orders catalyst sets: circuitless first, then by their catalysts in {@link #keyOrder}. */
    public Comparator<Set<String>> catalystOrder() {
        return (a, b) -> {
            if (a.isEmpty() || b.isEmpty()) return Boolean.compare(!a.isEmpty(), !b.isEmpty());
            final Iterator<String> ia = a.stream()
                .sorted(keyOrder)
                .iterator();
            final Iterator<String> ib = b.stream()
                .sorted(keyOrder)
                .iterator();
            while (ia.hasNext() && ib.hasNext()) {
                final int c = keyOrder.compare(ia.next(), ib.next());
                if (c != 0) return c;
            }
            return Boolean.compare(ia.hasNext(), ib.hasNext());
        };
    }
}
