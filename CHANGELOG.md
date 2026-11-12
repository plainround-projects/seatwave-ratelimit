# Changelog

All notable changes to this project are written here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[Semantic Versioning](https://semver.org/).

## [1.0.0] - 2026-11-12

The first version, built from the code seed of Module 12 (`_tools/module-12-code/` in the course's
tools folder) on top of Module 11's built project (`seatwave-gateway`, revision `864e840`, with
the package root `io.plainround.seatwave.gateway` renamed to `io.plainround.seatwave.ratelimit`),
and run in full: `./gradlew check`, the Compose stack with three gateways and Redis, the stack
tests and every lab.

### Added

- `limits`: the JDK-only `TokenBucket`, `FixedWindow`, `SlidingLog`, `SlidingWindowCounter`,
  `Decision`, `InFlightLimit` and the programs `AlgorithmDemo`, `SharedLimitDemo`, `ShedDemo`
  (tasks `:limits:algorithms`, `:limits:sharedLimit`, `:limits:shed`).
- The gateway's rate limiter (`edge.limiter`): `RateLimitFilter`, `LimitPolicy`,
  `RateLimitProperties`, `RedisTokenBucket` with `ratelimit/token-bucket.lua`, `LocalBuckets`,
  `RateLimitConfiguration`, and the three policies in `application.yaml`.
- Booking's load shedder (`booking.shedding`): `LoadShedFilter`, `SheddingProperties`,
  `SheddingConfiguration`.
- `compose.yaml`: three gateway instances, one Redis 8.10.3 that publishes no port, booking with
  `SEATWAVE_BOOKING_MAX_IN_FLIGHT` and `SEATWAVE_BOOKING_READ_WORKERS`.
- Tests: `RateLimitFilterTest`, `RateLimitPropertiesTest`, `LimitPolicyTest`, `LocalBucketsTest`,
  `RateLimitWiringTest` (the filter's order in the real gateway), `LoadShedFilterTest`,
  `SheddingPropertiesTest`, `ReadLineTest`, `WindowLimitersTest`, `DecisionAndInFlightTest`; the
  seed had `TokenBucketTest` and the stack test `ThreeInstanceLimitTest`.
- `HoldTraffic` (lab traffic: paced, burst, retry, outage, mix) and `LatencyProbe`'s
  `address=random` mode and per-status percentiles; task `:labtools:holdTraffic`.
- `scripts/lab.sh`: the labs `shared`, `per-instance`, `store-down`, `latency`, `redis-capacity`,
  `retry-storm`, `mix` and `shedding`. Module 11's labs stay in Module 11's project.

### Changed (against the seed and Module 11)

- `RedisTokenBucket` waits for Redis at most `spring.data.redis.timeout` on its own clock; the call
  runs on one of 16 platform threads with a queue of 256 (on virtual threads it could start late on
  a one-CPU gateway under a burst). With Redis stopped, Spring Data Redis reconnects under one
  lock, one attempt after another (about 52 ms each in the lab), so with the seed's code the
  hundredth hold of a burst waited about five seconds and many clients gave up without an answer.
- `RateLimitFilter`: after a quarantine only the request that wins a compare-and-set asks the store
  again; in the seed every request that arrived at that moment asked it.
- `management.health.redis.enabled: false` in the gateway: Redis down is not the gateway's health.
- `server.tomcat.remoteip.internal-proxies` lists `192.168.0.0/16` beside `172.16.0.0/12`: Docker
  gives a Compose network a subnet from either pool, and with the seed's setting a lab network in
  `192.168.0.0/16` made the gateways ignore `X-Forwarded-For` (`RateLimitWiringTest` checks that an
  untrusted peer cannot name its own address).
- The store call's p50 and p99 are published as gauges (`management.metrics.distribution
  .percentiles`), so the lab reads them from `/actuator/metrics`.
- `ThreeInstanceLimitTest` warms the three gateways up first, counts a `503 limit-unavailable` as
  the limiter's refusal of an unchecked hold, prints the status mix, and is skipped when Lab 2
  passes `-Dseatwave.redis=stopped`.
- Booking's lab read delay waits for one of `seatwave.booking.read-workers` (default 10,000), so
  the shedding lab has a service with a fixed capacity.
- The stack tests run through Module 11's `:gateway:stackTest` task and `scripts/stack.sh test`,
  not the seed's planned `-PincludeStack` switch.
- `scripts/common.sh` asks all three gateways whether they are ready.
