# Mend — Second-by-Second Adaptive Life Scheduler

> A personal, phone-first task/schedule manager that builds and continuously
> re-negotiates a tight, realistic daily timetable — covering everything from
> brushing teeth to exam-prep deadlines — using a deterministic scheduling
> engine with a thin AI layer for language understanding, not decision-making.

This document is the master plan. It is meant to be worked through **phase by
phase**, each phase producing a working, testable increment. Each phase ends
with a ready-to-paste prompt block for Claude Code. Do not skip ahead — later
phases assume earlier ones are solid, tested, and committed.

---

## 0. Core Objectives (why this exists)

1. Replace vague to-do apps with a system that understands **real constraints**:
   fixed college/mess/gym timings, comfortable pace on goals, and genuine
   deadlines with IRL consequences.
2. Produce a **second-by-second timetable** covering the entire day — habits,
   goals, static blocks, emergencies — not just a task list.
3. **Adapt dynamically**: when reality changes (exam announced, task missed,
   emergency appears), the app proposes a new plan; it never silently mutates
   your day without consent.
4. Actively **protect sleep** — treat bedtime as a protected block that can
   compete with and even override low-priority work, since fixing chronic
   late-night work is an explicit personal goal of this project, not a
   nice-to-have.
5. **Guilt-trip responsibly** — only for tasks flagged as carrying real
   consequences, using actual adherence history, not vague nagging.
6. Ship as a **lightweight, native, offline-first Android app** with home/lock
   screen widgets and reliable notifications/alarms, good enough in quality
   and stability to be released publicly as an open-source APK later.

## 1. Non-Negotiable Constraints

- **Deterministic scheduling core.** All priority scoring, slot allocation,
  and reshuffling logic runs in plain, testable code — never an LLM call.
  AI is used only for (a) parsing natural-language input into structured
  data, (b) generating human-facing explanation/guilt-trip messages, and
  (c) interpreting fuzzy feedback ("that felt too aggressive") into a
  parameter nudge. The engine must work fully offline; AI features degrade
  gracefully without network.
- **Every schedule change is a conversation**, not a silent mutation:
  `accept change / decline change / propose different change (free text)`.
  The one exception is a genuine emergency interrupt (see Phase 4), which
  still requires acknowledgment, just not prior negotiation.
- **New schedules take effect tomorrow**, not retroactively — today is never
  upended mid-day except by a true one-shot emergency interrupt.
- **Static blocks are (mostly) untouchable.** College, mess, gym hours are
  constraints that carve out free time, not tasks competing for it.
- **Habits (brushing, water, wake time) carry no priority score.** They are
  fixed timeline entries. If the day is packed, they are silently skipped,
  never reshuffled or guilt-tripped.
- **Sleep is a protected block**, closer in behavior to an interrupt-tier
  item than a habit — see Phase 4/8.
- **No feature ships half-working.** Prefer a smaller feature set that is
  fully reliable (accurate alarms, no missed notifications, no crashes) over
  a large one that occasionally fails — this app's entire value proposition
  depends on being trustworthy enough to actually obey.
- **Lightweight and native.** No heavyweight cross-platform runtime if it
  compromises widget/lock-screen/alarm reliability or battery use.
- **No AI attribution anywhere in the repository, ever.** No AI co-author or
  collaborator trailers in commits, no AI mentions in commit messages,
  README, CHANGELOG, code comments, or any other repo artifact. Claude Code
  must not add `Co-Authored-By: Claude` or similar trailers — disable this
  explicitly in local git/Claude Code config before the first commit (e.g.
  ensure commit templates and any Claude Code settings that auto-append
  co-author trailers are turned off), and check `git log` periodically to
  confirm none have slipped in. This applies at every phase, not just setup.

## 2. Recommended Tech Stack (Android, Phase 1 target)

- **Language/UI:** Kotlin + Jetpack Compose
- **Widgets (home + lock screen):** Glance (Jetpack Compose for widgets)
- **Local storage:** Room (SQLite) — fully offline-first; the schedule must
  be usable with zero network connectivity
- **Background work / reminders:** `AlarmManager` (exact alarms for
  time-critical interrupts) + `WorkManager` (deferrable background checks,
  daily replanning jobs)
- **Architecture:** MVVM + a clean separation between:
  - `SchedulingEngine` (pure Kotlin, no Android dependencies — unit-testable
    in isolation)
  - `AiBridge` (thin interface to an LLM API for NLP parsing / message
    generation only — swappable, mockable, never load-bearing for core logic)
  - UI layer (Compose) that only ever displays what the engine produces
- **AI provider:** Anthropic API (Claude) via a thin network layer, called
  only for the narrow AI responsibilities defined in Section 1. Never called
  synchronously in a path that blocks an alarm from firing.
- **Desktop (later, out of scope for v1):** revisit once Android is stable —
  likely Kotlin Multiplatform to reuse `SchedulingEngine`, or a separate
  client against a shared local data format.

## 3. Data Model (reference — implement in Phase 1)

```
Task (base)
  id, title, type: STATIC_BLOCK | GOAL_TASK | HABIT | EMERGENCY_ONESHOT | PROTECTED_RECOVERY
  consequence_type: IRL_BINDING | SOFT_GOAL | NONE   (NONE for habits)

TimeSpec
  unit_duration (15/30/60/120 min)
  flexibility: FIXED | SHRINKABLE | SPLITTABLE | MOVABLE
  window_start, window_end (for static/fixed windows, e.g. gym hours)
  required_duration_within_window (e.g. 45 of a 120 min gym window)

Goal (parent of Lessons)
  id, title, deadline, manual_importance (1-5), is_one_shot: bool
  total_lessons, lessons_completed (rollup)
  pileup_risk: bool
  delay_negotiation_eligible: bool (derived)
  negotiated_delay_limit: nullable duration

Lesson (child of Goal)
  id, goal_id, order_index, status
  sub_tasks: [SubTask]   -- template-driven (reading/writing/listening/etc.)

SubTask
  id, lesson_id, type (reading/writing/listening/custom), est_duration
  pace_comfortable (per sub-task type, learned/declared)
  status: NOT_STARTED | IN_PROGRESS | DONE

AdherenceLogEntry
  task_id, date, assigned_slot, completed_at (nullable), delay_minutes
  outcome: ON_TIME | LATE | MISSED | NEGOTIATED

PriorityScore (computed, not stored)
  f(deadline_proximity, manual_importance, adherence_trend, weights)

Weights (per user, adaptive)
  w_deadline, w_importance, w_adherence_trend
  updated via feedback loop (Phase 6)
```

Key relationships to implement carefully:
- `Goal → Lesson → SubTask` is a real hierarchy; progress rolls up.
- Sub-tasks are **not templated across a goal**. Each lesson defines its own
  sub-tasks independently — a lesson may have zero sub-tasks (completion is
  just marking the lesson itself done), one, or several, and they need not
  repeat the same structure as any other lesson in the same goal. "Japanese:
  reading/writing/listening" was one example, not a fixed pattern.
- `AdherenceLogEntry` is append-only — never overwritten, it's the source of
  truth for behavioral pattern detection.
- `PROTECTED_RECOVERY` (sleep, and possibly meals) is structurally its own
  type — not a habit, not a goal — because it can compete with/override
  goal tasks near bedtime.

---

## Phase 0 — Project Scaffolding

**Objective:** A clean, buildable Android project skeleton with the
architectural boundaries in place, before any real feature exists.

**Deliverables:**
- Kotlin + Compose project, minimum SDK chosen deliberately (document why),
  Room configured, basic navigation shell (Today view / Tasks view / Settings
  placeholder screens, empty).
- `SchedulingEngine` module physically separated (its own Gradle module) with
  zero Android/UI dependencies, so it can be unit tested in isolation from
  day one.
- CI-friendly test setup (local unit tests runnable from terminal).
- Git repo initialized, sensible `.gitignore`, README stub stating the
  project's purpose and current phase.
- Confirm no AI co-author/collaborator trailers will be added to commits —
  check Claude Code's settings for any auto co-author trailer behavior and
  disable it before the first real commit.

**Constraints:** No feature logic yet. This phase is purely about making sure
future phases have a clean foundation — get the module boundaries right now,
they're expensive to fix later.

**Claude Code prompt:**
```
Set up a new Android project in Kotlin using Jetpack Compose. Create a
multi-module Gradle structure with an `app` module (UI, Android-specific
code) and a separate pure-Kotlin `scheduling-engine` module with no Android
dependencies, so it can be unit tested without an emulator. Set up Room in
the app module with an empty placeholder database. Create three empty
Compose screens (Today, Tasks, Settings) wired into basic navigation. Add a
README describing the project's purpose (a second-by-second adaptive
personal scheduler) and current phase. Set up local unit test scaffolding
for the scheduling-engine module. Do not add any AI co-author, collaborator,
or generated-by trailers or mentions anywhere in commits, code, or docs —
confirm no such setting is active before making the first commit. Do not
implement any scheduling logic yet — this phase is scaffolding only.
```

---

## Phase 1 — Data Model & Static Layer

**Objective:** Implement the full data model (Section 3) in Room, and get
static blocks (college, mess, gym, wake time) + habits working as fixed,
non-negotiable timeline entries. Produce a "free time map" for a given day.

**Deliverables:**
- Room entities/DAOs for `Task`, `Goal`, `Lesson`, `SubTask`,
  `AdherenceLogEntry`, matching Section 3.
- UI to input static blocks and habits (create/edit/delete), including
  window-based ones (gym open/close + required duration within window).
- A pure function in `scheduling-engine`: given a date and the set of static
  blocks/habits, compute the **free time map** for that day (list of open
  intervals). Unit test this thoroughly — it is the foundation everything
  else builds on.
- Today screen renders the static skeleton of the day (no goal tasks yet).

**Constraints:** Habits have no priority score, no deadline, and are never
reshuffled — enforce this at the type level, not just by convention. Static
blocks are excluded from bin-packing entirely; they define the grid others
fit into.

**Claude Code prompt:**
```
In the scheduling-engine module, implement the data model described in
[paste Section 3 of PROJECT_PLAN.md] using plain Kotlin data classes, and
mirror it with Room entities/DAOs in the app module. Build UI screens to
create/edit/delete static blocks (fixed time windows like college, mess,
gym — including window_start/window_end and required_duration_within_window
for cases like gym) and habits (fixed point-in-time entries like brushing,
wake time, with no priority or deadline). Implement a pure, well-tested
function `computeFreeTimeMap(date, staticBlocks, habits): List<TimeInterval>`
in scheduling-engine with unit tests covering overlaps, back-to-back blocks,
and partial-window requirements (e.g. only 45 min needed within a 2-hour gym
window). Update the Today screen to render the static skeleton of the day
using this free time map, with no goal tasks yet.
```

---

## Phase 2 — Goals, Lessons, Sub-tasks & Progress Tracking

**Objective:** Let the user define goals with a lesson/sub-task hierarchy,
track granular progress, and see it roll up.

**Deliverables:**
- UI to create a Goal: title, deadline, manual importance, total lesson
  count. For each lesson, the user defines its own sub-tasks independently
  — zero, one, or several, freely named, with no requirement that lessons
  within the same goal share a structure. A lesson with zero sub-tasks is
  simply completed by marking the lesson itself done.
- Progress UI: mark sub-tasks (or whole lessons, if no sub-tasks) done, see
  lesson completion and goal-level rollup (`lessons_completed /
  total_lessons`), plus a simple pace view (comfortable pace vs. actual
  velocity, scoped per sub-task type where sub-tasks exist).
- Still no automatic scheduling of goal tasks into free time yet — this
  phase is about correctly modeling and tracking goals, not placing them.

**Constraints:** Do not build a cross-lesson template system — sub-task
structure is per-lesson, entered freely each time. Keep sub-task types
simple and user-defined (arbitrary names, not hardcoded categories).

**Claude Code prompt:**
```
Implement Goal creation with a Lesson/SubTask hierarchy as described in
Section 3 of PROJECT_PLAN.md. Each lesson defines its own sub-tasks
independently (zero, one, or several, freely named) — do not build a
template system that applies one sub-task structure across all lessons in a
goal. A lesson with zero sub-tasks is completed by marking the lesson itself
done. Build UI to mark sub-tasks (or whole lessons, when there are no
sub-tasks) as done, and see progress roll up from sub-task -> lesson ->
goal. Add a simple pace view comparing user-declared comfortable pace
against actual completion velocity, scoped per sub-task type where
sub-tasks exist. Do not implement automatic scheduling of these tasks into
free time yet — that's the next phase.
```

---

## Phase 3 — Deterministic Priority & Scheduling Engine

**Objective:** The core engine. Given free time (Phase 1) and goal
sub-tasks with progress (Phase 2), compute a priority score for each pending
sub-task and pack them into free time to produce a full day's schedule.

**Deliverables:**
- `computePriorityScore(task, weights): Double` — pure function combining
  deadline proximity, manual importance, and adherence trend (adherence
  trend can default to neutral until Phase 6 provides real history).
- A slot-allocation algorithm (start simple: greedy — highest priority gets
  first pick of matching free intervals, respecting `flexibility`
  — `SPLITTABLE` tasks may span multiple intervals, `FIXED` tasks must fit
  a single interval or fail to schedule) that fills the free time map with
  ranked sub-tasks.
- Clear, logged reasoning for every placement decision (not user-facing yet
  — this is for debuggability: you must be able to answer "why is this
  here" from logs alone).
- Today screen now shows a full, real, second-by-second schedule: static
  blocks + habits + scheduled goal sub-tasks.
- Unit tests covering: overfull day (more tasks than time), tie-breaking
  between equal-priority tasks, splittable task spanning multiple gaps.

**Constraints:** This must stay understandable and debuggable — resist the
urge to make the allocation "smart" via heuristics that aren't traceable.
Simple, explainable rules over clever ones.

**Claude Code prompt:**
```
In scheduling-engine, implement `computePriorityScore(task, weights)`
combining deadline proximity, manual_importance, and adherence trend
(default neutral for now) into a single score, per the weighted model in
PROJECT_PLAN.md Section 1/3. Implement a greedy slot-allocation algorithm
that takes the free time map from Phase 1 and the set of pending goal
sub-tasks from Phase 2, ranks them by priority score, and places them into
free intervals respecting each task's `flexibility` (FIXED must fit one
interval or fail to schedule and be reported as unscheduled; SPLITTABLE may
span multiple intervals; SHRINKABLE may reduce duration under pressure).
Every placement decision must be logged with its reasoning for
debuggability. Update the Today screen to render the full computed schedule:
static blocks + habits + scheduled sub-tasks, in time order. Write unit
tests for an overfull day, priority tie-breaking, and a splittable task
spanning multiple free intervals.
```

---

## Phase 4 — Reshuffling, Interrupts & Protected Sleep

**Objective:** Handle the three "change" mechanics: negotiated reshuffling
(propose/accept/decline/negotiate), true emergency interrupts, and
sleep-as-protected-block.

**Deliverables:**
- **Reshuffle flow:** triggered by a schedule-invalidating event (deadline
  added/changed, task missed, manual edit). Engine computes a proposed new
  schedule for **tomorrow onward**, surfaces it with exactly three options:
  Accept / Decline / Propose different (free text, routed through AI parsing
  in Phase 7 — stub with simple manual override for now).
- **Emergency interrupt:** implement the narrow `EMERGENCY_ONESHOT` category
  — `is_one_shot == true AND (deadline == today OR no future free slot
  exists before deadline)`. When this condition is met, the task becomes
  the mandatory next action, with an assigned time or negotiated delay
  limit; overruns trigger an explicit "here's what you're now losing today"
  message. Interrupts claim the next free interval only — they do not cut
  into static blocks.
- **Protected sleep block:** implement `PROTECTED_RECOVERY` type for
  bedtime. Decide and implement: does an overrunning task get forcibly
  deferred to tomorrow as bedtime approaches (hard stop), or strongly
  nudged? Log actual sleep time vs. target as its own adherence stream.
- **Delay negotiation tier:** implement `pileup_risk` + skip-count logic —
  only tasks that are fragile (already skipped once/twice, sequential/
  non-parallelizable) become eligible for a `negotiated_delay_limit`, which
  is a last-resort fallback, not a default option.

**Constraints:** New schedules always take effect tomorrow, never
retroactively — except the interrupt itself, which is real-time by
definition. Reshuffle prompts must not fire so often they become noise —
batch same-day triggers into a single proposal where reasonable.

**Claude Code prompt:**
```
Implement three mechanics in scheduling-engine + UI, per PROJECT_PLAN.md
Phase 4:
1. Reshuffle flow: a function that detects schedule-invalidating events
   (new/changed deadline, missed task, manual edit) and produces a proposed
   schedule for tomorrow onward. Surface it in the UI with exactly three
   actions: Accept, Decline, Propose Different (free-text input stored for
   now, full NLP parsing comes in a later phase).
2. Emergency interrupt handling for EMERGENCY_ONESHOT tasks meeting
   is_one_shot && (deadline is today || no future free slot exists before
   deadline): make it the mandatory next action with an assigned time or
   negotiated delay limit, and implement the overrun message that explains
   what today's schedule loses as a result. Interrupts must only claim free
   time, never static blocks.
3. PROTECTED_RECOVERY handling for sleep: implement a hard-stop or strong-
   nudge mechanism (implement hard-stop, but make the threshold configurable)
   as bedtime approaches, and log actual vs. target sleep time as an
   adherence stream separate from tasks.
Also implement pileup_risk + skip-count tracking so only fragile,
non-parallelizable tasks become eligible for a negotiated_delay_limit as a
last-resort fallback state, not a first option.
```

---

## Phase 5 — Adherence Logging & Guilt-Trip Triggers

**Objective:** Turn the append-only `AdherenceLogEntry` stream into real
behavioral signal, and implement the guilt-trip trigger for IRL-binding
tasks.

**Deliverables:**
- Full adherence logging wired into every task completion/miss event across
  the app (not just goal tasks — interrupts and protected-recovery too).
- Rolling adherence rate and delay-pattern computation per task and per
  task-type.
- Guilt-trip trigger: fires specifically for `consequence_type ==
  IRL_BINDING` tasks where projected pace (based on `adherence_log` /
  `velocity_trend`) will miss the deadline. Message includes: progress so
  far, days remaining, why the projection says you'll miss it, and a
  request to permission to reshuffle (routes into Phase 4's reshuffle flow).
- Explicitly exclude habits and soft goals from this trigger.

**Constraints:** The trigger must be based on actual logged history and
projected velocity — not a vague "you haven't done this in a while."
Precision here is what keeps this feature from becoming just noise.

**Claude Code prompt:**
```
Wire AdherenceLogEntry creation into every task completion/miss event
app-wide (goal sub-tasks, interrupts, protected-recovery/sleep). Implement
rolling adherence rate and delay-pattern calculations per task and per
task-type in scheduling-engine, with unit tests. Implement the guilt-trip
trigger: it must fire only for tasks with consequence_type == IRL_BINDING,
based on comparing projected completion pace (derived from adherence_log
and velocity_trend) against the deadline. The resulting message must state
current progress, days remaining, why the projection misses the deadline,
and end with a request to reshuffle that routes into the Phase 4 reshuffle
flow. Habits and SOFT_GOAL tasks must never trigger this.
```

---

## Phase 6 — Adaptive Weight Tuning

**Objective:** Let priority weights (`w_deadline`, `w_importance`,
`w_adherence_trend`) start fixed and adjust over time based on lightweight
feedback.

**Deliverables:**
- After a reshuffle/prioritization decision, a simple feedback prompt
  ("too aggressive / too lax / just right" or thumbs up/down).
- A small, explainable adjustment rule (e.g. N consecutive "too aggressive"
  responses nudges `w_deadline` or `w_adherence_trend` down by a fixed
  step, with bounds). Explicitly avoid building a full ML model — keep this
  a simple, inspectable heuristic.
- Settings screen showing current weight values and recent adjustment
  history, so the user can see and manually override the learned weights
  at any time.

**Constraints:** Keep this debuggable — a user (or you, six months later)
must be able to look at the weight-adjustment log and understand exactly
why a weight changed.

**Claude Code prompt:**
```
Implement a post-reshuffle feedback prompt (too aggressive / too lax / just
right) and a simple, bounded, rule-based weight adjustment mechanism in
scheduling-engine that nudges w_deadline, w_importance, or w_adherence_trend
based on a small streak of consistent feedback (e.g. 3 consecutive "too
aggressive" responses -> reduce the relevant weight by a fixed step, within
defined min/max bounds). Do not implement a full ML model. Log every
adjustment with its cause. Add a Settings screen showing current weights and
a readable history of adjustments, with manual override capability.
```

---

## Phase 7 — AI Layer: NLP Parsing & Message Generation

**Objective:** Add the Anthropic API integration for exactly the two
AI responsibilities defined in Section 1 — nothing more.

**Deliverables:**
- `AiBridge` module: a thin, swappable interface with two methods —
  `parseNaturalLanguageInput(text): StructuredConstraint` (e.g. "exams from
  next week for 5 days" → structured date range + affected task
  adjustments) and `generateExplanationMessage(context): String` (guilt-trip
  and reshuffle explanation copy).
- Wire the "Propose Different" free-text option (Phase 4) through
  `parseNaturalLanguageInput`, with the resulting structured change still
  going through the deterministic engine and the normal accept/decline flow
  — the AI never applies a change directly.
- Graceful offline degradation: if the API is unreachable, fall back to a
  manual structured-input form and a plain templated message instead of a
  generated one. The app must remain fully usable offline for its core
  scheduling function.
- Cost/latency awareness: batch or debounce AI calls where reasonable; never
  block a notification/alarm firing on a network call.

**Constraints:** The AI must never be in the path of core scheduling
decisions — only translation in, and phrasing out. Validate/sanitize
AI-parsed output against hard constraints (deadlines, static blocks) in the
deterministic engine before ever presenting it to the user.

**Claude Code prompt:**
```
Implement an AiBridge module in the app layer with two responsibilities
only, per PROJECT_PLAN.md Section 1 and Phase 7: parseNaturalLanguageInput
(turns free text like "exams from next week for 5 days" into a structured
constraint object) and generateExplanationMessage (produces guilt-trip and
reshuffle explanation copy from structured context). Use the Anthropic API.
Wire the Phase 4 "Propose Different" free-text option through
parseNaturalLanguageInput, ensuring the resulting structured change is
validated against hard constraints by the deterministic engine and still
goes through the normal accept/decline UI — the AI must never apply a
change directly. Implement graceful offline fallback: manual structured
input form and templated (non-generated) messages when the API is
unreachable. Ensure no AI call ever blocks alarm/notification firing.
```

---

## Phase 8 — Notifications, Alarms, Widgets & Lock Screen

**Objective:** Make the schedule actually reach the user reliably,
including on the lock screen, without being a battery/reliability liability.

**Deliverables:**
- Exact alarms (`AlarmManager`) for time-critical items: interrupts, sleep
  hard-stop, task start times.
- `WorkManager` jobs for the daily replanning check and less time-critical
  reminders.
- Home screen + **lock screen widget** (Glance) showing: current/next task,
  time remaining, quick mark-done action.
- Notification channels tuned by task criticality (emergency interrupt vs.
  routine reminder) so Android/OEM battery optimization doesn't silently
  suppress the important ones — document and handle OEM-specific
  aggressive battery management (a known Android pain point) explicitly.
- Manual test pass across at least one real device confirming alarms fire
  reliably with the screen off / app killed / Doze mode active.

**Constraints:** This is the phase most likely to produce "it worked in
testing but not in real life" bugs — treat reliability here as seriously as
correctness in the scheduling engine. This is also core to the "not an
average buggy APK" goal.

**Claude Code prompt:**
```
Implement exact-alarm scheduling (AlarmManager) for time-critical items
(interrupts, sleep hard-stop, task start times) and WorkManager jobs for
daily replanning and routine reminders. Build a home screen widget and a
lock screen widget using Glance showing the current/next task, time
remaining, and a quick mark-done action. Set up notification channels
differentiated by criticality so emergency interrupts are not suppressed by
battery optimization. Add handling/documentation for common OEM aggressive
battery management issues (e.g. requesting battery optimization exemption
with a clear user-facing explanation). Verify alarms fire reliably with
screen off, app killed, and Doze mode active.
```

---

## Phase 9 — Hardening, Testing & v1 Release

**Objective:** Get this to a genuinely stable, releasable first version.

**Deliverables:**
- Full pass of unit tests on `scheduling-engine` (target high coverage on
  priority scoring, slot allocation, reshuffle, interrupt logic — these are
  the trust-critical paths).
- Manual QA pass across edge cases: overfull days, back-to-back deadline
  collisions, missed sleep block, app killed mid-interrupt, timezone/date
  rollover at midnight.
- Crash reporting hooked in (local logging at minimum) so real-world issues
  after release are diagnosable.
- Performance pass: cold start time, widget update latency, battery impact
  over a full day of normal use.
- Versioning scheme decided (e.g. semver) and a `CHANGELOG.md` started, in
  preparation for labelled releases.
- README brought up to date for a public/open-source audience: what the app
  does, architecture overview, how to build, current limitations.

**Constraints:** Do not release v1 until the trust-critical paths (alarms,
interrupts, sleep protection) have been manually verified on a real device
over multiple real days, not just simulator runs.

**Claude Code prompt:**
```
Do a hardening pass: raise unit test coverage on scheduling-engine
especially around priority scoring, slot allocation, reshuffle, and
interrupt logic. Add local crash/error logging. Profile and address cold
start time, widget update latency, and battery impact. Set up a CHANGELOG.md
and semantic versioning. Update the README for a public open-source
audience: purpose, architecture overview (deterministic engine + thin AI
layer), build instructions, and known limitations. List remaining manual QA
items to verify on a real device (overfull days, deadline collisions, missed
sleep block, app killed mid-interrupt, midnight rollover) before tagging v1.
```

---

## Phase 10 — Future Work (not started yet)

Tracked here so it isn't lost, explicitly out of scope until v1 is stable:

- Desktop client (likely Kotlin Multiplatform, reusing `scheduling-engine`)
- Public open-source release prep: license choice, contribution guidelines,
  stripping any personal data/config from the repo
- Possible calendar/timetable import (observe static blocks automatically
  instead of manual entry)
- Expanded AI role only if Phase 7's narrow scope proves genuinely
  insufficient in practice — revisit deliberately, don't drift into it

---

## How to Use This Document

Work phase by phase, in order. For each phase:
1. Paste the phase's "Claude Code prompt" block into Claude Code.
2. Review the result against that phase's Deliverables and Constraints.
3. Commit only when it's genuinely working — don't carry broken foundations
   into the next phase.
4. Update this file if a phase's scope changed during implementation, so it
   stays an accurate record, not just an initial plan.
