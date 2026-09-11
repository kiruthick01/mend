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

**Phase 7 — The AI layer.** An optional, deliberately narrow Anthropic
integration: language in, phrasing out. It can produce four shapes of
constraint and nothing else, the deterministic engine validates every one
before it reaches the screen, and the app is fully usable with no key and no
network.

Done so far:

- **Phase 0** — module boundaries, build, navigation shell, test harness.
- **Phase 1** — the Section 3 data model in Room, static blocks and habits, and
  the free time map.
- **Phase 2** — goals broken into lessons and per-lesson sub-tasks, with
  progress rollup and a pace view.
- **Phase 3** — priority scoring, greedy slot allocation, and a Today screen
  showing the full computed day.
- **Phase 4** — negotiated reshuffling, emergency interrupts, protected sleep
  with a configurable hard stop, and the delay-negotiation tier.
- **Phase 5** — app-wide adherence logging, rolling adherence and delay
  statistics, and the deadline-projection warning.
- **Phase 6** — the feedback prompt, bounded rule-based weight adjustment, and
  a Settings screen showing the weights and every change made to them.
- **Phase 7** — natural-language parsing into engine-validated constraints,
  generated explanation copy, and full offline degradation.

Not yet built: alarms, notifications and widgets.

The full phase plan lives in [PROJECT_PLAN.md](PROJECT_PLAN.md).

## How the schedule is decided

Every scheduling decision is ordinary deterministic code. Nothing is asked of a
model, and the same inputs always produce the same day.

1. **The grid.** Static blocks (college, mess, gym) and habits are laid down
   first. A block with a window it only partly needs — 45 minutes inside a
   two-hour gym window — occupies only that much and leaves the rest free.
   What remains is the *free time map*.
2. **The ranking.** Each pending sub-task is scored from three terms, each
   normalised to 0-1 and combined by the user's weights: how close its
   deadline is, how important its goal is, and how reliably that work actually
   gets done. Ties break by deadline, then importance, then id, so the same set
   of tasks always yields the same order.
3. **The packing.** Greedy: highest score gets first pick of the free time
   left. A task's flexibility decides how far it can be bent — split across
   gaps, shrunk under pressure, or placed whole or not at all. Anything that
   does not fit is reported as unscheduled, with the reason.

Every one of those decisions is written to a structured log, so "why is this
here?" and "why is this *not* here?" are both answerable after the fact.

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

## The AI layer is optional

Mend schedules with ordinary arithmetic. The AI does two jobs and no others:
it turns a sentence into one of four constraint shapes, and it rewrites
already-decided messages so they read better. It never ranks, places, or
refuses anything.

The `scheduling-engine` module has no reference to it whatsoever, so that
separation is a property of the build rather than a promise. With no API key
and no network, every core function works: the schedule is built, warnings
fire, proposals arrive, and messages come from templates that live in the
engine itself.

To switch it on, add your own Anthropic API key in Settings. It is stored on
the device only. **No key is, or should ever be, committed to this repository** —
a key inside an APK can be extracted by anyone who has the APK.

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
