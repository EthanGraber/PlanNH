package com.sbancuz.plannh.data.channels;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.stream.IntStream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import com.sbancuz.plannh.data.flowchart.balancer.Budget;

/**
 * Splits a machine type's plan recipes into the fewest "channels" so that none of them can be hijacked
 * by another recipe, then uses the fewest input blocks among those splits.
 * <p>
 * A channel is one isolated input group. The {@link Mode} says how the recipes inside a channel are
 * kept apart, which follows GT's multiblock controller (MTEMultiBlockBase#doCheckRecipe): crafting
 * input buffer slots first, then each bus color black to white as separate checks (uncolored parts
 * join every color), and within one check the machine's last recipe, then GT's lookup cache, then the
 * normal lookup.
 * <p>
 * The ordered modes decide a channel's check order per catalyst set: recipes with the same catalysts
 * are found in the same check, so no order can put one before the other. A circuitless recipe can't be
 * given priority over any catalyst either: it is always checked last, so a circuitless recipe that can
 * be hijacked must get a channel of its own.
 */
public final class ChannelSolver {

    /** Bus/hatch colors, in the order the controller checks them. */
    public static final int COLORS = 16;
    /** Fluids per quad input hatch; the whole block takes one color. */
    public static final int QUAD = 4;

    public enum Mode {
        /**
         * No hijack may exist inside a channel. A channel is its own machine, its own fully colored
         * bus+hatch group, or its own crafting input buffer; several fit in one machine as colors.
         */
        NONE,
        /**
         * Several circuits in one bus, checked in circuit order. A hijack of A by B is fine if A's
         * catalysts are checked first, except when B is itself a plan recipe in the channel (the
         * controller retries its last recipe before that order) or A is circuitless.
         */
        CIRCUIT,
        /**
         * A colored bus per catalyst set, fluids through shared uncolored hatches, checked black first.
         * Only hijacks that run on the victim's fluids cross colors. Uncolored hatches reach every
         * color, so a channel here is a whole machine (the panel calls it one), holding up to 16
         * colors. Circuitless recipes still come last.
         */
        COLOR
    }

    /**
     * How long a solve may search, in the balancer's terms: a wall-clock {@link Budget} shared by both
     * passes, and a cap on the search nodes of each so a result doesn't hinge on machine speed where
     * it matters. The channel count is proven minimal when pass 1 finishes, the block count when pass 2
     * does; when either stops early the best split found so far stands and the {@link Solution} says
     * so.
     *
     * @param millis       wall-clock ceiling on the whole solve
     * @param nodesPerPass search nodes each pass may visit
     */
    public record Limits(long millis, int nodesPerPass) {

        /**
         * The summary re-derives its rows on the client thread, so a solve gets a fraction of what the
         * balancer's alternatives search is allowed ({@code Numerics#altBudgetMillis}). Realistic
         * charts finish in well under a millisecond.
         */
        public static final Limits DEFAULT = new Limits(150, 200_000);
    }

    /** Input blocks a channel needs; outputs, energy and maintenance aren't counted. */
    public record Parts(int buses, int quad, int normal) {

        public static final Parts ZERO = new Parts(0, 0, 0);

        public int blocks() {
            return buses + quad + normal;
        }

        public Parts plus(final Parts o) {
            return new Parts(buses + o.buses, quad + o.quad, normal + o.normal);
        }
    }

    /**
     * @param members    recipe indices in check order
     * @param checkOrder the channel's catalyst sets, checked first to last (in {@link Mode#COLOR} the
     *                   n-th set takes the n-th color)
     */
    public record Channel(@Nonnull List<Integer> members, @Nonnull List<Set<String>> checkOrder,
        @Nonnull Parts parts) {}

    /**
     * @param machines        machines needed for these channels, for conflicts alone
     * @param channelsMinimal the channel count is proven minimal (pass 1 finished within its limits)
     * @param blocksMinimal   the block count is proven minimal for that channel count (pass 2 finished)
     */
    public record Solution(@Nonnull Mode mode, @Nonnull List<Channel> channels, int machines, @Nonnull Parts total,
        boolean channelsMinimal, boolean blocksMinimal) {}

    private ChannelSolver() {}

    /** (quad, normal) hatches that hold {@code fluids} distinct fluids in the fewest blocks. */
    public static int[] fluidHatches(final int fluids) {
        final int quad = fluids / QUAD;
        final int rest = fluids % QUAD;
        if (rest == 1) return new int[] { quad, 1 };
        return new int[] { quad + (rest > 0 ? 1 : 0), 0 };
    }

    /**
     * Blocks needed, assuming one batch in the machine at a time: hatches are sized for the recipe with
     * the most fluids. {@link Mode#COLOR} needs a bus per catalyst set, plus one for circuitless
     * recipes with items.
     */
    public static Parts parts(final ChannelProblem p, final List<Integer> members, final Mode mode) {
        int fluids = 0;
        boolean bus = false;
        boolean circuitlessBus = false;
        final Set<Set<String>> sets = new HashSet<>();
        for (final int i : members) {
            final ChannelProblem.Recipe r = p.recipes()
                .get(i);
            fluids = Math.max(fluids, r.fluidInputs());
            bus |= r.needsBus();
            if (r.catalysts()
                .isEmpty()) circuitlessBus |= r.needsBus();
            else sets.add(r.catalysts());
        }
        final int[] hatches = fluidHatches(fluids);
        final int buses = mode == Mode.COLOR ? sets.size() + (circuitlessBus ? 1 : 0) : bus ? 1 : 0;
        return new Parts(buses, hatches[0], hatches[1]);
    }

    /**
     * Check order of the channel's catalyst sets, first to last, or null if the recipes can't share a
     * channel in this mode. Each hijack of A by B that can happen needs A's catalysts checked before
     * B's.
     */
    @Nullable
    public static List<Set<String>> checkOrder(final ChannelProblem p, final List<Integer> members, final Mode mode) {
        final Comparator<Set<String>> order = p.catalystOrder();
        final Set<Set<String>> setsPresent = new HashSet<>();
        final Set<String> present = new HashSet<>();
        for (final int i : members) {
            final Set<String> catalysts = p.recipes()
                .get(i)
                .catalysts();
            setsPresent.add(catalysts);
            present.addAll(catalysts);
        }
        final List<Set<String>> sets = new ArrayList<>(setsPresent);
        sets.sort(order);
        if (mode == Mode.COLOR && sets.size() > COLORS) return null;

        // An edge A -> B: A's catalysts must be checked before B's
        final Map<Set<String>, Set<Set<String>>> edges = new LinkedHashMap<>();
        for (final Set<String> s : sets) edges.put(s, new HashSet<>());
        final Set<Integer> memberSet = new HashSet<>(members);

        for (final int victim : members) {
            final Set<String> src = p.recipes()
                .get(victim)
                .catalysts();
            for (final ChannelProblem.Hijack h : p.hijacks()
                .get(victim)) {
                if (mode == Mode.COLOR) {
                    // B runs from any bus holding its catalysts, seeing only the victim's fluids
                    if (!h.fluidsOnly()) continue;
                    for (final Set<String> s : sets) {
                        if (s.equals(src) || !s.containsAll(h.needs())) continue;
                        if (src.isEmpty()) return null; // circuitless is always checked last
                        edges.get(src)
                            .add(s);
                    }
                    continue;
                }
                if (!present.containsAll(h.needs())) continue;
                if (mode == Mode.NONE) return null;
                // CIRCUIT: B is found in the check for exactly its catalysts
                final Set<String> dst = setsPresent.contains(h.needs()) ? h.needs() : null;
                if (dst == null || dst.equals(src) || src.isEmpty() || memberSet.contains(h.planRecipe())) {
                    return null;
                }
                edges.get(src)
                    .add(dst);
            }
        }
        final List<Set<String>> sorted = topoSort(sets, edges, order);
        if (sorted == null || mode == Mode.NONE) return sorted;
        // Nothing can be ordered after circuitless (such hijacks are inherent), so it goes last
        if (sorted.remove(Set.<String>of())) sorted.add(Set.of());
        return sorted;
    }

    @Nullable
    private static List<Set<String>> topoSort(final List<Set<String>> sets,
        final Map<Set<String>, Set<Set<String>>> edges, final Comparator<Set<String>> order) {
        final Map<Set<String>, Integer> indegree = new HashMap<>();
        for (final Set<String> s : sets) indegree.put(s, 0);
        for (final Set<Set<String>> ds : edges.values())
            for (final Set<String> d : ds) indegree.merge(d, 1, Integer::sum);

        final TreeSet<Set<String>> ready = new TreeSet<>(order);
        for (final Set<String> s : sets) if (indegree.get(s) == 0) ready.add(s);
        final List<Set<String>> out = new ArrayList<>();
        while (!ready.isEmpty()) {
            final Set<String> s = ready.pollFirst();
            out.add(s);
            for (final Set<String> d : edges.get(s)) {
                if (indegree.merge(d, -1, Integer::sum) == 0) ready.add(d);
            }
        }
        return out.size() == sets.size() ? out : null;
    }

    public static Solution solve(final ChannelProblem p, final Mode mode) {
        return solve(p, mode, Limits.DEFAULT);
    }

    /**
     * Fewest channels, then fewest input blocks, each exact unless {@code limits} run out first; see
     * {@link Solution#channelsMinimal()} and {@link Solution#blocksMinimal()}.
     */
    public static Solution solve(final ChannelProblem p, final Mode mode, final Limits limits) {
        return new Search(p, mode, limits).run();
    }

    private static final class Search {

        private final ChannelProblem p;
        private final Mode mode;
        private final Limits limits;
        private final Budget budget;
        private final int n;
        private final int[] order;
        private final Map<Set<Integer>, Boolean> validCache = new HashMap<>();
        private final Map<Set<Integer>, Integer> blocksCache = new HashMap<>();
        private final List<List<Integer>> channels = new ArrayList<>();

        private List<List<Integer>> best;
        private int bestCost;
        private int target;
        private int nodes;
        private boolean stopped;

        Search(final ChannelProblem p, final Mode mode, final Limits limits) {
            this.p = p;
            this.mode = mode;
            this.limits = limits;
            this.budget = Budget.of(limits.millis());
            this.n = p.recipes()
                .size();
            // Most-constrained recipes first; recipes with the same catalysts adjacent
            final Comparator<Set<String>> sets = p.catalystOrder();
            this.order = IntStream.range(0, n)
                .boxed()
                .sorted(
                    Comparator.<Integer>comparingInt(
                        i -> -p.hijacks()
                            .get(i)
                            .size())
                        .thenComparing(
                            i -> p.recipes()
                                .get(i)
                                .catalysts(),
                            sets))
                .mapToInt(Integer::intValue)
                .toArray();
        }

        Solution run() {
            if (n == 0) return new Solution(mode, List.of(), 0, Parts.ZERO, true, true);

            // Pass 1 starts from a first-fit split, so stopping early still leaves a sound answer
            best = firstFit();
            startPass();
            fewest(0);
            final boolean channelsMinimal = !stopped;
            target = best.size();

            // Pass 2 is optional: it only runs on what is left of the budget
            bestCost = cost(best);
            startPass();
            if (!budget.expired()) cheapest(0);
            else stopped = true;
            final boolean blocksMinimal = !stopped;

            final List<Channel> out = new ArrayList<>();
            for (final List<Integer> members : best) {
                final List<Set<String>> checkOrder = checkOrder(p, members, mode);
                if (checkOrder == null) throw new IllegalStateException("solver kept an invalid channel");
                final List<Integer> sorted = new ArrayList<>(members);
                sorted.sort(
                    Comparator.<Integer>comparingInt(
                        i -> checkOrder.indexOf(
                            p.recipes()
                                .get(i)
                                .catalysts()))
                        .thenComparingInt(i -> i));
                out.add(new Channel(List.copyOf(sorted), List.copyOf(checkOrder), parts(p, members, mode)));
            }
            final Comparator<Set<String>> sets = p.catalystOrder();
            out.sort(
                (a, b) -> sets.compare(
                    p.recipes()
                        .get(
                            a.members()
                                .getFirst())
                        .catalysts(),
                    p.recipes()
                        .get(
                            b.members()
                                .getFirst())
                        .catalysts()));

            Parts total = Parts.ZERO;
            for (final Channel c : out) total = total.plus(c.parts());
            final int machines = mode == Mode.COLOR ? out.size() : (out.size() + COLORS - 1) / COLORS;
            return new Solution(mode, List.copyOf(out), machines, total, channelsMinimal, blocksMinimal);
        }

        /** Each recipe, in search order, into the first channel that takes it. */
        private List<List<Integer>> firstFit() {
            final List<List<Integer>> out = new ArrayList<>();
            for (final int r : order) {
                boolean placed = false;
                for (final List<Integer> c : out) {
                    c.add(r);
                    if (valid(c)) {
                        placed = true;
                        break;
                    }
                    c.removeLast();
                }
                if (!placed) out.add(new ArrayList<>(List.of(r)));
            }
            return out;
        }

        private void startPass() {
            nodes = 0;
            stopped = false;
        }

        /** Counts a node; false once this pass's nodes or the shared clock have run out. */
        private boolean step() {
            if (stopped) return false;
            if (++nodes > limits.nodesPerPass() || budget.expired()) stopped = true;
            return !stopped;
        }

        private boolean valid(final List<Integer> members) {
            return validCache.computeIfAbsent(Set.copyOf(members), k -> checkOrder(p, members, mode) != null);
        }

        private int blocks(final List<Integer> members) {
            return blocksCache.computeIfAbsent(Set.copyOf(members), k -> parts(p, members, mode).blocks());
        }

        private int cost(final List<List<Integer>> chs) {
            int c = 0;
            for (final List<Integer> ch : chs) c += blocks(ch);
            return c;
        }

        /** Pass 1: the minimum channel count. */
        private void fewest(final int k) {
            if (!step() || channels.size() >= best.size()) return;
            if (k == n) {
                best = copy(channels);
                return;
            }
            final int r = order[k];
            // By index: deeper calls add and remove channels, leaving the list as they found it
            for (int ci = 0, size = channels.size(); ci < size; ci++) {
                final List<Integer> c = channels.get(ci);
                c.add(r);
                if (valid(c)) fewest(k + 1);
                c.removeLast();
            }
            channels.add(new ArrayList<>(List.of(r)));
            fewest(k + 1);
            channels.removeLast();
        }

        /** Pass 2: the fewest blocks with exactly {@link #target} channels. */
        private void cheapest(final int k) {
            if (!step() || lowerBound(k) >= bestCost) return;
            if (k == n) {
                best = copy(channels);
                bestCost = cost(channels);
                return;
            }
            final int r = order[k];
            // By index: deeper calls add and remove channels, leaving the list as they found it
            for (int ci = 0, size = channels.size(); ci < size; ci++) {
                final List<Integer> c = channels.get(ci);
                c.add(r);
                if (valid(c)) cheapest(k + 1);
                c.removeLast();
            }
            if (channels.size() < target) {
                channels.add(new ArrayList<>(List.of(r)));
                cheapest(k + 1);
                channels.removeLast();
            }
        }

        private int lowerBound(final int k) {
            int bound = cost(channels);
            if (mode == Mode.COLOR) {
                // Every catalyst set not placed yet needs at least a bus of its own
                final Set<Set<String>> placed = new HashSet<>();
                for (final List<Integer> c : channels) for (final int i : c) placed.add(
                    p.recipes()
                        .get(i)
                        .catalysts());
                final Set<Set<String>> pending = new HashSet<>();
                for (int j = k; j < n; j++) pending.add(
                    p.recipes()
                        .get(order[j])
                        .catalysts());
                pending.removeAll(placed);
                bound += pending.size();
            }
            return bound;
        }

        private static List<List<Integer>> copy(final List<List<Integer>> chs) {
            final List<List<Integer>> out = new ArrayList<>();
            for (final List<Integer> c : chs) out.add(new ArrayList<>(c));
            return out;
        }
    }
}
