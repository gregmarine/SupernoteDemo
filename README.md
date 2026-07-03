# SupernoteDemo

Proof-of-concept Android app proving we can tap the **Ratta Supernote** firmware's
stylus digitizer, capture stroke data, persist it, and re-render it — the groundwork for
Supernote support in [Notesprout](../Notesprout).

Validated on a **Supernote Nomad** (Android 11 / SDK 30). The same firmware is used on
the Manta, so it's expected to work there unchanged.

## What it proves

- **Firmware ink binder** — `SupernoteInk.kt` is a Kotlin port of the KOReader
  `supernote_ink.lua` plugin. It looks up the firmware Binder service
  `service_myservice` (interface token `android.demo.IMyService`) via reflection on the
  hidden `android.os.ServiceManager`, and drives it with raw `Parcel`/`transact` calls:
  claim pen (tx 0), disable areas (tx 1), set pen/eraser (tx 2), clear overlay (tx 6).
  On a real Supernote the firmware paints live ink to the e-ink overlay at sub-frame
  latency; on any other device the binder is absent and the app cleanly falls back to
  canvas rendering.
- **Stroke capture** from the normal Android `MotionEvent` stream (the firmware gives no
  point data back — only the pixels it paints).
- **Save / load** to a plain JSON file (Gson), re-rendered on relaunch.
- **Erasing** via software hit-test (stroke removal), matching how the plan handles
  eraser mode (firmware pen released, `clearAll()` issued).

### Verified on-device (Nomad, via injected touch events)

| Capability | Result |
|---|---|
| Firmware binder found | ✅ `found firmware binder "service_myservice"` |
| Draw → bake → render | ✅ 3 strokes captured |
| Save to JSON | ✅ `note.json` written with per-point x/y/pressure |
| Kill + relaunch → reload | ✅ strokes re-rendered from JSON |
| Erase | ✅ stroke removed, count updated live |

**Not yet exercised:** the live firmware overlay ink itself is driven by the physical
EMR pen hardware and can't be triggered by injected `adb` events — that needs a
hands-on stylus test on the device.

## Architecture

| File | Role |
|---|---|
| `SupernoteInk.kt` | JNI/binder client for the firmware ink daemon (the crux). |
| `DrawingView.kt` | MotionEvent capture, baked-bitmap model, erase hit-test, firmware overlay handoff. |
| `model/Note.kt` | `Note` / `Stroke` / `StrokePoint` data classes. |
| `io/NoteStore.kt` | Gson JSON save/load. |
| `MainActivity.kt` | Toolbar (Pen / Eraser / Clear / Save) + status line. |
| `SupernoteDemoApp.kt` | `HiddenApiBypass` so reflection onto hidden APIs works on Android 11+. |

The note is stored at (adb-pullable, no root):
```
/sdcard/Android/data/org.iccnet.supernotedemo/files/note.json
```

## Build & install

Requires **JDK 17** (the default JDK 26 is too new for the Android Gradle Plugin) and
the Android SDK.

```sh
export JAVA_HOME=$(/usr/libexec/java_home -v 17)
export ANDROID_HOME=$HOME/development/android-sdk   # your SDK path
echo "sdk.dir=$ANDROID_HOME" > local.properties

./gradlew :app:assembleDebug

# Install ONLY on the Supernote — pick its serial from `adb devices -l`
# (look for model:Supernote_*; do NOT install on other e-ink devices)
adb -s <SUPERNOTE_SERIAL> install -r app/build/outputs/apk/debug/app-debug.apk
```

## Follow-ups / known gaps

- Live firmware-overlay ink needs a hands-on pen test on the device.
- EMR `size` → stroke-width mapping is a placeholder (`DrawingView.emrSize`); the real
  ranges live in the upstream `plateaukao/supernote_draw` repo — tune on-device.
- Single page, single note file. No colors/pen-types UI, undo, or pressure-modulated
  width yet (pressure is captured and stored, just not rendered).
