# Zukünftige Richtungen

**Sprachen**: [English](FUTURE.md) · [Русский](FUTURE.ru.md) · Deutsch (diese Datei)

Ideen, was als Nächstes auf Basis dieser Demo gebaut werden kann. Jeder
Eintrag trägt seine **Quelle**:

* **[code]** — wächst natürlich aus Code, der bereits existiert (das
  Gerüst liegt im Repo; der Eintrag sagt, was fehlt)
* **[roadmap]** — übernommen aus der Roadmap im README des Repositories
  (größere Features, einige schon halb skizziert)

Die Einträge unter *Fern* sind bewusst knapp: nur ein Name, um zu
wissen, wonach zu suchen ist — die Details müssen ohnehin recherchiert
werden, wenn der Bedarf entsteht.

Keine Bindungsliste. Nutzen, um eine Richtung zu wählen; `MODULES.md`
sagt, welches Modul jede Richtung anfassen würde.

---

## Kurzfristig — kleine Veränderungen am bestehenden Code

### 1. Panels mit der Maus ziehen [code]

* **Vorhanden**: `LayoutEngine.move(id, to)` (core-scene), Panel-Pick
  über `RayCursor`, Drag-Events werden bereits zur Modellrotation
  weitergeleitet, wenn ein Modell ausgewählt ist.
* **Fehlend**: ein Zustand `DRAGGING_PANEL` — wenn das Fadenkreuz ein
  Panel trifft (kein Modell), verschiebt der Drag es in der Sichtebene,
  statt Blick/Modell zu rotieren.
* **Betrifft**: `:app` (`wireTouch`/`handleGesture`), vielleicht ein
  Helper in `core-scene`.

### 2. `DisplayController` für Rokid implementieren [code]

* **Vorhanden**: die `DisplayController`-Schnittstelle, das Null-Object
  `UnavailableDisplay`, der Raw-HID-Kanal in `RokidMaxBridge`, das HUD.
* **Fehlend**: der eigentliche HID-Report für Helligkeit / 2D-3D-Modus,
  HUD-Widgets zum Steuern (ein Slider in einem Panel würde die Widgets
  B4/B5 wiederverwenden).
* **Betrifft**: nur `core-bridge` — der Rest der App sieht die
  Schnittstelle bereits.

### 3. Physik: Kollisionsevents + Raycast-Abfragen [roadmap]

* **Vorhanden**: `PhysicsWorld` mit Pro-Objekt-Körpern; Greifen/Werfen
  beweist, dass die Kontaktabhandlung funktioniert.
* **Fehlend**: ein `onContact(idA, idB, impulse)`-Callback, der der App
  zugänglich gemacht wird, und `raycast(origin, dir) → (id, point, normal)`
  — die beiden Primitive, die jedes Spiel und jede Interaktion braucht.
* **Betrifft**: `core-physics` + ein Demo-Hook in `:app`.

### 4. OBJ-Import [roadmap]

* **Vorhanden**: OBJ-**Export** (`toObj`), STL-Import als Adapter-Muster
  (`StlParser` → `GltfModel`), das Dateiscanning enthält `.obj`
  bereits? — nein: `MODEL_EXTENSIONS = {glb, gltf, stl}`.
* **Fehlend**: ein `scene/obj/ObjParser.kt` (positions + normals + uvs,
  `mtllib` optional), `"obj"` zu `MODEL_EXTENSIONS` hinzufügen.
* **Betrifft**: `core-scene` (+1 JVM-Test), eine Zeile in `:app`.

### 5. Tonemapping / HDR-Bloom [roadmap]

* **Vorhanden**: Post-Processing ist in `renderLoop` ausdrücklich
  **deaktiviert** (`v.isPostProcessingEnabled = false`) samt Kommentar,
  warum (Unlit-Tints im Display-Raum erstellt); Emissive-Maps werden
  bereits gerendert.
* **Fehlend**: ACES + Bloom wieder aktivieren, die Unlit-Panel-Farben
  für die neue Übertragungsfunktion neu abstimmen.
* **Betrifft**: nur `core-render`. Risiko: Jedes bestehende visuelle
  Element muss neu geprüft werden — das zuletzt tun, nicht zuerst.

### 6. Szenen-Export — der gesamte Arbeitsbereich [roadmap]

* **Vorhanden**: GLB-Export pro Modell mit verifiziertem Round-trip; der
  Sandkasten verfolgt bereits id/Transformation/Skalierung jedes Objekts.
* **Fehlend**: ein Writer, der **ein** glTF mit N Knoten (einer pro
  `ModelObject`) + Transformationen ausgibt; ein Import-Pfad, der eine
  Szenenliste wiederherstellt, statt sie zu ersetzen.
* **Betrifft**: `core-scene` (Exporter), `:app` (Lade-/Spiel-Einstieg).

### 7. Sprachsteuerung (Offline-STT) [roadmap]

* **Vorhanden**: Jedes Feature ist bereits ein einzelner Funktions-
  aufruf (`toggleVideo`, `spawnClone`, `togglePhysics`, Recenter…), der
  Tasten-Handler ist ein einziges `when`.
* **Fehlend**: eine STT-Engine (z. B. Vosk / Whisper.cpp Android), ein
  Recognizer, der Phrasen auf dieselben Aufrufe abbildet.
* **Betrifft**: neues `core-voice`-Modul + ein Verdrahtungspunkt in
  `:app`. Freihändiger Recenter/Export/Quellenwechsel ist die natürliche
  erste Demo.

### 8. Gamepad-/Tastatur-zuerst-UX + Shortcut-Overlay [roadmap]

* **Vorhanden**: `GestureRecognizer` bildet bereits
  DPAD/L1/R1/ENTER/BUTTON_A ab; `NavigationState` verfolgt den Fokus.
* **Fehlend**: ein Hilfe-Overlay-Panel mit den Shortcuts (verwendet den
  Widget-Rasterizer), volle Gamepad-Parität, ein Fokusindikator auf dem
  Bildschirm.
* **Betrifft**: `core-input`, ein Panel in `:app`.

---

## Mittelfristig — Features mit echter Designarbeit

### 9. Phase C: räumliches Web-Browsing [roadmap]

* **Vorhanden**: Panels sind texturierte Quads mit UV-Hit-Testing — ein
  in eine Panel-Textur gezeichnetes WebView ist dieselbe Pipeline wie
  `PanelUiDrawer`; die README-Roadmap nennt den PC-seitigen Begleiter:
  `verto-display-driver` (Python-`vdd`-Agent über LAN) für Desktop-Sharing.
* **Fehlend**: die Schleife `WebView → Bitmap → updatePanelUi` (WebView
  muss im Haupt-Thread leben; auf die Panel-Aktualisierungsrate
  drosseln), Input-Weiterleitung (Klicks kommen bereits als u/v an — an
  `WebView.touch` routen), Scroll-Gesten und der `vdd`-Agent für
  PC-Streaming.
* **Betrifft**: neues `core-web` + ein Panel in `:app`. Das ist das
  größte „Fenster in eine andere Welt“-Feature, das das Panel-System
  beherbergen kann.

### 10. Räumlicher Ton für Video [roadmap]

* **Vorhanden**: `StreamPlayer` besitzt Media3 (das einzige Modul, das
  darf), das Kopf-Quaternion steht jedes Frame zur Verfügung.
* **Fehlend**: kopfbezogenes Panning (Ambisonics oder Stereo→Binaural
  über Media3-Effekte oder OpenSL), Lautstärke im Deck-Widget,
  Audio-Metadaten pro Quelle.
* **Betrifft**: `core-video` + Deck-Widget.

### 11. Morph Targets + Draco/KTX2-komprimiertes glTF [roadmap]

* **Vorhanden**: vollständige glTF-Parse-/Export-Pipeline, Mechanik der
  Animationskanäle (Morphs sind nur ein weiteres Animationsziel:
  `weights`).
* **Fehlend**: Parsing der Morph-Attribute (`extras.targetNames`,
  POSITION/NORMAL/TANGENT-Deltas), `MorphTargetBuffer` + `setMorphWeights`
  auf der Render-Seite; eine Decoder-Abhängigkeit für Draco (z. B.
  `draco-android`) und KTX2 (basis-transcoder).
* **Betrifft**: `core-scene` (Parsing), `core-render` (Filament
  unterstützt beide bereits — `morphing(targetCount)` und die Legacy-Pfade
  sind in der API, die wir bereits recherchiert haben).

### 12. 6DoF-Aufrüstpfad [roadmap]

* **Vorhanden**: `HeadPoseProvider.dof: DoF` (das Enum existiert bereits),
  `RokidMaxBridge.UXR_ENTRY_CANDIDATES` nennt die zu erkundenden
  UXR-SDK-Klassen, die Erkennung `uxrSdkPresent` läuft heute; der
  Renderer konsumiert nur ein Quaternion — eine Positionskomponente
  würde denselben Kanal erweitern.
* **Fehlend**: die eigentliche UXR-Session verdrahten (`connectUxr` ist
  mit einem Kommentar nur angedeutet), oder Inside-Out-Tracking aus einer
  Handy-Kamera (`QuaternionPoseProvider.setOrientation` akzeptiert bereits
  eine Orientierung; die Position käme aus visueller inertieller Odometrie
  am Handy).
* **Betrifft**: vor allem `core-bridge`; `RayCursor` und Kamera würden
  Translation lernen (Parallaxe wird möglich).

### 13. Hand-/Controller-Tracking (Max Pro) [roadmap]

* **Vorhanden**: die Producer-/Consumer-Aufteilung wurde genau dafür
  entworfen — README und `InputEvent`-Dokumente sagen: „Handtracking
  steckt hier als weiterer InputEvent-Producer ein; Recognizers bleiben
  unverändert“.
* **Fehlend**: die Tracking-Quelle (UXR-SDK-Hand-API oder kamera-
  basiert), das Mapping der Gesten auf `InputEvent`.
* **Betrifft**: neuer Producer in `core-bridge` oder neues `core-hand`;
  `core-input` bleibt bewusst unverändert.

### 14. Aufnahme der 3D-Ansicht [roadmap]

* **Vorhanden**: Alles, was komposited wird, lebt in einem einzigen
  Filament-View; Media3 ist bereits eine Abhängigkeit.
* **Fehlend**: `Surface`-Capture (`ImageReader`/`MediaRecorder` auf der
  Swap-Chain-Surface ist nicht direkt möglich — man bräuchte Filament-
  `readPixels` oder einen virtuellen Display); Encode-zu-Datei-Pipeline.
* **Betrifft**: neues `core-record` + eine HUD-Schaltfläche.

### 15. Trage-Erkennung + Auto-Start beim Booten [roadmap]

* **Vorhanden**: `MadgwickRokid` konsumiert bereits Beschleunigung/Gyro
  mit Takt (`isInitializedFromAccel`); die App startet über einen
  USB-Attach-Intent.
* **Fehlend**: einen Aktivitätsklassifizierer auf dem IMU-Stream (Box
  still / getragen / abgelegt), Auto-Pause/-Fortsetzen von Video +
  UI-Dimming; einen Boot-Receiver + Provisionierung für den headless-Box.
* **Betrifft**: `core-bridge` (Klassifizierer), `:app` (Lebenszyklus).

### 16. Vollständiger Material-Export — `.mtl`, Texturen, KHR-Extensionen [roadmap]

* **Vorhanden**: `GltfExporter.toGlb` gibt PBR-Faktoren, Texturverweise
  und die **originalen kodierten Bild-Bytes** erneut aus (byte-identisch,
  ohne Re-Kodierung); `toObj` schreibt nur Geometrie.
* **Fehlend**: ein `.mtl`-Sidecar für den OBJ-Export (Kd/Ks/Ns/map_Kd
  aus `GltfMaterial` abgeleitet); Re-Kodier-Pfade für Texturen, deren
  Quelle kein portabler Byte-Blob ist (z. B. künftige dekodierte/
  prozedurale Texturen); **KHR-Extension-Round-trip** — `unlit`,
  `clearcoat`, `transmission` parsen und erneut ausgeben (heute werden
  unbekannte Extensionen von `GltfParser` stillschweigend verworfen,
  sodass z. B. Modelle mit `KHR_materials_unlit` ihr Kennzeichen verlieren).
* **Betrifft**: `core-scene` (`GltfParser`, `GltfExporter`) + JVM-
  Round-trip-Tests. Natürlicher Begleiter zu #4 (OBJ-Import): Zusammen
  schließen sie den Import↔Export-Loop für die gängigen DCC-Formate.

---

## Langfristig / experimentell — Namen zum Suchen [roadmap]

Kurz gehalten mit Absicht; jedes braucht seinen eigenen Research-Spike
vor jeder Schätzung:

* **Cloud-Szenen-Synchronisierung** — Arbeitsbereich serialisieren →
  hochladen → Multi-User-Sitzungen; baut auf dem Szenen-Export (#6) auf.
  Suche: *glTF scene sync realtime collaboration*.
* **AIDL-Plugin-API** — Feature-Module (Panels, Importer, Games) zur
  Laufzeit laden, ohne den Host neu zu bauen. Suche: *Android AIDL
  plugin architecture, dynamic feature modules*.
* **Cloud-Rendering / Remote-GPU** — einen Renderer in Desktop-Klasse in
  den 3DoF-Host streamen. Suche: *WebRTC game streaming, CloudXR*.
* **Prozedurales Terrain + Vegetation** — Demo-Content auf dem Physik-+
  glTF-Stack. Suche: *prozedural terrain heightmap Unity/Unreal style
  algorithms, GPU vegetation*.
* **Selbst geschriebene Spiellogik** — mit #3 (Kollisionsevents) wird der
  Sandkasten zu einer Mini-Game-Laufzeitumgebung; Suche: *Jolt Physics
  gameplay events*.
* **Inhalt für den Stereo-/3D-Modus** — `DisplayController.set3dMode`
  (#2) plus Side-by-Side-Rendering; Suche: *Android stereoscopic 3D
  companion mode*.
* **Multi-Brillen-Sitzungen** — zwei Bridges gleichzeitig (die Kette
  abstrahiert sie bereits) für geteilte Szenen. Suche: *multi-user AR
  shared anchor sessions*.

---

## Wie man wählt

| Wenn du … willst | Starte mit | Warum |
|---|---|---|
| Diese Woche etwas Sichtbares liefern | #1 Panels ziehen, #8 Shortcut-Overlay | reine `:app`/`core-input`-Änderungen, keine neuen Deps |
| Die Box zu einem echten Gadget machen | #2 DisplayController, #15 Trage-Erkennung | `core-bridge` ist isoliert; Hardware-Protokolle leben dort allein |
| Content anziehen | #9 Web-Browsing, #4 OBJ-Import, #11 komprimiertes glTF | Content kommt schneller herein, als wir ihn nativ modellieren können |
| Zur Plattform machen | #13 Handtracking, AIDL-Plugins, Szenen-Export | jedes braucht zuerst die Schnittstellenarbeit in `MODULES.md` §10 |
| „Wow“ bei einem Meetup demonstrieren | Figuren auf CesiumMan-Niveau (#11 Morphs), #3 Physik-Games, #14 Aufnahme | schon zu 80 % da; Aufnahme macht es teilbar |
