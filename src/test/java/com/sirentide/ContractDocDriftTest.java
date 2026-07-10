package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.contract.SirentideContract;
import com.sirentide.contract.SirentideRole;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// Build-failing drift guard between `docs/sirentide-container-contract.md` — the doc that
/// declares itself the human-readable contract — and the ACTUAL jar-exported constants
/// enforcement reads (plan sirentide-contract-doc-drift-closure; Lattice #6265: "doc
/// correction plus enum-backed drift test before Part 2"). The doc drifted once already
/// (an 11-role table vs the enum's 17, a phantom inner-element class row, present-tense fx)
/// and the enum-drift lesson is written INTO the doc itself — this test makes the lesson
/// structural instead of aspirational.
class ContractDocDriftTest {

    private static final Path DOC = Path.of("docs", "sirentide-container-contract.md");

    /// The doc's `data-sirentide-role` table row must list EXACTLY the enum's wire values,
    /// in enum declaration order — byte-aligned with what the sanitizer allow-list pins.
    @Test
    void roleTableRowMatchesTheWireEnumExactly() throws IOException {
        String row = docLineContaining("`data-sirentide-role`");
        List<String> documented = backtickedTokens(row.replaceFirst("^\\|[^|]*\\|", "|"));
        // Drop the leading cell's own token (the attribute name itself) if captured.
        documented.remove("data-sirentide-role");

        List<String> wire = new ArrayList<>();
        for (SirentideRole role : SirentideRole.values()) {
            wire.add(role.wire());
        }
        assertEquals(wire, documented,
            "the doc's role table must equal SirentideRole wire values, in declaration order — "
                + "if this fails, the ENUM moved: update the doc row (and the Stafficy S3 "
                + "sanitizer already follows automatically via WIRE_VALUES)");
    }

    /// The doc must carry BOTH seq bounds — the /docs wire bound (4-digit, what the emitter
    /// saturates to and the Stafficy sanitizer enforces) and the in-process contract bound —
    /// and the in-process one must match SirentideContract.ANCHOR_SEQ verbatim.
    @Test
    void seqRowDocumentsBothBoundsAndMatchesTheContract() throws IOException {
        String row = docLineContaining("`data-sirentide-seq`");
        assertTrue(row.contains("^[0-9]{1,4}$"), "the /docs wire bound (emitter-saturated)");
        assertTrue(row.contains("^[0-9]{1,9}$"), "the in-process contract bound");
        assertEquals("[0-9]{1,9}", SirentideContract.ANCHOR_SEQ.pattern(),
            "doc claims the in-process bound is {1,9}; keep this assertion aligned if the "
                + "contract pattern ever changes (and update the doc row with it)");
    }

    /// The doc's id row must match the contract pattern verbatim.
    @Test
    void idRowMatchesTheContractPattern() throws IOException {
        String row = docLineContaining("`data-sirentide-id`");
        assertTrue(row.contains("^[" + "A-Za-z0-9_-" + "]{1,32}$"), "the documented id bound");
        assertEquals("[A-Za-z0-9_-]{1,32}", SirentideContract.ANCHOR_ID.pattern());
    }

    /// fx stays Part 2: the doc must not present-tense it into the allowed table, and the
    /// contract must still refuse it — the same fact SemanticAnchorTest pins from the code
    /// side, asserted here from the doc side so the two can't drift apart silently.
    @Test
    void fxStaysReservedInBothDocAndContract() throws IOException {
        String doc = Files.readString(DOC);
        assertTrue(doc.contains("`data-sirentide-fx` is Part 2"),
            "the doc must mark fx as Part 2 / not admitted today");
        assertTrue(!SirentideRole.isWire("fx"), "fx is not a role wire value");
        assertTrue(!SirentideContract.attributeValueValid("data-sirentide-fx", "glow"),
            "the contract must not admit data-sirentide-fx before Part 2");
    }

    private static String docLineContaining(String needle) throws IOException {
        for (String line : Files.readAllLines(DOC)) {
            if (line.contains(needle) && line.startsWith("|")) {
                return line;
            }
        }
        throw new AssertionError("no table row containing " + needle + " in " + DOC);
    }

    /// All backticked tokens in a string, in order.
    private static List<String> backtickedTokens(String s) {
        List<String> out = new ArrayList<>();
        Matcher m = Pattern.compile("`([^`]+)`").matcher(s);
        while (m.find()) {
            out.add(m.group(1));
        }
        return out;
    }
}
