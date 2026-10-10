# ResQ — Design Spec

Premium, minimalist Android emergency-safety app. Restrained Liquid Glass on Material 3 foundations. Calm in everyday use, urgent but clean in an emergency.

Reference canvas: https://claude.ai/artifact/QYw9XVnfqAVMio9WHGeBYE (9 artboards, 390 × 844 dp).

---

## 1. Principles

1. **Clarity over decoration.** One primary action per screen, readable in under a second.
2. **Restrained glass.** Glass is used only on single surfaces that need focus (status ring, grouped cards, pills, secondary buttons). Never glass on glass. Never full-screen glass.
3. **Three atmospheres.** Light, Dark, Emergency. Components are identical; only tokens change.
4. **Stress-proof.** Large targets, large type, generous spacing, no dense data, no tiny text.
5. **No gimmicks.** No neon, no cyberpunk styling, no heavy gradients, no speedometers, no trip analytics, no decorative maps, no chatbot UI.

---

## 2. Themes and Tokens

All components read semantic tokens. Switch the token set, not the component.

| Token | Light | Dark | Emergency |
|---|---|---|---|
| `bg` | `#EDF2F5` | `#0B1418` | `#A50E18` |
| `bgGlow` (soft radial, top-right, 55% falloff) | `rgba(110,200,190,.38)` | `rgba(60,170,160,.28)` | `rgba(255,130,120,.45)` |
| `text` | `#0F1C24` | `#EAF2F5` | `#FFFFFF` |
| `textMuted` | `#46596A` | `#9CB1BB` | `rgba(255,255,255,.92)` |
| `glass` (surface fill) | `rgba(255,255,255,.66)` | `rgba(255,255,255,.07)` | `rgba(255,255,255,.14)` |
| `glassBorder` (1dp) | `rgba(255,255,255,.95)` | `rgba(255,255,255,.15)` | `rgba(255,255,255,.32)` |
| `divider` | `rgba(15,40,50,.10)` | `rgba(255,255,255,.10)` | `rgba(255,255,255,.25)` |
| `accent` (primary fill, status dot, toggle on) | `#0A7570` | `#62D6CA` | `#FFFFFF` |
| `onAccent` | `#FFFFFF` | `#052320` | `#9A0C16` |
| `shadow` | `0 8 24 rgba(15,40,50,.08)` | `0 8 24 rgba(0,0,0,.35)` | `0 8 24 rgba(60,0,0,.25)` |

Extra emergency-only token: `danger` = `#5A0610` (fill for "I Need Help Now"), text `#FFFFFF`.

**Contrast.** Emergency white on `#A50E18` is about 7.8:1. Light accent `#0A7570` with white text is about 5.5:1. All body text meets 4.5:1 or better; large text meets 3:1 or better. Re-verify any value you change.

**Why the emergency primary is white-on-red inverted.** A white filled button with red text is the highest-contrast element on a red field, so the primary action is found instantly.

---

## 3. Typography

Typeface: **Figtree** (400, 600, 700). Fall back to the system sans (Roboto) if unavailable.

| Role | Size / line | Weight | Notes |
|---|---|---|---|
| Display (countdown) | 84 / 84 sp | 700 | Alert screen only |
| Distance stat | 56 / 56 sp | 700 | Resource details |
| Title (h1) | 32 / 36 sp | 700 | letter-spacing −0.6 |
| Title compact | 28 sp | 700 | Developer screen |
| Button | 18–20 sp | 600 | 20 for large primaries |
| Row / list item | 17 sp | 400 / 600 for names | |
| Body | 16 / 23 sp | 400 | `textMuted` |
| Caption | 14–15 sp | 400–600 | Minimum size in the app |
| Section label | 13 sp | 600 | uppercase, +0.8 tracking, `textMuted` |

Never go below 13 sp. Respect the system font-scale setting; layouts must reflow, not truncate.

---

## 4. Shape, Spacing, Elevation

- **Grid:** 8 dp base, 24 dp screen side padding, 16 dp gap between blocks, 12 dp between stacked buttons.
- **Safe areas:** top padding 56 dp (status bar + breathing room), bottom 32 dp. Use real window insets in code.
- **Radii:** glass cards 28 dp, pills 20 dp, buttons fully rounded (height / 2), status ring circular, device frame 36 dp (mock only).
- **Glass recipe** (the only surface recipe):
  - fill `glass`, 1 dp `glassBorder`, backdrop blur ≈ 24 dp, `shadow`, inner padding 6 dp vertical / 20 dp horizontal for row groups.
- **Status ring:** 176 dp circle, glass fill and border, plus a 14 dp outer halo in `bgGlow`. Alert countdown variant is 200 dp.

---

## 5. Components

| Component | Spec |
|---|---|
| **Primary button** | Height 72 dp (60 dp in compact contexts), full width, `accent` fill, `onAccent` text, 20 sp / 600, no border. |
| **Secondary button** | Height 60 dp, glass fill + border, `text` color, 18 sp / 600. |
| **Danger button** | Height 72 dp, `#5A0610` fill, white text, 20 sp / 600. Emergency only. |
| **Text button** | Min height 48 dp, no fill, 16 sp / 600. Used for Settings, Back. |
| **Status pill** | Height about 36 dp, glass fill + border, 14 sp / 600, optional 10 dp `accent` dot. |
| **Glass card (row group)** | Glass recipe. Rows min 52 dp, divider between rows. Two-line rows min 68 dp. |
| **Row** | Label left (17 sp), value or chevron right (`textMuted`). Status values start with a 10 dp accent dot. |
| **Toggle** | 52 × 32 dp track, 24 dp knob, `accent` when on, knob `onAccent`. Use Material 3 `Switch` with these colors; keep 48 dp min touch area. |
| **Segmented control** | Glass container, 4 dp padding, equal segments, selected segment `accent` / `onAccent`, 12 dp vertical padding. |
| **Confidence bar** | 90 × 6 dp, track `divider`, fill `accent`. Developer only. |

---

## 6. Screens

### 6.1 Protection Home (Light + Dark)
- Top row: "ResQ" wordmark (20 sp, bold) and a "Settings" text button.
- Centered shield-check icon in the status ring (76 dp, stroke 1.6, `accent`).
- Title "You're protected" with one calm explanatory sentence below it.
- One glass card, two rows: Motion sensors (Active), Location (Precise).
- Bottom: single secondary button "Pause Protection".
- Pausing must confirm the action and show the paused state clearly (separate state, same layout, accent swapped for muted).

### 6.2 Possible Incident Alert (Emergency theme)
- Pill: "Possible accident detected".
- Title "Are you okay?"; 200 dp ring containing the 84 sp countdown and "seconds".
- One sentence explaining what happens if there is no response.
- Bottom, stacked: **"I'm OK — Cancel Alert"** (primary, white) and **"I Need Help Now"** (danger).
- Countdown default 30 s (configurable in Settings). Trigger vibration and sound; keep the screen awake. At 0, transition to Emergency Response.

### 6.3 Emergency Response (Emergency theme)
- Pill "Emergency active", title "Help is being alerted", one-line contact status.
- Location row in a glass card (shared, accuracy).
- Section "Nearest hospitals": glass card with two two-line rows (name, distance + hours), chevron opens details.
- Bottom: primary **"Call 112"** (confirm the regional emergency number per locale), secondary "Directions to <nearest>".

### 6.4 Location Unavailable (Emergency theme)
- Same header as 6.3 so the emergency state stays obvious.
- Title "Location is unavailable"; reassure that the alert is still active and contacts get the last known area.
- Glass card: Last known location (age), Nearby hospitals (Limited).
- Helper sentence: enable Location for accurate help; calling the emergency number does not require it.
- Bottom: primary **"Enable Location"** (deep-link to system settings or permission prompt), secondary "Call 112".

### 6.5 Emergency Resource Details (Emergency theme)
- Back text button, pill (type, hours), name as title.
- Large distance (56 sp) with travel time below.
- Glass card, two-line rows: Address, Phone (hide row if unavailable).
- Bottom: primary **"Call <type>"** (dial intent), secondary "Directions" (maps intent).

### 6.6 Settings (Light + Dark)
- Sections: Appearance (Light / Dark / System segmented), Protection (Crash detection, Fall detection toggles), Emergency (Alert countdown, Emergency contacts), Permissions (Location, Motion sensors with status dot).
- Permissions rows deep-link to system settings when not granted and show a clear "Needs access" value.
- Appearance follows system by default; the choice persists.

### 6.7 Developer / Test Mode (Light shown; supports Dark)
- Hidden behind a developer toggle; show a "Debug" pill.
- Status card: Sensors (rate), Location (fix and accuracy), Detection state, Confidence bar with percentage.
- Simulation: Simulate impact, Simulate fall, Simulate no location (a simulated alert must be clearly labeled and must never contact real services).
- Technical card: app build, detection model, sample window.
- Keep the same glass system; no monospace terminal styling.

---

## 7. Behaviour and State

- **Theme resolution:** user setting (Light/Dark/System) → resolves to Light or Dark. Any emergency state (countdown, active emergency, location unavailable, resource details during an emergency) overrides with the Emergency theme and returns to the resolved theme when cleared.
- **Transitions:** crossfade the background and tokens over about 250 ms; reduce or skip motion when "remove animations" is on.
- **Haptics and sound:** pulse during countdown; never rely on color alone for the state change.
- **Glass fallback:** blur needs `RenderEffect` (API 31+). On older devices, or when reduce-transparency / battery saver applies, use an opaque tint of the same token (about 85% alpha) with the same border.

---

## 8. Android Implementation Notes (Jetpack Compose + Material 3)

- Build one `ResQColors` data class holding the tokens above and provide it via a `CompositionLocal`; map to `MaterialTheme.colorScheme` for stock components.
- Create `GlassSurface(modifier, shape = 28.dp)` that applies fill, border, shadow, and blur in one place. Nothing else should draw glass.
- Create `ResQButton(style = Primary | Secondary | Danger)` and `StatusPill`, `GlassRowGroup`, `SettingRow`.
- Use `Modifier.minimumInteractiveComponentSize()` for every tappable element; no target under 48 dp.
- Use `WindowInsets.safeDrawing` for padding; do not hard-code 56 / 32 dp.
- Icons: a single outline icon family, 24 dp, 1.5–2 dp stroke, `text` or `accent` color. Shield-check is the brand mark.
- Add `contentDescription` to icon-only controls; announce the countdown with live-region updates (every 5 s, then every second for the last 5).

---

## 9. Accessibility Checklist

- Text contrast 4.5:1 minimum (3:1 for 24 sp and above) in all three themes.
- Status is never color-only: dot + label, or label + icon.
- Fully operable with TalkBack and switch access; primary action is first in focus order on every emergency screen.
- Supports font scale up to 200% without clipped primary actions (let the content area scroll; buttons stay pinned).
- Motion is optional; the countdown also works without animation.

---

## 10. Content Notes

- Hospital names, address, phone number, and the 112 emergency number in the mockups are **placeholders**. Replace with real data from the places and dialer providers and confirm the emergency number per region.
- Tone: short, calm, direct. Avoid alarmist wording on Home; be unambiguous in the emergency flow.
