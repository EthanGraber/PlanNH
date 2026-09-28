package com.sbancuz.plannh.data.channels;

import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import javax.annotation.Nonnull;

/**
 * The input to {@link ChannelSolver}: the plan recipes one machine runs, and the other recipes that
 * could run in their place.
 *
 * @param hijacks   {@link Feed#BATCH} only: per recipe (same index), what can run on its batch
 * @param intruders {@link Feed#PASSIVE} only: what can run on whatever a channel holds
 * @param keyOrder  display and tie-break order of catalysts (circuits by number first)
 */
public record ChannelProblem(@Nonnull Feed feed, @Nonnull List<Recipe> recipes, @Nonnull List<List<Hijack>> hijacks,
    @Nonnull List<Intruder> intruders, @Nonnull Comparator<Ingredient.Item> keyOrder) {

    public enum Feed {
        PASSIVE,
        BATCH
    }

    public ChannelProblem {
        if (feed == Feed.BATCH ? !intruders.isEmpty()
            : hijacks.stream()
                .anyMatch(h -> !h.isEmpty())) {
            throw new IllegalArgumentException("hijacks are for batch feeds, intruders for passive ones");
        }
        if (feed == Feed.BATCH && hijacks.size() != recipes.size()) {
            throw new IllegalArgumentException("need one hijack list per recipe");
        }
        for (int i = 0; i < hijacks.size(); i++) {
            for (final Hijack h : hijacks.get(i)) {
                if (recipes.get(i)
                    .catalysts()
                    .containsAll(h.needs())) {
                    throw new IllegalArgumentException("recipe " + i + " is hijacked in a channel of its own");
                }
            }
        }
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

    /**
     * @param catalysts what the recipe needs present; empty for circuitless
     * @param inputs    what it uses up, items and fluids
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
     * Another recipe B that runs on a plan recipe's batch once its catalysts are present. Ones that
     * need only the victim's own catalysts, and harmless exact multiples, are for the caller to report.
     *
     * @param needs      the catalysts B needs present, which is also the check B is found in
     * @param fluidsOnly B also runs on the batch's fluids alone, so it can reach across colors
     * @param planRecipe index of the plan recipe B is, or -1; the machine retries its last recipe
     *                   before any circuit order
     */
    public record Hijack(@Nonnull Set<Ingredient.Item> needs, boolean fluidsOnly, int planRecipe) {}

    /**
     * A recipe that runs on its own once a passive channel holds its ingredients.
     *
     * @param needs one set per ingredient, any of which will do
     * @param owner the plan recipe it is, or is an exact multiple of: harmless wherever that recipe is.
     *              -1 for none
     */
    public record Intruder(@Nonnull List<Set<Ingredient>> needs, int owner) {

        public boolean metBy(final Set<? extends Ingredient> held) {
            for (final Set<Ingredient> any : needs) {
                boolean met = false;
                for (final Ingredient i : any) {
                    if (held.contains(i)) {
                        met = true;
                        break;
                    }
                }
                if (!met) return false;
            }
            return true;
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
