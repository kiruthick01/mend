# Mend

A phone-first, offline-first personal scheduler that builds and continuously
re-negotiates a realistic second-by-second daily timetable — from brushing
teeth to exam-prep deadlines.

Mend is not a to-do list. It models the real shape of a day: fixed blocks
(college, mess, gym) carve out the grid, habits sit at fixed points on the
timeline, and goal work is packed into whatever free time is genuinely left.
When reality changes, the app proposes a new plan and asks; it never silently
rewrites your day.

## What it does

- **A real timetable.** Fixed blocks (college, mess, gym) and habits carve out
  the day, and goal work is packed into the time that is genuinely left.
- **Negotiated changes.** When reality shifts, Mend proposes a new plan and
  asks. It never rewrites your day silently.
- **Protected sleep** with a configurable hard stop, emergency interrupts for
  one-shot deadlines, and a deadline-projection warning.
- **Adherence tracking** that feeds bounded, inspectable weight adjustments.
- **Alarms, notifications, a home-screen widget and a lock-screen note**, so the
  schedule reaches you without opening the app.
- **Connected apps** (see below): practice in Duolingo or AnkiDroid ticks off
  the matching goal and shapes the schedule.
- **An optional AI layer**: language in, phrasing out. Fully usable without it.

### Connected apps

Link a goal to Duolingo or an AnkiDroid deck in Settings. Everything here is
optional and degrades to nothing when the apps are absent.

| Source | How it is read | Notes |
| --- | --- | --- |
| AnkiDroid | Its public ContentProvider API | Needs AnkiDroid installed and its API permission granted. Reading a deck selects it inside AnkiDroid briefly; the previous selection is restored. |
| Duolingo (username) | The public profile endpoint the website uses | **Unofficial.** Not a documented API, it can change without notice, and it may not suit Duolingo's terms. A failed read shows as "unknown", never as a missed day. |
| Either app (no username or deck) | Android usage history | Counts time in the app. Needs Usage access, granted by hand in system Settings. |

What the readings do:

- **Tick off work.** When practice is detected, the goal's next pending item is
  completed through the normal path, against the slot it was scheduled in, so
  lateness is real. One item per link per day.
- **Size the work.** Anki cards due set the review task's length (8 seconds a
  card). Without Anki, the median of your real minutes does.
- **Guard streaks.** From 20:00, a run of practice about to break raises the
  goal's priority and sends one notification, never inside protected sleep.
- **Show it.** Today, the widget and goal detail show streak, cards due and
  minutes. Mend can suggest a work window from the hours you actually use an app.

Anki and usage readings stay on the device. The one thing sent out is the
Duolingo username, to Duolingo (and, if you add a key, the text you type to the
AI layer goes to Anthropic).

## Current phase

Phases 0 to 8 are done: engine, data model, goals, scheduling, reshuffling,
adherence, weight tuning, the AI layer, and alarms and widgets. Connected apps
are an addition on top, tested on a real device. Phase 9 (hardening and a
first release) is next.

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

Pre-release and under active development. It runs on a real phone and is used
daily by its author, but it has not been hardened for other people yet: expect
rough edges, no migration guarantees between versions, and no Play Store build.
