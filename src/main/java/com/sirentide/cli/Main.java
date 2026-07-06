package com.sirentide.cli;

import com.sirentide.api.Sirentide;
import com.sirentide.parse.DslParser;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/// CLI entry: read a Sirentide DSL from stdin, write baked SVG to stdout (mirrors LatteX's CLI
/// shape). `--batch` (NUL-delimited, many-per-invocation, one JVM) lands with real rendering in
/// M1 — it is the amortization lever for many diagrams per page.
public final class Main {

    private Main() {}

    public static void main(String[] args) throws IOException {
        // M0: single-shot stdin → stdout. --batch is a documented stub until M1.
        // Bound the read to the parser's source cap (+1 to detect overflow): a runaway stdin
        // degrades to the inert shell in render() rather than OOMing on readAllBytes.
        byte[] bytes = System.in.readNBytes(DslParser.MAX_SOURCE_BYTES + 1);
        String dsl = new String(bytes, StandardCharsets.UTF_8);
        System.out.print(Sirentide.render(dsl));
    }
}
