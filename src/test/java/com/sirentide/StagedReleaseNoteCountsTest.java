package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.ir.Diagram;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// Guards the STAGED release note's diagram-type count against the sealed IR inventory
/// (plan 398daca1, item 3).
///
/// WHY THIS EXISTS. `RELEASE-NOTE-ENTRY.md` claimed "all 25 diagram types"; the sealed
/// {@code Diagram} interface permits 23 real records plus {@code Empty}, and the README
/// independently says twenty-three. The file is staged to be merged VERBATIM into
/// `RELEASE_NOTES`, so the wrong number would have become a shipped inaccuracy the moment
/// it landed — a confident false statement a reader has no way to distinguish from a
/// current one.
///
/// WHY IT GUARDS ONLY THE STAGED FILE, which is the design decision worth stating: a live
/// invariant must NOT be pointed at `RELEASE_NOTES`. Shipped release notes are point-in-time
/// records — "23 types at 0.6.0" stays true about 0.6.0 forever, and a test that forced
/// historical entries to track the current count would be actively wrong, rewriting history
/// to match HEAD. `RELEASE-NOTE-ENTRY.md` is the one file that must be true RIGHT NOW,
/// because it has not shipped yet; it leaves this filename when it merges, at which point
/// this guard correctly stops applying to it.
///
/// SCOPE, stated rather than implied: this matches DIGIT counts only. The README's
/// "twenty-three diagram types" is spelled in words and is NOT covered here. I am not
/// closing the class of stale doc counts — I am closing the one file with a merge deadline,
/// and saying so, because a guard described as broader than it is would be the same defect
/// this plan is about.
///
/// AND THE OTHER NUMBER IS GONE, NOT GUARDED. The same release-note sentence claimed "43
/// layout classes". I could not derive 43 from anything — `layout/` holds 46 files and the
/// per-diagram `*Layout` classes number 23 — so it was DESTATED to "every layout class"
/// rather than restated. That is defensible (the budget is charged at the shared dispatch
/// seam, so universal coverage is the real property) but it is not the same outcome as the
/// type count: one number is now pinned by a test, the other simply no longer exists and
/// nothing here would notice if a future edit reintroduced a wrong one. Recorded because a
/// reader who sees this guard could reasonably assume the whole sentence is covered.
/// (Fixpoint asked for this to be said out loud — sirentide/868.)
class StagedReleaseNoteCountsTest {

    private static final Path STAGED_NOTE = Path.of("RELEASE-NOTE-ENTRY.md");

    /// `25 diagram types`, `all 23 diagram types` — the numeric form only.
    private static final Pattern TYPE_COUNT_CLAIM =
        Pattern.compile("(\\d+)\\s+diagram\\s+types");

    /// The real diagram types: every permitted subtype except the {@code Empty} degenerate,
    /// which is the inert shell a refused or unsupported input bakes to, not a diagram a user
    /// can author.
    private static int realDiagramTypeCount() {
        Class<?>[] permitted = Diagram.class.getPermittedSubclasses();
        int real = 0;
        for (Class<?> c : permitted) {
            if (!"Empty".equals(c.getSimpleName())) {
                real++;
            }
        }
        return real;
    }

    @Test
    void theSealedInventoryIsReadableAndPlausible() {
        // POSITIVE CONTROL ON THE INSTRUMENT. If getPermittedSubclasses() ever returns empty
        // — the interface stops being sealed, or reflection is restricted — realDiagramTypeCount()
        // returns 0 and the assertion below could pass vacuously against a doc with no claims.
        Class<?>[] permitted = Diagram.class.getPermittedSubclasses();
        assertTrue(permitted.length > 1,
            "Diagram is no longer a readable sealed hierarchy (" + permitted.length
                + " permitted) — every count assertion in this class is now meaningless");
        assertEquals(permitted.length - 1, realDiagramTypeCount(),
            "exactly one permitted subtype (Empty) must be excluded as the degenerate shell");
    }

    @Test
    void stagedReleaseNoteDiagramCountsMatchTheSealedInventory() throws IOException {
        if (!Files.exists(STAGED_NOTE)) {
            return; // already merged into RELEASE_NOTES; the guard has done its job.
        }
        String text = Files.readString(STAGED_NOTE, StandardCharsets.UTF_8);
        int expected = realDiagramTypeCount();
        List<String> wrong = new ArrayList<>();
        int claims = 0;
        for (Matcher m = TYPE_COUNT_CLAIM.matcher(text); m.find(); ) {
            claims++;
            if (Integer.parseInt(m.group(1)) != expected) {
                wrong.add(m.group(0));
            }
        }
        assertTrue(claims > 0,
            "no numeric \"N diagram types\" claim found in the staged note — either the claim was "
                + "reworded (fine, but this guard is now inert and should be retired) or the file "
                + "moved; an assertion over zero claims proves nothing");
        assertEquals(List.of(), wrong,
            "the STAGED release note asserts a diagram-type count the sealed IR contradicts "
                + "(sealed inventory says " + expected + "). This file merges VERBATIM into "
                + "RELEASE_NOTES, so a wrong number here ships as a permanent record.");
    }
}
