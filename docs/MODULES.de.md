# Modulkatalog

**Sprachen**: [English](MODULES.md) · [Русский](MODULES.ru.md) · Deutsch (diese Datei)

Alles in diesem Repository ist in Gradle-Module aufgeteilt, damit ein neues
Feature hinzugefügt werden kann, **ohne die bereits funktionierenden
Module anzufassen (und ohne sie beschädigen zu können)**. Diese Datei
dokumentiert jedes Modul in seinem heutigen Stand: seinen Zweck, seine
öffentliche API, Invarianten (was nicht kaputtgehen darf), und wie man es
erweitert. Zusammen lesen mit:

* `ARCHITECTURE.md` — wie die Module zur Laufzeit zusammenarbeiten (Threads,
  Warteschlangen, Datenflüsse)
* `CAPABILITIES.md` — was die Demo kann und wie man es demonstriert
* `FUTURE.md` — was als Nächstes auf Basis dieser Module gebaut werden könnte

---

## 1. Modulübersicht

| Modul | Zweck | Hängt ab von | Drittanbieter |
|---|---|---|---|
| `:core-scene` | Reines-Kotlin-Szenenmodell + Asset-Pipeline: glTF/STL-Parsing, GLB/OBJ-Export, Animationssampling, 4×4-Mathematik, Panel-/Widget-/Ray-Tests | *(kein — Blatt)* | `org.json` (nur Tests), JUnit |
| `:core-render` | Filament-Rendering: Render-Schleife, Materialien, IBL, GPU-Lebenszyklus von Modellen, Panel-Texturen, Video-Sphäre | `:core-scene` | Filament 1.75.1 (`filament`, `filamat`, `filament-utils`) |
| `:core-physics` | Starrkörpersimulation: Jolt-Welt, dynamische Boxen, statischer Boden, Feder-Zug beim Greifen | *(kein)* | `jolt-jni-Android:7.0.0:SpDebug` |
| `:core-video` | Videowiedergabe: ExoPlayer-Wrapper, optimiert für Streaming in die Sphäre mit niedriger Latenz | *(kein)* | Media3 1.4.1 (`exoplayer`, `hls`, `rtsp`) via `api` |
| `:core-bridge` | Brillen-Schnittstellen + Implementierungen: Head-Pose-Provider, Bridge-Kette, Quaternion-Mathematik, Anzeigensteuerung, USB-Diagnose | *(kein)* | Coroutines |
| `:core-input` | Input-Schnittstellen: rohe Events → Gesten, 3DoF-Ray-Cursor, Navigationszustand | `:core-scene`, `:core-bridge` | — |
| `:core-battery` | Temperatur-/FPS-Governor + Leistungsbuchhaltung pro Sitzung | *(kein)* | Coroutines |
| `:app` | `MainActivity`: Android-Lebenszyklus, HUD, Verdrahtung aller Module, Tasten-/Touch-Behandlung, USB-Berechtigungen, Dateiscanning | alle `core-*` | Coroutines |

Versionen sind in `gradle/libs.versions.toml` festgeschrieben (AGP 8.13.0,
Kotlin 2.4.21, coroutines 1.8.1, Filament 1.75.1, Media3 1.4.1). Filament
wird bewusst auf 1.75.1 festgeschrieben: 1.80.x erfordert compileSdk 37.

### Abhängigkeitsregeln

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

Strenge Regeln:

1. **`core-*` hängt nie von `:app` ab** und nie von einem Schwestermodul,
   außer den oben gezeichneten Kanten (`core-render → core-scene`,
   `core-input → core-scene + core-bridge`).
2. **`core-scene` ist das Blatt** — reines Kotlin, null Android-Importe
   (deshalb läuft die gesamte Asset-Pipeline als JVM-Unit-Tests in CI).
   Alles, was hier leben kann, muss hier leben.
3. **`core-video` gibt Media3 mit `api` frei**, damit die App Quellen
   bauen kann, ohne selbst Media3 zu deklarieren.
4. Neue Module folgen derselben Benennung (`core-<domain>`), werden zu
   `settings.gradle.kts` hinzugefügt und bekommen einen `docs`-Eintrag
   in dieser Datei.

---

## 2. `:core-scene` — Szenenmodell & Asset-Pipeline

**Zweck.** Alles, was ohne GPU oder Gerät getestet werden kann:
die Speicherrepräsentation der Szene, alle Dateiformate, die
Animationsmathematik und die Ray-/Panel-Geometrie. Hier sind keine
`android.*`-Importe erlaubt.

### Öffentliche API

**Asset-Pipeline** (`scene/gltf/`, `scene/stl/`):

| Einstiegspunkt | Signatur | Hinweise |
|---|---|---|
| `GltfParser.parse` | `(bytes, defaultName, basePathResolver?) → GltfModel` | GLB und JSON `.gltf`, eingebettete `data:`-Buffer, externe `.bin` über den Resolver |
| `GltfExporter.toGlb` | `(model) → ByteArray` | Round-trip verifiziert: Reparsen der Ausgabe muss die Struktur reproduzieren |
| `GltfExporter.toObj` | `(model, matrix?, name) → String` | Verlustbehaftetes Nebenformat; brennt die Platzierungsmatrix in die Vertices ein |
| `StlParser.parse` | `(bytes, defaultName) → GltfModel` | Binär (`84 + 50n`-Größenregel) und ASCII; einzelner Mesh, flache Normals beim Laden abgeleitet |
| `Animator` | pro Modell: `advance(dt)`, `sample() → Map<nodeIdx, localMatrix>` | LINEAR/STEP/CUBICSPLINE, Quaternion-nlerp, Schleife bei Clip-Dauer |
| `Mat4` | `identity/mul/inverse/boneMatrix` | Spaltenweise; `inverse` ist Gauss–Jordan, liefert `null` bei singulärer Eingabe |
| `GltfParser.trsMatrix` | `(t, r, s) → FloatArray(16)` | Gemeinsames TRS → Matrix |

**Schlüsseldatentypen** (alle `data class`, alle weitgehend immutabel):

* `GltfModel` — `primitives: Map<meshIdx, List<GltfPrimitive>>`, `nodes`,
  `sceneRootNodes`, `boundsMin/Max`, `materials`, `textures`,
  `animations: List<GltfAnimation>`, `skins: List<GltfSkin>`
* `GltfPrimitive` — `positions/normals/uvs/tangents`, `indices`,
  `material`, dazu Skinning-Attribute `joints: IntArray?` (4 pro Vertex)
  und `weights: FloatArray?` (4 pro Vertex, normalisiert)
* `GltfNode` — `matrix` **plus** Basis-`translation/rotation/scale` (das
  TRS muss das Parsen überstehen: Animationskanäle überschreiben Teile davon)
* `GltfSkin` — `joints: IntArray` (Knotenindizes), `inverseBindMatrices`
  (16 Floats pro Gelenk), `jointCount`
* `GltfAnimation` / `GltfChannel` / `GltfSampler` — Clip, Kanal
  (Knoten + Pfad), Keyframes (Times, Values, Interpolation)

**Szenen-/Panel-Geometrie** (`scene/Scene.kt`):

* `Vec3` — minimale Vektoralgebra (dot/cross/length/normalized)
* `SpatialPanel` — id, Größe in Metern, Position, Label, `widgets`,
  Flagge `persistent`; `normal` und `basis()` für das Rendering
* `LayoutEngine` — geordnete `panels`-Map; `add`, `defaultArc(count=3,
  radiusM=2.5, arcDeg=60)`, `move(id, to)`, `hitTest(dir, maxDistance=20)`
  → `PanelHit(panel, distance, u, v)`
* `hitBox(origin, dir, min, max) → Float?` — Slab-Test, der zum Picken
  von 3D-Modellen unter dem Fadenkreuz verwendet wird

**Widgets** (`scene/Widgets.kt`): `sealed class Widget` mit `Button`,
`Toggle`, `Slider`, `Label`; `UvRect` für das Hit-Testing innerhalb einer
Panel-Textur.

### Invarianten

* **Round-trip**: `GltfExporter.toGlb(GltfParser.parse(x))` muss zur
  selben Struktur reparsen (Geometrie bit-exakt, Bilder byte-identisch).
  Abgesichert durch `GltfRoundTripTest`.
* **TRS-Erhalt**: Knoten behalten das Basis-TRS, auch wenn der Renderer
  eine eingebrannte Matrix konsumiert — Animation und Export hängen davon ab.
* **Keine `android.*`**: In dem Moment, in dem hier ein Android-Import
  auftaucht, sterben die JVM-Tests und mit ihnen die CI.
* Gewichte werden beim Parsen normalisiert (u8 → /255, u16 → /65535); der
  Renderer darf pro Vertex `Σw = 1` annehmen.

### Wie man erweitert

Neues Dateiformat? Ein `scene/<fmt>/XParser.kt` hinzufügen, das ein
`GltfModel` erzeugt — der gesamte nachgelagerte Stack (Renderer, Physik,
Exporter) funktioniert unverändert. Neue Animationsinterpolation?
`Animator` + `GltfSampler.interpolation` erweitern und einen Test zu
`AnimationTest` hinzufügen.

---

## 3. `:core-render` — Filament-Rendering

**Zweck.** Besitzt den Render-Thread, die Filament-Engine/Szene, die
GPU-Ressourcen für Modelle und Panels, die Materialien, IBL und die
Video-Sphäre. Die App berührt nie direkt Filament — sie postet Anfragen.

### Öffentliche API

**`FilamentRenderer(surfaceView)`** — `SurfaceHolder.Callback`; die
einzige Fassade, mit der die App spricht:

| Kategorie | Mitglieder |
|---|---|
| Kamera | `poseSupplier: (() -> FloatArray)?` — Quaternion vom aktiven Bridge |
| Lebenszyklus | `startThread()`, `stopThread()`, `onSceneReady: (() -> Unit)?` (Callback im Haupt-Thread, ausgelöst nach jedem Szenen-Aufbau/Wiederaufbau) |
| Panels | `setPanels(specs: List<PanelSpec>)`, `updatePanelUi(id, w, h, pixels)`, `setFocusedPanel(id?)` |
| Modelle | `requestModel(id, GltfModel, matrix)`, `setModelTransform(id, matrix)`, `requestRemoveModel(id)` |
| Animation | `requestAnimationToggle()`, `requestNextAnimationClip()`, `animationStatus: String` (HUD-Snapshot) |
| Video | `requestVideoSphere(onReady: (Surface) -> Unit)`, `requestColorSphere(onDone)` |
| Kennzahlen | `targetFps`, `measuredFps`, `onStats`-Callback |

`PanelSpec(id, widthM, heightM, x, y, z, persistent)` — ein flaches
Value-Object; Panels sind **keine** `SpatialPanel`s (dieser Typ gehört
core-scene).

**`ModelManager`** (nur Render-Thread) — GPU-Lebenszyklus der Szenenobjekte:

* `load(id, model, matrix)`, `setMatrix(id, matrix)`, `release(id)`,
  `releaseAll()`, `shutdown()`
* `tickAnimations(nowNs)` — führt jeden `Animator` voran, schreibt die
  Knoten-Transformationen (dt auf 250 ms begrenzt, damit ein Stall keine
  Keyframes herausschleudern kann)
* `updateBones()` — berechnet pro skinned Renderable
  `bone[j] = inverse(meshWorld) · jointWorld[j] · IBM[j]` (die exakte
  gltfio-Formel) und speist `RenderableManager.setBonesAsMatrices`
* `toggleAnimations()`, `nextAnimationClip()`, `hudStatus()`
* `models: LinkedHashMap<String, LoadedModel>` — die Objektmenge des Sandkastens

**`MaterialFactory`** (Objekt) — Kompilierung zur Laufzeit über filamat,
damit der Build kein `matc` und keine vorkompilierten `.filamat`-Assets
benötigt: `buildColorUnlit`, `buildLit` (PBR: baseColor/MR/normal/emissive/AO,
Split zwischen sRGB- und linearem Sampling), `buildPanelUi`, `buildVideoSphere`.

**`IblBuilder(engine)`** — prozedurales Equirect → GPU-gefilterte
`reflections`, SH `irradianceCube`, `indirectLight`; `build()`/`release()`.

**`Meshes`** — `createSphere(radius=30f)`, `createQuad()`, `allocBytes`.

**`PanelUiDrawer.draw(panel, bitmap)`** — rasterisiert ein `SpatialPanel`
(Titel + Widgets) in das Bitmap, das seiner Filament-Textur zugrunde liegt.

### Invarianten

* **Zugehörigkeit zum Render-Thread**: Alles in `ModelManager` und alle
  Filament-Objekte werden nur vom Render-Thread berührt. Anfragen über
  Threads hinweg laufen über die volatile Pending-Kanäle (siehe
  `ARCHITECTURE.md § Pending channels`).
* **Ressourcen-Paarung**: Jedes `create*` hat ein passendes destroy in
  `release()/shutdown()/destroySceneState()`; der Einstellungs-Bildschirm
  beim ersten Start zerstört und baut die ganze Szene neu auf, sodass
  nichts einmalig sein darf, ohne über den `onSceneReady`-Re-Push zu laufen.
* **Exakte Buffergrößen**: Filament validiert `ByteBuffer.remaining()`
  gegen `vertexCount × attributeSize` und bricht den Draw bei Abweichung
  stillschweigend ab (das verursachte sowohl den „unsichtbares Modell“-
  als auch den „triangle fan“-Fehler — siehe README-Pitfalls).
* Materialien halten `variantFilter` leer — `skinning` herauszufiltern
  würde jedes skinned Modell crashen.

### Wie man erweitert

Neues visuelles Feature → entscheiden, welcher Kanal es trägt (Panels:
Pending-Felder; Modelle: `ModelOp`-Warteschlange), die Render-Thread-Hälfte
in `ModelManager` oder `FilamentRenderer` implementieren und der App nur
eine Request-Methode exponieren. Niemals Filament-Typen über die Modulgrenze
hinweg exponieren.

---

## 4. `:core-physics` — Starrkörpersimulation

**Zweck.** Jolt-Wrapper für den Sandkasten: Schwerkraft, ein statischer
Boden, eine dynamische Box pro Szenenobjekt und der Feder-Zug, der von
Greifen-Werfen verwendet wird.

### Öffentliche API — `PhysicsWorld`

* Lebenszyklus: `ensureInit()` (idempotent), `isInitialized`
* Körper: `addFloor(topY, centerX, centerZ)`,
  `addModelBox(id, px,py,pz, hx,hy,hz)` (ersetzt einen Körper derselben
  id), `removeBody(id)`, `removeAllBodies()`, `hasBody(id)`, `bodyCount`
* Abfragen: `bodyPosition(id)`, `bodyRotation(id)` (Quaternion xyzw)
* Simulation: `step(dt)` — ein fester Schritt (der Aufrufer nutzt `1/60 s`)
* Greifen: `grab(id)`, `dragTo(x,y,z)`, `releaseGrab()`, `isGrabbing`
  — eine kritisch gedämpfte Feder (`DRAG_KP = 88.8`, `DRAG_KD = 17.0`)
  zieht den Körper zum Ziel; Loslassen behält die Geschwindigkeit (Werfen)

### Invarianten

* Nur zwei Objektebenen: `LAYER_MOVING` (dynamisch) vs. `LAYER_STATIC`
  (Boden); moving-vs-moving und moving-vs-static kollidieren,
  static-vs-static nicht.
* Kapazität: `MAX_BODIES = 5000`, `MAX_BODY_PAIRS = 65536`,
  `MAX_CONTACTS = 20480`.
* `step()` muss billig bleiben und weitgehend allozierungsfrei: es läuft
  im Haupt-Thread bei 60 Hz innerhalb von `physicsTick`.
* Die Bodenoberfläche ist der mit dem Rendering geteilte Welt-Vertrag:
  `FLOOR_TOP_Y = -1.0` (in `MainActivity` deklariert, an `addFloor`
  übergeben), und die Schattenboden-Platte in `core-render` liegt auf
  demselben Y.

### Wie man erweitert

Neue Körperformen → eine `add*`-Methode neben `addModelBox` hinzufügen.
Gameplay-Abfragen (Raycast, Kollisionsevents) gehören ebenfalls hierher —
sie brauchen Jolt-Zugriff, aber nichts vom Renderer. Jolt-Typen für das
Modul privat halten (die App sieht nur ids und Float-Arrays).

---

## 5. `:core-video` — Wiedergabe

**Zweck.** Ein dünner, auf Latenz optimierter Wrapper um ExoPlayer für
die 360°-Sphäre: Der Renderer übergibt ihr eine `Surface`, die App
steuert die Quellen.

### Öffentliche API — `StreamPlayer(context)`

* `prepare(url, lowLatency = true)`, `setSurface(surface?)`
* `pause()`, `resume()`, `seekTo(ms)`, `setLooping(on)`, `release()`
* Zustand: `playing`, `positionMs`, `durationMs`
* Callbacks: `onError: ((PlaybackException) -> Unit)?`,
  `onFirstFrame: (() -> Unit)?`

### Invarianten

* Der Player besitzt die Surface nie — das tut der Renderer. Nach einem
  Szenen-Wiederaufbau muss die App erneut `setSurface` **und** `prepare`
  aufrufen (ein bloßes `setSurface` lässt ExoPlayer in
  `ERROR_CODE_DECODING_FAILED`; siehe README „Storage & scene rebuilds“).
* `onFirstFrame` ist die einzige Wahrheitsquelle für „Video streamt“
  im HUD.

### Wie man erweitert

Neue Quellenarten sind einfach URLs, die an `prepare` übergeben werden
(HLS/RTSP/Datei). Audio-Features (räumliches Panning) erweitern dieses
Modul — es ist der einzige Ort, an dem Media3 erlaubt ist.

---

## 6. `:core-bridge` — Brillen-Schnittstellen & Implementierungen

**Zweck.** Alles, was mit Hardware außerhalb der Box spricht: Pose-
Protokolle, Anzeigensteuerung und die Mathematik, die sie gemeinsam nutzen.
Die übrige App hängt nur an den Schnittstellen, sodass ein neues Brillen-
Modell einsteckbar ist, ohne render/input anzufassen.

### Schnittstellen

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

`UnavailableDisplay` ist die Null-Object-Implementierung, die verwendet
wird, wenn ein Bridge kein Anzeigeprotokoll hat.

### Implementierungen

| Klasse | Hardware | Hinweise |
|---|---|---|
| `RokidMaxBridge` | Rokid Max, raw HID `04d2:162f` | nur 3DoF (keine Kameras beim normalen Max). `poseCount`/`droppedPackets` für das HUD. `UXR_ENTRY_CANDIDATES` nennt die UXR-SDK-Klassen, die für einen künftigen 6DoF-Max-Pro-Pfad erkundet werden — alle SDK-Referenzen bleiben in dieser Datei |
| `VitureHidBridge` | Viture (Port des ar-bridge von VertoXR Web) | Gen2 `0x0301`/`0x7308`, Gen1 Euler@18; `poseCount`, `rawCount` |
| `EmulatorBridge` | keine Hardware | Touch-Look (Maus-Drag wird in die Orientierung eingebrannt) oder Handy-Gyro (`useDeviceSensors`); `look(dy, dp)` |

### Hilfstypen

* `MadgwickRokid(beta = 0.045)` — AHRS-Fusion von Gyro+Beschleunigung
  des Max in ein Quaternion (`update(gx,gy,gz,ax,ay,az,dt)`, `cameraQuat()`,
  `initFromAccel`). Reine Mathematik, prinzipiell unit-testbar.
* `QuaternionPoseProvider` — ein `HeadPoseProvider`, gespeist von
  `setOrientation(rawGl)` (vom Emulator-Pfad genutzt).
* `Quat` (in `Types.kt`) — `fromEulerYXZ`, `mul`, `rotate`, `forward`,
  `toEulerYXZ`, `normalize`, `conjugate`; von Bridges und Input gemeinsam
  genutzt.
* `UsbDiagnostics.describe(usbManager)` — einmaliger Dump des USB-Busses
  für logcat-Debugging.

### Invarianten

* **Keine UXR-/SDK-Typen außerhalb von `RokidMaxBridge`** — der normale
  Max hat keine Kameras; SDK-Referenzen isoliert zu halten bedeutet, dass
  das Projekt auf jedem Box ohne das proprietäre SDK baut.
* `Quat` und `HeadPose` sind die Lingua franca: Die an den Renderer
  übergebene Pose ist immer ein Quaternion `float[4]` (x,y,z,w).
* Bridges werden bei jedem `connectChain()` neu konstruiert — kein
  statischer Zustand, keine leakenden USB-File-Deskriptoren (immer
  zuerst `disconnect()`).

### Wie man erweitert

Für neue Hardware `GlassesBridge` (+ `HeadPoseProvider`) implementieren,
es in `MainActivity.connectChain()` in Prioritätsreihenfolge hinzufügen
und seine USB-VID in den Diagnose-Dokumenten registrieren. Das ist die
gesamte Integrationsoberfläche.

---

## 7. `:core-input` — Events → Gesten

**Zweck.** Die Producer-/Consumer-Aufteilung, die Input testbar hält:
rohe Events hinein, semantische Gesten heraus, dazu der 3DoF-Ray-Cursor.

### Öffentliche API

* `sealed class InputEvent` — `Drag(dxPx, dyPx)`, `Tap`, `DoubleTap`,
  `DragEnd`, `Back`, `Key(keyCode)`
* `enum class Gesture` — `SELECT`, `RECENTER`, `SWIPE_LEFT`,
  `SWIPE_RIGHT`, `TOGGLE_HUD`, `BACK`
* `GestureRecognizer(swipeThresholdPx = 120f).onEvent(e) → Gesture?` —
  Schwellwerte und der Tap-vs.-Drag-Race-Fix leben hier (ein beendeter
  Drag darf den nächsten Klick nicht verschlingen — dafür gibt es `DragEnd`)
* `RayCursor(layout).update(pose: HeadPose) → PanelHit?` — 3DoF-Ray
  durch den Sitzungsframe, das nächste Panel gewinnt
* `NavigationState` — `focusedPanelId`, `selectedPanelId`, `hudVisible`

Im Recognizer werden außerdem zugeordnet: DPAD/L1/R1 → Swipes,
ENTER/BUTTON_A → Select, `R` → Recenter, `H` → HUD-Umschalter.

### Invarianten

* Recognizers müssen **bezüglich der Szene zustandslos** bleiben: Sie
  übersetzen Events, sie verändern nie Panels oder Modelle. Das ist es,
  was ermöglicht, dass Handtracking oder ein Gamepad später zu einem
  neuen `InputEvent`-Producer werden, ohne die Gestenlogik anzufassen.
* Jede `Drag`-Sequenz endet mit `DragEnd` (oder einem `Tap`, das sie
  verbraucht) — sonst wird der nächste Klick falsch klassifiziert.

### Wie man erweitert

Neues Eingabegerät → `InputEvent`s emittieren; neue Interaktion → ein
`Gesture` + eine Verzweigung in `onEvent` hinzufügen. Pixel-Schwellwerte
konfigurbar halten (dafür existiert der Konstruktorparameter).

---

## 8. `:core-battery` — Wärme & Leistung

**Zweck.** Die Box innerhalb ihres thermischen Budgets halten und
berichten, was eine Sitzung gekostet hat.

### Öffentliche API

* `ThermalGovernor(context)` — `budget: StateFlow<Budget(targetFps,
  resolutionScale, thermalStatus)>`, `start()/stop()`,
  `frameIntervalNanos()` (die Render-Schleife taktt Frames darüber)
* `PowerReport(context)` — `stats: StateFlow<Stats(elapsedSeconds,
  mahUsed, drainMa, batteryPercent)>`, `start()`, `sample()`

### Invarianten

* Der Governor **schlägt vor**, er erzwingt nie: Nur das `targetFps`
  der Render-Schleife konsumiert ihn. Ein Fehler hier darf die FPS nur
  senken, nie crashen.
* Die Leistungszahlen sind Schätzungen (Battery-Stats-Deltas); sie sind
  HUD-Telemetrie, keine Messwerte, denen man für Engineering-Entscheidungen
  vertrauen darf.

### Wie man erweitert

Neue Thermikrichtlinie → anpassen, wie `Budget.targetFps` abgeleitet wird.
Neue Telemetrie → Felder zu `Stats` hinzufügen (das HUD rendert sie
bereits).

---

## 9. `:app` — MainActivity (die Verdrahtungsschicht)

**Zweck.** Das einzige Modul, das gleichzeitig Android-UI, USB-
Berechtigungen, Dateiscanning und *alle* Core-Module kennen darf. Es
besitzt die **Politik** (welcher Bridge zuerst, welche Quelle als
Nächstes, was eine Taste bedeutet); die Module besitzen den **Mechanismus**.

### Was heute hier lebt (~1580 Zeilen)

| Bereich | Mitglieder |
|---|---|
| Szenenobjekte | `ModelObject(id, label, bounds, pos, yaw/pitch, scale)`, `sceneModels`, `selectedModelId`, `pickModelAtCrosshair()`, `spawnClone()`, `deleteSelected()` |
| Modellquellen | `loadCurrentModel()`, `nextModelSource()`, `scanLocalModelFiles()` (`.glb/.gltf/.stl` aus `LOCAL_MODEL_DIRS`), `exportCurrentModel()` |
| Video | `toggleVideo()`, `startVideo()/stopVideo()`, `stepVideoSource()`, `videoPanelWidgets()`, `PUBLIC_SOURCES` (6 integrierte Streams), `scanLocalVideos()` |
| Physik | `physicsTick` (60 Hz), `togglePhysics()`, `addPhysicsBoxFor()`, Greifen-Verdrahtung in `tryGrabAtCrosshair()`/`dragGrabbedModel()` |
| Panels | `panelSpecs()`, `pushPanelUi()`, `handleWidget()`, `reapplySceneContent()` (der `onSceneReady`-Re-Push) |
| Input | `wireTouch()`, `onKeyDown()`, `handleGesture()`, `selectAtCrosshair()`, `cycleFocus()` |
| HUD | `hudTick` (1 Hz), `updateHud()` |
| Bridge | `connectChain()` — Rokid → Viture → Emulator-Fallback |
| Android | USB-Attach-Receiver, Speicher-/All-Files-Berechtigungen, Immersive-Modus |

### Tastenbelegung (zur Referenz; vollständige Tabelle in der README)

`A` Animations-Wiedergabe/Pause · `B` Klonen · `DEL` Löschen · `E` Export ·
`G` nächster Animationsclip · `M` Modellquelle · `N` Videoquelle ·
`P` Physik · `V` Video-Sphäre · `H` HUD · `R` Recenter ·
Mausrad = Auswahl skalieren / nächste Videoquelle · Pfeiltasten = Fokus

### Invarianten

* **Keine Filament-, keine Jolt-, keine Media3-Typen** erscheinen hier —
  nur Request-Methoden und schlichte Daten. Wenn festgestellt wird, dass
  einer importiert wird, gehört die Logik ins zuständige Modul.
* Alle aus dem UI-Thread angefragten Szenenänderungen laufen über die
  Kanäle des Renderers; Render-Thread-Zustand nie direkt verändern.
* Die Tastenbearbeitung hat eine feste Reihenfolge: Feature-Tasten
  (`V/N/M/P/E/B/DEL/A/G`) geben früh zurück; alles andere fällt an den
  Gesten-Recognizer durch — eine neue Feature-Taste darf `H`/`R`/DPAD
  nicht überdecken.

### Wie man erweitert (das „Feature“-Rezept)

1. Mechanismus im zuständigen `core-*`-Modul (mit Tests, wenn es reines
   Kotlin ist).
2. Ein Paar Request-Methoden auf `FilamentRenderer`/Modulfassade, wenn
   es den Render-Thread braucht.
3. Eine `when`-Verzweigung in `onKeyDown` (oder eine Geste) + eine
   HUD-Zeile.
4. In `CAPABILITIES.md` und der README-Steuerungstabelle dokumentieren.

---

## 10. Regeln für das Entwerfen eines neuen Moduls

Eine Checkliste, destilliert aus dem, was im Codebase bereits funktioniert:

1. **`core-<domain>` nennen** und zu `settings.gradle.kts` hinzufügen.
2. **Die schmalsten Abhängigkeiten deklarieren.** Schwestermodule nur
   dort, wo das obige Diagramm es erlaubt; nie `:app`.
3. **Den Thread vor dem Schreiben des Codes festlegen.** Render-Thread-
   Zustand bleibt hinter einer Request-Methode; Haupt-Thread-Zustand
   (Physik-Tick, HUD) bleibt in `MainActivity` oder eigenem Handler;
   alles andere braucht eine explizite Schnittstelle (siehe
   `ARCHITECTURE.md`).
4. **Threads nur mit unveränderlichen Daten überqueren** — volatile
   Felder, die Value-Objects halten, oder eine Warteschlange kleiner Ops
   (`ModelOp` ist die Vorlage). Keine gemeinsamen veränderbaren Objekte,
   keine Locks über Module hinweg.
5. **Schnittstellen exponieren, keine Implementierungen** — das
   `GlassesBridge`-Muster: Der Consumer hängt an einer Schnittstelle,
   die neben seinen Datentypen definiert ist (`HeadPose`, `BridgeState`).
6. **Reine Logik → `core-scene` (oder der eigene JVM-testbare Teil des
   neuen Moduls)**, damit die CI sie abdeckt. Code, der nur auf dem Gerät
   läuft, bekommt stattdessen Log-Diagnostik (eine Zeile pro
   Zustandsübergang, stabiler Tag).
7. **Jedes create hat ein destroy**, und jeder einmalige Push hat einen
   `onSceneReady`-Re-Push-Pfad — der Einstellungs-Bildschirm beim ersten
   Start *wird* die Szene neu aufbauen.
8. **Exakte Buffer-/Größenverträge mit Filament** (oder jeder fremden
   Engine) **prüfen**: Größen selbst validieren, die Fehlerart ist eine
   stille visuelle Verfälschung, keine Exception.
9. **An drei Stellen dokumentieren**: diese Datei (Modul),
   `CAPABILITIES.md` (Demo), README-Steuerung (Tasten für die Nutzer).
