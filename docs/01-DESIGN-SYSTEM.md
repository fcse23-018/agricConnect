# AgriConnect Design System v1.0

A premium, modern agricultural identity. Every token below is implemented in code at
`android/app/src/main/java/bw/agriconnect/ui/theme/`.

---

## 1. Brand foundation

AgriConnect sits at the intersection of **soil and growth**. Forest green carries action, trust and
brand; soil brown grounds earth-related data; generous warm white keeps it airy and readable.

The logo (`logo.png`) combines a **leaf / growing shoot** with a subtle **connection / network
motif**, in forest green and soil brown on transparent warm white. It is wired into the app as the
launcher icon, the splash mark and the in-app brand header.

---

## 2. Colour tokens

### Core palette (brief §3, verified against `logo.png` and `app layout.png`)

| Token | Hex | Role |
|-------|-----|------|
| `ForestGreen` | `#174D32` | Primary brand, primary actions, navigation |
| `DeepGreen` | `#103B27` | Dark surfaces, app bars, pressed states |
| `AgriGreen` | `#3F7852` | Secondary actions, positive indicators, charts |
| `SoilBrown` | `#A66A3F` | Earthy accent, soil/plot info, selected secondary |
| `DarkBrown` | `#70472E` | Brown pressed/strong states |
| `WarmWhite` | `#F8F8F3` | App background |
| `PureWhite` | `#FFFFFF` | Cards, sheets, elevated surfaces |
| `TextMain` | `#202720` | Primary text |
| `TextSecondary` | `#667067` | Secondary text, captions, helper |
| `Border` | `#E1E6DF` | Dividers, card borders, input outlines |

### Extended semantic tokens (derived, contrast-checked)

| Token | Hex | Role |
|-------|-----|------|
| `GreenContainer` | `#E3EFE6` | Selected chips, success banners |
| `BrownContainer` | `#F0E4D9` | Soil cards, brown chips |
| `SuccessGreen` | `#2E7D4F` | Success text/icon on light |
| `WarningAmber` | `#B07A24` | Warnings, moderate stress |
| `ErrorRed` | `#B3261E` | Errors, destructive, high stress |
| `ErrorContainer` | `#F9DEDC` | Error banners |
| `InfoBlue` | `#2A5C8A` | Informational banners |
| `DisabledBg` | `#EDF1EC` | Disabled fills |
| `DisabledText` | `#A3ACA4` | Disabled text |
| `Scrim` | `#1A202E00` → `#66000000` | Modal scrim |

### NDVI semantic mapping

| NDVI | Stress level | Colour |
|------|--------------|--------|
| `< 0.20` | High Stress | `ErrorRed #B3261E` |
| `0.20 – <0.45` | Moderate Stress | `WarningAmber #B07A24` |
| `≥ 0.45` | Healthy Vegetation | `SuccessGreen #2E7D4F` |

### Sync-status semantic mapping

| Status | Colour | Icon |
|--------|--------|------|
| `PENDING` | `WarningAmber` | cloud-off / clock |
| `SYNCING` | `InfoBlue` | sync (animated) |
| `SYNCED` | `SuccessGreen` | cloud-done / check |
| `CONFLICT` | `SoilBrown` | compare-arrows |
| `FAILED` | `ErrorRed` | cloud-alert |

Contrast: all text pairings meet **WCAG AA** (≥ 4.5:1 body, ≥ 3:1 large). `TextSecondary` on
`WarmWhite` = 4.9:1. `PureWhite` on `ForestGreen` = 9.1:1.

---

## 3. Typography

Single family: **Inter** (variable) with Android system fallback. Numerals use tabular figures for
financial tables.

| Style | Size / Line | Weight | Tracking | Use |
|-------|-------------|--------|----------|-----|
| Display | 40 / 48 | 700 | -0.5 | Splash, hero numerals |
| Headline L | 28 / 36 | 700 | -0.2 | Screen titles (large) |
| Headline M | 24 / 32 | 600 | -0.2 | Section headers |
| Title L | 20 / 28 | 600 | 0 | Card titles, app bar |
| Title M | 16 / 24 | 600 | 0 | List item titles |
| Body L | 16 / 24 | 400 | 0.1 | Body copy |
| Body M | 14 / 20 | 400 | 0.15 | Secondary body |
| Label L | 14 / 20 | 600 | 0.1 | Buttons, labels |
| Label M | 12 / 16 | 600 | 0.4 | Chips, tabs |
| Caption | 11 / 16 | 500 | 0.4 | Timestamps, helper |

Max line length ~ 66 characters for readability.

---

## 4. Spacing — 8dp system

`4 · 8 · 12 · 16 · 24 · 32 · 40 · 48 · 64`.

| Token | dp | Typical use |
|-------|----|-------------|
| `xs` | 4 | Icon-to-label |
| `sm` | 8 | Intra-component |
| `md` | 12 | Chip padding, dense lists |
| `lg` | 16 | Screen gutter, card padding |
| `xl` | 24 | Between sections |
| `xxl` | 32 | Between major blocks |
| `xxxl` | 40/48 | Empty-state breathing room |

Screen horizontal gutter = **16dp**. Card padding = **16dp**. Card gap = **12dp**.

---

## 5. Shape & elevation

| Token | Radius | Use |
|-------|--------|-----|
| `xs` | 6 | Chips, badges |
| `sm` | 10 | Inputs, small buttons |
| `md` | 14 | Standard buttons, list cards |
| `lg` | 20 | Cards, sheets |
| `xl` | 28 | Bottom sheets, dialogs (top) |
| `pill` | 999 | Status pills, avatars |

Elevation is **restrained**: cards use a 1dp `Border` outline + very soft shadow
(`0/1/3 @ 6 %`). Only FABs and dialogs carry a pronounced shadow. No gradients on
content surfaces.

---

## 6. Iconography

- Material Symbols (Rounded), 24dp default, 20dp inline, 1.8dp optical weight.
- Custom crop/soil/leaf/livestock glyphs where the standard set is weak.
- Icons always paired with a text label (never icon-only for primary actions).

---

## 7. Motion

| Token | Duration | Curve |
|-------|----------|-------|
| `fast` | 120 ms | standard ease-out |
| `medium` | 220 ms | emphasized decelerate |
| `slow` | 340 ms | emphasized |

Respect **Reduce Motion** (`Settings.Global.ANIMATOR_DURATION_SCALE`) → swap predictive/scale
transitions for cross-fades.

---

## 8. Component library (reusable, in `ui/components/`)

| Component | Notes |
|-----------|-------|
| `AgriTopAppBar` | brand header with optional logo + sync badge |
| `AgriPrimaryButton` / `AgriSecondaryButton` / `AgriTextButton` | 3 button tiers, loading + disabled |
| `AgriTextField` | outlined, inline validation, error/helper, prefix/suffix |
| `AgriPasswordField` | with show/hide toggle |
| `AgriCard` | outlined card, optional leading icon + trailing action |
| `SyncStatusBadge` | PENDING/SYNCING/SYNCED/CONFLICT/FAILED |
| `OfflineBanner` | animated connectivity strip |
| `AgriChip` / `AgriFilterChip` | green selected, brown secondary |
| `StatTile` | large numeral + label + delta |
| `SectionHeader` | title + optional action |
| `EmptyState` | illustration + title + body + CTA |
| `ErrorState` | message + retry |
| `LoadingState` | skeletons, not spinners, where possible |
| `AgriBottomSheet` | role-aware menu / confirmations |
| `ConfirmationDialog` | destructive + neutral variants |
| `AgriSnackbarHost` | success/error/info |
| `RoleBadge` | Farmer / Employee / Buyer |
| `PulaAmount` | tabular BWP money formatter |
| `NdviGauge` | NDVI value + stress pill |
| `AgriChart` | sales-vs-expenses bars + net line |

Every component renders **loading, error, empty, offline and success** states.

---

## 9. States — mandatory on every screen

1. **Loading** — skeleton or progress with label.
2. **Empty** — friendly illustration + explanation + primary CTA.
3. **Error** — plain-language message (never raw server text) + Retry.
4. **Offline** — offline banner + "saved locally / showing cached data" affordance.
5. **Success** — inline confirmation (snackbar or inline banner), never a bare toast.

---

## 10. Accessibility

- Minimum touch target **48×48dp**.
- Every interactive element has a `contentDescription` or semantic label.
- Dynamic type: layouts survive **fontScale 2.0** without clipping (no fixed-height text rows).
- Colour is never the only signal — status uses icon **and** text **and** colour.
- Focus order matches reading order; full TalkBack traversal on the critical journeys.
- Forms announce validation errors to the screen reader.

---

## 11. Role-aware theming

The palette is identical for all roles; the **navigation and available actions** change:

| Role | Bottom nav (5) |
|------|----------------|
| Farmer | Home · Farm · Finance · Market · Profile |
| Farm Employee | Home · Field · Alerts · Profile |
| Buyer | Market · Orders · Notifications · Profile |

Financial destinations are **absent** (not merely hidden) for Employee/Buyer, and the same
enforcement exists server-side.
