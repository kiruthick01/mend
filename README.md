# Mend

A phone-first, offline-first personal scheduler that builds and continuously
re-negotiates a realistic second-by-second daily timetable — from brushing
teeth to exam-prep deadlines.

Mend is not a to-do list. It models the real shape of a day: fixed blocks
(college, mess, gym) carve out the grid, habits sit at fixed points on the
timeline, and goal work is packed into whatever free time is genuinely left.
When reality changes, the app proposes a new plan and asks; it never silently
rewrites your day.

## Current phase

**Phase 0 — Project scaffolding.** The module boundaries, build, navigation
shell and test harness exist. No scheduling logic has been written yet.

The full phase plan lives in [PROJECT_PLAN.md](PROJECT_PLAN.md).

## Architecture

Two modules, with a hard boundary between them:

| Module | What it is | Depends on Android? |
| --- | --- | --- |
| `scheduling-engine` | Pure Kotlin. All priority scoring, slot allocation and reshuffling. | No — never |
| `app` | Jetpack Compose UI, Room storage, Android platform integration. | Yes |

The engine is deliberately isolated so the trust-critical logic can be unit
tested in milliseconds on the JVM, with no emulator and no device. Scheduling
decisions are made by ordinary deterministic code — they are reproducible from
their inputs, and they work with no network connection.

## Minimum SDK

`minSdk = 26` (Android 8.0 Oreo), `compileSdk`/`targetSdk = 35`.

API 26 is a deliberate floor rather than a default:

- **Notification channels** were introduced in API 26. Mend needs per-criticality
  channels so an emergency interrupt is not muted alongside a routine nudge.
  Supporting anything older would mean carrying a second, weaker notification
  path for the exact feature the app's value depends on.
- **`java.time`** is available natively from API 26. The engine is date/time
  arithmetic almost end to end; using the platform types directly avoids
  desugaring and keeps the pure-Kotlin module free of Android-shaped
  workarounds.
- **Background execution limits** landed in API 26 too. Building against that
  model from the start is better than retrofitting it later.
- API 26 still covers the overwhelming majority of active devices, so the cost
  of this floor is negligible.

## Building

Requires JDK 17 and the Android SDK (platform 35). Point Gradle at your SDK by
creating a `local.properties` file in the repository root:

```
sdk.dir=/path/to/android-sdk
```

Then:

```
./gradlew assembleDebug          # build the app
./gradlew test                   # run all unit tests
./gradlew :scheduling-engine:test  # engine tests only, no Android SDK needed
```

## Status

Pre-release and under active development. Not yet usable.
