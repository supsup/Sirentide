# Sirentide Slowstart 🌊

The unhurried tour: *why* Sirentide exists, what makes it different, and where it's going. For
the five-minute version, see [QUICKSTART.md](QUICKSTART.md); for the full design, `docs/DESIGN.md`.

---

## The one-line pitch

**Living, narratable diagrams — baked to static SVG, no runtime JavaScript.** Sirentide takes
the *idea* of a diagram-from-text tool (like Mermaid) and gives it a new spin based on
[LatteX](https://github.com/supsup/LatteX): the diagram bakes to inert SVG at build time, and it
carries a **native effect/narrative layer** — a flow you can *play through*, effects bound to the
diagram's own meaning, and real LaTeX math *inside* labels.

## Why not just use Mermaid?

Mermaid is excellent, and we read it for ideas — but it runs in a **browser**, as JavaScript, at
view time. That doesn't fit a docs site that bakes to static HTML and strips runtime JS. You
*can* pre-render Mermaid with a headless browser (`mermaid-cli`), but that drags a ~300 MB
Chromium into your build. Sirentide is the hermetic, zero-dependency answer: pure JVM, bakes to
inert SVG, ships nothing to the reader.

And it does one thing no browser-JS diagram tool can do in a CSP-clean static bake: a controlled,
sanitizer-safe **effect layer** that gives a diagram presence — the reason to build it rather
than shell out to a browser.

## The three things only Sirentide will do

1. **★ Math *in* diagram labels.** Because Sirentide and LatteX are minimal-alphabet siblings, a
   node label, axis tick, or bar can *be* a real LaTeX formula — rendered at bake, zero runtime.
   A flowchart node that is an equation. Mermaid's math is runtime KaTeX; impossible in a static
   bake. ***(shipped)*** — real baked LaTeX renders in every label-bearing type (incl. braced
   `\frac{a}{b}`/`\sqrt{x}`) and standalone `mathblock`, via the injected LatteX renderer.
2. **Directable, playable flows.** Step through a sequence; reveals in reading order; the active
   step pulses. A process as a narrated slideshow, not a snapshot. *(planned)*
3. **Effects bound to meaning.** "Glow the critical path", "flash the error edge" — keyed off the
   diagram's own semantics, which Sirentide emits as stable anchors. *(planned)*

---

## Scenario — Dana documents an architecture

Dana writes internal docs on a static site whose sanitizer strips `<script>` and `<style>`. She
wants a diagram, not a hand-placed SVG.

Today she can already reach for twenty diagram types — a `pie` of where the team's time goes, an
`xychart` of throughput, a `timeline` or `gantt` of the roadmap, a `flowchart` of the review
gate, a `sequence` of the request path, plus `state`, `quadrant`, `classDiagram`, `erDiagram`,
`gitGraph`, `journey`, `mindmap`, `sankey`, `mathblock`, `matrix`, `snake`, `tensornetwork`,
`young`, and `dynkin`:

```
flowchart TD
  A[Open PR] --> B{Approve?}
  B -->|yes| C[Merge]
  B -->|no|  D[Revise]
  D -->|re-review| B
```

Her build calls `Sirentide.render(...)`, gets back a self-contained `<svg>` of inert geometry,
and splices it into the page. No JavaScript ships; the sanitizer waves it through untouched; the
reader sees a crisp diagram with the page.

Already today a node label reads `latency = $\frac{n}{r}$` and the formula renders *in* the box.
*Where it's going:* soon the reader can **play** her `sequence` step by step, and she tags the
critical path `fx=glow` and it pulses. Same DSL, same hermetic bake, same no-runtime-JS guarantee
— with presence.

---

## The milestone ladder

Sirentide scopes by **layout tractability**, not feature parity — it will never chase all of
Mermaid. The ladder moved faster than first drawn: the graph and time-axis types landed on the
current path/line alphabet, so `flowchart`, `gantt`, and `timeline` are built now rather than
deferred. Math-in-labels and the full `sequence` (alt/loop/par frames + activation bars) also
shipped. What remains ahead is the *thesis* layer (play-through, effects) and the
`marker`/`defs`-backed denser `sequence` heads.

| | Diagrams | Status |
|---|---|---|
| **M0** | scaffold, contracts, font-metrics oracle | **built** (font oracle ✓, scaffold ✓, contracts ✓) |
| **M1** | `pie`, `xychart`, a minimal linear `sequence` (`->>` calls / `-->>` replies / self-messages) | **built** — the linear-sequence demonstrator ships; the play-through *effect* that rides it is *planned* |
| **M2** | full `sequence` (alt/loop/par frames, activation bars) | **built** — alt/loop/par frames and activation bars both ship; `marker`/`defs` stay deferred, so arrowheads today are inline `<path>` triangles |
| **M3** | `gantt`, `timeline` | **built** (proportional time axis; ISO dates shown as dates) |
| **M-gate** | `flowchart` (`TD`/`LR`, rect/diamond nodes, edge labels, cycle-tolerant) | **built** — a layered, deterministic layout with visible back-edge lanes. Full graph auto-layout (crossing-minimization, dagre/ELK-class) is *not* attempted and stays a conscious re-decision. |

## Family

- **[LatteX](https://github.com/supsup/LatteX)** — the LaTeX→SVG math sibling Sirentide depends
  on (render-only) and composes with. Same clean-room, zero-dep, sanitizer-safe discipline.
