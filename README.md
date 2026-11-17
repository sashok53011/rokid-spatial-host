# rokid-spatial-host

**Languages**: [Русский](README.ru.md) · [Deutsch](README.de.md) · English (this file)

**Docs**: [Modules](docs/MODULES.md) · [Architecture](docs/ARCHITECTURE.md) · [Capabilities](docs/CAPABILITIES.md) · [Future](docs/FUTURE.md)

[![CI](../../actions/workflows/ci.yml/badge.svg)](../../actions/workflows/ci.yml)

Native Android spatial host for **Rokid Max** AR glasses — an immersive
3DoF environment built in Kotlin + Filament: 360° video sphere, floating
spatial panels, glTF models with full PBR materials, rigid-body physics
and model export. Works mouse-only out of the box (the target control box
has no touchscreen) and drives the glasses through **raw USB HID** — no
vendor SDK required.

The project is a complete **glTF prototyping pipeline**:

```
import (D1) → materials/IBL (D2) → physics (D3) → export (D4)
```

Everything below has been implemented, verified on real hardware and
committed step-by-step (see [Git history](#roadmap--todo)) so the whole
path can be reproduced.

## Status

| Feature | State |
|---|---|
| Gradle multi-module project (8 modules) | ✅ builds (`assembleDebug` / `assembleRelease`) |
| Filament render loop, frame pacing, pose-driven camera | ✅ |
| 360° backdrop sphere + spatial panels (runtime-compiled materials) | ✅ |
| 3DoF ray select / focus / recenter, touch + keys | ✅ |
| Bridge chain: **Rokid raw-HID 3DoF (no UXR SDK!)** → Viture → emulator | ✅ connected & tracked on OPi5Plus |
| Thermal/FPS governor + session power report (mAh, mA) | ✅ |
| **360° video sphere: HLS/equirect/RTSP → media3 → Filament Stream** | ✅ V=toggle N=source |
| Viture IMU protocol (Gen2 `0x0301`/`0x7308`, Gen1 Euler@18) | ✅ implemented, needs real-glasses verification |
| **D1a: glTF import (own GLB/glTF2 parser → Filament, lit material)** | ✅ DamagedHelmet demo renders |
| **D1b: model pick / rotate / scale / local-file switch** | ✅ tap=pick, drag=rotate, wheel=scale, M=source |
| **D2a: baseColor textures (hardware sRGB) + PBR factors** | ✅ DamagedHelmet textured |
| **D2b: metallic-roughness + normal maps (glTF TANGENTS)** | ✅ verified on device |
| **D2c: IBL — procedural equirect → GPU-prefiltered reflections + SH** | ✅ metals reflect the env |
| **D3a: Jolt physics — gravity, floor, dynamic model body (P toggle)** | ✅ exact landing verified |
| **D3b: grab-and-throw — spring drag, mouse-flick throws** | ✅ 3.16 m throw verified |
| **D4: OBJ + glTF export (E) with verified round-trip** | ✅ Phase D complete |
| **Panel widgets + video deck: PLAY/SRC/LOOP/SEEK via gaze-click** | ✅ |
| **IBL skybox background + lit shadow floor + dynamic shadows** | ✅ |
| **Emissive + AO maps; sRGB/linear color-space split fixed** | ✅ |
| **STL import + all-files model scan + scene-rebuild resilience** | ✅ |
| **CI (GitHub Actions) + 17 JVM asset-pipeline tests** | ✅ |
| **Sandbox: multiple objects, B-clone/DEL-delete, physics stacking** | ✅ |
| **glTF animations: rigid clips (LINEAR/STEP/CUBICSPLINE) + export** | ✅ |
| **Skinning: JOINTS/WEIGHTS → GPU bones (≤255), gltfio formula** | ✅ |
| Release build (debug-key signed, arm64-only ~26 MB) | ✅ |

## Requirements

**Hardware**

* Rokid Max glasses (3DoF, USB-C; HID id `04d2:162f`)
* Android 12+ ARM64 host box with USB host — developed on an Orange Pi
  5 Plus; any box works if it can run the APK and drive the glasses'
  interrupt-IN HID interface
* USB mouse (required), USB keyboard (recommended — several shortcuts)

**Software**

* JDK 17+
* Android SDK platform 35 (Gradle wrapper downloads the rest)
* `adb` on your PATH

## Build & run (reproducible)

```powershell
git clone <your-fork> rokid-spatial-host
cd rokid-spatial-host
.\gradlew.bat assembleRelease          # ~26 MB, arm64, debug-key signed

# target box (adjust the IP to your device):
adb connect 192.168.0.173:5555
adb install -r app\build\outputs\apk\release\app-release.apk
adb shell pm grant com.rokid.spatialhost android.permission.READ_EXTERNAL_STORAGE
adb shell am start -n com.rokid.spatialhost/.MainActivity
```

Useful logcat filters (each phase logs under its own tag):

```powershell
adb logcat -s SpatialHost FilamentRenderer MaterialFactory ModelManager \
    IblBuilder PhysicsWorld RokidMaxBridge UsbDiagnostics
```

First start: allow the storage permission when asked (it makes local
video/model scanning work), plug the glasses into the box's USB port -
the bridge connects automatically and the 360° video auto-starts ~3 s
later.

**Important (phone hosts)**: before entering glasses mode the device
**must be switched to landscape (album) orientation** — this is
required, the app does not rotate it automatically. On a phone in
portrait the glasses mode will not come up correctly.

Requirements recap: `gradle.properties` pins **Filament 1.75.1** (1.80.x
needs compileSdk 37), **Kotlin 2.4.x**, AGP from the version catalog.

### Tests & CI

The asset pipeline is guarded by pure-JVM unit tests in `core-scene`
(glTF parse ↔ GLB export round-trip incl. the bundled DamagedHelmet,
binary/ASCII STL parsing):

```powershell
.\gradlew.bat :core-scene:test
```

GitHub Actions (`.github/workflows/ci.yml`) runs `test assembleDebug`
on every push/PR — JDK17 + Android SDK35 on the runner.

## Controls (mouse-only friendly)

| Input | Action |
|---|---|
| Mouse drag | Look around (on top of head tracking) — **rotates the selected model**; in physics mode a press on the model **grabs it** (drag = carry, flick = throw) |
| Left click | Select model under crosshair, else panel (click empty space = deselect) |
| Double click / Right click | Recenter session frame |
| Middle click / `V` | Video sphere on/off (360° backdrop ↔ live stream) |
| Wheel | Next video source — **scales the selected model** (×0.2…×6) |
| `M` | Next model source (bundled demo → local files → …) |
| `P` | Physics on/off — every object becomes a rigid body and they **stack** on the floor |
| `B` | **Clone** the selected object (spawns 1 m above, becomes the new selection) |
| `DEL` | **Delete** the selected object |
| `A` | Animation play/pause (glTF clips autoplay on load) |
| `G` | Next animation clip |
| `E` | Export the current model as `.glb` + `.obj` into Downloads (round-trip verified) |
| `N` | Next video source |
| Gaze-click deck widgets | Bottom "Video" deck: PLAY/PAUSE · ‹SRC/SRC› · LOOP toggle · SEEK slider (click = seek) |
| `H` | Toggle HUD |
| `R` / arrows | Recenter / cycle focused panel |

### Workflow: models

1. Push models to the box: `adb push MyModel.glb /sdcard/Download/`
2. Restart the app (or press `M` after a restart) — `.glb`/`.gltf` files
   are auto-scanned from `/sdcard/Download`, `/sdcard/models`,
   `/sdcard/Documents`, `/sdcard/Movies` (external `.bin` next to a
   `.gltf` is resolved too).
3. Click the model to select it, drag to rotate, wheel to scale, `M` to
   cycle sources.
4. `E` writes `<name>.glb` + `<name>.obj` into Downloads. The app
   re-parses its own GLB in memory and logs `ROUND-TRIP OK` with
   structure counts; the exported file is then importable via `M`.

### Workflow: video

Wheel/`N` cycles **360 equirect → LIVE HLS → ToS → PTS → bipbop → BBB →
local files**. Local videos are scanned from `/sdcard/Movies`,
`/sdcard/Download`, `/sdcard/DCIM/Camera`, `/sdcard/video`
(mp4/mkv/webm/mov/ts/avi/m4v). Any `rtsp://` or ExoPlayer URL can be
added to `PUBLIC_SOURCES` in `MainActivity.kt`. `V` toggles the sphere.

### Workflow: physics

`P` turns on Jolt: the model becomes a dynamic box built from its scaled
bounds and drops onto a static floor at `y = -1` (exact resting contact:
`y = floor + half-height`). While simulating:

* press **on the model** to grab it (spring-dragged to the crosshair +
  a mouse-driven offset in the camera plane),
* release while moving the mouse to **throw** it,
* press `P` again — the landing pose is kept (position + yaw/pitch from
  the body quaternion).

## Architecture

```
app                     MainActivity, HUD, bridge chain, USB permission,
                        gestures, model/video/physics/export wiring
core-bridge             GlassesBridge / HeadPoseProvider contract
  ├─ rokid/             RokidMaxBridge — raw-HID 3DoF (no UXR SDK)
  ├─ viture/            VitureHidBridge (USB port of VertoXR Web's ar-bridge)
  └─ emulator/          EmulatorBridge (touch look / phone gyro remote)
core-render             FilamentRenderer (render loop, channels for
                        pending scene mutations), Meshes, MaterialFactory
                        (filamat runtime compilation), IblBuilder, ModelManager
core-scene              SpatialPanel, LayoutEngine, ray tests, GltfParser,
                        GltfExporter (pure Kotlin, unit-testable)
core-input              GestureRecognizer, RayCursor, NavigationState
core-video              StreamPlayer (ExoPlayer, tight low-latency buffers)
core-battery            ThermalGovernor (FPS budget), PowerReport (mAh)
core-physics            PhysicsWorld (Jolt via jolt-jni): bodies, spring drag
```

Key patterns:

* **One contract for head pose** — `HeadPoseProvider`; the renderer only
  consumes `poseSupplier: () -> FloatArray` (quaternion), so new bridges
  (Max Pro 6DoF, hand tracking) plug in without touching render/input.
* **Render-thread scene mutation** — everything the UI thread wants the
  renderer to do goes through volatile "pending" fields
  (`pendingModel`, `pendingPanels`, `pendingModelTransform`, video
  sphere requests) consumed at the top of the render loop.
* **Runtime materials** — `.material()` sources are compiled on-device
  with filamat (`MaterialFactory`), so the build needs no `matc` and no
  precompiled `.filamat` assets.

## How it works — phase notes & pitfalls (for reproducers)

Each section records the non-obvious facts needed to rebuild this path.

### Rokid Max raw-HID bridge

The glasses expose an interrupt-IN HID interface streaming 64-byte `0x11`
IMU reports at ~450 Hz: **f32 accel @ byte 9, f32 gyro @ byte 21**,
device frame is rotated Rx(−90°) vs the world frame (validated by offline
simulation + a level-from-accel sanity gate at boot). The bridge has
stall detection (3 s without packets → reconnect), auto-resume, and a
USB-detach broadcast receiver; the HUD line `stream : pkt=… drop=…`
makes a dead/tugged cable obvious. Only one app can hold the USB device
at a time — stop `com.rokid.spatialstudio` before running this app.

### 360° video sphere

A filamat `video_external` material samples a Filament `Stream` wrapped
around a `SurfaceTexture` fed by media3. In shader code samplers are
referenced as the **macro** `materialParams_<name>` (dot-syntax is only
for uniform parameters). The equirect sphere uses `culling(NONE)`.

**Pitfall — upside-down video**: frames reach the shader GL-oriented
(row0 = bottom) while the panorama convention expects v = 0 = top, so
`getUV0()` must be flipped (`vec2(uv.x, 1.0 - uv.y)`) or the whole
360° world renders upside down (roofs on top → water on top). This bug
lived since Phase B2 until verified with a street-panorama screencap.

### D1 — glTF import

`GltfParser` (core-scene) handles GLB containers, `data:`/external
buffers, bufferViews/accessors (f32/u16/u32, strided), node `matrix` |
TRS hierarchy, and computes model-space bounds by walking the hierarchy.

Pitfalls solved (each cost a debugging session):

1. **Invisible model** — `IndexBuffer` must be allocated with *exact*
   byte size (`indexCount * 2|4`). A helper sized "like floats" (×4)
   makes Filament **silently drop the draw** (no error, zero pixels).
   Same bug class existed earlier in `Meshes.kt`.
2. **Normals in Filament** — there is no `VertexAttribute.NORMAL`;
   normals live in the **TANGENTS** attribute as a quaternion frame.
   Pack `(tangent, bitangent, normal)` with
   `MathUtils.packTangentFrame(...)`; bitangent = ±(n × t) with the
   sign stored in the quaternion's `w` (glTF's TANGENT.w).
3. **Lighting** — directional intensity is in lux (110 000 ≈ clear day),
   the direction is where light *travels*; camera exposure uses the
   physical `setExposure(sunny f/16)` recipe. Without IBL, metals render
   nearly black (fixed in D2c).

### D2 — materials & IBL

* **baseColor is sRGB** — upload as `InternalFormat.SRGB8_A8` and let the
  GPU linearize on sample (cheaper and more correct than a CPU LUT).
  Normal/MR maps stay plain `RGBA8` (linear data!).
* **UV0** must be declared for every lit renderable; zero-fill when the
  model has none so the vertex layout stays valid.
* **MR sampling**: `metallic = mrFactor * tex.b`,
  `roughness = clamp(mrFactor * tex.g, 0.04, 1)`.
* **Normal maps** are assigned in *tangent space* and **before**
  `prepareMaterial()` (see Filament's normal-mapping doc example).
* **IBL (D2c)**:
  * environment = procedural equirect rendered with *exactly* the
    mapping of Filament's `equirectToCube` material:
    `u = 0.5 + atan2(x, z)/2π`, `v = 0.5 - asin(y)/π` (v=0 = zenith);
  * GPU prefilter via `IBLPrefilterContext` from filament-utils:
    equirect → cubemap → `SpecularFilter` roughness mips →
    `IndirectLight.reflections()`;
  * **the AAR ships `libfilament-utils-jni.so` but no Java class loads
    it** — call `System.loadLibrary("filament-utils-jni")` yourself;
  * the equirect texture must be `SAMPLER_2D` with a **full mip chain**
    (`levels == ilogb(width)+1`) and `GEN_MIPMAPPABLE` usage, otherwise
    `generateMipmaps` aborts with a Precondition panic;
  * diffuse = 3-band SH of the same analytic environment:
    `sh[i] = c_i · Ĉ_l · N_i / π` where `Ĉ = [π, 2π/3, π/4]` (truncated
    cos) and `N` are the Sloan polynomial constants — i.e. **"Fd_Lambert()
    is baked in the SH"** (verified against `CubemapSH.cpp` and the
    shipped `Irradiance_SphericalHarmonics` shader; sanity check:
    `sh[0]` equals the environment's average irradiance).

### D3 — physics (jolt-jni)

Dependency: `com.github.stephengold:jolt-jni-Android:7.0.0:SpDebug@aar`.

Pitfalls solved:

1. **SpRelease's `classes.jar` is R8-stripped to nothing** in published
   releases — compile against **SpDebug**.
2. The AAR declares **minSdk 33** while the box runs Android 12 — add
   `<uses-sdk tools:overrideLibrary="com.github.stephengold.joltjni" />`.
3. `JoltPhysicsObject.startCleaner()` needs `java.lang.ref.Cleaner`
   (**API 33+**) — guard it and `close()` transient native wrappers
   (shapes, `BodyCreationSettings`) explicitly; `RVec3`/`Quat`/`Vec3` are
   pure Java and never leak.
4. Init order matters: `loadLibrary("joltjni")` → allocators →
   `newFactory()/registerTypes()` → layer tables → `PhysicsSystem.init`.
5. Dragging = spring force `F = m·(kp·Δx − kd·v)` toward a target point
   (Jolt's own drag is a spring too); mass is estimated from the box
   volume at Jolt's default density (1000 kg/m³).

### D4 — export

`GltfExporter` re-emits what the parser consumed: node matrices, meshes
with POSITION/NORMAL/UV0 + u16/u32 indices (POSITION accessors carry the
spec-required min/max), PBR materials, samplers/textures, and the
**original encoded image bytes** copied verbatim into the GLB BIN chunk —
so textures round-trip without re-encoding. GLB chunks are 4-byte aligned
(JSON padded with spaces, BIN with zeros).

Downloads are written through **MediaStore** (`IS_PENDING` flow) on Q+ —
use a MIME type whose canonical extension matches the file (e.g.
`model/obj`, not `text/plain`, or the provider appends `.txt`). The
round-trip is verified in-memory right after export: re-parse the GLB and
compare primitive/node/texture/material counts.

### Input note: tap vs drag

`moved` must be computed from the **cumulative** delta since
`ACTION_DOWN`, not from per-event deltas — slow drags (small per-event
moves, e.g. an `input swipe` with a long duration or a careful human)
were previously misread as taps.

Also: a drag's `ACTION_UP` must emit `InputEvent.DragEnd` — otherwise
the recognizer keeps its `dragging` flag and **swallows the next real
click** (symptom: you must click twice; in adb scripts: `input tap`
right after `input swipe` did nothing).

### Panel widgets (the video deck)

* `TextureSampler` is **native-backed** — a companion-object val would be
  created in `<clinit>` *before* `Filament.init()` loads libfilament-jni
  and dies with `UnsatisfiedLinkError: nCreateSampler`. Keep such vals
  `by lazy` (first use happens on the render thread, after engine
  creation).
* Widget bitmaps map to panel UVs **without** a V flip (raw `getUV0()`) —
  unlike the video sphere, a plain `setImage` bitmap upload already lines
  canvas-top with panel-top. The wrong direction shows instantly as the
  whole deck mirrored vertically; don't reuse the video's flip here.
* `SpatialPanel.persistent` panels survive the immersive-video hide —
  logged as `3 hidden, 1 persistent` (the arc panels disappear, the deck
  stays for control).

### Skybox & shadows

* The skybox reuses `IblBuilder.reflections` (mip0 = sharp env):
  `Skybox.Builder().environment(...)`. In skybox mode the flat color
  sphere is NOT created (`sphereEntity = -1`) and every sphere code path
  guards `>= 0`. Video still works — the video sphere builds on demand
  and hides floor + arc panels (the persistent deck stays).
* Shadows need a **LIT receiver**: unlit panels ignore lights entirely,
  so a lit floor slab (`lit_pbr`, matte, y = −1 = physics floor) was
  added — without it the model would cast onto nothing. Verified with a
  temporary straight-down sun: the helmet's silhouette lands on the floor
  (with the angled sun aligned to the IBL env, the shadow sits behind the
  deck and only shows from the sides).
* Aliasing trap: several `updatePanelUi` calls sharing ONE pixel buffer
  made every panel display the last-drawn image — the channel now clones
  pixels.

### Storage & scene rebuilds

* **Scoped storage hides non-media files**: `READ_EXTERNAL_STORAGE` only
  exposes media — the walk can never see foreign `.glb`/`.stl`/video
  pushed via adb (only the app's own MediaStore exports). The scanners
  therefore need `MANAGE_EXTERNAL_STORAGE`; the app opens its all-files
  toggle once at first start (grant once — or
  `adb shell appops set <pkg> MANAGE_EXTERNAL_STORAGE allow`).
* **First-run settings churn destroys the surface** → the render loop
  fully stops and rebuilds (engine, scene, materials). One-shot pending
  channels (panels, UI textures, model, video) were already consumed —
  without re-push the scene came back EMPTY. Fixed via the renderer's
  `onSceneReady` callback (main thread): re-set panels, re-push UI
  textures, re-request the model, and **re-prepare** the video (a bare
  `setSurface` after churn leaves ExoPlayer in
  `ERROR_CODE_DECODING_FAILED`).

### Skinning (GPU bones)

* **Vertex-buffer sizes must be EXACT**: Filament validates
  `ByteBuffer.remaining()` against `vertexCount × attributeSize` and
  silently breaks the draw on mismatch (same trap as index buffers —
  "the invisible-model bug"). JOINTS_0 as `USHORT4` needs **8 bytes per
  vertex** (`allocateDirect(vertexCount * 8)`); an `alloc(vertexCount*4)`
  (16 B/vertex, sized "like floats") produced the classic *fan of
  triangles* instead of the character. Symptom → check buffer math first.
* **IBM are Armature-relative in some exporters** (CesiumMan:
  `jointWorld·IBM = I` only with the Armature as root, not the scene
  root) — harmless for `bone = inverse(meshWorld)·jointWorld·IBM` because
  the shared prefix cancels, but don't "verify" bind pose by testing
  `jw·IBM == I` at scene root — you'll get a false negative.
* **Diagnosis order that worked**: (1) JVM-side asserts (Σw==1, index
  ranges, bone translations vs device log — must match bit-for-bit),
  (2) skinning OFF + 3-slot VB → proves geometry, (3) identity bones →
  isolates vertex attributes from bone values, (4) real bones.

## Debugging tips

* **Screenshots**: PowerShell `>` corrupts binary output — use
  `cmd /c "adb exec-out screencap -p > shot.png"` (the PNG magic must
  start `89-50-4E-47`).
* **Aim races**: the glasses are on a live head; any scripted
  `input swipe` aim races the IMU. Recenter (`keyevent 46`), then read
  the renderer's `diag: look=(…)` line (logged every second) to see where
  the camera actually points.
* `input swipe x1 y1 x2 y2 duration` generates real MOVE events — good
  for testing drag logic; `input tap` is a tap; `input keyevent <code>`
  for keys (`E`=33, `M`=41, `N`=42, `P`=44, `R`=46, `V`=50).
* The render loop logs `diag: vp=… entities=… renderables=…` — a missing
  model shows up as a low `renderables` count before you look at pixels.

## Roadmap / TODO

### Done (this repository)

- [x] Phase 0–1: Filament host, panels, gestures, HUD, thermal governor
- [x] Rokid raw-HID bridge (stall/auto-resume), Viture protocol port
- [x] Phase B2: 360° video sphere (HLS/equirect/RTSP), source cycling
- [x] **D1**: glTF import + manipulation (pick/rotate/scale/switch)
- [x] **D2**: PBR materials — baseColor, MR, normal maps, IBL
- [x] **D3**: Jolt physics — gravity, floor, grab-and-throw
- [x] **D4**: glTF/OBJ export with verified round-trip

### Near-term improvements (small, concrete)

- [x] **Emissive + AO maps** from glTF — done: parsed (emissiveFactor,
      emissive/occlusion textures + strength), `lit_pbr` gained
      emissiveMap/aoMap; also fixed the color-space split (baseColor +
      emissive sRGB, normal/MR/AO linear — data maps were wrongly
      sRGB-decoded before)
- [x] **Visible skybox** from the IBL environment — done: skybox from the
      prefiltered reflections (mip0); in skybox mode the flat color
      sphere is skipped (kept as fallback if IBL fails)
- [x] **Shadows** — done: key light casts, the model casts/receives, a
      new lit floor slab (y = -1, matching the physics floor) receives;
      arc panels excluded (unlit cards must not cast)
- [ ] **Tonemapping/HDR**: post-processing is currently disabled for
      perf — re-enable ACES with the bloom pass for emissive/neon looks
- [x] **STL import** (binary + ASCII → GltfModel adapter; the 3D-printing
      loop closes: print → push → scan → edit → export) — **OBJ import**
      still open
- [x] **Multiple simultaneous models** — done: the sandbox holds a scene
      list (`ModelObject` per object with its own id): pick nearest under
      the crosshair, `B` clones the selection, `DEL` deletes, wheel scales
      the selected one
- [x] **Physics: stacked scenes** — done: every object gets its own Jolt
      body (`bodyIds`), `P` drops them all onto the floor where they
      stack/collide; grab-and-throw works per body. Still open: explicit
      collision callbacks and raycast queries for gameplay
- [x] **Panel widgets**: buttons/sliders/checkboxes rendered into panel
      textures with UV hit-testing — done: `Widgets.kt` + `PanelUiDrawer`
- [x] **Playback controls panel** for video — done: the persistent
      `video-deck` panel (PLAY/PAUSE, SRC stepping, LOOP, click-to-seek
      via `StreamPlayer` position/duration/seek)
- [x] **CI**: GitHub Actions workflow (`test assembleDebug` on push/PR) —
      done: `.github/workflows/ci.yml`, green on every push
- [x] **Parser test suite**: round-trip tests for `GltfParser` ↔
      `GltfExporter` + STL + animation/skin round-trip — done: 17 JVM
      tests in `core-scene`

### Medium-term (real features)

- [ ] **Phase C — spatial web browsing**: WebView rendered into a quad,
      PC desktop sharing via the `verto-display-driver` IPC bridge
      (Python `vdd` agent over LAN)
- [x] **glTF animations (rigid)** — done: `animations` parsed (channels +
      samplers, LINEAR/STEP/CUBICSPLINE with proper Hermite + quaternion
      nlerp), an `Animator` per object ticks on the render thread and
      writes node transforms (looping playback, frame-delta clamp);
      exported back to GLB (TRS nodes). Controls: `A` play/pause, `G`
      next clip
- [x] **Skinning** — done: `skins` (joints + inverse bind matrices) and
      `JOINTS_0`/`WEIGHTS_0` parsed (u8/u16 indices, normalized u8/u16
      weights); per renderable `Builder.skinning(≤255)` with bone slots in
      the vertex buffer; every frame the render thread computes
      `bone[j] = inverse(meshWorld) · jointWorld[j] · IBM[j]` — the exact
      gltfio formula — and feeds `setBonesAsMatrices`. Exported back to
      GLB. Characters with animations now move correctly
- [ ] **Morph targets**, Draco/KTX2-compressed glTF (needs a decoder —
      draco-android or a pure-Kotlin path)
- [ ] **Full material export**: `.mtl` for OBJ, re-encoded textures in
      glTF, KHR extensions (unlit, clearcoat, transmission)
- [ ] **Scene export**: multiple objects + transforms + physics state as
      one glTF scene (round-trip of the whole workspace)
- [ ] **Spatial audio** for video (media3 audio + head-relative panning)
- [ ] **6DoF upgrade path**: Rokid Max Pro via the isolated UXR SDK hook
      (`RokidMaxBridge.UXR_ENTRY_CANDIDATES`) or inside-out tracking
      from a phone camera
- [ ] **Hand/controller tracking** (Max Pro) plugging into the existing
      `InputEvent` producer/recognizer split
- [ ] **Voice control** (offline STT) for hands-free recenter/export/
      source switching
- [ ] **Gamepad/keyboard-first UX** overlay with shortcut help

### Long-term / experimental (Phase E and beyond)

- [ ] **Cloud scene sync**: serialize the workspace (D4 already writes
      glTF) → upload → multi-user sessions viewing the same scene
- [ ] **AIDL plugin API**: load feature modules (new panels, importers,
      games) at runtime without rebuilding the host
- [ ] **Cloud rendering / remote GPU**: stream a desktop-class renderer
      into the3DoF host
- [ ] **Procedural terrain + vegetation** demos on top of the physics
      and glTF stack
- [ ] **Recording**: capture the3D view (or composite of video sphere +
      panels) to file for sharing
- [ ] **Wear detection** (glasses on/off via IMU activity) → auto
      pause/resume video and dim the UI
- [ ] **Boot autostart service** + per-device provisioning (the box is
      headless; today the app starts via USB-attach intent)

## License

No license file yet — add one (MIT/Apache-2.0) before publishing.
