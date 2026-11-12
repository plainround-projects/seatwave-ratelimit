#!/usr/bin/env bash
# The Module 12 labs and failure reproductions against the running stack (scripts/stack.sh up).
# The JDK-only lab tools (LatencyProbe, HoldTraffic) run on the Mac against the published ports, so
# every timing crosses Docker Desktop's port forwarder into its Linux virtual machine.
#
# Usage: scripts/lab.sh <lab> [--run] [--yes]
#   shared        Lab 2, steps 3 and 4: one fan's holds through the three gateways, paced (60, one
#                 every 400 ms) and all at once (300); then six holds with curl and the sixth's fields
#   per-instance  Section 9, failure mode 1: the gateways restarted with the hold policy on each
#                 instance's own bucket (gateway-instances 1, holds LOCAL) and Redis stopped; the
#                 same two runs as "shared"
#   store-down    Lab 2, step 5, and failure mode 2: Redis stopped, the store-failure stack test,
#                 the store-error counter, Redis started and one hold; then 60 s of holds and seat
#                 reads with Redis paused from 10 s to 25 s and stopped from 35 s to 50 s
#   latency       What the limiter adds: catalog timed directly and through gateway-1 at RATE/s,
#                 the limiter's store-call timer, Redis's own time and CPU per script call, the
#                 memory of one bucket
#   redis-capacity What one Redis can do with the script: redis-benchmark, from a throwaway
#                 container on the lab network, sends 200,000 EVALSHA calls from 50 clients over
#                 100,000 keys; Redis's own CPU per call from its control group
#   retry-storm   Section 9, failure mode 4: ACCOUNTS accounts resending every refused hold at once
#                 for STORM_SECONDS s, with a seat-read probe beside them before and during
#   mix           Lab 3, step 6, and failure mode 3: Lab 3's minute of fans and bots (FANS, BOTS)
#                 with the hold policy in shadow mode, then enforcing
#   shedding      Lab 3, step 6, and failure mode 5: booking alone, its reads taking 40 ms on one
#                 of 8 workers (a stand-in for its database pool: about 200 reads a second), probed
#                 directly at SHED_RATE/s, first without shedding (100000 in flight), then with
#                 SHED_LIMIT in flight
#   default a dry run: print the plan, change nothing; --run does it after a question; --yes skips it
#
# Guardrails: the only target is the project seatwave-ratelimit; Docker must answer and the stack
# must be ready first; each change appends an audit line to build/stack/audit.log; the stack is put
# back as compose.yaml describes it at the end (an EXIT trap), also when a step failed; reports go
# to build/runs/<lab>.txt; any failure exits non-zero.
set -euo pipefail

usage() {
  echo "usage: $0 <shared|per-instance|store-down|latency|redis-capacity|retry-storm|mix|shedding> [--run] [--yes]" >&2
  exit 2
}

[[ $# -ge 1 ]] || usage
lab="$1"
shift
mode="dry-run"
confirmed="no"
for option in "$@"; do
  case "$option" in
    --run) mode="run" ;;
    --yes) confirmed="yes" ;;
    *) usage ;;
  esac
done

readonly RATE="${RATE:-500}"
readonly SECONDS_TIMED="${DURATION:-30}"
readonly WARMUP="${WARMUP:-10}"
readonly ACCOUNTS="${ACCOUNTS:-20}"
readonly STORM_SECONDS="${STORM_SECONDS:-20}"
readonly FANS="${FANS:-100}"
readonly BOTS="${BOTS:-2}"
# Module 4's hold of 40 ms; 8 workers serve 8 / 0.040 s = 200 reads a second, and the lab sends
# twice that. The in-flight limit is Little's Law at that capacity: 200/s x 0.040 s = 8.
readonly READ_DELAY="${READ_DELAY:-40ms}"
readonly READ_WORKERS="${READ_WORKERS:-8}"
readonly SHED_RATE="${SHED_RATE:-400}"
readonly SHED_LIMIT="${SHED_LIMIT:-8}"
readonly WAIT_SECONDS=120
readonly GATEWAY="http://127.0.0.1:21200"
readonly SEAT_PAGE="/v1/events/1/seats?section=A&limit=100"
readonly OVERRIDE="build/stack/compose.lab.yaml"
readonly GATEWAYS=(gateway-1 gateway-2 gateway-3)

case "$lab" in
  shared) plan="60 paced holds and 300 simultaneous holds of one fan, then six holds with curl; nothing is changed" ;;
  per-instance) plan="the gateways restarted with gateway-instances 1 and the hold policy LOCAL (SPRING_APPLICATION_JSON); Redis stopped; the runs of 'shared'; then compose.yaml's settings and Redis again" ;;
  store-down) plan="Redis stopped, the stack test with -Dseatwave.redis=stopped, Redis started; then 60 s of traffic with Redis paused at 10 s, unpaused at 25 s, stopped at 35 s, started at 50 s" ;;
  latency) plan="catalog published on 127.0.0.1:21230 for this lab; two probe runs at ${RATE}/s for ${SECONDS_TIMED} s; then compose.yaml's ports again" ;;
  redis-capacity) plan="the script loaded into Redis; redis-benchmark in a throwaway container (removed when it ends) on the lab network: 200,000 calls, 50 clients, 100,000 keys" ;;
  retry-storm) plan="a seat-read probe at 100/s alone, then beside ${ACCOUNTS} accounts that resend every 429 at once for ${STORM_SECONDS} s; nothing is changed" ;;
  mix) plan="the gateways restarted with the hold policy in shadow mode; ${FANS} fans and ${BOTS} bots for 60 s; the gateways restarted as compose.yaml says; the same minute again" ;;
  shedding) plan="booking published on 127.0.0.1:21231 with reads of ${READ_DELAY} on ${READ_WORKERS} workers, restarted with 100000 and then ${SHED_LIMIT} in flight, probed at ${SHED_RATE}/s for ${SECONDS_TIMED} s each; then compose.yaml's booking again" ;;
  *) usage ;;
esac

cd "$(dirname "$0")/.."
source scripts/common.sh

echo "plan: lab $lab on the Compose project $PROJECT: $plan"
if [[ "$mode" == "dry-run" ]]; then
  echo "dry run: nothing changed. Add --run to do it."
  exit 0
fi
docker_must_answer
if ! stack_ready; then
  echo "the stack is not ready (scripts/stack.sh up --run first); nothing changed" >&2
  exit 1
fi
if [[ "$confirmed" != "yes" ]]; then
  read -r -p "Go ahead with lab '$lab'? [y/N] " answer < /dev/tty
  [[ "$answer" == "y" ]] || { echo "not confirmed; nothing changed" >&2; exit 1; }
fi

mkdir -p build/runs build/stack
report="build/runs/$lab.txt"
: > "$report"
say() { echo "$1" | tee -a "$report"; }

# The lab tools, compiled with the JDK alone, as Section 8 of the module does.
javac -Xlint:all -Werror -d build/labtools labtools/src/main/java/io/plainround/seatwave/ratelimit/labtools/*.java
probe() { java -cp build/labtools io.plainround.seatwave.ratelimit.labtools.LatencyProbe "$@"; }
traffic() { java -cp build/labtools io.plainround.seatwave.ratelimit.labtools.HoldTraffic "$@"; }
redis_cli() { docker exec "$PROJECT-redis-1" redis-cli "$@"; }

# A fan no earlier run used (the issuer accepts fan-1 to fan-999999999), and a seat number for
# booking (A-1 to Z-99999).
new_fan() { echo "fan-$(( RANDOM * 10000 + RANDOM % 10000 + 1 ))"; }
new_seat() { echo "$(( RANDOM % 9000 + 1000 ))$1"; }

token_for() {
  curl -s --fail --max-time 5 -X POST "http://127.0.0.1:21220/v1/tokens?subject=$1" \
    | sed -n 's/.*"accessToken":"\([^"]*\)".*/\1/p'
}

# The script calls Redis has run so far and the microseconds it spent in them, by its own count
# (INFO commandstats: EVALSHA, plus the EVAL that loads the script once).
script_stats() {
  redis_cli INFO commandstats | tr -d '\r' \
    | awk -F'[:=,]' '/^cmdstat_eval(sha)?:/ { calls += $3; usec += $5 } END { print calls + 0, usec + 0 }'
}

# The CPU time a container has used since it started, in microseconds, from its control group.
cpu_usec() {
  docker exec "$PROJECT-$1-1" cat /sys/fs/cgroup/cpu.stat | awk '$1 == "usage_usec" { print $2 }'
}

gateways_cpu_usec() {
  local total=0 gateway
  for gateway in "${GATEWAYS[@]}"; do
    total=$(( total + $(cpu_usec "$gateway") ))
  done
  echo "$total"
}

# One value of one metric of gateway-1, from its management port.
metric() {
  curl -s --max-time 2 "http://127.0.0.1:21210/actuator/metrics/$1" \
    | grep -o "\"statistic\":\"$2\",\"value\":[0-9.Ee+-]*" | sed 's/.*"value"://'
}

# The limiter's store-call timer on gateway-1: count, mean and the p50 and p99 gauges, in ms.
print_store_timer() {
  local name="seatwave.ratelimit.added.latency"
  local count total p50 p99
  count="$(metric "$name" COUNT)"
  total="$(metric "$name" TOTAL_TIME)"
  p50="$(metric "$name.percentile?tag=phi:0.5" VALUE)"
  p99="$(metric "$name.percentile?tag=phi:0.99" VALUE)"
  say "$(awk -v count="$count" -v total="$total" -v p50="$p50" -v p99="$p99" 'BEGIN {
    printf "gateway-1 store-call timer: %d calls since start, mean %.3f ms; last 2 min p50 %.3f ms, p99 %.3f ms", count, total / count * 1000, p50 * 1000, p99 * 1000 }')"
}

# The three policies of application.yaml as SPRING_APPLICATION_JSON, with the hold policy's
# when-store-down and is-shadow and the instance count given here. Spring Boot never merges a list
# from two places, so changing one field of one policy means giving the whole list again.
policies_json() {
  local instances="$1" holds_store_down="$2" holds_shadow="$3"
  printf "'{\"seatwave\":{\"ratelimit\":{\"gateway-instances\":%s,\"policies\":[" "$instances"
  printf '{\"name\":\"holds\",\"method\":\"POST\",\"path-prefix\":\"/v1/holds\",\"key\":\"SUBJECT\",\"capacity\":5,\"refill-tokens\":1,\"refill-period\":\"10s\",\"when-store-down\":\"%s\",\"is-shadow\":%s},' "$holds_store_down" "$holds_shadow"
  printf '{\"name\":\"seat-reads\",\"method\":\"GET\",\"path-prefix\":\"/v1/events\",\"key\":\"ADDRESS\",\"capacity\":40,\"refill-tokens\":20,\"refill-period\":\"1s\",\"when-store-down\":\"LOCAL\",\"is-shadow\":false},'
  printf '{\"name\":\"fan-requests\",\"method\":\"ANY\",\"path-prefix\":\"/\",\"key\":\"SUBJECT\",\"capacity\":30,\"refill-tokens\":10,\"refill-period\":\"1s\",\"when-store-down\":\"LOCAL\",\"is-shadow\":false}'
  printf "]}}}'"
}

# Recreates the gateways with extra environment from the override file, or as compose.yaml says.
gateways_with() {
  if [[ $# -gt 0 ]]; then
    {
      echo "services:"
      for gateway in "${GATEWAYS[@]}"; do
        echo "  $gateway:"
        echo "    environment:"
        for setting in "$@"; do echo "      $setting"; done
      done
    } > "$OVERRIDE"
    "${compose[@]}" -f compose.yaml -f "$OVERRIDE" up -d "${GATEWAYS[@]}" > /dev/null 2>&1
  else
    "${compose[@]}" up -d "${GATEWAYS[@]}" > /dev/null 2>&1
  fi
}

# A gateway just started runs its first seconds in the interpreter while its one CPU compiles; a
# 15 s run of seat reads at 200/s through each, thrown away, so the measured run meets warm code.
warm_gateways() {
  local instance
  for instance in 0 1 2; do
    probe "http://127.0.0.1:2120${instance}$SEAT_PAGE" 200 15 address=random > /dev/null &
  done
  wait
}

# Waits until every gateway answers health again (Redis may be down, which is not their health).
wait_until_gateways_answer() {
  local waited instance healthy
  for (( waited = 0; waited < WAIT_SECONDS; waited++ )); do
    healthy=0
    for instance in 0 1 2; do
      if curl -s --max-time 2 "http://127.0.0.1:2121${instance}/actuator/health" 2> /dev/null | grep -q '"status":"UP"'; then
        healthy=$(( healthy + 1 ))
      fi
    done
    [[ "$healthy" -eq 3 ]] && return 0
    sleep 1
  done
  echo "the gateways did not answer health within ${WAIT_SECONDS} s" >&2
  return 1
}

# Puts the stack back as compose.yaml describes it, and checks it is ready.
restore() {
  "${compose[@]}" unpause redis > /dev/null 2>&1 || true
  "${compose[@]}" up -d --quiet-pull > /dev/null 2>&1
  wait_until_ready "$WAIT_SECONDS" > /dev/null
  # A gateway this lab recreated is cold; the next lab should not measure its warm-up.
  warm_gateways
  audit "$lab: stack put back as compose.yaml describes it"
}

six_holds() {
  local token seat
  token="$(token_for "$(new_fan)")"
  say "\$ for seat in 1 2 3 4 5 6; do curl ... \"http://127.0.0.1:2120\$((seat % 3))/v1/holds\"; done"
  local codes=""
  for seat in 1 2 3 4 5 6; do
    codes+="$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $token" \
      -H "Idempotency-Key: k-$seat" -H 'Content-Type: application/json' \
      -d "{\"eventId\":1,\"seatId\":\"C-$(new_seat "$seat")\"}" "http://127.0.0.1:2120$(( seat % 3 ))/v1/holds") "
  done
  say "$codes"
  say "\$ curl -s -i -X POST ... http://127.0.0.1:21200/v1/holds | grep -E '^HTTP|^Retry-After|^RateLimit|violated'"
  curl -s -i -X POST -H "Authorization: Bearer $token" -H 'Idempotency-Key: k-7' -H 'Content-Type: application/json' \
    -d "{\"eventId\":1,\"seatId\":\"C-$(new_seat 7)\"}" "$GATEWAY/v1/holds" | tr -d '\r' \
    | grep -E '^HTTP|^Retry-After|^RateLimit|violated' | sed -E 's/urn:uuid:[0-9a-f-]+/urn:uuid:.../' | tee -a "$report"
}

case "$lab" in
  shared)
    say "== one fan, 60 holds, one every 400 ms, round robin over the three gateways"
    traffic paced 60 400 | tee -a "$report"
    say "== one fan, 300 holds released together, 100 through each gateway"
    traffic burst 300 | tee -a "$report"
    # A burst can leave a gateway in its 1 s store quarantine; the curl holds start after it.
    sleep 2
    say "== six quick holds with curl"
    six_holds
    ;;
  per-instance)
    trap restore EXIT
    gateways_with "SPRING_APPLICATION_JSON: $(policies_json 1 LOCAL false)"
    wait_until_ready "$WAIT_SECONDS" > /dev/null
    warm_gateways
    "${compose[@]}" stop redis > /dev/null 2>&1
    audit "per-instance: gateways with their own full buckets for holds; Redis stopped"
    wait_until_gateways_answer
    say "== buckets in each instance's memory: one fan, 60 holds, one every 400 ms"
    traffic paced 60 400 | tee -a "$report"
    say "== buckets in each instance's memory: one fan, 300 holds released together"
    traffic burst 300 | tee -a "$report"
    ;;
  store-down)
    trap restore EXIT
    token="$(token_for "$(new_fan)")"
    "${compose[@]}" stop redis > /dev/null 2>&1
    audit "store-down: Redis stopped"
    say "== Redis stopped: the store-failure case of ThreeInstanceLimitTest"
    ./gradlew :gateway:stackTest --tests '*ThreeInstanceLimitTest' -Dseatwave.redis=stopped > build/runs/store-down-test.txt 2>&1
    grep -E "ThreeInstanceLimitTest > .*(PASSED|FAILED|SKIPPED)" build/runs/store-down-test.txt | tee -a "$report"
    say "\$ curl -s http://127.0.0.1:21210/actuator/metrics/seatwave.ratelimit.store.errors | grep -o '\"value\":[0-9.]*'"
    curl -s http://127.0.0.1:21210/actuator/metrics/seatwave.ratelimit.store.errors | grep -o '"value":[0-9.]*' | tee -a "$report"
    "${compose[@]}" start redis > /dev/null 2>&1
    audit "store-down: Redis started"
    sleep 2
    say "one hold after Redis started -> $(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: Bearer $token" \
      -H 'Idempotency-Key: k-after' -H 'Content-Type: application/json' -d "{\"eventId\":1,\"seatId\":\"D-$(new_seat 1)\"}" "$GATEWAY/v1/holds")"
    say "== 60 s of holds (5/s, new fans) and seat reads (15/s); Redis paused at 10 s, unpaused at 25 s, stopped at 35 s, started at 50 s"
    traffic outage 60 > build/runs/outage.txt &
    sender=$!
    # The tool mints its 300 tokens first; the timeline starts when it says it is sending.
    until grep -q '^sending from now' build/runs/outage.txt 2> /dev/null || ! kill -0 "$sender" 2> /dev/null; do sleep 0.1; done
    ( sleep 10; "${compose[@]}" pause redis > /dev/null 2>&1; audit "store-down: Redis paused"
      sleep 15; "${compose[@]}" unpause redis > /dev/null 2>&1; audit "store-down: Redis unpaused"
      sleep 10; "${compose[@]}" stop redis > /dev/null 2>&1; audit "store-down: Redis stopped"
      sleep 15; "${compose[@]}" start redis > /dev/null 2>&1; audit "store-down: Redis started" ) &
    changer=$!
    wait "$sender"
    wait "$changer"
    tee -a "$report" < build/runs/outage.txt
    for instance in 0 1 2; do
      say "gateway-$(( instance + 1 )) store errors since start: $(curl -s "http://127.0.0.1:2121${instance}/actuator/metrics/seatwave.ratelimit.store.errors" | grep -o '"value":[0-9.]*' | sed 's/.*://')"
    done
    ;;
  latency)
    trap restore EXIT
    printf 'services:\n  catalog:\n    ports: ["127.0.0.1:21230:8080"]\n' > "$OVERRIDE"
    "${compose[@]}" -f compose.yaml -f "$OVERRIDE" up -d catalog > /dev/null 2>&1
    audit "latency: catalog published on 21230"
    wait_until_ready "$WAIT_SECONDS" > /dev/null
    probe "http://127.0.0.1:21230$SEAT_PAGE" "$RATE" "$WARMUP" > /dev/null
    say "== catalog, direct: http://127.0.0.1:21230$SEAT_PAGE at ${RATE}/s for ${SECONDS_TIMED} s, after a ${WARMUP} s warm-up"
    probe "http://127.0.0.1:21230$SEAT_PAGE" "$RATE" "$SECONDS_TIMED" | tee -a "$report"
    probe "$GATEWAY$SEAT_PAGE" "$RATE" "$WARMUP" address=random > /dev/null
    read -r calls_before usec_before <<< "$(script_stats)"
    redis_before="$(cpu_usec redis)"
    gateway_before="$(cpu_usec gateway-1)"
    say "== catalog through gateway-1 (seat-reads policy, a new address key per request): $GATEWAY$SEAT_PAGE at ${RATE}/s for ${SECONDS_TIMED} s, after a ${WARMUP} s warm-up"
    probe "$GATEWAY$SEAT_PAGE" "$RATE" "$SECONDS_TIMED" address=random | tee -a "$report"
    read -r calls_after usec_after <<< "$(script_stats)"
    redis_after="$(cpu_usec redis)"
    gateway_after="$(cpu_usec gateway-1)"
    print_store_timer
    calls=$(( calls_after - calls_before ))
    say "$(awk -v calls="$calls" -v usec=$(( usec_after - usec_before )) -v cpu=$(( redis_after - redis_before )) -v gw=$(( gateway_after - gateway_before )) -v planned=$(( RATE * SECONDS_TIMED )) 'BEGIN {
      printf "Redis during the run: %d script calls, %.2f us each inside the script (INFO commandstats), %.2f us of Redis CPU each (its control group)\ngateway-1 CPU during the run: %d us per planned request", calls, usec / calls, cpu / calls, gw / planned }')"
    # A seat-read bucket is forgotten 50 ms after its last use; a hold bucket lives for 10 s.
    fan="$(new_fan)"
    curl -s -o /dev/null -X POST -H "Authorization: Bearer $(token_for "$fan")" -H 'Idempotency-Key: k-memory' \
      -H 'Content-Type: application/json' -d "{\"eventId\":1,\"seatId\":\"E-$(new_seat 1)\"}" "$GATEWAY/v1/holds"
    key="seatwave:rl:holds:sub:$fan"
    say "one bucket: $(redis_cli MEMORY USAGE "$key") bytes (MEMORY USAGE $key), expiring in $(redis_cli PTTL "$key") ms; used_memory $(redis_cli INFO memory | tr -d '\r' | sed -n 's/^used_memory://p') bytes"
    ;;
  redis-capacity)
    image="$(sed -n 's/^ *image: \(redis:[^ ]*\)$/\1/p' compose.yaml)"
    sha="$(redis_cli SCRIPT LOAD "$(cat gateway/src/main/resources/ratelimit/token-bucket.lua)" | tr -d '\r')"
    read -r calls_before usec_before <<< "$(script_stats)"
    redis_before="$(cpu_usec redis)"
    say "== redis-benchmark -h redis -q -n 200000 -c 50 -r 100000 EVALSHA <token-bucket.lua> 1 seatwave:rl:bench:__rand_int__ 5 1 10000"
    docker run --rm --label "com.docker.compose.project=$PROJECT" --network "${PROJECT}_lab" --cpus 1 "$image" \
      redis-benchmark -h redis -q -n 200000 -c 50 -r 100000 EVALSHA "$sha" 1 'seatwave:rl:bench:__rand_int__' 5 1 10000 \
      | tr '\r' '\n' | grep -v '^$' | tail -1 | tee -a "$report"
    read -r calls_after usec_after <<< "$(script_stats)"
    redis_after="$(cpu_usec redis)"
    calls=$(( calls_after - calls_before ))
    say "$(awk -v calls="$calls" -v usec=$(( usec_after - usec_before )) -v cpu=$(( redis_after - redis_before )) 'BEGIN {
      printf "Redis during the run: %d script calls, %.2f us each inside the script, %.2f us of Redis CPU each", calls, usec / calls, cpu / calls }')"
    ;;
  retry-storm)
    say "== seat reads through gateway-2 at 100/s for ${STORM_SECONDS} s, alone"
    probe "http://127.0.0.1:21201$SEAT_PAGE" 100 "$STORM_SECONDS" address=random | tee -a "$report"
    read -r calls_before usec_before <<< "$(script_stats)"
    gateways_before="$(gateways_cpu_usec)"
    redis_before="$(cpu_usec redis)"
    say "== the same probe beside ${ACCOUNTS} accounts that resend every refused hold at once for ${STORM_SECONDS} s"
    probe "http://127.0.0.1:21201$SEAT_PAGE" 100 "$STORM_SECONDS" address=random > build/runs/storm-probe.txt &
    prober=$!
    traffic retry "$ACCOUNTS" "$STORM_SECONDS" | tee -a "$report"
    wait "$prober"
    read -r calls_after usec_after <<< "$(script_stats)"
    gateways_after="$(gateways_cpu_usec)"
    redis_after="$(cpu_usec redis)"
    say "seat-read probe during the storm:"
    tee -a "$report" < build/runs/storm-probe.txt
    calls=$(( calls_after - calls_before ))
    say "$(awk -v calls="$calls" -v usec=$(( usec_after - usec_before )) -v gw=$(( gateways_after - gateways_before )) -v redis=$(( redis_after - redis_before )) -v seconds="$STORM_SECONDS" 'BEGIN {
      printf "Redis: %.0f script calls per second, %.2f us each inside the script, %.0f ms of Redis CPU per second; the three gateways: %.2f CPUs busy", calls / seconds, usec / calls, redis / seconds / 1000, gw / seconds / 1000000 }')"
    ;;
  mix)
    trap restore EXIT
    gateways_with "SPRING_APPLICATION_JSON: $(policies_json 3 REFUSE true)"
    audit "mix: hold policy in shadow mode"
    wait_until_ready "$WAIT_SECONDS" > /dev/null
    warm_gateways
    say "== Lab 3's minute, ${FANS} fans (one in 20 eager) and ${BOTS} bots, hold policy in shadow mode"
    traffic mix "$FANS" "$BOTS" | tee -a "$report"
    gateways_with
    audit "mix: hold policy enforcing again"
    wait_until_ready "$WAIT_SECONDS" > /dev/null
    warm_gateways
    say "== the same minute with the hold policy enforcing"
    traffic mix "$FANS" "$BOTS" | tee -a "$report"
    ;;
  shedding)
    trap restore EXIT
    printf 'services:\n  booking:\n    ports: ["127.0.0.1:21231:8080"]\n' > "$OVERRIDE"
    for limit in 100000 "$SHED_LIMIT"; do
      SEATWAVE_BOOKING_MAX_IN_FLIGHT="$limit" SEATWAVE_BOOKING_READ_DELAY="$READ_DELAY" SEATWAVE_BOOKING_READ_WORKERS="$READ_WORKERS" \
        "${compose[@]}" -f compose.yaml -f "$OVERRIDE" up -d booking > /dev/null 2>&1
      audit "shedding: booking with reads of $READ_DELAY on $READ_WORKERS workers, $limit in flight"
      until curl -s --fail --max-time 2 -o /dev/null -H 'X-SeatWave-Subject: fan-42' 'http://127.0.0.1:21231/v1/holds?eventId=1'; do sleep 1; done
      # Warm-up below the capacity, so the JIT has compiled the path before the timed run.
      probe "http://127.0.0.1:21231/v1/holds?eventId=1" 100 "$WARMUP" subject=fan-42 > /dev/null
      say "== booking alone, reads of ${READ_DELAY} on ${READ_WORKERS} workers, max in flight ${limit}: GET /v1/holds at ${SHED_RATE}/s for ${SECONDS_TIMED} s"
      probe "http://127.0.0.1:21231/v1/holds?eventId=1" "$SHED_RATE" "$SECONDS_TIMED" subject=fan-42 | tee -a "$report"
    done
    ;;
esac
echo "report: $report"
