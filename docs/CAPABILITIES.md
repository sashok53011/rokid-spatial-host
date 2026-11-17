# Capabilities - what this demo shows and how to see it

**Languages**: [Русский](CAPABILITIES.ru.md) · [Deutsch](CAPABILITIES.de.md) · English (this file)

This is the showcase inventory: every capability implemented in
`rokid-spatial-host`, which module implements it, how to demonstrate it
on the device, and where the code lives. Use it to decide **what to
build next** — see `FUTURE.md` for the ideas that grow out of these
capabilities.

**Target**: Rokid Max (3DoF) on an Android ARM64 box, mouse + keyboard.
Build/install/permissions: see README «Build & run».

**Important (phone hosts)**: before entering glasses mode the device
**must be switched to landscape (album) orientation** — the app does
not rotate it automatically; in portrait the glasses mode does not come
up correctly. Box hosts are unaffected.

Quick verification of any change:

```powershell
adb logcat -s SpatialHost FilamentRenderer MaterialFactory ModelManager \
    IblBuilder PhysicsWorld RokidMaxBridge UsbDiagnostics
```

Status legend: **✅ verified on device** · **🟡 implemented, needs
device check** · **⚪ theoretical / not yet exercised**

---

## A. Head tracking & display

| # | Capability | Status | Module | Demonstrate | Code |
|---|---|---|---|---|---|
| A1 | **Rokid Max raw-HID 3DoF tracking** — no UXR SDK; quaternion from the glasses' interrupt-IN HID | ✅ | `core-bridge` | Plug glasses in; HUD `pose:` line changes as you turn your head; `stream: pkt=… drop=…` counts live packets | `RokidMaxBridge`, `MadgwickRokid` |
| A2 | **Bridge priority chain** — Rokid → Viture → emulator fallback; app runs headless-without-glasses too | ✅ | `:app` + `core-bridge` | Start without glasses: HUD `bridge: EMULATOR · Touch/mouse-look`; plug glasses, restart: `ROKID_MAX · Rokid Max` | `MainActivity.connectChain()` |
| A3 | **Viture protocol port** (Gen2 `0x0301`/`0x7308`, Gen1 Euler@18) | 🟡 needs real Viture glasses | `core-bridge` | Same as A1 with Viture hardware attached | `VitureHidBridge` |
| A4 | **Manual look offset over IMU** — mouse drag steers an offset layered on top of head pose | ✅ | `:app` + `core-bridge` | Drag mouse while wearing glasses: view pans, `pose:` still tracks | `MainActivity.wireTouch()`, `QuaternionPoseProvider` |
| A5 | **Recenter** session frame | ✅ | `core-input` | Double-click or press `R` | `Gesture.RECENTER` → `HeadPoseProvider.recenter()` |
| A6 | **Emulator look mode** — touch/mouse-look with no hardware; optional phone-gyro remote | ✅ | `core-bridge` | Run without glasses, drag to look; `EmulatorBridge(useDeviceSensors = true)` for phone remote | `EmulatorBridge` |
| A7 | **Display control contract** — brightness / 2D-3D mode (`DisplayController`) | ⚪ contract + null-object only; no device protocol wired yet | `core-bridge` | — (HUD does not expose it yet) | `DisplayController`, `UnavailableDisplay` |
| A8 | **6DoF probe** — detects UXR SDK classes on Max Pro, ready to switch paths | ⚪ detection only | `core-bridge` | logcat shows `rokid bridge unavailable: …` / `uxrSdkPresent` | `RokidMaxBridge.UXR_ENTRY_CANDIDATES` |

## B. Panels & widgets (the spatial UI)

| # | Capability | Status | Module | Demonstrate | Code |
|---|---|---|---|---|---|
| B1 | **3DoF gaze/crosshair selection** of floating panels (ray from head pose) | ✅ | `core-input` + `:app` | Look at a panel; `focus=` in HUD changes; click to select | `RayCursor`, `LayoutEngine.hitTest` |
| B2 | **Panel arc layout** — default3-panel arc at 2.5 m, repositionable | ✅ | `core-scene` | Visible arc of cards; `defaultArc()` at startup | `LayoutEngine` |
| B3 | **Focus cycling** via swipe/arrow keys | ✅ | `core-input` + `:app` | `←`/`→` or swipe; `focus=` steps through panels | `cycleFocus()` |
| B4 | **Widget rasterization** — buttons/toggles/sliders/labels drawn into panel textures | ✅ | `core-render` + `core-scene` | Video deck shows PLAY / ‹SRC / SRC› / LOOP / SEEK | `PanelUiDrawer`, `Widgets.kt` |
| B5 | **Widget UV hit-testing** — click inside a panel maps to widget | ✅ | `:app` | Click PLAY on the deck: video pauses; click SEEK: playhead jumps | `handleWidget()` |
| B6 | **HUD overlay** — bridge, fps, pose, panels, model, animation, power, touch hint, video state | ✅ | `:app` | Always visible; `H` toggles | `updateHud()` |
| B7 | **Persistent panels** — video deck survives while video sphere hides the rest | ✅ | `core-render` | Start video: arc panels vanish, deck stays | `PanelSpec.persistent` |
| B8 | **Focus/selection highlight** in renderer | ✅ | `core-render` | Focused panel renders highlighted | `setFocusedPanel`, `applyFocus` |

## C. Video (360° sphere)

| # | Capability | Status | Module | Demonstrate | Code |
|---|---|---|---|---|---|
| C1 | **360° equirect video sphere** (r=30 m backdrop) | ✅ | `core-video` + `core-render` | Auto-starts ~3 s after launch; `V` toggles to color sphere | `StreamPlayer`, `requestVideoSphere` |
| C2 | **HLS live + VOD sources** (6 built-ins: equirect demo, Akamai live, Mux ToS/PTS, bipbop, Big Buck Bunny) | ✅ | `:app` + `core-video` | `N` steps sources; HUD `video:` shows name + state | `PUBLIC_SOURCES` |
| C3 | **RTSP / any ExoPlayer URL** — append to `PUBLIC_SOURCES` | ✅ (URL plumbing) | `:app` + `core-video` | Add `rtsp://…`, restart, `N` to it | `StreamPlayer.prepare` (rtsp dependency present) |
| C4 | **Local file playback** — mp4/mkv/webm/mov/ts/avi/m4v scanned from 4 dirs | ✅ | `:app` | `adb push x.mp4 /sdcard/Movies/`, restart, `N` cycles to it | `scanLocalVideos()`, `LOCAL_VIDEO_DIRS` |
| C5 | **Low-latency tuning** — `lowLatency=true`, tight buffer config | ✅ | `core-video` | Live HLS plays without multi-second lag | `StreamPlayer.prepare` |
| C6 | **Playback deck** — play/pause, source stepping, loop toggle, click-to-seek | ✅ | all three | Gaze-click deck widgets; HUD time updates | `videoPanelWidgets()`, `playPauseVideo()` |
| C7 | **Loop toggle** (`LOOP`) + first-frame truth (`onFirstFrame` → `streaming`) | ✅ | `core-video` | Toggle LOOP; HUD flips `loading → streaming` on first frame | `StreamPlayer.onFirstFrame` |
| C8 | **Error surfacing** — ExoPlayer errors reach HUD (`err:ERROR_CODE_…`) | ✅ | `core-video` + `:app` | Point at a dead URL, watch HUD | `StreamPlayer.onError` |

## D. 3D models (import & manipulation)

| # | Capability | Status | Module | Demonstrate | Code |
|---|---|---|---|---|---|
| D1 | **Own glTF/GLB2 parser** — GLB + JSON `.gltf`, embedded data URIs, external `.bin`, node hierarchy, PBR materials | ✅ | `core-scene` | Bundled DamagedHelmet renders with full PBR | `GltfParser` |
| D2 | **PBR materials** — baseColor (hardware sRGB), metallic-roughness, normal maps from glTF tangents, emissive + AO, sRGB/linear split | ✅ | `core-render` | Helmet: metal reflections, glow, correct shading | `MaterialFactory.buildLit` |
| D3 | **IBL** — procedural equirect → GPU-prefiltered reflections + SH irradiance; visible skybox from the same env | ✅ | `core-render` | Metals reflect the sky; background = sharp env | `IblBuilder` |
| D4 | **Key light + shadows** — directional110 klux aligned with IBL sun; model self-shadows onto lit floor slab (y=-1) | ✅ | `core-render` | Helmet shadow on the floor | `renderLoop`, `buildShadowFloor` |
| D5 | **Model pick / rotate / scale** — crosshair pick (nearest `hitBox`), drag rotates, wheel scales ×0.2…×6 | ✅ | `:app` + `core-scene` | Click model, drag, wheel | `pickModelAtCrosshair()` |
| D6 | **Local file sources** — `.glb/.gltf/.stl` auto-scanned from 4 dirs, `M` cycles demo→files | ✅ | `:app` | `adb push model.glb /sdcard/Download/`, restart, `M` | `scanLocalModelFiles()`, `MODEL_EXTENSIONS` |
| D7 | **STL import** (binary `84+50n` + ASCII) as a GltfModel adapter — closes the 3D-print loop | ✅ | `core-scene` | Push `cube.stl`, `M` to it: white cube renders | `StlParser` |
| D8 | **All-files access** — `MANAGE_EXTERNAL_STORAGE` because scoped storage hides non-media files from `READ_EXTERNAL_STORAGE` | ✅ | `:app` | First start opens the toggle once; or `adb shell appops set <pkg> MANAGE_EXTERNAL_STORAGE allow` | `requestAllFilesAccessIfNeeded()` |

## E. Sandbox (multiple objects + physics)

| # | Capability | Status | Module | Demonstrate | Code |
|---|---|---|---|---|---|
| E1 | **Scene list of objects** — each with own id, transform, scale | ✅ | `:app` + `core-render` | HUD `model: … ×N` | `ModelObject`, `ModelManager.models` |
| E2 | **Clone (`B`) / delete (`DEL`)** of the selected object | ✅ | `:app` | `B` ×2 → `×3`; `DEL` → `×2`, log `deleted …` | `spawnClone()`, `deleteSelected()` |
| E3 | **Nearest-object pick under crosshair** | ✅ | `:app` | Aim between two objects, click: the nearer one selects | `pickModelAtCrosshair()` |
| E4 | **Physics toggle (`P`)** — every object becomes a dynamic box from its scaled AABB, drops to floor `y=-1` | ✅ | `core-physics` + `:app` | `P`: objects fall and **stack**; log `physics ON — N bodies` | `togglePhysics()`, `PhysicsWorld.addModelBox` |
| E5 | **Grab & throw** — press on object to spring-drag it, release mid-flick to throw | ✅ | `core-physics` + `:app` | `P` on, press-drag object, release while moving | `tryGrabAtCrosshair()`, `PhysicsWorld.dragTo` |
| E6 | **Pose freeze on physics-off** — landing pose (pos + yaw/pitch from body quaternion) is kept | ✅ | `:app` | `P` off: objects stay where they landed | `togglePhysics()` |
| E7 | **Wheel scales selected** (only when physics off — simulation owns transforms while active) | ✅ | `:app` | Wheel on selected object | `ACTION_SCROLL` guard |
| E8 | **Scene-rebuild resilience** — settings-screen churn rebuilds the scene; models/panels/video re-pushed | ✅ | `:app` + `core-render` | Open all-files settings, return: everything returns; log `scene content re-applied` | `onSceneReady` → `reapplySceneContent()` |

## F. Animations & skinning

| # | Capability | Status | Module | Demonstrate | Code |
|---|---|---|---|---|---|
| F1 | **Rigid glTF animation clips** — LINEAR/STEP/CUBICSPLINE (Hermite), quaternion nlerp, looping, 250 ms dt clamp | ✅ | `core-scene` + `core-render` | Load an animated `.glb`; HUD `anim: clip 1/1 … 0.59/2.00s` counts up | `Animator`, `ModelManager.tickAnimations` |
| F2 | **Play/pause (`A`) and next clip (`G`)** | ✅ | `:app` | `A` → HUD `PAUSED 0.87/2.00s`; `A` resumes; `G` → log `animation clip → 0` | `requestAnimationToggle()`, `requestNextAnimationClip()` |
| F3 | **GPU skinning** — `skins` + `JOINTS_0`/`WEIGHTS_0`, ≤255 bones,4 per vertex; per-frame `bone[j] = inverse(meshWorld)·jointWorld[j]·IBM[j]` (gltfio formula) | ✅ | `core-scene` + `core-render` | Push **CesiumMan.glb** (Khronos sample), `M` to it: the figure **walks**; legs/arms move correctly | `GltfSkin`, `ModelManager.updateBones` |
| F4 | **Animation + skin round-trip export** — clips and skins re-emitted into GLB | ✅ (JVM tests) | `core-scene` | `AnimationTest`, `SkinTest` in CI | `GltfExporter` |
| F5 | **Skinning debug discipline** — the exact diagnosis path for "fan of triangles" (buffer-size trap) is documented | ✅ | docs | README «Skinning (GPU bones)» section | `buildVertexBuffer` |

To try F3 yourself:

```powershell
Invoke-WebRequest 'https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/CesiumMan/glTF-Binary/CesiumMan.glb' -OutFile CesiumMan.glb
adb push CesiumMan.glb /sdcard/Download/
adb shell am force-stop com.rokid.spatialhost
adb shell am start -n com.rokid.spatialhost/.MainActivity
# press M until HUD shows model: CesiumMan — the figure walks
```

## G. Asset round-trip & export

| # | Capability | Status | Module | Demonstrate | Code |
|---|---|---|---|---|---|
| G1 | **GLB export (`E`)** — geometry, materials, images (verbatim), node hierarchy, animations, skins | ✅ | `core-scene` + `:app` | `E` → log `ROUND-TRIP OK` with structure counts; file lands in Downloads | `GltfExporter.toGlb`, `exportCurrentModel()` |
| G2 | **OBJ export** — lossy side format, placement matrix baked into vertices | ✅ | `core-scene` | Same `E` press produces `.obj` too | `GltfExporter.toObj` |
| G3 | **Verified round-trip** — the app re-parses its own GLB in memory before writing | ✅ | `:app` | Log line proves structure equality | `exportCurrentModel()` |
| G4 | **JVM test suite (17 tests)** — parse↔export round-trip incl. bundled DamagedHelmet, STL binary/ASCII, animation sampling, skin bind pose, Mat4 | ✅ | `core-scene` | `.\gradlew.bat :core-scene:test`; CI green on push | `core-scene/src/test/…` |

## H. Platform & quality

| # | Capability | Status | Module | Demonstrate | Code |
|---|---|---|---|---|---|
| H1 | **Thermal/FPS governor** — target fps + resolution scale from thermal status | ✅ | `core-battery` | HUD `fps: 90 (target 90, thermal 0)`; heat the box, watch target drop | `ThermalGovernor` |
| H2 | **Session power report** — elapsed, mAh used, drain mA, battery % | ✅ | `core-battery` | HUD `power:` line | `PowerReport` |
| H3 | **CI on every push** — JDK17 + Android SDK35, `test assembleDebug` | ✅ | — | GitHub Actions badge / `gh run list` | `.github/workflows/ci.yml` |
| H4 | **Immersive full-screen mode** + USB attach auto-start | ✅ | `:app` | App takes over the box's display; starts on glasses plug-in | `enterImmersive()`, `usbReceiver` |
| H5 | **USB diagnostics** — one-shot bus dump for debugging detection | ✅ | `core-bridge` | `adb logcat -s UsbDiagnostics` | `UsbDiagnostics.describe` |
| H6 | **Frame diagnostics line** — viewport, entity/renderable counts, look vector (1 Hz) | ✅ | `core-render` | `adb logcat -s FilamentRenderer:D` | `renderLoop` diag |
| H7 | **Release build** — arm64, debug-key signed, ~26 MB | ✅ | `:app` | `assembleRelease` | README build section |

---

## Suggested demo script (5 minutes)

1. **Boot** → bridge connects, HUD shows `ROKID_MAX`, video autostarts.
2. **Look around** → panels track head; gaze at deck, click PLAY.
3. **`N` ×2** → switch streams; **`V`** off → color sphere + panels back.
4. **`M`** → cycle to a pushed model; drag to rotate, wheel to scale.
5. **`B` `B`** → three objects; **`P`** → they fall and stack; grab and
   throw one; **`DEL`** removes the selected.
6. **`M`** → CesiumMan: the figure walks (**F3** — the newest capability).
7. **`A`** → pause mid-stride, **`A`** resume; **`E`** → `ROUND-TRIP OK`.
8. **`H`** → clean view for a photo.
