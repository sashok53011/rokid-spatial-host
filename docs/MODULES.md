# Module catalog

Everything in this repository is split into Gradle modules so that a new
feature can be added **without touching (and without being able to break)
the modules that already work**. This file documents every module as it
exists today: its purpose, public API, invariants (what must not break),
and how to extend it. Read together with:

* `ARCHITECTURE.md` — how the modules cooperate at runtime (threads,
  queues, data flows)
* `CAPABILITIES.md` — what the demo can do and how to demonstrate it
* `FUTURE.md` — what could be built next on top of these modules

---

## 1. Module overview

| Module | Purpose | Depends on | Third-party |
|---|---|---|---|
| `:core-scene` | Pure-Kotlin scene model + asset pipeline: glTF/STL parsing, GLB/OBJ export, animation sampling, 4×4 math, panels/widgets/ray tests | *(none — leaf)* | `org.json` (test only), JUnit |
| `:core-render` | Filament rendering: render loop, materials, IBL, model GPU lifecycle, panel textures, video sphere | `:core-scene` | Filament 1.75.1 (`filament`, `filamat`, `filament-utils`) |
| `:core-physics` | Rigid-body simulation: Jolt world, dynamic boxes, static floor, spring-drag grab | *(none)* | `jolt-jni-Android:7.0.0:SpDebug` |
| `:core-video` | Video playback: ExoPlayer wrapper tuned for low-latency sphere streaming | *(none)* | Media3 1.4.1 (`exoplayer`, `hls`, `rtsp`) via `api` |
| `:core-bridge` | Glasses contracts + implementations: head-pose provider, bridge chain, quaternion math, display control, USB diagnostics | *(none)* | coroutines |
| `:core-input` | Input contracts: raw events → gestures, 3DoF ray cursor, navigation state | `:core-scene`, `:core-bridge` | — |
| `:core-battery` | Thermal/FPS governor + session power accounting | *(none)* | coroutines |
| `:app` | `MainActivity`: Android lifecycle, HUD, wiring of every module, key/touch handling, USB permissions, file scanning | all `core-*` | coroutines |

Versions are pinned in `gradle/libs.versions.toml` (AGP 8.13.0, Kotlin
2.4.21, coroutines 1.8.1, Filament 1.75.1, Media3 1.4.1). Filament is
pinned to 1.75.1 deliberately: 1.80.x requires compileSdk 37.

### Dependency rules

```
                    ┌──────────┐
                    │   :app   │  (wires everything, owns Android)
                    └────┬─────┘
       ┌────────┬────────┼─────────┬──────────┬─────────┬──────────┐
       ▼        ▼        ▼         ▼          ▼         ▼          ▼
 ┌──────────┐┌────────┐┌────────┐┌────────┐┌────────┐┌─────────┐┌─────────┐
 │core-     ││core-   ││core-   ││core-   ││core-   ││core-    ││core-    │
 │render    ││physics ││video   ││input   ││battery ││bridge   ││scene    │
 └────┬─────┘└────────┘└────────┘├───┬────┘└────────┘└─────────┘└────▲────┘
      │                          │   │                              │
      └──────────────────────────┘   └──────────────┬───────────────┘
                                    (core-input → core-bridge, core-scene)
```

Hard rules:

1. **`core-*` never depends on `:app`** and never on a sibling except the
   edges drawn above (`core-render → core-scene`,
   `core-input → core-scene + core-bridge`).
2. **`core-scene` is the leaf** — pure Kotlin, zero Android imports
   (this is why the whole asset pipeline runs as JVM unit tests in CI).
   Anything that can live here, must live here.
3. **`core-video` exposes Media3 with `api`** so the app can build
   sources without declaring Media3 itself.
4. New modules follow the same naming (`core-<domain>`), are added to
   `settings.gradle.kts`, and get a `docs` entry in this file.

---

## 2. `:core-scene` — scene model & asset pipeline

**Purpose.** Everything that can be tested without a GPU or a device:
the in-memory scene representation, all file formats, animation math,
and the ray/panel geometry. No `android.*` imports are allowed here.

### Public API

**Asset pipeline** (`scene/gltf/`, `scene/stl/`):

| Entry point | Signature | Notes |
|---|---|---|
| `GltfParser.parse` | `(bytes, defaultName, basePathResolver?) → GltfModel` | GLB and JSON `.gltf`, embedded `data:` buffers, external `.bin` via resolver |
| `GltfExporter.toGlb` | `(model) → ByteArray` | Round-trip verified: re-parsing the output must reproduce structure |
| `GltfExporter.toObj` | `(model, matrix?, name) → String` | Lossy side-format; bakes the placement matrix into vertices |
| `StlParser.parse` | `(bytes, defaultName) → GltfModel` | Binary (`84 + 50n` size rule) and ASCII; single mesh, flat normals derived at load |
| `Animator` | per model: `advance(dt)`, `sample() → Map<nodeIdx, localMatrix>` | LINEAR/STEP/CUBICSPLINE, quaternion nlerp, loops at clip duration |
| `Mat4` | `identity/mul/inverse/boneMatrix` | Column-major; `inverse` is Gauss–Jordan, returns `null` on singular input |
| `GltfParser.trsMatrix` | `(t, r, s) → FloatArray(16)` | Shared TRS → matrix |

**Key data types** (all `data class`, all immutable-ish):

* `GltfModel` — `primitives: Map<meshIdx, List<GltfPrimitive>>`, `nodes`,
  `sceneRootNodes`, `boundsMin/Max`, `materials`, `textures`,
  `animations: List<GltfAnimation>`, `skins: List<GltfSkin>`
* `GltfPrimitive` — `positions/normals/uvs/tangents`, `indices`,
  `material`, plus skinning attributes `joints: IntArray?` (4 per vertex)
  and `weights: FloatArray?` (4 per vertex, normalized)
* `GltfNode` — `matrix` **plus** base `translation/rotation/scale` (the
  TRS must survive parsing: animation channels override components of it)
* `GltfSkin` — `joints: IntArray` (node indices), `inverseBindMatrices`
  (16 floats per joint), `jointCount`
* `GltfAnimation` / `GltfChannel` / `GltfSampler` — clip, channel
  (node + path), keyframes (times, values, interpolation)

**Scene/panel geometry** (`scene/Scene.kt`):

* `Vec3` — minimal vector algebra (dot/cross/length/normalized)
* `SpatialPanel` — id, size in meters, position, label, `widgets`,
  `persistent` flag; `normal` and `basis()` for rendering
* `LayoutEngine` — ordered `panels` map; `add`, `defaultArc(count=3,
  radiusM=2.5, arcDeg=60)`, `move(id, to)`, `hitTest(dir, maxDistance=20)`
  → `PanelHit(panel, distance, u, v)`
* `hitBox(origin, dir, min, max) → Float?` — slab test used for picking
  3D models under the crosshair

**Widgets** (`scene/Widgets.kt`): `sealed class Widget` with `Button`,
`Toggle`, `Slider`, `Label`; `UvRect` for hit-testing inside a panel
texture.

### Invariants

* **Round-trip**: `GltfExporter.toGlb(GltfParser.parse(x))` must reparse
  to the same structure (geometry bit-exact, images byte-identical).
  Guarded by `GltfRoundTripTest`.
* **TRS preservation**: nodes keep base TRS even though the renderer
  consumes a baked matrix — animation and export depend on it.
* **No `android.*`**: the moment an Android import appears here, the JVM
  tests die and CI with them.
* Weights are normalized at parse time (u8 → /255, u16 → /65535); the
  renderer may assume `Σw = 1` per vertex.

### How to extend

New file format? Add a `scene/<fmt>/XParser.kt` that produces a
`GltfModel` — the entire downstream stack (renderer, physics, exporter)
works unchanged. New animation interpolation? Extend `Animator` +
`GltfSampler.interpolation` and add a test to `AnimationTest`.

---

## 3. `:core-render` — Filament rendering

**Purpose.** Owns the render thread, the Filament engine/scene, GPU
resources for models and panels, materials, IBL, and the video sphere.
The app never touches Filament directly — it posts requests.

### Public API

**`FilamentRenderer(surfaceView)`** — `SurfaceHolder.Callback`; the
single facade the app talks to:

| Category | Members |
|---|---|
| Camera | `poseSupplier: (() -> FloatArray)?` — quaternion from the active bridge |
| Lifecycle | `startThread()`, `stopThread()`, `onSceneReady: (() -> Unit)?` (main-thread callback fired after every scene build/rebuild) |
| Panels | `setPanels(specs: List<PanelSpec>)`, `updatePanelUi(id, w, h, pixels)`, `setFocusedPanel(id?)` |
| Models | `requestModel(id, GltfModel, matrix)`, `setModelTransform(id, matrix)`, `requestRemoveModel(id)` |
| Animation | `requestAnimationToggle()`, `requestNextAnimationClip()`, `animationStatus: String` (HUD snapshot) |
| Video | `requestVideoSphere(onReady: (Surface) -> Unit)`, `requestColorSphere(onDone)` |
| Metrics | `targetFps`, `measuredFps`, `onStats` callback |

`PanelSpec(id, widthM, heightM, x, y, z, persistent)` — a flat value
object; panels are **not** `SpatialPanel`s (that type is core-scene's).

**`ModelManager`** (render-thread only) — GPU lifecycle of scene objects:

* `load(id, model, matrix)`, `setMatrix(id, matrix)`, `release(id)`,
  `releaseAll()`, `shutdown()`
* `tickAnimations(nowNs)` — advances every `Animator`, writes node
  transforms (dt clamped to 250 ms so a stall cannot fling keyframes)
* `updateBones()` — per skinned renderable computes
  `bone[j] = inverse(meshWorld) · jointWorld[j] · IBM[j]` (the exact
  gltfio formula) and feeds `RenderableManager.setBonesAsMatrices`
* `toggleAnimations()`, `nextAnimationClip()`, `hudStatus()`
* `models: LinkedHashMap<String, LoadedModel>` — the sandbox's object set

**`MaterialFactory`** (object) — runtime compilation via filamat, so the
build needs no `matc` and no precompiled `.filamat` assets:
`buildColorUnlit`, `buildLit` (PBR: baseColor/MR/normal/emissive/AO,
sRGB-vs-linear sampling split), `buildPanelUi`, `buildVideoSphere`.

**`IblBuilder(engine)`** — procedural equirect → GPU-prefiltered
`reflections`, SH `irradianceCube`, `indirectLight`; `build()`/`release()`.

**`Meshes`** — `createSphere(radius=30f)`, `createQuad()`, `allocBytes`.

**`PanelUiDrawer.draw(panel, bitmap)`** — rasterizes a `SpatialPanel`
(title + widgets) into the Bitmap that backs its Filament texture.

### Invariants

* **Render-thread ownership**: everything in `ModelManager` and all
  Filament objects are touched only by the render thread. Cross-thread
  requests go through the volatile pending channels (see
  `ARCHITECTURE.md § Pending channels`).
* **Resource pairing**: every `create*` has a matching destroy in
  `release()/shutdown()/destroySceneState()`; the first-run settings
  screen destroys and rebuilds the whole scene, so nothing may be
  one-shot without going through `onSceneReady` re-push.
* **Exact buffer sizes**: Filament validates `ByteBuffer.remaining()`
  against `vertexCount × attributeSize` and silently breaks the draw on
  mismatch (this caused both the "invisible model" and the "triangle
  fan" bugs — see README pitfalls).
* Materials keep `variantFilter` empty — filtering out `skinning` would
  crash every skinned model.

### How to extend

New visual feature → decide which channel carries it (panels: pending
fields; models: `ModelOp` queue), implement the render-thread half in
`ModelManager` or `FilamentRenderer`, and expose only a request-method
to the app. Never expose Filament types across the module boundary.

---

## 4. `:core-physics` — rigid-body simulation

**Purpose.** Jolt wrapper for the sandbox: gravity, a static floor,
one dynamic box per scene object, and the spring-drag used by
grab-and-throw.

### Public API — `PhysicsWorld`

* Lifecycle: `ensureInit()` (idempotent), `isInitialized`
* Bodies: `addFloor(topY, centerX, centerZ)`,
  `addModelBox(id, px,py,pz, hx,hy,hz)` (replaces a body of the same
  id), `removeBody(id)`, `removeAllBodies()`, `hasBody(id)`, `bodyCount`
* Queries: `bodyPosition(id)`, `bodyRotation(id)` (quaternion xyzw)
* Simulation: `step(dt)` — one fixed step (caller uses `1/60 s`)
* Grab: `grab(id)`, `dragTo(x,y,z)`, `releaseGrab()`, `isGrabbing`
  — a critically-damped spring (`DRAG_KP = 88.8`, `DRAG_KD = 17.0`)
  pulls the body toward the target; release keeps the velocity (throw)

### Invariants

* Two object layers only: `LAYER_MOVING` (dynamic) vs `LAYER_STATIC`
  (floor); moving-vs-moving and moving-vs-static collide, static-vs-
  static does not.
* Capacity: `MAX_BODIES = 5000`, `MAX_BODY_PAIRS = 65536`,
  `MAX_CONTACTS = 20480`.
* `step()` must stay cheap and allocation-free-ish: it runs on the main
  thread at 60 Hz inside `physicsTick`.
* The floor top is the world contract shared with rendering:
  `FLOOR_TOP_Y = -1.0` (declared in `MainActivity`, passed to
  `addFloor`), and the shadow floor slab in `core-render` is placed at
  the same Y.

### How to extend

New body shapes → add an `add*` method next to `addModelBox`. Gameplay
queries (raycast, collision events) belong here too — they need Jolt
access but nothing from the renderer. Keep Jolt types private to the
module (the app only ever sees ids and float arrays).

---

## 5. `:core-video` — playback

**Purpose.** A thin, latency-focused wrapper around ExoPlayer for the
360° sphere: the renderer hands it a `Surface`, the app drives sources.

### Public API — `StreamPlayer(context)`

* `prepare(url, lowLatency = true)`, `setSurface(surface?)`
* `pause()`, `resume()`, `seekTo(ms)`, `setLooping(on)`, `release()`
* State: `playing`, `positionMs`, `durationMs`
* Callbacks: `onError: ((PlaybackException) -> Unit)?`,
  `onFirstFrame: (() -> Unit)?`

### Invariants

* The player never owns the Surface — the renderer does. After a scene
  rebuild the app must `setSurface` **and** `prepare` again (a bare
  `setSurface` leaves ExoPlayer in `ERROR_CODE_DECODING_FAILED`; see
  README "Storage & scene rebuilds").
* `onFirstFrame` is the single source of truth for "video is streaming"
  in the HUD.

### How to extend

New source kinds are just URLs handed to `prepare` (HLS/RTSP/file).
Audio features (spatial panning) extend this module — it is the only
place Media3 is allowed.

---

## 6. `:core-bridge` — glasses contracts & implementations

**Purpose.** Everything that speaks to hardware outside the box: pose
protocols, display control, and the math shared by them. The rest of the
app depends only on the contracts, so a new glasses model plugs in
without touching render/input.

### Contracts

```kotlin
interface GlassesBridge {
    val state: StateFlow<BridgeState>       // Disconnected/Connecting/Connected/Error
    val poses: HeadPoseProvider
    val display: DisplayController
    suspend fun connect()
    fun disconnect()
}

interface HeadPoseProvider {
    val latest: StateFlow<HeadPose>         // qx,qy,qz,qw + yaw,pitch,roll + timestampNanos
    fun recenter()
    var dof: DoF
}

interface DisplayController {
    val supported: Boolean
    suspend fun setBrightness(level: Int): Boolean
    suspend fun set3dMode(enabled: Boolean): Boolean
}
```

`UnavailableDisplay` is the null-object implementation used when a
bridge has no display protocol.

### Implementations

| Class | Hardware | Notes |
|---|---|---|
| `RokidMaxBridge` | Rokid Max, raw HID `04d2:162f` | 3DoF only (no cameras on plain Max). `poseCount`/`droppedPackets` for HUD. `UXR_ENTRY_CANDIDATES` names the UXR SDK classes to probe for a future 6DoF Max Pro path — all SDK references stay inside this file |
| `VitureHidBridge` | Viture (port of VertoXR Web's ar-bridge) | Gen2 `0x0301`/`0x7308`, Gen1 Euler@18; `poseCount`, `rawCount` |
| `EmulatorBridge` | no hardware | touch-look (mouse drag bakes into orientation) or phone gyro (`useDeviceSensors`); `look(dy, dp)` |

### Support types

* `MadgwickRokid(beta = 0.045)` — AHRS fusion of the Max's gyro+accel
  into a quaternion (`update(gx,gy,gz,ax,ay,az,dt)`, `cameraQuat()`,
  `initFromAccel`). Pure math, unit-testable in principle.
* `QuaternionPoseProvider` — `HeadPoseProvider` fed by
  `setOrientation(rawGl)` (used by the emulator path).
* `Quat` (in `Types.kt`) — `fromEulerYXZ`, `mul`, `rotate`, `forward`,
  `toEulerYXZ`, `normalize`, `conjugate`; shared by bridges and input.
* `UsbDiagnostics.describe(usbManager)` — one-shot dump of the USB bus
  for logcat debugging.

### Invariants

* **No UXR/SDK types outside `RokidMaxBridge`** — the plain Max has no
  cameras; keeping SDK references isolated means the project builds on
  any box without the proprietary SDK.
* `Quat` and `HeadPose` are the lingua franca: pose handed to the
  renderer is always a quaternion `float[4]` (x,y,z,w).
* Bridges are constructed fresh on every `connectChain()` — no static
  state, no leaked USB file descriptors (always `disconnect()` first).

### How to extend

Implement `GlassesBridge` (+ `HeadPoseProvider`) for new hardware, add it
to `MainActivity.connectChain()` in priority order, and register its USB
VID in the diagnostics docs. That is the whole integration surface.

---

## 7. `:core-input` — events → gestures

**Purpose.** The producer/consumer split that keeps input testable:
raw events in, semantic gestures out, and the 3DoF ray cursor.

### Public API

* `sealed class InputEvent` — `Drag(dxPx, dyPx)`, `Tap`, `DoubleTap`,
  `DragEnd`, `Back`, `Key(keyCode)`
* `enum class Gesture` — `SELECT`, `RECENTER`, `SWIPE_LEFT`,
  `SWIPE_RIGHT`, `TOGGLE_HUD`, `BACK`
* `GestureRecognizer(swipeThresholdPx = 120f).onEvent(e) → Gesture?` —
  thresholds and the tap-vs-drag race fix live here (a finished drag
  must not swallow the next click — `DragEnd` exists for that)
* `RayCursor(layout).update(pose: HeadPose) → PanelHit?` — 3DoF ray
  through the session frame, nearest panel wins
* `NavigationState` — `focusedPanelId`, `selectedPanelId`, `hudVisible`

Also mapped in the recognizer: DPAD/L1/R1 → swipes, ENTER/BUTTON_A →
select, `R` → recenter, `H` → HUD toggle.

### Invariants

* Recognizers must stay **stateless with respect to the scene**: they
  translate events, they never mutate panels or models. This is what
  lets hand-tracking or a gamepad become a new `InputEvent` producer
  later without touching gesture logic.
* Every `Drag` sequence ends with `DragEnd` (or a `Tap` that consumes
  it) — otherwise the next click is misclassified.

### How to extend

New input device → emit `InputEvent`s; new interaction → add a
`Gesture` + a branch in `onEvent`. Keep pixel thresholds configurable
(the constructor parameter exists for that).

---

## 8. `:core-battery` — thermal & power

**Purpose.** Keep the box inside its thermal budget and report what a
session cost.

### Public API

* `ThermalGovernor(context)` — `budget: StateFlow<Budget(targetFps,
  resolutionScale, thermalStatus)>`, `start()/stop()`,
  `frameIntervalNanos()` (the render loop paces frames from this)
* `PowerReport(context)` — `stats: StateFlow<Stats(elapsedSeconds,
  mahUsed, drainMa, batteryPercent)>`, `start()`, `sample()`

### Invariants

* The governor **suggests**, it never enforces: only the render loop's
  `targetFps` consumes it. A bug here must degrade FPS, never crash.
* Power numbers are estimates (battery-stats deltas); they are HUD
  telemetry, not measurements to trust for engineering decisions.

### How to extend

New thermal policy → adjust how `Budget.targetFps` is derived. New
telemetry → add fields to `Stats` (the HUD already renders them).

---

## 9. `:app` — MainActivity (the wiring layer)

**Purpose.** The only module allowed to know about Android UI, USB
permissions, file scanning, and *all* the core modules at once. It owns
**policy** (which bridge first, which source next, what a key means);
the modules own **mechanism**.

### What lives here today (~1580 lines)

| Area | Members |
|---|---|
| Scene objects | `ModelObject(id, label, bounds, pos, yaw/pitch, scale)`, `sceneModels`, `selectedModelId`, `pickModelAtCrosshair()`, `spawnClone()`, `deleteSelected()` |
| Model sources | `loadCurrentModel()`, `nextModelSource()`, `scanLocalModelFiles()` (`.glb/.gltf/.stl` from `LOCAL_MODEL_DIRS`), `exportCurrentModel()` |
| Video | `toggleVideo()`, `startVideo()/stopVideo()`, `stepVideoSource()`, `videoPanelWidgets()`, `PUBLIC_SOURCES` (6 built-in streams), `scanLocalVideos()` |
| Physics | `physicsTick` (60 Hz), `togglePhysics()`, `addPhysicsBoxFor()`, grab wiring in `tryGrabAtCrosshair()`/`dragGrabbedModel()` |
| Panels | `panelSpecs()`, `pushPanelUi()`, `handleWidget()`, `reapplySceneContent()` (the `onSceneReady` re-push) |
| Input | `wireTouch()`, `onKeyDown()`, `handleGesture()`, `selectAtCrosshair()`, `cycleFocus()` |
| HUD | `hudTick` (1 Hz), `updateHud()` |
| Bridge | `connectChain()` — Rokid → Viture → emulator fallback |
| Android | USB attach receiver, storage/all-files permissions, immersive mode |

### Key bindings (for reference; full table in README)

`A` animation play/pause · `B` clone · `DEL` delete · `E` export ·
`G` next animation clip · `M` model source · `N` video source ·
`P` physics · `V` video sphere · `H` HUD · `R` recenter ·
wheel = scale selected / next video source · arrows = focus

### Invariants

* **No Filament, no Jolt, no Media3 types** appear here — only request
  methods and plain data. If you find yourself importing one, the logic
  belongs in the owning module.
* All scene mutations requested from the UI thread go through the
  renderer's channels; never mutate render-thread state directly.
* Key handling has a fixed order: feature keys (`V/N/M/P/E/B/DEL/A/G`)
  return early; everything else falls through to the gesture
  recognizer — a new feature key must not shadow `H`/`R`/DPAD.

### How to extend (the "feature" recipe)

1. Mechanism in the owning `core-*` module (with tests if it is pure
   Kotlin).
2. A request-method pair on `FilamentRenderer`/module facade if it needs
   the render thread.
3. One `when` branch in `onKeyDown` (or a gesture) + one HUD line.
4. Document it in `CAPABILITIES.md` and the README controls table.

---

## 10. Rules for designing a new module

A checklist distilled from what already works in this codebase:

1. **Name it `core-<domain>`** and add it to `settings.gradle.kts`.
2. **Declare the narrowest dependencies.** Siblings only where the
   diagram above allows; never `:app`.
3. **Pick the thread before writing code.** Render-thread state stays
   behind a request method; main-thread state (physics tick, HUD) stays
   in `MainActivity` or its own handler; anything else needs an explicit
   contract (see `ARCHITECTURE.md`).
4. **Cross threads only with immutable data** — volatile fields holding
   value objects, or a queue of small ops (`ModelOp` is the template).
   No shared mutable objects, no locks across modules.
5. **Expose contracts, not implementations** — the `GlassesBridge`
   pattern: the consumer depends on an interface defined next to its
   data types (`HeadPose`, `BridgeState`).
6. **Pure logic → `core-scene` (or the new module's own JVM-testable
   part)** so CI covers it. Device-only code gets log diagnostics
   instead (one line per state transition, stable tag).
7. **Every create has a destroy**, and every one-shot push has an
   `onSceneReady` re-push path — the first-run settings screen *will*
   rebuild the scene.
8. **Verify exact buffer/size contracts** with Filament (or any
   foreign engine): validate sizes yourself, the failure mode is a
   silent visual corruption, not an exception.
9. **Document in three places**: this file (module), `CAPABILITIES.md`
   (demo), README controls (user-facing keys).
