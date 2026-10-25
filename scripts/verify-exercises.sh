#!/usr/bin/env bash
# Run by CI (needs Docker for the database exercises). Proves two things about every exercise:
#   1. Without a solution at least one test fails, so the exercise is not already done.
#   2. With the reference solutions from solutions/ every test passes, so it is solvable
#      and later levels have not broken it.
# It works on a COPY of the repo, so it never touches your own files.
set -euo pipefail

root="$(cd "$(dirname "$0")/.." && pwd)"
work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

tar -C "$root" --exclude=./target --exclude=./.git -cf - . | tar -C "$work" -xf -
cd "$work"

check_reports() {
  local mode="$1" count=0
  shopt -s nullglob
  for f in target/surefire-reports/*ExerciseTest.txt; do
    local line run fail err skip
    line="$(grep -m1 "Tests run:" "$f")"
    run="$(sed -E 's/.*Tests run: ([0-9]+),.*/\1/' <<<"$line")"
    fail="$(sed -E 's/.*Failures: ([0-9]+),.*/\1/' <<<"$line")"
    err="$(sed -E 's/.*Errors: ([0-9]+),.*/\1/' <<<"$line")"
    skip="$(sed -E 's/.*Skipped: ([0-9]+).*/\1/' <<<"$line")"
    echo "  $(basename "$f" .txt): run=$run failures=$fail errors=$err skipped=$skip"
    if [ "$run" -eq 0 ] || [ "$skip" -ne 0 ]; then
      echo "ERROR: exercise tests did not actually run" >&2; exit 1
    fi
    if [ "$mode" = unsolved ] && [ $((fail + err)) -eq 0 ]; then
      echo "ERROR: $(basename "$f" .txt) passes without any solution" >&2; exit 1
    fi
    if [ "$mode" = solved ] && [ $((fail + err)) -ne 0 ]; then
      echo "ERROR: $(basename "$f" .txt) fails even with the reference solution" >&2; exit 1
    fi
    count=$((count + 1))
  done
  if [ "$count" -lt 3 ]; then
    echo "ERROR: expected at least 3 exercise test classes, found $count" >&2; exit 1
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
