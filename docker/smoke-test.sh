#!/bin/sh
set -eu

image=${1:-sirentide:local}
tmp_root=$(mktemp -d "${TMPDIR:-/tmp}/sirentide-docker-smoke.XXXXXX")
name_suffix=$$
watch_one="sirentide-smoke-one-$name_suffix"
watch_recovery="sirentide-smoke-recovery-$name_suffix"
watch_collision="sirentide-smoke-collision-$name_suffix"
watch_race_a="sirentide-smoke-race-a-$name_suffix"
watch_race_b="sirentide-smoke-race-b-$name_suffix"

cleanup() {
    docker rm -f "$watch_one" "$watch_recovery" "$watch_collision" \
        "$watch_race_a" "$watch_race_b" >/dev/null 2>&1 || true
    if [ -n "$tmp_root" ] && [ "$tmp_root" != "/" ]; then
        chmod -R u+rwx "$tmp_root" >/dev/null 2>&1 || true
        rm -rf -- "$tmp_root"
    fi
}
trap cleanup EXIT INT TERM

write_markdown() {
    target=$1
    title=$2
    value=$3
    printf '%s\n' \
        "# $title" \
        '' \
        '```sirentide' \
        'pie' \
        "\"$title\" : $value" \
        '```' > "$target"
}

wait_for_path() {
    target=$1
    container=$2
    attempt=0
    while [ ! -e "$target" ] && [ "$attempt" -lt 240 ]; do
        sleep 0.05
        attempt=$((attempt + 1))
    done
    if [ ! -e "$target" ]; then
        echo "timed out waiting for expected worker artifact: $target" >&2
        docker logs "$container" >&2 || true
        return 1
    fi
}

first_match() {
    directory=$1
    pattern=$2
    for match in "$directory"/$pattern; do
        if [ -f "$match" ]; then
            printf '%s\n' "$match"
            return 0
        fi
    done
    return 1
}

wait_for_match() {
    directory=$1
    pattern=$2
    container=$3
    attempt=0
    while ! match=$(first_match "$directory" "$pattern"); do
        if [ "$attempt" -ge 240 ]; then
            echo "timed out waiting for worker artifact matching: $directory/$pattern" >&2
            docker logs "$container" >&2 || true
            return 1
        fi
        sleep 0.05
        attempt=$((attempt + 1))
    done
    printf '%s\n' "$match"
}

repeat_char() {
    count=$1
    character=$2
    printf '%*s' "$count" '' | tr ' ' "$character"
}

byte_length() {
    LC_ALL=C printf '%s' "$1" | wc -c | tr -d ' '
}

assert_svg() {
    target=$1
    test -s "$target"
    grep -q '<svg' "$target"
}

# Runtime shape: non-root, immutable jars, fixed mount roots.
docker run --rm --entrypoint sh "$image" -c '
    test "$(id -u)" = 10001
    test -r /opt/sirentide/sirentide.jar
    test ! -w /opt/sirentide/sirentide.jar
    test -r /opt/sirentide/sirentide-worker.jar
    test -d /sirentide/input
    test -d /sirentide/output
'

# The explicit `cli` spelling is byte-compatible with the old no-mode shape.
printf '%s\n' 'pie' '"A" : 1' \
    | docker run --rm -i "$image" > "$tmp_root/legacy.svg"
printf '%s\n' 'pie' '"A" : 1' \
    | docker run --rm -i "$image" cli > "$tmp_root/explicit-cli.svg"
cmp "$tmp_root/legacy.svg" "$tmp_root/explicit-cli.svg"
assert_svg "$tmp_root/legacy.svg"

input="$tmp_root/Input"
output="$tmp_root/Output"
mkdir -p "$input" "$output"
chmod 0777 "$input" "$output"

# File CLI mode uses a read-only input mount and a writable output mount.
write_markdown "$input/cli example.md" 'CLI' 2
docker run --rm \
    --user "$(id -u):$(id -g)" \
    -v "$input:/sirentide/input:ro" \
    -v "$output:/sirentide/output" \
    "$image" cli render '/sirentide/input/cli example.md' \
    -o '/sirentide/output/cli example.svg'
assert_svg "$output/cli example.svg"

# Startup backlog, raw DSL, failure, spaces, and ignore rules.
write_markdown "$input/startup diagram.md" 'Startup' 3
printf '%s\n' 'pie' '"Raw" : 4' > "$input/raw diagram.sirentide"
printf '%s\n' 'TOP-SECRET-SENTINEL' > "$input/bad.md"
write_markdown "$input/.upload.md.tmp" 'Partial' 5
printf '%s\n' '{"ignored":true}' > "$input/ignored.json"
mkdir -p "$input/finished" "$input/processing"
write_markdown "$input/finished/state child.md" 'StateChild' 6
write_markdown "$input/processing/not-a-claim.md" 'NotAClaim' 7
chmod -R a+rwX "$input" "$output"
# Producers only need to make job bytes readable; archive transitions require
# write access to the input directory, not to the source inode itself.
chmod 0444 "$input/raw diagram.sirentide"

docker run -d --name "$watch_one" \
    -e SIRENTIDE_WATCH_POLL_MS=20 \
    -v "$input:/sirentide/input" \
    -v "$output:/sirentide/output" \
    "$image" watch >/dev/null

wait_for_path "$output/startup diagram.md.svg" "$watch_one"
wait_for_path "$output/raw diagram.sirentide.svg" "$watch_one"
wait_for_path "$output/bad.md.error.txt" "$watch_one"
wait_for_path "$input/finished/startup diagram.md" "$watch_one"
wait_for_path "$input/finished/raw diagram.sirentide" "$watch_one"
wait_for_path "$input/failed/bad.md" "$watch_one"
assert_svg "$output/startup diagram.md.svg"
assert_svg "$output/raw diagram.sirentide.svg"
test -e "$input/.upload.md.tmp"
test -e "$input/ignored.json"
test -e "$input/finished/state child.md"
test -e "$input/processing/not-a-claim.md"
test ! -e "$output/.upload.md.tmp.svg"
test ! -e "$output/ignored.json.svg"
test "$(wc -c < "$output/bad.md.error.txt")" -le 600
! grep -q 'TOP-SECRET-SENTINEL' "$output/bad.md.error.txt"

# A 230-byte host-valid filename used to become an unrepresentable claim after
# the worker prefixed a 32-byte UUID. The claim directory now keeps those
# components separate, and the watcher remains alive after the disposition.
long_claim_name="$(repeat_char 227 c).md"
test "$(byte_length "$long_claim_name")" -eq 230
write_markdown "$tmp_root/long-claim.md" 'LongClaim' 12
mv "$tmp_root/long-claim.md" "$input/$long_claim_name"
wait_for_path "$output/$long_claim_name.svg" "$watch_one"
wait_for_path "$input/finished/$long_claim_name" "$watch_one"
assert_svg "$output/$long_claim_name.svg"
test "$(docker inspect --format '{{.State.Running}}' "$watch_one")" = true

# A source component may fit while appending an output suffix does not. In that
# case a bounded job-id output is used and the source keeps its original name.
max_success_name="$(repeat_char 249 s).md"
test "$(byte_length "$max_success_name")" -eq 252
write_markdown "$tmp_root/max-success.md" 'MaxSuccess' 13
mv "$tmp_root/max-success.md" "$input/$max_success_name"
wait_for_path "$input/finished/$max_success_name" "$watch_one"
max_success_svg=$(wait_for_match "$output" 'job-*.svg' "$watch_one")
assert_svg "$max_success_svg"
test "$(byte_length "$(basename "$max_success_svg")")" -le 255
test "$(docker inspect --format '{{.State.Running}}' "$watch_one")" = true

# A long failed recovery claim uses the bounded job-id diagnostic name. If that
# target already contains different bytes, a bounded attempt-id fallback is
# published without overwriting the sentinel or stopping the watcher.
diagnostic_id=fedcba9876543210fedcba9876543210
long_failure_name="$(repeat_char 249 f).md"
test "$(byte_length "$long_failure_name")" -eq 252
printf '%s\n' 'DIAGNOSTIC-SENTINEL' > "$output/job-$diagnostic_id.error.txt"
mkdir "$input/processing/$diagnostic_id"
printf '%s\n' 'LONG-FAILURE-SECRET' > "$tmp_root/max-failure.md"
mv "$tmp_root/max-failure.md" \
    "$input/processing/$diagnostic_id/$long_failure_name"
wait_for_path "$input/failed/$long_failure_name" "$watch_one"
diagnostic_fallback=$(wait_for_match \
    "$output" "job-$diagnostic_id-*.error.txt" "$watch_one")
test "$(cat "$output/job-$diagnostic_id.error.txt")" = 'DIAGNOSTIC-SENTINEL'
test "$(wc -c < "$diagnostic_fallback")" -le 600
! grep -q 'LONG-FAILURE-SECRET' "$diagnostic_fallback"
test "$(byte_length "$(basename "$diagnostic_fallback")")" -le 255
test ! -e "$input/processing/$diagnostic_id"
test "$(docker inspect --format '{{.State.Running}}' "$watch_one")" = true
docker stop -t 2 "$watch_one" >/dev/null
docker rm "$watch_one" >/dev/null

# Restart recovery: a valid UUID claim directory is reclaimed after the prior
# process is gone; unrelated processing entries remain untouched.
recovery_id=0123456789abcdef0123456789abcdef
mkdir "$input/processing/$recovery_id"
write_markdown "$input/processing/$recovery_id/recovered diagram.md" 'Recovered' 8
docker run -d --name "$watch_recovery" \
    -e SIRENTIDE_WATCH_POLL_MS=20 \
    -v "$input:/sirentide/input" \
    -v "$output:/sirentide/output" \
    "$image" watch >/dev/null
wait_for_path "$output/recovered diagram.md.svg" "$watch_recovery"
wait_for_path "$input/finished/recovered diagram.md" "$watch_recovery"
assert_svg "$output/recovered diagram.md.svg"
test ! -e "$input/processing/$recovery_id"
test -e "$input/processing/not-a-claim.md"
docker stop -t 2 "$watch_recovery" >/dev/null
docker rm "$watch_recovery" >/dev/null

# A second source with a previously used name cannot overwrite either the
# completed SVG or the first archived source. It becomes an explicit failure.
write_markdown "$input/same name.md" 'First' 9
docker run -d --name "$watch_collision" \
    -e SIRENTIDE_WATCH_POLL_MS=20 \
    -v "$input:/sirentide/input" \
    -v "$output:/sirentide/output" \
    "$image" watch >/dev/null
wait_for_path "$output/same name.md.svg" "$watch_collision"
wait_for_path "$input/finished/same name.md" "$watch_collision"
before_svg=$(sha256sum "$output/same name.md.svg" | cut -d ' ' -f 1)
before_source=$(sha256sum "$input/finished/same name.md" | cut -d ' ' -f 1)
write_markdown "$input/.same name.md.tmp" 'Second' 10
mv "$input/.same name.md.tmp" "$input/same name.md"
wait_for_path "$output/same name.md.error.txt" "$watch_collision"
wait_for_path "$input/failed/same name.md" "$watch_collision"
test "$before_svg" = "$(sha256sum "$output/same name.md.svg" | cut -d ' ' -f 1)"
test "$before_source" = "$(sha256sum "$input/finished/same name.md" | cut -d ' ' -f 1)"
docker stop -t 2 "$watch_collision" >/dev/null
docker rm "$watch_collision" >/dev/null

# Two workers share one mount. Advisory locking is only an optimization: the
# idempotent publication/archive path makes exactly one worker emit a finished
# event even when a bind-mount driver lets both execute the same claim.
race_input="$tmp_root/RaceInput"
race_output="$tmp_root/RaceOutput"
mkdir -p "$race_input" "$race_output"
chmod 0777 "$race_input" "$race_output"
docker run -d --name "$watch_race_a" \
    -e SIRENTIDE_WATCH_POLL_MS=20 \
    -v "$race_input:/sirentide/input" \
    -v "$race_output:/sirentide/output" \
    "$image" watch >/dev/null
docker run -d --name "$watch_race_b" \
    -e SIRENTIDE_WATCH_POLL_MS=20 \
    -v "$race_input:/sirentide/input" \
    -v "$race_output:/sirentide/output" \
    "$image" watch >/dev/null
write_markdown "$race_input/.race file.md.tmp" 'Race' 11
mv "$race_input/.race file.md.tmp" "$race_input/race file.md"
wait_for_path "$race_output/race file.md.svg" "$watch_race_a"
wait_for_path "$race_input/finished/race file.md" "$watch_race_a"
sleep 0.2
assert_svg "$race_output/race file.md.svg"
test ! -e "$race_output/race file.md.error.txt"
test ! -e "$race_input/failed/race file.md"
test "$(docker inspect --format '{{.State.Running}}' "$watch_race_a")" = true
test "$(docker inspect --format '{{.State.Running}}' "$watch_race_b")" = true
finished_count=$(
    { docker logs "$watch_race_a"; docker logs "$watch_race_b"; } 2>&1 \
        | grep -c ' finished$'
)
test "$finished_count" -eq 1
docker stop -t 2 "$watch_race_a" "$watch_race_b" >/dev/null
docker rm "$watch_race_a" "$watch_race_b" >/dev/null

# No success-shaped temp artifacts survive any path.
test -z "$(find "$output" "$race_output" -maxdepth 1 -type f -name '.*.tmp' -print -quit)"

echo "sirentide Docker smoke: PASS"
