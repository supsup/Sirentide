package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Sirentide;
import com.sirentide.ir.Diagram;
import com.sirentide.ir.Journey;
import com.sirentide.ir.JourneySection;
import com.sirentide.ir.JourneyTask;
import com.sirentide.parse.DslParser;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Sirentide's thirteenth diagram type: a `journey` parses into a title + ordered {@link JourneySection}
/// list (each with ordered {@link JourneyTask}s), and NEVER throws on malformed input (a non-numeric
/// score, a task before any section, an out-of-range score, an empty body). The score→y / section /
/// line GEOMETRY is proven separately in {@link com.sirentide.layout.JourneyLayoutTest}.
class JourneyTest {

    private static Journey parse(String dsl) {
        Diagram d = DslParser.parse(dsl);
        return assertInstanceOf(Journey.class, d);
    }

    @Test
    void parsesTitleSectionsAndTasks() {
        Journey j = parse("journey\n  title My working day\n  section Go to work\n    Make tea: 5: Me\n"
            + "    Commute: 3: Me, Cat\n  section Do work\n    Code: 5: Me\n    Meetings: 2: Me, Boss\n");
        assertEquals("My working day", j.title());
        assertEquals(2, j.sections().size(), "two sections");
        JourneySection go = j.sections().get(0);
        assertEquals("Go to work", go.name());
        assertEquals(2, go.tasks().size());
        assertEquals("Make tea", go.tasks().get(0).name());
        assertEquals(5, go.tasks().get(0).score());
        assertEquals(List.of("Me"), go.tasks().get(0).actors());
    }

    @Test
    void aTaskParsesMultipleActors() {
        JourneyTask commute = parse("journey\n  section S\n    Commute: 3: Me, Cat, Dog\n")
            .sections().get(0).tasks().get(0);
        assertEquals(3, commute.score());
        assertEquals(List.of("Me", "Cat", "Dog"), commute.actors(), "comma-split actors");
    }

    @Test
    void aTaskWithNoActorsKeepsAnEmptyActorList() {
        // `Solo: 4` — a score but no second colon → a valid task with no actors (documented default).
        JourneyTask solo = parse("journey\n  section S\n    Solo: 4\n").sections().get(0).tasks().get(0);
        assertEquals(4, solo.score());
        assertTrue(solo.actors().isEmpty(), "a scoreful actorless task keeps an empty actor list");
    }

    @Test
    void titleIsOptional() {
        Journey j = parse("journey\n  section S\n    Task: 3: Me\n");
        assertEquals(null, j.title(), "no title line → null title");
        assertEquals(1, j.sections().size());
    }

    @Test
    void outOfRangeScoreIsClampedIntoOneToFive() {
        List<JourneyTask> t = parse("journey\n  section S\n    High: 9: Me\n    Low: 0: Me\n"
            + "    Neg: -4: Me\n    Frac: 4.7: Me\n").sections().get(0).tasks();
        assertEquals(5, t.get(0).score(), "9 clamps to 5");
        assertEquals(1, t.get(1).score(), "0 clamps to 1");
        assertEquals(1, t.get(2).score(), "-4 clamps to 1");
        assertEquals(5, t.get(3).score(), "4.7 rounds to 5 (still in range)");
    }

    @Test
    void aTaskWithNoNumericScoreIsDroppedNotThrown() {
        // `Bad: nope: Me` has no numeric score → dropped; the valid task survives.
        List<JourneyTask> t = parse("journey\n  section S\n    Good: 4: Me\n    Bad: nope: Me\n"
            + "    NoColonHere\n").sections().get(0).tasks();
        assertEquals(1, t.size(), "only the well-formed task survives: " + t);
        assertEquals("Good", t.get(0).name());
    }

    @Test
    void aTaskBeforeAnySectionIsDropped() {
        // `Orphan: 4: Me` appears before any `section` → dropped (it has no group to join).
        Journey j = parse("journey\n  Orphan: 4: Me\n  section S\n    Real: 3: Me\n");
        assertEquals(1, j.sections().size());
        assertEquals(1, j.sections().get(0).tasks().size(), "the orphan task dropped: " + j.sections());
        assertEquals("Real", j.sections().get(0).tasks().get(0).name());
    }

    @Test
    void emptyBodyRoundTripsAsAJourneyAndBakesToARealCanvas() {
        Journey j = parse("journey\n");
        assertTrue(j.sections().isEmpty());
        String svg = Sirentide.render("journey\n");
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"), "well-formed");
        assertFalse(svg.contains("width=\"0\" height=\"0\""), "a real inert canvas, not the 0x0 shell: " + svg);
    }

    @Test
    void malformedJourneyNeverThrowsAndBakesReal() {
        String dsl = "journey\n  title X\n  Orphan: 4: Me\n  section Go\n    Make tea: 9: Me\n"
            + "    : 3: Me\n    Bad: nope\n    Commute: 3: Me, Cat\n";
        String svg = Sirentide.render(dsl);
        assertTrue(svg.startsWith("<svg") && svg.endsWith("</svg>"), "well-formed");
        assertFalse(svg.contains("width=\"0\" height=\"0\""), "a real render, not the inert shell: " + svg);
    }

    @Test
    void renderIsDeterministic() {
        String dsl = "journey\n  title Day\n  section Go\n    Make tea: 5: Me\n    Commute: 3: Me, Cat\n";
        assertEquals(Sirentide.render(dsl), Sirentide.render(dsl));
    }

    @Test
    void a11yDescReflectsSectionsTasksAndScores() {
        String svg = Sirentide.render("journey\n  title My working day\n  section Go to work\n"
            + "    Make tea: 5: Me\n    Commute: 3: Me, Cat\n  section Do work\n    Code: 5: Me\n");
        int t = svg.indexOf("<desc>");
        int e = svg.indexOf("</desc>");
        assertTrue(t >= 0 && e > t, "a <desc> is baked: " + svg);
        String desc = svg.substring(t + 6, e);
        assertFalse(desc.isBlank(), "the desc is non-empty");
        assertTrue(desc.contains("User journey") && desc.contains("My working day"),
            "the desc names the journey + title: " + desc);
        assertTrue(desc.contains("Go to work") && desc.contains("Do work"),
            "the desc names the sections: " + desc);
        assertTrue(desc.contains("Make tea") && desc.contains("scored 5") && desc.contains("scored 3"),
            "the desc names tasks + scores: " + desc);
        assertTrue(desc.contains("Cat"), "the desc names a multi-actor task's actors: " + desc);
    }
}
