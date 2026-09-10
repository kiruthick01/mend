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
