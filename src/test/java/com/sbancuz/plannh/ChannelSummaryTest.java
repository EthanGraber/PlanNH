package com.sbancuz.plannh;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.sbancuz.plannh.data.channels.ChannelProblem;
import com.sbancuz.plannh.data.channels.ChannelReport;
import com.sbancuz.plannh.data.channels.ChannelSolver;
import com.sbancuz.plannh.data.channels.ChannelSolver.Mode;
import com.sbancuz.plannh.data.flowchart.Summary;
import com.sbancuz.plannh.data.flowchart.Summary.Section;

/** The summary-side plumbing of the channel analysis: section order and node badges. */
class ChannelSummaryTest {

    @Test
    void channelsSitBeforeMessagesByDefault() {
        final List<Section> order = sections(new Summary().getSectionOrder());
        assertEquals(order.indexOf(Section.MESSAGES) - 1, order.indexOf(Section.CHANNELS));
        assertEquals(Section.VALUES.length, order.size());
    }

    @Test
    void anOrderSavedBeforeChannelsKeepsItsArrangement() {
        // An older save: every section but CHANNELS, with HELP moved to the top
        final List<Integer> old = new ArrayList<>();
        old.add(Section.HELP.ordinal());
        for (final Section s : Section.VALUES) {
            if (s != Section.HELP && s != Section.CHANNELS) old.add(s.ordinal());
        }
        final Summary summary = new Summary();
        summary.setSectionOrder(
            old.stream()
                .mapToInt(Integer::intValue)
                .toArray());
        final List<Section> order = sections(summary.getSectionOrder());
        assertEquals(Section.HELP, order.getFirst());
        assertEquals(order.indexOf(Section.MESSAGES) - 1, order.indexOf(Section.CHANNELS));
    }

    @Test
    void badgesFollowTheChosenMode() {
        // #1 is hijacked through #2: apart without ordering, #1 first with it
        final List<ChannelProblem.Recipe> recipes = List
            .of(new ChannelProblem.Recipe(Set.of("#1"), 1, true), new ChannelProblem.Recipe(Set.of("#2"), 1, true));
        final List<List<ChannelProblem.Hijack>> hijacks = List
            .of(List.of(new ChannelProblem.Hijack(Set.of("#2"), true, -1)), List.of());
        final ChannelProblem problem = new ChannelProblem(recipes, hijacks, Comparator.naturalOrder());
        final Map<Mode, ChannelSolver.Solution> solutions = new EnumMap<>(Mode.class);
        for (final Mode m : Mode.values()) solutions.put(m, ChannelSolver.solve(problem, m));

        final UUID a = UUID.randomUUID(), b = UUID.randomUUID();
        final ChannelReport report = new ChannelReport(
            List.of(
                new ChannelReport.MachineReport(
                    "gt.recipe.largechemicalreactor",
                    "LCR",
                    List.of(
                        new ChannelReport.PlanRecipe("A", Set.of("#1"), List.of(a)),
                        new ChannelReport.PlanRecipe("B", Set.of("#2"), List.of(b))),
                    Map.of("#1", "#1", "#2", "#2"),
                    solutions,
                    List.of(),
                    2)));

        final Map<UUID, ChannelReport.Badge> none = report.badges(Mode.NONE);
        assertEquals(new ChannelReport.Badge(0, "1"), none.get(a));
        assertEquals(new ChannelReport.Badge(1, "2"), none.get(b));

        final Map<UUID, ChannelReport.Badge> circuit = report.badges(Mode.CIRCUIT);
        assertEquals(new ChannelReport.Badge(0, "1"), circuit.get(a));
        assertEquals(new ChannelReport.Badge(0, "2"), circuit.get(b));

        // Color mode: one dye per bus, in check order
        final Map<UUID, ChannelReport.Badge> color = report.badges(Mode.COLOR);
        assertEquals(new ChannelReport.Badge(0, "1"), color.get(a));
        assertEquals(new ChannelReport.Badge(1, "2"), color.get(b));
    }

    @Test
    void noBadgeWhileTheAnalysisIsOff() {
        assertNull(new Summary().channelBadge(UUID.randomUUID()));
    }

    private static List<Section> sections(final int[] ordinals) {
        return Arrays.stream(ordinals)
            .mapToObj(i -> Section.VALUES[i])
            .toList();
    }
}
