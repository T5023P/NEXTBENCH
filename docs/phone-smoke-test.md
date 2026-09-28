# Real-device smoke test (release candidate)

Focused, ~30-minute smoke test of the 20 highest-risk flows on a physical phone — not a QA audit.
Goal: confirm the release candidate does not crash, and capture decisive evidence if it does.

**APK:** `app/build/outputs/apk/debug/app-debug.apk` · package **`com.nextbench.app`** ·
versionName `1.0.0` (versionCode 1)

## Setup

```bash
adb devices                       # confirm the phone is attached and authorized
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb logcat -c                     # clear the log BEFORE testing
```

Keep one terminal running the live crash filter during the whole session:

```bash
adb logcat -v time *:E AndroidRuntime:E | grep -iE "nextbench|fatal|AndroidRuntime"
```

Capture app logs only (smaller files): `adb logcat -d -v time > logs.txt` works, but the filtered
variant is what you read first.

## The 20 flows

Each flow: **expected** result, and what counts as a **failure** (crash = any `AndroidRuntime`
FATAL dialog, or the process disappearing from `dumpsys activity top`).

| # | Flow | Expected | Failure means |
|---|------|----------|---------------|
| 1 | Fresh install | Install succeeds; no immediate crash on first process start | Install-time or init crash (Google-services init, Hilt graph) |
| 2 | App launch | Splash → sign-in/welcome renders; no white screen hang | Application/Activity crash, theme or Compose init failure |
| 3 | Sign in (email OTP + Google) | OTP arrives; sign-in lands on home; Google popup completes | Firebase Auth path crash, OTP screen state crash |
| 4 | Home/feed loading | Feed renders with content or empty state | Firestore query crash, null field in `UserData` mapping |
| 5 | Open another user's profile | Public profile renders (name, school/city, posts) | Cross-user read path, blocked/blocked-by state crash |
| 6 | Chat list | Conversations render with previews | Message preview mapping crash |
| 7 | Open a chat | Transcript renders; composer focused | Room read, participant lookup crash |
| 8 | Send a message | Message appears; Cloudinary upload works for image/file attachments | Write path, upload progress crash |
| 9 | Receive notification (second account sends) | Notification arrives | FCM parse crash (background handler) |
| 10 | Open notification | Lands on the right room | PendingIntent / nav-args crash |
| 11 | Block, then unblock | Blocked user disappears from feeds/chat; unblock restores | Block write/read crash, rules denial surfaced as crash |
| 12 | Report a user / post / product | Sheet opens, submit succeeds, confirmation shown | Report-sheet state crash |
| 13 | Privacy settings incl. hide-last-seen | Toggle persists; last-seen hidden from other account | Write denied (rules) or toggle not persisted |
| 14 | Verification screens (if account allows) | Upload + submit completes or fails with readable message, never silent | File/URI handling crash |
| 15 | Settings / profile edit | Name/about/city edit persists; profile picture uploads | Profile update path crash |
| 16 | Account deletion entry point (use a THROWAWAY account) | Sheet opens; wrong confirmation refused; cancel exits cleanly | Deletion-sheet state crash |
| 17 | Background → foreground | Return restores the same screen and state | Lifecycle crash, listener leak |
| 18 | Kill app → relaunch | Restores to a sane screen; no stale-state crash | SavedState / cached-listener crash |
| 19 | Airplane mode on → off | Friendly offline errors; recovery without restart | Unhandled network exception crash |
| 20 | Logout → login again | Session clears; sign-in works again | Sign-out listener crash |

Failure that is NOT a crash but still a blocker: any action that fails silently, shows a raw
technical error string, or requires an app restart to recover.

## Crash-capture procedure (do this in order, immediately)

```bash
# A. before testing (once per session)
adb logcat -c

# B. reproduce the crash

# C. capture the full buffer immediately, before it rotates
adb logcat -d -v time > nextbench-crash.txt

# D. capture UI and package state
adb shell dumpsys activity top > nextbench-activity-top.txt
adb shell dumpsys package com.nextbench.app > nextbench-package.txt
```

Also note the **timestamp** and the **exact screen/action** from one second before the crash.
A screen recording (`adb shell screenrecord`) of the reproduction is gold if you can manage it.

**Where to send the files:** put `nextbench-crash.txt`, `nextbench-activity-top.txt`,
`nextbench-package.txt` (plus any recording/screenshot) in the project root as
`nextbench-crash.txt` and tell the engineering agent/lead — do not paste raw logcat into chat.
The handoff doc (`docs/HANDOFF.md` §H) is the reading list the agent will follow.

## If it crashes — how the analysis will be done

No guessing at root cause. The procedure:

1. Find the **first** `AndroidRuntime: FATAL EXCEPTION` in `nextbench-crash.txt`; everything above
   it is context, everything after is secondary.
2. Classify the top frame: app code (`com.nextbench.*`), Firebase SDK, permissions, lifecycle,
   navigation, network, or device-specific (OEM-specific classes in the trace).
3. If app code: point at the **exact class/method/file** and propose the smallest evidence-based
   fix, targeted tests included. If not app code: report the exact SDK/version frames and stop —
   no speculative workarounds.

## Report template (fill in and return)

```
Device:
Android version:
APK version:            1.0.0 (1) debug
Install result:         PASS / FAIL
Launch result:          PASS / FAIL
Login result:           PASS / FAIL
Home result:            PASS / FAIL
Profile result:         PASS / FAIL
Chat result:            PASS / FAIL
Push notification result: PASS / FAIL
Block/report result:    PASS / FAIL
Settings result:        PASS / FAIL
Background/foreground result: PASS / FAIL
Logout/login result:    PASS / FAIL
Crash reproduced:       YES / NO
```

If YES, add:

```
- exact reproduction steps:
- timestamp:
- screen/action immediately before crash:
- logcat file:            nextbench-crash.txt
- screenshot/video:
- flows that still passed (from the table above):
```

Anything below 13 that fails but doesn't crash: note the flow number and what the screen showed —
that is enough for triage without another audit.
