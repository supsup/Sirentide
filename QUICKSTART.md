# Sirentide Quickstart 🌊

Turn a tiny diagram DSL into a self-contained SVG, then drop it straight into your HTML.

> **Status note.** Sirentide is early but real. What's **built**: the render pipeline
> (DSL → IR → layout → SVG), the clean-room **font-metrics oracle**, labels baked to `<path>`
> glyphs, and **twenty-three diagram types** — `pie`, `xychart`, `timeline`, `gantt`, `flowchart`,
> `sequence`, `state`, `quadrant`, `classDiagram`, `erDiagram`, `gitGraph`, `journey`, `mindmap`,
> `sankey`, `mathblock`, `matrix`, `heatmap`, `snake`, `tensornetwork`, `young`, `dynkin`, `rootsystem`, and `knot`. Also built: **LaTeX math in labels** (the LatteX bridge), **semantic
> anchors** (`data-sirentide-role/id/seq`), the **baked-frame play-through API** (`renderFrames`),
> `classDef`/`class` **semantic colour classes** (incl. built-in `status-danger`/`-warn`/`-ok`/`-neutral`
> **status roles** — a closed **theme-durable** palette (one fill/stroke set chosen to read on both the
> light and dark canvases; a renderer-owned light/dark status pair is a named future slice) + a
> border-severity channel + a spoken a11y word; a `classDef` on a `status-*` name merges facets, so a
> fill-only override keeps the severity border), the `%% caption:` **annotation band**, and the
> live **`/docs`** ```` ```sirentide ```` fence. Accuracy over hype: if it isn't built, this doc
> says so.

---

## 1. What Sirentide is

Sirentide is a clean-room, pure-**Java 25**, **zero-runtime-dependency** renderer that turns a
small diagram DSL into **static SVG at build time** — no JavaScript, no headless browser, no
runtime. Its emitter targets a deliberately tiny, sanitizer-safe SVG alphabet (`<svg>`, `<path>`,
`<rect>`, `<line>`, plus `<g>` for math fragments + semantic anchors and `<title>`/`<desc>` for
a11y — labels are baked to `<path>` glyphs; never `<script>`, `<style>`, `<foreignObject>`, or
external `href`s), so the output is safe to inline directly into HTML and already sits inside a
standard sanitizer allow-list. Apache-2.0.

It's the diagram sibling of [LatteX](https://github.com/supsup/LatteX) (the LaTeX→SVG math
renderer), and shares its discipline — and its font, so a diagram label can *contain* a real
LaTeX formula, rendered at bake.

> **Label font coverage.** Labels bake to `<path>` glyphs from the bundled **STIX Two Math** font,
> which covers **Latin text and mathematical symbols**. Code points outside that coverage —
> non-Latin scripts (CJK, Arabic, …) and emoji — have no glyph and bake today as empty `.notdef`
> boxes. The bake never fails on them; it stays inert and deterministic. `renderWithDiagnostics`
> surfaces the boundary as an `OK` result whose message/detail **names the out-of-coverage
> `U+XXXX` code points**, so an unexplained box is a nameable signal rather than a silent surprise.

## 2. Your first diagram: from nothing to a saved SVG

One path, top to bottom. Every command is meant to be copied as written.

### What you need

- **JDK 25.** The build compiles for Java 25, and the jar it produces needs a Java 25 runtime
  to run. Gradle can download the compile toolchain for itself, but `java -jar` uses the `java`
  on your `PATH`, so installing JDK 25 is the simple route.
- **A git clone, not a zip download.** The jar stamps the commit it was built from into its
  manifest, and the build refuses to produce a jar without one exact 40-hex commit. In a clone
  Gradle reads that from git. A source zip has no git history, so `./gradlew jar` fails there
  (unless you supply the commit yourself in `SIRENTIDE_SOURCE_REVISION`).

### Build

**Getting the CLI from a source checkout** — build the executable jar once, then (optionally)
alias it:

```bash
git clone https://github.com/supsup/Sirentide.git
cd Sirentide
./gradlew jar                # produces build/libs/sirentide-0.6.0.jar (Main-Class is set)
# The double quotes are load-bearing: $PWD expands NOW, while you are still in the checkout,
# baking the absolute jar path into the alias — so it keeps working after you cd anywhere else.
# (Single-quoting the alias would defer $PWD to each invocation and break outside the checkout.)
alias sirentide="java -jar '$PWD/build/libs/sirentide-0.6.0.jar'"
```

The alias is for an interactive shell. In a script, call
`java -jar build/libs/sirentide-0.6.0.jar` directly.

### A page with a diagram in it

Save this as `first.md`. It is an ordinary Markdown file; the diagram is the fenced block whose
info string is exactly `sirentide`:

````markdown
# First diagram

Any prose. Only the first sirentide fence is rendered.

```sirentide
flowchart TD
  A[Open PR] --> B{Approve?}
  B -->|yes| C[Merge]
  B -->|no|  D[Revise]
  D -->|re-review| B
```
````

### Render it, then open it

```bash
sirentide render first.md -o out.svg
echo "exit=$?"        # 0 when the diagram rendered
```

`out.svg` is a complete, standalone SVG document. Open it in any browser (`open out.svg` on macOS,
`xdg-open out.svg` on Linux), or paste its contents inline into an HTML page.

That is the whole loop: edit the fence, run `render` again, reload. If the exit code is not `0`,
section 5 shows what the common failures look like.

## 3. From the command line

Section 2 built the jar and the `sirentide` alias; everything here uses them. The shortest form
takes no file at all:

```bash
echo 'pie
  "A" : 60
  "B" : 40' | sirentide
```

Reads a DSL from stdin, writes the SVG to stdout.

**Many diagrams in one JVM.** `sirentide render --batch` reads NUL-separated sources from stdin and
writes one NUL-terminated SVG record per source, in order, so record N always answers source N:
```bash
printf 'pie\n  "A" : 60\n  "B" : 40\0xychart\n"x" : 1\n"y" : 2\0' | sirentide render --batch
```

> **Note on math in labels from the CLI.** By default the CLI bakes a `$…$` label as its **raw
> LaTeX source text**. That is the documented per-fragment fail-soft, not an error. To typeset it,
> pass `--math` with the LatteX jar (located at run time, never bundled):
> ```bash
> sirentide render first.md --math --lattex libs/lattex-0.6.0.jar
> ```
> (or set `SIRENTIDE_LATTEX_JAR`). `--math` without a usable jar is a usage error (exit 2), and
> a `$…$` run LatteX cannot typeset is reported as a caveat, so `--strict` fails on it, and so is
> a typeset fragment Sirentide's fragment guard refused (`fragment guard refused: ...`, named by
> reason, never by content; the run shows its raw `$…$` source). From the
> API, pass a `MathFragmentRenderer` to the two-arg `Sirentide.render(dsl, mathRenderer)`.

`libs/lattex-0.6.0.jar` is tracked in the repository, so that path works from the checkout root.
On `first.md` the flag changes nothing, because its labels carry no `$…$` run.

**Checking a docs fence locally, before it ever reaches `/docs`.** `sirentide render <file.md>`
extracts the first ```` ```sirentide ```` fence **the Stafficy `/docs` bake would capture** from a
markdown file and bakes it —
```bash
sirentide render first.md          # SVG to stdout
sirentide render first.md -o out.svg   # SVG to a file (atomic write)
```
The exit code tells you what `/docs` would do with the page:

- **`0`** — the fence renders; the SVG you got is what the page will embed.
- **`1`** — a fence was found but it does **not** render: `/docs` would keep the fence verbatim
  on the page with a visible *"diagram did not render"* caption. Nothing is written; the reason
  is on stderr. A raw DSL source or a fence body over the 1 MB source cap lands here too: it is
  not recognized, so it does not render. **Or** `--strict` was passed and the render carried a
  caveat (for example a dropped flowchart statement, a dropped or shortened label, a label code point outside the font's
  coverage, untypeset math, a math fragment the fragment guard refused, or a lint finding).
  In that case the
  SVG **is** written: it is exactly what `/docs` would serve, and worth inspecting.
  The dropped-statement caveat exists for flowcharts only. A `sequence`, `pie`, `timeline` or
  `gantt` line the parser cannot read is skipped with no caveat, so `--strict` cannot see it.
- **`2`** — loud error: no capturable fence (including a fence nested inside another fence —
  the bake leaves those literal), unreadable input, a Markdown file over the 8 MB cap, or an
  unwritable `-o` destination. Nothing is written. Also `2`: an unknown command or bad flags,
  `--math` or `--png` without a usable jar, `--batch` with no source on stdin, a failed write
  to stdout, and a BrewShot failure under `--png` (that one after the SVG has been written).

Without `--strict`, a caveat goes to stderr and the exit stays `0`, because the bake really
happens and really serves that SVG.

**Every form and flag.** `sirentide --help` prints the full text to stderr (it is treated as a
usage error, so it exits `2`); in short:

| Form | What it does |
|---|---|
| `sirentide` | raw DSL on stdin, SVG on stdout (the original shape) |
| `sirentide render <file.md>` | render the first fence the `/docs` bake would capture |
| `sirentide render -` | raw DSL on stdin, like the first form, plus flags and caveat reporting (the bare form takes no flags and prints no caveats) |
| `sirentide render --batch` | many NUL-separated sources on stdin, one NUL-terminated record each |

| Flag | What it does |
|---|---|
| `-o PATH` | write the SVG to `PATH` (atomic replace) instead of stdout; not with `--batch` |
| `--png PATH` | also screenshot the SVG to a PNG, through BrewShot; not with `--batch` |
| `--brewshot PATH` | the BrewShot jar `--png` uses (or `SIRENTIDE_BREWSHOT_JAR`); not bundled, so `--png` without it is a usage error |
| `--strict` | any caveat fails the run with exit `1` (the SVG is still written) |
| `--math` | typeset `$…$` label runs with LatteX (see the note above) |
| `--lattex PATH` | the LatteX jar `--math` loads (or `SIRENTIDE_LATTEX_JAR`) |
| `--lint-overlap` | also report text that overlaps text in another element, as a caveat |
| `--source-hash` | also print the SHA-256 of the raw source bytes to stderr; output and exit unchanged |

`-o` writes are **atomic — unconditionally**: the SVG is fully written to a temp sibling and then
*atomically* moved onto the destination, so a failed run never truncates or corrupts an existing
file. On a filesystem that cannot replace atomically, the CLI **refuses with exit 2** and leaves
the destination untouched — there is no non-atomic fallback. (A destination that is a directory
is refused; a symlink destination — even one pointing at a directory — is replaced as a path
entry, not written through, its target untouched; `-o` naming the input file is a safe full
replace.) Fence **extraction is scanner-parity** with
the `/docs` bake's `SirentideDiagramConverter` (the Stafficy-repo pre-flexmark pass) — see the
contract note on `FenceExtractor` and its cross-repository fixture test.

## 4. From Java, and the diagram language

The whole pipeline is one call. Give it a Sirentide DSL source, get back a self-contained SVG
string. The first token names the diagram type. A **pie**, as one complete file — save it as
`FirstDiagram.java` in the checkout root:

```java
import com.sirentide.api.RenderResult;
import com.sirentide.api.Sirentide;
import java.nio.file.Files;
import java.nio.file.Path;

public class FirstDiagram {
    public static void main(String[] args) throws Exception {
        String dsl = """
            pie legend
              "Reviews" : 40
              "Builds"  : 30
              "Docs"    : 30 #22c55e
            """;

        String svg = Sirentide.render(dsl);
        // -> <svg xmlns="http://www.w3.org/2000/svg" width="..." height="..." viewBox="0 0 ..." role="img"><title>...</title><desc>...</desc> ... </svg>
        Files.writeString(Path.of("first.svg"), svg);

        // The same bake, plus a verdict that says why when something is off.
        RenderResult result = Sirentide.renderWithDiagnostics(dsl);
        System.err.println(result.diagnostics().outcome() + ": " + result.diagnostics().message());
    }
}
```

Run it with the jar from section 2 on the classpath. Sirentide has no runtime dependencies, so
that one jar is the whole classpath, and `java` compiles a single source file on the fly:

```bash
java -cp build/libs/sirentide-0.6.0.jar FirstDiagram.java
```

It writes `first.svg` and prints the verdict; a clean render reads `OK: Rendered successfully.`
`render` is built not to throw on a bad source: it returns an SVG string either way (the inert
empty shell when the source did not render), and `renderWithDiagnostics` is how you find out
which case you are in. For a play-through,
`Sirentide.renderFrames(dsl)` returns a list of static SVG frames, one per step (past 512 steps,
or 50 MB of frames in total, it returns one empty shell instead).

`legend` (alias `key`) adds a colour key; a trailing `#hex` (3- or 6-digit) sets one wedge's
colour. A **very thin slice's** outside label may not fit and gets dropped (its wedge still draws;
`renderWithDiagnostics` names the dropped label) — use `pie legend` to show every label in the side
key. Swap the source and you get a different diagram from the same call. A **flowchart**:

```
flowchart TD
  A[Open PR] --> B{Approve?}
  B -->|yes| C[Merge]
  B -->|no|  D[Revise]
  D -->|re-review| B
```

`TD` (top-down, default) or `LR` (left-right); `A[label]` is a rectangle, `A{label}` a diamond;
`-->|text|` labels a single hop; chains like `A --> B --> C` expand; cycles are drawn with a
visible back-edge lane. A **sequence**:

```
sequence
  Client ->> Gateway : GET /token
  Gateway ->> Auth   : validate
  Auth   -->> Gateway : ok
  Gateway ->> Gateway : sign JWT
  Gateway -->> Client : 200 token
```

`->>` is a call (solid, filled head), `-->>` a reply (lighter, open head); `A ->> A` is a
self-message; the `: label` after the arrow is optional. Actors register in first-seen order.

### Comments

A line beginning `%%` is a comment. It draws nothing, and a diagram containing one renders
**byte-identically** to the same diagram without it:

```
flowchart TD
    A[Start] --> B[End]
    %% the happy path; the retry arm is added in the next section
```

Comments are *blanked* rather than removed, which is the part worth knowing: an error further
down still reports its **real source line number**, so a comment above a mistake does not shift
the diagnostic away from the line you are looking at. Measured — a parse error on source line 3
reports line 3 whether or not line 2 is a comment.

`%%` also introduces the optional configuration block at the very top of a diagram (title, theme,
direction, caption). That block is consumed as configuration; a `%%` line anywhere in the body is
a comment.

One trap follows from that. Every `%%` line *above* the diagram-type line belongs to the
configuration block, so a remark written there is read as a `key: value` setting and not as a
comment. A key the block does not know is ignored, but `note:` is an alias of `caption:`, so this
source draws "draft, do not publish" as a caption band under the pie:

```
%% note: draft, do not publish
pie
"A" : 60
"B" : 40
```

Put a remark below the type line and it is a comment again.

Every result is complete and standalone — write it to a `.svg` file, or paste it inline into a
page. A malformed row is skipped and an unknown diagram type degrades to an empty shell — the
bake never fails on one bad line. That is the Java API, `Sirentide.render`. The command line
refuses the same unknown type instead: exit `1`, nothing written, the reason on stderr (sections 3 and 5).
(See the [gallery](examples/gallery/GALLERY.md) for the diagram
types, `timeline` and `gantt` included, with real-browser renders.)

Working from a source checkout: `./gradlew test` never rewrites that gallery or the pages under
`examples/`. What the tests generate goes to `build/generated-examples/`, so `git status` stays
clean on any machine. The committed gallery images are the Linux render, and regenerating them is
a deliberate step (`-Dsirentide.regenerate=true`) done on the reference host; see
[docs/gallery-linux-check.md](docs/gallery-linux-check.md).

## 5. When it does not render

Four failures worth recognising. The stderr lines quoted below are what the renderer printed for
that exact source.

**A line was dropped, and `--strict` says so.** A flowchart line the parser cannot read is
dropped and the rest still renders. Without `--strict` that is exit `0` with the caveat on
stderr. With it:

```bash
printf 'flowchart TD\nA[One] --> B[Two]\nB <--> C[Three]\n' | sirentide render - --strict > lost-line.svg
echo "exit=$?"        # 1
```

```text
sirentide: rendered, with caveats — dropped statement(s): 1; line 3: B <--> C[Three]
```

The SVG is still written, so you can look at what the gate rejected. The bidirectional arrow
`<-->` is not supported, which is why line 3 went.

**An unknown diagram type.** `piechart` is not a type. Through `Sirentide.render` that degrades to
an empty shell; the command line refuses it instead, with exit `1`, nothing written, and a stderr
line saying the diagram-type keyword on line 1 is unknown:

```bash
printf 'piechart\n"A" : 60\n"B" : 40\n' | sirentide
echo "exit=$?"        # 1
```

**HTML in a label.** The most common paste from Mermaid. Sirentide renders label text literally
and does not interpret HTML, so a tag-shaped `<br/>` refuses the whole diagram rather than draw
the tag as text:

```
flowchart TD
  A[Build<br/>and test] --> B[Ship]
```

```text
sirentide: diagram did not render — The label node:A contains unsupported markup <br/>, so the diagram degraded to the empty shell. …
```

Exit `1`, nothing written.

**Sequence lines that vanish without a word.** This is the one to remember, because nothing
reports it. The sequence parser reads messages, notes, `alt`/`loop`/`par` frames and
`create`/`destroy`; a line it does not read is skipped:

```
sequence
participant Client
Client ->> Auth : login
opt remember me
Auth ->> Auth : mint refresh token
end
note over Cache : never mentioned in a message
Auth -->> Client : ok
```

This exits `0`, even under `--strict`, and draws only the three messages. The `participant`
line, the `opt` frame and the note over `Cache` (an actor no message names) are absent. The
dropped-statement caveat exists for flowcharts only, so **`--strict` cannot see this**; the only
check is to look at the picture or read the SVG's `<desc>`, which lists what was drawn.

## 6. Why it's safe to inline

Sirentide emits only inert geometry — filled `<path>`s, no script, no style, no runtime. So the
baked SVG needs **no downstream sanitization** and ships **no JavaScript**. For a static docs
site baking untrusted-author content, that's the whole point.

## 7. Status legend

| Mark | Meaning |
|---|---|
| **built** | Works today: the pipeline, the font-metrics oracle, labels-as-paths, all **twenty-three** diagram types (`pie`, `xychart`, `timeline`, `gantt`, `flowchart`, `sequence`, `state`, `quadrant`, `classDiagram`, `erDiagram`, `gitGraph`, `journey`, `mindmap`, `sankey`, `mathblock`, `matrix`, `heatmap`, `snake`, `tensornetwork`, `young`, `dynkin`, `rootsystem`, `knot`), semantic anchors (`data-sirentide-role/id/seq`), LatteX-math-in-labels, `alt`/`loop`/`par` sequence frames **and activation bars**, the baked-frame play-through API (`renderFrames`), and the `/docs` ```` ```sirentide ```` fence. |
| *planned* | Designed, not yet built: the native **effect layer** (`data-sirentide-fx`, the security-gated Part 2). |

See [`docs/DESIGN.md`](docs/DESIGN.md) for the full design and [`SLOWSTART.md`](SLOWSTART.md) for
the why.
