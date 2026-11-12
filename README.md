# seatwave-ratelimit

SeatWave, the ticket-booking service of the course System Design Fundamentals, with one rate limit per fan enforced across three API gateway instances through one Redis, and a load shedder in the booking service. This is the project of **Module 12, "Rate limiting"**.

## What it shows

- A token-bucket limiter as a filter in Module 11's gateway (Spring Cloud Gateway 5.0.4 Server Web MVC on Spring Boot 4.1.2): per-route policies as checked configuration, holds keyed by the verified fan and seat reads by the client's address, one atomic Lua script in Redis per request, `429` with `Retry-After`, the IETF draft's `RateLimit-Policy` and `RateLimit` fields and a problem body.
- What each route does when Redis does not answer within 50 ms: holds fail closed (`503`), reads fall back to each instance's share of the limit; a 1 s quarantine with one probe after it.
- Booking's in-flight load shedder (`503` with `Retry-After` beyond 64 in flight).
- The JDK-only algorithms (token bucket, fixed window, sliding log, sliding window counter) and three programs that show them, and lab tools that drive one fan, a crowd, bots and a retry loop through the three gateways.

Measured on the runs below (this Mac, Docker Desktop's virtual machine, every timing through its port forwarder, other agents' builds running): one fan sending 60 holds through three gateways was allowed 7 against a bound of 7.5, and 21 with a bucket in each instance; 300 holds released together, 5; the limiter's store call took 0.193 ms at p50 and 2.617 ms at p99, and one Redis ran 126,582 script calls a second at 7.92 µs of CPU each; with shedding at 8 in flight booking served at a p99 of 44.86 ms, without it 1,705 of 12,000 reads were answered within 5 s.

## Where the code came from

- **Module 11's built project**, `seatwave-gateway` revision `864e840`: every file of it, with the package root `io.plainround.seatwave.gateway` renamed to `io.plainround.seatwave.ratelimit`, the project name and the ports changed (21100 to 21102 became 21200, 21210 and 21220). Unchanged in behavior: catalog, the BFF, the issuer, the gateway's routes and token check, the build's root file, the `Dockerfile` and `scripts/stack.sh`.
- **Module 12's code seed**, `_tools/module-12-code/` in the course's tools folder: `limits/`, the gateway's `edge.limiter` package and `token-bucket.lua`, booking's `booking.shedding` package, both programs' build files and `application.yaml`, `ThreeInstanceLimitTest`, `compose.yaml`, `settings.gradle.kts`, `gradle/libs.versions.toml`.
- **New in this build**: the unit tests of the limiter, the shedder and the algorithms, `HoldTraffic`, `scripts/lab.sh` (Module 11's labs stay in Module 11's project), and the fixes listed in `CHANGELOG.md`.

## Prerequisites

- JDK 25 (the build's toolchain asks for 25; it was run on Eclipse Temurin 25.0.2; the images run 25.0.5).
- Docker with Compose v2 for the stack (run on Docker Desktop, engine 24.0.6, Compose v2.23.0-desktop.1). `./gradlew check` needs no Docker.
- `curl` on the machine for the scripts.

## Dependencies

Every version is pinned in `gradle/libs.versions.toml`; transitive versions are in each program's `gradle.lockfile`, the build's own in `buildscript-gradle.lockfile`. All were checked on Maven Central and Docker Hub on 12 November 2026, and OSV listed no vulnerability for any of the 192 locked jars that day.

| Coordinates | Version | Checked | License | Why the JDK alone is not enough |
|---|---|---|---|---|
| `org.springframework.boot:spring-boot-starter-data-redis` (Spring Data Redis) | 4.1.2, managed by Boot 4.1.2 | 12 Nov 2026 | Apache-2.0 | A Redis connection with timeouts from `spring.data.redis.*`, and `RedisScript`, which sends the script by its digest and falls back to its source on `NOSCRIPT`. |
| `io.lettuce:lettuce-core` (through the starter) | 7.5.2.RELEASE, managed by Boot 4.1.2 (7.9.0.RELEASE is newer and not forced over Boot's choice) | 12 Nov 2026 | MIT | The Redis client under Spring Data Redis: one multiplexed connection per gateway carries all its calls. |
| `org.springframework.boot:spring-boot-dependencies` (BOM) and the Spring Boot Gradle plugin | 4.1.2 | 12 Nov 2026 | Apache-2.0 | An HTTP server, JSON binding, validated settings, metrics, in one tested set of versions. |
| `org.springframework.cloud:spring-cloud-dependencies` (BOM), `spring-cloud-starter-gateway-server-webmvc` | 2025.1.4, 5.0.4 | 12 Nov 2026 | Apache-2.0 | Module 11's routes and proxy. |
| `org.springframework.boot:spring-boot-starter-security-oauth2-resource-server` (Spring Security 7.1.2) | managed by Boot | 12 Nov 2026 | Apache-2.0 | Module 11's token check, which runs before the limiter. |
| `com.nimbusds:nimbus-jose-jwt` | 10.9.1 | 12 Nov 2026 | Apache-2.0 | The issuer's ES256 signing (Module 11). |
| `org.springframework.boot:spring-boot-starter-webmvc`, `-restclient`, `-validation`, `-actuator` | managed by Boot | 12 Nov 2026 | Apache-2.0 | Endpoints, REST clients, start-up checks of every setting, the limiter's and the shedder's metrics. |
| `com.github.sviperll:result4j` | 1.2.0 | 12 Nov 2026 | Apache-2.0 | Booking's expected refusals (Module 11). |
| `org.springframework.boot:spring-boot-starter-test` (JUnit Jupiter 6.0.3, AssertJ 3.27.7, Awaitility) | managed by Boot | 12 Nov 2026 | EPL-2.0, Apache-2.0 | The tests. |
| `org.apache.commons:commons-lang3` (build classpath only, a constraint) | 3.22.0 | 12 Nov 2026 | Apache-2.0 | Replaces 3.16.0 on the Spring Boot plugin's classpath (CVE-2025-48924). |
| Spotless, google-java-format, JaCoCo (build) | 8.11.0, 2.0.0, 0.8.16 | 12 Nov 2026 | Apache-2.0, Apache-2.0, EPL-2.0 | One enforced format and a 0.80 line-coverage floor per program. |

The images: `redis:8.10.3@sha256:1f88291b4c57bdf249f02eb085a8669889b2d2954e91d4de5118a5da6b78dd53` (the newest release; 8.12 was at RC2) and `eclipse-temurin:25.0.5_8-jre-noble@sha256:e3b6f7887a50f20d8f07abf279d8a81a5953fb398a9555bd9edd56b9bd15968c`, both Docker Official Images with an `arm64` variant, checked on Docker Hub on 12 November 2026. Gradle 9.9.1 through the wrapper (Module 11's, checksums checked there that day). The `limits` program uses the JDK alone.

## Commands and what they printed

Run on 12 November 2026 on an Apple M2 Max (12 cores, 32 GB, macOS 27.2) with other agents' builds running; containers in Docker Desktop's Linux virtual machine (12 CPUs, 8 GB); the lab tools on the Mac. The timed runs were taken under the course's measurement lock, while no other course project was measuring; the 1-minute load averages before each run were:

```text
2026-11-12T12:09:37Z
openjdk version "25.0.2" 2026-01-20 LTS
Docker 24.0.6, 12 CPUs, 8225304576 bytes, Docker Compose version v2.23.0-desktop.1
load averages before the JDK-only programs: 3.91 5.21 8.08
load averages before the stack: 3.75 5.15 8.04
lab network 192.168.240.0/20, gateway 192.168.240.1
load averages before the stack tests: 3.64 5.06 7.96
load averages before lab shared: 4.95 5.26 7.93
load averages before lab per-instance: 6.31 5.56 7.96
load averages before lab store-down: 13.96 9.36 9.24
load averages before lab latency: 7.22 8.20 8.80
load averages before lab retry-storm: 4.30 6.71 8.13
load averages before lab mix: 4.05 6.35 7.93
load averages before lab shedding: 3.76 5.20 7.13
load averages before the end (after the runs): 4.29 4.70 6.64
load averages before the second round: 4.37 6.60 7.07
load averages before lab shared (second round): 3.64 6.15 6.88
load averages before lab redis-capacity (second round): 3.21 5.77 6.71
load averages before the end (after the runs) (second round): 3.21 5.77 6.71
```

Your numbers will differ.

Build, test and check the format and the coverage floor (no Docker needed):

```text

BUILD SUCCESSFUL in 23s
62 actionable tasks: 62 executed
```

179 tests in seven programs; line coverage per program, each above the 0.80 floor:

```text
gateway     tests  65 failures 0 lines 368/381 = 0.966
catalog     tests  13 failures 0 lines 74/78 = 0.949
booking     tests  39 failures 0 lines 121/123 = 0.984
mobile-bff  tests   8 failures 0 lines 108/112 = 0.964
issuer      tests  21 failures 0 lines 79/85 = 0.929
labtools    tests  15 failures 0 lines 87/89 = 0.978
limits      tests  18 failures 0 lines 100/103 = 0.971
total tests 179
```

The formatter's check, alone:

```text
$ ./gradlew spotlessCheck --rerun-tasks --console=plain
> Task :booking:spotlessJavaCheck
> Task :catalog:spotlessJavaCheck
> Task :gateway:spotlessJavaCheck
> Task :issuer:spotlessJavaCheck
> Task :labtools:spotlessJavaCheck
> Task :limits:spotlessJavaCheck
> Task :mobile-bff:spotlessJavaCheck
BUILD SUCCESSFUL in 699ms
exit status 0
Java files under the formatter: 102
```

The whole check also passes from a copy at a long path and from a folder with a space in its name:

```text
$ ./gradlew clean check   (in a copy at a path of 314 characters)
BUILD SUCCESSFUL in 39s
exit status 0
$ ./gradlew clean check   (in a copy at a path of 203 characters, with a space in a folder name)
BUILD SUCCESSFUL in 54s
exit status 0
```

The JDK-only programs (`./gradlew -q :limits:algorithms`, `:limits:sharedLimit` and `:limits:shed` run the same classes):

```text
$ javac -Xlint:all -Werror -d build/limits limits/src/main/java/io/plainround/seatwave/ratelimit/limits/*.java
exit status 0
$ java -cp build/limits io.plainround.seatwave.ratelimit.limits.SharedLimitDemo
one fan, 300 holds at once, 100 through each of 3 gateway instances; bucket of 5, time frozen, so at most 5 may pass
way the bucket is kept                         allowed in 5 runs   bound 5 kept
1 one bucket per instance, in its memory       [15, 15, 15, 15, 15] false
2 shared, read then write (two commands)       [300, 300, 300, 300, 300] false
3 shared, one atomic check-and-take            [5, 5, 5, 5, 5]     true
$ java -cp build/limits io.plainround.seatwave.ratelimit.limits.ShedDemo
3,200 holds per second for 1 s into 64 workers of 40 ms (capacity about 1,600 per second)
way                          arrived  served  shed  shed %  served p50 ms  p99 ms  max ms
queue every hold                3200    3200     0     0.0          642.7  1270.5  1288.8
shed beyond 64 in flight        3200    1421  1779    55.6           44.0    50.2    50.2
```

`AlgorithmDemo`'s timelines are in the module (Section 8, command 2).

Start the stack, run the stack tests and stop it (a dry run unless `--run` is given):

```text
$ scripts/stack.sh up --run --yes
stack is up after 6 s: three gateways, issuer and catalog answer
$ ./gradlew :gateway:stackTest
StackTest > refusesAWriteToTheCatalogAndAnUnknownVersionWithoutAToken() PASSED
StackTest > servesTheCatalogWithoutAToken() PASSED
StackTest > refusesAHoldWithoutATokenBeforeItReachesBooking() PASSED
StackTest > drawsTheSeatScreenWithTheFansOwnHoldThroughTheMobileRoute() PASSED
StackTest > refusesAnUnroutedPathEvenWithAGoodToken() PASSED
StackTest > answersHealthOnTheManagementPortOnly() PASSED
StackTest > holdsASeatForTheFanTheTokenNamesNotTheOneAForgedHeaderNames() PASSED
StackTest > refusesSomethingThatIsNotATokenAsMalformed() PASSED
ThreeInstanceLimitTest > refusesHoldsButServesSeatReadsWhileRedisIsDown() SKIPPED
ThreeInstanceLimitTest > neverAllowsOneFanMoreThanTheBucketPlusRefillAcrossThreeInstances() STANDARD_OUT
ThreeInstanceLimitTest > neverAllowsOneFanMoreThanTheBucketPlusRefillAcrossThreeInstances() PASSED
    holds 60, allowed 7, refused 53, elapsed 24.9 s, bound 7.5, statuses {201=7, 429=53}
```

One fan through the three gateways, paced and all at once, and six holds with `curl` (`scripts/lab.sh shared --run`):

```text
plan: lab shared on the Compose project seatwave-ratelimit: 60 paced holds and 300 simultaneous holds of one fan, then six holds with curl; nothing is changed
== one fan, 60 holds, one every 400 ms, round robin over the three gateways
one fan, 60 holds, one every 400 ms
statuses (0 = no answer): {201=7, 429=53}
passed the limiter per gateway (1 / 2 / 3): 3 / 2 / 2
allowed 7 of 60 in 25.6 s; bound 5 + 0.1 x 25.6 = 7.6; bound kept: true
== one fan, 300 holds released together, 100 through each gateway
one fan, 300 holds released together
statuses (0 = no answer): {201=5, 429=291, 503=4}
passed the limiter per gateway (1 / 2 / 3): 2 / 1 / 2
allowed 5 of 300 in 0.4 s; bound 5 + 0.1 x 0.4 = 5.0; bound kept: true
== six quick holds with curl
$ for seat in 1 2 3 4 5 6; do curl ... "http://127.0.0.1:2120$((seat % 3))/v1/holds"; done
201 201 201 201 201 429 
$ curl -s -i -X POST ... http://127.0.0.1:21200/v1/holds | grep -E '^HTTP|^Retry-After|^RateLimit|violated'
HTTP/1.1 429 
RateLimit-Policy: "holds";q=5;w=50
RateLimit: "holds";r=0;t=10
Retry-After: 10
{"type":"https://iana.org/assignments/http-problem-types#quota-exceeded","title":"Too many requests under policy holds","status":429,"violated-policies":["holds"],"instance":"urn:uuid:..."}
report: build/runs/shared.txt
```

Failure mode 1, a bucket in each instance's memory (`scripts/lab.sh per-instance --run`):

```text
== buckets in each instance's memory: one fan, 60 holds, one every 400 ms
one fan, 60 holds, one every 400 ms
statuses (0 = no answer): {201=21, 429=39}
passed the limiter per gateway (1 / 2 / 3): 7 / 7 / 7
allowed 21 of 60 in 28.8 s; bound 5 + 0.1 x 28.8 = 7.9; bound kept: false
== buckets in each instance's memory: one fan, 300 holds released together
one fan, 300 holds released together
statuses (0 = no answer): {201=11, 429=285, 500=4}
passed the limiter per gateway (1 / 2 / 3): 5 / 5 / 5
allowed 15 of 300 in 0.4 s; bound 5 + 0.1 x 0.4 = 5.0; bound kept: false
```

Lab 2 step 5 and failure mode 2, Redis stopped, then paused and stopped during traffic (`scripts/lab.sh store-down --run`), the lines around each change:

```text
== Redis stopped: the store-failure case of ThreeInstanceLimitTest
ThreeInstanceLimitTest > refusesHoldsButServesSeatReadsWhileRedisIsDown() PASSED
ThreeInstanceLimitTest > neverAllowsOneFanMoreThanTheBucketPlusRefillAcrossThreeInstances() SKIPPED
$ curl -s http://127.0.0.1:21210/actuator/metrics/seatwave.ratelimit.store.errors | grep -o '"value":[0-9.]*'
"value":5.0
one hold after Redis started -> 201
== 60 s of holds (5/s, new fans) and seat reads (15/s); Redis paused at 10 s, unpaused at 25 s, stopped at 35 s, started at 50 s
sending from now, for 60 s
     9  {201=5}                  {200=15}                       13.4
    10  {201=2, 503=3}           {200=15}                       59.7
    11  {503=5}                  {200=15}                       58.0
    25  {503=5}                  {200=15}                       26.0
    26  {201=5}                  {200=15}                       21.3
    35  {201=5}                  {200=15}                       54.9
    36  {503=5}                  {200=15}                       63.0
    50  {503=5}                  {200=15}                       60.0
    53  {503=5}                  {200=15}                       17.0
    54  {201=5}                  {200=15}                       37.7
holds in all: {201=132, 503=168}; reads in all: {200=900}
gateway-1 store errors since start: 37.0
gateway-2 store errors since start: 43.0
gateway-3 store errors since start: 36.0
```

What the limiter costs (`scripts/lab.sh latency --run`):

```text
== catalog, direct: http://127.0.0.1:21230/v1/events/1/seats?section=A&limit=100 at 500/s for 30 s, after a 10 s warm-up
planned 15000, recorded 15000, answered 15000, no answer 0
statuses (0 = no answer): {200=15000}
latency of answered requests: p50 0.82 ms, p99 3.53 ms, max 55.37 ms
== catalog through gateway-1 (seat-reads policy, a new address key per request): http://127.0.0.1:21200/v1/events/1/seats?section=A&limit=100 at 500/s for 30 s, after a 10 s warm-up
planned 15000, recorded 15000, answered 15000, no answer 0
statuses (0 = no answer): {200=15000}
latency of answered requests: p50 1.22 ms, p99 18.46 ms, max 106.52 ms
gateway-1 store-call timer: 26411 calls since start, mean 0.534 ms; last 2 min p50 0.193 ms, p99 2.617 ms
Redis during the run: 15000 script calls, 13.69 us each inside the script (INFO commandstats), 47.56 us of Redis CPU each (its control group)
gateway-1 CPU during the run: 766 us per planned request
one bucket: 108 bytes (MEMORY USAGE seatwave:rl:holds:sub:fan-275701881), expiring in 9855 ms; used_memory 1779112 bytes
```

One Redis driven to saturation with the script (`scripts/lab.sh redis-capacity --run`):

```text
== redis-benchmark -h redis -q -n 200000 -c 50 -r 100000 EVALSHA <token-bucket.lua> 1 seatwave:rl:bench:__rand_int__ 5 1 10000
EVALSHA eb443f8e230abb4da910494e584c4ba1dd94468c 1 seatwave:rl:bench:__rand_int__ 5 1 10000: 126582.27 requests per second, p50=0.335 msec
Redis during the run: 200000 script calls, 3.99 us each inside the script, 7.92 us of Redis CPU each
```

Failure mode 4, twenty accounts that resend every refused hold at once (`scripts/lab.sh retry-storm --run`):

```text
== seat reads through gateway-2 at 100/s for 20 s, alone
planned 2000, recorded 2000, answered 2000, no answer 0
statuses (0 = no answer): {200=2000}
latency of answered requests: p50 3.17 ms, p99 9.71 ms, max 53.56 ms
== the same probe beside 20 accounts that resend every refused hold at once for 20 s
20 accounts, each resending at once for 20 s
statuses (0 = no answer): {201=120, 429=27257}
passed the limiter per gateway (1 / 2 / 3): 36 / 41 / 43
sent 1369 holds per second in all, 68 per account; allowed 120 (6.0 per account)
seat-read probe during the storm:
planned 2000, recorded 2000, answered 2000, no answer 0
statuses (0 = no answer): {200=2000}
latency of answered requests: p50 4.12 ms, p99 81.04 ms, max 230.59 ms
Redis: 1469 script calls per second, 12.43 us each inside the script, 59 ms of Redis CPU per second; the three gateways: 2.24 CPUs busy
```

Lab 3, fans and bots through the limiter in shadow mode and enforcing (`scripts/lab.sh mix --run`):

```text
== Lab 3's minute, 100 fans (one in 20 eager) and 2 bots, hold policy in shadow mode
kind       callers  holds sent  passed  refused  shadow-refused  callers refused
fan             95         380     380        0               0                0
eager fan        5          30      30        0               5                5
bot              2        6000    6000        0            5947                2
statuses (0 = no answer): {201=6410}
== the same minute with the hold policy enforcing
kind       callers  holds sent  passed  refused  shadow-refused  callers refused
fan             95         380     380        0               0                0
eager fan        5          30      25        5               0                5
bot              2        6000      20     5980               0                2
statuses (0 = no answer): {201=425, 429=5985}
```

Lab 3 step 6 and failure mode 5, booking at twice its capacity without shedding and with it (`scripts/lab.sh shedding --run`):

```text
== booking alone, reads of 40ms on 8 workers, max in flight 100000: GET /v1/holds at 400/s for 30 s
planned 12000, recorded 12000, answered 1705, no answer 10295
statuses (0 = no answer): {0=10295, 200=1705}
latency of answered requests: p50 2307.15 ms, p99 4662.17 ms, max 4932.55 ms
== booking alone, reads of 40ms on 8 workers, max in flight 8: GET /v1/holds at 400/s for 30 s
planned 12000, recorded 12000, answered 12000, no answer 0
statuses (0 = no answer): {200=5728, 503=6272}
latency of answered requests: p50 1.75 ms, p99 43.65 ms, max 84.89 ms
  status 200: 5728 answers, p50 41.20 ms, p99 44.86 ms, max 84.89 ms
  status 503: 6272 answers, p50 0.98 ms, p99 3.65 ms, max 53.77 ms
```

Every test that guards a rule was checked by changing the rule (`23` of `23` changes made a test fail); the test task passed 40 times in a row beside four busy loops (0 failures).

Nothing of the project is left after `scripts/stack.sh down --run`:

```text
$ docker ps -a --filter label=com.docker.compose.project=seatwave-ratelimit -q | wc -l; docker network ls --filter label=com.docker.compose.project=seatwave-ratelimit -q | wc -l; docker volume ls --filter label=com.docker.compose.project=seatwave-ratelimit -q | wc -l
0
0
0
```

## Ports

All on 127.0.0.1 only: the gateways on 21200, 21201 and 21202, their management ports (health, metrics) on 21210, 21211 and 21212, the issuer on 21220. `scripts/lab.sh latency` publishes catalog on 21230 and `scripts/lab.sh shedding` booking on 21231, for that lab only. Redis, catalog, booking and the BFF publish no port otherwise.

## Project structure

See `ARCHITECTURE.md` for packages, the dependency rule, a data-flow diagram, walk-throughs, decisions and invariants.

```text
compose.yaml, Dockerfile, .dockerignore
scripts/         stack.sh, lab.sh, common.sh
limits/          io.plainround.seatwave.ratelimit.limits: the JDK-only algorithms, InFlightLimit, three programs
gateway/         io.plainround.seatwave.ratelimit.edge (Module 11) and .edge.limiter: the rate limiter, token-bucket.lua
booking/         io.plainround.seatwave.ratelimit.booking (Module 11) and .booking.shedding: the load shedder
catalog/         io.plainround.seatwave.ratelimit.catalog: events and seats (Module 11)
mobile-bff/      io.plainround.seatwave.ratelimit.bff: the seat screen (Module 11)
issuer/          io.plainround.seatwave.ratelimit.issuer: key set and token stand-in (Module 11)
labtools/        io.plainround.seatwave.ratelimit.labtools: JDK-only tools, HoldTraffic
```

## What is simplified

- Three gateway instances on one laptop, each limited to one CPU; the test plays the balancer by sending to each instance's port in turn. Production runs Module 11's eleven machines behind Module 10's balancer pair.
- One Redis without a replica and without persistence; a Redis restart refills every bucket once. Production runs a primary and a replica.
- In the lab the trusted-proxy range is the whole Compose network, which contains Docker's port forwarder, so a request from the Mac can choose its own address key with `X-Forwarded-For` (`LatencyProbe address=random` does so on purpose). Production lists exactly the balancers' addresses.
- Booking keeps holds in memory (Module 11); its lab read delay with `read-workers` stands in for a database pool, so the shedding lab has a service with a fixed capacity.
- Not coded, described in the module: a local "refused until T" cache in each gateway, a coarse per-address limit before the token check, and token leases.
- Module 11's gateway answers `500` when its call to booking fails after the limiter allowed a request (a connect timeout under a burst, seen in the labs); a `502` or `504` would be the right status, a Module 11 behavior left as it is here.

## No CI on purpose

This repository has no `.github/` folder and no CI workflow, on purpose: the course's owner verifies every project locally, with the commands above.

## The Compose path

`compose.yaml` holds all the infrastructure under the project name `seatwave-ratelimit`; `scripts/stack.sh` starts it, tests it and brings it down with `down -v`, then checks that no container, network or volume of the project is left. The issuer's private key is generated into `build/keys/` (ignored by git and Docker) and mounted as a Compose secret.

## License

MIT, see `LICENSE`.
