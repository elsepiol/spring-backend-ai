#!/usr/bin/env bash
# Run by CI (needs Docker for the database exercises, Node for the frontend ones). Proves two
# things about every exercise, backend and frontend:
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

tar -C "$root" --exclude=./target --exclude=./.git \
  --exclude=./frontend/node_modules --exclude=./frontend/dist -cf - . | tar -C "$work" -xf -
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

# Frontend exercises: vitest writes one JSON report; every exercise file must have run tests, and
# (unsolved) at least one failure per file, or (solved) none at all.
check_frontend() {
  local mode="$1"
  (cd frontend && npx vitest run --mode exercises --reporter=json --outputFile=exercises.json >/dev/null 2>&1) || true
  [ -f frontend/exercises.json ] || fail "the frontend exercises did not run"
  node -e '
    const [mode, file] = process.argv.slice(1);
    const report = JSON.parse(require("fs").readFileSync(file, "utf8"));
    const files = report.testResults;
    let bad = files.length < 2 ? `expected at least 2 frontend exercise files, found ${files.length}` : "";
    for (const f of files) {
      const name = f.name.split("/").pop();
      const run = f.assertionResults.length;
      const failed = f.assertionResults.filter((t) => t.status === "failed").length;
      console.log(`  ${name}: run=${run} failures=${failed}`);
      if (run === 0) bad ||= `${name}: the tests did not actually run`;
      else if (mode === "unsolved" && failed === 0) bad ||= `${name} passes without any solution`;
      else if (mode === "solved" && failed !== 0) bad ||= `${name} fails even with the reference solution`;
    }
    if (bad) { console.log("::error::" + bad); process.exit(1); }
  ' "$mode" frontend/exercises.json || fail "frontend exercises: see above"
  rm frontend/exercises.json
}

echo "== 1. Without solutions the exercises must fail"
./mvnw -B -q clean test -Pexercises || true
check_reports unsolved
(cd frontend && npm ci --no-audit --no-fund --loglevel=error)
check_frontend unsolved

echo "== 2. Applying the reference solutions"
# Each overlay mirrors the repo layout (src/... for the backend, frontend/src/... for the frontend).
for dir in solutions/level-*/; do
  cp -r "$dir/." ./
done

echo "== 3. With solutions every exercise test must pass"
./mvnw -B -q clean test -Pexercises
check_reports solved
check_frontend solved

echo "== 4. The normal builds must still pass with the solutions in place"
./mvnw -B verify
(cd frontend && npm run typecheck && npm test && npm run build)

echo "All exercises verified."
