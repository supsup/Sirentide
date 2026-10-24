package com.sirentide.layout;

import static org.junit.jupiter.api.Assertions.*;

import com.sirentide.contract.SirentideRole;
import com.sirentide.emit.SvgEmitter;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class FrameActivationOverlapLintTest {
    private static GlyphRun label() {
        return new GlyphRun("M0 0 L20 0 L20 20 L0 20 Z", "currentColor");
    }

    private static Rect bar() {
        return new Rect(5, 5, 8, 30, "#c7d2fe");
    }

    private static LaidOut scene(GlyphRun label, Rect bar) {
        return new LaidOut(100, 100, List.of(label, bar), List.of(label), List.of(bar));
    }

    @Test
    void explicitFrameAndActivationMetadataAddsTheSelectiveFinding() {
        GlyphRun label = label();
        Rect bar = bar();
        List<OverlapLint.Finding> findings = OverlapLint.findings(scene(label, bar));
        assertEquals(List.of(new OverlapLint.Finding("frame-label:0", "activation:0", 5, 5, 8, 15)), findings);
    }

    @Test
    void ordinaryTextAndFillRemainExemptAndMetadataDoesNotChangeSvg() {
        GlyphRun label = label();
        Rect bar = bar();
        LaidOut plain = new LaidOut(100, 100, List.of(label, bar));
        assertTrue(plain.frameLabels().isEmpty());
        assertTrue(plain.activationBars().isEmpty());
        assertEquals(List.of(), OverlapLint.findings(plain));
        assertEquals(SvgEmitter.emit(plain), SvgEmitter.emit(scene(label, bar)));
        assertEquals(List.of(), OverlapLint.findings(
            new LaidOut(100, 100, List.of(label, bar), List.of(label), List.of())));
        assertEquals(List.of(), OverlapLint.findings(
            new LaidOut(100, 100, List.of(label, bar), List.of(), List.of(bar))));
    }

    @Test
    void equalButUnemittedMetadataCannotCreateGhostFindings() {
        GlyphRun actual = label();
        Rect emitted = bar();
        GlyphRun equalLabel = label();
        Rect equalBar = bar();
        assertEquals(actual, equalLabel);
        assertNotSame(actual, equalLabel);
        assertEquals(emitted, equalBar);
        assertNotSame(emitted, equalBar);
        assertTrue(OverlapLint.findings(new LaidOut(100, 100, List.of(actual, emitted),
            List.of(equalLabel), List.of(emitted))).isEmpty());
        assertTrue(OverlapLint.findings(new LaidOut(100, 100, List.of(actual, emitted),
            List.of(actual), List.of(equalBar))).isEmpty());
    }

    @Test
    void repeatedMetadataIsDeduplicatedAndGroupedLeavesRemainPresent() {
        GlyphRun label = label();
        Rect bar = bar();
        Group group = new Group(new Anchor(SirentideRole.NOTE, "test", 0), List.of(label, bar));
        LaidOut laid = new LaidOut(100, 100, List.of(group), List.of(label, label), List.of(bar, bar));
        assertEquals(OverlapLint.findings(scene(label, bar)), OverlapLint.findings(laid));
    }

    @Test
    void theExistingOnePixelThresholdAppliesOnBothAxes() {
        GlyphRun label = label();
        for (Rect touching : List.of(new Rect(19, 0, 8, 20, "#000000"),
                new Rect(0, 19, 8, 20, "#000000"), new Rect(20, 0, 8, 20, "#000000"))) {
            assertTrue(OverlapLint.findings(scene(label, touching)).isEmpty());
        }
        assertEquals(1, OverlapLint.findings(scene(label,
            new Rect(18.5, 18.5, 8, 20, "#000000"))).size());
        for (Rect invalid : List.of(new Rect(5, 5, 0, 20, "#000000"),
                new Rect(5, 5, 8, -20, "#000000"), new Rect(5, 5, 8, Double.NaN, "#000000"),
                new Rect(Double.NEGATIVE_INFINITY, 5, Double.POSITIVE_INFINITY, 20, "#000000"))) {
            assertTrue(OverlapLint.findings(scene(label, invalid)).isEmpty());
        }
    }

    @Test
    void intervalCandidatesPreserveMetadataOrderAcrossBothSidesOfTheIndex() {
        GlyphRun first = label();
        GlyphRun second = new GlyphRun("M0 110 L20 110 L20 130 L0 130 Z", "currentColor");
        List<Rect> bars = List.of(new Rect(5, 15, 8, 20, "#000000"),
            new Rect(5, -5, 8, 10, "#000000"), new Rect(5, 8, 8, 4, "#000000"),
            new Rect(5, 100, 8, 10, "#000000"), new Rect(5, -50, 8, 20, "#000000"),
            new Rect(5, 20, 8, 10, "#000000"), new Rect(5, 19, 8, 10, "#000000"),
            new Rect(5, 18.5, 8, 10, "#000000"), new Rect(5, 120, 8, 10, "#000000"));
        List<Shape> shapes = new ArrayList<>(List.of(first, second));
        shapes.addAll(bars);
        List<OverlapLint.Finding> found = OverlapLint.findings(
            new LaidOut(100, 150, shapes, List.of(first, second), bars));
        assertEquals(List.of("frame-label:0 x activation:0", "frame-label:0 x activation:1",
            "frame-label:0 x activation:2", "frame-label:0 x activation:7",
            "frame-label:1 x activation:8"), found.stream().map(f -> f.a() + " x " + f.b()).toList());
        assertEquals(List.of(5.0, 5.0, 4.0, 1.5, 10.0), found.stream().map(OverlapLint.Finding::h).toList());
    }

    @Test
    void layoutMetadataIsAnImmutableSnapshot() {
        GlyphRun label = label();
        Rect bar = bar();
        List<Shape> shapes = new ArrayList<>(List.of(label, bar));
        List<GlyphRun> labels = new ArrayList<>(List.of(label));
        List<Rect> bars = new ArrayList<>(List.of(bar));
        LaidOut laid = new LaidOut(100, 100, shapes, labels, bars);
        shapes.clear();
        labels.clear();
        bars.clear();
        assertEquals(2, laid.shapes().size());
        assertSame(label, laid.frameLabels().getFirst());
        assertSame(bar, laid.activationBars().getFirst());
        assertThrows(UnsupportedOperationException.class, () -> laid.frameLabels().clear());
        assertThrows(UnsupportedOperationException.class, () -> laid.activationBars().clear());
        assertEquals(1, OverlapLint.findings(laid).size());
    }

    @Test
    void aCaptionPreservesTheSameEmittedMetadataReferencesAndFindings() {
        GlyphRun label = label();
        Rect bar = bar();
        LaidOut before = scene(label, bar);
        LaidOut captioned = CaptionLayout.withCaption(before, "Caption below the scene");
        assertTrue(captioned.height() > before.height());
        assertSame(label, captioned.frameLabels().getFirst());
        assertSame(bar, captioned.activationBars().getFirst());
        assertEquals(OverlapLint.findings(before), OverlapLint.findings(captioned));
        assertSame(before, CaptionLayout.withCaption(before, ""));
    }
}
