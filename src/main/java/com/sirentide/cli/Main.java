package com.sirentide.cli;

import com.sirentide.api.Sirentide;
import java.io.IOException;
import java.nio.charset.StandardCharsets;

/// CLI entry: read a Sirentide DSL from stdin, write baked SVG to stdout (mirrors LatteX's CLI
/// shape). `--batch` (NUL-delimited, many-per-invocation, one JVM) lands with real rendering in
/// M1 — it is the amortization lever for many diagrams per page.
public final class Main {

    private Main() {}

    public static void main(String[] args) throws IOException {
        // M0: single-shot stdin → stdout. --batch is a documented stub until M1.
        String dsl = new String(System.in.readAllBytes(), StandardCharsets.UTF_8);
        System.out.print(Sirentide.render(dsl));
    }
}
