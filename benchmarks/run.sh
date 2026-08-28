#!/usr/bin/env bash
# Full benchmark matrix: Kormium Native + Kormium JVM + Exposed + Hibernate, all against
# one tuned PostgreSQL container. The summary table at the end merges every column.
#
# Usage: benchmarks/run.sh [--quick] [--skip-native] [--skip-jvm]
#   --quick        fast indicative run (~2-3 min instead of ~20)
#   --skip-native  JVM ORMs only (no Kotlin/Native build)
#   --skip-jvm     native harness only; re-renders the summary with the merged column
set -euo pipefail
cd "$(dirname "$0")/.."

QUICK="" SKIP_NATIVE="" SKIP_JVM=""
for arg in "$@"; do
  case "$arg" in
    --quick) QUICK=1 ;;
    --skip-native) SKIP_NATIVE=1 ;;
    --skip-jvm) SKIP_JVM=1 ;;
    *) echo "unknown option: $arg" >&2; exit 1 ;;
  esac
done

PORT="${KORM_BENCH_PORT:-54329}"
CONTAINER=kormium-bench-pg
RESULTS_DIR=benchmarks/build/results/jmh
mkdir -p "$RESULTS_DIR"

echo "==> Starting PostgreSQL (tmpfs, durability off) on port $PORT"
docker rm -f "$CONTAINER" >/dev/null 2>&1 || true
docker run -d --name "$CONTAINER" \
  -e POSTGRES_PASSWORD=password \
  -p "$PORT:5432" \
  -e PGDATA=/var/lib/postgresql/data \
  --tmpfs /var/lib/postgresql/data \
  postgres:18-alpine \
  postgres -c fsync=off -c synchronous_commit=off -c full_page_writes=off >/dev/null
trap 'docker rm -f "$CONTAINER" >/dev/null 2>&1 || true' EXIT
until docker exec "$CONTAINER" pg_isready -U postgres >/dev/null 2>&1; do sleep 0.5; done

export KORMIUM_DB_HOST=127.0.0.1 KORMIUM_DB_PORT="$PORT" KORMIUM_DB_NAME=postgres
export KORMIUM_DB_USER=postgres KORMIUM_DB_PASSWORD=password

if [ -z "$SKIP_NATIVE" ]; then
  case "$(uname -sm)" in
    "Darwin arm64")  TARGET=macosArm64 ;;
    "Darwin x86_64") TARGET=macosX64 ;;
    "Linux x86_64")  TARGET=linuxX64 ;;
    *) echo "==> No native target for '$(uname -sm)' — skipping native benchmark"; TARGET="" ;;
  esac
  if [ -n "$TARGET" ]; then
    echo "==> Building native test binary ($TARGET, release)"
    # The optimized binary: the default debug test kexe understates CPU-bound ops
    # (row materialization, batch binding) by 2-3x vs the JIT-optimized JVM ORMs.
    TARGET_CAP="$(printf '%s' "${TARGET:0:1}" | tr '[:lower:]' '[:upper:]')${TARGET:1}"
    ./gradlew -q ":kormium-postgres:linkBenchReleaseTest${TARGET_CAP}"
    KEXE=$(find "kormium-postgres/build/bin/$TARGET/benchReleaseTest" -name "*.kexe" -not -path "*.dSYM/*" | head -1)
    echo "==> Running native benchmark"
    RESULT_LINE=$(env KORM_BENCH=1 ${QUICK:+KORM_BENCH_OPS=500} \
      "$KEXE" --ktest_filter='NativeBenchmark.*' | tee /dev/stderr | grep KORM_NATIVE_RESULT || true)
    if [ -n "$RESULT_LINE" ]; then
      # "KORM_NATIVE_RESULT findById=123 ..." -> {"findById": 123, ...}
      echo "$RESULT_LINE" | sed 's/.*KORM_NATIVE_RESULT //' | awk '{
        printf "{";
        for (i = 1; i <= NF; i++) { split($i, kv, "="); printf "%s\"%s\": %s", (i > 1 ? ", " : ""), kv[1], kv[2] }
        print "}"
      }' > "$RESULTS_DIR/native.json"
      echo "==> Native results written to $RESULTS_DIR/native.json"
    else
      echo "WARNING: native benchmark produced no KORM_NATIVE_RESULT line" >&2
    fi
  fi
fi

if [ -z "$SKIP_JVM" ]; then
  echo "==> Running JVM benchmarks (kormium / Exposed / Hibernate)"
  ./gradlew :benchmarks:jmh ${QUICK:+-Pbench.quick} \
    -Pbench.db.host=127.0.0.1 -Pbench.db.port="$PORT" -Pbench.db.name=postgres \
    -Pbench.db.user=postgres -Pbench.db.password=password
else
  ./gradlew :benchmarks:benchmarkSummary
fi
