# Rendering the gallery on Linux

The gallery PNGs under `examples/gallery/` are references, not byte-goldens: the bytes depend on
the host's Chromium, and a macOS run and a Linux run disagree on some images. The committed
references are the Linux render below, so that anyone can reproduce them exactly.

**A default test run does not touch them.** `BrewShotGalleryTest` and `MathGalleryBrewShotTest`
render into `build/generated-examples/gallery/`, which mirrors `examples/gallery/` and is ignored by
git, so `./gradlew test` leaves `git status` clean on any host, with or without a browser. There is
nothing to restore after a Mac run. The build also checks this itself: it hashes every file under
`examples/` before the tests start and again after them, and fails with
`TRACKED EXAMPLES WRITTEN WITHOUT THE SWITCH` if any file changed during a run that did not ask
for it. The second half is its own task, `examplesWriteAudit`, which runs after the test task
whether or not a test failed, so a run that both fails a test and dirties the tree reports both.
The check compares content, so a write of identical bytes is not detected; that is acceptable
because such a write leaves the tracked tree, and `git status`, exactly as they were.

The byte-goldens under `src/test/resources/golden/` have the same kind of check, as the task
`goldenWriteAudit`: a golden whose content changed during the run must carry the bytes the
regeneration gate recorded writing under `-Dsirentide.updateGolden=true`, or the build fails with
`GOLDEN WRITE BYPASS`. It too runs after the test task whether or not a test failed.

Both checks are separate tasks, so they can be excluded: `./gradlew test -x examplesWriteAudit`
(or `-x goldenWriteAudit`) runs the suite without that check. The run then prints one warning,
`SIRENTIDE WRITE AUDIT NOT RUNNING: [:examplesWriteAudit] not scheduled in this build ...`, at
Gradle's warning level, which `-q` hides. Nothing else marks such a run as unchecked.

A run that has not been checked clean is not treated as up to date. If a check reported a finding,
or was excluded, or the build was killed, the next `./gradlew test` runs the suite again even when
no source changed, and it keeps doing so until a run finishes with both checks finding nothing.
So after `git restore examples` the next run cannot go green on `UP-TO-DATE` while the code that
wrote the tree is still there. The test task is also never restored from the Gradle build cache,
for the same reason: the checks are about a test run that actually happened.

The tracked gallery is written only under the regeneration switch, `-Dsirentide.regenerate=true`.
A run with the switch prints a `SIRENTIDE EXAMPLES REGEN` line naming the host and the configured
browser, and each gallery test class records the browser's user agent in its own banner (in the
test report's standard error). **Do not commit gallery PNGs regenerated on macOS**; regenerate on
the harness below.

`GALLERY.md` and the HTML pages do not depend on the host, so a default run still compares the
`GALLERY.md` and `showcase.html` it generates with the committed files and fails on a difference.
The per-type pages (`journey.html` and the others `ShowcaseGenTest` writes) are generated but not
compared, so a committed one can fall behind the renderer until the next regeneration.

### Checking the checks by hand

The build has no committed test for a whole build that fails a test and dirties a tracked tree at
once: that would need a second Gradle build of this project started from inside its own test run.
The decisions the checks rest on are exercised by `./gradlew goldenAuditSelfTest` (part of
`check`). The end-to-end behaviour is checked by hand, with a temporary edit that is never
committed. In `src/test/java/com/sirentide/GeneratedExamplesTest.java`, add as the first statement
of `theBuildScriptForwardsTheSwitchTheHelperReads`:

```java
Files.writeString(Path.of("examples", "audit-probe.txt"), "probe");
```

1. `./gradlew test`: the tests pass and the build fails with
   `TRACKED EXAMPLES WRITTEN WITHOUT THE SWITCH`, naming `audit-probe.txt`.
2. `rm examples/audit-probe.txt`, then `./gradlew test` again with the edit still in place: the
   test task must run again (not `UP-TO-DATE`) and the build must fail the same way.
3. Add `org.junit.jupiter.api.Assertions.fail("probe");` as the next statement, remove the probe
   file and run again: the build must report both the failed test and the finding.
4. Remove the probe file and run `./gradlew test -x examplesWriteAudit`: the
   `SIRENTIDE WRITE AUDIT NOT RUNNING` warning appears and no finding is reported.
5. Undo the edit, remove the probe file, and run `./gradlew test` twice: the first run executes
   and passes, the second is `UP-TO-DATE`.

The same steps with `Path.of("src/test/resources/golden", "audit-probe.svg")` exercise
`goldenWriteAudit` and `GOLDEN WRITE BYPASS`.

## The harness

The same base image and Chromium pins as BrewShot's own Dockerfile, run as a non-root user:

```dockerfile
FROM eclipse-temurin:25-jdk-alpine@sha256:5ecfde8e5ecde5954ea3721155b345ef56c1d579b940c761318ad4c05959a151
RUN apk add --no-cache chromium=149.0.7827.53-r0 font-liberation=2.1.5-r2 font-dejavu=2.37-r6 font-noto-emoji=2.048-r0 \
    && adduser -D -u 1000 tester
ENV BREWSHOT_CHROME=/usr/bin/chromium-browser \
    BREWSHOT_CHROME_ARGS="--no-sandbox --disable-dev-shm-usage"
USER 1000:1000
```

## Render and compare

From a clean checkout, with the Dockerfile above saved as `harness/Dockerfile` outside the checkout:

```sh
docker build -t sirentide-gallery-linux harness
rev=$(git rev-parse HEAD)
work=$(mktemp -d)
mkdir -p "$work/src" "$work/home"
git archive "$rev" | tar -x -C "$work/src"
chmod -R a+rwX "$work"
docker run --rm -e HOME=/work/home -e GRADLE_USER_HOME=/work/home/.gradle \
  -v "$work:/work" -w /work/src sirentide-gallery-linux \
  ./gradlew -q test --tests com.sirentide.BrewShotGalleryTest \
    --tests com.sirentide.MathGalleryBrewShotTest
# every committed gallery file against the Linux render, which a default run leaves under build/
for f in $(git ls-tree --name-only "$rev" examples/gallery/); do
  git show "$rev:$f" | cmp -s - "$work/src/build/generated-examples/${f#examples/}" \
    || echo "DIFFERS $f"
done
```

No `DIFFERS` line means the committed gallery is the Linux render. Both test classes are named
because the `math-*.png` references come from `MathGalleryBrewShotTest`; a file a run did not
render is absent under `build/` and reports as `DIFFERS`. Check that the test reports show the
gallery tests ran rather than skipped (`skipped="0"` in
`$work/src/build/test-results/test/TEST-com.sirentide.BrewShotGalleryTest.xml` and
`TEST-com.sirentide.MathGalleryBrewShotTest.xml`); a skip means Chromium was not found and nothing
was rendered.

## Regenerate

When a gallery Case is added or a renderer change moves an image, regenerate in the same harness
with the switch, without `-q` so the regeneration banner is visible:

```sh
docker run --rm -e HOME=/work/home -e GRADLE_USER_HOME=/work/home/.gradle \
  -v "$work:/work" -w /work/src sirentide-gallery-linux \
  ./gradlew test -Dsirentide.regenerate=true \
    --tests com.sirentide.BrewShotGalleryTest \
    --tests com.sirentide.MathGalleryBrewShotTest \
    --tests com.sirentide.ShowcaseGenTest
```

Then copy `examples/` back from `$work/src` and review the diff before committing. The switch
regenerates the HTML pages too, which is why `ShowcaseGenTest` is in the command: the root-system
gallery images are rendered from those pages. Under the switch the comparisons against the
committed `GALLERY.md` and showcase page are not a verification, because the run has just written
those files; the diff is the review.

The older `-Dsirentide.updateShowcase=true` still regenerates the HTML pages alone and never the
gallery.
