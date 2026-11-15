# rokid-spatial-host

Native Android spatial host for **Rokid Max** AR glasses — a VertoXR-Web-style
immersive environment (360° backdrop, floating spatial panels, 3DoF ray
navigation) built in Kotlin + Filament, designed around pluggable glasses
bridges so the same app drives multiple headsets.

## Status (Phase 0–1 + B2)

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
| Release build (debug-key signed, arm64-only ~26 MB) | ✅ |

## Build & run

```powershell
.\gradlew.bat assembleDebug     # dev
.\gradlew.bat assembleRelease   # ~26 MB arm64, signed with debug key
adb install -r app\build\outputs\apk\debug\app-debug.apk
adb shell am start -n com.rokid.spatialhost/.MainActivity
adb logcat -s SpatialHost FilamentRenderer MaterialFactory VitureHidBridge RokidMaxBridge UsbDiagnostics
```

Requirements: JDK 17+, Android SDK (platform 35), Gradle wrapper 8.14.3
(downloaded automatically).

## Controls (Phase 1) — mouse-only friendly (the box has no keyboard)

| Input | Action |
|---|---|
| Mouse drag | Look around (on top of head tracking) — **rotates the selected model**; in physics mode a press on the model **grabs it** (drag = carry, flick = throw) |
| Left click | Select model under crosshair, else panel (click empty space = deselect) |
| Double click / Right click | Recenter session frame |
| Middle click / `V` | Video sphere on/off (360° backdrop ↔ live stream) |
| Wheel | Next video source — **scales the selected model** (×0.2…×6) |
| `M` | Next model source (bundled demo → local files → …) |
| `P` | Physics on/off — the model drops onto the floor as a rigid body |
| Arrow keys / swipe | Cycle focused panel (`H` toggles HUD) |

### 3D models (Phase D1)

The app ships a bundled `DamagedHelmet.glb` (parsed by the in-repo glTF 2.0
parser — GLB/gltf, embedded & external buffers, node matrix|TRS hierarchy,
POSITION/NORMAL/UV0, u16/u32 indices, PBR materials + baseColor textures)
and auto-fits it to ~1.1 m in front of you. Click it to select (HUD shows
`SELECTED`), then drag to rotate and use the wheel to scale; click empty
space to deselect. `M` cycles the bundled demo with local `.glb`/`.gltf`
files auto-scanned from `/sdcard/Download`, `/sdcard/models`,
`/sdcard/Documents`, `/sdcard/Movies` — push models with
`adb push model.glb /sdcard/Download/` and press `M`.

### Video sources

Wheel cycles: **360 equirect → LIVE HLS → ToS → PTS → bipbop → BBB → local
files**. Local videos are auto-scanned at startup from `/sdcard/Movies`,
`/sdcard/Download`, `/sdcard/DCIM/Camera` (mp4/mkv/webm/mov/ts/avi/m4v, up
to 100) — copy files there and restart the app. Any `rtsp://` or other
ExoPlayer URL can be added to `PUBLIC_SOURCES`. Video auto-starts ~3 s after
the glasses connect (no keyboard needed).

## Architecture

```
app                     MainActivity, HUD, bridge chain, USB permission
core-bridge             GlassesBridge / HeadPoseProvider / DisplayController
  ├─ rokid/             RokidMaxBridge (UXR SDK adapter — stub, isolated)
  ├─ viture/            VitureHidBridge (USB Host port of VertoXR Web's ar-bridge)
  └─ emulator/          EmulatorBridge (touch look / phone gyro remote)
core-render             FilamentRenderer, Meshes, MaterialFactory (filamat runtime)
core-scene              SpatialPanel, LayoutEngine, ray hit-test (pure Kotlin)
core-input              GestureRecognizer, RayCursor, NavigationState
core-video              StreamPlayer (ExoPlayer, tight buffers for low latency)
core-battery            ThermalGovernor (FPS/resolution budget), PowerReport (mAh)
core-physics            PhysicsWorld (Jolt via jolt-jni): bodies, gravity, stepping
```

### Bridge chain

On startup (and on USB attach / permission grant) the app tries, in order:

1. **RokidMaxBridge** — detects Rokid UXR SDK entry classes on the classpath.
   Without the SDK it reports an error and the chain continues.
2. **VitureHidBridge** — VID `0x35CA`, Gen2 pose stream enabled with one
   10-byte frame (`0x0301 [1,2]` → 120 Hz `0x7308` quaternion events), Gen1
   Euler parsing (BE f32 @ offset 18). Protocol per community RE
   (`elasticjava/viture-v2`, `bfvogel/viture-webxr-extension`).
3. **EmulatorBridge** — always available; touch-look or phone gyro remote.

`HeadPoseProvider` is the only contract the renderer consumes, so a future
Max-Pro 6DoF or hand-tracking adapter plugs in without touching render/input.

### Adding the Rokid UXR SDK

1. Register at developer.rokid.com, download the UXR package.
2. Drop the AAR(s) into `app/libs/` and add
   `implementation(fileTree(mapOf("dir" to "libs", "include" to listOf("*.aar"))))`
   to `app/build.gradle.kts`.
3. Update `RokidMaxBridge.UXR_ENTRY_CANDIDATES` with the real entry class and
   wire the 3DoF pose callback in `RokidMaxBridge.connectUxr()` — **all SDK
   references stay inside that one file**.

### Rokid Max hardware notes

The plain Max is **3DoF only** (no cameras): no SLAM, no environment mapping,
no hand tracking. The glasses expose an interrupt-IN HID interface streaming
64-byte `0x11` IMU reports at ~450 Hz — **the raw-HID path is implemented and
verified** (`RokidMaxBridge`: f32 accel@9 / gyro@21, Rx(−90°) frame mapping
validated by offline simulation, sanity gate, level-from-accel boot). The UXR
enterprise SDK remains the future path for 6DoF on Max Pro — all SDK hooks
stay isolated in `RokidMaxBridge.UXR_ENTRY_CANDIDATES`.

## Roadmap

- **Phase B2** ✅: textured external material (`video_external`), 360°
  equirect/HLS video sphere, media3 → Surface → Filament Stream, source
  cycling (V/N), RTSP module ready for `rtsp://` URLs.
- **Phase D (glTF prototyping)** — in progress, sequential:
  - **D1a ✅**: glTF import pipeline (parser → node hierarchy → Filament,
    quaternion tangent frames, lit material + key light, auto-fit placement).
  - **D1b ✅**: model manipulation (ray/AABB pick, drag-rotate, wheel-scale,
    local-file scan + `M` switching).
  - **D2 ✅** (materials/lighting):
    - **D2a ✅**: baseColor textures (PNG/JPEG decode → hardware-sRGB RGBA8),
      per-primitive material instances, baseColorFactor/metallic/roughness
      uniforms, UV0 vertex stream.
    - **D2b ✅**: metallic-roughness + normal maps, glTF TANGENT →
      quaternion tangent frames (handedness via w sign).
    - **D2c ✅**: IBL — procedural equirect environment → GPU-prefiltered
      reflections (IBLPrefilterContext) + 3-band SH irradiance, key light
      aligned with the environment sun.
  - **D3** (physics, Jolt via jolt-jni) — in progress:
    - **D3a ✅**: physics world, gravity, static floor, dynamic model box,
      transform sync back to the renderer (`P` toggle).
    - **D3b**: grab-and-throw interaction + on-scene toggle button
      (mouse-only affordance).
  - **D4**: OBJ/glTF export (round-trip).
- **Phase C** (WebView browsing) deferred behind Phase D.
- **Phase E**: cloud scene sync, multi-user collaboration, AIDL plugin API,
  cloud rendering pipeline.
