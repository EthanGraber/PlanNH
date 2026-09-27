package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.stream.IntStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.sbancuz.plannh.data.channels.ChannelProblem;
import com.sbancuz.plannh.data.channels.ChannelProblem.Hijack;
import com.sbancuz.plannh.data.channels.ChannelProblem.Recipe;
import com.sbancuz.plannh.data.channels.ChannelSolver;
import com.sbancuz.plannh.data.channels.ChannelSolver.Mode;
import com.sbancuz.plannh.data.channels.ChannelSolver.Solution;

/**
 * The channel solver on hand-built problems. Catalysts are "#n" for circuit n, anything else for other
 * catalysts; a hijack is "recipe i can be replaced by something needing these catalysts".
 */
class ChannelSolverTest {

    private static final Comparator<String> KEYS = Comparator
        .<String>comparingInt(k -> k.startsWith("#") ? Integer.parseInt(k.substring(1)) : Integer.MAX_VALUE)
        .thenComparing(Comparator.naturalOrder());

    /** Builder: recipes in order, hijacks attached to the last-added recipe or by index. */
    private static final class P {

        final List<Recipe> recipes = new ArrayList<>();
        final List<List<Hijack>> hijacks = new ArrayList<>();

        P recipe(final int fluids, final boolean bus, final String... catalysts) {
            recipes.add(new Recipe(Set.of(catalysts), fluids, bus || catalysts.length > 0));
            hijacks.add(new ArrayList<>());
            return this;
        }

        P circuit(final String c) {
            return recipe(1, true, c);
        }

        /** Recipe {@code victim} can be replaced by a non-plan fluid-only recipe needing {@code needs}. */
        P hijack(final int victim, final String... needs) {
            return hijack(victim, true, -1, needs);
        }

        P hijack(final int victim, final boolean fluidsOnly, final int planRecipe, final String... needs) {
            hijacks.get(victim)
                .add(new Hijack(Set.of(needs), fluidsOnly, planRecipe));
            return this;
        }

        ChannelProblem build() {
            return new ChannelProblem(recipes, hijacks, KEYS);
        }

        Map<Mode, Solution> solveAll() {
            final Map<Mode, Solution> out = new EnumMap<>(Mode.class);
            for (final Mode m : Mode.values()) out.put(m, ChannelSolver.solve(build(), m));
            return out;
        }
    }

    private static int[] counts(final Map<Mode, Solution> s) {
        return new int[] { s.get(Mode.NONE)
            .channels()
            .size(),
            s.get(Mode.CIRCUIT)
                .channels()
                .size(),
            s.get(Mode.COLOR)
                .channels()
                .size() };
    }

    private static List<Set<String>> order(final Solution s) {
        assertEquals(
            1,
            s.channels()
                .size());
        return s.channels()
            .getFirst()
            .checkOrder();
    }

    @Test
    void hijacksNeedingOnlyTheVictimsOwnCatalystsAreRejected() {
        final P p = new P().circuit("#1")
            .hijack(0, "#1");
        assertThrows(IllegalArgumentException.class, p::build);
    }

    @Test
    void recipesWithoutConflictsShareOneChannel() {
        final var s = new P().circuit("#1")
            .circuit("#2")
            .circuit("#3")
            .solveAll();
        assertArrayEquals(new int[] { 1, 1, 1 }, counts(s));
    }

    @Test
    void mutualConflictNeedsTwoChannelsInEveryMode() {
        final var s = new P().circuit("#1")
            .circuit("#2")
            .hijack(0, "#2")
            .hijack(1, "#1")
            .solveAll();
        assertArrayEquals(new int[] { 2, 2, 2 }, counts(s));
    }

    @Test
    void oneWayConflictIsResolvedByOrder() {
        final var s = new P().circuit("#1")
            .circuit("#2")
            .hijack(0, "#2")
            .solveAll();
        assertArrayEquals(new int[] { 2, 1, 1 }, counts(s));
        assertEquals(List.of(Set.of("#1"), Set.of("#2")), order(s.get(Mode.CIRCUIT)));
    }

    @Test
    void orderPutsTheVictimFirst() {
        final var s = new P().circuit("#1")
            .circuit("#2")
            .hijack(1, "#1")
            .solveAll();
        assertEquals(List.of(Set.of("#2"), Set.of("#1")), order(s.get(Mode.COLOR)));
    }

    @Test
    void directedCycleOfThree() {
        final var s = new P().circuit("#1")
            .circuit("#2")
            .circuit("#3")
            .hijack(0, "#2")
            .hijack(1, "#3")
            .hijack(2, "#1")
            .solveAll();
        assertArrayEquals(new int[] { 3, 2, 2 }, counts(s));
    }

    @Test
    void circuitModeCannotOrderBelowAPlanRecipe() {
        // Recipe 1 hijacks recipe 0 and is in the plan: it runs, gets cached, and is retried first
        final var s = new P().circuit("#1")
            .circuit("#2")
            .hijack(0, true, 1, "#2")
            .solveAll();
        assertArrayEquals(new int[] { 2, 2, 1 }, counts(s));
    }

    @Test
    void aPlanRecipeInAnotherChannelIsNotCached() {
        // Recipe 2 hijacks recipe 0, but is kept apart from it by a mutual conflict with recipe 0's catalysts
        final var s = new P().circuit("#1")
            .circuit("#2")
            .circuit("#3")
            .hijack(0, true, 2, "#3")
            .hijack(2, "#1")
            .solveAll();
        assertEquals(
            2,
            s.get(Mode.CIRCUIT)
                .channels()
                .size());
    }

    @Test
    void orderCannotProtectCircuitlessRecipes() {
        final var s = new P().recipe(2, false)
            .circuit("#2")
            .hijack(0, "#2")
            .solveAll();
        assertArrayEquals(new int[] { 2, 2, 2 }, counts(s));
    }

    @Test
    void circuitlessIsCheckedLast() {
        final var s = new P().recipe(2, true)
            .circuit("#2")
            .circuit("#1")
            .hijack(2, "#2")
            .solveAll();
        assertEquals(List.of(Set.of("#1"), Set.of("#2"), Set.of()), order(s.get(Mode.CIRCUIT)));
        assertEquals(List.of(Set.of("#1"), Set.of("#2"), Set.of()), order(s.get(Mode.COLOR)));
    }

    @Test
    void colorModeHidesItemsInOtherBuses() {
        // 0 is hijacked through its items (invisible across colors), 1 through fluids
        final var s = new P().circuit("#9")
            .circuit("#1")
            .hijack(0, false, -1, "#1")
            .hijack(1, "#9")
            .solveAll();
        assertArrayEquals(new int[] { 2, 2, 1 }, counts(s));
        assertEquals(List.of(Set.of("#1"), Set.of("#9")), order(s.get(Mode.COLOR)));
    }

    @Test
    void hijackerNeedingTwoBusesCannotRunInColorMode() {
        final var s = new P().circuit("#1")
            .recipe(1, true, "lens")
            .circuit("#2")
            .hijack(0, "#2", "lens")
            .solveAll();
        assertArrayEquals(new int[] { 2, 2, 1 }, counts(s));
    }

    @Test
    void colorModeHasSixteenColors() {
        final P p = new P();
        for (int i = 1; i <= 17; i++) p.circuit("#" + i);
        final var s = p.solveAll();
        assertArrayEquals(new int[] { 1, 1, 2 }, counts(s));
        assertEquals(
            2,
            s.get(Mode.COLOR)
                .machines());
    }

    @ParameterizedTest
    @CsvSource({ "0,0,0", "1,0,1", "2,1,0", "3,1,0", "4,1,0", "5,1,1", "6,2,0", "8,2,0", "9,2,1" })
    void fluidHatches(final int fluids, final int quad, final int normal) {
        assertArrayEquals(new int[] { quad, normal }, ChannelSolver.fluidHatches(fluids));
    }

    @Test
    void partsPerMode() {
        // Circuitless fluid-only, #1 with five fluids, #2 with an item
        final var s = new P().recipe(2, false)
            .recipe(5, true, "#1")
            .recipe(1, true, "#2")
            .solveAll();
        assertEquals(
            new ChannelSolver.Parts(1, 1, 1),
            s.get(Mode.NONE)
                .total());
        // The fluid-only circuitless recipe needs no bus of its own
        assertEquals(
            new ChannelSolver.Parts(2, 1, 1),
            s.get(Mode.COLOR)
                .total());
    }

    @Test
    void fluidOnlyCircuitlessChannelNeedsNoBus() {
        final var s = new P().recipe(3, false)
            .solveAll();
        assertEquals(
            new ChannelSolver.Parts(0, 1, 0),
            s.get(Mode.NONE)
                .total());
    }

    @Test
    void tiesAreBrokenByFewestBlocks() {
        // 0 and 2 conflict; 1 fits with either. Pairing the two five-fluid recipes saves a block.
        final var s = new P().recipe(5, true, "#1")
            .recipe(5, true, "#2")
            .recipe(1, true, "#3")
            .hijack(0, "#3")
            .solveAll();
        final Solution none = s.get(Mode.NONE);
        assertEquals(
            2,
            none.channels()
                .size());
        assertEquals(
            5,
            none.total()
                .blocks());
        assertEquals(
            List.of(0, 1),
            none.channels()
                .getFirst()
                .members());
    }

    @Test
    void isolatedChannelsShareAMachineAsColors() {
        final var s = new P().circuit("#1")
            .circuit("#2")
            .hijack(0, "#2")
            .solveAll();
        assertEquals(
            1,
            s.get(Mode.NONE)
                .machines());
    }

    @Test
    void searchLimitsAreReported() {
        final P p = new P();
        for (int i = 1; i <= 17; i++) p.circuit("#" + i);
        // Ten nodes stops pass 1 early; its first-fit start is already the minimum here
        final Solution limited = ChannelSolver.solve(p.build(), Mode.COLOR, new ChannelSolver.Limits(10_000, 10));
        assertEquals(
            2,
            limited.channels()
                .size());
        assertFalse(limited.channelsMinimal());
        assertFalse(limited.blocksMinimal());
        final Solution full = ChannelSolver.solve(p.build(), Mode.COLOR);
        assertTrue(full.channelsMinimal());
        assertTrue(full.blocksMinimal());
    }

    @Test
    void aStoppedSearchStillReturnsSoundChannels() {
        final ChannelProblem problem = randomProblem(24, 30);
        for (final Mode mode : Mode.values()) {
            final Solution s = ChannelSolver.solve(problem, mode, new ChannelSolver.Limits(10_000, 1));
            assertFalse(s.channelsMinimal());
            assertSound(problem, s);
        }
    }

    @Test
    void anExpiredClockStopsBothPasses() {
        final ChannelProblem problem = randomProblem(24, 30);
        final Solution s = ChannelSolver.solve(problem, Mode.CIRCUIT, new ChannelSolver.Limits(0, Integer.MAX_VALUE));
        assertFalse(s.channelsMinimal());
        assertFalse(s.blocksMinimal());
        assertSound(problem, s);
    }

    /** Every recipe placed exactly once, in channels the mode allows. */
    private static void assertSound(final ChannelProblem problem, final Solution s) {
        final List<Integer> seen = new ArrayList<>();
        for (final ChannelSolver.Channel ch : s.channels()) {
            assertTrue(ChannelSolver.checkOrder(problem, ch.members(), s.mode()) != null);
            seen.addAll(ch.members());
        }
        seen.sort(Comparator.naturalOrder());
        assertEquals(
            IntStream.range(
                0,
                problem.recipes()
                    .size())
                .boxed()
                .toList(),
            seen);
    }

    /** {@code recipes} circuit recipes with {@code hijacks} random cross-circuit conflicts. */
    private static ChannelProblem randomProblem(final int recipes, final int hijacks) {
        final Random rng = new Random(0);
        final P p = new P();
        for (int i = 1; i <= recipes; i++) p.recipe(1 + rng.nextInt(6), true, "#" + i);
        for (int k = 0; k < hijacks; k++) {
            final int victim = rng.nextInt(recipes);
            final int needs = 1 + (victim + 1 + rng.nextInt(recipes - 1)) % recipes; // never the victim's own
            p.hijack(victim, rng.nextBoolean(), -1, "#" + needs);
        }
        return p.build();
    }

    @Test
    void manyRecipesStayFast() {
        final Random rng = new Random(0);
        final P p = new P();
        for (int i = 1; i <= 24; i++) p.recipe(1 + rng.nextInt(6), true, "#" + i);
        for (int k = 0; k < 30; k++) {
            final int victim = rng.nextInt(24);
            final int needs = 1 + (victim + 1 + rng.nextInt(23)) % 24; // any circuit but the victim's own
            p.hijack(victim, rng.nextBoolean(), -1, "#" + needs);
        }
        final long start = System.nanoTime();
        final var s = p.solveAll();
        assertTrue(System.nanoTime() - start < 20_000_000_000L);
        for (final Solution sol : s.values()) {
            assertFalse(
                sol.channels()
                    .isEmpty());
        }
    }
}
