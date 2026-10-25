#!/usr/bin/env bash
# Run by CI (needs Docker for the database exercises). Proves two things about every exercise:
#   1. Without a solution at least one test fails, so the exercise is not already done.
#   2. With the reference solutions from solutions/ every test passes, so it is solvable
#      and later levels have not broken it.
# It works on a COPY of the repo, so it never touches your own files.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
# On failure keep the test reports where CI can annotate them, then clean up.
trap 'code=$?; if [ "$code" -ne 0 ] && [ -d "$work/target/surefire-reports" ]; then rm -rf "$root/target/surefire-reports"; mkdir -p "$root/target"; cp -r "$work/target/surefire-reports" "$root/target/surefire-reports"; fi; rm -rf "$work"' EXIT

fail() { echo "::error::$*"; echo "ERROR: $*" >&2; exit 1; }

tar -C "$root" --exclude=./target --exclude=./.git -cf - . | tar -C "$work" -xf -
cd "$work"

check_reports() {
  local mode="$1" count=0
  shopt -s nullglob
  for f in target/surefire-reports/*ExerciseTest.txt; do
    local line run nfail err skip
    line="$(grep -m1 "Tests run:" "$f")"
    run="$(sed -E 's/.*Tests run: ([0-9]+),.*/\1/' <<<"$line")"
    nfail="$(sed -E 's/.*Failures: ([0-9]+),.*/\1/' <<<"$line")"
    err="$(sed -E 's/.*Errors: ([0-9]+),.*/\1/' <<<"$line")"
    skip="$(sed -E 's/.*Skipped: ([0-9]+).*/\1/' <<<"$line")"
    echo "  $(basename "$f" .txt): run=$run failures=$nfail errors=$err skipped=$skip"
    if [ "$run" -eq 0 ] || [ "$skip" -ne 0 ]; then
      fail "exercise tests did not actually run"
    fi
    if [ "$mode" = unsolved ] && [ $((nfail + err)) -eq 0 ]; then
      fail "$(basename "$f" .txt) passes without any solution"
    fi
    if [ "$mode" = solved ] && [ $((nfail + err)) -ne 0 ]; then
      fail "$(basename "$f" .txt) fails even with the reference solution"
    fi
    count=$((count + 1))
  done
  if [ "$count" -lt 7 ]; then
    fail "expected at least 7 exercise test classes, found $count"
  fi
}

echo "== 1. Without solutions the exercises must fail"
./mvnw -B -q clean test -Pexercises || true
check_reports unsolved

echo "== 2. Applying the reference solutions"
for dir in solutions/level-*/; do
  cp -r "$dir/src/." src/
done

echo "== 3. With solutions every exercise test must pass"
./mvnw -B -q clean test -Pexercises
check_reports solved

echo "== 4. The normal build must still pass with the solutions in place"
./mvnw -B verify

echo "All exercises verified."
