# Mend — Build Log

Session scope: Phase 0 through Phase 3 only. Phase 4+ not attempted.

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
- Android SDK licenses were accepted via `sdkmanager --licenses`.

**See "Needs your input" — item 1.** Both were judgement calls I made to avoid
being blocked all night; flagging them rather than burying them.

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
