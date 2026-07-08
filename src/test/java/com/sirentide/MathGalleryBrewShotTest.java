package com.sirentide;

import com.brewshot.BrewShot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.sirentide.api.MathFragmentRenderer;
import com.sirentide.api.Sirentide;
import com.sirentide.math.LatteXMathFragmentRenderer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/// Real-browser "eyes" for the BROADENED math moat (plan sirentide-math-in-all-label-types): renders a
/// SEQUENCE diagram with a `$\frac{a}{b}$` / `$O(n^2)$` MESSAGE label AND a STATE diagram with a math
/// TRANSITION label, through the REAL {@link LatteXMathFragmentRenderer}, in a live browser. Proves
/// (a) the baked math STAYS INSIDE the canvas (same containment audit as {@link BrewShotGalleryTest}),
/// and (b) a MathBox actually baked (the emitter wrapper is present). Writes reference PNGs under
/// examples/gallery/ for the human eyeball. Skips clean when no local Chrome.
class MathGalleryBrewShotTest {

    private static final MathFragmentRenderer REAL = new LatteXMathFragmentRenderer();

    private static final String CONTAINMENT_AUDIT = """
        (function () {
          var bad = [];
          var svg = document.querySelector('svg');
          if (!svg) { return ['no svg element']; }
          var s = svg.getBoundingClientRect();
          if (!s.width || !s.height) { return ['zero-size svg']; }
          var tol = 1.5;
          svg.querySelectorAll('path, rect, line').forEach(function (el, i) {
            var r = el.getBoundingClientRect();
            if (r.width === 0 && r.height === 0) { return; }
            if (!isFinite(r.left) || !isFinite(r.top) || !isFinite(r.right) || !isFinite(r.bottom)) {
              bad.push(el.tagName + '#' + i + ' non-finite bbox'); return;
            }
            if (r.left < s.left - tol || r.top < s.top - tol
                || r.right > s.right + tol || r.bottom > s.bottom + tol) {
              bad.push(el.tagName + '#' + i + ' escapes canvas');
            }
          });
          return bad;
        })()
        """;

    private record Case(String name, String dsl) {}

    private static final List<Case> GALLERY = List.of(
        new Case("math-sequence",
            "sequence\n  Client ->> Server : request $O(n^2)$\n"
                + "  Server ->> DB : cost $\\frac{a}{b}$\n  DB -->> Server : rows\n"
                + "  Server -->> Client : $\\Delta t$ latency\n"),
        new Case("math-state",
            "state\n  [*] --> Idle\n  Idle --> Busy : work $\\frac{n}{2}$\n"
                + "  Busy --> Idle : done $O(1)$\n  Busy --> [*]\n"));

    private static Path galleryDir() {
        return Path.of("examples", "gallery").toAbsolutePath();
    }

    @Test
    void mathLabelsBakeAndStayInsideTheCanvas() throws Exception {
        assumeTrue(BrewShot.available(), "no local Chrome; skipping the browser eyes");
        Path dir = galleryDir();
        Files.createDirectories(dir);
        try (BrewShot shot = BrewShot.launch(560, 340)) {
            for (Case c : GALLERY) {
                String svg = Sirentide.render(c.dsl(), REAL);
                // The math actually baked (a MathBox wrapper is present, not degraded to raw text).
                assertTrue(svg.matches("(?s).*<g fill=\"[^\"]+\" transform=\"translate\\(.*"),
                    c.name() + ": a MathBox baked the label math: " + svg.substring(0, Math.min(200, svg.length())));
                shot.html("<!doctype html><html><body style=\"margin:20px;background:#fff\">"
                    + svg + "</body></html>");
                shot.settle(120);
                @SuppressWarnings("unchecked")
                List<Object> escapes = (List<Object>) shot.eval(CONTAINMENT_AUDIT);
                assertEquals(List.of(), escapes,
                    c.name() + ": baked math must stay inside the canvas — " + escapes);
                shot.screenshot(dir.resolve(c.name() + ".png"));
            }
        }
    }
}
