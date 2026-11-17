# Fähigkeiten - was diese Demo zeigt und wie man sie sieht

**Sprachen**: [English](CAPABILITIES.md) · [Русский](CAPABILITIES.ru.md) · Deutsch (diese Datei)

Das ist die Showroom-Inventur: jede in `rokid-spatial-host` implementierte
Fähigkeit, welches Modul sie implementiert, wie man sie auf dem Gerät
demonstriert und wo der Code liegt. Damit lässt sich entscheiden, **was
als Nächstes gebaut wird** — siehe `FUTURE.md` für die Ideen, die aus
diesen Fähigkeiten entstehen.

**Ziel**: Rokid Max (3DoF) auf einem Android-ARM64-Box, Maus + Tastatur.
Build/Installation/Berechtigungen: siehe README «Build & run».

**Wichtig (Phone-Hosts)**: Vor dem Eintritt in den Brillen-Modus muss
das Gerät **zwingend in Querformat (Album-/Landscape-Modus)** gedreht
werden — die App macht das nicht automatisch; im Hochformat kommt der
Brillen-Modus nicht korrekt hoch. Box-Hosts betrifft das nicht.

Schnelle Verifikation jeder Änderung:

```powershell
adb logcat -s SpatialHost FilamentRenderer MaterialFactory ModelManager \
    IblBuilder PhysicsWorld RokidMaxBridge UsbDiagnostics
```

Status-Legende: **✅ auf Gerät verifiziert** · **🟡 implementiert,
Gerätecheck nötig** · **⚪ theoretisch / noch nicht erprobt**

---

## A. Kopfverfolgung & Anzeige

| # | Fähigkeit | Status | Modul | Demonstration | Code |
|---|---|---|---|---|---|
| A1 | **Rokid-Max-raw-HID-3DoF-Tracking** — kein UXR-SDK; Quaternion aus dem Interrupt-IN-HID der Brille | ✅ | `core-bridge` | Brille einstecken; die HUD-Zeile `pose:` ändert sich beim Drehen des Kopfes; `stream: pkt=… drop=…` zählt die Live-Pakete | `RokidMaxBridge`, `MadgwickRokid` |
| A2 | **Bridge-Prioritätskette** — Rokid → Viture → Emulator-Fallback; die App läuft auch headless ohne Brille | ✅ | `:app` + `core-bridge` | Ohne Brille starten: HUD `bridge: EMULATOR · Touch/mouse-look`; Brille einstecken, neu starten: `ROKID_MAX · Rokid Max` | `MainActivity.connectChain()` |
| A3 | **Viture-Protokoll-Port** (Gen2 `0x0301`/`0x7308`, Gen1 Euler@18) | 🟡 echte Viture-Brille nötig | `core-bridge` | Wie A1, nur mit angeschlossener Viture-Hardware | `VitureHidBridge` |
| A4 | **Manueller Look-Offset über der IMU** — ein Maus-Drag steuert einen über der Kopfpose liegenden Offset | ✅ | `:app` + `core-bridge` | Mit angelegter Brille die Maus ziehen: Der Blick schwenkt, `pose:` verfolgt weiter | `MainActivity.wireTouch()`, `QuaternionPoseProvider` |
| A5 | **Recenter** des Sitzungsframes | ✅ | `core-input` | Doppelklick oder `R` drücken | `Gesture.RECENTER` → `HeadPoseProvider.recenter()` |
| A6 | **Emulator-Look-Modus** — Touch-/Maus-Look ohne Hardware; optionaler Handy-Gyro-Remote | ✅ | `core-bridge` | Ohne Brille laufen lassen, zum Umsehen ziehen; `EmulatorBridge(useDeviceSensors = true)` für den Handy-Remote | `EmulatorBridge` |
| A7 | **Schnittstelle zur Anzeigensteuerung** — Helligkeit / 2D-3D-Modus (`DisplayController`) | ⚪ nur Schnittstelle + Null-Object; noch kein Geräteprotokoll verdrahtet | `core-bridge` | — (das HUD stellt es noch nicht bereit) | `DisplayController`, `UnavailableDisplay` |
| A8 | **6DoF-Sonde** — erkennt UXR-SDK-Klassen auf dem Max Pro, bereit für den Pfadwechsel | ⚪ nur Erkennung | `core-bridge` | logcat zeigt `rokid bridge unavailable: …` / `uxrSdkPresent` | `RokidMaxBridge.UXR_ENTRY_CANDIDATES` |

## B. Panels & Widgets (die räumliche UI)

| # | Fähigkeit | Status | Modul | Demonstration | Code |
|---|---|---|---|---|---|
| B1 | **3DoF-Blick-/Fadenkreuz-Auswahl** schwebender Panels (Ray aus der Kopfpose) | ✅ | `core-input` + `:app` | Ein Panel ansehen; `focus=` im HUD ändert sich; klicken zum Auswählen | `RayCursor`, `LayoutEngine.hitTest` |
| B2 | **Panel-Bogenlayout** — Standard-3-Panel-Bogen bei 2.5 m, verschiebbar | ✅ | `core-scene` | Sichtbarer Bogen aus Karten; `defaultArc()` beim Start | `LayoutEngine` |
| B3 | **Fokus-Wechsel** über Swipe/Pfeiltasten | ✅ | `core-input` + `:app` | `←`/`→` oder Swipe; `focus=` schaltet durch die Panels | `cycleFocus()` |
| B4 | **Widget-Rasterisierung** — Buttons/Toggles/Slider/Labels in Panel-Texturen gezeichnet | ✅ | `core-render` + `core-scene` | Das Video-Deck zeigt PLAY / ‹SRC / SRC› / LOOP / SEEK | `PanelUiDrawer`, `Widgets.kt` |
| B5 | **Widget-UV-Hit-Testing** — ein Klick innerhalb eines Panels wird einem Widget zugeordnet | ✅ | `:app` | PLAY im Deck anklicken: Das Video pausiert; SEEK anklicken: Die Abspielposition springt | `handleWidget()` |
| B6 | **HUD-Overlay** — Bridge, fps, Pose, Panels, Modell, Animation, Leistung, Touch-Hinweis, Videozustand | ✅ | `:app` | Dauerhaft sichtbar; `H` schaltet um | `updateHud()` |
| B7 | **Persistente Panels** — das Video-Deck bleibt erhalten, während die Video-Sphäre den Rest verbirgt | ✅ | `core-render` | Video starten: Die Bogen-Panels verschwinden, das Deck bleibt | `PanelSpec.persistent` |
| B8 | **Fokus-/Auswahl-Hervorhebung** im Renderer | ✅ | `core-render` | Das fokussierte Panel wird hervorgehoben gerendert | `setFocusedPanel`, `applyFocus` |

## C. Video (360°-Sphäre)

| # | Fähigkeit | Status | Modul | Demonstration | Code |
|---|---|---|---|---|---|
| C1 | **360°-Equirect-Video-Sphäre** (Hintergrund r=30 m) | ✅ | `core-video` + `core-render` | Startet ~3 s nach dem Start automatisch; `V` schaltet auf die Farbsphäre | `StreamPlayer`, `requestVideoSphere` |
| C2 | **HLS-Live- + VOD-Quellen** (6 integrierte: Equirect-Demo, Akamai live, Mux ToS/PTS, bipbop, Big Buck Bunny) | ✅ | `:app` + `core-video` | `N` schaltet die Quellen durch; HUD `video:` zeigt Name + Zustand | `PUBLIC_SOURCES` |
| C3 | **RTSP / beliebige ExoPlayer-URL** — an `PUBLIC_SOURCES` anhängen | ✅ (URL-Weg) | `:app` + `core-video` | `rtsp://…` hinzufügen, neu starten, mit `N` dorthin schalten | `StreamPlayer.prepare` (RTSP-Abhängigkeit vorhanden) |
| C4 | **Lokale Dateiwiedergabe** — mp4/mkv/webm/mov/ts/avi/m4v aus 4 Verzeichnissen gescannt | ✅ | `:app` | `adb push x.mp4 /sdcard/Movies/`, neu starten, mit `N` dorthin schalten | `scanLocalVideos()`, `LOCAL_VIDEO_DIRS` |
| C5 | **Optimierung für niedrige Latenz** — `lowLatency=true`, knappe Buffer-Konfiguration | ✅ | `core-video` | Live-HLS spielt ohne mehrsekündige Verzögerung | `StreamPlayer.prepare` |
| C6 | **Wiedergabe-Deck** — Wiedergabe/Pause, Quellenwechsel, Loop-Umschalter, Klick-zum-Springen | ✅ | alle drei | Deck-Widgets per Blick anklicken; HUD-Zeit aktualisiert sich | `videoPanelWidgets()`, `playPauseVideo()` |
| C7 | **Loop-Umschalter** (`LOOP`) + Wahrheit des ersten Frames (`onFirstFrame` → `streaming`) | ✅ | `core-video` | LOOP umschalten; HUD wechselt beim ersten Frame von `loading → streaming` | `StreamPlayer.onFirstFrame` |
| C8 | **Fehler sichtbar machen** — ExoPlayer-Fehler erreichen das HUD (`err:ERROR_CODE_…`) | ✅ | `core-video` + `:app` | Auf eine tote URL zeigen, das HUD beobachten | `StreamPlayer.onError` |

## D. 3D-Modelle (Import & Manipulation)

| # | Fähigkeit | Status | Modul | Demonstration | Code |
|---|---|---|---|---|---|
| D1 | **Eigener glTF/GLB2-Parser** — GLB + JSON `.gltf`, eingebettete data-URIs, externe `.bin`, Knotenhierarchie, PBR-Materialien | ✅ | `core-scene` | Das mitgelieferte DamagedHelmet wird mit vollem PBR gerendert | `GltfParser` |
| D2 | **PBR-Materialien** — baseColor (Hardware-sRGB), Metallic-Roughness, Normal Maps aus glTF-Tangents, Emissive + AO, sRGB-/linear-Split | ✅ | `core-render` | Helm: Metallreflexe, Leuchten, korrektes Shading | `MaterialFactory.buildLit` |
| D3 | **IBL** — prozedurales Equirect → GPU-gefilterte Reflexe + SH-Irradiance; sichtbarer Skybox aus derselben Umgebung | ✅ | `core-render` | Metalle spiegeln den Himmel; Hintergrund = scharfe Umgebung | `IblBuilder` |
| D4 | **Key Light + Schatten** — direktionales Licht mit 110 klux, ausgerichtet mit der IBL-Sonne; das Modell wirft Selbstschatten auf die beleuchtete Platte (y=-1) | ✅ | `core-render` | Helm-Schatten auf dem Boden | `renderLoop`, `buildShadowFloor` |
| D5 | **Modell-Picken / Rotieren / Skalieren** — Pick unter dem Fadenkreuz (nächstes `hitBox`), Drag dreht, Mausrad skaliert ×0.2…×6 | ✅ | `:app` + `core-scene` | Modell anklicken, ziehen, Mausrad | `pickModelAtCrosshair()` |
| D6 | **Lokale Dateiquellen** — `.glb/.gltf/.stl` automatisch aus 4 Verzeichnissen gescannt, `M` schaltet Demo→Dateien | ✅ | `:app` | `adb push model.glb /sdcard/Download/`, neu starten, `M` | `scanLocalModelFiles()`, `MODEL_EXTENSIONS` |
| D7 | **STL-Import** (binär `84+50n` + ASCII) als GltfModel-Adapter — schließt den 3D-Druck-Loop | ✅ | `core-scene` | `cube.stl` pushen, mit `M` dorthin: Ein weißer Würfel wird gerendert | `StlParser` |
| D8 | **All-Files-Zugriff** — `MANAGE_EXTERNAL_STORAGE`, weil Scoped Storage Nicht-Media-Dateien vor `READ_EXTERNAL_STORAGE` verbirgt | ✅ | `:app` | Der erste Start öffnet den Schalter einmal; oder `adb shell appops set <pkg> MANAGE_EXTERNAL_STORAGE allow` | `requestAllFilesAccessIfNeeded()` |

## E. Sandkasten (mehrere Objekte + Physik)

| # | Fähigkeit | Status | Modul | Demonstration | Code |
|---|---|---|---|---|---|
| E1 | **Szenenliste der Objekte** — jedes mit eigener id, Transformation, Skalierung | ✅ | `:app` + `core-render` | HUD `model: … ×N` | `ModelObject`, `ModelManager.models` |
| E2 | **Klonen (`B`) / Löschen (`DEL`)** des ausgewählten Objekts | ✅ | `:app` | `B` ×2 → `×3`; `DEL` → `×2`, Log `deleted …` | `spawnClone()`, `deleteSelected()` |
| E3 | **Pick des nächsten Objekts unter dem Fadenkreuz** | ✅ | `:app` | Zwischen zwei Objekten zielen, klicken: Das nähere wird ausgewählt | `pickModelAtCrosshair()` |
| E4 | **Physik-Umschalter (`P`)** — jedes Objekt wird aus seiner skalierten AABB eine dynamische Box und fällt auf den Boden `y=-1` | ✅ | `core-physics` + `:app` | `P`: Objekte fallen und **stapeln**; Log `physics ON — N bodies` | `togglePhysics()`, `PhysicsWorld.addModelBox` |
| E5 | **Greifen & Werfen** — auf das Objekt drücken, um es federnd zu ziehen, mitten im Schwung loslassen, um es zu werfen | ✅ | `core-physics` + `:app` | `P` an, Objekt gedrückt ziehen, während es sich bewegt loslassen | `tryGrabAtCrosshair()`, `PhysicsWorld.dragTo` |
| E6 | **Posen-Einfrieren bei Physik aus** — die Landepose (pos + yaw/pitch aus dem Körper-Quaternion) bleibt erhalten | ✅ | `:app` | `P` aus: Objekte bleiben dort, wo sie gelandet sind | `togglePhysics()` |
| E7 | **Mausrad skaliert die Auswahl** (nur wenn Physik aus — die Simulation besitzt die Transformationen, solange sie aktiv ist) | ✅ | `:app` | Mausrad auf dem ausgewählten Objekt | `ACTION_SCROLL`-Wächter |
| E8 | **Robustheit beim Szenen-Wiederaufbau** — Wechsel des Einstellungs-Bildschirms baut die Szene neu auf; Modelle/Panels/Video werden erneut gepusht | ✅ | `:app` + `core-render` | All-Files-Einstellungen öffnen, zurückkehren: Alles kehrt zurück; Log `scene content re-applied` | `onSceneReady` → `reapplySceneContent()` |

## F. Animationen & Skinning

| # | Fähigkeit | Status | Modul | Demonstration | Code |
|---|---|---|---|---|---|
| F1 | **Starre glTF-Animationsclips** — LINEAR/STEP/CUBICSPLINE (Hermite), Quaternion-nlerp, Endlosschleife, 250-ms-dt-Begrenzung | ✅ | `core-scene` + `core-render` | Ein animiertes `.glb` laden; HUD `anim: clip 1/1 … 0.59/2.00s` zählt hoch | `Animator`, `ModelManager.tickAnimations` |
| F2 | **Wiedergabe/Pause (`A`) und nächster Clip (`G`)** | ✅ | `:app` | `A` → HUD `PAUSED 0.87/2.00s`; `A` setzt fort; `G` → Log `animation clip → 0` | `requestAnimationToggle()`, `requestNextAnimationClip()` |
| F3 | **GPU-Skinning** — `skins` + `JOINTS_0`/`WEIGHTS_0`, ≤255 Knochen, 4 pro Vertex; pro Frame `bone[j] = inverse(meshWorld)·jointWorld[j]·IBM[j]` (gltfio-Formel) | ✅ | `core-scene` + `core-render` | **CesiumMan.glb** pushen (Khronos-Sample), mit `M` dorthin: Die Figur **geht**; Beine/Arme bewegen sich korrekt | `GltfSkin`, `ModelManager.updateBones` |
| F4 | **Round-trip-Export von Animation + Skin** — Clips und Skins werden erneut in die GLB ausgegeben | ✅ (JVM-Tests) | `core-scene` | `AnimationTest`, `SkinTest` in der CI | `GltfExporter` |
| F5 | **Skinning-Debug-Disziplin** — der genaue Diagnosepfad für „fan of triangles“ (Buffer-Größen-Falle) ist dokumentiert | ✅ | docs | README-Abschnitt «Skinning (GPU bones)» | `buildVertexBuffer` |

F3 selbst ausprobieren:

```powershell
Invoke-WebRequest 'https://raw.githubusercontent.com/KhronosGroup/glTF-Sample-Assets/main/Models/CesiumMan/glTF-Binary/CesiumMan.glb' -OutFile CesiumMan.glb
adb push CesiumMan.glb /sdcard/Download/
adb shell am force-stop com.rokid.spatialhost
adb shell am start -n com.rokid.spatialhost/.MainActivity
# press M until HUD shows model: CesiumMan — the figure walks
```

## G. Asset-Round-trip & Export

| # | Fähigkeit | Status | Modul | Demonstration | Code |
|---|---|---|---|---|---|
| G1 | **GLB-Export (`E`)** — Geometrie, Materialien, Bilder (byte-identisch), Knotenhierarchie, Animationen, Skins | ✅ | `core-scene` + `:app` | `E` → Log `ROUND-TRIP OK` mit Strukturzählen; die Datei landet in Downloads | `GltfExporter.toGlb`, `exportCurrentModel()` |
| G2 | **OBJ-Export** — verlustbehaftetes Nebenformat, Platzierungsmatrix in die Vertices eingebrannt | ✅ | `core-scene` | Derselbe `E`-Druck erzeugt auch eine `.obj` | `GltfExporter.toObj` |
| G3 | **Verifizierter Round-trip** — die App parst ihre eigene GLB vor dem Schreiben im Speicher neu | ✅ | `:app` | Die Logzeile beweist die Strukturgleichheit | `exportCurrentModel()` |
| G4 | **JVM-Testsuite (17 Tests)** — Parse↔Export-Round-trip inkl. mitgeliefertem DamagedHelmet, STL binär/ASCII, Animationssampling, Skin-Bind-Pose, Mat4 | ✅ | `core-scene` | `.\gradlew.bat :core-scene:test`; CI grün beim Push | `core-scene/src/test/…` |

## H. Plattform & Qualität

| # | Fähigkeit | Status | Modul | Demonstration | Code |
|---|---|---|---|---|---|
| H1 | **Temperatur-/FPS-Governor** — Ziel-fps + Auflösungsskala aus dem Temperaturstatus | ✅ | `core-battery` | HUD `fps: 90 (target 90, thermal 0)`; die Box erhitzen, den Zielwert sinken sehen | `ThermalGovernor` |
| H2 | **Leistungsbericht der Sitzung** — vergangene Zeit, verbrauchte mAh, Verbrauch mA, Akku-% | ✅ | `core-battery` | HUD-Zeile `power:` | `PowerReport` |
| H3 | **CI bei jedem Push** — JDK17 + Android SDK35, `test assembleDebug` | ✅ | — | GitHub-Actions-Badge / `gh run list` | `.github/workflows/ci.yml` |
| H4 | **Immersiver Vollbildmodus** + Auto-Start beim USB-Anschließen | ✅ | `:app` | Die App übernimmt die Anzeige der Box; startet beim Einstecken der Brille | `enterImmersive()`, `usbReceiver` |
| H5 | **USB-Diagnose** — einmaliger Bus-Dump zum Debuggen der Erkennung | ✅ | `core-bridge` | `adb logcat -s UsbDiagnostics` | `UsbDiagnostics.describe` |
| H6 | **Frame-Diagnosezeile** — Viewport, Entity-/Renderable-Zähler, Look-Vektor (1 Hz) | ✅ | `core-render` | `adb logcat -s FilamentRenderer:D` | `renderLoop`-Diag |
| H7 | **Release-Build** — arm64, mit Debug-Schlüssel signiert, ~26 MB | ✅ | `:app` | `assembleRelease` | README-Build-Abschnitt |

---

## Vorgeschlagenes Demo-Skript (5 Minuten)

1. **Booten** → Bridge verbindet, HUD zeigt `ROKID_MAX`, Video startet automatisch.
2. **Umsehen** → Panels folgen dem Kopf; das Deck ansehen, PLAY klicken.
3. **`N` ×2** → Streams wechseln; **`V`** aus → Farbsphäre + Panels zurück.
4. **`M`** → zu einem gepushten Modell schalten; zum Rotieren ziehen, mit
   dem Mausrad skalieren.
5. **`B` `B`** → drei Objekte; **`P`** → sie fallen und **stapeln**; eines
   greifen und werfen; **`DEL`** entfernt die Auswahl.
6. **`M`** → CesiumMan: Die Figur geht (**F3** — die neueste Fähigkeit).
7. **`A`** → mitten im Schritt pausieren, **`A`** fortsetzen; **`E`** → `ROUND-TRIP OK`.
8. **`H`** → freier Blick für ein Foto.
