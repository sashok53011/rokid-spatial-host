# Каталог модулей

**Языки**: [English](MODULES.md) · [Deutsch](MODULES.de.md) · Русский (этот файл)

Всё в этом репозитории разбито на Gradle-модули так, что новую
функцию можно добавить **не трогая (и не имея возможности сломать)
уже работающие модули**. Этот файл описывает каждый модуль в его
текущем виде: назначение, публичный API, инварианты (что нельзя
нарушать) и способы его расширения. Читайте вместе с:

* `ARCHITECTURE.md` — как модули взаимодействуют во время работы
  (потоки, очереди, потоки данных)
* `CAPABILITIES.md` — что умеет демо и как его демонстрировать
* `FUTURE.md` — что можно построить дальше поверх этих модулей

---

## 1. Обзор модулей

| Модуль | Назначение | Зависит от | Сторонние |
|---|---|---|---|
| `:core-scene` | Модель сцены на чистом Kotlin + конвейер ассетов: разбор glTF/STL, экспорт GLB/OBJ, сэмплирование анимаций, математика 4×4, тесты панелей/виджетов/лучей | *(нет — лист)* | `org.json` (только тесты), JUnit |
| `:core-render` | Рендер на Filament: цикл рендера, материалы, IBL, жизненный цикл GPU моделей, текстуры панелей, видеосфера | `:core-scene` | Filament 1.75.1 (`filament`, `filamat`, `filament-utils`) |
| `:core-physics` | Симуляция твёрдых тел: мир Jolt, динамические боксы, статический пол, хват с пружинным перетаскиванием | *(нет)* | `jolt-jni-Android:7.0.0:SpDebug` |
| `:core-video` | Воспроизведение видео: обёртка над ExoPlayer, настроенная на потоковую сферу с низкой задержкой | *(нет)* | Media3 1.4.1 (`exoplayer`, `hls`, `rtsp`) через `api` |
| `:core-bridge` | Контракты и реализации очков: поставщик позы головы, цепочка мостов, математика кватернионов, управление дисплеем, диагностика USB | *(нет)* | корутины |
| `:core-input` | Контракты ввода: сырые события → жесты, курсор-луч 3DoF, состояние навигации | `:core-scene`, `:core-bridge` | — |
| `:core-battery` | Термостат/FPS-регулятор и учёт энергии за сессию | *(нет)* | корутины |
| `:app` | `MainActivity`: жизненный цикл Android, HUD, связывание всех модулей, обработка клавиш/касаний, разрешения USB, сканирование файлов | все `core-*` | корутины |

Версии зафиксированы в `gradle/libs.versions.toml` (AGP 8.13.0, Kotlin
2.4.21, корутины 1.8.1, Filament 1.75.1, Media3 1.4.1). Filament
намеренно зафиксирован на 1.75.1: 1.80.x требует compileSdk 37.

### Правила зависимостей

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

Жёсткие правила:

1. **`core-*` никогда не зависит от `:app`** и никогда от соседнего
   модуля, кроме рёбер, изображённых выше (`core-render → core-scene`,
   `core-input → core-scene + core-bridge`).
2. **`core-scene` — это лист** — чистый Kotlin, ноль импортов Android
   (поэтому весь конвейер ассетов выполняется как JVM unit-тесты в CI).
   Всё, что может жить здесь, должно жить здесь.
3. **`core-video` выставляет Media3 через `api`**, чтобы приложение
   могло собирать источники, не объявляя Media3 самостоятельно.
4. Новые модули следуют той же схеме именования (`core-<domain>`),
   добавляются в `settings.gradle.kts` и получают запись в разделе
   `docs` этого файла.

---

## 2. `:core-scene` — модель сцены и конвейер ассетов

**Назначение.** Всё, что можно тестировать без GPU или устройства:
представление сцены в памяти, все форматы файлов, математика анимаций
и геометрия лучей/панелей. Здесь запрещены импорты `android.*`.

### Публичный API

**Конвейер ассетов** (`scene/gltf/`, `scene/stl/`):

| Точка входа | Сигнатура | Примечания |
|---|---|---|
| `GltfParser.parse` | `(bytes, defaultName, basePathResolver?) → GltfModel` | GLB и JSON `.gltf`, встроенные буферы `data:`, внешние `.bin` через resolver |
| `GltfExporter.toGlb` | `(model) → ByteArray` | Проверка round-trip: повторный разбор результата должен воспроизвести структуру |
| `GltfExporter.toObj` | `(model, matrix?, name) → String` | Потероносный вспомогательный формат; запекает матрицу размещения в вершины |
| `StlParser.parse` | `(bytes, defaultName) → GltfModel` | Бинарный (правило размера `84 + 50n`) и ASCII; одна mesh, плоские нормали вычисляются при загрузке |
| `Animator` | для каждой модели: `advance(dt)`, `sample() → Map<nodeIdx, localMatrix>` | LINEAR/STEP/CUBICSPLINE, nlerp кватернионов, зацикливание по длительности клипа |
| `Mat4` | `identity/mul/inverse/boneMatrix` | Порядок столбцов (column-major); `inverse` — методом Гаусса–Жордана, возвращает `null` на вырожденной матрице |
| `GltfParser.trsMatrix` | `(t, r, s) → FloatArray(16)` | Общая трансформация TRS → матрица |

**Ключевые типы данных** (все `data class`, все практически неизменяемые):

* `GltfModel` — `primitives: Map<meshIdx, List<GltfPrimitive>>`, `nodes`,
  `sceneRootNodes`, `boundsMin/Max`, `materials`, `textures`,
  `animations: List<GltfAnimation>`, `skins: List<GltfSkin>`
* `GltfPrimitive` — `positions/normals/uvs/tangents`, `indices`,
  `material`, плюс атрибуты скиннинга `joints: IntArray?` (4 на вершину)
  и `weights: FloatArray?` (4 на вершину, нормализованы)
* `GltfNode` — `matrix` **плюс** базовые `translation/rotation/scale` (TRS
  должен пережить разбор: каналы анимации переопределяют его компоненты)
* `GltfSkin` — `joints: IntArray` (индексы узлов), `inverseBindMatrices`
  (16 float на сустав), `jointCount`
* `GltfAnimation` / `GltfChannel` / `GltfSampler` — клип, канал
  (узел + путь), ключевые кадры (времена, значения, интерполяция)

**Геометрия сцены/панелей** (`scene/Scene.kt`):

* `Vec3` — минимальная векторная алгебра (dot/cross/length/normalized)
* `SpatialPanel` — id, размер в метрах, позиция, подпись, `widgets`,
  флаг `persistent`; `normal` и `basis()` для рендера
* `LayoutEngine` — упорядоченная карта `panels`; `add`,
  `defaultArc(count=3, radiusM=2.5, arcDeg=60)`, `move(id, to)`,
  `hitTest(dir, maxDistance=20)` → `PanelHit(panel, distance, u, v)`
* `hitBox(origin, dir, min, max) → Float?` — slab-тест, применяемый для
  выбора 3D-моделей под прицелом

**Виджеты** (`scene/Widgets.kt`): `sealed class Widget` с `Button`,
`Toggle`, `Slider`, `Label`; `UvRect` для определения попадания внутри
текстуры панели.

### Инварианты

* **Round-trip**: `GltfExporter.toGlb(GltfParser.parse(x))` должен
  повторно разобраться в ту же структуру (геометрия бит-в-бит,
  изображения байт-в-байт). Страхуется тестом `GltfRoundTripTest`.
* **Сохранение TRS**: узлы хранят базовый TRS, хотя рендерер потребляет
  запеченную матрицу — от этого зависят анимация и экспорт.
* **Без `android.*`**: в тот момент, когда здесь появится импорт
  Android, JVM-тесты умрут, вместе с ними — и CI.
* Веса нормализуются во время разбора (u8 → /255, u16 → /65535);
  рендерер может полагать `Σw = 1` на каждую вершину.

### Как расширять

Новый формат файла? Добавьте `scene/<fmt>/XParser.kt`, порождающий
`GltfModel` — весь остальной стек (рендерер, физика, экспортёр) продолжит
работать без изменений. Новая интерполяция анимации? Расширьте `Animator` +
`GltfSampler.interpolation` и добавьте тест в `AnimationTest`.

---

## 3. `:core-render` — рендер на Filament

**Назначение.** Владеет потоком рендера, движком/сценой Filament,
ресурсами GPU для моделей и панелей, материалами, IBL и видеосферой.
Приложение никогда не трогает Filament напрямую — оно отправляет запросы.

### Публичный API

**`FilamentRenderer(surfaceView)`** — `SurfaceHolder.Callback`; единый
фасад, с которым общается приложение:

| Категория | Элементы |
|---|---|
| Камера | `poseSupplier: (() -> FloatArray)?` — кватернион от активного моста |
| Жизненный цикл | `startThread()`, `stopThread()`, `onSceneReady: (() -> Unit)?` (колбэк главного потока, вызываемый после каждой сборки/пересборки сцены) |
| Панели | `setPanels(specs: List<PanelSpec>)`, `updatePanelUi(id, w, h, pixels)`, `setFocusedPanel(id?)` |
| Модели | `requestModel(id, GltfModel, matrix)`, `setModelTransform(id, matrix)`, `requestRemoveModel(id)` |
| Анимация | `requestAnimationToggle()`, `requestNextAnimationClip()`, `animationStatus: String` (снимок для HUD) |
| Видео | `requestVideoSphere(onReady: (Surface) -> Unit)`, `requestColorSphere(onDone)` |
| Метрики | `targetFps`, `measuredFps`, колбэк `onStats` |

`PanelSpec(id, widthM, heightM, x, y, z, persistent)` — плоский объект
значений; панели **не** являются `SpatialPanel` (этот тип принадлежит
core-scene).

**`ModelManager`** (только поток рендера) — жизненный цикл GPU объектов
сцены:

* `load(id, model, matrix)`, `setMatrix(id, matrix)`, `release(id)`,
  `releaseAll()`, `shutdown()`
* `tickAnimations(nowNs)` — продвигает каждый `Animator`, записывает
  преобразования узлов (dt ограничен 250 мс, чтобы остановка не могла
  рвануть ключевые кадры)
* `updateBones()` — для каждого скиннинг-рендерабла вычисляет
  `bone[j] = inverse(meshWorld) · jointWorld[j] · IBM[j]` (точная
  формула из gltfio) и передаёт их в `RenderableManager.setBonesAsMatrices`
* `toggleAnimations()`, `nextAnimationClip()`, `hudStatus()`
* `models: LinkedHashMap<String, LoadedModel>` — набор объектов песочницы

**`MaterialFactory`** (object) — компиляция во время выполнения через
filamat, поэтому сборке не нужны ни `matc`, ни прекомпилированные ассеты
`.filamat`: `buildColorUnlit`, `buildLit` (PBR: baseColor/MR/normal/
emissive/AO, разделение сэмплирования sRGB и linear), `buildPanelUi`,
`buildVideoSphere`.

**`IblBuilder(engine)`** — процедурный equirect → префильтрованный на
GPU `reflections`, SH `irradianceCube`, `indirectLight`;
`build()`/`release()`.

**`Meshes`** — `createSphere(radius=30f)`, `createQuad()`, `allocBytes`.

**`PanelUiDrawer.draw(panel, bitmap)`** — растеризует `SpatialPanel`
(заголовок + виджеты) в Bitmap, лежащий в основе его текстуры Filament.

### Инварианты

* **Владение потоком рендера**: ко всему в `ModelManager` и ко всем
  объектам Filament прикасается только поток рендера. Межпоточные
  запросы идут через volatile-каналы ожидания (см. `ARCHITECTURE.md`,
  раздел «Каналы ожидания»).
* **Парность ресурсов**: у каждого `create*` есть соответствующее
  уничтожение в `release()/shutdown()/destroySceneState()`; экран настроек
  первого запуска уничтожает и пересобирает сцену целиком, поэтому ничто
  не может быть одноразовым без повторной отправки через `onSceneReady`.
* **Точные размеры буферов**: Filament сверяет `ByteBuffer.remaining()`
  с `vertexCount × attributeSize` и при несовпадении молча прерывает
  отрисовку (по этой причине появились оба бага — «невидимая модель»
  и «веер из треугольников» — см. раздел об ошибках в README).
* Материалы держат `variantFilter` пустым — отфильтровать `skinning`
  означало бы аварийное завершение каждого скиннинг-рендерабла.

### Как расширять

Новая визуальная функция → решите, каким каналом она пойдёт (панели:
поля ожидания; модели: очередь `ModelOp`), реализуйте половину в потоке
рендера в `ModelManager` или `FilamentRenderer` и выставьте приложению
только метод-запрос. Никогда не выставляйте типы Filament за пределы
границы модуля.

---

## 4. `:core-physics` — симуляция твёрдых тел

**Назначение.** Обёртка над Jolt для песочницы: гравитация, статический
пол, один динамический бокс на каждый объект сцены и пружинное
перетаскивание, применяемое хватом с броском.

### Публичный API — `PhysicsWorld`

* Жизненный цикл: `ensureInit()` (идемпотентный), `isInitialized`
* Тела: `addFloor(topY, centerX, centerZ)`,
  `addModelBox(id, px,py,pz, hx,hy,hz)` (заменяет тело с тем же id),
  `removeBody(id)`, `removeAllBodies()`, `hasBody(id)`, `bodyCount`
* Запросы: `bodyPosition(id)`, `bodyRotation(id)` (кватернион xyzw)
* Симуляция: `step(dt)` — один фиксированный шаг (вызывающая сторона
  использует `1/60 s`)
* Хват: `grab(id)`, `dragTo(x,y,z)`, `releaseGrab()`, `isGrabbing`
  — критически затухающая пружина (`DRAG_KP = 88.8`, `DRAG_KD = 17.0`)
  тянет тело к цели; при отпускании скорость сохраняется (бросок)

### Инварианты

* Только два слоя объектов: `LAYER_MOVING` (динамические) и
  `LAYER_STATIC` (пол); движущиеся сталкиваются с движущимися и со
  статикой, статика со статикой — нет.
* Ёмкость: `MAX_BODIES = 5000`, `MAX_BODY_PAIRS = 65536`,
  `MAX_CONTACTS = 20480`.
* `step()` должен оставаться дешёвым и почти без аллокаций: он
  выполняется в главном потоке на 60 Гц внутри `physicsTick`.
* Верх пола — это общий для рендера контракт мира:
  `FLOOR_TOP_Y = -1.0` (объявлен в `MainActivity`, передаётся в
  `addFloor`), а slab-плоскость пола с тенью в `core-render`
  расположена на том же Y.

### Как расширять

Новые формы тел → добавьте метод `add*` рядом с `addModelBox`.
Игровые запросы (raycast, события столкновений) тоже принадлежат сюда —
им нужен доступ к Jolt, но ничего от рендерера. Держите типы Jolt
приватными для модуля (приложение видит только id и массивы float).

---

## 5. `:core-video` — воспроизведение

**Назначение.** Тонкая обёртка над ExoPlayer с упором на задержку для
360°-сферы: рендерер передаёт ей `Surface`, источниками управляет
приложение.

### Публичный API — `StreamPlayer(context)`

* `prepare(url, lowLatency = true)`, `setSurface(surface?)`
* `pause()`, `resume()`, `seekTo(ms)`, `setLooping(on)`, `release()`
* Состояние: `playing`, `positionMs`, `durationMs`
* Колбэки: `onError: ((PlaybackException) -> Unit)?`,
  `onFirstFrame: (() -> Unit)?`

### Инварианты

* Плеер никогда не владеет Surface — владеет рендерер. После пересборки
  сцены приложение должно снова вызвать `setSurface` **и** `prepare`
  (один только `setSurface` оставляет ExoPlayer в `ERROR_CODE_DECODING_FAILED`;
  см. в README раздел «Storage & scene rebuilds»).
* `onFirstFrame` — единственный источник истины о том, что «видео
  транслируется», в HUD.

### Как расширять

Новые виды источников — это просто URL, передаваемые в `prepare`
(HLS/RTSP/файл). Аудиофункции (пространственная панорама) расширяют
этот модуль — это единственное место, где разрешён Media3.

---

## 6. `:core-bridge` — контракты и реализации очков

**Назначение.** Всё, что общается с оборудованием вне корпуса: протоколы
позы, управление дисплеем и математика, общая для них. Остальная часть
приложения зависит только от контрактов, поэтому новая модель очков
подключается без изменений в render/input.

### Контракты

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

`UnavailableDisplay` — реализация «null-объект», используемая, когда у
моста нет протокола управления дисплеем.

### Реализации

| Класс | Оборудование | Примечания |
|---|---|---|
| `RokidMaxBridge` | Rokid Max, сырой HID `04d2:162f` | Только 3DoF (у обычного Max нет камер). `poseCount`/`droppedPackets` для HUD. `UXR_ENTRY_CANDIDATES` перечисляет классы UXR SDK для поиска на будущем пути 6DoF Max Pro — все ссылки на SDK остаются внутри этого файла |
| `VitureHidBridge` | Viture (порт VertoXR Web's ar-bridge) | Gen2 `0x0301`/`0x7308`, Gen1 Euler@18; `poseCount`, `rawCount` |
| `EmulatorBridge` | без оборудования | взгляд касанием/мышью (перетаскивание мыши запекается в ориентацию) или гироскоп телефона (`useDeviceSensors`); `look(dy, dp)` |

### Вспомогательные типы

* `MadgwickRokid(beta = 0.045)` — фильтр AHRS, сводящий гироскоп и
  акселерометр Max в кватернион (`update(gx,gy,gz,ax,ay,az,dt)`,
  `cameraQuat()`, `initFromAccel`). Чистая математика, в принципе
  тестируемая юнит-тестами.
* `QuaternionPoseProvider` — `HeadPoseProvider`, питаемый через
  `setOrientation(rawGl)` (используется путём эмулятора).
* `Quat` (в `Types.kt`) — `fromEulerYXZ`, `mul`, `rotate`, `forward`,
  `toEulerYXZ`, `normalize`, `conjugate`; общий для мостов и input.
* `UsbDiagnostics.describe(usbManager)` — однократный дамп шины USB
  для отладки через logcat.

### Инварианты

* **Никаких типов UXR/SDK вне `RokidMaxBridge`** — у обычного Max нет
  камер; изоляция ссылок на SDK означает, что проект собирается на любом
  устройстве без проприетарного SDK.
* `Quat` и `HeadPose` — lingua franca: поза, передаваемая рендереру,
  всегда кватернион `float[4]` (x,y,z,w).
* Мосты создаются заново при каждом `connectChain()` — никакого
  статического состояния, никаких утечших дескрипторов USB (сначала
  всегда `disconnect()`).

### Как расширять

Реализуйте `GlassesBridge` (+ `HeadPoseProvider`) для нового
оборудования, добавьте его в `MainActivity.connectChain()` в порядке
приоритета и зарегистрируйте его USB VID в документации по диагностике.
Вот и весь интерфейс интеграции.

---

## 7. `:core-input` — события → жесты

**Назначение.** Разделение на производителя и потребителя, которое
делает ввод тестируемым: на входе сырые события, на выходе семантические
жесты, плюс курсор-луч 3DoF.

### Публичный API

* `sealed class InputEvent` — `Drag(dxPx, dyPx)`, `Tap`, `DoubleTap`,
  `DragEnd`, `Back`, `Key(keyCode)`
* `enum class Gesture` — `SELECT`, `RECENTER`, `SWIPE_LEFT`,
  `SWIPE_RIGHT`, `TOGGLE_HUD`, `BACK`
* `GestureRecognizer(swipeThresholdPx = 120f).onEvent(e) → Gesture?` —
  здесь живут пороги и решение гонки «тап против перетаскивания»
  (завершённое перетаскивание не должно поглощать следующий клик — для
  этого существует `DragEnd`)
* `RayCursor(layout).update(pose: HeadPose) → PanelHit?` — луч 3DoF
  сквозь кадр сессии, побеждает ближайшая панель
* `NavigationState` — `focusedPanelId`, `selectedPanelId`, `hudVisible`

Также распознаватель сопоставляет: DPAD/L1/R1 → свайпы, ENTER/BUTTON_A →
select, `R` → перецентровка, `H` → переключение HUD.

### Инварианты

* Распознаватели должны оставаться **бессостоятельными относительно
  сцены**: они переводят события, но никогда не изменяют панели или
  модели. Именно это позволяет позже сделать отслеживание рук или
  геймпад новым производителем `InputEvent`, не трогая логику жестов.
* Каждая последовательность `Drag` заканчивается `DragEnd` (или `Tap`,
  который её поглощает) — иначе следующий клик будет классифицирован
  неверно.

### Как расширять

Новое устройство ввода → генерируйте `InputEvent`; новое взаимодействие
→ добавьте `Gesture` и ветку в `onEvent`. Держите пиксельные пороги
настраиваемыми (для этого существует параметр конструктора).

---

## 8. `:core-battery` — тепло и питание

**Назначение.** Держать устройство в рамках теплового бюджета и
сообщать, во что обошлась сессия.

### Публичный API

* `ThermalGovernor(context)` — `budget: StateFlow<Budget(targetFps,
  resolutionScale, thermalStatus)>`, `start()/stop()`,
  `frameIntervalNanos()` (цикл рендера задаёт темп кадров по этому
  значению)
* `PowerReport(context)` — `stats: StateFlow<Stats(elapsedSeconds,
  mahUsed, drainMa, batteryPercent)>`, `start()`, `sample()`

### Инварианты

* Регулятор **предлагает**, но никогда не принуждает: его потребляет
  только `targetFps` цикла рендера. Ошибка здесь должна деградировать
  до падения FPS, но не до аварийного завершения.
* Числа по энергии — оценки (дельты battery-stats); это телеметрия для
  HUD, а не измерения, на которые можно полагаться при инженерных
  решениях.

### Как расширять

Новая тепловая политика → измените способ вычисления `Budget.targetFps`.
Новая телеметрия → добавьте поля в `Stats` (HUD уже их отрисовывает).

---

## 9. `:app` — MainActivity (слой связывания)

**Назначение.** Единственный модуль, которому разрешено знать об UI
Android, разрешениях USB, сканировании файлов и *одновременно* обо всех
core-модулях. Он владеет **политикой** (какой мост первым, какой источник
следующим, что означает клавиша), модули владеют **механизмом**.

### Что здесь лежит сегодня (~1580 строк)

| Область | Элементы |
|---|---|
| Объекты сцены | `ModelObject(id, label, bounds, pos, yaw/pitch, scale)`, `sceneModels`, `selectedModelId`, `pickModelAtCrosshair()`, `spawnClone()`, `deleteSelected()` |
| Источники моделей | `loadCurrentModel()`, `nextModelSource()`, `scanLocalModelFiles()` (`.glb/.gltf/.stl` из `LOCAL_MODEL_DIRS`), `exportCurrentModel()` |
| Видео | `toggleVideo()`, `startVideo()/stopVideo()`, `stepVideoSource()`, `videoPanelWidgets()`, `PUBLIC_SOURCES` (6 встроенных потоков), `scanLocalVideos()` |
| Физика | `physicsTick` (60 Гц), `togglePhysics()`, `addPhysicsBoxFor()`, связывание хвата в `tryGrabAtCrosshair()`/`dragGrabbedModel()` |
| Панели | `panelSpecs()`, `pushPanelUi()`, `handleWidget()`, `reapplySceneContent()` (повторная отправка по `onSceneReady`) |
| Ввод | `wireTouch()`, `onKeyDown()`, `handleGesture()`, `selectAtCrosshair()`, `cycleFocus()` |
| HUD | `hudTick` (1 Гц), `updateHud()` |
| Мост | `connectChain()` — Rokid → Viture → запасной эмулятор |
| Android | приёмник подключения USB, разрешения «все файлы»/хранилища, режим погружения |

### Назначение клавиш (для справки; полная таблица в README)

`A` — пауза/воспроизведение анимации · `B` — клон · `DEL` — удалить ·
`E` — экспорт · `G` — следующий клип анимации · `M` — источник модели ·
`N` — источник видео · `P` — физика · `V` — видеосфера · `H` — HUD ·
`R` — перецентровка · колесо = масштаб выбранного / следующий источник
видео · стрелки = фокус

### Инварианты

* Здесь не появляются типы **Filament, Jolt и Media3** — только методы-
  запросы и простые данные. Если вы импортируете один из них, логика
  принадлежит модулю-владельцу.
* Все изменения сцены, запрашиваемые из потока UI, идут через каналы
  рендерера; никогда не изменяйте состояние потока рендера напрямую.
* Обработка клавиш имеет фиксированный порядок: функциональные клавиши
  (`V/N/M/P/E/B/DEL/A/G`) возвращаются раньше; всё остальное доходит до
  распознавателя жестов — новая функциональная клавиша не должна
  затенять `H`/`R`/DPAD.

### Как расширять (рецепт «функции»)

1. Механизм в модуле `core-*`-владельце (с тестами, если это чистый
   Kotlin).
2. Пара методов-запросов на `FilamentRenderer`/фасаде модуля, если
   нужен поток рендера.
3. Одна ветка `when` в `onKeyDown` (или жест) + одна строка в HUD.
4. Опишите это в `CAPABILITIES.md` и в таблице управления README.

---

## 10. Правила проектирования нового модуля

Чек-лист, выведенный из того, что уже работает в этой кодовой базе:

1. **Называйте его `core-<domain>`** и добавляйте в
   `settings.gradle.kts`.
2. **Объявляйте самые узкие зависимости.** Соседние модули — только
   там, где это допускает схема выше; никогда `:app`.
3. **Выберите поток до написания кода.** Состояние потока рендера
   остаётся за методом-запросом; состояние главного потока (физический
   тик, HUD) — в `MainActivity` или его собственном обработчике; всё
   остальное требует явного контракта (см. `ARCHITECTURE.md`).
4. **Пересекайте потоки только с неизменяемыми данными** — volatile-
   поля со значениями-объектами или очередь из маленьких операций
   (`ModelOp` — шаблон). Никаких общих изменяемых объектов и блокировок
   между модулями.
5. **Выставляйте контракты, а не реализации** — паттерн
   `GlassesBridge`: потребитель зависит от интерфейса, определённого
   рядом с его типами данных (`HeadPose`, `BridgeState`).
6. **Чистая логика → `core-scene` (или тестируемая на JVM часть нового
   модуля)**, чтобы её покрывал CI. Код, работающий только на
   устройстве, получает вместо этого лог-диагностику (одна строка на
   переход состояния, стабильный тег).
7. **У каждого create есть destroy**, а у каждой одноразовой отправки —
   путь повторной отправки через `onSceneReady` — экран настроек первого
   запуска *обязательно* пересоберёт сцену.
8. **Проверяйте точные контракты размеров буферов** с Filament (или
   любым чужим движком): валидируйте размеры сами, иначе режим отказа —
   это молча искажённая картинка, а не исключение.
9. **Документируйте в трёх местах**: этот файл (модули),
   `CAPABILITIES.md` (демо), таблица управления README (клавиши для
   пользователя).
