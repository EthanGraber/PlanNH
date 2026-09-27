package com.sbancuz.plannh.data.provider;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraftforge.fluids.FluidStack;

import com.sbancuz.plannh.data.RecipeHandlerAccess;
import com.sbancuz.plannh.data.channels.ChannelProblem;
import com.sbancuz.plannh.data.channels.ChannelReport;
import com.sbancuz.plannh.data.channels.ChannelReport.Finding;
import com.sbancuz.plannh.data.channels.ChannelReport.Kind;
import com.sbancuz.plannh.data.channels.ChannelSolver;
import com.sbancuz.plannh.data.channels.ChannelSolver.Mode;
import com.sbancuz.plannh.data.flowchart.Graph;
import com.sbancuz.plannh.data.flowchart.Node;

import codechicken.nei.recipe.RecipeHandlerRef;
import codechicken.nei.recipe.TemplateRecipeHandler;
import gregtech.api.recipe.RecipeMap;
import gregtech.api.util.GTRecipe;
import gregtech.api.util.GTUtility;
import gregtech.nei.GTNEIDefaultHandler;
import gregtech.nei.GTNEIDefaultHandler.CachedDefaultRecipe;

/**
 * Finds, for every GT recipe on a chart, the other recipes in its machine's recipe maps that could run on
 * its inputs instead, using GT's own recipe matcher, and hands them to {@link ChannelSolver}.
 * <p>
 * A plan recipe A is queried twice: with its inputs plus every catalyst the machine's plan recipes use
 * (anything that could share a channel with it), and with only its fluids plus those catalysts (what a
 * bus of another color sees through shared uncolored hatches). Queries never write GT's lookup cache.
 */
public final class GTChannelAnalyzer {

    private static final String CIRCUIT = "gregtech:gt.integrated_circuit";
    private static final int FULL_CHANCE = 10000;

    private GTChannelAnalyzer() {}

    /** A distinct plan recipe on one machine type, and the nodes that run it. */
    private record Entry(GTRecipe recipe, RecipeMap<?> map, List<UUID> nodeIds) {}

    public static ChannelReport analyze(@Nonnull final Graph graph, final boolean respectAmounts) {
        // Machine type is the recipe handler, as for machine groups; the display name only labels it
        final Map<String, List<Entry>> byHandler = new LinkedHashMap<>();
        final Map<String, String> labels = new HashMap<>();
        final Map<String, Map<GTRecipe, Entry>> seen = new HashMap<>();
        for (final Node node : graph.getNodes()) {
            final GTRecipe recipe = recipeOf(node);
            final RecipeMap<?> map = mapOf(node);
            if (recipe == null || map == null) continue;
            final String handler = node.handlerName()
                .isEmpty() ? map.unlocalizedName : node.handlerName();
            labels.putIfAbsent(handler, node.machineName != null ? node.machineName : map.unlocalizedName);
            final Map<GTRecipe, Entry> known = seen.computeIfAbsent(handler, k -> new IdentityHashMap<>());
            Entry e = known.get(recipe);
            if (e == null) {
                e = new Entry(recipe, map, new ArrayList<>());
                known.put(recipe, e);
                byHandler.computeIfAbsent(handler, k -> new ArrayList<>())
                    .add(e);
            }
            e.nodeIds()
                .add(node.id);
        }

        final List<ChannelReport.MachineReport> machines = new ArrayList<>();
        for (final Map.Entry<String, List<Entry>> m : byHandler.entrySet()) {
            final ChannelReport.MachineReport report = analyzeMachine(
                m.getKey(),
                labels.get(m.getKey()),
                m.getValue(),
                respectAmounts);
            // A lone recipe with nothing to warn about has nothing to say
            if (report.recipes()
                .size() > 1 || report.findings()
                    .stream()
                    .anyMatch(f -> f.kind() == Kind.INHERENT)) {
                machines.add(report);
            }
        }
        machines.sort(Comparator.comparing(ChannelReport.MachineReport::machine));
        return new ChannelReport(List.copyOf(machines));
    }

    private static ChannelReport.MachineReport analyzeMachine(final String handler, final String machine,
        final List<Entry> entries, final boolean respectAmounts) {
        // Every catalyst the machine's plan recipes use: anything a channel could hold
        final Map<String, ItemStack> universe = new LinkedHashMap<>();
        final Map<String, String> names = new HashMap<>();
        final List<Set<String>> catalystSets = new ArrayList<>();
        for (final Entry e : entries) {
            final Set<String> catalysts = new HashSet<>();
            for (final ItemStack s : items(e.recipe())) {
                if (s.stackSize != 0) continue;
                final String key = key(s);
                catalysts.add(key);
                final ItemStack one = s.copy();
                one.stackSize = 1;
                universe.putIfAbsent(key, one);
                names.putIfAbsent(key, catalystName(s));
            }
            catalystSets.add(Set.copyOf(catalysts));
        }
        final ItemStack[] catalystStacks = universe.values()
            .toArray(new ItemStack[0]);
        final Map<GTRecipe, Integer> planIndex = new IdentityHashMap<>();
        for (int i = 0; i < entries.size(); i++) planIndex.put(
            entries.get(i)
                .recipe(),
            i);
        final Set<RecipeMap<?>> maps = Collections.newSetFromMap(new IdentityHashMap<>());
        for (final Entry e : entries) maps.add(e.map());

        final List<ChannelProblem.Recipe> recipes = new ArrayList<>();
        final List<List<ChannelProblem.Hijack>> hijacks = new ArrayList<>();
        final List<Finding> findings = new ArrayList<>();
        final List<ChannelReport.PlanRecipe> planRecipes = new ArrayList<>();

        for (int ai = 0; ai < entries.size(); ai++) {
            final Entry entry = entries.get(ai);
            final GTRecipe a = entry.recipe();
            final Set<String> catalystsA = catalystSets.get(ai);
            final List<ItemStack> consumed = new ArrayList<>();
            for (final ItemStack s : items(a)) if (s.stackSize > 0) consumed.add(s.copy());
            final FluidStack[] fluids = fluids(a).map(FluidStack::copy)
                .toArray(FluidStack[]::new);

            final ItemStack[] withItems = Stream.concat(consumed.stream(), Stream.of(catalystStacks))
                .toArray(ItemStack[]::new);
            final Set<GTRecipe> hits = find(maps, withItems, fluids, a, respectAmounts);
            final Set<GTRecipe> fluidHits = find(maps, catalystStacks, fluids, a, respectAmounts);

            final List<ChannelProblem.Hijack> own = new ArrayList<>();
            for (final GTRecipe b : hits) {
                final Set<String> needs = needs(b, consumed, universe);
                final int plan = planIndex.getOrDefault(b, -1);
                final long[] k = exactMultiple(a, b);
                if (k != null) {
                    final double time = a.mDuration > 0 ? (double) b.mDuration * k[1] / k[0] / a.mDuration : 0;
                    findings.add(new Finding(Kind.TOLERATED, ai, label(b), needs, plan >= 0, scale(k), b.mEUt, time));
                } else if (catalystsA.containsAll(needs)) {
                    findings.add(new Finding(Kind.INHERENT, ai, label(b), needs, plan >= 0, "", b.mEUt, 0));
                } else {
                    own.add(new ChannelProblem.Hijack(needs, fluidHits.contains(b), plan));
                    findings.add(new Finding(Kind.CONFLICT, ai, label(b), needs, plan >= 0, "", b.mEUt, 0));
                }
            }

            final boolean hasItems = !consumed.isEmpty() || !catalystsA.isEmpty();
            final int distinctFluids = (int) fluids(a).map(
                f -> f.getFluid()
                    .getName())
                .distinct()
                .count();
            recipes.add(new ChannelProblem.Recipe(catalystsA, distinctFluids, hasItems));
            hijacks.add(own);
            planRecipes.add(new ChannelReport.PlanRecipe(label(a), catalystsA, List.copyOf(entry.nodeIds())));
        }

        final ChannelProblem problem = new ChannelProblem(recipes, hijacks, KEY_ORDER);
        final Map<Mode, ChannelSolver.Solution> solutions = new EnumMap<>(Mode.class);
        for (final Mode mode : Mode.values()) solutions.put(mode, ChannelSolver.solve(problem, mode));
        return new ChannelReport.MachineReport(
            handler,
            machine,
            List.copyOf(planRecipes),
            Map.copyOf(names),
            Map.copyOf(solutions),
            List.copyOf(findings),
            new HashSet<>(catalystSets).size());
    }

    /** Circuits first, by number; other catalysts after, by key. */
    private static final Comparator<String> KEY_ORDER = Comparator.<String>comparingInt(k -> circuitNumber(k))
        .thenComparing(Comparator.naturalOrder());

    private static int circuitNumber(final String key) {
        if (!key.startsWith(CIRCUIT + ":")) return Integer.MAX_VALUE;
        try {
            return Integer.parseInt(key.substring(CIRCUIT.length() + 1));
        } catch (final NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    private static Set<GTRecipe> find(final Set<RecipeMap<?>> maps, final ItemStack[] items, final FluidStack[] fluids,
        final GTRecipe self, final boolean respectAmounts) {
        final Set<GTRecipe> out = Collections.newSetFromMap(new IdentityHashMap<>());
        for (final RecipeMap<?> map : maps) {
            map.findRecipeQuery()
                .items(items)
                .fluids(fluids)
                .dontCheckStackSizes(!respectAmounts)
                .findAll()
                .filter(r -> r != self && r.mEnabled && !r.mFakeRecipe)
                .forEach(out::add);
        }
        return out;
    }

    /**
     * The plan catalysts {@code b} needs present: its own catalysts, and any plan catalyst it consumes,
     * unless the victim's own consumed inputs already cover that input.
     */
    private static Set<String> needs(final GTRecipe b, final List<ItemStack> victimConsumed,
        final Map<String, ItemStack> universe) {
        final Set<String> needs = new HashSet<>();
        for (final ItemStack in : items(b)) {
            if (victimConsumed.stream()
                .anyMatch(v -> GTUtility.areStacksEqual(in, v))) continue;
            for (final Map.Entry<String, ItemStack> u : universe.entrySet()) {
                if (GTUtility.areStacksEqual(in, u.getValue())) {
                    needs.add(u.getKey());
                    break;
                }
            }
        }
        return Set.copyOf(needs);
    }

    /**
     * {num, den} if {@code b} is exactly num/den times {@code a}: the same consumed inputs and outputs
     * (with the same chances), every amount scaled by one factor. Null otherwise.
     */
    @Nullable
    static long[] exactMultiple(final GTRecipe a, final GTRecipe b) {
        final Map<String, Long> aIn = consumed(a), bIn = consumed(b);
        final Map<String, Long> aOut = outputs(a), bOut = outputs(b);
        if (aIn.isEmpty() || aOut.isEmpty()
            || !aIn.keySet()
                .equals(bIn.keySet())
            || !aOut.keySet()
                .equals(bOut.keySet())) {
            return null;
        }
        final String ref = aIn.keySet()
            .iterator()
            .next();
        final long num = bIn.get(ref), den = aIn.get(ref);
        if (num <= 0 || den <= 0 || !scaled(aIn, bIn, num, den) || !scaled(aOut, bOut, num, den)) return null;
        final long g = gcd(num, den);
        return new long[] { num / g, den / g };
    }

    /** Every amount in {@code b} is num/den of the same key's amount in {@code a}. */
    private static boolean scaled(final Map<String, Long> a, final Map<String, Long> b, final long num,
        final long den) {
        for (final Map.Entry<String, Long> e : a.entrySet()) {
            if (b.get(e.getKey()) * den != num * e.getValue()) return false;
        }
        return true;
    }

    private static long gcd(final long a, final long b) {
        return b == 0 ? a : gcd(b, a % b);
    }

    private static String scale(final long[] k) {
        return k[1] == 1 ? String.valueOf(k[0]) : k[0] + "/" + k[1];
    }

    private static Map<String, Long> consumed(final GTRecipe r) {
        final Map<String, Long> out = new HashMap<>();
        for (final ItemStack s : items(r)) if (s.stackSize > 0) out.merge(key(s), (long) s.stackSize, Long::sum);
        fluids(r).forEach(
            f -> out.merge(
                "fluid:" + f.getFluid()
                    .getName(),
                (long) f.amount,
                Long::sum));
        return out;
    }

    private static Map<String, Long> outputs(final GTRecipe r) {
        final Map<String, Long> out = new HashMap<>();
        if (r.mOutputs != null) {
            for (int i = 0; i < r.mOutputs.length; i++) {
                final ItemStack s = r.mOutputs[i];
                if (s == null || s.getItem() == null) continue;
                out.merge(key(s) + "@" + chance(r.mOutputChances, i), (long) s.stackSize, Long::sum);
            }
        }
        if (r.mFluidOutputs != null) {
            for (int i = 0; i < r.mFluidOutputs.length; i++) {
                final FluidStack f = r.mFluidOutputs[i];
                if (f == null || f.getFluid() == null) continue;
                out.merge(
                    "fluid:" + f.getFluid()
                        .getName() + "@" + chance(r.mFluidOutputChances, i),
                    (long) f.amount,
                    Long::sum);
            }
        }
        return out;
    }

    private static int chance(final int[] chances, final int i) {
        return chances != null && i < chances.length ? chances[i] : FULL_CHANCE;
    }

    private static List<ItemStack> items(final GTRecipe r) {
        final List<ItemStack> out = new ArrayList<>();
        if (r.mInputs != null) for (final ItemStack s : r.mInputs) if (s != null && s.getItem() != null) out.add(s);
        return out;
    }

    private static Stream<FluidStack> fluids(final GTRecipe r) {
        return r.mFluidInputs == null ? Stream.empty()
            : Stream.of(r.mFluidInputs)
                .filter(f -> f != null && f.getFluid() != null);
    }

    private static String key(final ItemStack s) {
        final String id = Item.itemRegistry.getNameForObject(s.getItem());
        final String base = id + ":" + s.getItemDamage();
        return s.hasTagCompound() ? base + s.getTagCompound() : base;
    }

    private static String catalystName(final ItemStack s) {
        final String id = Item.itemRegistry.getNameForObject(s.getItem());
        if (CIRCUIT.equals(id)) return "#" + s.getItemDamage();
        try {
            return s.getDisplayName();
        } catch (final RuntimeException e) {
            return id + ":" + s.getItemDamage();
        }
    }

    /** "Nitric Acid, Nitric Oxide": up to two outputs, which is how players tell recipes apart. */
    private static String label(final GTRecipe r) {
        final List<String> names = new ArrayList<>();
        if (r.mOutputs != null) for (final ItemStack s : r.mOutputs) {
            if (s != null && s.getItem() != null) names.add(displayName(s));
        }
        if (r.mFluidOutputs != null) for (final FluidStack f : r.mFluidOutputs) {
            if (f != null && f.getFluid() != null) names.add(f.getLocalizedName());
        }
        if (names.isEmpty()) return "?";
        final String head = String.join(", ", names.subList(0, Math.min(2, names.size())));
        return names.size() > 2 ? head + ", ..." : head;
    }

    private static String displayName(final ItemStack s) {
        try {
            return s.getDisplayName();
        } catch (final RuntimeException e) {
            return String.valueOf(s);
        }
    }

    @Nullable
    private static GTRecipe recipeOf(final Node node) {
        final TemplateRecipeHandler.CachedRecipe cached = cachedOf(node);
        return cached instanceof final CachedDefaultRecipe gt ? gt.mRecipe : null;
    }

    @Nullable
    private static RecipeMap<?> mapOf(final Node node) {
        if (node.recipeId == null) return null;
        final RecipeHandlerRef ref = RecipeHandlerRef.of(node.recipeId);
        return ref != null && ref.handler instanceof final GTNEIDefaultHandler gth ? gth.getRecipeMap() : null;
    }

    @Nullable
    private static TemplateRecipeHandler.CachedRecipe cachedOf(final Node node) {
        if (node.recipeId == null) return null;
        final RecipeHandlerRef ref = RecipeHandlerRef.of(node.recipeId);
        if (ref == null || !(ref.handler instanceof final GTNEIDefaultHandler gth)) return null;
        final List<TemplateRecipeHandler.CachedRecipe> recipes = RecipeHandlerAccess.getArecipes(gth);
        return ref.recipeIndex >= 0 && ref.recipeIndex < recipes.size() ? recipes.get(ref.recipeIndex) : null;
    }
}
