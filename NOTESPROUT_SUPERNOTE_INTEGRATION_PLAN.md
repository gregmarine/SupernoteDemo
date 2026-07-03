# Notesprout ← Supernote Support: Integration Plan

> **Status:** Ready to implement. Grounded in a working PoC (this repo, `SupernoteDemo`)
> validated on real hardware — a Supernote **Manta** (1920×2448 canvas) and **Nomad**
> (1404×1760). This plan **supersedes the assumptions** in Notesprout's original
> `SUPERNOTE_SUPPORT_PLAN.md` where they differ (see [§3 Corrections](#3-corrections-to-the-original-plan)).
>
> **Where this file lives:** kept in `SupernoteDemo` for now; move/copy into Notesprout
> (e.g. `docs/`) when work starts. File paths below are **Notesprout** paths unless prefixed
> `SupernoteDemo/`.

---

## 1. What the PoC proved (on real devices)

| Capability | Result |
|---|---|
| Firmware ink binder reachable | ✅ `ServiceManager.getService("service_myservice")` → `android.demo.IMyService`, both Manta & Nomad |
| Live hardware ink under the pen | ✅ smooth, sub-frame latency, no app-side lag |
| Stroke capture (points) | ✅ from `MotionEvent` (firmware returns **no** point data) |
| Pressure | ✅ real EMR pressure captured per point (0..1) |
| Erasing | ✅ software hit-test removal + firmware taken out of ink mode |
| Persistence round-trip | ✅ save → kill → relaunch → re-render (PoC used JSON; Notesprout uses its own store) |
| No per-stroke flash / ghost | ✅ once the **deferred handoff** model was adopted (the key lesson) |
| Survives task-switch | ✅ re-claims pen + full-UI ink on window-focus regain |
| Finger vs stylus | ✅ only stylus draws; finger left for gestures |

**The single most important architectural finding** is in §3 and §5: the firmware overlay must
**own the live view while writing**, and the bake-into-canvas + overlay-release **handoff must
be deferred to natural boundaries** — never per pen-lift. Doing it per stroke causes a screen
flash and a "ghost/enlargement." This is exactly how Notesprout's `OnyxNotebookView` already
behaves, and Notesprout's `NotebookView` interface already exposes the seam for it.

The PoC's firmware client `SupernoteDemo/app/.../SupernoteInk.kt` is **self-contained and
production-ready** — it can be dropped into Notesprout with only a package rename.

---

## 2. Notesprout architecture this plugs into

Engine selection (single line):

```kotlin
// NotebookActivity.kt:1259
drawingView = if (isBooxDevice()) OnyxNotebookView(this) else GenericNotebookView(this)
```

| Piece | File | Role |
|---|---|---|
| Engine interface | `notebook/NotebookView.kt` (662 LOC) | Contract: drawing enable/disable, erase, lasso, text/line/link/sticky/shape objects, snapshot, **handoff hooks** |
| Canvas engine | `notebook/GenericNotebookView.kt` (1895 LOC) | `MotionEvent` capture + baked bitmap + lasso/erase/objects/snapshot. Laggy live ink on e-ink. |
| Hardware engine | `notebook/OnyxNotebookView.kt` (2588 LOC) | BOOX `TouchHelper`/`EpdController` overlay owns live ink; bakes on completion; overlay lifecycle in `onWindowFocusChanged` |
| Device detect | `core/Device.kt` | `isBooxDevice()` = `MANUFACTURER contains "onyx"` |
| Hidden-API unlock | `NotesproutApplication.kt:40` | `HiddenApiBypass.addHiddenApiExemptions("")` — **already present** (our prerequisite) |

**The mapping is almost 1:1:** a Supernote is "an Onyx-shaped problem with a different overlay
API." `RattaNotebookView` = **GenericNotebookView's input/model/objects** (points come from
`MotionEvent`, same as Generic) **+ OnyxNotebookView's overlay-lifecycle discipline** (a
hardware layer owns live ink; release it at handoff boundaries) — with the Onyx SDK swapped for
our `SupernoteInk` binder client.

### The handoff seam already exists

`NotebookView` defines exactly the hooks the deferred handoff needs (currently no-ops on
Generic, real on Onyx):

- `releaseForHandoff()` — before launching another drawing screen (release shared pen pipeline).
- `resetOverlay()` — reset the live overlay.
- `releaseRender()` — "Release the EPD writing overlay so the next screen refresh shows toolbar
  changes. Called on any toolbar touch. BOOX: disables overlay render + invalidates."
- `setToolbarExclusion(rect)` — keep hardware ink off the toolbar.
- `enableDrawing()` / `disableDrawing()` / `resumeDrawing()` / `releaseResources()`.
- `setEraserMode(active)`, `eraseAll()`.
- `onPenLifted`, `onSnapshotReady`, plus the full lasso/text/object callback surface.

`RattaNotebookView` implements these to drive the firmware overlay (§5). **No new seam work in
the shared layer is required.**

---

## 3. Corrections to the original plan

The original `SUPERNOTE_SUPPORT_PLAN.md` was written from the KOReader source before hardware
testing. The PoC confirmed the overall design but **corrected these specifics** — carry these
forward:

1. **Pen type = NEEDLE (10), not INK (16).** NEEDLE is uniform-width (ballpoint); it matches the
   uniform-width baked stroke. INK/CALLIGRAPHY vary width with pressure/angle, so the live
   overlay disagrees with the baked bitmap.
2. **EMR size mapping is `floor(widthPx * 100)` clamped to `[200, 1200]`** (a width-3 pen → EMR
   300). The original placeholder (`~3.0f`, effectively EMR ≈ 9) is **invisible** — the firmware
   paints a sub-pixel line, so the stroke only appears when you lift (bitmap bake). This one bug
   masqueraded as "no live ink." Real ranges hug the thin end of the Nomad Needle penSizeArray
   (~200..2400).
3. **DO NOT bake + `clearAll()` + refresh on every pen-lift.** That fights the hardware → flash +
   ghost/enlargement. Defer the handoff (§5). *(The original plan's Phase 2 described a per-lift
   bake; replace it with the deferred model — which is what Onyx already does.)*
4. **`enableFullUiAuto` lands on `android.os.EinkManager`** (confirmed). Also discovered on that
   service: `enableAutoRegal` (anti-ghosting waveform — **enable at setup**), `screenRefresh`,
   `sendOneFullFrame`. **Avoid per-stroke `screenRefresh`/`sendOneFullFrame` — both flash.**
5. **Manufacturer string is `"Supernote"`, not `"ratta"`** on the Manta/Nomad. `isRattaDevice()`
   must match `"supernote"` **or** `"ratta"`. Both units also report `model = "Supernote_Nomad"`
   regardless of being a Manta vs Nomad; distinguish by **screen resolution** if ever needed, but
   the engine should gate on **binder availability**, not model.
6. **Input is stylus-only for ink.** Gate drawing/erasing to `TOOL_TYPE_STYLUS`/`TOOL_TYPE_ERASER`;
   let finger fall through for gestures (Generic likely already partitions this — verify).

---

## 4. The firmware client (`SupernoteInk`)

Port `SupernoteDemo/app/src/main/java/org/iccnet/supernotedemo/SupernoteInk.kt` verbatim into
`notebook/ratta/SupernoteInk.kt` (rename package; drop the demo `appName`). It is a Kotlin object
with **no Notesprout dependencies**.

Surface (all safe no-ops when the binder is absent):

```
isAvailable(): Boolean                       // ServiceManager lookup, cached tri-state
claimPen()                                   // tx0 WRITE_APP_INFO — own the pen
setPen(type, sizeEmr, color)                 // tx2 — NEEDLE, floor(w*100).coerceIn(200,1200), BLACK
setEraser(rectangular, sizeEmr)              // tx2 — stops firmware inking
clearAll()                                   // tx6 DRAW_BUFFER — clear EPDC overlay
setDisableAreas(rects) / clearDisableAreas() // tx1 — keep ink off toolbar
enableFullUiAuto(context, enable)            // EinkManager reflection — ink everywhere
enableAutoRegal(context, enable)             // EinkManager — anti-ghost (enable at setup)
screenRefresh(context, full) / sendOneFullFrame(context)  // use sparingly (flash)
```

Constants (confirmed): pen `NEEDLE=10, INK=16, MARK=11, CALLIGRAPHY=15`; color `BLACK=0`; tx
`WRITE_APP_INFO=0, DISABLE_AREA=1, PEN=2, DRAW_BUFFER=6`; iface token `android.demo.IMyService`;
services `service_myservice` / `service.myservice`.

---

## 5. RattaNotebookView — the overlay lifecycle (the crux)

Model each finished stroke as **pending** (data captured, shown by the firmware overlay, **not**
yet baked into the app bitmap). Bake + release the overlay only at a **handoff boundary**.

```
While writing (per stroke):
  ACTION_DOWN/MOVE/UP  → capture points from MotionEvent into the stroke model
  ACTION_UP            → add stroke to model + pendingStrokes; DO NOTHING to the overlay
                         (firmware keeps showing it). No bake, no clearAll, no refresh.

Handoff boundary  → releaseFirmwareOverlay():
  1. bake pendingStrokes into the render bitmap  (Generic's buildRenderBitmap path)
  2. SupernoteInk.clearAll()                      (wipe the firmware overlay)
  3. invalidate()                                 (app bitmap now shows everything)
```

**Handoff boundaries** (wire each to `releaseFirmwareOverlay()`):

| Notesprout hook / event | Action |
|---|---|
| tool change (pen↔eraser↔lasso↔text) | release overlay, then `setPen`/`setEraser` or stop claiming pen |
| `releaseRender()` (toolbar touch) | release overlay |
| `releaseForHandoff()` (launch next screen) | release overlay |
| `resetOverlay()` | release overlay |
| `onWindowFocusChanged(false)` | release overlay + `enableFullUiAuto(false)` + release claim |
| snapshot capture (`onSnapshotReady`) | release overlay **first** so the snapshot has baked ink |
| page nav / `setTemplate` / load | release overlay before swapping the bitmap |
| `eraseAll()` / clear | clear model + `clearAll()` (+ optional `sendOneFullFrame` — flash OK here) |

**Setup / re-claim** (`onAttachedToWindow`, `enableDrawing()`, and `onWindowFocusChanged(true)` —
firmware hands the pen back to other apps while we're away, so re-assert on return):

```
claimPen()
enableFullUiAuto(context, true)
enableAutoRegal(context, true)          // clean handoff refresh, no full flash
setToolbarExclusion → setDisableAreas([toolbar rect])   // getLocationOnScreen offset
applyTool()   // PEN → setPen(NEEDLE, emr, BLACK);  ERASER → setEraser(false, emr)
```

`releaseResources()` / detach: `releaseFirmwareOverlay()` + `clearDisableAreas()` +
`enableFullUiAuto(false)`.

This mirrors `OnyxNotebookView`'s `setRawDrawingRenderEnabled(false) → bitmap → repaint` ordering
and its `onWindowFocusChanged` re-`openRawDrawing`. Reuse Onyx's lifecycle structure as the
template; only the overlay API differs (`SupernoteInk` vs `TouchHelper`).

---

## 6. Phased implementation

**Phase 0 — Baseline (already true).** Supernote falls through `isBooxDevice()==false` to
`GenericNotebookView` and works today, just with e-ink-laggy live ink. Ratta is a **latency
upgrade with a safe fallback**, so it can land incrementally.

**Phase 1 — Drop in `SupernoteInk`.** Copy the PoC client to `notebook/ratta/SupernoteInk.kt`.
Verify `isAvailable()` logs the binder on a device. (Self-contained; no behavior change yet.)

**Phase 2 — Device detection.** Add to `core/Device.kt`:
```kotlin
fun isRattaDevice(): Boolean =
    Build.MANUFACTURER.lowercase(Locale.ROOT).let { it.contains("ratta") || it.contains("supernote") }
```
Gate the engine on **binder availability** (cheap manufacturer pre-filter, then the JNI probe) at
`NotebookActivity.kt:1259`:
```kotlin
drawingView = when {
    isBooxDevice()                       -> OnyxNotebookView(this)
    isRattaDevice() && SupernoteInk.isAvailable() -> RattaNotebookView(this)
    else                                 -> GenericNotebookView(this)
}
```
A Supernote lacking the binder safely uses Generic.

**Phase 3 — Extract `CanvasNotebookView` base from `GenericNotebookView`.** ~90% of Generic
(MotionEvent capture, bitmap model, lasso, erase hit-test, snapshot, object composite/load,
`buildRenderBitmap`) is what Ratta reuses verbatim. Extract an abstract base; `Generic` and
`Ratta` both extend it. *Fallback if too invasive for a first cut:* sibling-copy Generic into
Ratta (matches how Onyx/Generic already duplicate) and refactor later. Prefer the base class — it
avoids a third copy.

**Phase 4 — `RattaNotebookView` overlay lifecycle.** Implement setup/teardown/re-claim and the
`releaseFirmwareOverlay()` handoff (§5), modeled on `OnyxNotebookView`. Suppress the software
active-stroke draw (firmware owns live ink); keep it only on the Generic fallback path.

**Phase 5 — Wire handoff boundaries.** Map every boundary in the §5 table to
`releaseFirmwareOverlay()`. This is where the "no flash / no ghost" behavior comes from.

**Phase 6 — Modes reuse Generic.** Eraser (software hit-test + `setEraser` so firmware stops
inking), lasso / lasso-eraser / smart-lasso, text placement, shape/line/link/sticky objects — all
Generic logic unchanged. In every non-pen mode, **stop claiming the firmware pen and release the
overlay** so the firmware can't paint over app-drawn dashed overlays.

**Phase 7 — EinkManager tuning.** `enableAutoRegal(true)` at setup. No per-stroke refresh.
`sendOneFullFrame` only on clear/erase-all (flash acceptable there). Tune baked stroke width to
match the firmware NEEDLE `emr` so the one-time handoff transition shows no size shift.

**Phase 8 — Process-global lifecycle.** Like Onyx's global `TouchHelper`, the firmware binder /
full-UI-ink state is **system/process-global**. Honor Notesprout's existing ownership-guard /
`releaseForHandoff()` discipline across screen-to-screen navigation so a backgrounded Ratta view
releases full-UI ink and the foreground one re-claims it.

---

## 7. Persistence — mostly free

The PoC's JSON is demo-only. In Notesprout, captured strokes flow into the **existing** stroke /
`NotebookObject` model and store — unchanged from Generic (points come from `MotionEvent` exactly
as Generic already handles). Pressure is available per point if Notesprout wants variable-width
later. **No new persistence work**; just ensure `pendingStrokes` are committed into the model
before any save/snapshot (they already are on pen-up in the PoC — the stroke is added to the model
immediately; only the *visual* bake is deferred).

---

## 8. Risks / open items to validate on-device

1. **Baked-width vs firmware-width match.** A slight mismatch shows as a one-time size/darkness
   shift at the handoff moment. Pure tuning: match the bitmap paint width to the NEEDLE `emr`
   mapping. (PoC used 3px paint ↔ emr 300; looked good, minor room to tune.)
2. **Grayscale color.** Firmware ink is grayscale; live preview is BLACK. The baked bitmap keeps
   Notesprout's real stroke color — accept a black live preview, or map to firmware gray codes
   (`DARK_GRAY=-101, GRAY=-102, LIGHT_GRAY=254`).
3. **Coordinate offset.** Firmware paints in **screen** coords; `MotionEvent` is **view** coords.
   The toolbar `setDisableAreas` + `getLocationOnScreen` offset must line up (in the PoC, view-local
   coords baked into a view-positioned bitmap aligned with no visible jump — verify under
   Notesprout's toolbar chrome).
4. **`enableFullUiAuto` absence.** Some firmwares may lack it (guarded — degrade to Generic).
5. **Firmware variance across models/OTAs.** Pen codes confirmed on Nomad/A5X2; Manta shares
   firmware. Re-verify after major Supernote OTAs.
6. **Multi-window / split-screen.** `EinkManager` exposes split-screen APIs; out of scope for v1
   but note full-UI ink behavior there is untested.

---

## 9. Test plan (both devices)

Run on **Manta** and **Nomad** (they're one firmware target; test both to be safe):

1. Draw multiple strokes — smooth live hardware ink, **no per-stroke flash or enlargement**.
2. Pen ↔ eraser ↔ lasso ↔ text transitions — single clean handoff each; no stale overlay ink.
3. Erase — strokes removed; **no ink painted along the eraser path**.
4. Task-switch away and back — live ink stays responsive (pen re-claimed on focus regain).
5. Toolbar touches — overlay released so toolbar state is visible.
6. Save → kill → relaunch — strokes re-render from Notesprout's store.
7. Snapshot / thumbnail — contains baked ink, not a transient overlay.
8. Finger input — pans/gestures, does **not** ink.
9. Screen-to-screen navigation (notebook A → B) — pen pipeline released/re-claimed cleanly.

---

## 10. Reference

- Working PoC: `SupernoteDemo/` — `SupernoteInk.kt` (client), `DrawingView.kt` (overlay lifecycle
  + deferred handoff + stylus filter), `MainActivity.kt`.
- Upstream RE: KOReader `pencil.koplugin` (`supernote_ink.lua`, `main.lua`) and
  `github.com/plateaukao/supernote_draw` (`SupernoteInk.kt` / decompiled `HandWriteClient`).
- Notesprout template to mirror: `notebook/OnyxNotebookView.kt` (hardware-overlay lifecycle),
  `notebook/GenericNotebookView.kt` (input/model/objects to reuse), `notebook/NotebookView.kt`
  (handoff hooks).
