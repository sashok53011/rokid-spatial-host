# rokid-spatial-host

Native Android-Spatial-App für **Rokid Max**-AR-Brille — eine
immersive 3DoF-Umgebung in Kotlin + Filament:360°-Videosphäre,
schwebende räumliche Panels, glTF-Modelle mit vollständigen
PBR-Materialien, Starrkörperphysik und Modell-Export. Läuft
allein mit der Maus (das Ziel-Box hat keinen Touchscreen) und
steuert die Brille über **rohes USB HID** — kein Vendor-SDK
nötig.

Das Projekt ist eine komplette **glTF-Prototyping-Pipeline**:

```
Import (D1) → Materialien/IBL (D2) → Physik (D3) → Export (D4)
```

Alles unten ist implementiert, an echter Hardware verifiziert und
Schritt für Schritt committet (siehe [Git-Historie](#roadmap--todo)),
der Pfad ist also reproduzierbar.

**Sprachen**: [English](README.md) · [Русский](README.ru.md) · Deutsch (diese Datei)

## Status

| Feature | Zustand |
|---|---|
| Gradle-Multimodul-Projekt (8 Module) | ✅ baut (`assembleDebug` / `assembleRelease`) |
| Filament-Renderloop, Frame-Pacing, pose-getriebene Kamera | ✅ |
|360°-Hinterundsphäre + räumliche Panels (Laufzeit-kompilierte Materialien) | ✅ |
| 3DoF-Strahl-Selektion / Fokus / Recenter, Touch + Tasten | ✅ |
| Bridge-Kette: **Rokid raw-HID 3DoF (kein UXR SDK!)** → Viture → Emulator | ✅ verbunden & getrackt auf OPi5Plus |
| Thermik/FPS-Governor + Sitzungs-Leistungsbericht (mAh, mA) | ✅ |
| **360°-Videosphäre: HLS/equirect/RTSP → media3 → Filament Stream** | ✅ V=an/aus N=Quelle |
| Viture-IMU-Protokoll (Gen2 `0x0301`/`0x7308`, Gen1 Euler@18) | ✅ implementiert, Verifikation an echter Brille nötig |
| **D1a: glTF-Import (eigener GLB/glTF2-Parser → Filament, Lit-Material)** | ✅ Demo-Helm rendert |
| **D1b: Modell-Pick / Drehen / Skalieren / Quellenwechsel** | ✅ Klick=Pick, Drag=Drehen, Rad=Skalieren, M=Quelle |
| **D2a: baseColor-Texturen (Hardware-sRGB) + PBR-Faktoren** | ✅ Helm texturiert |
| **D2b: metallic-roughness + Normal-Maps (glTF TANGENTS)** | ✅ am Gerät verifiziert |
| **D2c: IBL — prozedurales equirect → GPU-gefilterte Reflections + SH** | ✅ Metall spiegelt die Umgebung |
| **D3a: Jolt-Physik — Gravitation, Boden, dynamischer Körper (P)** | ✅ exaktes Landen verifiziert |
| **D3b: Greifen-und-Werfen — Feder-Drag, Maus-Flick wirft** | ✅3,16 m Wurf verifiziert |
| **D4: OBJ- + glTF-Export (E) mit verifiziertem Round-Trip** | ✅ Phase D abgeschlossen |
| **Panel-Widgets + Video-Deck: PLAY/SRC/LOOP/SEEK per Blick-Klick** | ✅ |
| **IBL-Skybox-Hintergrund + beleuchteter Schattenboden + dynamische Schatten** | ✅ |
| Release-Build (mit Debug-Key signiert, arm64-only ~26 MB) | ✅ |

## Voraussetzungen

**Hardware**

* Rokid-Max-Brille (3DoF, USB-C; HID-ID `04d2:162f`)
* Android 12+ ARM64-Box mit USB-Host — entwickelt auf Orange Pi
  5 Plus; jede Box reicht, die das APK ausführt und den
  Interrupt-IN-HID der Brille lesen kann
* USB-Maus (Pflicht), USB-Tastatur (empfohlen — es gibt Shortcuts)

**Software**

* JDK 17+
* Android SDK Platform 35 (Gradle-Wrapper lädt den Rest)
* `adb` im PATH

## Bauen & Starten (reproduzierbar)

```powershell
git clone <dein-fork> rokid-spatial-host
cd rokid-spatial-host
.\gradlew.bat assembleRelease          # ~26 MB, arm64, Debug-Key

# Ziel-Box (IP anpassen):
adb connect 192.168.0.173:5555
adb install -r app\build\outputs\apk\release\app-release.apk
adb shell pm grant com.rokid.spatialhost android.permission.READ_EXTERNAL_STORAGE
adb shell am start -n com.rokid.spatialhost/.MainActivity
```

Nützliche logcat-Filter (jede Phase loggt unter eigenem Tag):

```powershell
adb logcat -s SpatialHost FilamentRenderer MaterialFactory ModelManager \
    IblBuilder PhysicsWorld RokidMaxBridge UsbDiagnostics
```

Erster Start: Speicherzugriff erlauben (damit das Scannen lokaler
Videos/Modelle funktioniert), Brille ins USB der Box stecken — die
Bridge verbindet sich selbst, das360°-Video startet ~3 s später.

Zusammenfassung: `gradle.properties` pinnt **Filament 1.75.1**
(für 1.80.x braucht man compileSdk 37), **Kotlin 2.4.x**, AGP aus dem
Version-Catalog.

## Steuerung (mausfreundlich, ohne Touch)

| Eingabe | Aktion |
|---|---|
| Maus-Ziehen | Umsehen (oben auf Head-Tracking) — **dreht das ausgewählte Modell**; im Physikmodus ergreift ein Druck auf das Modell **greift es** (Ziehen = tragen, Flick = werfen) |
| Linksklick | Modell unter dem Fadenkreuz wählen, sonst Panel (Klick auf Leerraum = Auswahl aufheben) |
| Doppelklick / Rechtsklick | Recenter des Sitzungsrahmens |
| Mittelklick / `V` | Video-Sphäre an/aus (360°-Hintergrund ↔ Live-Stream) |
| Rad | Nächste Videoquelle — **skaliert das ausgewählte Modell** (×0,2…×6) |
| `M` | Nächstes Modell (Demo → lokale Dateien → …) |
| `P` | Physik an/aus — das Modell fällt als Starrkörper auf den Boden |
| `E` | Aktuelles Modell als `.glb` + `.obj` nach Downloads exportieren (Round-Trip verifiziert) |
| `N` | Nächste Videoquelle |
| Deck-Widgets anblicken & klicken | Untere „Video"-Platte: PLAY/PAUSE · ‹SRC/SRC› · LOOP-Schalter · SEEK-Slider (Klick = Spule) |
| `H` | HUD ein/aus |
| `R` / Pfeiltasten | Recenter / Panel-Fokus zyklisch wechseln |

### Arbeitsablauf: Modelle

1. Modelle auf die Box legen: `adb push MeinModell.glb /sdcard/Download/`
2. App neu starten (oder nach Neustart `M` drücken) — `.glb`/`.gltf`
   werden automatisch gescannt aus `/sdcard/Download`, `/sdcard/models`,
   `/sdcard/Documents`, `/sdcard/Movies` (externe `.bin` neben
   `.gltf` werden auch aufgelöst).
3. Modell anklicken = auswählen, Drag = drehen, Rad = skalieren, `M` =
   Quellen wechseln.
4. `E` schreibt `<name>.glb` + `<name>.obj` nach Downloads. Die App
   parst ihr eigenes GLB sofort im Speicher neu und loggt
   `ROUND-TRIP OK` mit Strukturzählern; die exportierte Datei ist über
   `M` wieder importierbar.

### Arbeitsablauf: Video

Rad/`N` schaltet durch: **360 equirect → LIVE HLS → ToS → PTS → bipbop →
BBB → lokale Dateien**. Lokale Videos werden gescannt aus
`/sdcard/Movies`, `/sdcard/Download`, `/sdcard/DCIM/Camera`,
`/sdcard/video` (mp4/mkv/webm/mov/ts/avi/m4v). Jede `rtsp://`- oder
ExoPlayer-URL kann in `PUBLIC_SOURCES` in `MainActivity.kt` ergänzt
werden. `V` schaltet die Sphäre.

### Arbeitsablauf: Physik

`P` aktiviert Jolt: Das Modell wird ein dynamischer Kasten aus seinen
skalierten Grenzen und fällt auf einen statischen Boden (`y = -1`,
exakter Kontakt: `y = Boden + halbe Höhe`). Während der Simulation:

* Druck **auf das Modell** greift es (Feder zieht es zum Fadenkreuz
  plus ein mausgetriebener Versatz in der Kamera-Ebene),
* Loslassen in Bewegung = **Wurf**,
* erneutes `P` — die Landepose bleibt (Position + yaw/pitch aus dem
  Körperquaternion).

## Architektur

```
app                     MainActivity, HUD, Bridge-Kette, USB-Berechtigung,
                        Gesten, Modell/Video/Physik/Export-Verdrahtung
core-bridge             Kontrakt GlassesBridge / HeadPoseProvider
  ├─ rokid/             RokidMaxBridge — raw-HID 3DoF (ohne UXR SDK)
  ├─ viture/            VitureHidBridge (USB-Port des ar-bridge aus VertoXR Web)
  └─ emulator/          EmulatorBridge (Touch-Look / Telefon-Gyro)
core-render             FilamentRenderer (Renderloop, Kanäle für aufgeschobene
                        Szenen-Mutationen), Meshes, MaterialFactory
                        (filamat-Laufzeit), IblBuilder, ModelManager
core-scene              SpatialPanel, LayoutEngine, Strahl-Tests, GltfParser,
                        GltfExporter (reines Kotlin, JVM-testbar)
core-input              GestureRecognizer, RayCursor, NavigationState
core-video              StreamPlayer (ExoPlayer, enge Low-Latency-Puffer)
core-battery            ThermalGovernor (FPS-Budget), PowerReport (mAh)
core-physics            PhysicsWorld (Jolt via jolt-jni): Körper, Feder-Drag
```

Schlüsselpatrone:

* **Ein einziger Head-Pose-Kontrakt** — `HeadPoseProvider`; der Renderer
  konsumiert nur `poseSupplier: () -> FloatArray` (Quaternion), neue
  Bridges (Max-Pro-6DoF, Hand-Tracking) stecken ohne Änderungen in
  Render/Input.
* **Szenen-Mutationen im Renderthread** — alles, was der UI-Thread
  ändern will, läuft über volatile „pending"-Felder (`pendingModel`,
  `pendingPanels`, `pendingModelTransform`, Video-Sphären-Anfragen),
  oben im Renderloop verbraucht.
* **Materialien zur Laufzeit** — `.material()`-Quellen werden im Gerät
  mit filamat kompiliert (`MaterialFactory`), deshalb braucht der Build
  weder `matc` noch vorkompilierte `.filamat`-Assets.

## So funktioniert es — Phasen-Notizen & Fallstricke (für Reproduzierer

Jeder Abschnitt hält die nicht offensichtlichen Fakten fest, die zum
Neubauen nötig sind.

### Rokid-Max-Raw-HID-Bridge

Die Brille streamt über ein Interrupt-IN-HID-Interface64-Byte-`0x11`-
IMU-Reports mit ~450 Hz: **f32 accel ab Byte 9, f32 gyro ab Byte 21**,
der Geräterahmen ist um Rx(−90°) gegenüber dem Weltrahmen gedreht
(offline simuliert, dazu ein Level-from-accel-Sanity-Gate beim Start).
Die Bridge hat Stall-Erkennung (3 s ohne Pakete → Reconnect),
Auto-Resume und einen USB-Detach-Receivers; die HUD-Zeile
`stream : pkt=… drop=…` macht ein totes/gerissenes Kabel sichtbar.
Nur eine App darf das USB-Gericht halten — `com.rokid.spatialstudio`
vor dem Start dieser App stoppen.

###360°-Videosphäre

Ein filamat-`video_external`-Material samplt einen Filament-`Stream`,
gewrappt um eine von media3 gespeiste `SurfaceTexture`. Im Shader werden
Sampler als **Makro** `materialParams_<name>` referenziert (Punkt-Syntax
nur für Uniform-Parameter). Die Equirect-Sphäre nutzt `culling(NONE)`.

**Fallstrick — auf dem Kopf stehendes Video**: Frames kommen GL-
orientiert an (Zeile0 = unten), die Panorama-Konvention erwartet
v=0 = oben; `getUV0()` muss also invertiert werden
(`vec2(uv.x, 1.0 - uv.y)`), sonst rendert die ganze360°-Welt auf dem
Kopf (statt Dächer oben — Wasser oben). Der Bug lebte seit Phase B2,
bis ein Screenshot einer Straßenpanorama ihn fing.

### D1 — glTF-Import

`GltfParser` (core-scene) versteht GLB-Container, `data:`/externe
Buffer, bufferViews/accessors (f32/u16/u32, gestaffelt), Knoten-
`matrix`|-TRS-Hierarchien und berechnet Modellgrenzen über den
Hierarchie-Walk.

Gelöste Fallstricke (jeder kostete eine Debug-Sitzung):

1. **Unsichtbares Modell** — `IndexBuffer` muss mit *exakter*
   Bytegröße allokiert werden (`indexCount * 2|4`). Ein Helper, der
   „wie float" rechnet (×4), lässt Filament den Draw **stillschweigend
   verwerfen** (kein Fehler, null Pixel). Derselbe Bug-Klasse lag
   vorher in `Meshes.kt`.
2. **Normalen in Filament** — es gibt kein `VertexAttribute.NORMAL`;
   Normalen liegen im Attribut **TANGENTS** als Quaternion-Frame.
   Packen Sie `(tangent, bitangent, normal)` mit
   `MathUtils.packTangentFrame(...)`; Bitangent = ±(n × t), das Vorzeichen
   steckt im `w` des Quaternions (bei glTF TANGENTS.w).
3. **Beleuchtung** — die gerichtete Lichtintensität ist in Lux
   (110000 ≈ klarer Tag), die Richtung ist, wohin das Licht *reist*;
   die Kameraexposition nutzt das physikalische `setExposure(sunny f/16)`.
   Ohne IBL sind Metalle fast schwarz (behoben in D2c).

### D2 — Materialien & IBL

* **baseColor ist sRGB** — als `InternalFormat.SRGB8_A8` laden und die
  Linearisierung beim Samplen der GPU überlassen (billiger und korrekter
  als eine CPU-LUT). Normal/MR-Maps bleiben einfaches `RGBA8`
  (lineare Daten!).
* **UV0** muss für jeden Lit-Renderable deklariert sein; mit Nullen
  füllen, wenn das Modell keine hat — das Vertex-Layout bleibt gültig.
* **MR-Sampling**: `metallic = mrFactor * tex.b`,
  `roughness = clamp(mrFactor * tex.g, 0.04, 1)`.
* **Normal-Maps** werden im *Tangent-Raum* gesetzt und **vor**
  `prepareMaterial()` (siehe Normal-Mapping-Beispiel in den
  Filament-Doks).
* **IBL (D2c)**:
  * die Umgebung ist ein prozedurales equirect, gerendert mit
    *exakt* dem Mapping des `equirectToCube`-Materials von Filament:
    `u = 0.5 + atan2(x, z)/2π`, `v = 0.5 - asin(y)/π` (v=0 = Zenit);
  * GPU-Präfilter via `IBLPrefilterContext` aus filament-utils:
    equirect → Cubemap → `SpecularFilter` (Roughness-Mips) →
    `IndirectLight.reflections()`;
  * **das AAR liefert `libfilament-utils-jni.so`, aber keine Java-Klasse
    lädt sie** — `System.loadLibrary("filament-utils-jni")` selbst
    aufrufen;
  * die equirect-Textur braucht `SAMPLER_2D` und eine **volle
    Mip-Kette** (`levels == ilogb(width)+1`) samt `GEN_MIPMAPPABLE`-
    Flag, sonst panikt `generateMipmaps` mit einer Precondition;
  * Diffuse =3-Band-SH derselben analytischen Umgebung:
    `sh[i] = c_i · Ĉ_l · N_i / π`, wobei `Ĉ = [π, 2π/3, π/4]`
    (abgeschnittener Kosinus) und `N` die Sloan-Polynom-Konstanten sind —
    also **„Fd_Lambert() ist ins SH eingebacken"** (abgeglichen mit
    `CubemapSH.cpp` und dem mitgelieferten `Irradiance_SphericalHarmonics`-
    Shader; Sanity-Check: `sh[0]` entspricht der mittleren Bestrahlung
    der Umgebung).

### D3 — Physik (jolt-jni)

Abhängigkeit: `com.github.stephengold:jolt-jni-Android:7.0.0:SpDebug@aar`.

Gelöste Fallstricke:

1. Die **`classes.jar` von SpRelease ist in veröffentlichten Releases
   von R8 bis auf nichts gestripped** — gegen **SpDebug** kompilieren.
2. Das AAR deklariert **minSdk 33**, die Box läuft Android12 —
   `<uses-sdk tools:overrideLibrary="com.github.stephengold.joltjni" />`
   ergänzen.
3. `JoltPhysicsObject.startCleaner()` braucht `java.lang.ref.Cleaner`
   (**API 33+**) — absichern und transiente native Wrapper (Shapes,
   `BodyCreationSettings`) explizit `close()`-n; `RVec3`/`Quat`/`Vec3`
   sind reines Java und lecken nicht.
4. Die Initialisierungsreihenfolge zählt: `loadLibrary("joltjni")` →
   Allokatoren → `newFactory()/registerTypes()` → Layertabellen →
   `PhysicsSystem.init`.
5. Dragging = Federkraft `F = m·(kp·Δx − kd·v)` zu einem Zielpunkt
   (Jolts eigener Drag ist auch eine Feder); die Masse wird aus dem
   Kastenvolumen bei Jolts Standarddichte (1000 kg/m³) geschätzt.

### D4 — Export

`GltfExporter` gibt wieder, was der Parser konsumiert: Knotenmatrizen,
Meshes mit POSITION/NORMAL/UV0 und u16/u32-Indizes (POSITION-Accessor
tragen die spez Pflicht-min/max), PBR-Materialien, Sampler/Texturen und
die **origuellen Bild-Bytes**, wörtlich in den GLB-BIN-Chunk kopiert —
Texturen überleben den Round-Trip also ohne Re-Encoding. GLB-Chunks sind
4-Byte-ausgerichtet (JSON mit Leerzeichen, BIN mit Nullen).

Downloads laufen über **MediaStore** (`IS_PENDING`-Fluss) auf Q+ — ein
MIME-Typ wählen, dessen kanonische Endung zur Datei passt (z. B.
`model/obj`, nicht `text/plain`, sonst hängt der Provider `.txt` an).
Der Round-Trip wird direkt nach dem Export in-memory geprüft: GLB neu
parsen und Primitive/Knoten/Texturen/Materialien zählen.

### Eingabe-Hinweis: Tap vs. Drag

`moved` muss aus der **kumulativen** Delta seit `ACTION_DOWN`
berechnet werden, nicht aus Event-Deltas — langsame Züge (kleine
Event-Deltas, z. B. ein `input swipe` mit langer Dauer oder ein
vorsichtiger Mensch) wurden vorher als Tap missverstanden.

Außerdem: das `ACTION_UP` eines Zugs muss `InputEvent.DragEnd` senden —
sonst hält der Recognizer seine `dragging`-Flagge und **verschluckt den
nächsten echten Klick** (Symptom: zweimal klicken; in adb-Skripten tat
`input tap` direkt nach `input swipe` nichts).

### Panel-Widgets (das Video-Deck)

* `TextureSampler` ist **nativ** — ein companion-`val` würde in
  `<clinit>` *vor* `Filament.init()` (das lädt libfilament-jni)
  erzeugt und mit `UnsatisfiedLinkError: nCreateSampler` sterben. Solche
  vals `by lazy` halten (erste Nutzung im Renderthread nach Engine-
  Erstellung).
* Widget-Bitmaps mappen auf Panel-UVs **ohne** V-Flip (rohes `getUV0()`)
  — anders als die Videosphäre bringt ein normales `setImage`-Bitmap-
  Upload die Bitmap-Oberkante schon mit der Panel-Oberkante zur
  Übereinstimmung. Falsche Richtung zeigt sofort: das ganze Deck
  vertikal gespiegelt; den Video-Flip hier nicht wiederverwenden.
* Panels mit `persistent=true` überleben das Ausblenden im
  Immersive-Video — geloggt als `3 hidden, 1 persistent` (die
  Arc-Panel-Platten verschwinden, das Deck bleibt zur Steuerung).

### Skybox & Schatten

* Der Skybox nutzt `IblBuilder.reflections` wieder (mip0 = scharfe
  Umgebung): `Skybox.Builder().environment(...)`. Im Skybox-Modus wird
  die flache Farbsphäre NICHT erzeugt (`sphereEntity = -1`), alle
  Sphären-Pfade prüfen `>= 0`. Video läuft wie gehabt — die Videosphäre
  wird bei Bedarf gebaut und blendet Boden + Arc-Panels aus (das
  persistente Deck bleibt).
* Schatten brauchen einen **LIT-Receiver**: unlit Panels ignorieren Licht
  komplett, deshalb gibt es einen beleuchteten Boden (`lit_pbr`, matt,
  y = −1 = Physikboden) — ohne ihn würde das Modell ins Nichts werfen.
  Verifiziert mit einem tempierten Sonne-gerade-runter-Test: die
  Helmsilhouette liegt am Boden; beim schrägen (IBL-ausgerichteten) Licht
  liegt der Schatten hinter dem Deck und ist nur von der Seite sichtbar.
* Aliasing-Falle: mehrere `updatePanelUi`-Aufrufe mit EINEM Pixelpuffer
  ließen alle Panels das zuletzt gezeichnete Bild zeigen — der Kanal
  klont Pixel jetzt.

## Debugging-Tipps

* **Screenshots**: PowerShell `>` zerstört Binärdaten —
  `cmd /c "adb exec-out screencap -p > shot.png"` nutzen (das
  PNG-Magic muss mit `89-50-4E-47` beginnen).
* **Ziel-Rennen**: die Brille sitzt auf einem lebendigen Kopf; jeder
  geskriptete `input swipe` konkurriert mit dem IMU. Recenter
  (`keyevent46`) und die `diag: look=(…)`-Zeile des Renderers lesen
  (wird jede Sekunde geloggt) — sie zeigt, wohin die Kamera wirklich
  schaut.
* `input swipe x1 y1 x2 y2 duration` erzeugt echte MOVE-Events — gut
  für Drag-Logik; `input tap` ist ein Tap; `input keyevent <code>` für
  Tasten (`E`=33, `M`=41, `N`=42, `P`=44, `R`=46, `V`=50).
* Der Renderloop loggt `diag: vp=… entities=… renderables=…` — ein
  fehlendes Modell zeigt sich als zu niedriger `renderables`-Zähler,
  noch bevor man auf Pixel schaut.

## Roadmap / TODO

### Fertig (in diesem Repository)

- [x] Phase0–1: Filament-Host, Panels, Gesten, HUD, Thermik-Governor
- [x] Rokid-Raw-HID-Bridge (stall/auto-resume), Viture-Protokoll-Port
- [x] Phase B2:360°-Videosphäre (HLS/equirect/RTSP), Quellenwechsel
- [x] **D1**: glTF-Import + Manipulation (Pick/Drehen/Skalieren/Wechseln)
- [x] **D2**: PBR-Materialien — baseColor, MR, Normal-Maps, IBL
- [x] **D3**: Jolt-Physik — Gravitation, Boden, Greifen-und-Werfen
- [x] **D4**: glTF/OBJ-Export mit verifiziertem Round-Trip

### Nächste Verbesserungen (klein, konkret)

- [ ] **Emissive- + AO-Maps** aus glTF (Texturindizes hält der Parser
      schon; ein weiterer Sampler in `lit_pbr`)
- [x] **Sichtbarer Skybox** aus der IBL-Umgebung — fertig: Himmel aus den
      gefilterten Reflections (mip0); im Skybox-Modus wird die flache
      Farbsphäre NICHT erzeugt (bleibt Fallback bei IBL-Ausfall)
- [x] **Schatten** — fertig: Key-Light wirft, Modell wirft/fängt, neuer
      beleuchteter Boden (`lit_pbr`, matt, y = −1 = Physikboden) fängt;
      Arc-Panels ausgeschlossen (unlit dürfen nicht werfen)
- [ ] **Tonemapping/HDR**: Post-Processing ist gerade wegen Performance
      aus — ACES mit Bloom für Emit/Neon zurückholen
- [ ] **STL- + OBJ-Import** (Export exists; Import schließt den Kreis
      für3D-Druck-Workflows)
- [ ] **Mehrere Modelle gleichzeitig** — `ModelManager` lädt schon eine
      „Gruppe"; auf eine Szenenliste erweitern mit per-Object
      select/move/delete (Fundament für die Szenen unten)
- [ ] **Physik: Stapel-Szenen** — mehrere Kugeln/Kästen spawnen,
      Kollisions-Callbacks (Logs/Events), Raycast-Abfragen für Gameplay
- [x] **Panel-Widgets**: Buttons/Sliders/Checkboxen, in Panel-Texturen
      gerendert mit UV-Hit-Testing — fertig: `Widgets.kt` +
      `PanelUiDrawer`
- [x] **Wiedergabe-Steuerpult** für Video (play/pause/seek/Quellenwahl) —
      fertig: die persistente `video-deck`-Platte (PLAY/PAUSE,
      SRC-Schritt, LOOP, Spulen per Klick über Position/Dauer von
      `StreamPlayer`)
- [ ] **CI**: GitHub-Actions-Workflow (`assembleRelease` + Unit-Tests)
- [ ] **Parser-Testsuite**: Round-Trip-Fuzz-Tests `GltfParser` ↔
      `GltfExporter` (reines Kotlin-Moduell, leicht auf der JVM)

### Mittelfristig (echte Features)

- [ ] **Phase C — räumliches Webbrowsen**: WebView in eine Quad,
      PC-Desktop-Sharing über den `verto-display-driver`-IPC-Brücke
      (Python-`vdd`-Agent übers LAN)
- [ ] **glTF-Animationen & Skinning**: Animationskanäle →
      Filament-`Animator`/Knochentransformen (Charaktermodelle werden
      nutzbar)
- [ ] **Morph-Targets**, komprimiertes glTF (Draco/KTX2) — Decoder
      nötig
- [ ] **Voller Material-Export**: `.mtl` für OBJ, Re-Encoded Texturen
      in glTF, KHR-Extensionen (unlit, clearcoat, transmission)
- [ ] **Szenen-Export**: mehrere Objekte + Transformen + Physikzustand
      als eine glTF-Szene (Round-Trip des ganzen Workspaces)
- [ ] **Räumliches Audio** für Video (media3-Audio + Kopf-relative
      Panning)
- [ ] **6DoF-Aufstiegspfad**: Rokid Max Pro über den isolierten
      UXR-SDK-Hook (`RokidMaxBridge.UXR_ENTRY_CANDIDATES`) oder
      Inside-Out-Tracking aus einer Telefonkamera
- [ ] **Hand-/Controller-Tracking** (Max Pro) in den bestehenden
      InputEvent-Producer/Recognizer-Split einstecken
- [ ] **Sprachsteuerung** (Offline-STT) für Recenter/Export/Quellen-
      wechsel ohne Hände
- [ ] **Gamepad/Tastatur-First-UX**-Overlay mit Shortcut-Hilfe

### Langfristig / experimentell (Phase E und darüber hinaus)

- [ ] **Cloud-Szenensync**: Workspace serialisieren (D4 schreibt schon
      glTF) → Upload → Multiuser-Sessions derselben Szene
- [ ] **AIDL-Plugin-API**: Feature-Module (neue Panels, Importe,
      Spiele) zur Laufzeit laden ohne Neubau des Hosts
- [ ] **Cloud-Rendering / externe GPU**: Desktop-Class-Renderer in den
      3DoF-Host streamen
- [ ] **Prozedurales Terrain + Vegetation** auf dem Physik- und
      glTF-Stack
- [ ] **Aufnahme**: die3D-Ansicht (oder Komposit aus Videosphäre +
      Panels) fürs Teilen in eine Datei fangen
- [ ] **Wear-Detection** (Brille an/aus über IMU-Aktivität) →
      Video auto-pausieren, UI dimmen
- [ ] **Boot-Autostart-Dienst** + Geräte-Provisioning (die Box ist
      headless; heute startet die App per USB-Attach-Intent)

## Lizenz

Noch keine Lizenzdatei — eine hinzufügen (MIT/Apache-2.0) vor der
Veröffentlichung.
