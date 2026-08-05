package com.sirentide.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.ir.Diagram;
import com.sirentide.ir.Empty;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;

/// COMPLETENESS of the header-alias table against the IR type inventory (plan 8a991947 slice 2,
/// adopting Fixpoint's recommendation at sirentide/843).
///
/// THE DRIFT THIS EXISTS TO CATCH, in his words: the alias table is deliberately ENUMERATED
/// rather than derived from the dispatch switch's labels — enumeration keeps the table decoupled
/// from the switch's internals, which is the right call — but it means a NEW diagram type added
/// without a table entry silently becomes case-SENSITIVE again, and nothing fails. Slice 1
/// shipped with that risk real and unguarded.
///
/// His resolution is the standard one for enumerate-versus-derive and is better than either pole:
/// keep the enumeration for decoupling, and pin completeness with a test that goes RED on drift,
/// so the coupling lives in a TEST rather than in production code.
///
/// WHY THIS KEYS ON THE SEALED INTERFACE. {@link Diagram} is `sealed ... permits`, so its member
/// list is COMPILER-ENFORCED — a new diagram type cannot join the IR without appearing in
/// `getPermittedSubclasses()`. That makes it the one inventory in this codebase that cannot
/// silently grow. Scraping the switch's source text would be fragile (several unrelated switches
/// in the same file use `case "…"` for config directives — `title`, `theme`, `direction` — so a
/// naive scrape reports labels that are not diagram types at all; I checked, and it does).
class DiagramTypeAliasCompletenessTest {

    /// The canonical dispatch token for each permitted IR type. Hand-written ON PURPOSE — but it
    /// CANNOT silently rot, because {@link #everyPermittedDiagramTypeHasACanonicalToken} derives
    /// the required key set from the sealed interface. Add a type to `permits` without adding a
    /// row here and this suite fails, naming the missing type.
    /// Values are LISTS because the dispatch switch has MULTI-TOKEN arms — `case "matrix",
    /// "comparison"` and `case "sankey", "sankey-beta"` — where BOTH tokens are canonical switch
    /// labels rather than one being an alias of the other.
    ///
    /// I did not model that at first, and this suite caught me: the fourth test failed naming
    /// `comparison` and `sankey-beta` as strays. They are not strays; my map was too simple. That
    /// is a control failing FOR ITS OWN REASONS rather than finding a defect, and the honest
    /// resolution was to fix the model, not to widen the assertion until it passed.
    private static final Map<String, List<String>> CANONICAL_TOKENS_BY_IR_TYPE = Map.ofEntries(
        Map.entry("Pie", List.of("pie")),
        Map.entry("XyChart", List.of("xychart")),
        Map.entry("Timeline", List.of("timeline")),
        Map.entry("Gantt", List.of("gantt")),
        Map.entry("Flowchart", List.of("flowchart")),
        Map.entry("Sequence", List.of("sequence")),
        Map.entry("StateDiagram", List.of("state")),
        Map.entry("QuadrantChart", List.of("quadrant")),
        Map.entry("ClassDiagram", List.of("classDiagram")),
        Map.entry("ErDiagram", List.of("erDiagram")),
        Map.entry("MathBlock", List.of("mathblock")),
        Map.entry("GitGraph", List.of("gitGraph")),
        Map.entry("Journey", List.of("journey")),
        Map.entry("Mindmap", List.of("mindmap")),
        Map.entry("Sankey", List.of("sankey", "sankey-beta")),
        Map.entry("Matrix", List.of("matrix", "comparison")),
        Map.entry("Heatmap", List.of("heatmap")),
        Map.entry("Snake", List.of("snake")),
        Map.entry("TensorNetwork", List.of("tensornetwork")),
        Map.entry("YoungDiagram", List.of("young")),
        Map.entry("Knot", List.of("knot")),
        Map.entry("Dynkin", List.of("dynkin")),
        Map.entry("RootSystem", List.of("rootsystem")));

    private static TreeSet<String> allCanonicalTokens() {
        TreeSet<String> all = new TreeSet<>();
        CANONICAL_TOKENS_BY_IR_TYPE.values().forEach(all::addAll);
        return all;
    }

    private static List<String> permittedDiagramTypeNames() {
        List<String> names = new ArrayList<>();
        for (Class<?> c : Diagram.class.getPermittedSubclasses()) {
            if (c != Empty.class) {           // Empty is the inert shell, not a dispatchable type
                names.add(c.getSimpleName());
            }
        }
        return names;
    }

    @Test
    void everyPermittedDiagramTypeHasACanonicalToken() {
        // THE DRIFT DETECTOR. The sealed list is the authority; this map must keep up with it.
        assertEquals(new TreeSet<>(permittedDiagramTypeNames()),
            new TreeSet<>(CANONICAL_TOKENS_BY_IR_TYPE.keySet()),
            "a diagram type joined (or left) Diagram's permits without updating this test's "
                + "token map — add the row, then confirm the alias table covers it too");
    }

    @Test
    void everyCanonicalTokenIsPresentInTheAliasTable() {
        // The table's VALUES must cover every dispatchable type. A type whose token is absent
        // from the table is exactly the silent case-sensitivity regression this suite exists for:
        // it still dispatches when spelled canonically, and blanks on any other casing.
        List<String> missing = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : new TreeMap<>(CANONICAL_TOKENS_BY_IR_TYPE).entrySet()) {
            for (String token : e.getValue()) {
                if (!DslParser.DIAGRAM_TYPE_ALIASES.containsValue(token)) {
                    missing.add(e.getKey() + " -> \"" + token + "\"");
                }
            }
        }
        assertTrue(missing.isEmpty(),
            "dispatchable types absent from DIAGRAM_TYPE_ALIASES (they will silently become "
                + "case-sensitive): " + missing);
    }

    @Test
    void everyCanonicalTokenResolvesCaseInsensitively() {
        // The PROPERTY the table exists to provide, asserted per type rather than spot-checked.
        for (Map.Entry<String, List<String>> e : new TreeMap<>(CANONICAL_TOKENS_BY_IR_TYPE).entrySet()) {
            for (String token : e.getValue()) {
                for (String spelling : List.of(token,
                                               token.toUpperCase(Locale.ROOT),
                                               token.toLowerCase(Locale.ROOT))) {
                    assertEquals(token, DslParser.canonicalDiagramType(spelling),
                        e.getKey() + ": spelling \"" + spelling + "\" must canonicalise to \""
                            + token + "\" — a casing that misses the table renders a BLANK svg "
                            + "at exit 0");
                }
            }
        }
    }

    @Test
    void everyAliasTableValueIsItselfADispatchableToken() {
        // The other direction: a table VALUE that is not a real canonical token would silently
        // route a valid-looking header to `default` (blank). Fixpoint audited all 19 entries by
        // hand at sirentide/843; this makes that audit executable so it does not have to be
        // repeated by hand next time.
        TreeSet<String> canonical = allCanonicalTokens();
        List<String> strays = new ArrayList<>();
        for (String value : new TreeSet<>(DslParser.DIAGRAM_TYPE_ALIASES.values())) {
            if (!canonical.contains(value)) {
                strays.add(value);
            }
        }
        assertTrue(strays.isEmpty(),
            "alias table maps to token(s) no diagram type claims — these route to the inert "
                + "shell: " + strays);
    }

    @Test
    void aTurkishLocaleDefaultCannotBreakTheLookup() {
        // Locale.ROOT in canonicalDiagramType is load-bearing and non-obvious: under a tr-TR
        // default locale, "I".toLowerCase() is a DOTLESS i, so `STATEDIAGRAM` would miss the
        // table. Fixpoint flagged it at sirentide/843 as the kind of correct choice someone
        // "simplifies" away later. This pins it so that simplification fails loudly.
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr-TR"));
            assertEquals("state", DslParser.canonicalDiagramType("STATEDIAGRAM"));
            assertEquals("classDiagram", DslParser.canonicalDiagramType("CLASSDIAGRAM"));
        } finally {
            Locale.setDefault(previous);
        }
    }
}
