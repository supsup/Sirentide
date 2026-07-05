# Sirentide

**Living, narratable diagrams — baked to static SVG, no runtime JS.**

Sirentide is a clean-room, pure-Java, zero-dependency renderer that turns a small diagram DSL into inert static SVG *at build time* — with a **native effect/narrative layer**: diagrams you can *play through*, effects bound to the diagram's own meaning, and real **LaTeX math baked into labels** (via its sibling, [LatteX](https://github.com/supsup/LatteX)).

It is not a Mermaid clone. Mermaid is a design reference; Sirentide takes the idea, drops the browser, and adds the thing no browser-JS diagram tool can put in a CSP-clean static bake: a controlled, sanitizer-safe effect layer that gives a diagram *presence*.

## Why it exists

- **Static-site / docs safe.** Output is inert `svg/g/path/rect/...` — no `<script>`, no runtime JS, no external fonts. It survives a strict HTML sanitizer untouched.
- **Hermetic build.** Pure JVM, zero runtime deps (no headless browser at bake). The one build-time asset is a bundled OFL/Apache font, rendered to paths.
- **Native effects.** Because Sirentide emits the SVG with its own stable semantic anchors, effects ("glow the critical path", "pulse the active step", "play the flow in order") attach natively — not bolted onto a drifting class-soup.
- **Math composition.** A node label, axis tick, or bar can *be* a real LaTeX formula — its LatteX sibling renders it at bake, and the two minimal-alphabet emitters compose for free.

## Status

Greenlit; **M0** (contracts + LatteX seam + core scaffold) in progress. See [`docs/DESIGN.md`](docs/DESIGN.md) for the full design, milestones, and the security model.

## Family

- **[LatteX](https://github.com/supsup/LatteX)** — the clean-room LaTeX→SVG math sibling Sirentide depends on (render-only) and composes with.

## License

Apache-2.0.
