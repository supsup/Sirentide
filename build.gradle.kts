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
version = "0.5.0"

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
}

application {
    // Classpath app for M0 (non-modular — keeps the build + tests simple). A module-info can
    // be added later if we want JPMS strictness; it is not load-bearing for the scaffold.
    mainClass = "com.sirentide.cli.Main"
}

val releaseSourceRevision = providers.exec {
    workingDir(layout.projectDirectory)
    commandLine("git", "rev-parse", "--verify", "HEAD^{commit}")
}.standardOutput.asText.map { it.trim() }

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
