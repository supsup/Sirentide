package com.sirentide;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sirentide.api.Diagnostics;
import com.sirentide.api.Outcome;
import com.sirentide.api.Sirentide;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

/// The heatmap-extension directives (plan f4d69e44) never fail the bake: a malformed `ramp:`,
/// `bins:`, `hide:` or `palette:` token, or a categorical cell that names no category, falls back
/// to a default. Before this class those fallbacks were SILENT: exit 0, `Rendered successfully.`,
/// and `render --strict` could not gate them. Each now rides the existing OK-caveat channel as a
/// LINE-SCOPED entry in {@link Diagnostics#detail()} naming the directive, the physical line, and
/// the rejected token. The SVG is unchanged by the caveat; valid input carries NO caveat.
class HeatmapInputCaveatTest {

    private static Diagnostics diag(String dsl) {
        return Sirentide.renderWithDiagnostics(dsl).diagnostics();
    }

    /// Asserts an OK render whose detail lists exactly `entry` (as `line N: entry`) among its issues.
    private static void caveats(String dsl, int line, String entry) {
        Diagnostics d = diag(dsl);
        assertEquals(Outcome.OK, d.outcome(), "a malformed directive never fails the bake: " + d);
        assertTrue(d.detail().contains("line " + line + ": " + entry),
            "expected `line " + line + ": " + entry + "` in: " + d.detail());
        assertTrue(d.message().contains("heatmap input"), "the message says so too: " + d.message());
        assertEquals(line, d.line(), "the diagnostic is line-scoped to the first issue");
    }

    private static void clean(String dsl) {
        Diagnostics d = diag(dsl);
        assertEquals(Outcome.OK, d.outcome(), d.toString());
        assertEquals("Rendered successfully.", d.message(), "valid input carries no caveat");
        assertEquals("", d.detail(), "valid input carries no caveat");
    }

    private static int rects(String svg, String hex) {
        Matcher m = Pattern.compile("<rect [^>]*fill=\"" + Pattern.quote(hex) + "\"").matcher(svg);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    /// Legend steps are the only RAMP_H (12px) tall rects in a non-categorical heatmap.
    private static int legendSteps(String svg) {
        Matcher m = Pattern.compile("<rect [^>]*height=\"12\"").matcher(svg);
        int n = 0;
        while (m.find()) {
            n++;
        }
        return n;
    }

    // ---- ramp: ----------------------------------------------------------------------------

    @Test
    void aRampColourWordIsNamed() {
        caveats("heatmap\nramp: #000000, red, #ffffff\n\"r\" : 0.5", 2,
            "ramp: \"red\" is not a #hex colour; stop ignored");
    }

    @Test
    void aBadRampHexIsNamed() {
        caveats("heatmap\nramp: #000000, #12345, #ffffff\n\"r\" : 0.5", 2,
            "ramp: \"#12345\" is not a #hex colour; stop ignored");
    }

    @Test
    void aRampWithFewerThanTwoValidStopsSaysTheDefaultIsKept() {
        String dsl = "heatmap\nramp: #000000, nope\n\"r\" : 1";
        caveats(dsl, 2, "ramp: fewer than 2 valid stops; the default ramp is kept");
        caveats(dsl, 2, "ramp: \"nope\" is not a #hex colour; stop ignored");
    }

    @Test
    void rampStopsPastSixteenAreDroppedAndNamed() {
        // 16 black stops then a white 17th: capped, the value-1 cell is the 16th stop (black);
        // accepting the 17th (the `<` → `<=` mutant) would make it white.
        String dsl = "heatmap\nramp: " + "#000000, ".repeat(16) + "#ffffff\n\"r\" : 1";
        String svg = Sirentide.render(dsl);
        assertEquals(0, rects(svg, "#ffffff"), "the 17th stop never reaches a fill");
        caveats(dsl, 2, "ramp: 1 stop past the 16-stop cap dropped (\"#ffffff\")");
    }

    @Test
    void sixteenStopsAreAcceptedWithoutACaveat() {
        String dsl = "heatmap\nramp: " + "#000000, ".repeat(15) + "#ffffff\n\"r\" : 1";
        assertTrue(rects(Sirentide.render(dsl), "#ffffff") >= 1, "the 16th stop is the top end");
        clean(dsl);
    }

    @Test
    void aSixteenStopLegendSamplesEveryStop() {
        // Alternating black/white stops: a 12-sample legend aliases them away; a legend that shows
        // every stop samples each segment at least twice, so both halves of every segment appear.
        StringBuilder r = new StringBuilder("ramp: ");
        for (int i = 0; i < 16; i++) {
            r.append(i > 0 ? ", " : "").append(i % 2 == 0 ? "#000000" : "#ffffff");
        }
        String svg = Sirentide.render("heatmap\n" + r + "\n\"r\" : 0.5");
        int steps = legendSteps(svg);
        assertEquals(30, steps, "15 segments x 2 samples");
    }

    // ---- bins: ----------------------------------------------------------------------------

    @Test
    void aBinCountOutsideTwoToSixtyFourIsNamed() {
        for (String n : new String[] {"0", "1", "65", "999"}) {
            caveats("heatmap\nbins: " + n + "\n\"r\" : 0.5", 2, "bins: \"" + n
                + "\" is neither a bin count in 2..64 nor a cut point strictly inside (0, 1); no bins applied");
        }
    }

    @Test
    void aNegativeOrNonNumericBinTokenIsNamed() {
        for (String n : new String[] {"-3", "abc", "1.5"}) {
            caveats("heatmap\nbins: " + n + "\n\"r\" : 0.5", 2, "bins: \"" + n
                + "\" is neither a bin count in 2..64 nor a cut point strictly inside (0, 1); no bins applied");
        }
    }

    @Test
    void cutPointsAtOrPastTheEndsAreNamedAndNotKept() {
        String dsl = "heatmap\ncols: a\nbins: 0, 0.5, 1, 1.5\n\"r\" : 0.2";
        for (String t : new String[] {"0", "1", "1.5"}) {
            caveats(dsl, 3, "bins: \"" + t + "\" is not a cut point strictly inside (0, 1); cut point ignored");
        }
        // Only 0.5 survives: a 2-step legend. Keeping the 0 and 1 ends (the mutant) is 4 steps.
        String svg = Sirentide.render(dsl);
        String control = Sirentide.render("heatmap\ncols: a\nbins: 0.5\n\"r\" : 0.2");
        assertEquals(control, svg, "the rejected ends change nothing in the SVG");
        assertEquals(1, rects(control, "#1e40af"), "two bins: the upper one is the HI end (legend)");
    }

    @Test
    void unsortedAndDuplicateCutsAreBenignAndSilent() {
        clean("heatmap\nbins: 0.75, 0.25, 0.25\n\"r\" : 0.5");
        clean("heatmap\nbins: 4\n\"r\" : 0.5");
        clean("heatmap\nbins: 25%, 50%\n\"r\" : 0.5");
    }

    @Test
    void anEmptyBinsDirectiveIsNamed() {
        caveats("heatmap\nbins:\n\"r\" : 0.5", 2, "bins: no bin count or cut point given; no bins applied");
    }

    // ---- hide: ----------------------------------------------------------------------------

    @Test
    void anUnknownHideTargetIsNamed() {
        caveats("heatmap\ncols: a\nhide: everything\n\"r\" : 0.5", 3,
            "hide: \"everything\" is not rows, cols or both; ignored");
    }

    @Test
    void aKnownHideTargetBesideAnUnknownOneStillApplies() {
        String dsl = "heatmap\ncols: a\nhide: cols, sideways\n\"r\" : 0.5";
        caveats(dsl, 3, "hide: \"sideways\" is not rows, cols or both; ignored");
        assertEquals(0, rects(Sirentide.render(dsl), "#e2e8f0"), "cols is still hidden");
    }

    @Test
    void validHideTargetsAreSilent() {
        clean("heatmap\ncols: a\nhide: rows, cols\n\"r\" : 0.5");
        clean("heatmap\ncols: a\nhide: both\n\"r\" : 0.5");
        clean("heatmap\ncols: a\nhide: headers\n\"r\" : 0.5");
    }

    // ---- palette: -------------------------------------------------------------------------

    @Test
    void anInvalidPaletteHexIsNamed() {
        String dsl = "heatmap\ncols: a\npalette: A #zzzzzz, B #12345\n\"r\" : A";
        caveats(dsl, 3, "palette: \"#zzzzzz\" is not a #hex colour for category \"A\"; the default colour is used");
        caveats(dsl, 3, "palette: \"#12345\" is not a #hex colour for category \"B\"; the default colour is used");
    }

    @Test
    void aColourWordIsABadColourNotPartOfTheName() {
        // `C2 red` used to fold the word into the NAME: the legend read "C2 red" and every C2 cell
        // went NA. Now the last token of a multi-token entry is the colour slot.
        String dsl = "heatmap\ncols: a, b\npalette: C1 #ff0000, C2 red\n\"r\" : C1, C2";
        caveats(dsl, 3, "palette: \"red\" is not a #hex colour for category \"C2\"; the default colour is used"
            + " (quote a multi-word category name)");
        String svg = Sirentide.render(dsl);
        assertTrue(svg.contains("<desc>") && svg.contains("Categories: C1, C2."), "the name is C2");
        assertEquals(2, rects(svg, "#f28e2b"), "C2's cell + swatch take palette[1]");
        assertFalse(Sirentide.renderWithDiagnostics(dsl).diagnostics().detail().contains("is not a palette category"),
            "the C2 cell now matches its category");
    }

    @Test
    void aFunctionalColourIsABadColourToo() {
        caveats("heatmap\npalette: C1 url(#x)\n\"r\" : C1", 2,
            "palette: \"url(#x)\" is not a #hex colour for category \"C1\"; the default colour is used"
                + " (quote a multi-word category name)");
    }

    @Test
    void anUnquotedMultiWordNameWithoutAColourIsCaveatedAndAQuotedOneIsNot() {
        // THE RULE: in an unquoted entry the LAST whitespace token is always the colour slot. A
        // multi-word name with no colour must be quoted; unquoted, the caveat says so.
        caveats("heatmap\npalette: big win\n\"r\" : big", 2,
            "palette: \"win\" is not a #hex colour for category \"big\"; the default colour is used"
                + " (quote a multi-word category name)");
        clean("heatmap\npalette: \"big win\"\n\"r\" : big win");
        clean("heatmap\npalette: \"big win\" #ff0000\n\"r\" : big win");
        clean("heatmap\npalette: big win #ff0000\n\"r\" : big win");
        assertEquals(2, rects(Sirentide.render("heatmap\npalette: \"big win\" #ff0000\n\"r\" : big win"),
            "#ff0000"), "the quoted multi-word name matches its cell");
    }

    @Test
    void aNamelessPaletteEntryIsNamed() {
        caveats("heatmap\npalette: #ff0000, A\n\"r\" : A", 2,
            "palette: \"#ff0000\" has no category name; entry ignored");
    }

    @Test
    void aDuplicateCategoryIsNamedAndTheFirstEntryWins() {
        String dsl = "heatmap\ncols: a\npalette: A #ff0000, A #00ff00\n\"r\" : A";
        caveats(dsl, 3, "palette: duplicate category \"A\"; the later entry is ignored");
        String svg = Sirentide.render(dsl);
        assertEquals(2, rects(svg, "#ff0000"), "the first entry's colour: cell + one swatch");
        assertEquals(0, rects(svg, "#00ff00"), "the duplicate draws no second swatch");
    }

    @Test
    void anEmptyPaletteDirectiveIsNamed() {
        caveats("heatmap\npalette:\n\"r\" : 0.5", 2, "palette: no category given; line ignored");
    }

    @Test
    void validPalettesAreSilent() {
        clean("heatmap\ncols: a, b\npalette: A #ff0000, B\n\"r\" : A, B");
        clean("heatmap\ncols: a, b\npalette: \"part one\" #f00, two\n\"r\" : part one, two");
        clean("heatmap\ncols: a, b\npalette: A #ff0000\n\"r\" : x:A!, -");
    }

    // ---- categorical cells ----------------------------------------------------------------

    @Test
    void aCellNamingNoCategoryIsNamed() {
        String dsl = "heatmap\ncols: a, b, c\npalette: A #ff0000\n\"r\" : A, Z, 1";
        caveats(dsl, 4, "cell \"Z\" is not a palette category; drawn neutral (NA)");
        caveats(dsl, 4, "cell \"1\" is not a palette category; drawn neutral (NA)");
    }

    @Test
    void anEmptyOrDashCellIsALegitimateNaAndSilent() {
        clean("heatmap\ncols: a, b, c\npalette: A #ff0000\n\"r\" : A, -, ");
    }

    @Test
    void aQuotedCategoricalCellIsUnquotedBeforeMatching() {
        String svg = Sirentide.render("heatmap\ncols: a\npalette: A #ff0000\n\"r\" : \"A\"");
        assertEquals(2, rects(svg, "#ff0000"), "\"A\" is category A: cell + swatch");
        clean("heatmap\ncols: a\npalette: A #ff0000\n\"r\" : \"A\"");
    }

    @Test
    void aQuotedCommaStaysInsideAPaletteNameAndACell() {
        String dsl = "heatmap\ncols: a, b\npalette: \"A, B\" #ff0000, C #00ff00\n\"r\" : \"A, B\", C";
        clean(dsl);
        String svg = Sirentide.render(dsl);
        assertEquals(2, rects(svg, "#ff0000"), "the \"A, B\" cell + swatch");
        assertEquals(2, rects(svg, "#00ff00"), "C is the second column, not shifted to a third");
        assertTrue(svg.contains("Categories: A, B, C."), "two categories");
    }

    @Test
    void anUnbalancedQuoteNeverSwallowsTheRestOfThePalette() {
        // A stray `"` falls back to the plain comma split, so the B entry after it survives intact
        // instead of being folded into one giant name.
        String svg = Sirentide.render(
            "heatmap\ncols: a, b\npalette: \"A #ff0000, B #00ff00\n\"r\" : x, B");
        assertEquals(2, rects(svg, "#00ff00"), "B is its own category: cell + swatch");
    }

    @Test
    void manyIssuesAreCountedAndTheFirstFewListed() {
        String dsl = "heatmap\ncols: a\nramp: a, b, c, d, e, f, g\n\"r\" : 0.5";
        Diagnostics d = diag(dsl);
        assertTrue(d.detail().startsWith("heatmap input issue(s): 8"), d.detail());
        assertTrue(d.detail().contains("(3 more not listed)"), d.detail());
    }

    @Test
    void aSourceUsingNoExtensionCarriesNoCaveat() {
        clean("heatmap\ncols: a, b\nscale: lo --> hi\n\"r\" : 0.2, zz");
    }

    @Test
    void theCaveatLineIsThePhysicalSourceLineBelowAConfigBlock() {
        caveats("%% title: T\n\nheatmap\nhide: nope\n\"r\" : 0.5", 4,
            "hide: \"nope\" is not rows, cols or both; ignored");
    }

    @Test
    void aRampLegendWithFewStopsIsUnchanged() {
        // The 12-step legend stays for every stop count it already samples at least twice per segment.
        String svg = Sirentide.render("heatmap\nramp: #000000, #ffffff\n\"r\" : 0.5");
        assertEquals(12, legendSteps(svg), "12 legend steps");
    }
}
