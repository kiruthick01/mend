# Mend — Build Log

## Summary

**Phases 0 through 8 are complete, verified and committed.**

| Phase | Status | Commit |
| --- | --- | --- |
| 0 — Project scaffolding | Complete | `e937ae5` |
| 1 — Data model & static layer | Complete | `a4dc736` |
| 2 — Goals, lessons, sub-tasks, progress | Complete | `ffc3bdd` |
| 3 — Priority scoring & slot allocation | Complete | `8620a66` |
| 4 — Reshuffling, interrupts, protected sleep | Complete | `f0a10c2` |
| 5 — Adherence logging & guilt-trip trigger | Complete | `faf54d3` |
| 6 — Adaptive weight tuning | Complete | `c15d02b` |
| 7 — AI layer (NLP parsing, message generation) | Complete | `9f968e9` |
| 8 — Notifications, alarms, widgets, lock screen | Complete | `09a1b51` |
| 9+ | Not started | — |

`./gradlew build` from an empty build directory: **577 tests, 0 failures**,
lint clean, debug and release APKs produced. Every phase from 4 onward has been
exercised on an Android emulator, and Phase 8 was verified on a real phone — a
vivo iQOO Z7 5G on Android 15 — with the screen off and the device held in deep
Doze. No commit carries an AI co-author, collaborator or generated-by trailer,
and no tracked file mentions one — audited after every commit.

### What to review first

-1. **Two settings on your phone that Mend cannot set for you.** Phase 8
   measured this rather than guessing: with Android's battery exemption removed,
   a normal-tier alarm **never arrived** — your phone froze the app and deferred
   the broadcast indefinitely. With the exemption granted, the same alarm fired
   nine seconds after its target. Settings → "Will alarms actually fire?" will
   tell you what is missing; the vivo autostart setting underneath it is one
   Android has no API for at all, so it has to be done by hand. **Until both are
   on, treat every non-critical reminder as optional.**

0. **Question 9 — Decline does not yet mean "keep the old plan".** New in
   Phase 4 and the one place the implementation is thinner than it looks.
   Schedules are derived rather than stored, so Decline currently means
   "acknowledged, stop asking" rather than "revert". Fixing it needs schedule
   pinning, which is a design call I did not want to make for you.
1. **The product decisions I had to guess at.** Questions 3, 4
   and 5 under "Needs your input" below: where a partial-window block sits
   inside its window, what "pace" actually means, and how deadline urgency
   should curve. Each is load-bearing, each shapes behaviour you will feel
   daily, and each is cheap to change now and awkward later. Start here.
2. **`scheduling-engine/src/main/kotlin/com/mend/engine/allocation/Allocation.kt`**
   — the greedy allocator, the piece the whole app's trustworthiness rests on.
   Read the `place()` branches and ask whether each refusal is one you would
   want.
3. **`.../priority/Priority.kt`** — the scoring function and, in particular,
   the tie-breaking order. If two tasks are equal, this decides which one you
   actually do.
4. **Phase 8's decision A — the "lock screen widget".** Android removed lock
   screen widgets in API 21, so there is nothing to build. I implemented the
   lock screen surface as a persistent, silent, publicly-visible notification
   carrying the same content and the same Done button. That is what lock screen
   widgets became, but it is an interpretation of a deliverable rather than the
   deliverable, so it is the one to disagree with if you meant something else.

### Environment note

This machine had **no Java, no Gradle, no Android SDK and no Android Studio**.
I installed a self-contained toolchain under `<tools dir>` (removable
by deleting that one folder), set `JAVA_HOME` at user scope, and accepted the
Android SDK licences. Details and rationale in "Session setup" below. **This is
question 1 under "Needs your input".**

---

## Needs your input

Eighteen open questions, most-consequential first. Most are places where the
plan did not answer something I needed, so I picked a defensible default,
implemented it behind a named constant or a field, and wrote it down here
rather than letting it pass silently.

Questions 9-11 are new in Phase 4, 12-13 in Phase 5, 14-15 in Phase 7 and
16-17 in Phase 8; each is recorded in that phase's section below. **Question 18
is a correction to Phase 7 found on 14 September and is the most important thing
in this list after question 9.**

### 18. The prose input parses your words and then discards them — **RESOLVED, option 2**

Found while answering a question about whether the API key was worth funding,
which is a humbling way to find it.

`ProposeDifferent` → type prose → the parser returns a `BusyPeriod`,
`AvoidWindow` or `DeadlineChange` → the engine validates it → you are shown
"Treat 2026-09-20 to 2026-09-25 as mostly taken up by exams" → you press
Accept → **nothing is written**. `acceptInterpretedConstraint()` records a
`ScheduleInvalidatingEvent.ManualEdit`, whose entire payload is `occurredAt`
and a `summary` string. The replan it triggers runs against unchanged inputs.

Confirmed structurally: `BusyPeriod`, `AvoidWindow` and `DeadlineChange` appear
in exactly one place in the app module — `AnthropicAiBridge`, where the API
response is parsed into them — and are consumed nowhere.

This is worse than a missing feature, because it **looks** like it worked. The
offline path, by contrast, is correct: it saves the words verbatim and directs
you to Tasks, where making the change by hand genuinely reschedules.

My Phase 7 checklist said 7.7 PASS. The flow does run; the effect does not
happen. The row was accurate and misleading at once, which is the kind of
checklist entry this log exists to avoid, so it has been corrected in place
rather than quietly amended.

**Resolved on 14 September: you chose option 2.** The "Use this" button is
gone. The dialog now has exactly one commit action, `Save what I wrote`, on both
the understood and the no-AI paths — because saving the words is the only thing
either path can honestly do.

The interpretation itself stays, and earns its place better than it did: it
shows what Mend understood, shows the engine's validation warnings, and then
**names the change to go and make**, in the vocabulary of the screen that makes
it —

- a busy period → "add a fixed block called *exams* in Tasks, covering
  2026-09-20 to 2026-09-25."
- an avoid window → "add a fixed block called *gym* in Tasks on Tuesday,
  Thursday, from 18:00 to 19:30."
- a deadline change → "open *JLPT N4* in Tasks and set its deadline to
  2026-12-01."

So the feature is now a translator rather than a pretend actuator. It turns a
sentence into a specific instruction you can carry out in about ten seconds,
and it no longer claims to have done anything it did not do.

`acceptInterpretedConstraint()` was deleted rather than left unused, so nothing
can call it back into existence by accident.

**Option 1 remains available** if you later want the constraints written
directly — the parser, the four constraint shapes and the engine's validation
are all still there and tested, and only the write-through is missing. It is a
Phase 9-or-later decision, not a regression.

### 1. Was installing a toolchain on this machine acceptable?

I could not run a build or a test without one, and the per-phase process
requires both. What I did:

- Extracted **Temurin JDK 17**, **Gradle 8.13** and the **Android SDK**
  (platform 35, build-tools 35.0.0, platform-tools) into
  `<tools dir>`. No installer, no admin rights, nothing written to
  `Program Files` or the registry. Deleting that folder removes all of it.
- Set the user-scope `JAVA_HOME` environment variable.
- Accepted the Android SDK licences via `sdkmanager --licenses`. **This is a
  licence acceptance made on your behalf** — unavoidable for Android
  development, but yours to make, so I am flagging it rather than burying it.

If you would rather have Android Studio manage all of this, say so and I will
remove the folder and the environment variable.

### 2. Package name — `com.mend.app`

Nothing in the plan specifies one. Application ID is `com.mend.app`, engine
package `com.mend.engine`. This is trivial to change now and genuinely
expensive after a public release, since the application ID is permanent on the
Play Store. If you have a domain you would rather use, now is the moment.

### 3. Where does a partial-window block sit inside its window?

**The most consequential guess of the night.** The plan requires the case —
"only 45 min needed within a 2-hour gym window" — but never says *where* in
the window those 45 minutes land.

I anchor to the **start** of the window by default and leave the remainder
free, with a per-block `WindowAnchor` (`EARLIEST` / `LATEST`) exposed in the
editor. So a 06:00-08:00 gym window needing 45 minutes occupies 06:00-06:45,
and 06:45-08:00 stays available for goal work.

The alternatives I did not take:

- **Reserve the whole window.** Safe, but throws away 75 usable minutes a day.
- **Let the allocator choose.** Would pack better, but makes the answer to
  "when am I going to the gym?" depend on unrelated coursework, which seemed
  contrary to the spirit of static blocks being constraints rather than
  competitors.

Is "as early as possible in the window" the right default for you, or would
you rather it defaulted to latest, or asked per block?

### 4. What should "pace" actually mean?

Section 3 has `pace_comfortable` with no units, and Phase 2 asks for
"comfortable pace vs. actual velocity" without defining velocity.

I made both sides **minutes of that kind of work per day**, so they are
directly comparable:

- *Comfortable pace* — averaged from the `paceComfortable` values you set on
  sub-tasks of that type. The editor labels the field "Pace (min/day)".
- *Actual velocity* — estimated minutes of completed work of that type,
  divided by the days from your first recorded completion to today.

So: "you said 60m/day of reading; you are averaging 22m/day."

The obvious alternative is **items per week** rather than minutes per day,
which is easier to state but ignores that sub-tasks vary hugely in size. Is
minutes-per-day the comparison you actually want to see?

### 5. How should deadline urgency curve?

`deadlineProximity` is **linear over a 30-day horizon**: maximum urgency at or
past the deadline, zero at 30+ days out, straight line between.

I chose linear deliberately over an exponential curve, which models "panic
sets in near the end" more realistically but is much harder to reason about
when a task appears somewhere you did not expect. Given the plan's insistence
on explainable rules, linear won.

Two things to confirm: is **30 days** the right horizon for your deadlines
(exam prep often runs longer), and do you want the curve to stay linear or to
bite harder in the final week?

### 6. Should a shrinkable task be cut below half?

`MINIMUM_SHRINK_FRACTION` is 0.5: a 60-minute shrinkable task will take a
40-minute slot, but not a 25-minute one — below half it is reported as
unscheduled rather than done badly. Arbitrary but explicit. Adjust if half is
too strict or too generous.

### 7. How long is a lesson with no sub-tasks?

A bare lesson still has to be given a duration to be placed, and it carries no
estimate of its own. I used **60 minutes** as a placeholder. This is a genuine
gap rather than a considered answer — the sensible fix is probably to ask for
a duration when creating a bare lesson, but that was not in the Phase 2
deliverables so I did not add the field.

### 8. Minor: `MOVABLE` and `FIXED` behave identically

Both must be placed whole in a single interval; only the logged reason
differs. If `MOVABLE` should mean something distinct — preferring a later
slot, or being displaceable by higher-priority work — that is a Phase 4
reshuffle concern, but tell me if you meant it to differ now.

---

## What I did NOT get to, and why

- **Phase 8 onward.** Not started.
- **Any run on real hardware.** No emulator image and no attached device. This
  is the most important untested surface — see "What to review first".
- **Instrumented Room tests.** Persistence is covered by mapper round-trip
  tests, which verify the translation but never touch SQLite. Real DAO
  behaviour, foreign-key cascades and migrations are unverified. This needs a
  device or emulator, so it is noted as a Phase 9 hardening item.
- **Date navigation.** Every screen shows today only. No deliverable in Phases
  0-3 asks for more.
- **Schedule persistence.** The day is recomputed from scratch on every change.
  Correct for Phase 3, but Phase 4 needs a *proposed* schedule to sit beside
  the accepted one so a change can be accepted or declined — expect to add
  storage for it there.

---

## Session setup (before Phase 0)

This machine had **no Java, no Gradle, no Android SDK, and no Android Studio**.
A full search of `C:\Program Files`, `C:\Program Files (x86)`, `%LOCALAPPDATA%`
and common JDK locations found no `java.exe` at all. Without a toolchain the
per-phase rule "run the full build and full test suite" could not be satisfied,
so the toolchain was installed first.

Everything was installed **into the user profile, no admin rights, no system
modification**, so it is removable by deleting one folder:

| Tool | Version | Location |
| --- | --- | --- |
| Temurin JDK | 17.0.20.1+1 | `<tools dir>\jdk-17.0.20.1+1` |
| Gradle | 8.13 | `<tools dir>\gradle-8.13` |
| Android SDK | platform 35, build-tools 35.0.0, platform-tools | `<tools dir>\android-sdk` |

Two changes outside the repo:
- `JAVA_HOME` was set at **user** scope to the JDK path.
- Android SDK licences were accepted via `sdkmanager --licenses`.

See "Needs your input" item 1.

To build from a fresh terminal:

```
JAVA_HOME=<tools dir>\jdk-17.0.20.1+1
./gradlew clean test assembleDebug
```

`local.properties` already points at the SDK and is gitignored.

---

## Phase 0 — Project Scaffolding

**Status: COMPLETE** (build passes, tests pass, committed)

### Deliverables checklist

| # | Deliverable | Result | Evidence |
| --- | --- | --- | --- |
| 0.1 | Kotlin + Compose project | **PASS** | Kotlin 2.0.21, Compose BOM 2024.12.01, Material3; `./gradlew assembleDebug` produces a debug APK |
| 0.2 | Minimum SDK chosen deliberately, documented why | **PASS** | `minSdk = 26`; four-point rationale in README "Minimum SDK" |
| 0.3 | Room configured | **PASS** | Room 2.6.1 + KSP; `MendDatabase` v1 with one placeholder entity/DAO; `kspDebugKotlin` runs clean |
| 0.4 | Basic navigation shell: Today / Tasks / Settings, empty | **PASS** | `MendApp.kt` NavHost + bottom bar, 3 routes, all rendering `PlaceholderScreen` |
| 0.5 | `scheduling-engine` as its own Gradle module | **PASS** | `include(":scheduling-engine")`, own `build.gradle.kts` using the `kotlin-jvm` plugin only |
| 0.6 | Engine has zero Android/UI dependencies | **PASS** | `:scheduling-engine:dependencies --configuration testRuntimeClasspath` grepped for `android`/`compose` → no matches |
| 0.7 | CI-friendly local unit tests from terminal | **PASS** | `./gradlew :scheduling-engine:test` runs on the JVM with no emulator and no Android SDK needed |
| 0.8 | Git repo initialized | **PASS** | `git init -b main` |
| 0.9 | Sensible `.gitignore` | **PASS** | ignores `.gradle/`, `build/`, `local.properties`, APK/AAB output, IDE files |
| 0.10 | README stub: purpose + current phase | **PASS** | README states purpose, architecture table, current phase, build instructions |
| 0.11 | Confirm no AI co-author/collaborator trailers | **PASS** | See "Attribution verification" below |

### Constraints checklist

| Constraint | Result | Notes |
| --- | --- | --- |
| No feature logic yet | **PASS** | `Engine.kt` contains only a version constant; no scheduling code exists |
| Module boundaries correct now | **PASS** | Engine is a separate `kotlin-jvm` module; `app` depends on it one-way and it depends on nothing |

### Attribution verification

Checked **before** the first commit:
- `git config commit.template` → unset (local and global)
- `.git/hooks/` → no active hooks, samples only
- `~/.claude/settings.json` → contains no co-author setting
- Set `includeCoAuthoredBy: false` in project-local `.claude/settings.json`
- Added `.claude/` to `.gitignore` so no AI-tooling artifact is committed either

Post-commit `git log --format=%B` was grepped for `Co-Authored`, `Claude`,
`Anthropic`, `AI`, `Generated` → no matches. Result recorded below.

### Test results

| Suite | Tests | Passed | Failed |
| --- | --- | --- | --- |
| `:scheduling-engine:test` | 1 | 1 | 0 |
| `:app` unit tests | 0 | – | – |
| **Total** | **1** | **1** | **0** |

`./gradlew assembleDebug` → BUILD SUCCESSFUL. `./gradlew test` → BUILD SUCCESSFUL.

There is only one engine test because Phase 0 forbids feature logic; the test
exists to prove the harness runs, not to test behaviour.

### Decisions I had to make (not specified in PROJECT_PLAN.md)

1. **`minSdk = 26`.** The plan said "chosen deliberately (document why)" but not
   which. I chose 26 because notification channels (API 26) are load-bearing for
   Phase 8's per-criticality channels, `java.time` is native from 26 which keeps
   the pure-Kotlin engine free of desugaring workarounds, and background
   execution limits arrived in 26 so building against that model from day one is
   cheaper than retrofitting. Full reasoning is in the README.
2. **Package/application ID `com.mend.app`**, engine package `com.mend.engine`.
   Not specified anywhere. Trivial to rename now, expensive after release —
   **see "Needs your input" item 2** if you want a real domain.
3. **Version pinning.** AGP 8.7.3 / Kotlin 2.0.21 / Compose BOM 2024.12.01 /
   Room 2.6.1, via a Gradle version catalog (`gradle/libs.versions.toml`).
4. **Placeholder Room entity.** The plan wanted "an empty placeholder database",
   but Room refuses to compile a `@Database` with zero entities. I added one
   trivial `placeholder` table purely so the KSP toolchain is proven wired. It
   is deleted in Phase 1 when the real entities land.
5. **Repository filtering removed from `settings.gradle.kts`.** The usual
   `includeGroupByRegex` filters were dropped in favour of plain `google()` /
   `mavenCentral()`; no behavioural difference, just fewer moving parts.

### Not done in this phase

Nothing from the Phase 0 deliverable list was skipped.

---

## Phase 1 — Data Model & Static Layer

**Status: COMPLETE** (build passes, 70 tests pass, committed)

### Deliverables checklist

| # | Deliverable | Result | Evidence |
| --- | --- | --- | --- |
| 1.1 | Room entities/DAOs for `Task` | **PASS** | `TaskEntity` (`tasks`, 15 cols) + `TaskDao` |
| 1.2 | Room entities/DAOs for `Goal` | **PASS** | `GoalEntity` (`goals`, 10 cols) + `GoalDao` |
| 1.3 | Room entities/DAOs for `Lesson` | **PASS** | `LessonEntity` (`lessons`, 5 cols) + `LessonDao`, cascade delete from goal |
| 1.4 | Room entities/DAOs for `SubTask` | **PASS** | `SubTaskEntity` (`sub_tasks`, 8 cols) + `SubTaskDao`, cascade delete from lesson |
| 1.5 | Room entities/DAOs for `AdherenceLogEntry` | **PASS** | `AdherenceLogEntity` (`adherence_log`, 8 cols) + `AdherenceLogDao` exposing **insert and read only** |
| 1.6 | Entities match Section 3 | **PASS** | All five tables exported to `app/schemas/.../1.json`; every Section 3 field represented |
| 1.7 | UI to create/edit/delete static blocks | **PASS** | Tasks screen, "Fixed blocks" tab; `StaticBlockEditorDialog` |
| 1.8 | UI to create/edit/delete habits | **PASS** | Tasks screen, "Habits" tab; `HabitEditorDialog` |
| 1.9 | Window-based blocks (open/close + required duration within window) | **PASS** | "Only part of the window" toggle → minutes-needed field + anchor picker |
| 1.10 | Pure `computeFreeTimeMap(date, staticBlocks, habits)` in scheduling-engine | **PASS** | `com.mend.engine.freetime.computeFreeTimeMap`; no Android, no clock, no I/O |
| 1.11 | Unit tests: overlaps | **PASS** | `overlapping static blocks are merged rather than double-counted`, `a static block fully inside another is absorbed` |
| 1.12 | Unit tests: back-to-back blocks | **PASS** | `back-to-back static blocks leave no sliver between them`, plus `normalize merges back-to-back intervals into one span` |
| 1.13 | Unit tests: partial-window requirements | **PASS** | `a partial window occupies only the required duration and frees the rest` (the plan's own 45-of-120 gym example), plus LATEST-anchor and equal-to-window cases |
| 1.14 | Today screen renders the static skeleton | **PASS** | `TodayScreen` renders occupied placements and free gaps as one ascending timeline, with committed/free totals |

### Constraints checklist

| Constraint | Result | How it is enforced |
| --- | --- | --- |
| Habits have no priority score | **PASS** | `Habit` does not implement `Schedulable` and has no priority field to read. Type-level, not convention. |
| Habits have no deadline | **PASS** | Same — no deadline field exists on `Habit` |
| Habits are never reshuffled | **PASS** | `computeFreeTimeMap` places habits at their declared `timeOfDay` and never moves them; the allocator (Phase 3) only accepts `Schedulable` |
| Enforced at the type level, not by convention | **PASS** | Sealed `ScheduleEntry` → `Schedulable` split. A habit cannot be passed to anything that ranks work; it will not compile. |
| Static blocks excluded from bin-packing | **PASS** | `StaticBlock` is a `ScheduleEntry` but not a `Schedulable`. It only ever produces occupied intervals. |
| Static blocks define the grid others fit into | **PASS** | `FreeTimeMap.free` is the day minus static blocks and habits; that list is the sole input to Phase 3 allocation |

### Test results

| Suite | Tests | Passed | Failed |
| --- | --- | --- | --- |
| `:scheduling-engine:test` | 45 | 45 | 0 |
| `:app:testDebugUnitTest` | 25 | 25 | 0 |
| **Total** | **70** | **70** | **0** |

Engine breakdown: `TimeIntervalTest` 11, `IntervalsTest` 10, `FreeTimeMapTest` 23, `EngineTest` 1.
App breakdown: `TaskMappersTest` 12, `TimeParsingTest` 13.

`./gradlew assembleDebug` → BUILD SUCCESSFUL. `./gradlew test --rerun-tasks` → BUILD SUCCESSFUL.

### Decisions I had to make (not specified in PROJECT_PLAN.md)

1. **Partial-window placement — the most consequential guess of this phase.**
   The plan requires a test for "only 45 min needed within a 2-hour gym
   window" but never says *where* in the window those 45 minutes go. I
   anchor them to the **start** of the window by default and leave the rest
   free, with a `WindowAnchor` field (`EARLIEST` / `LATEST`) exposed in the
   editor so it can be flipped per block. Rationale: it is deterministic,
   explainable, and it does not throw away the other 75 minutes.
   **This is question 3 under "Needs your input" — it is a real product
   choice and I do not want to have made it for you silently.**
2. **Day bounds.** `computeFreeTimeMap` treats the day as 00:00 to 24:00
   unless told otherwise, and takes optional `dayStart`/`dayEnd` parameters.
   Section 3 never defines where a day begins. Sleep (`PROTECTED_RECOVERY`)
   is Phase 4, so nothing yet trims the night — the map currently reports the
   small hours as free time, which is correct for Phase 1 and will be wrong
   the moment sleep exists. Flagged so it is not forgotten.
3. **Recurrence by weekday.** The signature takes a `date`, which only means
   something if entries vary by day, but Section 3 has no recurrence field. I
   added `daysOfWeek: Set<DayOfWeek>` to both `StaticBlock` and `Habit`
   (defaulting to all seven), so "college on weekdays" is expressible. Without
   it the `date` parameter would be decorative.
4. **Habits have a duration.** Section 3 calls habits "fixed point-in-time
   entries", which has no length, but brushing takes three minutes and that
   time is genuinely unavailable. Habits carry a `duration` defaulting to 5
   minutes. They still have no priority and no deadline.
5. **Flat table, sealed domain.** SQLite has no sum types, so `tasks` is one
   table with a `type` discriminator and nullable per-type columns. All the
   nullable handling is confined to `TaskMappers.kt`, and everything above
   that boundary sees the sealed `ScheduleEntry` types. `TaskMappersTest`
   guards the round trip in both directions.
6. **Half-open intervals.** `TimeInterval` is `[start, end)`. This is why
   09:00-10:00 and 10:00-11:00 do not overlap and leave no zero-width sliver
   between them. Every containment and subtraction rule depends on it.
7. **Adherence log has no foreign key to `tasks`.** A log entry must outlive
   the task it describes, or deleting a task would erase the record of having
   missed it. Deliberate, and commented in the entity.
8. **No DI framework.** Two modules and one repository do not justify Hilt.
   Wiring is a single factory in `MendViewModelFactory.kt`.

### Not done in this phase

- The Today screen shows the day for `LocalDate.now()` only; there is no date
  picker. Phase 1 asks for "the static skeleton of the day", which this is,
  and no deliverable mentions navigating between days.
- No instrumented (on-device) tests. Phase 1 asks for unit tests on
  `computeFreeTimeMap`, which are done and thorough. Room DAO behaviour is
  exercised only through the mapper round-trip tests, not against a real
  SQLite instance — that wants an instrumented test and a device, which this
  machine does not have. Noted as a gap for Phase 9's hardening pass.

---

## Phase 2 — Goals, Lessons, Sub-tasks & Progress Tracking

**Status: COMPLETE** (build passes, 109 tests pass, committed)

### Deliverables checklist

| # | Deliverable | Result | Evidence |
| --- | --- | --- | --- |
| 2.1 | UI to create a Goal: title, deadline, manual importance, total lesson count | **PASS** | `GoalEditorDialog` — all four fields, plus a consequence-type picker (`SOFT_GOAL` / `IRL_BINDING`) since Section 3 has the field and Phase 5 needs it set |
| 2.2 | Each lesson defines its own sub-tasks independently | **PASS** | `SubTaskEditorDialog` is opened per lesson; nothing copies structure between lessons |
| 2.3 | Zero, one, or several sub-tasks per lesson | **PASS** | Tested by `lessons in one goal may have wholly different sub-task structures` (0/1/3 sub-tasks in one goal) |
| 2.4 | Sub-tasks freely named | **PASS** | `SubTask.type` and `.title` are free text; previously-used types are offered as chips but never enforced |
| 2.5 | No requirement that lessons share a structure | **PASS** | See 2.3 — no template code exists to remove |
| 2.6 | A lesson with zero sub-tasks is completed by marking the lesson done | **PASS** | `Lesson.derivedStatus()`; the lesson checkbox is enabled only when `hasNoSubTasks` |
| 2.7 | Mark sub-tasks done | **PASS** | Checkbox per sub-task in `GoalDetailScreen`, stamping `completedAt` |
| 2.8 | Mark whole lessons done when there are no sub-tasks | **PASS** | Lesson checkbox, enabled only for bare lessons |
| 2.9 | See lesson completion | **PASS** | Per-lesson subtitle: done/total, percent, derived status |
| 2.10 | Goal-level rollup `lessons_completed / total_lessons` | **PASS** | `computeGoalProgress`; shown on the goal row and the detail header |
| 2.11 | Simple pace view: comfortable pace vs. actual velocity | **PASS** | `computePace` produces `PaceSnapshot`; rendered in the goal detail "Pace" section |
| 2.12 | Pace scoped per sub-task type where sub-tasks exist | **PASS** | `computePace` groups by `SubTask.type`; bare lessons contribute nothing |
| 2.13 | No automatic scheduling of goal tasks into free time | **PASS** | Nothing calls `computeFreeTimeMap` with goal work; no allocator exists yet |

### Constraints checklist

| Constraint | Result | Notes |
| --- | --- | --- |
| Do not build a cross-lesson template system | **PASS** | There is no template type, no "apply to all lessons" action, and no copy path between lessons. Each `SubTaskEditorDialog` writes one sub-task to one lesson. |
| Sub-task structure entered freely each time | **PASS** | Same |
| Sub-task types simple and user-defined | **PASS** | `type` is a `String`, not an enum. Previously-used values appear as tappable suggestions, which is a convenience, not a constraint — any new string is accepted. |
| Not hardcoded categories | **PASS** | Nothing in the engine or the app matches on a fixed set of type names |

### Test results

| Suite | Tests | Passed | Failed |
| --- | --- | --- | --- |
| `:scheduling-engine:test` | 71 | 71 | 0 |
| `:app:testDebugUnitTest` | 38 | 38 | 0 |
| **Total** | **109** | **109** | **0** |

New this phase: `ProgressTest` 26 (engine), `GoalMappersTest` 13 (app).

One test failed on first run — `lessons in one goal need not share a sub-task
structure` expected 3 completed sub-tasks where the fixture only has 2. That
was my arithmetic error in the test, not a defect in the code; the expectation
was corrected and the assertion now documents which lesson contributes what.

### Decisions I had to make (not specified in PROJECT_PLAN.md)

1. **`completedAt` added to `SubTask` and `Lesson`.** Section 3 has no
   completion timestamp anywhere except `AdherenceLogEntry`, but "actual
   velocity" cannot be measured without knowing *when* work landed, and Phase 2
   explicitly asks for a pace view. Both types gained a nullable
   `completedAt`, stamped on completion and cleared on un-completion.
2. **What "pace" means concretely.** This is the second real guess of the
   night. Section 3 gives `pace_comfortable` with no units and no definition of
   "velocity". I settled on **minutes of that kind of work per day** for both
   sides, so they are directly comparable:
   - *Comfortable pace* = the average of the declared `paceComfortable` values
     across sub-tasks of that type.
   - *Actual velocity* = estimated minutes of completed work of that type,
     divided by the days from the first recorded completion to today.

   Work marked done with no recorded completion date is excluded from the
   velocity numerator so it cannot invent a burst of activity on day one.
   **This is question 4 under "Needs your input".**
3. **Lesson completion is derived, never trusted from the row.** A lesson with
   sub-tasks is complete when they all are, regardless of what its own `status`
   column says. This means ticking the last sub-task cannot leave a stale
   lesson behind, and a lesson cannot claim completion its sub-tasks
   contradict. Both directions are tested.
4. **`lessons_completed` is not a stored column.** Section 3 lists it as a
   rollup. It is recomputed from the lesson rows on every read rather than
   cached, because a cached counter and its rows drift apart the first time a
   write is interrupted.
5. **`totalLessons` is raised to the actual lesson count when it is exceeded.**
   Declaring 10 lessons and entering 12 should not report 120% complete.
6. **Goals live on a third tab of the existing Tasks screen.** Phase 0 fixed
   navigation at Today / Tasks / Settings, and Phase 2 adds no new destination,
   so goals went into Tasks alongside blocks and habits, with a `goal/{goalId}`
   detail route pushed on top.
7. **Consequence type is settable at goal creation.** Section 3 has the field
   and Phase 5's guilt-trip depends on it; leaving it unsettable would make it
   unreachable data.

### Not done in this phase

- No sub-task reordering by drag. `orderIndex` exists and is respected on read,
  but sub-tasks and lessons currently take the order they were created in.
  Nothing in the Phase 2 deliverables asks for reordering.
- No `IN_PROGRESS` state in the UI. The model and the rollup both support it,
  but the checkboxes toggle between `NOT_STARTED` and `DONE` only. The plan
  asks to "mark sub-tasks done", which this does.
- Room DAO behaviour is still only covered through mapper tests, not against a
  real SQLite instance. Same gap noted in Phase 1; wants an instrumented test.

---

## Phase 3 — Deterministic Priority & Scheduling Engine

**Status: COMPLETE** (clean build from scratch passes, 172 tests pass, committed)

### Deliverables checklist

| # | Deliverable | Result | Evidence |
| --- | --- | --- | --- |
| 3.1 | `computePriorityScore(task, weights): Double` | **PASS** | `com.mend.engine.priority.computePriorityScore`; pure, takes `today` as a parameter rather than reading a clock |
| 3.2 | Combines deadline proximity | **PASS** | `deadlineProximity` — 1.0 at or past the deadline, falling linearly to 0.0 at a 30-day horizon |
| 3.3 | Combines manual importance | **PASS** | `normalisedImportance` maps the 1-5 scale onto 0.0-1.0, clamping anything outside it |
| 3.4 | Combines adherence trend, defaulting neutral | **PASS** | `adherencePressure`; `GoalTask.NEUTRAL_ADHERENCE = 0.5` until Phase 6 supplies real history |
| 3.5 | Greedy slot allocation, highest priority picks first | **PASS** | `allocate()` walks the ranked list top-down; no lookahead, no backtracking |
| 3.6 | `SPLITTABLE` may span multiple intervals | **PASS** | `a SPLITTABLE task spans multiple free intervals` — 180m across a 2h and a 4h gap |
| 3.7 | `FIXED` must fit one interval or fail and be reported unscheduled | **PASS** | `a FIXED task that cannot fit one interval is not scheduled at all` — lands in `unscheduled` with a reason |
| 3.8 | `SHRINKABLE` may reduce duration under pressure | **PASS** | Shrinks to the largest available interval, with a floor at 50% of the request; both the shrink and the refusal below the floor are tested |
| 3.9 | Fills the free time map with ranked sub-tasks | **PASS** | `allocate(freeTimeMap, tasks, weights, today)` |
| 3.10 | Clear logged reasoning for every placement decision | **PASS** | `SchedulingLog` — structured entries of kind INPUT / RANKED / PLACED / SPLIT / SHRUNK / PARTIAL / SKIPPED / SUMMARY, each with a message. `forTask(id)` retrieves one task's whole story |
| 3.11 | Able to answer "why is this here" from logs alone | **PASS** | The RANKED entry carries the full score breakdown (`score 0.675 = deadline 0.50 x 0.50 + ...`), the request size and the flexibility; the PLACED entry carries where and why. Refusals are logged too, so "why is this *not* here" is answerable as well. Dumped to logcat under tag `MendScheduler` on every recompute. |
| 3.12 | Today screen shows the full schedule: static blocks + habits + scheduled sub-tasks | **PASS** | `TodayScreen` renders `DaySchedule.timeline` interleaved with the remaining free gaps, plus a "Did not fit today" section |
| 3.13 | Unit test: overfull day | **PASS** | `an overfull day places what fits and reports the rest as unscheduled`, `on an overfull day the lowest priority work is what gets dropped` |
| 3.14 | Unit test: tie-breaking between equal-priority tasks | **PASS** | Three tests: tie broken by deadline, by importance, and finally by id, including a check that reversing the input order yields an identical schedule |
| 3.15 | Unit test: splittable task spanning multiple gaps | **PASS** | `a SPLITTABLE task spans multiple free intervals`, plus not splitting needlessly and filling the earlier gap first |

### Constraints checklist

| Constraint | Result | Notes |
| --- | --- | --- |
| Must stay understandable and debuggable | **PASS** | One pass, top-down, no backtracking. Every branch of `place()` produces either a placement with a written reason or a refusal with a written reason. |
| Resist heuristics that are not traceable | **PASS** | The only tuneable numbers are three named constants — `DEADLINE_HORIZON_DAYS` (30), `MINIMUM_SHRINK_FRACTION` (0.5), `MINIMUM_USEFUL_SLICE` (10m) — each documented where it is defined. Deadline proximity is linear rather than exponential specifically because a curve is harder to reason about when something turns up somewhere surprising. |
| Simple, explainable rules over clever ones | **PASS** | No bin-packing optimisation, no reordering to improve fit. A smarter allocator would fit marginally more work in and would be much harder to argue with. |
| Engine stays pure Kotlin, no Android | **PASS** | `:scheduling-engine:dependencies` shows no Android/Compose/Room artifacts; `grep -rn "import android" scheduling-engine/src/` finds nothing |
| No scheduling logic as an AI call | **PASS** | There is no network code anywhere in the repository yet. Scoring and allocation are arithmetic. |

### Test results

| Suite | Tests | Passed | Failed |
| --- | --- | --- | --- |
| `:scheduling-engine:test` | 122 | 122 | 0 |
| `:app:testDebugUnitTest` | 50 | 50 | 0 |
| **Total** | **172** | **172** | **0** |

Engine: `AllocationTest` 32, `ProgressTest` 26, `FreeTimeMapTest` 23,
`PriorityTest` 19, `TimeIntervalTest` 11, `IntervalsTest` 10, `EngineTest` 1.
App: `GoalMappersTest` 13, `TimeParsingTest` 13, `TaskMappersTest` 12,
`ScheduleBuilderTest` 12.

Verified with `./gradlew clean test assembleDebug` — a full build from an empty
build directory, not an incremental one. A debug APK is produced at
`app/build/outputs/apk/debug/app-debug.apk`.

One test failed on first run (`a SHRINKABLE task is cut down rather than
dropped`) because of a leftover expression in the test fixture that built a
zero-length day. That was a defect in the test, not the allocator; it was
removed and the test passes.

### Decisions I had to make (not specified in PROJECT_PLAN.md)

1. **Deadline horizon of 30 days, decaying linearly.** Section 3 says the score
   is `f(deadline_proximity, ...)` but never defines proximity. A deadline 30+
   days out contributes nothing; one today or overdue contributes the maximum.
   Linear, not exponential — an exponential curve looks more sophisticated and
   is much harder to reason about when a task turns up somewhere odd.
   **Question 5 under "Needs your input".**
2. **Default weights: deadline 0.50, importance 0.35, adherence 0.15.** They sum
   to 1.0, so a score is always in 0.0-1.0 and directly comparable. Section 3
   names the three weights but gives no starting values. Phase 6 will tune them.
3. **`SubTask` gained a `flexibility` field, defaulting to `SPLITTABLE`.**
   Section 3 puts flexibility on `TimeSpec`, but the sub-task is the unit that
   actually gets placed, and two sub-tasks of the same lesson genuinely differ:
   reading splits across gaps, a mock exam does not. It is exposed in the
   sub-task editor, worded as behaviour ("Can be broken up", "One sitting, all
   of it") rather than as enum names.
4. **`MOVABLE` behaves identically to `FIXED` in the allocator.** Both must be
   placed whole in a single interval. The difference is intent, not mechanism,
   and it shows up in the logged reason. If `MOVABLE` should mean something
   distinct — preferring a later slot, or being displaceable by higher-priority
   work — that is a Phase 4 reshuffle concern.
5. **`SHRINKABLE` floor at 50% of the request.** Below half, a task is not
   worth doing badly, so it is refused and reported instead. The number is a
   named constant. **Question 6 under "Needs your input".**
6. **Partial placement for `SPLITTABLE` on an overfull day.** If the day runs
   out, a splittable task takes what is available and the placement is marked
   partial, with the outstanding minutes named in the reason. The alternative
   — all or nothing — wastes real free time for no benefit, since splittable
   work continues tomorrow by definition.
7. **`MINIMUM_USEFUL_SLICE` of 10 minutes.** Free fragments shorter than this
   are dropped rather than filled, and a split will not create one. Without a
   floor the allocator produces four-minute study slivers that are worse than
   useless.
8. **A bare lesson is estimated at 60 minutes.** A lesson with no sub-tasks
   carries no duration of its own but still has to be placed.
   **Question 7 under "Needs your input" — this is a placeholder, not a
   considered answer.**
9. **`GoalTask` as the bridge type.** Sub-tasks and bare lessons are flattened
   into a `GoalTask` carrying its goal's deadline and importance, so scoring
   never walks back up the tree. It is the only `Schedulable` in the model,
   which is what makes "habits are never ranked" a compile-time fact.
10. **The decision log goes to logcat, not the UI.** Phase 3 says the reasoning
    is "not user-facing yet"; `TodayViewModel` dumps it under the tag
    `MendScheduler` on every recompute.

### Not done in this phase

- **The schedule is recomputed, not stored.** Every change to a block, habit or
  sub-task rebuilds today from scratch. That is correct for Phase 3 and will
  not survive Phase 4, which needs a *proposed* schedule to sit alongside the
  accepted one so a change can be accepted or declined. Expect to add schedule
  persistence there.
- **No date navigation.** Today only, as in Phase 1.
- **Adherence trend is neutral for every task.** By design — Phase 6 supplies
  the real values. The term is present, weighted, and tested, and it currently
  moves nothing.
- **Nothing has been run on a device or emulator.** The APK builds, all logic
  is unit tested, but no screen has been rendered on real hardware. There is no
  emulator image installed on this machine and no device attached.
  **This is the single biggest gap in tonight's work — see the summary.**
- Still no instrumented Room tests, as in Phases 1 and 2.

---

## Phase 4 — Reshuffling, Interrupts & Protected Sleep

**Status: COMPLETE** (clean build from scratch passes, 295 tests pass, committed)

### Deliverables checklist

#### Reshuffle flow

| # | Deliverable | Result | Evidence |
| --- | --- | --- | --- |
| 4.1 | Triggered by a schedule-invalidating event | **PASS** | `ScheduleInvalidatingEvent` — `DeadlineAdded`, `DeadlineChanged`, `TaskMissed`, `ManualEdit` |
| 4.2 | Deadline added/changed detected | **PASS** | `GoalRepository.saveGoal` compares the stored deadline and records an event only when it actually moved |
| 4.3 | Manual edit detected | **PASS** | `StaticLayerRepository` records add/change/remove of blocks and habits |
| 4.4 | Task missed detected | **PARTIAL — see below** | The event type exists and is handled end to end, but nothing yet *emits* it: marking a task missed is Phase 5's "wire AdherenceLogEntry into every completion/miss event app-wide" |
| 4.5 | Proposed schedule for tomorrow onward | **PASS** | `buildProposal` rejects any proposal dated before `effectiveDateFor(today)`; tested |
| 4.6 | Exactly three options in the UI | **PASS** | `ReshuffleProposalCard` — Accept, Decline, Propose different. No dismiss, no swipe-away |
| 4.7 | Propose Different free text stored for now | **PASS** | Stored verbatim in `reshuffle_proposals.response_text`; the dialog says plainly that Mend cannot act on it yet |

#### Emergency interrupt

| # | Deliverable | Result | Evidence |
| --- | --- | --- | --- |
| 4.8 | `is_one_shot && (deadline today \|\| no future free slot)` | **PASS** | `qualifyAsInterrupt`; all four branches tested, including the boundary where free time exactly equals the need (does not qualify) |
| 4.9 | Becomes the mandatory next action | **PASS** | `planInterrupt` claims the next free interval from `now`; surfaced as the topmost banner on Today |
| 4.10 | Assigned time or negotiated delay limit | **PASS** | A slot when one fits; the largest remaining interval plus a `negotiatedDelayLimit` for the shortfall when not; a pure delay limit when the day has nothing left |
| 4.11 | Overrun message explaining what today loses | **PASS** | `describeOverrun` names each displaced task with its lost minutes and a total |
| 4.12 | Interrupts claim free time only, never static blocks | **PASS** | Drawn from `freeTimeMap.free`, which already excludes blocks, habits and sleep. Tested against college hours and against a habit |

#### Protected sleep

| # | Deliverable | Result | Evidence |
| --- | --- | --- | --- |
| 4.13 | `PROTECTED_RECOVERY` type implemented | **PASS** | `ProtectedRecovery` — a `ScheduleEntry`, deliberately **not** `Schedulable`, so it can never be ranked or bin-packed |
| 4.14 | Decide hard stop vs nudge, implement hard stop | **PASS** | Hard stop is the default and is what the allocator enforces; nudge is available per block |
| 4.15 | Threshold configurable | **PASS** | `windDown` per block, default 30m, editable in Settings. The boundary is `bedtime - windDown` |
| 4.16 | Overrunning work deferred to tomorrow | **PASS** | Two mechanisms: the allocator cannot place work past the boundary at all, and `evaluateSleepStop` returns `STOP` for work still running |
| 4.17 | Log actual vs target sleep as its own adherence stream | **PASS** | `SleepLogEntry` + `sleep_log` table, entirely separate from `adherence_log`. It has no `taskId`, so sleep cannot be filed as a missed task even by accident |

#### Delay negotiation tier

| # | Deliverable | Result | Evidence |
| --- | --- | --- | --- |
| 4.18 | `pileup_risk` + skip-count logic | **PASS** | `assessFragility` counts `MISSED` outcomes from the append-only log; `FRAGILE_SKIP_THRESHOLD = 2` |
| 4.19 | Only fragile, sequential work becomes eligible | **PASS** | Both halves required. Parallelisable work is never eligible however often it is skipped — tested at four skips |
| 4.20 | `negotiated_delay_limit` as last resort, not default | **PASS** | `negotiateDelay` refuses by default and names why; grants are capped at 2 days, because an unbounded delay is a cancellation with better manners |

### Constraints checklist

| Constraint | Result | How it is enforced |
| --- | --- | --- |
| New schedules take effect tomorrow, never retroactively | **PASS** | `buildProposal` throws if the proposed date is before `effectiveDateFor(today)`. One function owns the rule so it cannot be quietly broken elsewhere |
| Except the interrupt, which is real-time | **PASS** | Interrupts bypass the proposal flow entirely and act on today |
| Reshuffle prompts must not become noise | **PASS** | Three separate guards: same-day events batch into one proposal (`batchEvents`); no new proposal is created while one is unanswered; only a deadline that *actually moved* counts, so renaming a goal prompts nothing |
| Sleep can compete with and override goal work | **PASS** | `sleep beats outstanding work for the late evening` — 20h of work wanted, the evening still ends at the boundary |
| Engine stays pure Kotlin | **PASS** | `grep -rn "import android" scheduling-engine/src/` finds nothing; classpath check clean |

### Test results

| Suite | Tests | Passed | Failed |
| --- | --- | --- | --- |
| `:scheduling-engine:test` | 208 | 208 | 0 |
| `:app:testDebugUnitTest` | 87 | 87 | 0 |
| **Total** | **295** | **295** | **0** |

New this phase: `SleepProtectionTest` 27, `ReshuffleTest` 23, `InterruptTest` 20,
`FragilityTest` 16 (engine); `Phase4MappersTest` 24, `InterruptDetectionTest` 13 (app).

Verified with `./gradlew clean test assembleDebug` from an empty build directory.

### Two real bugs the tests caught

Both were in my code, not the tests:

1. **`planInterrupt` drew from the wrong pool.** I had it take from
   `remainingFree` — what the allocator left over — which meant an interrupt
   could never displace goal work, making the `displaced` field dead code and
   contradicting the plan's own overrun message ("here's what you're now
   losing today"), which only means something if displacement can happen. Now
   it draws from `freeTimeMap.free`: goal work is displaceable, fixed
   commitments are not. It also accepted a one-minute sliver as a valid slot;
   it now applies the same `MINIMUM_USEFUL_SLICE` floor the allocator uses.
2. **`describeOverrun` undercounted.** It matched scheduled work by *start*
   time, so a task straddling the end of the interrupt's slot was reported as
   fully intact when half of it was already gone. Now measured by interval
   overlap.

### Decisions I had to make (not specified in PROJECT_PLAN.md)

1. **The hard stop bites at `bedtime - windDown`, not at bedtime.** Work still
   running when the block opens has already cost sleep, so enforcing at bedtime
   itself would be enforcing too late. Default wind-down 30 minutes,
   configurable per block as the plan requires.
2. **A night that crosses midnight occupies two intervals on one calendar
   day** — the tail of last night and the head of tonight. That is genuinely
   what the day looks like; collapsing it would hide half the protected time.
3. **Sleep is its own stream with no `taskId`.** The plan says "its own
   adherence stream"; making it structurally incapable of holding a task id
   means the two can never be conflated.
4. **A night counts as on target within 15 minutes** (`BEDTIME_ON_TARGET_SLACK_MINUTES`).
   Arbitrary but named and adjustable.
5. **At most one interrupt at a time.** Two simultaneous "mandatory next
   actions" is a contradiction; the most overdue wins. If the category ever
   routinely produced several it would mean the qualification test had stopped
   being narrow enough to mean anything.
6. **Free time exactly equal to the need does not qualify as an emergency.**
   Strictly less than. A task that fits, fits.
7. **`FRAGILE_SKIP_THRESHOLD = 2`**, from the plan's "skipped once/twice".
   **`MAX_NEGOTIATED_DELAY = 2 days`** is mine, not the plan's.
8. **Delay eligibility is derived from the adherence log, not a stored flag.**
   `Goal.pileupRisk` still exists per Section 3, but `assessFragility` reads
   the append-only history instead. A flag can be set by anything; a history
   cannot be argued with.
9. **`Goal.isSequential` added.** The plan names "sequential/non-parallelizable"
   as half the eligibility test but Section 3 has no such field. Exposed in the
   goal editor as "Lessons must be done in order".
10. **Event rows store their rendered summary.** Reading one back produces a
    `ManualEdit` carrying that text. Batching and display only ever use
    `occurredAt` and `describe()`, so persisting every field of every variant
    to rebuild an identical string would be machinery for nothing.
11. **The proposed schedule is not stored**, only the negotiation. It is
    recomputed deterministically from the same inputs, so a stored copy could
    only fall out of step.
12. **`fallbackToDestructiveMigration()` on the database bump to v2.** The app
    has never shipped. Flagged in the code and below as something that **must**
    be replaced before release.

### Needs your input (new this phase)

**9. Decline does not currently mean "keep the old plan".** This is the one
place Phase 4 is semantically thinner than it looks, and it is worth your
attention. Schedules are derived, not stored — so if a deadline moves, tomorrow
is recomputed whether or not you accept the proposal. Decline therefore means
"acknowledged, stop asking", not "revert". Making it mean revert requires
pinning an accepted schedule per date, which is real work and a design decision
I did not want to make for you. It is the same schedule-persistence gap I
flagged at the end of Phase 3, now actually biting.

**10. `MAX_NEGOTIATED_DELAY` is 2 days.** Invented. Is that the right ceiling
for the kind of work you are modelling?

**11. Destructive migration is armed.** Database v2 drops and recreates on
schema change. Fine now, unacceptable once you have real adherence history —
losing it would destroy the basis of every behavioural feature from Phase 5 on.
Worth a reminder in Phase 9.

### Not done in this phase

- **`TaskMissed` events are never emitted.** The whole path exists and is
  tested, but nothing marks a task as missed yet. That is explicitly Phase 5's
  first deliverable.
- **Fragility is engine-only.** `assessFragility` and `negotiateDelay` are
  written and tested but not called from the app, because they read the
  adherence log and nothing writes to it until Phase 5.
- **Sleep times are never recorded automatically.** `RecoveryRepository` has
  `recordBedtime`/`recordWake` and the trend renders in Settings, but there is
  no UI to log a night and no alarm to prompt one. Automatic capture needs the
  Phase 8 alarm infrastructure.
- **Still nothing run on a device.** Unchanged from Phase 3 and still the
  largest untested surface: no emulator image, no attached hardware.
- **Still no instrumented Room tests**, now covering eight tables rather than
  five.

---

## Phase 5 — Adherence Logging & Guilt-Trip Triggers

**Status: COMPLETE** (clean build from scratch passes, 375 tests pass, committed)

### Deliverables checklist

| # | Deliverable | Result | Evidence |
| --- | --- | --- | --- |
| 5.1 | Adherence logging on every goal sub-task completion | **PASS** | `GoalRepository.setSubTaskDone` writes through `AdherenceRepository`, from both the Today screen and the goal detail screen |
| 5.2 | Adherence logging on every miss | **PASS** | Two paths: a `Missed` action on each scheduled row, and an automatic sweep (`detectElapsedMisses`) for slots that pass unmarked |
| 5.3 | Wired for interrupts too | **PASS** | An interrupt is a `GoalTask` from a one-shot goal, so it flows through the same completion and miss paths as any other scheduled work |
| 5.4 | Wired for protected recovery / sleep | **PASS** | `RecoveryRepository.recordWake` writes an adherence row alongside the `SleepLogEntry`, so sleep appears in app-wide stats without losing the sleep-specific detail |
| 5.5 | Rolling adherence rate per task | **PASS** | `computeAdherence(log, taskId, asOf, windowDays)`; 14-day window by default, edges tested |
| 5.6 | Rolling adherence rate per task-type | **PASS** | `computeAdherenceByType` groups by the user's own type names |
| 5.7 | Delay-pattern computation | **PASS** | Mean, mean-when-late, median, worst, and a `DelayTrend` of WORSENING / STABLE / IMPROVING / UNKNOWN |
| 5.8 | Guilt-trip fires only for `IRL_BINDING` | **PASS** | First gate in `evaluateGuiltTrip`; soft goals and no-consequence goals tested explicitly |
| 5.9 | Based on projected pace vs deadline | **PASS** | `projectPace` derives velocity from honoured slots in the adherence log and compares capacity to remaining effort |
| 5.10 | Message states progress so far | **PASS** | "0 of 4 lessons done, 0/2 sub-tasks" |
| 5.11 | Message states days remaining | **PASS** | "5 days left", with "The deadline is today" and "One day left" as special cases |
| 5.12 | Message states why the projection misses | **PASS** | "600m of work left, 5d until the deadline. At the 30m/day you have actually been doing, that is 150m of capacity — 450m short." |
| 5.13 | Message ends with a request to reshuffle | **PASS** | `GuiltTrip.reshuffleRequest`; "Yes, replan" raises an event that routes into the Phase 4 proposal flow |
| 5.14 | Habits excluded | **PASS** | Structurally impossible: habits are not in the goal hierarchy and carry no consequence type, so they cannot reach the trigger |
| 5.15 | Soft goals excluded | **PASS** | Turned away at gate 1; tested against a goal far enough behind that it would otherwise fire |

### Constraints checklist

| Constraint | Result | How it is enforced |
| --- | --- | --- |
| Based on actual logged history | **PASS** | Velocity is the sum of honoured slot durations from the log, divided by observed days. Nothing is inferred from intentions or estimates |
| Based on projected velocity, not vagueness | **PASS** | Three numeric gates. With no history the trigger stays silent even when the deadline is arithmetically impossible — tested |
| Precision keeps it from becoming noise | **PASS** | Needs ≥3 active days **and** ≥3 completions. Misses alone do not qualify: a task never once done gives nothing to project a rate from |
| Not a vague "you haven't done this in a while" | **PASS** | A test asserts the message contains none of "lazy", "should have", "disappointing", "failure", "again" — it states consequence, not disapproval |
| Engine stays pure Kotlin | **PASS** | `grep -rn "import android" scheduling-engine/src/` finds nothing |

### Test results

| Suite | Tests | Passed | Failed |
| --- | --- | --- | --- |
| `:scheduling-engine:test` | 275 | 275 | 0 |
| `:app:testDebugUnitTest` | 100 | 100 | 0 |
| **Total** | **375** | **375** | **0** |

New this phase: `AdherenceTest` 29, `GuiltTripTest` 25, `MissDetectionTest` 13
(engine); `AdherenceRepositoryTest` 13 (app).

Verified with `./gradlew clean test assembleDebug` from an empty build directory.

### One real bug the tests caught

`AdherenceStats.describe()` rendered "N late by Xm on average" where X was the
mean delay across *all* completed sessions, on-time zeros included — so three
punctual sessions and one 30-minute slip reported "1 late by 7m on average".
The sentence and the number were answering different questions. Fixed by adding
`averageDelayWhenLateMinutes` ("when it slips, by how much?") alongside
`averageDelayMinutes` ("how late is this work typically"). Both are kept: they
are genuinely different and both are useful.

One test failure was mine rather than the code's — a fixture gave two goals
different sub-task ids, so one correctly had no history to project from.

### Decisions I had to make (not specified in PROJECT_PLAN.md)

1. **Late counts as honoured; missed does not.** `adherenceRate` is
   completed/total, with punctuality reported separately. Being twenty minutes
   late is a punctuality problem and not turning up is a different one;
   collapsing them would hide which is actually happening.
2. **A task with no history scores 1.0, not 0.0.** Assuming the worst about
   work that has never been scheduled would poison the priority score with a
   behavioural claim nothing supports.
3. **14-day rolling window, hard-edged.** Entries outside it are dropped
   entirely rather than weighted down — a weighted average is much harder to
   check by hand, and checkability is the requirement here.
4. **Velocity comes from assigned-slot durations of honoured entries**, spread
   over the observed span rather than the nominal window. Two weeks of history
   and two days of it both get an honest denominator.
5. **Projection gates: ≥3 active days and ≥3 completions.** Both invented.
   Without them the first missed session on a new goal would trigger a warning,
   which is exactly the noise the constraint forbids. **Question 12 below.**
6. **Delay trend compares the two halves of the window**, with a 5-minute
   sensitivity floor. Crude on purpose: a regression line would look more
   rigorous and be far harder to explain when it disagreed with what the user
   can see in their own history.
7. **Work completed with no assigned slot is recorded as on time.** Something
   ticked off from the goal screen that was never scheduled cannot be late for
   an appointment it never had. It still gets a synthetic slot so it counts
   towards velocity.
8. **Un-ticking a sub-task writes nothing.** The stream is append-only, so a
   row for an action the user is in the middle of undoing would be permanent.
9. **Sleep writes to both streams.** `SleepLogEntry` keeps the sleep-specific
   detail (bedtime drift, shortfall); the adherence row carries the uniform
   honoured/missed signal so protected recovery appears in app-wide statistics.
   A night more than 2 hours past target bedtime is recorded as MISSED.
   **Question 13 below.**
10. **A dismissed warning is session-only.** "Not now" hides it until the app
    restarts; it is not persisted and is not treated as resolved.
11. **Guilt trips are ordered by deadline, not by size of shortfall.** A small
    gap on something due Friday matters more than a large one due next month.

### Needs your input (new this phase)

**12. The projection gates are invented.** ≥3 active days and ≥3 completions
before a warning can fire. Too strict and a genuinely doomed deadline stays
quiet for a week; too loose and one bad Tuesday triggers a lecture. These are
the numbers most worth tuning once you have used it for a fortnight.

**13. Two hours late to bed counts as a missed night.** Also invented
(`MISSED_BEDTIME_THRESHOLD_MINUTES`). Is that the right line for you, given
fixing chronic late-night work is a stated goal of the project?

### Not done in this phase

- **Missed-work sweeps cover today only.** `detectElapsedMisses` runs against
  the live schedule while the app is open. Days the app was never opened are
  not back-filled, because that needs either stored schedules or a background
  job — Phase 8 work, and the third time this same gap has surfaced.
- **`adherenceTrend` still does not feed the priority score.** `GoalTask`
  carries the field and the allocator weights it, but every task is still
  constructed with the neutral 0.5. Wiring the computed rate in is Phase 6's
  adaptive-weights work, and doing it here would have changed scheduling
  behaviour ahead of the phase that is supposed to introduce it.
- **Fragility is computed but still not surfaced.** `assessFragility` now has a
  real stream to read, but nothing in the UI offers to negotiate a delay yet.
- **No adherence statistics screen.** The stats are computed and tested, and
  the guilt trip consumes them, but there is no view showing adherence per task
  or per type. Nothing in the Phase 5 deliverables asks for one; Phase 6 adds a
  Settings screen where it would naturally live.
- **Still nothing run on a device.** Unchanged since Phase 3, and now the
  largest untested surface by a considerable margin: nine tables, five
  repositories, and a Today screen with four distinct banner states.

---

## Post-Phase-5 — Closing the four flagged gaps

**Status: COMPLETE** (clean build passes, 408 tests pass, committed)

Four gaps were outstanding after Phase 5. All four are now addressed.

### 1. Run it on real hardware — **DONE, and it found three bugs**

The largest gap in the project since Phase 3 is closed. An emulator was
installed (`system-images;android-35;google_apis;x86_64`, AVD `mend-test`,
headless with swiftshader), the app installed and driven through its actual UI.

**Verified working on an Android runtime:**

- App launches, no crash, survives restart.
- Every screen renders: Today, Tasks (three tabs), goal detail, Settings, and
  all six dialogs.
- Room persistence is real — a static block survived an app restart and a
  reinstall.
- The full timeline renders correctly: sleep at both ends of the day, the
  evening block opening at 22:00 for a 22:30 bedtime (wind-down applied), a
  static block, scheduled goal work with Done/Missed actions, and free gaps.
- Progress rollup updates live ("0 of 0" → "0 of 1 lessons" on adding one).
- Form validation works: Save stays disabled with a live error until the input
  is valid.
- The reshuffle card renders with exactly three actions.
- **The decision log works on device.** Adding the sleep block moved free time
  from 1020m to 510m and relocated the task from 00:00 to 06:30, with the
  reasoning legible in logcat under `MendScheduler`. The Phase 3 promise —
  "you must be able to answer *why is this here* from logs alone" — holds on
  real hardware.

**Three bugs found, none of which any unit test could have caught:**

1. **Window insets applied twice.** `TasksScreen` nests a `Scaffold` inside
   `MendApp`'s. `Modifier.padding()` does not *consume* insets, so the status
   bar was inset twice and content sat a whole status bar too low. Fixed with
   `contentWindowInsets = WindowInsets(0,0,0,0)` on the inner Scaffolds.
2. **The same bug a third level down.** `TopAppBar` insets itself by default, so
   the goal detail screen applied it three times over. Same fix.
3. **Protected sleep rendered as "fixed block".** The subtitle fell through to
   the static-block branch, so the one entry type Phase 4 exists to distinguish
   was labelled as the thing it is not. Now reads "protected".

And one design bug the device made obvious:

4. **No-op proposals were being raised.** A reshuffle card appeared saying
   "nothing needs to move" with Accept/Decline buttons. Asking someone to accept
   a change that is not a change is exactly the noise PROJECT_PLAN.md warns
   against, and it trains them to dismiss the prompts that matter. Proposals
   with an empty diff are now suppressed; their events are still retired so they
   cannot resurface later as a mystery prompt.

### 2. Wire `adherenceTrend` into the priority score — **DONE**

The score has always weighted an adherence term, but every task carried the
neutral 0.5, so the term was inert. It now carries real recorded history.

The difficulty is that most individual sub-tasks are scheduled once or twice
ever, so their personal history is far too thin to rank on — one missed session
would make a brand-new task look chronically neglected. `resolveAdherenceTrend`
therefore works down three steps, widest evidence first:

1. the task's own record, if it has enough observations;
2. otherwise the record of **that kind of work** — if reading is honoured 40% of
   the time across the goal, a new reading sub-task inherits that;
3. otherwise neutral. Silence, not suspicion.

`resolveAdherenceTrendWithSource` reports which of the three was used, because
"this ranked higher because you keep skipping it" is only defensible if it can
name the evidence.

### 3. Back-fill missed work for days the app was not opened — **DONE**

Previously the sweep covered today only, so a weekend away left no trace and
quietly flattered every statistic built on the stream.

There is no stored schedule for a past day and there does not need to be: a
schedule is a pure function of its inputs, so a past day is recomputed. The risk
here is not missing a miss, it is **inventing** one — which would poison the
append-only stream in the direction hardest to notice and impossible to correct.
Three guards:

- **Completed work drops out on its own** — it stops being pending, so it is not
  in the recomputed day at all.
- **Goals that did not exist yet are excluded**, by creation date. Without this,
  adding a goal today would back-date a fortnight of failures for work nobody
  had committed to.
- **The lookback is capped at 14 days.** Back-filling a month after a long
  absence would flood the stream with entries saying nothing except "you were
  not using the app", which is not a behavioural signal.

A past day is judged at **its own end**, not against the current clock —
otherwise an evening slot on a past day would look as though it had not happened
yet. Sweep progress is kept in SharedPreferences rather than the schema: it is
bookkeeping about the app, not something the user owns.

### 4. The invented numbers — **partially: I cannot choose them, so I made them findable**

The values themselves are still yours to set; I have no basis for picking them
and guessing would defeat the point of having flagged them.

What I could fix is that **nineteen invented constants were scattered across
eleven files**, which made them effectively invisible — the real problem. They
now live in one place, `com.mend.engine.tuning.Tuning`, each with what it
controls and what moving it would do. Every original constant references it, so
changing a value there changes the behaviour and nothing has to be hunted down.

The rules are deliberately *not* in there. Sleep is protected, habits are never
ranked, changes take effect tomorrow, the guilt trip fires only for real
consequences — those are the design, not settings.

The two most worth your attention remain the projection gates
(`MINIMUM_ACTIVE_DAYS_FOR_PROJECTION` / `MINIMUM_COMPLETIONS_FOR_PROJECTION`,
both 3) and `MISSED_BEDTIME_THRESHOLD_MINUTES` (120).

### Test results

| Suite | Tests | Passed | Failed |
| --- | --- | --- | --- |
| `:scheduling-engine:test` | 296 | 296 | 0 |
| `:app:testDebugUnitTest` | 112 | 112 | 0 |
| **Total** | **408** | **408** | **0** |

New: `AdherenceTrendTest` 12, `SweepDatesTest` 9 (engine); `MissSweeperTest` 12
(app). Verified with `./gradlew clean test assembleDebug`, then installed and
exercised on the emulator.

### Still not done

- **Emulator only, not a physical device.** Alarms, Doze, and OEM battery
  management still cannot be verified this way, and those are the Phase 8
  reliability risks that matter most.
- **No instrumented Room tests.** Persistence is now demonstrably working on
  device by observation, but there is still no automated test against real
  SQLite.
- **Sweep runs at app open only.** Genuinely periodic catch-up wants the Phase 8
  `WorkManager` job.

---

## Phase 6 — Adaptive Weight Tuning

**Status: COMPLETE** (clean build passes, 455 tests pass, verified on device, committed)

### Deliverables checklist

| # | Deliverable | Result | Evidence |
| --- | --- | --- | --- |
| 6.1 | Feedback prompt after a reshuffle/prioritisation decision | **PASS** | `FeedbackPrompt` appears on Today immediately after a proposal is answered; verified on device |
| 6.2 | Three responses: too aggressive / too lax / just right | **PASS** | Exactly those, plus Skip. "Just right" is a real answer that resets the streak, not a dismissal |
| 6.3 | Small, explainable adjustment rule | **PASS** | Count consecutive identical responses; at three, move one named weight by one fixed step. No model, no learning rate, no decay |
| 6.4 | N consecutive responses nudge a weight | **PASS** | `FEEDBACK_STREAK_THRESHOLD = 3`; tested at one, two, three and ten responses |
| 6.5 | Fixed step | **PASS** | `WEIGHT_STEP = 0.05`; a ten-long streak moves exactly as far as a three-long one |
| 6.6 | Bounded | **PASS** | `WEIGHT_MIN = 0.05`, `WEIGHT_MAX = 0.80`, so no term is ever switched off or allowed to drown out the others |
| 6.7 | Explicitly no ML model | **PASS** | The entire rule is `leadingStreak` plus a comparison. It fits on a screen |
| 6.8 | Log every adjustment with its cause | **PASS** | `weight_adjustments` is append-only and stores the feedback, the streak length, both values, and the rendered reason |
| 6.9 | Settings screen showing current weights | **PASS** | Three sliders with live values; verified on device |
| 6.10 | Recent adjustment history | **PASS** | Newest first, each with its timestamp, whether it was automatic or by hand, and the reason recorded at the time |
| 6.11 | Manual override | **PASS** | Sliders write through `manualAdjustment`; verified on device — 0.50 → 0.30 with the other two rescaled and the change explained in the history |

### Constraints checklist

| Constraint | Result | How it is enforced |
| --- | --- | --- |
| Keep it debuggable | **PASS** | Every adjustment stores a rendered sentence naming the feedback, the streak, the old value and the new one. Nothing has to be recomputed to read the history |
| Understand exactly why a weight changed, six months later | **PASS** | The reason is stored **as written**, not regenerated on read, so changing the rule later cannot retroactively alter the explanation for an older adjustment |
| No ML | **PASS** | See 6.7 |
| Engine stays pure Kotlin | **PASS** | `grep -rn "import android" scheduling-engine/src/` finds nothing |

### Test results

| Suite | Tests | Passed | Failed |
| --- | --- | --- | --- |
| `:scheduling-engine:test` | 326 | 326 | 0 |
| `:app:testDebugUnitTest` | 129 | 129 | 0 |
| **Total** | **455** | **455** | **0** |

New: `WeightTuningTest` 30 (engine), `WeightsRepositoryTest` 17 (app).

### Verified on device

Installed and driven through the real UI:

- Settings renders the three sliders at 0.50 / 0.35 / 0.15 with the explanation
  and the empty-history note.
- **Manual override works end to end.** Dragging deadline pressure to 0.30
  rescaled importance to 0.49 and adherence to 0.21 — preserving their 7:3
  ratio exactly — with the total still 1.00, and wrote the history entry: *"set
  by hand: deadline pressure changed from 0.50 to 0.30; the other weights were
  rescaled to keep the total at 1.00"*.
- **The feedback prompt appears after answering a proposal**, with all three
  options and the honest note that nothing changes on one answer.

The same run also confirmed the Phase 4 and Phase 5 chain working live: the
elapsed-slot sweep noticed a 00:00 block had passed unmarked, logged the miss,
and that raised a proposal reading *"Because Chapter 1 was missed on
2026-09-11..."*. That is four phases interlocking on real hardware.

### One gap the device run exposed

A lesson with no sub-tasks is scheduled **as itself**, but Today only mapped
sub-tasks back to something completable — so a bare lesson offered "Missed" and
nothing else, which is a strange thing to offer on its own. Today now maps bare
lessons too and offers "Done" for them.

### Decisions I had to make (not specified in PROJECT_PLAN.md)

1. **Importance is never adjusted automatically.** The plan allows the rule to
   touch any of the three. I deliberately excluded importance: it encodes the
   user's own stated judgement of what matters, and an app quietly deciding its
   owner cares less about their exams than they said would be overstepping. It
   remains fully settable by hand, and the Settings screen says so.
2. **Which weight moves.** "Too aggressive" lowers `w_deadline`, the term that
   drives urgency; if it is already at the floor, `w_adherence_trend` is lowered
   instead. "Too lax" does the reverse. Deterministic, and it touches both
   pressure terms as the plan's prompt asks.
3. **Weights are renormalised to sum to 1.0** after every change. Without it the
   score would drift out of its 0.0-1.0 range and stop being comparable with
   scores recorded before the change, which would quietly invalidate every
   explanation already in the decision log. The untouched weights keep their
   relative proportion, and the adjustment message says this happened.
4. **Responses are "spent" once they trigger an adjustment.** Exactly the
   responses making up the streak are marked, so a fourth complaint does not
   immediately move the weight again on the strength of evidence already used.
   This is the subtle one — without it the weights drift on half the evidence
   they appear to need.
5. **Nothing moves silently.** An adjustment surfaces a notice on Today saying
   what changed. An app that retunes itself without telling anyone is exactly
   what the debuggability constraint is guarding against.
6. **Step 0.05, bounds 0.05-0.80, streak 3.** All invented, all in `Tuning`.
7. **The slider commits on release**, not on every pixel — each change is an
   append-only history entry and a drag would otherwise write fifty of them.
8. **A "just right" streak never adjusts anything.** It is agreement, and the
   rule only responds to sustained complaint.

### Not done in this phase

- **The feedback prompt only appears after a reshuffle proposal.** The plan says
  "after a reshuffle/prioritisation decision", which this satisfies, but
  proposals are deliberately rare — so in practice the app will ask for feedback
  infrequently. If it turns out to ask too seldom to ever accumulate a streak,
  the obvious fix is an occasional end-of-day prompt. Worth watching in use.
- **No test that a moved weight changes the resulting schedule.** The weights
  demonstrably reach the allocator (`buildDaySchedule` takes them, and
  `AllocationTest` proves weights change which task wins a contested slot), but
  there is no end-to-end test tying a feedback streak to a different day. The
  pieces are each covered; the seam between them is not.
- **Still emulator only.** Alarms, Doze and OEM battery management remain
  unverifiable this way, and are the Phase 8 risks that actually matter.

---

## Phase 7 — AI Layer: NLP Parsing & Message Generation

**Status: COMPLETE** (clean build passes, 488 tests pass, offline path verified on device, committed)

### Deliverables checklist

| # | Deliverable | Result | Evidence |
| --- | --- | --- | --- |
| 7.1 | `AiBridge` module: thin, swappable interface | **PASS** | `com.mend.app.ai.AiBridge` — two methods and nothing else. Two implementations: `AnthropicAiBridge` and `OfflineAiBridge` |
| 7.2 | `parseNaturalLanguageInput(text): StructuredConstraint` | **PASS** | Returns one of four engine-defined shapes; "exams from next week for 5 days" maps to `BusyPeriod` |
| 7.3 | `generateExplanationMessage(context): String` | **PASS** | Takes an `ExplanationContext` built from numbers the engine already computed; covers both the guilt trip and the reshuffle summary |
| 7.4 | Uses the Anthropic API | **PASS** | `AnthropicClient` — Messages API, `claude-opus-5`, `anthropic-version: 2023-06-01`, `output_config.effort: low` |
| 7.5 | "Propose Different" wired through parsing | **PASS** | The card hands off to a three-step flow: write it → see what Mend understood → decide |
| 7.6 | Result validated by the deterministic engine | **PASS** | `validateConstraint` runs **before** anything reaches the screen, not before it is applied |
| 7.7 | Still goes through the normal accept/decline flow | **PASS on the flow, FAIL on the effect — corrected 14 Sep, see question 18** | Accepting an interpretation raises an ordinary `ManualEdit` event and the plan that follows arrives with the same three buttons. But the parsed constraint is **never applied**: `ManualEdit` carries only a timestamp and a display string, and the replan runs against unchanged inputs |
| 7.8 | The AI never applies a change directly | **PASS** | Nothing in the AI package can reach a repository. The bridge returns a value; the engine judges it; the user decides |
| 7.9 | Graceful offline degradation | **PASS** | Verified on device with no key: "No API key is set, so Mend cannot read prose. What you wrote will be saved as-is…" with a **Save what I wrote** button |
| 7.10 | Manual structured-input form fallback | **PASS** — with a caveat | The words are preserved verbatim and the user is directed to make the change by hand in Tasks, which already has full create/edit UI for every constraint type. There is no separate duplicate form; see "Decisions" |
| 7.11 | Templated (non-generated) messages offline | **PASS** | `TemplatedMessages` lives in the **engine**, so the fallback has no network dependency to fail with. `OfflineAiBridge` returns them as `Success`, not as a degraded result |
| 7.12 | Fully usable offline for core scheduling | **PASS** | The engine has no reference to the AI layer at all — `grep` for `AiBridge\|Anthropic\|apiKey` across `scheduling-engine/src/` returns nothing |
| 7.13 | Cost/latency awareness | **PASS** | `effort: low` on both calls (small, well-specified jobs); 512/400 max tokens; a single probe for availability rather than a poll |
| 7.14 | Never block a notification/alarm on a network call | **PASS** | Both methods are `suspend`, run on `Dispatchers.IO` under a 20s `withTimeoutOrNull`, and are called only from the proposal dialog. No scheduling, alarm or draw path awaits them |

### Constraints checklist

| Constraint | Result | How it is enforced |
| --- | --- | --- |
| AI never in the path of core scheduling decisions | **PASS** | Structural, not conventional: the engine module cannot see the app module, so `computePriorityScore`, `allocate`, `evaluateGuiltTrip` and the rest are unreachable from the AI layer by construction |
| Only translation in, phrasing out | **PASS** | The parser can only produce four shapes, none of which expresses a priority or a placement. The generator is given a finished message and told to keep every number and add nothing |
| Validate AI output against hard constraints **before presenting it** | **PASS** | `ProposalInterpreter` runs parse → validate → show, in that order. Tested with a well-formed but incoherent suggestion (a past deadline) and one that would empty the day |
| Engine stays pure Kotlin | **PASS** | No Android and now no network reference either |

### Test results

| Suite | Tests | Passed | Failed |
| --- | --- | --- | --- |
| `:scheduling-engine:test` | 346 | 346 | 0 |
| `:app:testDebugUnitTest` | 142 | 142 | 0 |
| **Total** | **488** | **488** | **0** |

New: `ConstraintValidationTest` 20 (engine), `ProposalInterpreterTest` 13 (app).

### The dependency decision, and why it went the way it did

The official Anthropic Java SDK was added, built, installed and measured first.
It works on Android — but it adds **7.5 MB to the APK** (16.6 → 24.1 MB, +45%),
pulling in Jackson's six artifacts, OkHttp, Okio and Apache HttpComponents, for
what is two POST requests to one endpoint.

That was put to you as a decision rather than made quietly, because it is a
trade between a documented default and PROJECT_PLAN.md's own words: Section 1
makes "lightweight and native" a non-negotiable, and Section 2 asks for the
Anthropic API "via a **thin network layer**". You chose the thin layer.

`AnthropicClient` is therefore about 170 lines over `HttpsURLConnection` and
`org.json`, both already on every Android device. **Zero dependencies added**,
APK unchanged at 16.6 MB. The cost is that the request shape is ours to get
right, so it is kept to the documented essentials and nothing clever.

### One bug the device run caught

The Phase 7 flow was **unreachable**. `ReshuffleProposalCard` still owned the
Phase 4 dialog internally, so tapping "Propose different" opened the old
save-verbatim box and only called back afterwards — the new parse-and-confirm
flow sat behind it, fully built and never shown. Unit tests could not see this;
the whole failure was in which composable owned the state. The card now hands
straight off to the Today screen, which owns the flow.

### Decisions I had to make (not specified in PROJECT_PLAN.md)

1. **`StructuredConstraint` lives in the engine, not the app.** The engine has
   to validate these, and a type the validator cannot see is a type it cannot
   check. It also means the four shapes are the *only* things an AI parse can
   ever express — "reschedule this" and "deprioritise that" are not
   constructible, so the narrowness is enforced by the type system rather than
   by the prompt.
2. **Four shapes: busy period, avoid window, deadline change, unrecognised.**
   The plan gives one worked example and Phase 4 gives another; these cover
   both plus the obvious third, and `Unrecognised` is a first-class outcome
   rather than an error.
3. **The parser is told to prefer saying nothing.** An unrecognised input costs
   the user one form; a confidently wrong one costs them a day they did not
   agree to. The engine's validator is a backstop, not a substitute for the
   model declining.
4. **Validation checks consequence, not plausibility.** The engine cannot know
   whether you really have exams next week. It can know that "keep mornings
   free" would leave *no usable time at all* on a day that is already college
   and sleep — and that is the check that matters, because it is the one the
   user cannot easily do in their head.
5. **A three-step confirmation, not two.** Write it, see what Mend understood,
   then decide. The middle step is where a misreading becomes a corrected
   sentence instead of a changed schedule.
6. **A generation failure is invisible.** If phrasing fails for any reason the
   templated version is returned as a success. The user has nothing useful to
   do with the knowledge that no model was involved, and an error about prose
   would be noise.
7. **`TemplatedMessages` lives in the engine.** A fallback that sits behind the
   network layer is a fallback that can fail with it.
8. **No separate manual form.** Deliverable 7.10 asks for one; Tasks already
   has complete create/edit UI for blocks, habits, goals and deadlines — every
   constraint the AI can express. Building a second, parallel form would be
   duplicate UI for the same data. The offline path preserves the user's words
   and points them at the existing screens. **Flagged as question 14 below in
   case you read that deliverable more literally than I did.**
9. **API key in plain SharedPreferences**, with the honesty about it written
   into the UI: a key on a phone can be read by anyone who can unlock the
   phone. Acceptable for a personal app where the owner supplies their own key;
   not acceptable for anything shipped to someone else. **No key exists
   anywhere in the repository** — verified by `git grep`.
10. **`effort: low` on both calls.** Both jobs are small and well-specified and
    neither benefits from deep reasoning.

### Needs your input (new this phase)

**14. There is no separate manual structured-input form.** Deliverable 7.10
names one. I judged it duplicate UI — Tasks already edits every constraint the
AI can produce — and instead preserve the typed words and point there. If you
meant a literal fallback form inside the proposal dialog, say so and I will
build it.

**15. An API key on a phone is extractable.** Fine for your own key on your own
device. It becomes a real problem the moment Mend is shared, and Phase 10's
"strip personal config before release" should explicitly include a check that
no key ships.

### Not done in this phase

- **The live API path has never been exercised.** No key is configured, and I
  did not ask you for one — a credential is not something I should be
  collecting. Everything around the call is tested: the offline branch, the
  validation gate, the interpreter's four outcomes, and the request builder.
  What is unverified is the round trip itself, and the first real call may well
  find something the shape is wrong about. **Add a key in Settings and try
  "exams from next week for five days" to close that.**
- **No retry or backoff.** A failed call degrades to the manual path rather
  than retrying. Deliberate for now — retrying a request the user is waiting on
  mostly makes the wait longer — but rate limiting would be worth handling
  properly once there is real usage.
- **`generateExplanationMessage` is written and tested but not yet called from
  the UI.** The guilt-trip card and proposal card still render the engine's
  templated text directly. Wiring the generated version in is a small change;
  it was left out because with no key configured it would be untestable, and
  shipping an unexercised path felt worse than shipping a smaller one.
- **Still emulator only.** Unchanged, and still the Phase 8 concern.

---

## Phase 8 — Notifications, Alarms, Widgets & Lock Screen

**Status: COMPLETE** (clean build passes, 577 tests pass, verified on a real
phone under Doze with the screen off, committed)

### Why this section is longer than the others

Every phase before this one could be judged by reading code and running tests.
This one cannot. An alarm that does not arrive looks exactly like an alarm that
was never set. So this section leans much harder on device evidence, and where
the evidence contradicted what the code appeared to do, the device won.

Four real defects came out of that, and none of them could have been caught by
a unit test. One of them — the last — would have made every alarm in the app
stop working permanently after the first reboot. They are written up in full
below, because finding them is the whole point of the phase.

The device is a **vivo iQOO Z7 5G (I2207), Android 15 / API 35** — which is
fortunate, because it is one of the aggressive-battery-management OEMs the plan
names, and therefore a harder test than a Pixel would have been.

### Deliverables checklist

| # | Deliverable | Result | Evidence |
| --- | --- | --- | --- |
| 8.1 | Exact alarms for time-critical items | **PASS** | `AlarmScheduler.arm` picks one of three APIs by criticality. All three observed on hardware: `setAlarmClock` (`flags=0x3`, with an `Alarm clock:` block), `setExactAndAllowWhileIdle` (`flags=0x9`, `exactAllowReason=permission`), and `setWindow` on the emulator before exact-alarm permission was granted |
| 8.2 | …for interrupts | **PASS** | `interruptAlarm()` builds a `CRITICAL` / `INTERRUPT` alarm. The coordinator holds it across replans so an unrelated edit cannot silently drop it |
| 8.3 | …for the sleep hard stop | **PASS** | `dumpsys alarm` on the phone reported it as **`Next wake from idle`** — the platform's own name for the alarm that will bring the device out of Doze |
| 8.4 | …for task start times | **PASS** | One alarm per placement, at the start of its **first** slice. Split work gets one alarm, not one per slice: three buzzes for one task is how an app teaches you to silence it |
| 8.5 | WorkManager job for the daily replanning check | **PASS** | `DailyReplanWorker`, periodic 1 day with a 3h flex window, targeted at 04:00, `ExistingPeriodicWorkPolicy.KEEP` |
| 8.6 | WorkManager for less time-critical reminders | **INTERPRETED — see C** | The daily replan is the only worker. Every reminder this app has is tied to a specific minute, which WorkManager cannot promise |
| 8.7 | Home screen widget (Glance): current/next task | **PASS** | Placed on a launcher and photographed rendering `Reading 10 / 2m left · until 22:31 / Next: Grammar 26 at 22:31` |
| 8.8 | …time remaining | **PASS** | `NowNext.remainingLine()`, six unit tests in `NowNextTest` |
| 8.9 | …quick mark-done action | **PASS** | Tapped on a device. Database afterwards: `Reading 10 / DONE / 9` and `Grammar 26 / DONE / 25`, with matching `ON_TIME` rows in the adherence log |
| 8.10 | **Lock screen widget** | **INTERPRETED — see A** | Android has had no lock screen widget since API 21. Implemented as `OngoingNote`. Confirmed live on the phone: `channel=mend_ongoing flags=ONGOING_EVENT|ONLY_ALERT_ONCE category=status vis=PUBLIC actions=1` |
| 8.11 | Notification channels tuned by criticality | **PASS** | Four channels. On the phone the wind-down arrived on `mend_ambient` and the hard stop on `mend_critical` — the split is real at delivery time, not only at creation |
| 8.12 | Document and handle OEM battery management | **PASS** | `OemGuidance` covers vivo/iQOO, Xiaomi/Redmi/POCO, OPPO/realme/OnePlus, Huawei/Honor, Samsung. The vivo entry rendered on the test phone and its settings shortcut resolved. **And the guidance turned out to be literally correct — see the A/B below** |
| 8.13 | Manual test pass on a real device: **screen off** | **PASS** | Both alarms fired with `mWakefulness=Asleep`, 9s and 1s after their targets |
| 8.14 | …**app killed** | **PARTIAL — see below** | Verified with the app backgrounded and eligible for reclaim; `am kill` would not evict it while its activity was still in the task stack. A genuine `force-stop` is untested, and Android cancels alarms on force-stop by design |
| 8.15 | …**Doze mode active** | **PASS for the normal tier, PARTIAL for the critical one** | The wind-down fired in full deep Doze (`deviceidle get deep = IDLE`), unplugged, screen off. The hard stop fired with the screen asleep but the device only in light idle — see the note under the table |

### The A/B that matters

The same alarm, the same phone, the same Doze state — the only difference being
Android's battery exemption:

| Run | Battery exemption | `setExactAndAllowWhileIdle` wind-down | `setAlarmClock` hard stop |
| --- | --- | --- | --- |
| First, 22:35 | **Removed** | **Never arrived.** `dumpsys alarm` recorded `Reason=frozen` against it — the process was frozen and broadcast delivery deferred, including through an `IDLE_MAINTENANCE` window | not observed (USB dropped mid-test) |
| Second, 22:50 / 23:05 | **Granted** | **Fired at 22:50:09** — 9s after its 22:50:00 target, with `deviceidle get deep = IDLE` | **Fired at 23:05:01** — 1s after its 23:05:00 target |

One honesty note on that last cell. The hard stop fired with the screen asleep
and the device in `INACTIVE` — light idle — not deep. Restoring the phone's
battery state mid-session left it unwilling to re-enter deep idle before the
alarm came due, and I was not willing to hold someone's personal phone in forced
Doze for another cycle to get a tidier number. So: the **weaker** of the two
APIs is the one verified under full deep Doze, and the stronger one is verified
only under light idle. The platform's own view of the stronger one is in its
favour — `dumpsys alarm` listed it as `Next wake from idle` — but that is
supporting evidence, not the measurement, and it is worth one more run if you
want it airtight.

That is the whole argument for the reliability screen in Settings, measured
rather than assumed. The battery exemption is not advice on this phone; it is
the difference between an alarm and no alarm. The OEM autostart setting sits one
layer beyond even that, and Mend genuinely cannot check it — which is exactly
what the Settings text says.

### Four defects the device found

**1. Two components fighting over the alarm set.** The Today screen and the
coordinator were each computing a plan and applying it. With logging on they can
be seen cancelling one another's alarms in turn —

```
armed 3, cancelled 4, 0 unchanged     (thread A)
armed 4, cancelled 3, 0 unchanged     (thread B)
```

— a second apart, leaving the stored set describing neither. Every unit test
passed throughout; the symptom in real life is an alarm that sometimes does not
arrive.

*Fix:* the coordinator is now the only writer. Callers say "the day changed";
they do not hand in a plan, because a plan computed a moment ago can undo a
newer one. Read, diff, arm and store is one critical section under a `Mutex`.
The Today screen replans on a **signature** of the alarm-relevant state, so a
dismissed warning or an opened dialog no longer rebuilds the day. Re-tested on
the emulator: `armed 2, cancelled 2, 0 unchanged`, then `plan unchanged; 2 still
armed`.

**2. Changing your bedtime did not move the bedtime alarm.** `diffAlarms`
compared plans **by id**. A task alarm's id carries its start time, so moving a
task changes the id and the diff notices. The bedtime alarms are identified by
date alone — `sleep-stop-2026-09-13` — so an edited bedtime kept the id and
changed only the time, and the diff called it "already armed". Observed
directly: bedtime changed from 23:20 to 23:10, and the hard stop stayed sitting
at 22:50.

*Fix:* the diff compares the whole alarm. One whose id survives but whose
content changed is re-armed and **not** cancelled first, because arming replaces
an alarm with the same id atomically, where cancel-then-arm leaves a window with
nothing armed at all. Two regression tests in `AlarmPlanTest`. Verified on the
phone afterwards: wind-down 22:50:00, hard stop 23:05:00, as the edit intended.

**3. The boot receiver was never going to run.** This is the bad one. Alarms do
not survive a reboot, so `BootReceiver` exists to re-plan the day on
`BOOT_COMPLETED` — and it was registered `android:exported="false"`, copied from
the two receivers above it in the manifest, where false is correct.

It is not correct here. A non-exported receiver accepts broadcasts only from
senders sharing its own uid, and `BOOT_COMPLETED` comes from the system, uid
1000. So the receiver would simply never have been called. **After the first
reboot, Mend would have gone permanently silent** — no alarms, no widget
updates, nothing, until the app happened to be opened by hand. Which is the
precise failure the whole app exists to prevent, and it would have looked like
nothing at all from the outside.

Tested rather than reasoned about, using a package replace, which the system
sends the same way:

```
exported="false"  → install -r → (no log at all)
exported="true"   → install -r → "re-arming after android.intent.action.MY_PACKAGE_REPLACED"
```

The corroborating run on the phone was inconclusive rather than confirming:
**this vivo emits no app log lines at all** — `logcat | grep Mend` returns zero
even with the app in the foreground — which is why every piece of device
evidence in this section comes from `dumpsys` rather than logcat. Worth knowing
before you try to debug anything on it.

*Fix:* `BootReceiver` is exported, with the reasoning written into the manifest
next to it. This is safe: `BOOT_COMPLETED` and `MY_PACKAGE_REPLACED` are both
protected broadcasts that only the system may send, so no other app can reach
it. `AlarmReceiver` and `DoneReceiver` stay non-exported — those are triggered
by our own `PendingIntent`s, which run as our own uid.

**4. A double tap on the widget logged two completions.** The adherence log is
append-only and the widget takes a moment to redraw, so tapping Done twice wrote
two rows — quietly overstating how much was done, which feeds adherence
pressure, which feeds the schedule. Caught by reading the database after
testing: two `ON_TIME` rows 35 seconds apart for one sub-task.

*Fix:* `pendingWork()` refuses work that is already done. Six tests in
`PendingWorkTest`.

**A fifth, found by reading rather than running:** the same receiver also
declared `LOCKED_BOOT_COMPLETED`, which cannot arrive unless the receiver is
direct-boot aware, and would be useless if it did — the database is in
credential-encrypted storage and is unreadable until first unlock. Removed
rather than left as a filter that looks like it does something. The consequence,
now written down: alarms come back at first unlock, not at power-on.

### Two bugs fixed that were not in the deliverables

**Ticking a sub-task off moved it.** `setSubTaskDone` wrote the whole row back
with a caller-supplied `orderIndex`, and both callers supplied `0`. Sub-tasks
are read back `sortedBy { orderIndex }`, so completing sub-task 25 of a lesson
silently moved it to the front — a change to the order of a sequential goal as a
side effect of ticking something off. The stored position is now kept by
default. Four tests in `SubTaskOrderTest`, and confirmed on a device: the two
sub-tasks completed through the widget kept indices 9 and 25.

**The coordinator race**, above. Both are defects rather than features, which is
why they were fixed inside this phase rather than deferred.

### Constraints checklist

| Constraint | Result | How |
| --- | --- | --- |
| Treat reliability as seriously as engine correctness | **PASS** | Four defects found and fixed, each invisible to the test suite, one of which would have killed the app permanently after the first reboot; the A/B above was measured rather than assumed |
| "Not an average buggy APK" | **PASS, with the caveat stated in the app itself** | The critical path is verified on hardware. Where the platform can still drop an alarm, Settings says so plainly instead of the app pretending otherwise |
| Engine stays pure Kotlin, no Android | **PASS** | `AlarmPlan.kt` lives in `scheduling-engine`; `grep -rn "android\." scheduling-engine/src/main/` returns nothing |
| No scheduling logic as an AI call | **PASS** | Nothing in this phase touches the AI layer |
| Nothing beyond the phase's deliverables | **PASS** | The two extra changes are bug fixes, not capabilities |

### Interpretations and decisions

**A. "Lock screen widget" is not a thing Android has.** Keyguard widgets were
removed in API 21; `widgetCategory="keyguard"` is still accepted and ignored by
every launcher. What lock screen widgets actually became is a persistent
notification, so that is what the lock screen surface is: the same content as
the widget, silent, `VISIBILITY_PUBLIC` so it is readable without unlocking,
with the same Done action. `keyguard` was dropped from the widget's categories
rather than left in as a claim the app cannot honour. **If you wanted something
else here, this is the decision to push back on.**

**B. It is deliberately not a foreground service.** Holding one all day to draw
a line of text is precisely the battery liability this phase is meant to avoid,
and it would hand every OEM battery manager a reason to kill the app — which
would take the alarms with it.

**C. Routine reminders stayed on AlarmManager.** A wind-down warning at 22:50 is
not a wind-down warning at 23:20, and WorkManager cannot promise a minute. The
daily replan, which only needs a morning, is the job that genuinely suits a
worker, and that is what it got.

**D. The interrupt alarm lives in the coordinator, not the caller.** It is not
part of the planned day, so armed separately it would be lost at the next
replan. It is held and merged into every plan until cleared or past.

**E. Notification permission is asked for once, on first launch, and the answer
is not acted on.** A refusal is recoverable: Settings reports the real state and
offers the way back.

**F. `setAlarmClock` is spent sparingly.** It is the only genuinely Doze-exempt
alarm API and it costs a permanent alarm icon in the status bar. Only the
bedtime hard stop and emergency interrupts get it. Making every task start an
alarm clock would work, and would make the app insufferable.

### Test results

`./gradlew build` from clean:

- **scheduling-engine: 419 tests, 0 failures**
- **app: 158 tests, 0 failures**
- **TOTAL: 577 tests, 0 failures**, lint clean, debug and release APKs produced

New this phase: `AlarmPlanTest` (engine), and `NowNextTest`, `SubTaskOrderTest`
and `PendingWorkTest` (app).

Worth saying plainly: **the test suite did not find any of the four defects
above, and could not have.** Three of them were concurrency or platform
behaviour, and the fourth was a single word in a manifest. That is the argument
for this phase's device work, and the argument for doing the same in Phase 9
rather than trusting a green build.

APK: debug 18.8 MB, release (unsigned, unshrunk) 12.6 MB. The growth over
Phase 7's 16.6 MB is Glance and WorkManager.

### New open questions

**16. The lock screen surface is an interpretation, not the deliverable.**
Decision A above. Android has no lock screen widgets to build, so this is a
persistent notification instead. Say if you wanted something else.

**17. Two phone settings Mend cannot set, and one it cannot even read.**
Android's battery exemption is requestable and now demonstrably load-bearing on
your phone. The vivo autostart setting under Settings → More settings →
Permission management has no API at all: Mend cannot detect it, request it, or
verify it, and can only tell you where it lives. If alarms go quiet after a few
days of not opening the app, that is the first thing to check.

### Not done in this phase

- **A genuine `force-stop` test.** Android cancels an app's alarms when the user
  force-stops it, by design, and nothing can re-arm them until the app is opened
  again — no receiver fires either. Worth knowing, not worth working around.
- **No automated test of the widget's rendering.** Glance's testing artifact
  needs Robolectric, which this module does not have. The content it draws is
  unit tested; the drawing was verified by placing it on a launcher.
- **Timezone and DST are untested.** Alarms are stored as `LocalDateTime` and
  converted at arming time, so a timezone change between arming and firing would
  move them. The plan puts this in Phase 9's QA pass; flagged here because this
  is the phase that made it matter.
- **The live Anthropic round trip is still unexercised.** Unchanged from
  Phase 7, and still the oldest open thread.
- **Widget preview image is the app icon.** Functional, not attractive.
