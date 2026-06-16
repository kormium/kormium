# Sample: cross-instance-cache

A console app showing a **cross-instance cache** — the multi-instance case a real L2 cache must
handle. Two `Database` handles over one **Postgres** act as two app instances; a write on instance
A invalidates instance B's in-process cache through a **Redis** `NotificationTransport`, even though
B never saw the write directly.

This is how kormium intends caching to work: kormium ships the *mechanism* (the `WriteListener`
commit hook + `connectNotifications` + a pluggable transport), and the app owns the cache itself.

Key pieces in [`Sample.kt`](src/commonMain/kotlin/Sample.kt):

- `RedisNotificationTransport` — a `NotificationTransport` over Redis pub/sub in ~10 lines, using
  the multiplatform [`rethis`](https://github.com/vendelieu/re.this) client (so it runs on JVM and
  Native). Its wire format is core's `encodeTablePayload`/`decodeTablePayload`, so it interoperates
  with kormium's built-in Postgres `LISTEN/NOTIFY` transports on the same channel.
- `ProductCache` — a tiny read-through cache that clears itself when a `"products"` write arrives,
  whether local or from another instance.

> The cache here is deliberately minimal (not thread-safe, table-granular). A real one needs a
> concurrent store, per-key eviction and a **TTL**: notification delivery is best-effort, so a
> dropped signal must not pin a stale entry forever.

## Run

Run all commands from the repository root.

```sh
# 1. start Postgres + Redis
docker compose -f samples/cross-instance-cache/docker-compose.yml up -d --wait

# 2. run — JVM ...
./gradlew :samples:cross-instance-cache:runJvm
# ... or native
./gradlew :samples:cross-instance-cache:runDebugExecutableNative
```

Expected output: a cache `MISS` then `HIT` for `get(1)`, then — after instance A updates the row —
another `MISS` (the cross-instance notification cleared B's cache) returning the fresh value.

## Test

```sh
./gradlew :samples:cross-instance-cache:jvmTest
```

Verifies the cross-instance invalidation against throwaway Postgres + Redis via **Testcontainers**
(needs Docker; skipped if unavailable).

## Stop

```sh
docker compose -f samples/cross-instance-cache/docker-compose.yml down
```
