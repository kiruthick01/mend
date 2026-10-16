# Mend — Production Plan (Play Store)

Status: **draft for review.** Nothing here is built. It sits after
`PROJECT_PLAN.md` Phase 9 (hardening) and replaces the vague "Phase 10 future
work" list with a concrete route to a public Play Store release.

## 0. The honest starting point

Mend is an **offline-first, on-device** app. The schedule, the database, the
alarms and the adherence log all live on the phone. There is no server, and the
design deliberately keeps it that way: the engine works with no network.

That changes what "production ready" means, and it is worth stating plainly
before planning anything:

- **Docker, auto scaling and hosting do not apply to the app itself.** An APK
  or AAB runs on users' phones; there is nothing to containerise or scale.
- They **do** apply to two things we may add around it: a small backend
  (section 4) and the build pipeline (section 3).
- Adding a backend is a cost and a liability (uptime, privacy, security
  patching, money). It should be added only where something concrete forces it.

So this plan is split by what is genuinely needed, not by a checklist of
infrastructure words.

## 1. Blockers specific to this app (decide first)

These can stop a Play Store release outright, so they come before polish.

### 1.1 Permissions Play will question

| Permission | Why Mend uses it | Play risk | Plan |
| --- | --- | --- | --- |
| `PACKAGE_USAGE_STATS` | Counts time in Duolingo and AnkiDroid | Restricted. Needs a Permissions Declaration Form and a core-functionality justification. | Decide whether time-in-app is core. If not, **drop it** and keep Anki and Duolingo-profile only. |
| `SCHEDULE_EXACT_ALARM` | Bedtime hard stop and interrupts must land on the minute | Allowed for user-set alarms and reminders; declaration required on newer targets. | Keep. Write the declaration around the bedtime hard stop. |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | Alarms survive Doze | Play restricts this. Only allowed when the core function breaks without it. | Probably switch to the system battery settings screen intent instead of the direct request. |
| `com.ichi2.anki.permission.READ_WRITE_DATABASE` | AnkiDroid card counts | Fine, a third-party app permission. | Keep. |
| `INTERNET` | Optional AI layer, Duolingo profile | Fine. | Keep, and disclose in the Data safety form. |

### 1.2 The Duolingo integration

It reads an **unofficial endpoint** the Duolingo website uses. It is not a
documented API, may break at any time, and may not be allowed by Duolingo's
terms. Shipping it in a store app invites a takedown request and breakage
reviews. Options, in order of safety:

1. Ship without the username path. Keep time-in-app and a manual "I practised"
   tap. (Safest.)
2. Ship it behind a clear "unofficial" label and a kill switch (needs the remote
   config from section 4.3).
3. Seek an official partnership. Unlikely to be quick.

Recommendation: **option 1 for the first release**, option 2 later if people
want it.

### 1.3 The AI layer and the API key

Today the user types their own Anthropic key and it is stored on the device.
That is acceptable for a technical user, and unacceptable for a general Play
audience. Two routes:

- **Bring your own key** (current). Keep, label it an advanced feature, and say
  clearly that the key is stored unencrypted-by-default in app preferences.
  Move it to the Android Keystore-backed `EncryptedSharedPreferences` before
  release regardless.
- **Hosted proxy** (section 4.1). Mend never ships a key; a backend holds it and
  rate-limits per user. Only worth it if the AI layer becomes a headline
  feature, because it turns a free feature into a running cost.

### 1.4 Target audience and policy

- Not aimed at children. Declare it.
- Health-adjacent wording ("sleep", "recovery") must stay a scheduling tool, not
  medical advice.
- Publish a privacy policy URL (section 2.3) before the first upload.

## 2. Store readiness (needed whatever else we choose)

### 2.1 Build and signing

- Switch the release build to an **Android App Bundle** (`bundleRelease`).
- Turn on R8 (`isMinifyEnabled = true`) and shrink resources; write keep rules
  for Room, WorkManager and Glance; verify with a release build on a device.
- **Play App Signing**: Google holds the app signing key, we hold an upload key.
  Generate the upload key offline, back it up in two places, never commit it.
- Real `applicationId` decision *now*. `com.mend.app` is easy to collide with and
  cannot be changed after publishing. Pick the final one before the first
  upload.
- Version scheme: semantic versioning for `versionName`, monotonic
  `versionCode`, a `CHANGELOG.md`.
- Keep `targetSdk` current. Play requires a recent target within a year of each
  Android release; budget a yearly bump.

### 2.2 Quality gates

- Unit tests already cover the engine well. Add **instrumented tests** for the
  trust-critical paths: alarms, interrupts, sleep protection, midnight rollover.
- Manual QA matrix from `PROJECT_PLAN.md` Phase 9, on at least three
  manufacturers (the OEM battery behaviour is the main real-world risk; the
  existing notes cover vivo, Xiaomi, OPPO, Huawei).
- **Pre-launch report** in Play Console, and Android vitals targets: crash rate
  and ANR rate under Play's bad-behaviour thresholds.
- Accessibility pass: TalkBack labels, touch targets, font scaling, contrast.
- Localisation decision. English first; extract all strings to resources now so
  later translation is cheap.

### 2.3 Legal and listing

- Privacy policy hosted at a stable HTTPS URL (a GitHub Pages page is enough).
- **Data safety form**: what is collected (nothing leaves the device except the
  optional AI text and the Duolingo username), why, and how it is protected.
- Content rating questionnaire, store listing text, screenshots for phone (and
  tablet if supported), feature graphic, icon.
- Support email and a public issue tracker.
- Choose and keep the open-source licence (MIT is in the repository) and add a
  third-party licences screen (`oss-licenses` plugin).

### 2.4 Release process

1. Internal testing track (you and a few testers).
2. Closed testing. New personal developer accounts must run a closed test with a
   minimum number of testers for a minimum period before production; check the
   current numbers in Play Console, they change.
3. Staged production rollout (5%, 20%, 50%, 100%) watching vitals between steps.
4. A rollback plan: halt the rollout, ship a fixed version with a higher
   `versionCode`.

## 3. CI/CD and Docker (where Docker genuinely helps)

- **GitHub Actions**: on every push, run `./gradlew build` and the engine tests;
  on a tag, build the signed AAB and attach it to a GitHub Release. Signing
  secrets live in repository secrets, never in the tree.
- **Docker for reproducible builds**: a `Dockerfile` pinning JDK 17, the Android
  SDK and Gradle, so "builds on my machine" equals "builds in CI". This is the
  real, useful Docker story for an Android app.
- Dependency updates via Dependabot or Renovate, plus Gradle dependency
  verification so a tampered dependency fails the build.
- Static checks in CI: Android lint, detekt or ktlint, and a secret scanner
  (gitleaks) so a key can never be pushed.
- Optional: Gradle Play Publisher to upload to the internal track from CI.

## 4. Backend, only if justified

Each item below is optional and independent. Do not build the backend as a
package; add a piece only when a feature needs it.

### 4.1 AI proxy (the likeliest first backend)

- A stateless HTTP service: authenticates the request, applies a per-user
  rate limit and a monthly budget, calls the model, returns the text.
- **Docker** it; host on a **scale-to-zero container platform** (Cloud Run,
  Fly.io or similar). This is the honest form of "auto scaling": it scales with
  requests and costs nothing when idle, which suits a small app.
- Auth: Play Integrity API attestation plus an anonymous per-install token, so
  only genuine installs can spend your money.
- Secrets in the platform's secret store. Structured logs, alerts on spend.
- Cost control is the real design problem: hard caps, per-user quotas, and a
  kill switch.

### 4.2 Accounts, backup and sync

- Needed only if you want multi-device or restore after reinstall.
- Cheapest path first: Android **Auto Backup** (already configured via backup
  rules) or an export/import file. A real sync service means conflict
  resolution for an append-only adherence log, auth, and privacy obligations.
  Defer until users ask.

### 4.3 Remote config and feature flags

- A small config document (Firebase Remote Config or a static JSON on a CDN) to
  disable a broken integration, notably Duolingo, without shipping an update.

### 4.4 Crash and error reporting

- Firebase Crashlytics or Sentry. Disclose it in the Data safety form, and make
  it opt-in if it sends anything identifying. Local logging is the minimum from
  Phase 9.

## 5. UI and design overhaul

Raised during review: the colour combination is "too weird". This deserves its
own pass rather than tweaks.

- Audit the current palette (the mint-on-near-black scheme) against WCAG
  contrast, and in both light and dark.
- Define a small design system: a colour role set (primary, surface, outline,
  semantic colours for done, late, missed and protected), a type scale, spacing
  and corner radii. Keep it in one theme file.
- Support Android **dynamic colour** (Material You) as the default on Android 12
  and up, with a fixed brand palette as the fallback.
- Redesign Today first (it is the main screen), then Goals, Settings, widget and
  notifications. Make the lock-screen note and widget match.
- The launcher icon is a black tile with "mend." in a monospace face. Decide
  whether that wordmark becomes the brand (then reuse it in the splash screen,
  the store listing and the Today header) or is replaced.
- Add an onboarding flow: sleep setup, notifications, exact alarms, the OEM
  battery step, and optional connected apps. The first-run experience is
  currently implicit.
- Empty and error states for every list; a settings search; tablet and
  foldable layouts if Play's large-screen guidance applies.
- Deliver as a design pass (mockups first), then implementation, then a
  screenshot test or two so the look does not drift.

## 6. Suggested order

1. **Before any public release:** section 1 decisions (permissions, Duolingo,
   key storage, final `applicationId`).
2. UI overhaul (section 5), since it changes every screenshot in the listing.
3. Release engineering: AAB, R8, signing, CI (sections 2.1 and 3).
4. Quality gates and the closed test (2.2 and 2.4).
5. Legal and listing (2.3).
6. Backend pieces, only as features demand (section 4).

## 7. Open questions for you

- Is time-in-app (usage access) core enough to defend to Play, or do we drop it?
- First release without the Duolingo username path?
- Is the AI layer a headline feature (then build the proxy) or an advanced
  toggle (then bring-your-own-key is enough)?
- Free, ads, one-time purchase or subscription? It changes the backend and the
  policy work. (Mend's "no silent rewrites, no guilt" tone argues against ads.)
- Final app name and `applicationId`.
