import java.security.MessageDigest
import java.util.HexFormat
import java.util.jar.JarFile

plugins {
    `java-library`
    application
    `maven-publish`
}

group = "com.sirentide"
// A real, immutable release version — NOT a rolling SNAPSHOT. Same discipline as LatteX:
// a consumer pins the exact version; a change requires an explicit bump + republish, so a
// pinned consumer can never silently go stale. Bump on each downstream-relevant release.
// 0.4.0: the heatmap type (21 → 22) — continuous-score grid on a single-hue sequential
// ramp + legend; to be vendored into stafficy /docs as sirentide-0.4.0.jar (part B).
// 0.5.0: the 2026-07-30 immutable cut — render-check CLI, bounded frame decks + paired assets,
// rootsystem projections (22 → 23 types), Docker watch mode, and layout/diagnostic hardening.
// 0.6.0: the post-0.5.0 development line; contents remain IN PROGRESS until the next cut.
version = "0.6.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
    withJavadocJar()
}

repositories {
    mavenCentral()
    // The LatteX render-only dependency (com.lattex:lattex, for math-in-labels) wires in at
    // M1, once its published artifact / composite resolution is confirmed. M0 core needs no
    // runtime deps — the whole point is a hermetic, zero-dep bake (see docs/DESIGN.md §2).
}

// Sirentide ships ZERO runtime dependencies (hermetic bake). Test-scope only for now.
dependencies {
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // BrewShot: real-browser screenshot + in-page audit harness (Confluence, PROJECT/brewshot).
    // TEST SCOPE ONLY — the zero-runtime-dependency bake is untouched. Pins what XML can't see
    // (labels/shapes escaping the canvas: the negative-x clip + y=226 stacking from review 14).
    testImplementation(files("libs/brewshot-0.6.0.jar"))
    // LatteX: the real LaTeX->SVG math backend for the math-in-labels moat proof
    // (MathInLabelsRealRenderTest + LatteXMathFragmentRenderer). TEST SCOPE ONLY — the core bake
    // stays hermetic (ZERO runtime dependencies). A downstream consumer that wants baked math
    // supplies this jar + a renderer itself; Sirentide never depends on LatteX at runtime.
    testImplementation(files("libs/lattex-0.6.0.jar"))
}

tasks.test {
    useJUnitPlatform()
    // StagedReleaseNoteCountsTest reads RELEASE-NOTE-ENTRY.md, which is NOT a source file, so
    // Gradle would consider `test` UP-TO-DATE after a doc-only edit and skip the very run that
    // matters — the guard would be inert exactly when someone changes the number it guards.
    // (Observed, not theorised: editing the count and re-running produced BUILD SUCCESSFUL in
    // 252ms because the task never executed.) Declaring the file as an input makes a doc edit
    // invalidate the task.
    //
    // `inputs.files` (PLURAL) and not `inputs.file(...).optional(true)`. This is the whole
    // reason for the distinction, and my first version had it wrong: `optional(true)` does NOT
    // tolerate an absent file. With the note deleted the `test` task fails at CONFIGURATION
    // time — "property 'stagedReleaseNote' specifies file ... which doesn't exist" — before a
    // single test runs. That matters more than an ordinary bug because deleting this file is
    // the PLANNED happy path: it merges into RELEASE_NOTES and leaves this name, at which
    // point the guard is supposed to retire itself, and StagedReleaseNoteCountsTest already
    // handles that with a Files.exists early return. The Gradle half failed FIRST, so that
    // graceful path was unreachable and whoever merged the note would get a red build naming
    // a file they had just legitimately deleted. A FileCollection tolerates absent entries.
    // (Fixpoint, sirentide/868; reproduced here before fixing.)
    inputs.files(layout.projectDirectory.file("RELEASE-NOTE-ENTRY.md"))
        .withPropertyName("stagedReleaseNote")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // Forward the golden-regen switch to the forked test JVM so
    // `./gradlew test -Dsirentide.updateGolden=true` actually reaches GoldenSvgTest.
    systemProperty("sirentide.updateGolden", System.getProperty("sirentide.updateGolden", "false"))
    // Same for the showcase-page regen switch, so
    // `./gradlew test -Dsirentide.updateShowcase=true` reaches ShowcaseGenTest.
    systemProperty("sirentide.updateShowcase", System.getProperty("sirentide.updateShowcase", "false"))
    testLogging { events("skipped", "failed") }

    // CI-honesty guard: the BrewShot browser-eyes pins assume-skip when Chrome is
    // absent (the only skips in this suite). CI installs Chrome and sets
    // SIRENTIDE_REQUIRE_CHROME, so ANY skip there means the browser pins silently
    // didn't run — a green that tested nothing. Fail instead.
    val requireChrome = System.getenv("SIRENTIDE_REQUIRE_CHROME")
        ?.let { it == "1" || it.equals("true", true) || it.equals("yes", true) } ?: false
    afterSuite(KotlinClosure2<TestDescriptor, TestResult, Unit>({ desc, result ->
        if (desc.parent == null && requireChrome && result.skippedTestCount > 0) {
            throw GradleException(
                "SIRENTIDE_REQUIRE_CHROME is set but ${result.skippedTestCount} test(s) were " +
                "SKIPPED — the BrewShot browser pins must run (Chrome present) or fail, never " +
                "skip green (green-that-tested-nothing guard)."
            )
        }
    }))

    // THE GOLDEN WRITE AUDIT (needs-fix PROJECT/sirentide 1084). Enumerate WRITES, not writers.
    //
    // Three rounds of enumerating writers failed. Each pinned how a regen site SPELLS itself -- the
    // flag literal, then the flag constant, then the directory construct -- and each round the
    // reviewer found another spelling: a two-argument Path.of, a different package, a class named
    // GoldenRegen.java in a subpackage. Any such scan is a spelling predicate, and a test can always
    // reach Files.writeString with a path it computed itself.
    //
    // WHAT THIS CLAIMS, precisely, and it has been narrowed twice by measurement rather than by
    // caution (needs-fix 1086, then 1088). It began as "no golden's CONTENT changed without going
    // through the gate", which was falsified by a post-gate overwrite: the name was legitimately in a
    // name-keyed ledger, so the audit cleared it. Content hashes fixed that and the claim widened to
    // "every golden on disk carries the bytes the gate wrote".
    //
    // THEN THAT WAS FALSIFIED TOO, by forging the ledger. It is written by the same test JVM whose
    // writes it vouches for, so a class ordered last can overwrite a golden, hash the rogue bytes
    // itself, and append its own line. Duplicate names are now fatal, which breaks that route, because
    // a forgery for a gated name necessarily duplicates the legitimate line.
    //
    // SO THE CLAIM, WITH ITS TRUST BOUNDARY NAMED: every golden on disk carries the bytes the gate
    // recorded writing, ASSUMING THE LEDGER ITSELF IS HONEST. The ledger is self-attested. This is a
    // defence against code that writes GOLDENS, not against code that writes the LEDGER, and full
    // closure needs a trust anchor the test JVM cannot mint. A bypass that rewrites a golden with
    // byte-identical content also remains invisible, and is harmless by construction since nothing
    // unverified reached the artifact.
    //
    // So this asks the only question that cannot be spelled around: did every golden that CHANGED
    // on disk go through the gate? GoldenRegen publishes a ledger of what it wrote; this snapshots
    // the directory before the test JVM starts and diffs it after. The BEFORE boundary is why this
    // lives in the build rather than in GoldenRegen: a bypass in another package need never load
    // GoldenRegen at all, so no static initializer of ours is reliably "before" it.
    val goldenAuditDir = layout.projectDirectory.dir("src/test/resources/golden").asFile
    val goldenAuditLedger = layout.buildDirectory.file("golden-regen-ledger.txt").get().asFile
    var goldenAuditBefore: Map<String, String> = emptyMap()

    doFirst {
        // A STALE ledger would credit this run with a previous run's gated writes, which is the one
        // way this audit could report a false clean. Deleted rather than appended to.
        goldenAuditLedger.delete()
        goldenAuditBefore = goldenFileHashes(goldenAuditDir)
    }

    doLast {
        val after = goldenFileHashes(goldenAuditDir)
        val changed = (goldenAuditBefore.keys + after.keys)
            .filter { goldenAuditBefore[it] != after[it] }
            .sorted()
        // NOTE ON THE EARLIER "TRADE" IN THIS FILE, which was exactly INVERTED (needs-fix 1086).
        // I wrote that the audit only had teeth on a regen run and that a bypass would sit
        // unnoticed until someone regenerated. Both halves are backwards, and the reviewer's run
        // showed it: doFirst DELETES the ledger, so on an ordinary run `recorded` is empty and ANY
        // changed golden fails the build immediately. The REGEN run is the permissive one, because
        // that is the run where every tracked name is legitimately recorded. I described my own
        // mechanism from the armchair instead of running it.
        // A MISSING ledger reads as "nothing was gated", so every change is unrecorded. Fail closed:
        // losing the ledger must never manufacture a pass.
        val recorded = readGoldenLedger(goldenAuditLedger)
        val unrecorded = goldenAuditFindings(goldenAuditBefore, after, recorded)

        // DELIVERY TO THE OPERATOR (needs-fix 1084, second finding). announceRegen writes the
        // banner to System.err, which lands only in the XML system-err: testLogging above does not
        // set showStandardStreams, so an operator running the documented regen command saw BUILD
        // SUCCESSFUL and nothing at all. GoldenRegen's own doc says a green build must never be
        // indistinguishable from a regen; at the console it still was. Emission was asserted,
        // DELIVERY was not. This is the delivery.
        // KEYED ON THE REGEN HAVING HAPPENED, not on bytes having moved. A clean regen is
        // byte-stable -- the gate rewrites 34 goldens with identical content -- so keying this on
        // the changed set would print NOTHING on exactly the run the operator most needs told
        // about, and "a green build must never be indistinguishable from a regen" would still be
        // false at the console. The skipped byte-comparison is the fact worth delivering, and it
        // is skipped whether or not the bytes moved.
        // THE BANNER MUST NOT LAUNDER THE BYPASS (needs-fix 1086). The first version reported the
        // ungated file INSIDE the count of gated ones and then said "Review the diff", which
        // invites the operator to accept exactly the bytes nothing verified. The two sets are now
        // named separately, and the suspect ones are never described as reviewable.
        val gatedChanges = changed.filter { it !in unrecorded }
        if (recorded.isNotEmpty() || changed.isNotEmpty()) {
            logger.lifecycle(
                "SIRENTIDE GOLDEN REGEN: " + recorded.size + " golden(s) rewritten through the " +
                "gate. The byte-comparison assertion was SKIPPED for all " + recorded.size +
                "; this run did NOT verify them against a prior expectation. Review the diff for " +
                "the " + gatedChanges.size + " with changed content: " + gatedChanges +
                (if (unrecorded.isEmpty()) {
                    ""
                } else {
                    ". DO NOT review-and-accept the " + unrecorded.size + " listed as a bypass " +
                    "below; the gate did not write those bytes: " + unrecorded
                })
            )
        }

        if (unrecorded.isNotEmpty()) {
            throw GradleException(
                "GOLDEN WRITE BYPASS: " + unrecorded.size + " tracked golden(s) do not carry the " +
                "bytes GoldenRegen wrote, so nothing asserted they are real renders: " +
                unrecorded + ". Either the gate never wrote them, or something overwrote them " +
                "AFTER it did. The ledger records the hash of what the gate wrote, so a later " +
                "write to a legitimately-gated name is caught too."
            )
        }
    }

}

/// SHA-256 per tracked golden, keyed by file name. Used by the golden write audit on the test task
/// to establish what CHANGED across the run, which is the half a spelling scan can never see.
fun goldenFileHashes(dir: java.io.File): Map<String, String> {
    val files = dir.listFiles()?.filter { it.isFile } ?: emptyList()
    return files.associate { f ->
        val digest = MessageDigest.getInstance("SHA-256").digest(f.readBytes())
        f.name to HexFormat.of().formatHex(digest)
    }
}

/// THE AUDIT DECISION, as a pure function so it can be exercised by a fixture (needs-fix 1086).
///
/// A finding is a golden whose CONTENT changed and whose bytes on disk are not the bytes the gate
/// recorded writing. Name-presence is not enough: a rogue write landing on a tracked golden AFTER
/// the gate wrote it leaves the name legitimately in the ledger, and a name-keyed check clears it.
/// Comparing HASHES closes that, and it widens the claim from "no golden's content changed without
/// going through the gate" to "every golden on disk carries the bytes the gate wrote".
fun goldenAuditFindings(
    before: Map<String, String>,
    after: Map<String, String>,
    ledger: Map<String, String>
): List<String> =
    (before.keys + after.keys)
        .filter { before[it] != after[it] }
        .filter { ledger[it] == null || ledger[it] != after[it] }
        .sorted()

/// Reads `name<TAB>sha256` lines. A malformed or absent ledger yields an EMPTY map, which fails
/// closed: every changed golden is then unrecorded.
///
/// A DUPLICATE NAME IS FATAL, not last-wins (needs-fix 1088 finding A). The reviewer FORGED a
/// ledger: a test class ordered last overwrote a golden with rogue bytes, hashed them itself, and
/// APPENDED its own `pie<TAB>hash` line. `.associate{}` silently prefers the last line, so the
/// forgery became authoritative, the run went BUILD SUCCESSFUL with the rogue bytes on disk, and
/// the banner described the file as a reviewable gated change.
///
/// Two different hashes recorded for one name is a CONTRADICTION, and silently preferring either
/// is the same shape of defect as the name-keyed ledger this replaced. Refusing outright also
/// breaks the append route, because a forgery for a gated name necessarily duplicates the
/// legitimate line.
///
/// THIS IS NOT FULL CLOSURE AND MUST NOT BE READ AS IT. The ledger is SELF-ATTESTED: it is written
/// by the same test JVM whose writes it vouches for, with no permissions, checksum, token or lock
/// binding a line to a write. It is a defence against code that writes GOLDENS, not against code
/// that writes the LEDGER. Full closure needs a trust anchor the test JVM cannot mint.
fun readGoldenLedger(file: java.io.File): Map<String, String> {
    if (!file.exists()) return emptyMap()
    val entries = file.readLines()
        .filter { it.isNotBlank() && it.contains('\t') }
        .map { line -> (line.substringBefore('\t') + ".svg") to line.substringAfter('\t').trim() }
    val duplicates = entries.groupBy { it.first }.filterValues { it.size > 1 }.keys.sorted()
    if (duplicates.isNotEmpty()) {
        throw GradleException(
            "GOLDEN LEDGER CONTRADICTION: " + duplicates.size + " name(s) recorded more than " +
            "once: " + duplicates + ". The gate writes each name once per run, so a duplicate " +
            "means something appended to the ledger. Two hashes for one name is a contradiction " +
            "and preferring either would let an appended line overrule the gate's own record."
        )
    }
    return entries.toMap()
}

/// THE BUILD-LEVEL FIXTURE (needs-fix 1086, ruled). The audit's decision lives in Kotlin and no
/// JUnit test can reach it, so it was held only by ad-hoc rogue runs. Those found four evasions and
/// MISSED the post-gate overwrite, which the reviewer found in twenty minutes. Case 4 below is that
/// case, and it is the one no rogue-route demonstration can reach by construction: it needs a write
/// ordered AFTER a legitimate gated write to the same name.
val goldenAuditSelfTest = tasks.register("goldenAuditSelfTest") {
    group = "verification"
    description = "Exercises the golden write-audit decision against known-answer fixtures."
    doLast {
        val h1 = "1111111111111111111111111111111111111111111111111111111111111111"
        val h2 = "2222222222222222222222222222222222222222222222222222222222222222"
        val failures = mutableListOf<String>()

        fun check(case: String, before: Map<String, String>, after: Map<String, String>,
                  ledger: Map<String, String>, expected: List<String>) {
            val actual = goldenAuditFindings(before, after, ledger)
            if (actual != expected) {
                failures += case + ": expected " + expected + " but got " + actual
            }
        }

        // 1. nothing changed, nothing gated. An ordinary build must stay silent.
        check("unchanged-and-ungated", mapOf("a.svg" to h1), mapOf("a.svg" to h1),
            emptyMap(), emptyList())

        // 2. a legitimate gated write: content changed and the bytes ARE the gate's.
        check("gated-write", mapOf("a.svg" to h1), mapOf("a.svg" to h2),
            mapOf("a.svg" to h2), emptyList())

        // 3. a plain bypass: changed, never recorded.
        check("ungated-write", mapOf("a.svg" to h1), mapOf("a.svg" to h2),
            emptyMap(), listOf("a.svg"))

        // 4. THE POST-GATE OVERWRITE. The gate wrote h1 and recorded it; something then wrote h2
        //    over it. The NAME is legitimately in the ledger, which is exactly why a name-keyed
        //    audit cleared this and shipped the rogue bytes.
        check("post-gate-overwrite", mapOf("a.svg" to "0".repeat(64)), mapOf("a.svg" to h2),
            mapOf("a.svg" to h1), listOf("a.svg"))

        // 5. a new file nobody gated.
        check("new-ungated-file", emptyMap(), mapOf("new.svg" to h1), emptyMap(), listOf("new.svg"))

        // 6. a new file the gate wrote.
        check("new-gated-file", emptyMap(), mapOf("new.svg" to h1),
            mapOf("new.svg" to h1), emptyList())

        // 7. FAIL CLOSED ON A REALLY-ABSENT FILE, through the REAL reader (needs-fix 1088 B).
        //    This case used to pass emptyMap() literally, which made its inputs BYTE-IDENTICAL to
        //    case 3: its identity came from its NAME, not from anything it exercised. The reviewer
        //    measured the consequence -- deleting readGoldenLedger's if-not-exists guard left this
        //    printing "8 cases pass" while an ordinary run died with FileNotFoundException. The
        //    behaviour the case is named for was broken and the fixture never noticed.
        val absent = layout.buildDirectory.file("golden-audit-selftest/no-such-ledger.txt")
            .get().asFile
        absent.parentFile.mkdirs()
        absent.delete()
        val fromAbsent = readGoldenLedger(absent)
        if (fromAbsent.isNotEmpty()) {
            failures += "missing-ledger: the reader must yield NOTHING for an absent file, got " +
                fromAbsent
        }
        check("missing-ledger", mapOf("a.svg" to h1), mapOf("a.svg" to h2),
            fromAbsent, listOf("a.svg"))

        // 9. A WELL-FORMED LEDGER ROUND-TRIPS. Positive control on the reader: without it, a reader
        //    that returned empty for EVERYTHING would satisfy case 7 and look fail-closed while
        //    being merely broken.
        val realLedger = layout.buildDirectory.file("golden-audit-selftest/ledger.txt").get().asFile
        realLedger.writeText("a\t" + h2 + "\n")
        val parsed = readGoldenLedger(realLedger)
        if (parsed != mapOf("a.svg" to h2)) {
            failures += "ledger-round-trip: expected {a.svg=" + h2 + "} but got " + parsed
        }
        check("gated-write-via-real-reader", mapOf("a.svg" to h1), mapOf("a.svg" to h2),
            parsed, emptyList())

        // 10. THE FORGERY ROUTE (needs-fix 1088 A). A duplicate name must be FATAL, not last-wins.
        //     The reviewer appended his own line for a legitimately-gated name and it silently won.
        realLedger.writeText("a\t" + h1 + "\na\t" + h2 + "\n")
        val refused = try {
            readGoldenLedger(realLedger)
            false
        } catch (expected: GradleException) {
            true
        }
        if (!refused) {
            failures += "duplicate-ledger-line: a second line for the same name must be REFUSED, " +
                "not silently preferred"
        }
        realLedger.delete()

        // 8. a deleted golden is a content change too, and nothing gated a deletion.
        check("deleted-golden", mapOf("a.svg" to h1), emptyMap(), emptyMap(), listOf("a.svg"))

        if (failures.isNotEmpty()) {
            throw GradleException(
                "GOLDEN AUDIT SELF-TEST FAILED, so the write audit itself is not trustworthy:\n  " +
                failures.joinToString("\n  ")
            )
        }
        logger.lifecycle("golden audit self-test: 10 cases pass, 3 through the real reader")
    }
}

tasks.named("check") { dependsOn(goldenAuditSelfTest) }

application {
    // Classpath app for M0 (non-modular — keeps the build + tests simple). A module-info can
    // be added later if we want JPMS strictness; it is not load-bearing for the scaffold.
    mainClass = "com.sirentide.cli.Main"
}

// THE REVISION MAY BE SUPPLIED BY THE CALLER, because the build container has no git and
// cannot get one. `.dockerignore` excludes `.git`, so the Docker build context contains no
// repository at all — installing git in the build stage would NOT fix it, there is nothing
// for git to read. Since 2026-07-30 (d7c0521, the 0.5.0 cut) `docker build` has therefore
// failed outright on `:jar`:
//
//     Error while evaluating property 'sirentideSourceRevision' of task ':jar'
//        > A problem occurred starting process 'command 'git''
//
// Nothing rebuilt the image after that commit, so nobody hit it: the newest sirentide image
// on the host dates from THREE DAYS BEFORE the break.
//
// THE 40-HEX CHECK BELOW IS UNCHANGED AND IS THE POINT. The alternative fix — degrading the
// revision to "unknown" when git is absent — would defeat a deliberate release-integrity
// guarantee: an artifact must name the exact tree it was cut from. This keeps that guarantee
// and moves only the SOURCE of the value, from "shell out to git" to "git, or whoever already
// knows". An override that is not a real 40-hex commit still fails the build.
val releaseSourceRevision = providers.gradleProperty("sirentideSourceRevision")
    .orElse(providers.environmentVariable("SIRENTIDE_SOURCE_REVISION"))
    .orElse(
        providers.exec {
            workingDir(layout.projectDirectory)
            commandLine("git", "rev-parse", "--verify", "HEAD^{commit}")
        }.standardOutput.asText
    )
    .map { it.trim() }

// Make the plain library jar directly launchable: `java -jar build/libs/sirentide-<ver>.jar`.
tasks.jar {
    inputs.property("sirentideSourceRevision", releaseSourceRevision)
    manifest {
        attributes(
            "Main-Class" to "com.sirentide.cli.Main",
            "Implementation-Title" to "Sirentide",
            "Implementation-Version" to project.version.toString(),
        )
    }
    doFirst {
        val revision = releaseSourceRevision.get()
        check(revision.matches(Regex("[0-9a-f]{40}"))) {
            "Sirentide-Source-Revision requires one exact lowercase 40-hex git commit; got '$revision'"
        }
        manifest.attributes("Sirentide-Source-Revision" to revision)
    }
}

val releaseWorkingTreeStatus = providers.exec {
    workingDir(layout.projectDirectory)
    commandLine("git", "status", "--porcelain=v1", "--untracked-files=all")
}.standardOutput.asText.map { it.trim() }

val releaseArtifactFiles = providers.provider {
    val baseName = "${project.name}-${project.version}"
    listOf(
        layout.buildDirectory.file("libs/$baseName.jar").get().asFile,
        layout.buildDirectory.file("libs/$baseName-sources.jar").get().asFile,
        layout.buildDirectory.file("libs/$baseName-javadoc.jar").get().asFile,
    )
}

val releaseChecksumFiles = releaseArtifactFiles.map { artifacts ->
    artifacts.map { artifact -> artifact.resolveSibling("${artifact.name}.sha256") }
}

val verifyReleaseSource by tasks.registering {
    group = "verification"
    description = "Fails unless the immutable release build is bound to a clean, resolvable git commit."

    doLast {
        val revision = releaseSourceRevision.get()
        check(revision.matches(Regex("[0-9a-f]{40}"))) {
            "release source revision must resolve to one exact lowercase 40-hex commit; got '$revision'"
        }
        val releaseNotes = layout.projectDirectory.file("RELEASE_NOTES.md").asFile.readText()
        val quotedVersion = Regex.escape(project.version.toString())
        check(Regex("(?m)^## \\d{4}-\\d{2}-\\d{2} — Release \\*\\*$quotedVersion\\*\\*$")
            .containsMatchIn(releaseNotes)) {
            "RELEASE_NOTES.md must have a dated Release $version heading before an immutable cut"
        }
        check(!Regex("(?m)^## \\*\\*$quotedVersion\\*\\* — IN PROGRESS")
            .containsMatchIn(releaseNotes)) {
            "RELEASE_NOTES.md still marks $version IN PROGRESS"
        }
        val requireChrome = System.getenv("SIRENTIDE_REQUIRE_CHROME")
            ?.let { it == "1" || it.equals("true", true) || it.equals("yes", true) } ?: false
        check(requireChrome) {
            "releaseBuild requires SIRENTIDE_REQUIRE_CHROME=1 so browser pins cannot skip green"
        }
        val dirty = releaseWorkingTreeStatus.get()
        check(dirty.isEmpty()) {
            "immutable release builds require a clean worktree; git status reported:\n$dirty"
        }
        check(project.version.toString().matches(Regex("[0-9]+\\.[0-9]+\\.[0-9]+"))) {
            "immutable release version must be an exact X.Y.Z value; got '$version'"
        }
    }
}

val generateReleaseChecksums by tasks.registering {
    group = "build"
    description = "Writes SHA-256 sidecars for the three immutable release jars."
    dependsOn(tasks.jar, tasks.named("sourcesJar"), tasks.named("javadocJar"))
    inputs.files(releaseArtifactFiles)
    outputs.files(releaseChecksumFiles)

    doLast {
        releaseArtifactFiles.get().forEach { artifact ->
            check(artifact.isFile) { "missing release artifact: $artifact" }
            val digest = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(artifact.readBytes())
            )
            artifact.resolveSibling("${artifact.name}.sha256")
                .writeText("$digest  ${artifact.name}\n")
        }
    }
}

val verifyReleaseArtifacts by tasks.registering {
    group = "verification"
    description = "Verifies immutable release jars, provenance, paired assets, and checksums."
    dependsOn(generateReleaseChecksums)
    inputs.files(releaseArtifactFiles, releaseChecksumFiles)

    doLast {
        val revision = releaseSourceRevision.get()
        check(revision.matches(Regex("[0-9a-f]{40}"))) {
            "release source revision must resolve to one exact lowercase 40-hex commit; got '$revision'"
        }

        val artifacts = releaseArtifactFiles.get()
        val (mainJar, sourcesJar, javadocJar) = artifacts
        JarFile(mainJar).use { jar ->
            val attributes = checkNotNull(jar.manifest) { "main jar has no manifest" }.mainAttributes
            check(attributes.getValue("Main-Class") == "com.sirentide.cli.Main") {
                "main jar has the wrong Main-Class"
            }
            check(attributes.getValue("Implementation-Version") == project.version.toString()) {
                "main jar version does not match Gradle version $version"
            }
            check(attributes.getValue("Sirentide-Source-Revision") == revision) {
                "main jar must carry exact source revision $revision"
            }
            listOf(
                "com/sirentide/frames/sirentide-frames.js",
                "com/sirentide/frames/sirentide-frames.css",
            ).forEach { asset ->
                val entry = checkNotNull(jar.getJarEntry(asset)) { "main jar is missing paired asset $asset" }
                check(jar.getInputStream(entry).use { it.read() } != -1) {
                    "main jar carries an empty paired asset $asset"
                }
            }
        }
        JarFile(sourcesJar).use { jar ->
            check(jar.entries().asSequence().any { !it.isDirectory && it.name.endsWith(".java") }) {
                "sources jar contains no Java sources"
            }
        }
        JarFile(javadocJar).use { jar ->
            check(jar.entries().asSequence().any { !it.isDirectory && it.name.endsWith(".html") }) {
                "javadoc jar contains no HTML documentation"
            }
        }

        artifacts.forEach { artifact ->
            val sidecar = artifact.resolveSibling("${artifact.name}.sha256")
            val expected = HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(artifact.readBytes())
            )
            check(sidecar.readText() == "$expected  ${artifact.name}\n") {
                "checksum sidecar does not match ${artifact.name}"
            }
        }
    }
}

verifyReleaseSource.configure {
    mustRunAfter("clean")
}
tasks.named("build") {
    mustRunAfter(verifyReleaseSource)
}
verifyReleaseArtifacts.configure {
    mustRunAfter("build")
}

tasks.register("releaseBuild") {
    group = "build"
    description = "Cleans, fully builds, and verifies immutable release artifacts from a clean commit."
    dependsOn("clean", verifyReleaseSource, "build", verifyReleaseArtifacts)
}
