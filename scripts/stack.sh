#!/usr/bin/env bash
# Starts, tests and stops the Module 12 stack: three gateway instances sharing one Redis, catalog,
# booking with its load shedder, the mobile backend-for-frontend and the issuer stand-in, under the
# Compose project name seatwave-ratelimit, with only the gateways (21200 to 21202, management 21210
# to 21212) and the issuer (21220) published on 127.0.0.1. Module 11's script, unchanged but for
# these names; adapted there from the Module 10 project's scripts/stack.sh.
#
# Usage: scripts/stack.sh <up|down|test|status> [--run] [--yes]
#   default   a dry run: print what would happen, change nothing
#   --run     do it; asks for confirmation on the terminal first
#   --yes     skip the question (for a reader who has already read the plan)
#
#   up      generate the issuer's key if there is none, build the jars and the images, start the
#           stack, wait until the gateway, the issuer and the catalog answer
#   down    stop and remove every container, network and volume of the project (down -v)
#   test    up, ./gradlew :gateway:stackTest, then down, on every path (also when a test fails)
#   status  list the project's containers (read-only, no --run needed)
#
# Guardrails: the only target is the project seatwave-ratelimit; a dry run needs no Docker and
# changes nothing; with --run, Docker must answer first or nothing is done; every change appends
# an audit line to build/stack/audit.log; "down" checks afterwards that no container, network or
# volume of the project is left; any failure exits non-zero.
set -euo pipefail

readonly WAIT_SECONDS=120

usage() {
  echo "usage: $0 <up|down|test|status> [--run] [--yes]" >&2
  exit 2
}

[[ $# -ge 1 ]] || usage
action="$1"
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
case "$action" in up|down|test|status) ;; *) usage ;; esac

cd "$(dirname "$0")/.."
source scripts/common.sh

if [[ "$action" == "status" ]]; then
  docker_must_answer
  "${compose[@]}" ps --all
  exit 0
fi

echo "plan: $action the Compose project $PROJECT"
case "$action" in
  up) echo "  key into $KEY_FILE if missing; ./gradlew -q bootJar; ${compose[*]} up -d --build --quiet-pull; wait until ready" ;;
  down) echo "  ${compose[*]} down -v --remove-orphans; check nothing of $PROJECT is left" ;;
  test) echo "  up, then ./gradlew :gateway:stackTest, then down -v (always)" ;;
esac
if [[ "$mode" == "dry-run" ]]; then
  # Checked after the plan, so a dry run changes nothing and needs no Docker.
  echo "dry run: nothing changed. Add --run to do it."
  exit 0
fi
docker_must_answer
if [[ "$confirmed" != "yes" ]]; then
  read -r -p "Go ahead with '$action' for $PROJECT? [y/N] " answer < /dev/tty
  [[ "$answer" == "y" ]] || { echo "not confirmed; nothing changed" >&2; exit 1; }
fi

up() {
  if [[ ! -s "$KEY_FILE" ]]; then
    # The issuer's private key, new on each machine, in build/ (ignored by git and by Docker).
    ./gradlew -q :issuer:keys --args="generate $KEY_FILE"
  fi
  ./gradlew -q bootJar
  "${compose[@]}" up -d --build --quiet-pull
  audit "up: started $PROJECT"
  wait_until_ready "$WAIT_SECONDS"
}

down() {
  "${compose[@]}" down -v --remove-orphans
  audit "down: removed $PROJECT"
  # Check after: nothing of this project may be left.
  local left
  left="$(docker ps -aq --filter "label=com.docker.compose.project=$PROJECT";
          docker network ls -q --filter "label=com.docker.compose.project=$PROJECT";
          docker volume ls -q --filter "label=com.docker.compose.project=$PROJECT")"
  if [[ -n "$left" ]]; then
    echo "something of $PROJECT is still there:" >&2
    echo "$left" >&2
    return 1
  fi
  echo "stack is down: no container, network or volume of $PROJECT is left"
}

case "$action" in
  up) up ;;
  down) down ;;
  test)
    # down runs on every path out of this block, including a failed test.
    trap 'down' EXIT
    up
    ./gradlew :gateway:stackTest
    ;;
esac
