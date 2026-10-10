# ResQ — physical device test plan (Vivo Android 16)

Everything in this file **must be run on the Vivo**. The automated suite proves
ResQ's bookkeeping; it cannot prove what OriginOS does to a foreground service,
a locked screen or a background activity start.

---

## Execution log — 2026-10-10, Vivo V2561i (device serial redacted), Android 16 / SDK 36

Device: 1260x2800 px, density 520 (387dp wide — a **narrow** screen, and the
reason the row-layout defect below was visible at all).

### Automated results

| Check | Result |
|---|---|
| `:app:testDebugUnitTest` (forced rerun) | **PASS** — 496 tests, 0 failures, 0 errors, 0 skipped |
| `:app:assembleDebug` | **PASS** (exit 0) |
| `:app:lintDebug` | **PASS** (exit 0) — 0 errors, 35 pre-existing warnings |
| `:app:compileDebugAndroidTestKotlin` | **PASS** (exit 0) |
| `:app:connectedDebugAndroidTest` | **PASS** — 1 test, 0 failures, ran on the physical device (serial redacted) |

### Backend (probed directly, not through the app)

| Check | Result |
|---|---|
| `GET /health` | **PASS** — `{"status":"healthy"}` |
| `POST /api/emergency-resources/search` | **PASS** — 58 resources, `status=success` |

Note: the search path is `/api/emergency-resources/search`, **not** `/search`,
and `incident_type` must be one of the backend enum values
(`possible_accident`, `medical_emergency`, `fire`, `natural_disaster`,
`violence`, `other`). ResQ sends `possible_accident`, which is valid.

### Physical-device results — PASSED

| Area | Result |
|---|---|
| Back **edge-swipe gesture**, Settings→Permissions | **PASS** — returns to Settings main (was Home) |
| Back **edge-swipe gesture**, 3 levels deep (Settings→Emergency→Alert countdown) | **PASS** — unwinds one level per swipe, never gets stuck |
| Android **Back button**, settings submenu | **PASS** — same destination as the swipe |
| In-app **Back arrow**, settings submenu | **PASS** — unchanged |
| Back on the Settings **main** menu | **PASS** — closes to Home |
| Cold launch | **PASS** — `Displayed com.resq.app/.MainActivity +651ms`, no crash |
| Logcat across the whole session | **PASS** — no FATAL, no `AndroidRuntime` exception, no ANR |
| Accelerometer + gyroscope live readings | **PASS** — Diagnostics reports both "Live" |
| Emergency screen with **no location at all** | **PASS** — Reason row states "Device Location is switched off."; **Call 112**, **Refresh location** and the contact row are all present and enabled |
| Test Mode escalation sends nothing | **PASS** — logcat shows `NORMAL -> CONFIRMATION -> EMERGENCY` and **no** `SmsManager` / `TelecomManager` / `placeCall` activity |
| End-emergency confirmation + stand down | **PASS** — returns to Home, no residual emergency state |
| Location honesty | **PASS** — "Position basis: No position", "Nearby resources: Not searched"; no invented coordinates |

### Physical-device results — BLOCKED (need a human)

Every row below in sections A–S is still **NOT TESTED**. They need a consenting
test contact, the device Location toggle, or the phone unlocked and watched.

### Defects found and fixed during this audit

1. **System back gesture left Settings entirely** (the reported bug).
2. **`GlassRow` starved its own label.** The value column was given an equal
   `weight(1f)`, so an *empty* value still consumed half the row. On this 387dp
   phone that broke labels across two lines and squeezed descriptions to five
   lines. Fixed by measuring the value at its intrinsic width with a 120dp cap,
   and moving long values onto their own line beneath the label.

---

Everything below is the ORIGINAL plan text, which had never been executed.

## Safety rules — read before starting

- **Use a consenting test contact. Never 112.** 112 opens a real emergency call
  on some carriers. Use a second phone that belongs to someone who has agreed to
  be called repeatedly.
- Expect real calls to ring real people. Put the test phone on Do Not Disturb
  only after confirming the dialler opens.
- Location tests should be done outdoors or by a window for the first pass, then
  indoors to test the timeout.
- Resource discovery talks to the production backend at
  `https://resq-backend-s55p.onrender.com` over HTTPS by default, so no local
  backend is required. If you deliberately want to test against a local backend
  instead, run it on the PC and rebuild with
  `-Presq.backend.url=http://<pc-lan-ip>:8010` (the phone must be on the same
  Wi-Fi). Note the Render free tier sleeps when idle: the first search after a
  long pause can take a while, and a timed-out search shows a Retry action.
- Record the outcome of every row. A row that is "not tested" is not a pass.
- The production backend was probed on 2026-10-10: `GET /health` healthy and one
  `POST /search` returned 35 resources. If a later search fails, suspect the
  Render cold start first, not the app.

---

## A. The reported failure — Location OFF → ON

This is the regression this work exists to fix. Do not skip it.

| # | Steps | Expected | Result |
|---|---|---|---|
| A1 | Grant location permission. Enable protection. Confirm the GPS pill turns LOCKED. | Protection notification present. | |
| A2 | Turn the device Location toggle OFF in system settings. | ResQ reports location off; nothing crashes. | |
| A3 | Wait ~2 minutes so any cached fix ages out. | | |
| A4 | Trigger an incident (Diagnostics → possible collision). Let it run to "I need help now". | Emergency screen opens. | |
| A5 | Observe the location section. | Explains that Location is off. **Contacts, "Call 112" and the primary-contact button are all present and usable.** | |
| A6 | Turn the device Location ON. **Do not touch ResQ.** Wait 10 s. | | |
| A7 | Return to ResQ (back gesture / recents). | Recovery starts **by itself**. No need to find the Refresh button. A position arrives within a few seconds outdoors. | |
| A8 | Confirm resources load. | Nearby resources appear. | |
| A9 | Repeat A4–A8 but **with protection PAUSED** the whole time. | This is the case that used to be permanently stuck. It must now recover. | |
| A10 | Repeat A4–A8 but after killing ResQ from recents (swipe away) so the foreground service is gone, then raise the incident. | Must still recover — the emergency now owns its own listener. | |

## B. The pre-incident cache

| # | Steps | Expected | Result |
|---|---|---|---|
| B1 | Protection ON, outdoors, wait 60 s with the screen off. | Fixes are being cached. | |
| B2 | Open Diagnostics. Confirm a fresh fix and LOCKED GPS. | | |
| B3 | **Now** turn the device Location OFF, then trigger an incident. | Emergency screen shows the last known coordinates with **"Position basis: Last known position – not the current location"** and an Age row. It must **not** say the fix is current. | |
| B4 | Check the Reason row. | It still says Location is switched off — the cached position must not paper over why there is no live fix. | |
| B4a | With a fresh-but-too-coarse fix (Approximate location, poor sky view), open the location-unavailable recovery view. | The message must describe the real reason (precision / acquiring). It must **not** claim the position is "out of date" when the fix is fresh — that text now only appears for an existing-but-stale position. | |
| B5 | Confirm no resources are searched around the cached position. | "Waiting for a usable location before searching". | |
| B6 | Force-stop ResQ, relaunch, immediately open the emergency. | Cached position survives the restart. | |
| B7 | Wait >30 minutes with Location off, then raise an incident. | The cached position is **gone**, not shown. ResQ says no position is available. | |

## C. Battery

| # | Steps | Expected | Result |
|---|---|---|---|
| C1 | Protection ON, screen off, 30 min. Compare battery drain against the previous 1 Hz build if one is available. | Noticeably lower. | |
| C2 | Leave the phone stationary outdoors for 5 min. | Few or no new fixes once the 10 m distance filter stops them. | |
| C3 | Confirm the freshness promise still holds: with the phone still, the GPS pill must not sit on LOCKED forever. | Pill reads STANDBY once the fix ages past 30 s. That is correct and honest. | |

## D. Approximate location

| # | Steps | Expected | Result |
|---|---|---|---|
| D1 | Revoke precise location; grant **Approximate** only. | | |
| D2 | Raise an incident. | Fix is accepted and searched around — the user still gets resources. | |
| D3 | Check the Precision row. | Says "Approximate", never a fake "±5 m". | |

## E. Emergency usability with no location at all

| # | Steps | Expected | Result |
|---|---|---|---|
| E1 | Revoke location permission AND turn the toggle off. Raise an incident. | Emergency screen opens normally. | |
| E2 | Try, in order: **Call 112**, the primary-contact button, each contact row, Retry, Refresh location, Open Location settings. With permission revoked (not just the toggle off), also try **Open app settings**. | Every one of them is present and does something. Nothing is dead. "Open app settings" must land on ResQ's own App info page, not the device Location settings. | |
| E3 | Confirm the screen never sits on a permanent spinner. Also confirm **Refresh location** is disabled while an acquisition is already running (no queued second attempt on a double tap). | | |

## F. Direct calling — consent and permission

| # | Steps | Expected | Result |
|---|---|---|---|
| F1 | Fresh install. Settings → escalation consent OFF. | Consent is OFF by default. Capability reads "nothing happens automatically". | |
| F2 | Trigger a hardware incident to escalation. | **No call is placed.** Status explains consent is off. | |
| F3 | Turn consent ON. Do **not** grant CALL_PHONE. | Capability reads "dialer". | |
| F4 | Trigger an incident to escalation. | The **dialer opens** with the primary contact. The call is NOT placed. | |
| F5 | Grant CALL_PHONE from Settings (visible tap only). The grant can be made from **Settings → Permissions → Phone calls** — the row must show "Granted" / "Not granted" reflecting the real runtime state. | Capability reads "will place a real call". | |
| F6 | Trigger an incident to escalation. | **A real call is placed to the primary contact.** | |
| F7 | Read the status text. | "ResQ asked your phone to place a call to X." It must **not** say connected / answered / reached. | |
| F8 | Trigger a second incident after standing down. | Exactly one further attempt. Not zero, not two. | |
| F9 | Revoke CALL_PHONE in system settings mid-incident. | Reported honestly; manual call still available. | |

## G. Calling under real platform restrictions

These are the cases automation cannot decide. Record exactly what the Vivo does.

| # | Steps | Expected | Result |
|---|---|---|---|
| G1 | Foreground escalation (screen on, ResQ visible). | Call places. | |
| G2 | **Screen off**, app backgrounded, wait for the no-response window to expire. | Record precisely: does the call place? Does the in-call UI appear? Does it wait until unlock? | |
| G3 | During G2, pull down the shade. | The incident notification is present. | |
| G4 | Tap the notification. | Reopens the emergency screen. | |
| G5 | Tap the notification's **Call 112** action. | Opens the dialer for 112 — it must **not** place the call without the user pressing it. | |
| G6 | Lock the screen mid-incident and try the in-app Call 112 button. | Record whether OriginOS allows it. | |
| G7 | Rotate the device during an incident with a call already attempted. | **No second call.** | |
| G8 | Force-stop ResQ during an incident, reopen. | No duplicate call. | |

## H. Diagnostics / developer mode

| # | Steps | Expected | Result |
|---|---|---|---|
| H1 | Diagnostics → simulate possible collision → run to escalation. | **No call, ever.** Developer/Test Mode never dials. | |
| H2 | Diagnostics → simulate hard braking. | Stays NORMAL. | |
| H3 | Diagnostics → **simulate no response** from the calm home screen (consent ON, primary contact saved, CALL_PHONE granted — the exact configuration that used to place a real call). Let the confirmation window expire. | The emergency screen opens, but **no call is placed and the dialer is never opened.** This is the critical regression the new unit tests pin. | |
| H4 | During an incident, tap **I need help** twice in quick succession (double tap / screen-crossfade tap). | The emergency stage must **not** rewind to "locating" — it stays exactly where it was. | |
| H5 | Settings → Emergency → Alert countdown: choose **30 s**. Trigger a possible-collision alert. Watch the countdown ring. | The ring drains smoothly across the **full 30 s**, not stuck full for 20 s and then draining over the last 10. | |
| H6 | Settings → Emergency → Contacts → Deep-link contact setup (first launch), then Back twice. | First Back returns to Emergency settings, second Back to the main Settings menu. A third-level submenu (Alert countdown) must pop correctly too — Back must never get stuck. | |

---

## Known limitations to state plainly in any report

- ResQ **cannot confirm** that a call connected, rang, or was answered. It only
  reports what it asked Android to do.
- Android 10+ blocks background activity starts. A direct call made with the app
  backgrounded may be accepted by the platform without the in-call UI appearing
  until the device is unlocked. Locked-screen behaviour is OEM-dependent; do not
  promise it works.
- ResQ never changes the system Location toggle. Enabling it is always the user's
  action.
- Google Play restricts `CALL_PHONE`. The informed, per-incident, revocable
  consent exists specifically because auto-dialling without a visible user action
  is the use that gets reviewed.
- Cloud voice escalation is **not implemented**. No telephony provider has been
  selected. If the app ever claims a spoken message was delivered, that is a bug.
---

# Emergency SMS + automatic primary-contact call (MVP)

Added with the lifecycle-independent escalation coordinator. **None of the rows
below have been run.**

## Safety rules for this section

- These tests **send real SMS and place real calls** to your consenting test
  contact. Use a second SIM/number belonging to someone who has agreed to this.
- **Never save 112 as a contact** for these tests. Row S8 exists specifically to
  prove ResQ refuses to use it automatically.
- Each SMS may cost carrier credit. A dozen sends is negligible; hundreds are not.
- Carrier delivery receipts are unreliable. If ResQ says ""handed to carrier"",
  that is the correct wording - it does NOT mean delivered.

## Setup before starting

1. Settings -> Emergency -> Emergency contacts: add the test contact (and, for
   S9, a second one). No contact may be ""112"".
2. Settings -> Emergency -> Emergency contacts -> ""Send an alert text to my
   contacts"": **OFF by default.** Turn it on. The screen must warn that the
   message is sent from this phone's SIM and may cost carrier credit.
3. Settings -> Permissions -> Text messages: tap and **Allow**.
4. Settings -> Emergency -> Emergency contacts -> ""Let ResQ call my primary
   contact automatically"": ON, and Settings -> Permissions -> Phone calls:
   Allow. (For S5, leave CALL_PHONE denied.)

## SMS

| # | Steps | Expected |
|---|---|---|
| S1 | Consent OFF. Escalate a real incident to Emergency. | **No SMS sent.** Emergency screen says ResQ did not send an alert text - you have not agreed. Call buttons still work. |
| S2 | Consent ON, SEND_SMS denied. Force-stop, reopen Settings -> Permissions -> Text messages -> Deny. Escalate. | No SMS. Status explains the text-message permission is missing and offers an ""Allow alert texts"" button. |
| S3 | Consent ON, SEND_SMS allowed. Escalate. | Exactly one SMS per saved contact. Contact receives it. Status says ""handed your carrier"" - never ""delivered"". |
| S4 | Repeat S3 but kill the app (swipe away) before the incident; re-open during the same emergency. | **No second SMS.** The durable ledger must suppress it. |
| S5 | Consent ON, SEND_SMS allowed, **no** contacts saved. Escalate. | No SMS, and no invented number. Status says no contact is configured. |
| S6 | Escalate with Location ON and a fix. | SMS contains ""Location (current)"" plus a maps link with the coordinates. |
| S7 | Turn device Location OFF, let the pre-incident cache age, then escalate. | SMS still sends. Link is labelled ""Last known location ... not the current position"". Never says ""current"". |
| S8 | Save **112** as a contact (temporarily) with a normal contact too. Escalate. | 112 is **never** messaged and **never** called automatically. Only the normal contact is used. The manual ""Call 112"" button still works. |
| S9 | Two saved contacts. Escalate. | Both receive one SMS each. Only the PRIMARY is called (see below). |

## Ordering: SMS then call

| # | Steps | Expected |
|---|---|---|
| S10 | Everything granted. Escalate a real no-response incident and **do not touch the phone**. | Within ~1-3 s: contact receives the SMS, then the phone **rings the primary contact**. Order is SMS first, call second. |
| S11 | **Airplane mode ON**, then escalate. | SMS fails (no service). **The contact must NOT be called.** Status reports the failure and the manual Call button remains. Alert tone keeps playing (see S14). |
| S12 | Consent for the **call** OFF, SMS consent ON. Escalate. | SMS still sends. Contact is **not** called. Status says call consent is off. |
| S13 | Call consent ON but CALL_PHONE denied. Escalate. | SMS sends. The **dialer opens** (not a placed call). Status says ResQ asked the phone to open the dialer. |

## Screen-off escalation (the core fix)

| # | Steps | Expected |
|---|---|---|
| S14 | Protection ON. **Lock the screen / press Home and wait out both countdowns** without opening ResQ. | SMS is sent and the primary contact is called even though no UI was ever visible. This is the regression this work fixed. |
| S15 | As S14, then unlock and open ResQ during the same emergency. | Emergency UI is shown with the truthful status. **No duplicate SMS and no second call.** |
| S16 | As S14, but enable ""Background activity"" restrictions / let the OEM kill the service. Note any difference from S14 and report it. | Report actual OEM behaviour. Do not assume it matches S14. |

## Sound silencing

| # | Steps | Expected |
|---|---|---|
| S17 | Escalate with everything granted. Listen from the moment the emergency opens. | ResQ's own alert tone/speech **stops once the carrier accepts the SMS** - not when the send is merely attempted. |
| S18 | Consent OFF, escalate, and listen. | Alert tone **keeps playing**. It must NOT be silenced by a failed or blocked send. |
| S19 | Airplane mode, escalate, listen. | Alert tone **keeps playing**. |
| S20 | After S17 silencing, confirm the emergency UI is still there. | Emergency state retained; ""Call 112"" and per-contact Call buttons all still present and working. No system-wide audio was altered. |

## Test Mode safety

| # | Steps | Expected |
|---|---|---|
| S21 | Settings -> Developer/Test -> Diagnostics. Simulate a high-confidence collision, let it escalate fully. | **No SMS and no call**, at any stage. |
| S22 | Simulate an incident, stand down, then trigger a REAL incident. | The real incident **must** send and call. (This was a real bug: the new emergency inherited the simulated incident's origin and was silently denied.) |
| S23 | Simulate an incident, stand down, then tap ""I Need Help"" from Home. | SMS sent and contact called. The manual path must not be suppressed by an earlier test. |

## Manual actions still work

| # | Steps | Expected |
|---|---|---|
| S24 | With the automatic call blocked, tap a contact's Call row. | Dialer opens with the number. Works exactly as before. |
| S25 | Tap ""Call 112"". | Dialer opens with 112. Manual only; ResQ never initiates it. |

## Limitations to state plainly after running these

- ResQ cannot confirm **delivery**. ""Handed to carrier"" is the strongest honest
  claim, and most carriers never return a delivery receipt at all.
- The follow-up call happens after the **carrier handoff** verdict, which is
  normally within a second or two but is not instantaneous. If the process is
  killed in that window the call may not happen; the app prefers an under-call
  over a duplicate call.
- Exactly-once is impossible with the Android SMS API. ResQ is **at-most-once per
  incident** and can therefore, in rare process-death timing, miss a send.
- Background call launching is OEM-dependent on Vivo/OriginOS. A dialer may not
  appear from a locked screen.