package com.sirentide.parse;

import com.sirentide.ir.Diagram;
import com.sirentide.ir.Empty;
import com.sirentide.ir.FlowEdge;
import com.sirentide.ir.FlowNode;
import com.sirentide.ir.Flowchart;
import com.sirentide.ir.Gantt;
import com.sirentide.ir.Pie;
import com.sirentide.ir.SeqMessage;
import com.sirentide.ir.Sequence;
import com.sirentide.ir.Slice;
import com.sirentide.ir.StateDiagram;
import com.sirentide.ir.Task;
import com.sirentide.ir.Timeline;
import com.sirentide.ir.XyChart;
import com.sirentide.contract.SirentideContract;
import com.sirentide.layout.AxisScale;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/// The Sirentide DSL parser (its own language, not a mermaid subset — docs/DESIGN.md §8). M0
/// recognizes the empty diagram and `pie`. Malformed input degrades to the empty diagram or
/// skips the bad row — it never throws (§6: never fail the bake).
///
/// Pie form:
/// ```
/// pie
///   "Reviews" : 40
///   "Builds"  : 30
/// ```
public final class DslParser {

    private DslParser() {}

    /// Input-trust caps (DESIGN §6/§7: malformed/oversized → inert, never throw, never OOM). Past
    /// a cap the parser degrades to an inert/empty diagram rather than allocating unboundedly.
    /// `MAX_SOURCE_BYTES` is also the read bound the CLI enforces on stdin.
    public static final int MAX_SOURCE_BYTES = 1_000_000;   // 1 MB of DSL source
    public static final int MAX_DATA_ROWS = 10_000;         // rows past this are dropped
    public static final int MAX_LABEL_LEN = 512;            // labels are truncated to this
    // Flowchart graph caps (DESIGN §6/§7): past these, extra nodes/edges are dropped rather than
    // laid out — bounds the layering work + the shape count on a pathological graph, never throws.
    public static final int MAX_NODES = 500;
    public static final int MAX_EDGES = 1000;
    // Sequence-diagram cap (DESIGN §6/§7): a pathological actor count would blow up the lifeline
    // grid; extra first-seen actors past this are dropped (their messages then skip in layout).
    public static final int MAX_ACTORS = 50;

    public static Diagram parse(String src) {
        if (src == null || src.isBlank()) {
            return new Empty();
        }
        // Oversized source degrades to inert (never parse a runaway input into millions of shapes).
        // MAX_SOURCE_BYTES is a UTF-8 *byte* bound (DESIGN §6/§7 + the CLI stdin read cap). The cheap
        // `length()` guard is a fast reject on UTF-16 code units (always ≤ the UTF-8 byte count for
        // BMP, but multi-byte chars mean length() UNDER-counts bytes — a 600k-char `é` string is
        // 1.2 MB of UTF-8 yet only 600k code units). So ALSO scan the true UTF-8 byte length with a
        // no-alloc code-point walk (never materializes a byte[]) and reject once it exceeds the cap.
        if (src.length() > MAX_SOURCE_BYTES) {
            return new Empty();
        }
        long utf8Bytes = 0;
        for (int i = 0, n = src.length(); i < n; ) {
            int cp = src.codePointAt(i);
            utf8Bytes += cp < 0x80 ? 1 : cp < 0x800 ? 2 : cp < 0x10000 ? 3 : 4;
            if (utf8Bytes > MAX_SOURCE_BYTES) {
                return new Empty();
            }
            i += Character.charCount(cp);
        }
        String[] lines = src.strip().split("\\R");
        // The header is a TYPE token plus optional whitespace-split MODIFIER tokens (e.g.
        // `pie legend`). Bare `pie`/`xychart`/… stay exactly as before (a lone type token, no
        // modifiers). Unknown/malformed modifiers are simply ignored — the diagram still bakes
        // (DESIGN §6: never fail the bake).
        String[] header = lines[0].strip().split("\\s+");
        String type = header[0];
        // The off-slice text colour (page-background labels). `color=<value>` overrides the default;
        // an unparseable/illegal colour falls back to the default (never fails the bake).
        String textColor = parseTextColor(header);
        return switch (type) {
            // pie/xychart values are magnitudes → plain numeric parse. `pie legend` (or the `pie
            // key` alias) opts into the left-side colour key; a bare `pie` is legend-off.
            case "pie" -> new Pie(parseData(lines, false), hasLegendModifier(header), textColor);
            case "xychart" -> parseXyChart(lines, header, textColor);
            // timeline values are moments → date-aware parse (bare years stay numeric, ISO dates
            // map to epoch-day) so events place proportionally in time, not evenly by index.
            case "timeline" -> new Timeline(parseData(lines, true), textColor);
            case "gantt" -> parseGantt(lines, textColor);
            case "flowchart" -> parseFlowchart(lines, header, textColor);
            case "sequence" -> parseSequence(lines, header, textColor);
            // A mermaid-style state diagram — reuses the flowchart graph engine (§5); `statediagram`
            // is an accepted alias of `state`.
            case "state", "statediagram" -> parseStateDiagram(lines, header, textColor);
            default -> new Empty();
        };
    }

    /// The off-slice text fill from an optional `color=<value>` header modifier. The value must
    /// match the SirentideContract COLOR grammar (`#rrggbb` | `currentColor` | `none`); an invalid
    /// or unparseable colour degrades to the default `currentColor` rather than failing the bake
    /// (DESIGN §6). Order-independent with `legend`/`key` — it is just another header token.
    private static String parseTextColor(String[] header) {
        for (int i = 1; i < header.length; i++) {
            String tok = header[i];
            if (tok.startsWith("color=")) {
                String value = tok.substring("color=".length());
                if (SirentideContract.isColor(value)) {
                    // Normalize so a 3-digit `color=#333` reaches the emitter as `#333333`.
                    return SirentideContract.normalizeColor(value);
                }
                // Illegal colour → stop looking and use the default (a typo never fails the bake).
                return "currentColor";
            }
        }
        return "currentColor";
    }

    /// The default node/head box fill from an optional `nodecolor=<hex>` header modifier (flowchart
    /// nodes, state boxes, sequence actor heads). HEX-ONLY (like a per-item fill: a box needs a
    /// concrete swatch — `currentColor`/`none` are meaningless and are the H1 contrast footgun), so a
    /// non-hex value degrades to `null` = the layout's built-in default (never fails the bake, DESIGN
    /// §6). Normalized to canonical `#rrggbb`. Order-independent with the other header tokens.
    private static String parseNodeColor(String[] header) {
        for (int i = 1; i < header.length; i++) {
            String tok = header[i];
            if (tok.startsWith("nodecolor=")) {
                String value = tok.substring("nodecolor=".length());
                if (SirentideContract.isHexColor(value)) {
                    return SirentideContract.normalizeColor(value);
                }
                return null;   // invalid → the built-in default fill (a typo never fails the bake)
            }
        }
        return null;
    }

    /// True iff a pie header carries the `legend` modifier (alias `key`). Any other modifier token
    /// is ignored (degrade gracefully → plain pie), so a typo never fails the bake.
    private static boolean hasLegendModifier(String[] header) {
        for (int i = 1; i < header.length; i++) {
            if (header[i].equals("legend") || header[i].equals("key")) {
                return true;
            }
        }
        return false;
    }

    /// The xychart render mode from an optional header modifier: `line` or `scatter`; anything else
    /// (or absent) is the default `bars`. Order-independent with `legend`/`key`/`color=` — just
    /// another header token, so a typo degrades to bars rather than failing the bake (DESIGN §6).
    private static String parseXyMode(String[] header) {
        for (int i = 1; i < header.length; i++) {
            if (header[i].equals("line")) {
                return "line";
            }
            if (header[i].equals("scatter")) {
                return "scatter";
            }
        }
        return "bars";
    }

    /// Parses an xychart: single-series bars (the default, byte-identical to before) OR a
    /// multi-series line/scatter/grouped-bars chart.
    ///
    /// A category row is `"label" : v1 [v2 v3 …] [#hex]`. Whitespace-split numeric tokens after the
    /// colon are the per-series values (series count = the MAX row's length); a shorter row means the
    /// trailing series have NO point at that category (a gap, skipped — never zeroed). A trailing
    /// `#hex` is honoured as the per-item bar colour ONLY on a single-value (single-series) row —
    /// with more than one value there are no per-item colours (series colours come from the palette
    /// by series index). An optional FIRST body line `series: A, B, C` names the legend series
    /// (comma-split, `cap()`'d); otherwise the legend labels default to `Series 1..N`.
    ///
    /// BYTE-COMPAT: a single-series `bars` chart routes through the LEGACY {@link XyChart} shape
    /// (a `Slice` list, `series == null`) so its layout/emit is unchanged.
    private static Diagram parseXyChart(String[] lines, String[] header, String textColor) {
        String mode = parseXyMode(header);
        boolean legend = hasLegendModifier(header);

        List<String> seriesNames = null;
        List<String> labels = new ArrayList<>();
        List<double[]> rows = new ArrayList<>();
        List<String> singleColors = new ArrayList<>();   // per-row single-series colour (else null)
        int maxSeries = 0;

        for (int i = 1; i < lines.length && rows.size() < MAX_DATA_ROWS; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.lastIndexOf(':');
            if (colon < 0) {
                continue;
            }
            String key = line.substring(0, colon).strip();
            String rest = line.substring(colon + 1).strip();
            // An optional `series: A, B, C` naming row — only when it is the FIRST body row seen.
            if (seriesNames == null && rows.isEmpty() && key.equals("series")) {
                seriesNames = new ArrayList<>();
                for (String name : rest.split(",")) {
                    String s = cap(name.strip());
                    if (!s.isEmpty()) {
                        seriesNames.add(s);
                    }
                }
                continue;
            }
            String label = cap(unquote(key));
            // Walk the whitespace-split value tokens: leading NUMERIC tokens are the series values;
            // the FIRST non-numeric token stops the scan, and if it is the LAST token and a hex
            // colour it becomes the (single-series-only) per-item fill.
            String[] toks = rest.split("\\s+");
            List<Double> vals = new ArrayList<>();
            String color = null;
            for (int t = 0; t < toks.length; t++) {
                if (toks[t].isEmpty()) {
                    continue;
                }
                double v;
                try {
                    v = Double.parseDouble(toks[t]);
                } catch (NumberFormatException e) {
                    // A trailing hex token → candidate per-item colour (kept only if single-series
                    // below); any other non-numeric token just ends the value scan (never throws).
                    if (t == toks.length - 1 && SirentideContract.isHexColor(toks[t])) {
                        color = SirentideContract.normalizeColor(toks[t]);
                    }
                    break;
                }
                if (!Double.isFinite(v)) {
                    break;   // NaN/Infinity (incl. "1e400") ends the scan — never reaches layout
                }
                vals.add(v);
            }
            if (vals.isEmpty()) {
                continue;   // no numeric value → malformed row, skip (never fail the bake)
            }
            double[] arr = new double[vals.size()];
            for (int k = 0; k < arr.length; k++) {
                arr[k] = vals.get(k);
            }
            labels.add(label);
            rows.add(arr);
            singleColors.add(vals.size() == 1 ? color : null);
            maxSeries = Math.max(maxSeries, arr.length);
        }

        // Single-series BARS → the legacy Slice-list shape (byte-identical output). A single-series
        // line/scatter chart still routes through the multi path (it is a NEW render mode, not the
        // pinned bar golden).
        if (mode.equals("bars") && maxSeries <= 1) {
            List<Slice> bars = new ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                bars.add(new Slice(labels.get(i), rows.get(i)[0], singleColors.get(i), null));
            }
            return new XyChart(bars, textColor);
        }

        // Multi-series (or line/scatter): `bars` carries only the category LABELS (its value slot
        // holds series 0 for convenience, unused by the multi layout); `series` carries the grid.
        List<Slice> bars = new ArrayList<>();
        for (int i = 0; i < rows.size(); i++) {
            bars.add(new Slice(labels.get(i), rows.get(i)[0], null, null));
        }
        return new XyChart(bars, rows, seriesNames, mode, legend, textColor);
    }

    /// Parses gantt rows: `"Task" : start-end` (two numbers on a shared time axis). A malformed
    /// row is skipped (never fails the bake).
    private static Diagram parseGantt(String[] lines, String textColor) {
        List<Task> tasks = new ArrayList<>();
        for (int i = 1; i < lines.length && tasks.size() < MAX_DATA_ROWS; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.lastIndexOf(':');
            if (colon < 0) {
                continue;
            }
            String label = cap(unquote(line.substring(0, colon).strip()));
            // Peel an OPTIONAL trailing `#hex` colour token off the END first, BEFORE the range
            // split — so `"Design" : 0-3 #4e79a7` gives range=`0-3` (the numeric dash still splits
            // cleanly) and colour=`#4e79a7`, and an ISO `2020-01-01 - 2020-06-01 #abcdef` keeps its
            // ` - ` delimiter intact. A non-colour trailing token is left on the range (palette).
            String[] pc = peelColor(line.substring(colon + 1).strip());
            String range = pc[0];
            String color = pc[1];
            // Split start from end. ISO date ranges use a ` - ` delimiter (so the dashes INSIDE a
            // `2020-01-01` date are not mistaken for the range separator — the old first-dash split
            // broke absolute dates); the bare-numeric `start-end` form still splits on its lone dash.
            String startTok;
            String endTok;
            int sep = range.indexOf(" - ");
            if (sep >= 0) {
                startTok = range.substring(0, sep).strip();
                endTok = range.substring(sep + 3).strip();
            } else {
                int dash = range.indexOf('-', 1);   // skip a leading sign; find the numeric separator
                if (dash <= 0) {
                    continue;
                }
                startTok = range.substring(0, dash).strip();
                endTok = range.substring(dash + 1).strip();
            }
            try {
                double start = AxisScale.parseDomainValue(startTok);
                double end = AxisScale.parseDomainValue(endTok);
                // Non-finite (NaN / Infinity, incl. Double.parseDouble("1e400") == Infinity, which
                // throws NO exception) must not reach layout/emit — skip the row (never fail bake).
                if (!Double.isFinite(start) || !Double.isFinite(end)) {
                    continue;
                }
                tasks.add(new Task(label, start, end, color));
            } catch (NumberFormatException e) {
                // Skip a malformed / unparseable-date row (loud-not-silent: dropped, never misplaced).
            }
        }
        return new Gantt(tasks, textColor);
    }

    /// Parses a flowchart: a directed graph, one edge (or one lone node) per body line.
    /// ```
    /// flowchart TD
    ///   A[Start] --> B[Process]
    ///   B --> C[End]
    /// ```
    /// Header: `flowchart` optionally followed by a `TD` (default) or `LR` direction token. Each
    /// body line is a `-->`-separated CHAIN of endpoints where an endpoint is a bare `id`,
    /// `id[Label]`, or `id{Label}`; a line with no top-level `-->` is a lone node declaration. A
    /// chained `A --> B --> C` expands to edges A→B and B→C (any length); an edge label rides its
    /// OWN hop (`A -->|yes| B -->|no| C` = A-yes→B, B-no→C). A node's label is its FIRST decorated
    /// occurrence (a node that is never bracketed uses its id as its label). Nodes register in
    /// first-seen order.
    ///
    /// The `-->` split is OPERATOR-SCANNED, not a blind `indexOf`: it only splits on a `-->` that is
    /// OUTSIDE any `[...]`, `{...}`, or `|...|` span (see {@link #topLevelArrows}). So `A[a-->b] --> C`
    /// is one edge A→C with A labeled `a-->b` (the bracket-embedded arrow is NOT a separator), and a
    /// label-embedded `|a-->b|` is inert. A malformed line drops WHOLE (loud-not-silent, DESIGN §6),
    /// never half-drawn: an unterminated `A[Start` (no `]`), trailing junk after a closed delimiter
    /// (`A[Start] junk`), a nested/unbalanced bracket (`A[Start --> B[End]`), an empty endpoint, or a
    /// missing closing edge-label pipe all drop the line. Caps: {@link #MAX_NODES}/{@link #MAX_EDGES}
    /// bound the graph. Empty body → a Flowchart with no nodes (still a flowchart, so `flowchart`
    /// round-trips — NOT degraded to Empty).
    private static Diagram parseFlowchart(String[] lines, String[] header, String textColor) {
        String direction = "TD";
        for (int i = 1; i < header.length; i++) {
            if (header[i].equals("LR")) {
                direction = "LR";
            } else if (header[i].equals("TD")) {
                direction = "TD";
            }
        }
        // Insertion-ordered id → label map: preserves first-seen node order and lets the first
        // decorated occurrence win the label (a later bare mention never overwrites it). Shapes
        // ride a parallel map (absent = rect).
        // The header `nodecolor=#hex` default box fill (null → the layout's built-in default). A
        // per-node `#hex` always overrides it; an invalid value degrades to the default (never fails).
        String nodeColor = parseNodeColor(header);
        LinkedHashMap<String, String> nodeLabels = new LinkedHashMap<>();
        Map<String, String> nodeShapes = new java.util.HashMap<>();
        Map<String, String> nodeColors = new java.util.HashMap<>();
        List<FlowEdge> edges = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            // Operator-scan for the top-level `-->` positions (outside every bracket/brace/pipe span).
            List<Integer> arrows = topLevelArrows(line);
            if (arrows.isEmpty()) {
                // No edge operator at top level → the whole line is a lone node declaration. A
                // bracket-swallowed arrow (`A[Start --> B[End]`) lands here too and drops via the
                // endpoint validator (nested `[` → malformed), NOT as a plausible node.
                String[] nd = parseEndpoint(line);
                if (nd != null) {
                    registerNode(nodeLabels, nodeShapes, nodeColors, nd);
                }
                continue;
            }
            // Tokenize the chain: endpoints[0..k] separated by k arrows, each arrow carrying an
            // OPTIONAL leading `|label|` that annotates only its hop. The head endpoint precedes the
            // first arrow; each subsequent segment is `[|label|] endpoint`.
            String[] head = parseEndpoint(line.substring(0, arrows.get(0)));
            if (head == null) {
                continue;   // malformed head endpoint → drop the whole line
            }
            List<String[]> endpoints = new ArrayList<>();
            List<String> hopLabels = new ArrayList<>();
            endpoints.add(head);
            boolean dropped = false;
            for (int k = 0; k < arrows.size(); k++) {
                int segStart = arrows.get(k) + 3;
                int segEnd = (k + 1 < arrows.size()) ? arrows.get(k + 1) : line.length();
                String seg = line.substring(segStart, segEnd).strip();
                String label = null;
                if (seg.startsWith("|")) {
                    int close = seg.indexOf('|', 1);
                    if (close < 0) {
                        dropped = true;   // missing closing pipe → drop the whole line
                        break;
                    }
                    String raw = seg.substring(1, close).strip();
                    label = raw.isEmpty() ? null : cap(raw);
                    seg = seg.substring(close + 1).strip();
                }
                String[] ep = parseEndpoint(seg);
                if (ep == null) {
                    dropped = true;   // any malformed endpoint drops the whole line (never half-drawn)
                    break;
                }
                hopLabels.add(label);
                endpoints.add(ep);
            }
            if (dropped) {
                continue;
            }
            // Register every endpoint (only after the whole chain validated — a partial line never
            // half-registers), then wire one edge per hop with that hop's own label.
            for (String[] ep : endpoints) {
                registerNode(nodeLabels, nodeShapes, nodeColors, ep);
            }
            for (int k = 0; k < hopLabels.size(); k++) {
                String from = endpoints.get(k)[0];
                String to = endpoints.get(k + 1)[0];
                // Only draw an edge whose BOTH endpoints actually registered (a node past MAX_NODES is
                // dropped, so its edges are too) and while under the edge cap.
                if (edges.size() < MAX_EDGES
                    && nodeLabels.containsKey(from) && nodeLabels.containsKey(to)) {
                    edges.add(new FlowEdge(from, to, hopLabels.get(k)));
                }
            }
        }
        List<FlowNode> nodes = new ArrayList<>();
        for (Map.Entry<String, String> e : nodeLabels.entrySet()) {
            nodes.add(new FlowNode(e.getKey(), e.getValue(),
                nodeShapes.getOrDefault(e.getKey(), "rect"), nodeColors.get(e.getKey())));
        }
        return new Flowchart(nodes, edges, direction, textColor, nodeColor);
    }

    /// Scans a body line for the byte offsets of every top-level `-->` — one that lies OUTSIDE any
    /// `[...]` / `{...}` / `|...|` span — so brackets, braces, and edge-label pipes never poison the
    /// edge split (the old `indexOf("-->")` split blind, minting a phantom node from `A[a-->b]`).
    /// A tiny state machine walks the line: a `[`/`{` opens a bracket span until its matching closer;
    /// a `|` TOGGLES a pipe span; while inside either span the scanner ignores everything but the
    /// span terminator. An unterminated span at end-of-line simply yields no further arrows (the
    /// endpoint validator then drops the malformed line). Non-nesting by design — matches the DSL,
    /// where a label may contain `-->` but not a nested delimiter.
    private static List<Integer> topLevelArrows(String line) {
        List<Integer> arrows = new ArrayList<>();
        char bracketClose = 0;   // 0 = not in a bracket/brace span, else the awaited ']' or '}'
        boolean inPipe = false;
        int n = line.length();
        int i = 0;
        while (i < n) {
            char c = line.charAt(i);
            if (bracketClose != 0) {
                if (c == bracketClose) {
                    bracketClose = 0;
                }
                i++;
            } else if (inPipe) {
                if (c == '|') {
                    inPipe = false;
                }
                i++;
            } else if (c == '[') {
                bracketClose = ']';
                i++;
            } else if (c == '{') {
                bracketClose = '}';
                i++;
            } else if (c == '|') {
                inPipe = true;
                i++;
            } else if (c == '-' && i + 2 < n && line.charAt(i + 1) == '-' && line.charAt(i + 2) == '>') {
                arrows.add(i);
                i += 3;
            } else {
                i++;
            }
        }
        return arrows;
    }

    /// Parses one flowchart endpoint token into `{id, labelOrNull, shapeOrNull, colorOrNull}`. A bare
    /// `id` returns null label/shape/colour (the id becomes its own label, shape defaults to rect);
    /// `id[Label]` claims a rect box, `id{Label}` a DIAMOND decision node (M1.3) — whichever delimiter
    /// appears first wins. A closed delimiter may be followed by exactly ONE trailing `#hex` COLOUR
    /// token (`A[Start] #22c55e`, normalized to `#rrggbb`) that overrides the node's default box fill;
    /// ANY OTHER trailing junk still DROPS the whole line (the hardening pin stays green — the colour
    /// is the sole recognized trailing token, so a typo can't sneak a phantom node through). Id and
    /// label are `cap()`'d. Returns `null` (→ caller drops the whole line, loud-not-silent) for any
    /// malformed endpoint: an empty token or id, an UNTERMINATED delimiter (`A[Start` with no `]`),
    /// NON-COLOUR trailing junk after a closed delimiter (`A[Start] junk`), a NESTED/unbalanced
    /// delimiter inside the label (`A[Start --> B[End]`), or a BARE id with a trailing `#hex`
    /// (`A #22c55e` — ambiguous with a multi-word id, so it drops rather than guessing).
    /// Peel a trailing `: label` off a diagram line at the FIRST colon (any
    /// surrounding spacing), returning {@code [preColonHead, label]} where label
    /// is null when absent/empty. Only the first colon delimits, so a colon
    /// INSIDE the label survives; an arrow token in the pre-colon head stays
    /// inert (callers scan the head). THE single colon-peel every label-carrying
    /// diagram type uses — so the no-space-colon regression class (sirentide/33
    /// sequence, /35 state) cannot be reintroduced one type at a time. Label is
    /// stripped but NOT capped (callers cap).
    static String[] peelLabel(String line) {
        int colon = line.indexOf(':');
        if (colon < 0) {
            return new String[] {line, null};
        }
        String raw = line.substring(colon + 1).strip();
        return new String[] {line.substring(0, colon), raw.isEmpty() ? null : raw};
    }

    private static String[] parseEndpoint(String tok) {
        tok = tok.strip();
        if (tok.isEmpty()) {
            return null;
        }
        int openB = tok.indexOf('[');
        int openC = tok.indexOf('{');
        int open;
        char closeCh;
        String shape;
        if (openB >= 0 && (openC < 0 || openB < openC)) {
            open = openB;
            closeCh = ']';
            shape = "rect";
        } else if (openC >= 0) {
            open = openC;
            closeCh = '}';
            shape = "diamond";
        } else {
            // Bare id: no delimiter. A trailing `#hex` on a bare id is ambiguous (a colour, or part of
            // a multi-word id?) → DROP rather than guess. Any other bare token stays a plain id.
            if (trailingHex(tok) != null) {
                return null;
            }
            return new String[] {cap(tok), null, null, null};   // bare id, default colour
        }
        String id = tok.substring(0, open).strip();
        if (id.isEmpty()) {
            return null;
        }
        int close = tok.indexOf(closeCh, open);
        if (close < 0) {
            return null;   // unterminated delimiter → drop
        }
        String color = null;
        String trailing = tok.substring(close + 1).strip();
        if (!trailing.isEmpty()) {
            // AFTER the closer, ONLY a single `#hex` colour token is allowed; anything else drops the
            // whole line (never mint a plausible-but-wrong node from `A[Start] junk`).
            if (SirentideContract.isHexColor(trailing)) {
                color = SirentideContract.normalizeColor(trailing);
            } else {
                return null;   // trailing junk after the closer → drop (keeps the hardening pin green)
            }
        }
        String label = tok.substring(open + 1, close);
        // A nested/unbalanced delimiter inside the label means the operator-scan swallowed an arrow
        // (or the box is unbalanced) — malformed, drop rather than mint a plausible-but-wrong node.
        if (label.indexOf('[') >= 0 || label.indexOf(']') >= 0
            || label.indexOf('{') >= 0 || label.indexOf('}') >= 0) {
            return null;
        }
        return new String[] {cap(id), cap(label.strip()), shape, color};
    }

    /// If `tok`'s LAST whitespace-separated token is a contract-legal `#hex` colour, returns that
    /// (raw, un-normalized); else `null`. Used to detect a colour suffix on a bare-id / state endpoint.
    private static String trailingHex(String tok) {
        int start = tok.length();
        while (start > 0 && !Character.isWhitespace(tok.charAt(start - 1))) {
            start--;
        }
        if (start > 0) {
            String tail = tok.substring(start).strip();
            if (SirentideContract.isHexColor(tail)) {
                return tail;
            }
        }
        return null;
    }

    /// Registers a node in first-seen order. A brand-new id enters with its decorated label (or the
    /// id itself when bare), subject to {@link #MAX_NODES}. An already-seen id UPGRADES from a
    /// default (label == id) to its first decorated label; the first DECORATED occurrence also sets
    /// the shape AND the colour (a later mention never changes any of them — first decoration wins).
    private static void registerNode(LinkedHashMap<String, String> map, Map<String, String> shapes,
                                     Map<String, String> colors, String[] nd) {
        String id = nd[0];
        String decoratedLabel = nd[1];   // null when the token was bare
        String shape = nd[2];            // null when the token was bare
        String color = nd[3];            // null when no trailing `#hex` colour
        if (!map.containsKey(id)) {
            if (map.size() >= MAX_NODES) {
                return;   // drop past the node cap (never throw / never allocate unboundedly)
            }
            map.put(id, decoratedLabel != null ? decoratedLabel : id);
        } else if (decoratedLabel != null && map.get(id).equals(id)) {
            map.put(id, decoratedLabel);   // first decorated occurrence upgrades a bare default
        }
        if (shape != null && map.containsKey(id) && !shapes.containsKey(id)) {
            shapes.put(id, shape);   // first decorated occurrence wins the shape too
        }
        if (color != null && map.containsKey(id) && !colors.containsKey(id)) {
            colors.put(id, color);   // first colour-bearing occurrence wins the colour
        }
    }

    /// Parses a sequence diagram: actors across the top and time-ordered messages between them.
    /// ```
    /// sequence
    /// Alice ->> Bob   : Request token
    /// Bob  -->> Alice : Token
    /// Alice ->> Alice : Validate locally
    /// ```
    /// Each body line is `FROM ARROW TO : label`. The ARROW is a CALL — `->>` or its alias `->`
    /// (solid, filled-triangle head) — or a REPLY — `-->>` or its alias `-->` (lighter, open-V head).
    ///
    /// SPLIT DISCIPLINE (mirrors the flowchart operator-scan): split at the FIRST ` : ` first — that
    /// is the OPTIONAL label delimiter, and the label follows the whole arrow region — then scan the
    /// pre-colon HEAD for the arrow token. So an arrow INSIDE the label (`A ->> B : retry -> escalate`)
    /// is inert (post-colon), never a mis-split. The head scan is LEFTMOST + LONGEST-at-position
    /// ({@link #scanSeqArrow}): at each offset it tries `-->>` (len 4) before `->>`/`-->` (len 3)
    /// before `->` (len 2), so a longer form never mis-splits (`->>` is a substring of `-->>`; `->` of
    /// all of them). CONSEQUENCE: actor names cannot contain an arrow token or ` : ` (documented).
    ///
    /// Both endpoints auto-register in first-seen order (a self-message `A ->> A` registers `A` once).
    /// A malformed line — no arrow token in the head, or an empty endpoint — is DROPPED whole (never
    /// throws, DESIGN §6). Caps: {@link #MAX_ACTORS} actors, {@link #MAX_DATA_ROWS} messages;
    /// ids/labels `cap()`'d. A bare `sequence` body (no non-blank lines) → a Sequence with no actors
    /// and `bodyHadContent=false` (an intentional blank canvas). A NON-EMPTY body that parses to zero
    /// actors (every line malformed) sets `bodyHadContent=true` so layout degrades VISIBLY.
    private static Diagram parseSequence(String[] lines, String[] header, String textColor) {
        // Header `nodecolor=#hex` colours ALL actor heads (per-actor colours are a follow-up — no
        // actor-decl syntax yet). null → the layout's built-in head fill.
        String nodeColor = parseNodeColor(header);
        LinkedHashSet<String> actors = new LinkedHashSet<>();
        List<SeqMessage> messages = new ArrayList<>();
        boolean bodyHadContent = false;
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            bodyHadContent = true;   // a non-blank body line existed (even if it turns out malformed)
            // Peel the OPTIONAL label at the FIRST colon, ANY spacing (`B : hi`, `B: hi`, `B:hi` —
            // requiring " : " silently broke the common no-space form: Lattice, sirentide/33). The
            // arrow lives in the pre-colon HEAD, so an arrow token in the label is inert
            // (operator-scan discipline, mirrors the flowchart); a colon INSIDE the label survives
            // because only the FIRST colon delimits. Actor names cannot contain ':' (as before).
            String[] peeled = peelLabel(line);
            String head = peeled[0];
            String label = peeled[1] == null ? null : cap(peeled[1]);
            // Scan the head for the arrow token (leftmost, longest-at-position). null → no arrow → drop.
            SeqArrow arrow = scanSeqArrow(head);
            if (arrow == null) {
                continue;   // no arrow token in the head → malformed, drop the line
            }
            String from = cap(head.substring(0, arrow.pos()).strip());
            String to = cap(head.substring(arrow.pos() + arrow.len()).strip());
            if (from.isEmpty() || to.isEmpty()) {
                continue;   // empty endpoint → malformed, drop the line
            }
            // Register both endpoints (first-seen order), each subject to the actor cap. A message is
            // kept only if BOTH endpoints registered (so an over-cap actor drops its messages too).
            registerActor(actors, from);
            registerActor(actors, to);
            if (messages.size() < MAX_DATA_ROWS && actors.contains(from) && actors.contains(to)) {
                messages.add(new SeqMessage(from, to, label, arrow.reply()));
            }
        }
        return new Sequence(new ArrayList<>(actors), messages, textColor, nodeColor, bodyHadContent);
    }

    /// A located sequence-message arrow: byte `pos` in the head, token `len`, and whether it is a
    /// REPLY (`-->>` / `-->`) rather than a CALL (`->>` / `->`).
    private record SeqArrow(int pos, int len, boolean reply) {}

    /// Scans a head segment for the arrow token — LEFTMOST match, LONGEST token at that position, so a
    /// longer form never mis-splits (`->>` is a substring of `-->>`; `->` a substring of all). The
    /// left-to-right walk guarantees we never start MID-token: a `-->>` at offset j is caught at j, so
    /// the loop never reaches j+1 to spuriously match the embedded `->>`. Returns null when the head
    /// carries no arrow token (→ the caller drops the line, loud-not-silent). `-->>`/`-->` are replies;
    /// `->>`/`->` are calls.
    private static SeqArrow scanSeqArrow(String head) {
        int n = head.length();
        for (int i = 0; i < n; i++) {
            if (head.startsWith("-->>", i)) {
                return new SeqArrow(i, 4, true);    // reply
            }
            if (head.startsWith("->>", i)) {
                return new SeqArrow(i, 3, false);   // call
            }
            if (head.startsWith("-->", i)) {
                return new SeqArrow(i, 3, true);    // reply alias
            }
            if (head.startsWith("->", i)) {
                return new SeqArrow(i, 2, false);   // call alias
            }
        }
        return null;
    }

    /// Registers an actor in first-seen order, up to {@link #MAX_ACTORS}; past the cap a brand-new
    /// actor is dropped (never throws / never allocates unboundedly). An already-seen actor is a no-op.
    private static void registerActor(LinkedHashSet<String> actors, String id) {
        if (!actors.contains(id) && actors.size() < MAX_ACTORS) {
            actors.add(id);
        }
    }

    /// The reserved pseudostate node ids — mermaid's `[*]` maps to `__start__` when it is a
    /// transition SOURCE and `__end__` when it is a TARGET, so a single `[*]` token can resolve to
    /// EITHER depending on its role in a given hop (they are registered as two DISTINCT nodes).
    private static final String START_ID = "__start__";
    private static final String END_ID = "__end__";
    private static final String STATE_TOKEN = "[*]";

    /// Parses a mermaid-style state diagram — a directed graph of states + transitions, layered by the
    /// SAME engine flowcharts use (it wraps a {@link Flowchart} in a {@link StateDiagram}).
    /// ```
    /// state
    /// [*] --> Idle
    /// Idle --> Running : start
    /// Running --> Idle : stop
    /// Running --> [*]
    /// ```
    /// Each body line is either a TRANSITION (`SRC --> DST [ : label]`, chainable `A --> B --> C`) or
    /// a bare STATE declaration (`S`, or `S : display name`). The transition label is mermaid-style —
    /// it follows a `:` AFTER the destination, NOT `|pipes|`. So the line is split at its FIRST
    /// top-level ` : ` into (edges-part, tail); the tail is a TRANSITION label when the edges-part has
    /// arrows, or a state DISPLAY NAME when it does not. On a chained transition the label applies to
    /// the LAST hop only (mermaid semantics). `[*]` is the START pseudostate (id `__start__`) when it
    /// is a hop SOURCE and the END pseudostate (id `__end__`) when it is a hop TARGET; both carry an
    /// EMPTY label so layout draws a disc/bullseye with no text. Arrow splitting reuses
    /// {@link #topLevelArrows}. Malformed rows (empty endpoint, bare `[*]`) drop, never throw
    /// (DESIGN §6). Caps {@link #MAX_NODES}/{@link #MAX_EDGES} bound the graph. Empty body → a state
    /// diagram with no states (round-trips, NOT degraded to Empty).
    private static Diagram parseStateDiagram(String[] lines, String[] header, String textColor) {
        // Header `nodecolor=#hex` colours every state box (null → the built-in default); a per-state
        // trailing `#hex` (`Idle #22c55e`) overrides it, riding the same endpoint parse as flowcharts.
        String nodeColor = parseNodeColor(header);
        LinkedHashMap<String, String> nodeLabels = new LinkedHashMap<>();
        Map<String, String> nodeShapes = new java.util.HashMap<>();
        Map<String, String> nodeColors = new java.util.HashMap<>();
        List<FlowEdge> edges = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            // Split off the FIRST ` : ` tail. The colon FOLLOWS the destination, so scan arrows on the
            // pre-colon segment: the tail is a transition label (arrows present) or a display name (not).
            String[] peeled = peelLabel(line);
            String edgesPart = peeled[0].strip();
            String tail = peeled[1] == null ? null : cap(peeled[1]);
            List<Integer> arrows = topLevelArrows(edgesPart);
            if (arrows.isEmpty()) {
                // Bare state declaration: `S`, `S : display name`, or `S #hex` (per-state colour). A
                // bare `[*]` has no role → drop.
                String[] pc = peelStateEndpoint(edgesPart);
                String id = cap(pc[0]);
                if (id.isEmpty() || id.equals(STATE_TOKEN)) {
                    continue;
                }
                registerState(nodeLabels, nodeShapes, nodeColors, id, tail, pc[1]);
                continue;
            }
            // Tokenize the chain into endpoints separated by top-level `-->`, peel each endpoint's
            // optional trailing `#hex`, and validate the id is non-empty (drop the WHOLE line
            // otherwise — never half-wire a chain, DESIGN §6).
            List<String> ids = new ArrayList<>();
            List<String> colors = new ArrayList<>();
            List<String> raw = new ArrayList<>();
            raw.add(edgesPart.substring(0, arrows.get(0)).strip());
            for (int k = 0; k < arrows.size(); k++) {
                int segStart = arrows.get(k) + 3;
                int segEnd = (k + 1 < arrows.size()) ? arrows.get(k + 1) : edgesPart.length();
                raw.add(edgesPart.substring(segStart, segEnd).strip());
            }
            boolean dropped = false;
            for (String ep : raw) {
                String[] pc = peelStateEndpoint(ep);
                if (pc[0].isEmpty()) {
                    dropped = true;
                    break;
                }
                ids.add(pc[0]);
                colors.add(pc[1]);
            }
            if (dropped) {
                continue;
            }
            // Wire one edge per hop. `[*]` resolves per ROLE: as a hop's SOURCE it is __start__, as its
            // TARGET it is __end__ (so the same token in `A --> [*] --> B` is END then START). A
            // pseudostate never takes a colour (its disc fill is fixed). The label rides the LAST hop.
            int lastHop = ids.size() - 2;
            for (int k = 0; k < ids.size() - 1; k++) {
                boolean fromStar = ids.get(k).equals(STATE_TOKEN);
                boolean toStar = ids.get(k + 1).equals(STATE_TOKEN);
                String from = fromStar ? START_ID : cap(ids.get(k));
                String to = toStar ? END_ID : cap(ids.get(k + 1));
                registerState(nodeLabels, nodeShapes, nodeColors, from, null, fromStar ? null : colors.get(k));
                registerState(nodeLabels, nodeShapes, nodeColors, to, null, toStar ? null : colors.get(k + 1));
                String hopLabel = (k == lastHop) ? tail : null;
                if (edges.size() < MAX_EDGES
                    && nodeLabels.containsKey(from) && nodeLabels.containsKey(to)) {
                    edges.add(new FlowEdge(from, to, hopLabel));
                }
            }
        }
        List<FlowNode> nodes = new ArrayList<>();
        for (Map.Entry<String, String> e : nodeLabels.entrySet()) {
            nodes.add(new FlowNode(e.getKey(), e.getValue(),
                nodeShapes.get(e.getKey()), nodeColors.get(e.getKey())));
        }
        return new StateDiagram(new Flowchart(nodes, edges, "TD", textColor, nodeColor));
    }

    /// Peels an OPTIONAL trailing `#hex` colour off a state endpoint token, returning
    /// `{idPart, colorOrNull}` (colour normalized to canonical `#rrggbb`). Mirrors the flowchart
    /// endpoint's trailing-colour rule for state lines (`Idle #22c55e`). A token with no
    /// whitespace-separated trailing hex is returned unchanged with a `null` colour.
    private static String[] peelStateEndpoint(String tok) {
        String hex = trailingHex(tok);
        if (hex != null) {
            int cut = tok.length() - hex.length();
            return new String[] {tok.substring(0, cut).strip(), SirentideContract.normalizeColor(hex)};
        }
        return new String[] {tok, null};
    }

    /// Registers a state (or pseudostate) in first-seen order, up to {@link #MAX_NODES}. The
    /// pseudostates `__start__`/`__end__` get shape `"start"`/`"end"` and an EMPTY label (a disc, no
    /// text); a normal state gets shape `"state"` and defaults its label to its id. A `displayName`
    /// (from a bare `S : name` decl) UPGRADES a state whose label is still the default id — the first
    /// display name wins, a later bare mention never overwrites it. A per-state `color` (from a
    /// trailing `#hex`) sets the box fill on first colour-bearing occurrence; pseudostates never carry
    /// a colour (the caller passes `null` for `[*]`).
    private static void registerState(LinkedHashMap<String, String> map, Map<String, String> shapes,
                                      Map<String, String> colors, String id, String displayName,
                                      String color) {
        String shape;
        String defaultLabel;
        if (id.equals(START_ID)) {
            shape = "start";
            defaultLabel = "";
        } else if (id.equals(END_ID)) {
            shape = "end";
            defaultLabel = "";
        } else {
            shape = "state";
            defaultLabel = id;
        }
        if (!map.containsKey(id)) {
            if (map.size() >= MAX_NODES) {
                return;   // drop past the node cap (never throw / allocate unboundedly)
            }
            map.put(id, displayName != null ? displayName : defaultLabel);
            shapes.put(id, shape);
        } else if (displayName != null && map.get(id).equals(defaultLabel)) {
            map.put(id, displayName);   // first display name upgrades the default id label
        }
        if (color != null && map.containsKey(id) && !colors.containsKey(id)) {
            colors.put(id, color);   // first colour-bearing occurrence wins the box fill
        }
    }

    /// Parses the shared `"label" : value` rows (used by both pie and xychart). A malformed row
    /// is skipped rather than failing the whole diagram (docs/DESIGN.md §6: never fail the bake).
    private static List<Slice> parseData(String[] lines, boolean dateAware) {
        List<Slice> data = new ArrayList<>();
        for (int i = 1; i < lines.length && data.size() < MAX_DATA_ROWS; i++) {
            String line = lines[i].strip();
            if (line.isEmpty()) {
                continue;
            }
            int colon = line.lastIndexOf(':');
            if (colon < 0) {
                continue;
            }
            String label = cap(unquote(line.substring(0, colon).strip()));
            // A data value is a SINGLE token, optionally followed by a trailing colour token:
            // `"Reviews" : 40 #4e79a7` → value=`40`, colour=`#4e79a7`. Split off the first token as
            // the value; if a trailing token follows AND matches the COLOR grammar it becomes the
            // per-item fill (normalized to canonical `#rrggbb`). A trailing token that is NOT a colour
            // (a typo) is simply IGNORED — the value still parses → palette fallback, never fails bake.
            String[] tok = line.substring(colon + 1).strip().split("\\s+", 2);
            String valueTok = tok[0];
            String color = null;
            if (tok.length == 2) {
                String tail = tok[1].strip();
                if (SirentideContract.isHexColor(tail)) {   // per-item fill is hex-only (H1): currentColor/none fall through to palette
                    color = SirentideContract.normalizeColor(tail);
                }
            }
            try {
                // Date-aware rows (timeline) map ISO dates to epoch-day for proportional placement;
                // a bare year / plain number parses as its numeric value either way.
                double value = dateAware
                    ? AxisScale.parseDomainValue(valueTok)
                    : Double.parseDouble(valueTok);
                // Non-finite (NaN / Infinity, incl. "1e400" == Infinity which throws NO exception)
                // must not reach layout/emit — skip the row (never fail the bake).
                if (!Double.isFinite(value)) {
                    continue;
                }
                // An ISO-date token became an opaque epoch-day for placement; remember the original
                // so the timeline shows `2020-01-15`, not the epoch number `18276` (A2). A bare
                // year / plain number keeps a null valueLabel and formats numerically as before.
                String valueLabel = (dateAware && AxisScale.isDateToken(valueTok)) ? valueTok : null;
                data.add(new Slice(label, value, color, valueLabel));
            } catch (NumberFormatException e) {
                // Skip a malformed row.
            }
        }
        return data;
    }

    /// Peels an OPTIONAL trailing colour token off a stripped data value (the text after the `:`).
    /// Returns `{valuePart, colorOrNull}`: if the LAST whitespace-separated token matches the
    /// SirentideContract COLOR grammar it is removed and returned (normalized to canonical `#rrggbb`,
    /// so a 3-digit `#333` becomes `#333333` on the IR); the leading value part is returned stripped.
    /// If there is no trailing colour token (or the input is a single token), the value is returned
    /// unchanged with a `null` colour — an invalid/unparseable trailing token is NEVER treated as a
    /// colour (it stays on the value, whose numeric parse then rejects it → palette fallback, no throw).
    private static String[] peelColor(String tok) {
        int start = tok.length();
        while (start > 0 && !Character.isWhitespace(tok.charAt(start - 1))) {
            start--;
        }
        // start > 0 means there is at least one whitespace char before the final token.
        if (start > 0) {
            String tail = tok.substring(start);
            if (SirentideContract.isHexColor(tail)) {   // per-item fill is hex-only (H1): currentColor/none fall through to palette
                return new String[] {tok.substring(0, start).strip(), SirentideContract.normalizeColor(tail)};
            }
        }
        return new String[] {tok, null};
    }

    private static String unquote(String s) {
        if (s.length() >= 2 && s.startsWith("\"") && s.endsWith("\"")) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }

    /// Truncates an over-long label to the trust cap so a pathological label can't blow up the
    /// glyph run / output size (DESIGN §6/§7: bounded, inert degrade — never throw).
    private static String cap(String label) {
        return label.length() > MAX_LABEL_LEN ? label.substring(0, MAX_LABEL_LEN) : label;
    }
}
