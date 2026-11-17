# Future directions

Ideas for what could be built next on top of this demo. Each entry is
marked with its **source**:

* **[code]** — grows naturally out of code that already exists (the
  scaffolding is in the repo; the entry says what is missing)
* **[roadmap]** — carried over from the repository README roadmap
  (larger features, some already half-sketched)

Entries under *Distant* are deliberately brief: just enough of a name to
know what to search for — the details will have to be researched
anyway when the need arises.

Not a commitment list. Use it to pick a direction; `MODULES.md` says
which module each direction would touch.

---

## Near term — small deltas on existing code

### 1. Drag panels with the mouse [code]

* **Exists**: `LayoutEngine.move(id, to)` (core-scene), panel pick via
  `RayCursor`, drag events already routed to model rotation when a model
  is selected.
* **Missing**: a `DRAGGING_PANEL` state — when the crosshair hits a
  panel (not a model), drag moves it in the view plane instead of
  rotating the view/model.
* **Touches**: `:app` (`wireTouch`/`handleGesture`), maybe one helper
  in `core-scene`.

### 2. Implement `DisplayController` for Rokid [code]

* **Exists**: the `DisplayController` contract, `UnavailableDisplay`
  null-object, raw-HID channel inside `RokidMaxBridge`, HUD.
* **Missing**: the actual HID report for brightness / 2D-3D mode, HUD
  widgets to drive it (a slider in a panel would reuse B4/B5 widgets).
* **Touches**: `core-bridge` only — the rest of the app already sees
  the interface.

### 3. Physics: collision events + raycast queries [roadmap]

* **Exists**: `PhysicsWorld` with per-object bodies; grab/throw proves
  contact handling works.
* **Missing**: `onContact(idA, idB, impulse)` callback surfaced to the
  app, and `raycast(origin, dir) → (id, point, normal)` — the two
  primitives every game/interaction needs.
* **Touches**: `core-physics` + a demo hook in `:app`.

### 4. OBJ import [roadmap]

* **Exists**: OBJ **export** (`toObj`), STL import as the adapter
  pattern (`StlParser` → `GltfModel`), file scanning already includes
  `.obj`? — no: `MODEL_EXTENSIONS = {glb, gltf, stl}`.
* **Missing**: a `scene/obj/ObjParser.kt` (positions + normals + uvs,
  `mtllib` optional), add `"obj"` to `MODEL_EXTENSIONS`.
* **Touches**: `core-scene` (+1 JVM test), one line in `:app`.

### 5. Tonemapping / HDR bloom [roadmap]

* **Exists**: post-processing is explicitly **disabled** in
  `renderLoop` (`v.isPostProcessingEnabled = false`) with a comment on
  why (unlit tints authored in display space); emissive maps already
  render.
* **Missing**: re-enable ACES + bloom, re-tune the unlit panel colors
  for the new transfer function.
* **Touches**: `core-render` only. Risk: every existing visual needs a
  re-check — do it last, not first.

### 6. Scene export — the whole workspace [roadmap]

* **Exists**: per-model GLB export with verified round-trip; the
  sandbox already tracks every object's id/transform/scale.
* **Missing**: a writer that emits **one** glTF with N nodes (one per
  `ModelObject`) + transforms; import path that restores a scene list
  instead of replacing it.
* **Touches**: `core-scene` (exporter), `:app` (load/save entry).

### 7. Voice control (offline STT) [roadmap]

* **Exists**: every feature is already a single function call
  (`toggleVideo`, `spawnClone`, `togglePhysics`, recenter…), the key
  handler is one `when`.
* **Missing**: an STT engine (e.g. Vosk / Whisper.cpp Android), a
  recognizer that maps phrases to those same calls.
* **Touches**: new `core-voice` module + one wiring point in `:app`.
  Hands-free recenter/export/source-switch is the natural first demo.

### 8. Gamepad / keyboard-first UX + shortcut overlay [roadmap]

* **Exists**: `GestureRecognizer` already maps DPAD/L1/R1/ENTER/BUTTON_A;
  `NavigationState` tracks focus.
* **Missing**: a help overlay panel listing shortcuts (reuses the widget
  rasterizer), full gamepad parity, on-screen focus indicator.
* **Touches**: `core-input`, one panel in `:app`.

---

## Medium term — features with real design work

### 9. Phase C: spatial web browsing [roadmap]

* **Exists**: panels are textured quads with UV hit-testing — a WebView
  drawn into a panel texture is the same pipeline as `PanelUiDrawer`;
  the README roadmap names the PC-side companion: `verto-display-driver`
  (Python `vdd` agent over LAN) for desktop sharing.
* **Missing**: `WebView → Bitmap → updatePanelUi` loop (WebView must
  live on the main thread; throttle to panel refresh rate), input
  forwarding (clicks already arrive as u/v — route to `WebView.touch`),
  scroll gestures, and the `vdd` agent for PC streaming.
* **Touches**: new `core-web` + `:app` panel. This is the biggest
  "window into another world" feature the panel system can host.

### 10. Spatial audio for video [roadmap]

* **Exists**: `StreamPlayer` owns Media3 (only module allowed to), the
  head quaternion is available every frame.
* **Missing**: head-relative panning (Ambisonics or stereo→binaural via
  Media3 effects or OpenSL), volume in the deck widget, per-source
  audio metadata.
* **Touches**: `core-video` + deck widget.

### 11. Morph targets + Draco/KTX2 compressed glTF [roadmap]

* **Exists**: full glTF parse/export pipeline, animation channel
  machinery (morphs are just another animation target: `weights`).
* **Missing**: morph attribute parsing (`extras.targetNames`,
  POSITION/NORMAL/TANGENT deltas), `MorphTargetBuffer` + `setMorphWeights`
  on the render side; a decoder dependency for Draco (e.g. `draco-android`)
  and KTX2 (basis-transcoder).
* **Touches**: `core-scene` (parse), `core-render` (Filament already
  supports both — `morphing(targetCount)` and legacy paths are in the
  API we already researched).

### 12. 6DoF upgrade path [roadmap]

* **Exists**: `HeadPoseProvider.dof: DoF` (the enum is already there),
  `RokidMaxBridge.UXR_ENTRY_CANDIDATES` names the UXR SDK classes to
  probe, `uxrSdkPresent` detection runs today; renderer consumes only a
  quaternion — a position component would extend the same channel.
* **Missing**: wire the actual UXR session (`connectUxr` is stubbed with
  a comment), or inside-out tracking from a phone camera
  (`QuaternionPoseProvider.setOrientation` already accepts orientation;
  position would come from visual-inertial odometry on the phone).
* **Touches**: `core-bridge` primarily; `RayCursor` and camera would
  learn translation (parallax becomes possible).

### 13. Hand / controller tracking (Max Pro) [roadmap]

* **Exists**: the producer/consumer split was designed for exactly this —
  README and `InputEvent` docs say "hand tracking will plug in here as
  another InputEvent producer; recognizers stay unchanged".
* **Missing**: the tracking source (UXR SDK hand API or camera-based),
  mapping gestures to `InputEvent`.
* **Touches**: new producer in `core-bridge` or new `core-hand`;
  `core-input` unchanged by design.

### 14. Recording the 3D view [roadmap]

* **Exists**: everything composited lives in one Filament view; Media3
  is already a dependency.
* **Missing**: `Surface` capture (`ImageReader`/`MediaRecorder` on the
  swap chain surface is not directly possible — would need Filament
  `readPixels` or a virtual display); encode-to-file pipeline.
* **Touches**: new `core-record` + a HUD button.

### 15. Wear detection + boot autostart [roadmap]

* **Exists**: `MadgwickRokid` already consumes accel/gyro at rate
  (`isInitializedFromAccel`); app starts via USB-attach intent.
* **Missing**: an activity classifier on the IMU stream (box still /
  worn / doffed), auto pause/resume of video + UI dim; a boot receiver
  + provisioning for the headless box.
* **Touches**: `core-bridge` (classifier), `:app` (lifecycle).

---

## Long-term / experimental — names to search [roadmap]

Brief on purpose; each needs its own research spike before any estimate:

* **Cloud scene sync** — serialize workspace → upload → multi-user
  sessions; builds on scene export (#6). Search: *glTF scene sync
  realtime collaboration*.
* **AIDL plugin API** — load feature modules (panels, importers, games)
  at runtime without rebuilding the host. Search: *Android AIDL plugin
  architecture, dynamic feature modules*.
* **Cloud rendering / remote GPU** — stream a desktop-class renderer
  into the 3DoF host. Search: *WebRTC game streaming, CloudXR*.
* **Procedural terrain + vegetation** — demo content on top of the
  physics + glTF stack. Search: *procedural terrain heightmap Unity/Unreal
  style algorithms, GPU vegetation*.
* **Hand-authored game logic** — with #3 (collision events) the sandbox
  becomes a mini-game runtime; search: *Jolt Physics gameplay events*.
* **Stereo/3D mode content** — `DisplayController.set3dMode` (#2) plus
  side-by-side rendering; search: *Android stereoscopic 3D companion
  mode*.
* **Multi-glasses sessions** — two bridges at once (the chain already
  abstracts them) for shared scenes. Search: *multi-user AR shared
  anchor sessions*.

---

## How to choose

| If you want to… | Start with | Why |
|---|---|---|
| Ship something visible this week | #1 drag panels, #8 shortcut overlay | pure `:app`/`core-input` deltas, no new deps |
| Make the box a real gadget | #2 DisplayController, #15 wear detection | `core-bridge` is isolated; hardware protocols live there alone |
| Attract content | #9 web browsing, #4 OBJ import, #11 compressed glTF | content arrives faster than we can model it natively |
| Make it a platform | #13 hand tracking, AIDL plugins, scene export | each needs the contract work in `MODULES.md` §10 first |
| Demo "wow" at a meetup | CesiumMan-class characters (#11 morphs), #3 physics games, #14 recording | already 80 % there; recording makes it shareable |
