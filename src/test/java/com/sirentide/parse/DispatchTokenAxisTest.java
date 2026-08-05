package com.sirentide.parse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// The TOKEN axis of alias completeness (plan 8a991947, closing Fixpoint's carry-forward at
/// sirentide/847).
///
/// WHY THIS EXISTS, and it is the sharpest review finding I have received: the sibling
/// {@code DiagramTypeAliasCompletenessTest} pins the table against the sealed {@code Diagram}
/// inventory — which is compiler-enforced and genuinely catches a new diagram TYPE. But Fixpoint
/// mutated `case "flowchart"` to `case "flowchart", "flowchart-v2"` — one new dispatchable TOKEN
/// on an EXISTING type, no sealed change — and the ENTIRE 1071-test suite stayed green while
/// `Flowchart-V2` rendered a blank SVG at exit 0.
///
/// THAT IS EXACTLY HOW THE ORIGINAL DEFECT AROSE. `stateDiagram-v2` was never a new diagram type;
/// `state` already existed and already worked. It was a new SPELLING for an existing type, and
/// its absence from the table is what made it blank. So the type-axis detector, alone, WOULD NOT
/// HAVE CAUGHT THE DEFECT THAT MOTIVATED IT — an axis mismatch: the inventory axis was TYPES, the
/// defect's axis is TOKENS.
///
/// So this test connects the alias table to the DISPATCH SWITCH ITSELF, which is the actual
/// source of truth for what is dispatchable. Nothing else in the chain did.
///
/// ON READING SOURCE FROM A TEST: I rejected this for the type-axis test because a whole-file
/// grep for `case "…"` also catches unrelated config-directive switches (title, theme, direction)
/// — 36 tokens of which 13 are not diagram types. The fix is not to abandon the approach but to
/// SCOPE it: the extraction below is bounded to the single `switch (type)` block by its exact
/// opening and closing lines, and {@link #theExtractorItselfIsProvenToWork} fails if that scoping
/// ever stops finding a plausible switch — because an extractor that silently returns nothing
/// would make every assertion below vacuously true.
class DispatchTokenAxisTest {

    private static final Path PARSER_SOURCE =
        Path.of("src/main/java/com/sirentide/parse/DslParser.java");

    /// `case "a", "b" ->` — the label list of one switch arm.
    private static final Pattern CASE_ARM = Pattern.compile("^\\s*case\\s+(\"[^\"]+\"(?:\\s*,\\s*\"[^\"]+\")*)\\s*->");

    /// Every token the DIAGRAM-TYPE switch dispatches on, extracted from the bounded block only.
    private static TreeSet<String> dispatchTokens() throws IOException {
        List<String> lines = Files.readAllLines(PARSER_SOURCE);
        TreeSet<String> tokens = new TreeSet<>();
        boolean inside = false;
        for (String line : lines) {
            if (!inside) {
                if (line.contains("return switch (type) {")) {
                    inside = true;
                }
                continue;
            }
            if (line.contains("default -> new Empty();")) {
                break;                                  // end of the diagram-type switch
            }
            Matcher m = CASE_ARM.matcher(line);
            if (m.find()) {
                for (String quoted : m.group(1).split("\\s*,\\s*")) {
                    tokens.add(quoted.replace("\"", "").strip());
                }
            }
        }
        return tokens;
    }

    @Test
    void theExtractorItselfIsProvenToWork() throws IOException {
        // A POSITIVE CONTROL ON THE INSTRUMENT. If the source moves, the markers change, or the
        // regex stops matching, dispatchTokens() returns an EMPTY set and every other assertion
        // in this class passes vacuously — a silent false-clean, which is the exact failure class
        // this plan is about. So the extractor must prove it found a real switch first.
        TreeSet<String> tokens = dispatchTokens();
        assertTrue(tokens.size() >= 20,
            "the dispatch-switch extractor found only " + tokens.size() + " tokens (" + tokens
                + ") — it has lost its anchors, and every assertion in this class is now vacuous");
        assertTrue(tokens.contains("flowchart") && tokens.contains("classDiagram"),
            "extractor missed known dispatch tokens: " + tokens);
        assertTrue(!tokens.contains("title") && !tokens.contains("theme"),
            "extractor leaked config-directive labels from a NEIGHBOURING switch — the scoping "
                + "is broken and the assertions below would be measuring the wrong corpus: "
                + tokens);
    }

    @Test
    void everyDispatchableTokenIsInTheAliasTable() throws IOException {
        // THE CARRY-FORWARD. A token wired into the switch but absent from the table still
        // dispatches when spelled exactly, and renders a BLANK SVG at exit 0 on any other
        // casing. That is the original stateDiagram-v2 defect, and only this assertion sees it.
        TreeSet<String> missing = new TreeSet<>();
        for (String token : dispatchTokens()) {
            if (!DslParser.DIAGRAM_TYPE_ALIASES.containsValue(token)
                    && !DslParser.DIAGRAM_TYPE_ALIASES.containsKey(token.toLowerCase(Locale.ROOT))) {
                missing.add(token);
            }
        }
        assertEquals(new TreeSet<String>(), missing,
            "dispatchable token(s) absent from DIAGRAM_TYPE_ALIASES — each dispatches only when "
                + "spelled EXACTLY and renders a blank SVG at exit 0 on any other casing");
    }

    @Test
    void everyDispatchableTokenResolvesCaseInsensitively() throws IOException {
        // The property, asserted against the switch's own token set rather than a hand-written
        // list — so a token added to the switch is covered the moment it appears.
        for (String token : dispatchTokens()) {
            String upper = token.toUpperCase(Locale.ROOT);
            assertTrue(DslParser.DIAGRAM_TYPE_ALIASES.containsKey(upper.toLowerCase(Locale.ROOT)),
                "dispatch token \"" + token + "\" has no case-folded table entry, so \"" + upper
                    + "\" renders a blank SVG at exit 0");
        }
    }
}
