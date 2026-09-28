package com.sbancuz.plannh.data.channels;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

/**
 * The input to {@link ChannelSolver}: the plan recipes one machine runs, and what else could run in
 * their place.
 *
 * @param keyOrder display and tie-break order of catalysts (circuits by number first)
 */
public record ChannelProblem(@Nonnull List<Recipe> recipes, @Nonnull Conflicts conflicts,
    @Nonnull Comparator<Ingredient.Item> keyOrder) {

    public enum Feed {
        PASSIVE,
        BATCH
    }

    /** What else can run, which depends on the feed. */
    public sealed interface Conflicts permits Batch,Passive {
    }

    /** @param hijacks per recipe (same index), what can run on its batch */
    public record Batch(@Nonnull List<List<Hijack>> hijacks) implements Conflicts {}

    /** @param intruders what can run on whatever a channel holds */
    public record Passive(@Nonnull List<Intruder> intruders) implements Conflicts {}

    public static ChannelProblem batch(final List<Recipe> recipes, final List<List<Hijack>> hijacks,
        final Comparator<Ingredient.Item> keyOrder) {
        return new ChannelProblem(recipes, new Batch(hijacks), keyOrder);
    }

    public static ChannelProblem passive(final List<Recipe> recipes, final List<Intruder> intruders,
        final Comparator<Ingredient.Item> keyOrder) {
        return new ChannelProblem(recipes, new Passive(intruders), keyOrder);
    }

    /** Rejects conflicts no channel avoids: those are the caller's to report. */
    public ChannelProblem {
        switch (conflicts) {
            case Batch(final List<List<Hijack>> hijacks) -> {
                if (hijacks.size() != recipes.size()) throw new IllegalArgumentException("one hijack list per recipe");
                for (int i = 0; i < hijacks.size(); i++) {
                    for (final Hijack h : hijacks.get(i)) {
                        if (recipes.get(i)
                            .catalysts()
                            .containsAll(h.needs())) {
                            throw new IllegalArgumentException("recipe " + i + " is hijacked in a channel of its own");
                        }
                    }
                }
            }
            case Passive(final List<Intruder> intruders) -> {
                for (final Intruder x : intruders) {
                    for (int i = 0; i < recipes.size(); i++) {
                        if (i != x.owner() && x.metBy(
                            recipes.get(i)
                                .held())) {
                            throw new IllegalArgumentException("recipe " + i + " is intruded on in a channel of its own");
                        }
                    }
                }
            }
        }
    }

    /**
     * @param catalysts what the recipe needs present; empty for circuitless
     * @param inputs    what it uses up
     */
    public record Recipe(@Nonnull Set<Ingredient.Item> catalysts, @Nonnull Set<Ingredient> inputs) {

        public int fluidInputs() {
            return (int) inputs.stream()
                .filter(i -> i instanceof Ingredient.Fluid)
                .count();
        }

        public boolean needsBus() {
            return !catalysts.isEmpty() || inputs.stream()
                .anyMatch(i -> i instanceof Ingredient.Item);
        }

        public Set<Ingredient> held() {
            final Set<Ingredient> out = new HashSet<>(inputs);
            out.addAll(catalysts);
            return out;
        }
    }

    /**
     * A recipe B that runs on a plan recipe's batch once its catalysts are present.
     *
     * @param needs      B's catalysts, which also name the check B is found in
     * @param fluidsOnly B also runs on the batch's fluids alone, so it reaches across colors
     * @param planRecipe the plan recipe B is, or -1; the machine retries its last recipe first
     */
    public record Hijack(@Nonnull Set<Ingredient.Item> needs, boolean fluidsOnly, int planRecipe) {}

    /**
     * A recipe that runs once a passive channel holds its ingredients.
     *
     * @param ingredients per ingredient, the keys that satisfy it (ore dictionary, alternative fluids)
     * @param owner       the plan recipe it is or multiplies, harmless where that recipe is; -1 for none
     */
    public record Intruder(@Nonnull List<Set<Ingredient>> ingredients, int owner) {

        /** Every ingredient is present in one of its forms. */
        public boolean metBy(final Set<? extends Ingredient> held) {
            return ingredients.stream()
                .allMatch(
                    forms -> forms.stream()
                        .anyMatch(held::contains));
        }
    }

    /** Circuitless first, then by catalysts in {@link #keyOrder}. */
    public Comparator<Set<Ingredient.Item>> catalystOrder() {
        return (a, b) -> {
            if (a.isEmpty() || b.isEmpty()) return Boolean.compare(!a.isEmpty(), !b.isEmpty());
            final Iterator<Ingredient.Item> ia = a.stream()
                .sorted(keyOrder)
                .iterator();
            final Iterator<Ingredient.Item> ib = b.stream()
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
