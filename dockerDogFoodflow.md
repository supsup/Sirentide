# The Sirentide Dogfood Flow

*How to run Sirentide in a box every day, and how to know the box is telling you the truth.*

---

## Cold open

It's a Tuesday. You're writing an architecture page and you want a trust-boundary diagram,
so you drop `boundary.sirentide` into a folder. Four seconds later `boundary.svg` appears
next to it. You paste it into the page. Done.

Three weeks later you write a sequence diagram with activation bars — `A ->>+ B : msg` —
and you get an empty picture. Not an error. Not a warning. A well-formed SVG containing
nothing at all.

Your diagram is fine. Your container is four months old.

That failure is the entire reason this document exists. Everything below is in service of
one question that has no obvious answer from the outside: **is the renderer I'm using the
renderer I think I'm using?**

---

## Cast

- **The image** — `sirentide:main-<sha>`, immutable, built once from one commit.
- **The tag** — `sirentide:dogfood`, a moving alias you re-point on purpose.
- **The stamp** — `Sirentide-Source-Revision`, baked into the jar manifest. The hero.
- **The watcher** — a long-lived container turning dropped files into SVGs.

---

## Act I — Build, and why the build argument is not optional

```sh
SHA=$(git rev-parse --short HEAD)          # every example below reuses this
docker build --build-arg SIRENTIDE_SOURCE_REVISION="$(git rev-parse HEAD)" \
  -t "sirentide:main-$SHA" .
```

Leave the argument off and the build **fails**:

```
Sirentide-Source-Revision requires one exact lowercase 40-hex git commit; got ''
```

That refusal is deliberate, and it's the best thing in this Dockerfile. The alternative —
building happily and producing an unstamped jar — gives you an artifact that cannot say
where it came from. The Dockerfile's own comment puts it plainly: failing is *"the correct
posture for an artifact whose whole contract is naming the tree it was cut from."*

Why can't the build work it out itself? Because `.dockerignore` excludes `.git`. The build
context genuinely has no repository, so `git rev-parse` inside the image is impossible, and
installing git in the build stage wouldn't help — the history isn't there to read. The
caller is the only one who knows. So the caller must say.

### What the Dockerfile actually does, stage by stage

**Stage 1 — `eclipse-temurin:25-jdk AS build`**

| line | what it does | why it matters to you |
|---|---|---|
| `ARG SIRENTIDE_SOURCE_REVISION` | accepts the commit | the one thing you must pass |
| `ENV SIRENTIDE_SOURCE_REVISION=…` | promotes it for Gradle | the build reads it from the environment |
| `COPY . .` | copies the *filtered* context | `.dockerignore` already removed `.git`, `build`, `src/test`, `docs`, `examples`, `*.md` |
| `./gradlew --no-daemon clean jar` | builds from source | **`clean` is load-bearing** — see below |
| `find build/libs … -print -quit` | picks the jar by pattern | excludes `-sources` and `-javadoc` |

That `clean` deserves a paragraph. A host `build/libs` can accumulate *several* jars across
versions — I have personally been fooled by a stale `sirentide-0.5.0.jar` sitting beside a
freshly built `0.6.0.jar`, and a glob that took the first match picked the wrong one. Inside
the image this cannot happen: the context excludes `build` entirely and the build starts
with `clean`, so exactly one jar exists when `find` runs. **The image is not vulnerable to
the trap that catches people on the host.**

**Stage 2 — the worker**

`docker/SirentideFolderWorker.java` is compiled against the jar just built and packaged as
a separate `sirentide-worker.jar`. It is container-only: it exists to watch folders and
never ships in the library.

**Stage 3 — `eclipse-temurin:25-jre-alpine`**

| setting | value | reason |
|---|---|---|
| user/group | `sirentide` / `10001:10001`, system, no home | nothing runs as root |
| app tree | `/opt/sirentide` | immutable artifacts |
| jar permissions | `chmod 0444` | read-only; the running process cannot rewrite its own code |
| entrypoint permissions | `chmod 0555` | read+execute, not writable |
| data tree | `/sirentide/{input,output}` + `input/{processing,finished,failed,failed/pending}` | pre-created and `chown`ed so a fresh bind mount works |
| `USER 10001:10001` | dropped before entrypoint | privileges are gone before your code runs |
| `WORKDIR /sirentide` | the data tree | relative paths land in the mounts |

Only the two jars and the entrypoint cross from build stage to runtime. The JDK, Gradle,
the source tree, and every intermediate stay behind.

---

## Act II — Verify, in three widening circles

A build that exits 0 has proven only that it compiled.

**Circle 1 — does it render at all?**

```sh
printf '%s\n' 'pie' '"Rendered" : 62' '"Degraded" : 23' | docker run --rm -i sirentide:main-$SHA | head -1
```

**Circle 2 — is it the commit you think?** This is the circle other renderers can't offer:

```sh
docker run --rm --entrypoint sh sirentide:main-$SHA -c \
  'unzip -p /opt/sirentide/sirentide.jar META-INF/MANIFEST.MF' \
  | grep -E 'Implementation-Version|Sirentide-Source-Revision'
```

```
Implementation-Version: 0.6.0
Sirentide-Source-Revision: b2ac6fc5d6b9b69f49f4d84c11fe515d68b79e80
```

A **full 40-hex commit**, not a version string. A version string tells you what someone
*declared*; a commit tells you what was *compiled*. You can hand that sha straight to
`git log -1` and read the exact tree.

**Circle 3 — the whole contract:**

```sh
sh docker/smoke-test.sh sirentide:main-$SHA      # -> "sirentide Docker smoke: PASS"
```

Modes, mounts, atomic claims, restart recovery, unreadable sources, collisions, races.

> ⚠️ **Known flake, so you don't chase it.** This smoke is **not** reliably green on a
> correct image — roughly 1 run in 3 fails with
> `chmod: cannot access '/sirentide/input/unreadable.sirentide': No such file or directory`.
> It builds that fixture in two steps (`printf >` then `chmod 000`) while a watcher is
> already running on the mount, so the watcher can atomically claim the file between them.
> **Re-run before believing a single red.** Two clean passes is the signal.

---

## Act III — Promote the moving tag

Three names, three meanings:

| tag | mutability | meaning |
|---|---|---|
| `sirentide` | scratch | your throwaway working build |
| `sirentide:main-<sha>` | **immutable** | one commit, forever; never re-pointed |
| `sirentide:dogfood` | **moving** | "the one I actually use today" |

Build the immutable tag, verify it, *then* move the alias:

```sh
docker tag "sirentide:main-$SHA" sirentide:dogfood
```

Order matters. Because `dogfood` ends up carrying **both** tags, the immutable one becomes
the image's durable record of provenance even from outside — and the staleness check below
depends on it existing.

---

## Act IV — "Is mine stale?", the question that started this

Two independent answers. Prefer the first.

**The stamp (authoritative — reads the artifact):**

```sh
img=$(docker run --rm --entrypoint sh sirentide:dogfood -c \
  'unzip -p /opt/sirentide/sirentide.jar META-INF/MANIFEST.MF' \
  | sed -n 's/^Sirentide-Source-Revision: //p' | tr -d '\r')
echo "image built from: $img"
git fetch origin --quiet
echo "commits behind main: $(git rev-list --count "$img"..origin/main)"
```

This asks the *artifact*, not its label. It survives re-tagging, mislabeling, and someone
else's build.

**The tag (a cross-check — reads the label):**

```sh
built_from=$(docker inspect -f '{{join .RepoTags "\n"}}' sirentide:dogfood \
  | sed -n 's/^sirentide:main-//p' | head -1)
[ -n "$built_from" ] && git log --oneline "$built_from"..origin/main | wc -l \
  || echo "provenance unknown: no sirentide:main-$SHA tag on this image"
```

Never index `RepoTags` positionally — the order is not guaranteed, and a positional read
silently returns the wrong tag the day it flips.

### Roleplay: the four-month-old container

> **You:** My activation bars render as an empty diagram. Is that a bug?
>
> **The stamp:** `Sirentide-Source-Revision: 24c6440…`
>
> **git:** that commit is 100 commits behind main.
>
> **You:** …so my diagram is fine and my container is from July.
>
> **The stamp:** I never claimed otherwise. You just never asked me.

That is a real case, measured on 2026-08-11: `sirentide:dogfood` was built 2026-07-27, ran
100 commits behind, declared `0.5.0` against a source tree at `0.6.0`, and carried **no**
`Sirentide-Source-Revision` at all — because the stamping mechanism landed *after* that
image was built. The image predated the very feature that would have exposed it.

The tell was not an error message. It was a sequence diagram that came back as an empty
inert shell while current Sirentide rendered it correctly. **Silence is the symptom.**

---

## Act V — The dogfood watcher, running for real

```sh
mkdir -p ~/projects/dogfood/sirentide/{Input,Output}

docker run -d --name sirentide-dogfood --restart unless-stopped \
  --user "$(id -u):$(id -g)" \
  -v ~/projects/dogfood/sirentide/Input:/sirentide/input \
  -v ~/projects/dogfood/sirentide/Output:/sirentide/output \
  sirentide:dogfood watch

docker logs -f sirentide-dogfood
```

`--restart unless-stopped` survives reboots and Docker restarts. `--user "$(id -u):$(id -g)"`
makes output files yours instead of `10001`'s; omit it to keep the image's non-root default
if your runtime already translates ownership. Watch mode needs the input mount **writable** —
claiming a job is an atomic move, not a copy.

### ⚠️ Updating the image does NOT update the running container

This one silently defeats the entire flow, so it gets its own heading.

A container is bound to the image **ID** it was created from, not to the tag. Re-pointing
`sirentide:dogfood` and running `docker restart` leaves the watcher on the **old** build —
`restart` restarts the same container, it does not re-resolve the tag. Recreate it:

```sh
docker rm -f sirentide-dogfood
docker run -d --name sirentide-dogfood --restart unless-stopped \
  --user "$(id -u):$(id -g)" \
  -v ~/projects/dogfood/sirentide/Input:/sirentide/input \
  -v ~/projects/dogfood/sirentide/Output:/sirentide/output \
  sirentide:dogfood watch
```

**The tell is visible in `docker ps`.** When the image column shows a bare hex ID instead
of a tag name, the tag has moved on without the container:

```text
sirentide-dogfood   8df13f4bb6cb        <- STALE: tag moved, container did not
sirentide-dogfood   sirentide:dogfood   <- current
```

And the authoritative check asks the *running container*, not the image:

```sh
docker exec sirentide-dogfood sh -c \
  'unzip -p /opt/sirentide/sirentide.jar META-INF/MANIFEST.MF' \
  | grep -E 'Implementation-Version|Sirentide-Source-Revision'
```

Everything in Act IV verifies the **image**. This verifies what is actually serving you.
They can disagree, and when they do, this one is right.

### What counts as a job

- `.sirentide` — a raw DSL source
- `.md` / `.markdown` — renders the **first** ` ```sirentide ` fence

Ignored: hidden names, symlinks, nested directories, every other extension, and the worker's
own state folders.

### The one rule that will bite you

**Never write a live job filename incrementally.** Write a hidden sibling and rename:

```sh
printf 'flowchart LR\n  A[in] --> B[out]\n' > Input/.flow.sirentide.tmp
mv Input/.flow.sirentide.tmp Input/flow.sirentide
```

A rename is atomic; a slow `>` redirect is not. The watcher polls every 500 ms by default
(`SIRENTIDE_WATCH_POLL_MS`, 10–60000) and will happily claim a half-written file.

### The lifecycle, so nothing looks lost

```text
Input/flow.sirentide
  -> Input/processing/<job-id>/flow.sirentide     claimed
  -> Input/finished/flow.sirentide                success, source preserved
  -> Input/failed/flow.sirentide                  failure
Output/flow.sirentide.svg                         success
Output/flow.sirentide.error.txt                   failure, bounded, non-secret
```

Your source is **never deleted** — it moves. If output didn't appear, look in `failed/`
before you look for a bug.

---

## Troubleshooting

| symptom | look here first |
|---|---|
| build fails on `Sirentide-Source-Revision requires…` | you omitted `--build-arg`; it is required |
| a valid diagram renders empty or degraded | **check the stamp before doubting the diagram** |
| smoke test red once | re-run it; the unreadable-fixture race is known |
| output files owned by `10001` | pass `--user "$(id -u):$(id -g)"` |
| watcher ignores your file | wrong extension, hidden, nested, or a symlink |
| output never appears | check `Input/failed/` and `Output/*.error.txt` |
| `docker build` fails resolving dependencies | the build stage has no host Gradle cache; it needs network |

---

## The short version

1. Build **with** `--build-arg SIRENTIDE_SOURCE_REVISION="$(git rev-parse HEAD)"`.
2. Verify: renders → stamp names the commit → smoke passes (re-run once on red).
3. Tag `sirentide:main-$SHA` first, promote `sirentide:dogfood` second.
4. Ask the **stamp**, not the label, whether you're current.
5. Rename files into `Input/`; never write them in place.

When a diagram looks wrong, check the renderer's age before you edit the diagram. The
container will not volunteer that it is old — but it does carry the answer, and now you
know how to ask.
