package feather.link

import android.bluetooth.BluetoothManager
import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import feather.core.BoardCalibration
import feather.core.CutJob
import feather.core.FeatherDocument
import feather.core.FeatherFile
import feather.core.FeatherFileException
import feather.core.FileKind
import feather.core.FileSniffer
import feather.core.GCodeGenerator
import feather.core.GcodeAnalysis
import feather.core.GcodeStats
import feather.core.Geometry
import feather.core.ImageTracer
import feather.core.JobSettings
import feather.core.MachineLimitException
import feather.core.Measurement
import feather.core.Micrometers
import feather.core.PointUm
import feather.core.Shape
import feather.core.ShapeOps
import feather.core.Viewport
import feather.core.snapToGrid
import feather.model.Axis
import feather.model.ConnectionType
import feather.model.Device
import feather.model.DeviceCollection
import feather.model.DeviceRepository
import feather.model.DeviceStore
import feather.model.GcodeDialect
import feather.model.MachineCommands
import feather.model.Presets
import feather.model.ProfileParams
import feather.model.ToolHead
import feather.model.ToolParams
import feather.model.toMachineContext
import feather.interior.PlanExport
import feather.model.MaterialLibrary
import feather.model.MaterialPreset
import feather.model.MaterialStore
import feather.model.QrPayload
import java.util.UUID
import feather.studio.InteriorModel
import feather.studio.PhotoEditor
import android.graphics.Bitmap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import feather.core.HeightMap
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.io.IOException

/** What the transport selector offers. */
enum class LinkMode { BLUETOOTH, WIFI, SD_CARD }

enum class JobState { IDLE, RUNNING, PAUSED, DONE, ERROR }

/**
 * Drawing tools. PAN moves the board with one finger; SELECT picks a shape (drag to move it);
 * every tool zooms/pans with two fingers.
 */
enum class Tool { PAN, SELECT, FREEHAND, LINE, RECT, CIRCLE, MEASURE }

/** Where a job goes. */
sealed interface LinkTarget {
    data class Ble(val address: String) : LinkTarget
    data class Wifi(val host: String, val port: Int) : LinkTarget
    /** Export a .gcode file to a location the user picked (copy it to the SD card). */
    data class Export(val uri: Uri) : LinkTarget
}

data class BleDevice(val address: String, val name: String?, val rssi: Int)

data class FeatherUiState(
    val shapes: List<Shape> = emptyList(),
    val draft: Shape? = null,
    val measurement: Measurement? = null,
    val cursor: PointUm? = null,
    val viewport: Viewport = Viewport(),
    val calibration: BoardCalibration = BoardCalibration(10.0),
    val tool: Tool = Tool.FREEHAND,
    val showGrid: Boolean = true,
    val snapToGrid: Boolean = false,
    val settings: JobSettings = JobSettings(),
    val canUndo: Boolean = false,
    val canRedo: Boolean = false,
    val documentUri: Uri? = null,
    val documentName: String? = null,
    val linkMode: LinkMode = LinkMode.BLUETOOTH,
    val wifiHost: String = "192.168.4.1",
    val wifiPort: Int = 23,
    val bleAddress: String = "",
    val bleName: String? = null,
    val bleDevices: List<BleDevice> = emptyList(),
    val scanning: Boolean = false,
    val jobState: JobState = JobState.IDLE,
    val progress: Pair<Int, Int> = 0 to 0, // lines sent to total
    val statusMessage: String = "",
    val selection: Set<Int> = emptySet(),
    val devices: DeviceCollection = DeviceCollection(),
    val stock: StockDims? = null,
    val trace: TraceState? = null,
    val jobReport: JobReport? = null,
    val simulateRequested: Boolean = false,
    val projects: List<ProjectFile> = emptyList(),
    val fullscreen: Boolean = false,
    val heightMap: HeightMap? = null,
    val useHeightMap: Boolean = false,
    val autofocus: AutofocusState = AutofocusState(),
)

/** Progress and result of the last (or running) multi-point Z probe. */
data class AutofocusState(
    val running: Boolean = false,
    val doneCount: Int = 0,
    val totalCount: Int = 0,
    val message: String = "",
)

/** Raw material on the bed, in millimetres. Drawn as a dashed outline on the board. */
data class StockDims(val widthMm: Double, val heightMm: Double, val thicknessMm: Double)

data class ProjectFile(val name: String, val path: String, val modifiedMs: Long, val sizeBytes: Long)

/** The photo-to-vector wizard: settings the operator tunes plus the outlines they currently produce. */
data class TraceState(
    val loading: Boolean = true,
    val working: Boolean = false,
    val imageWidthPx: Int = 0,
    val imageHeightPx: Int = 0,
    val threshold: Int = 127,
    val autoThreshold: Int = 127,
    val invert: Boolean = false,
    val widthMm: Double = 100.0,
    val simplifyUm: Long = 100L,
    /** Loops with fewer raw vertices than this are treated as dust. */
    val despeckle: Int = 12,
    /** Outlines at the chosen size, with the picture's bottom-left corner at (0, 0). */
    val preview: List<Shape.Polyline> = emptyList(),
) {
    val heightMm: Double get() = if (imageWidthPx <= 0) 0.0 else widthMm * imageHeightPx / imageWidthPx
    val pointCount: Int get() = preview.sumOf { it.points.size }
}

/** Everything the Toolpath stage knows about the job that would run right now. */
data class JobReport(
    val source: String,
    val gcode: String,
    val stats: GcodeStats,
    val problems: List<String>,
)

/**
 * Holds the document (shapes + job settings), the viewport, and drives
 * whichever [MachineLink] is selected. All state mutations that touch history
 * happen on the main thread; job callbacks only update job-related fields.
 */
@OptIn(FlowPreview::class)
class FeatherViewModel(
    private val appContext: Context,
    defaultPixelsPerMillimeter: Double = 10.0,
) : ViewModel() {

    private companion object {
        const val PREFS = "feather_prefs"
        const val KEY_PX_PER_MM = "px_per_mm"
        const val KEY_LINK_MODE = "link_mode"
        const val KEY_WIFI_HOST = "wifi_host"
        const val KEY_WIFI_PORT = "wifi_port"
        const val KEY_BLE_ADDRESS = "ble_address"
        const val KEY_BLE_NAME = "ble_name"
        const val KEY_PRESETS_SEEDED = "presets_seeded"
        const val KEY_STOCK = "stock"
        const val KEY_FULLSCREEN = "fullscreen"
        const val TRACE_MAX_SIDE = 480
        /** Macro mode: for a close-up photo of a small object, keep more detail instead of downsampling to 480px. */
        const val TRACE_MAX_SIDE_MACRO = 960
        const val HIT_RADIUS_PX = 14.0
        val MAC_REGEX = Regex("^([0-9A-Fa-f]{2}:){5}[0-9A-Fa-f]{2}$")
        const val MAX_HISTORY = 100
        const val SIMPLIFY_TOLERANCE_UM = 50L
        const val AUTOSAVE_DEBOUNCE_MS = 750L
        const val CONNECT_TIMEOUT_MS = 20_000L
        const val SCAN_MS = 10_000L
        const val MAX_COORD_MM = 100_000.0
        const val VIEW_MARGIN_PX = 24.0
    }

    private val prefs = appContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    private val autosaveFile = java.io.File(appContext.filesDir, "autosave.feather")

    private val _uiState = MutableStateFlow(
        FeatherUiState(
            calibration = BoardCalibration(
                prefs.getString(KEY_PX_PER_MM, null)?.toDoubleOrNull()?.takeIf { it.isFinite() && it > 0.0 }
                    ?: defaultPixelsPerMillimeter,
            ),
            linkMode = runCatching { LinkMode.valueOf(prefs.getString(KEY_LINK_MODE, "") ?: "") }
                .getOrDefault(LinkMode.BLUETOOTH),
            wifiHost = prefs.getString(KEY_WIFI_HOST, null) ?: "192.168.4.1",
            wifiPort = prefs.getInt(KEY_WIFI_PORT, 23),
            bleAddress = prefs.getString(KEY_BLE_ADDRESS, null) ?: "",
            bleName = prefs.getString(KEY_BLE_NAME, null),
        ),
    )
    val uiState: StateFlow<FeatherUiState> = _uiState.asStateFlow()

    private var referencedImages: List<String> = emptyList()
    private val undoStack = ArrayList<List<Shape>>()
    private val redoStack = ArrayList<List<Shape>>()

    private var canvasWidthPx = 0f
    private var canvasHeightPx = 0f
    private var viewPlaced = false
    private var fitPending = false

    private var dragStart: PointUm? = null
    private val dragPoints = ArrayList<PointUm>()

    private var activeLink: MachineLink? = null
    private var jobRun: Job? = null
    private var scanJob: Job? = null

    private val deviceRepo = DeviceRepository(DeviceStore(File(appContext.filesDir, "devices.json")))
    private val projectsDir = File(appContext.filesDir, "projects")

    /** The live connection shared by Machine control, the Tools screen and Start. */
    val session = MachineSession(viewModelScope)

    /** Photo editor and 3D interior designer: each keeps its own state, the drawing board only receives their output. */
    val photo = PhotoEditor(appContext, viewModelScope)
    val interior = InteriorModel(File(appContext.filesDir, "interior.json"), viewModelScope)

    /** Common materials (built-in) plus any the person has saved or scanned in. */
    val materials = MaterialLibrary(MaterialStore(File(appContext.filesDir, "materials.json")))

    private var moveOrigin: List<Shape>? = null
    private var moveStart: PointUm? = null

    @Volatile private var traceGray: GrayImage? = null
    @Volatile private var traceLoops: List<ImageTracer.Loop> = emptyList()
    private var traceJob: Job? = null

    private val autosaveRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        viewModelScope.launch {
            autosaveRequests.debounce(AUTOSAVE_DEBOUNCE_MS).collect {
                withContext(Dispatchers.IO) {
                    try { FeatherFile.save(autosaveFile, snapshotDocument()) } catch (e: Exception) { /* best effort */ }
                }
            }
        }
        viewModelScope.launch {
            if (autosaveFile.exists()) {
                try {
                    val doc = withContext(Dispatchers.IO) { FeatherFile.load(autosaveFile) }
                    applyDocument(doc, null, null)
                    report("Restored your last session")
                } catch (e: Exception) {
                    if (e is CancellationException) throw e
                }
            }
        }
        if (!prefs.getBoolean(KEY_PRESETS_SEEDED, false)) {
            if (deviceRepo.state.value.devices.isEmpty()) Presets.starter().forEach { deviceRepo.addDevice(it) }
            prefs.edit().putBoolean(KEY_PRESETS_SEEDED, true).apply()
        }
        _uiState.update {
            it.copy(
                devices = deviceRepo.state.value,
                stock = loadStock(),
                fullscreen = prefs.getBoolean(KEY_FULLSCREEN, false),
            )
        }
        applyDeviceToLink(deviceRepo.state.value.activeDevice)
        viewModelScope.launch { deviceRepo.state.collect { c -> _uiState.update { it.copy(devices = c) } } }
        refreshProjects()
    }

    private fun requestAutosave() { autosaveRequests.tryEmit(Unit) }

    fun report(message: String) { _uiState.update { it.copy(statusMessage = message) } }

    // ---- Viewport ---------------------------------------------------------------

    fun onCanvasSize(width: Int, height: Int) {
        canvasWidthPx = width.toFloat()
        canvasHeightPx = height.toFloat()
        if (!viewPlaced && width > 0 && height > 0) {
            viewPlaced = true
            if (fitPending) fitToDrawing() else resetView()
        }
    }

    /** World (0,0) near the bottom-left corner at 100 % zoom. */
    fun resetView() {
        if (canvasHeightPx <= 0f) return
        _uiState.update {
            val s = it.calibration.pixelsPerMillimeterAt100Pct
            it.copy(
                viewport = Viewport(
                    zoom = 1.0,
                    originXUm = -Math.round(VIEW_MARGIN_PX / s * 1000.0),
                    originYUm = Math.round((canvasHeightPx - VIEW_MARGIN_PX) / s * 1000.0),
                ),
            )
        }
    }

    fun fitToDrawing() {
        if (canvasWidthPx <= 0f || canvasHeightPx <= 0f) { fitPending = true; return }
        fitPending = false
        val shapes = _uiState.value.shapes
        val b = Geometry.bounds(shapes)
        if (b == null) { resetView(); return }
        _uiState.update {
            val ppm = it.calibration.pixelsPerMillimeterAt100Pct
            val wMm = maxOf(b.width, 1L) / 1000.0
            val hMm = maxOf(b.height, 1L) / 1000.0
            val zoom = minOf(
                (canvasWidthPx - 2 * VIEW_MARGIN_PX) / (wMm * ppm),
                (canvasHeightPx - 2 * VIEW_MARGIN_PX) / (hMm * ppm),
            ).coerceIn(Viewport.MIN_ZOOM, Viewport.MAX_ZOOM)
            val scale = ppm * zoom
            val cx = (b.minX + b.maxX) / 2.0
            val cy = (b.minY + b.maxY) / 2.0
            it.copy(
                viewport = Viewport(
                    zoom = zoom,
                    originXUm = Math.round(cx - (canvasWidthPx / 2.0) / scale * 1000.0),
                    originYUm = Math.round(cy + (canvasHeightPx / 2.0) / scale * 1000.0),
                ),
            )
        }
    }

    fun zoomBy(factor: Double) {
        if (canvasWidthPx <= 0f) return
        _uiState.update {
            it.copy(viewport = it.viewport.zoomAbout(factor, canvasWidthPx / 2.0, canvasHeightPx / 2.0, it.calibration))
        }
    }

    /** Two-finger gesture (or one-finger with the Pan tool): zoom about the centroid, then pan. */
    fun onTransform(centroidX: Float, centroidY: Float, panX: Float, panY: Float, zoom: Float) {
        _uiState.update {
            it.copy(
                viewport = it.viewport
                    .zoomAbout(zoom.toDouble(), centroidX.toDouble(), centroidY.toDouble(), it.calibration)
                    .panByPx(panX.toDouble(), panY.toDouble(), it.calibration),
            )
        }
    }

    fun setTool(tool: Tool) { cancelDrag(); _uiState.update { it.copy(tool = tool) } }
    fun setFullscreen(on: Boolean) {
        prefs.edit().putBoolean(KEY_FULLSCREEN, on).apply()
        _uiState.update { it.copy(fullscreen = on) }
    }
    fun toggleGrid() { _uiState.update { it.copy(showGrid = !it.showGrid) } }
    fun toggleSnap() { _uiState.update { it.copy(snapToGrid = !it.snapToGrid) } }

    // ---- Drawing gestures ------------------------------------------------------

    private fun worldAt(x: Float, y: Float): PointUm {
        val s = _uiState.value
        val p = s.viewport.screenToWorld(x.toDouble(), y.toDouble(), s.calibration)
        if (!s.snapToGrid) return p
        val g = s.viewport.gridSpacingUm(s.calibration)
        return PointUm(snapToGrid(p.x, g), snapToGrid(p.y, g))
    }

    fun beginDrag(x: Float, y: Float) {
        if (_uiState.value.tool == Tool.PAN) return
        val p = worldAt(x, y)
        dragStart = p
        dragPoints.clear()
        dragPoints.add(p)
        if (_uiState.value.tool == Tool.SELECT) {
            val s = _uiState.value
            val tolUm = HIT_RADIUS_PX / s.viewport.pxPerMm(s.calibration) * 1000.0
            val hit = ShapeOps.hitTest(s.shapes, p, tolUm)
            if (hit >= 0) {
                moveOrigin = s.shapes
                moveStart = p
                _uiState.update { it.copy(cursor = p, draft = null, measurement = null, selection = if (hit in it.selection) it.selection else setOf(hit)) }
            } else {
                moveOrigin = null
                moveStart = null
                _uiState.update { it.copy(cursor = p, draft = null, measurement = null, selection = emptySet()) }
            }
            return
        }
        _uiState.update { it.copy(cursor = p, draft = null, measurement = null) }
    }

    fun dragTo(x: Float, y: Float) {
        val start = dragStart ?: return
        val p = worldAt(x, y)
        val tool = _uiState.value.tool
        if (tool == Tool.SELECT) { moveSelectionTo(p); return }
        if (tool == Tool.FREEHAND && !dragPoints.last().sameXY(p)) dragPoints.add(p)
        val points = if (tool == Tool.FREEHAND) dragPoints.toList() else emptyList()
        _uiState.update {
            when (tool) {
                Tool.FREEHAND -> it.copy(cursor = p, draft = Shape.Polyline(points))
                Tool.LINE -> it.copy(cursor = p, draft = Shape.Line(start, p))
                Tool.RECT -> it.copy(cursor = p, draft = rectFrom(start, p))
                Tool.CIRCLE -> it.copy(cursor = p, draft = Shape.Circle(start, Micrometers(Geometry.distanceUm(start, p))))
                Tool.MEASURE -> it.copy(cursor = p, measurement = Measurement(start, p))
                Tool.PAN, Tool.SELECT -> it
            }
        }
    }

    /** Live drag of the selected shapes; history is recorded once, in [endDrag]. */
    private fun moveSelectionTo(p: PointUm) {
        val origin = moveOrigin ?: return
        val start = moveStart ?: return
        val dx = p.x.raw - start.x.raw
        val dy = p.y.raw - start.y.raw
        _uiState.update { s ->
            val moved = try {
                origin.mapIndexed { i, sh -> if (i in s.selection) ShapeOps.translate(sh, dx, dy) else sh }
            } catch (e: ArithmeticException) {
                origin
            }
            s.copy(cursor = p, shapes = moved)
        }
    }

    fun endDrag() {
        val s = _uiState.value
        dragStart = null
        if (s.tool == Tool.SELECT) {
            val origin = moveOrigin
            moveOrigin = null; moveStart = null
            if (origin != null && origin != s.shapes) {
                undoStack.add(origin)
                if (undoStack.size > MAX_HISTORY) undoStack.removeAt(0)
                redoStack.clear()
                setShapes(s.shapes)
            }
            return
        }
        var shape = s.draft
        if (shape is Shape.Polyline) {
            val simplified = Geometry.simplify(shape.points, SIMPLIFY_TOLERANCE_UM)
            shape = if (simplified.size >= 2) Shape.Polyline(simplified) else null
        }
        _uiState.update { it.copy(draft = null) }
        if (s.tool != Tool.MEASURE && shape != null && !isDegenerate(shape)) commit(s.shapes + shape)
    }

    fun cancelDrag() {
        dragStart = null
        val origin = moveOrigin
        moveOrigin = null; moveStart = null
        _uiState.update { it.copy(draft = null, shapes = origin ?: it.shapes) }
    }

    private fun rectFrom(a: PointUm, b: PointUm): Shape.Rect {
        val x0 = minOf(a.x.raw, b.x.raw)
        val y0 = minOf(a.y.raw, b.y.raw)
        return Shape.Rect(
            PointUm(Micrometers(x0), Micrometers(y0)),
            Micrometers(maxOf(a.x.raw, b.x.raw) - x0),
            Micrometers(maxOf(a.y.raw, b.y.raw) - y0),
        )
    }

    private fun isDegenerate(s: Shape): Boolean = when (s) {
        is Shape.Line -> s.a.sameXY(s.b)
        is Shape.Rect -> s.widthUm.raw == 0L || s.heightUm.raw == 0L
        is Shape.Circle -> s.radiusUm.raw <= 0L
        is Shape.Polyline -> s.points.size < 2
    }

    // ---- Exact-dimension shapes --------------------------------------------------

    fun addRectangle(xMm: Double, yMm: Double, widthMm: Double, heightMm: Double): Boolean {
        if (!validMm(xMm, yMm) || !validMm(widthMm, heightMm) || widthMm <= 0.0 || heightMm <= 0.0) {
            report("Rectangle values are out of range"); return false
        }
        val origin = PointUm(Micrometers.fromMillimeters(xMm), Micrometers.fromMillimeters(yMm))
        commit(_uiState.value.shapes + Shape.Rect(origin, Micrometers.fromMillimeters(widthMm), Micrometers.fromMillimeters(heightMm)))
        return true
    }

    fun addCircle(centerXMm: Double, centerYMm: Double, diameterMm: Double): Boolean {
        if (!validMm(centerXMm, centerYMm) || !validMm(diameterMm, 0.0) || diameterMm <= 0.0) {
            report("Circle values are out of range"); return false
        }
        val c = PointUm(Micrometers.fromMillimeters(centerXMm), Micrometers.fromMillimeters(centerYMm))
        commit(_uiState.value.shapes + Shape.Circle(c, Micrometers(Math.round(diameterMm * 500.0))))
        return true
    }

    private fun validMm(a: Double, b: Double) =
        a.isFinite() && b.isFinite() && Math.abs(a) <= MAX_COORD_MM && Math.abs(b) <= MAX_COORD_MM

    // ---- History -------------------------------------------------------------------

    private fun commit(newShapes: List<Shape>) {
        undoStack.add(_uiState.value.shapes)
        if (undoStack.size > MAX_HISTORY) undoStack.removeAt(0)
        redoStack.clear()
        setShapes(newShapes)
    }

    private fun setShapes(shapes: List<Shape>) {
        _uiState.update {
            it.copy(
                shapes = shapes,
                canUndo = undoStack.isNotEmpty(),
                canRedo = redoStack.isNotEmpty(),
                selection = it.selection.filter { i -> i in shapes.indices }.toSet(),
                jobReport = null, // any edit invalidates the last toolpath report
            )
        }
        requestAutosave()
    }

    fun undo() {
        if (undoStack.isEmpty()) return
        redoStack.add(_uiState.value.shapes)
        _uiState.update { it.copy(selection = emptySet()) }
        setShapes(undoStack.removeAt(undoStack.size - 1))
    }

    fun redo() {
        if (redoStack.isEmpty()) return
        undoStack.add(_uiState.value.shapes)
        _uiState.update { it.copy(selection = emptySet()) }
        setShapes(redoStack.removeAt(redoStack.size - 1))
    }

    fun clearShapes() {
        if (_uiState.value.shapes.isNotEmpty()) commit(emptyList())
    }

    fun newDocument() {
        undoStack.clear(); redoStack.clear()
        referencedImages = emptyList()
        _uiState.update {
            it.copy(shapes = emptyList(), draft = null, measurement = null, canUndo = false, canRedo = false,
                selection = emptySet(), jobReport = null,
                documentUri = null, documentName = null, jobState = JobState.IDLE, progress = 0 to 0, statusMessage = "New drawing")
        }
        requestAutosave()
    }

    // ---- Calibration & settings --------------------------------------------------

    /**
     * The Measure tool showed a segment; the operator compared it with a real
     * ruler held against the screen and typed the true length. Corrects the
     * screen scale (shapes keep their µm dimensions; calibrate *before* drawing).
     */
    fun calibrateFromMeasurement(actualMm: Double): Boolean {
        val m = _uiState.value.measurement
        if (m == null || m.lengthUm <= 0L || !actualMm.isFinite() || actualMm <= 0.0) {
            report("Measure a segment first, then enter its real length"); return false
        }
        val corrected = _uiState.value.calibration.correctedBy(m.lengthUm / 1000.0, actualMm)
        prefs.edit().putString(KEY_PX_PER_MM, corrected.pixelsPerMillimeterAt100Pct.toString()).apply()
        _uiState.update { it.copy(calibration = corrected, statusMessage = "Screen scale calibrated") }
        return true
    }

    fun updateSettings(settings: JobSettings) {
        _uiState.update { it.copy(settings = settings) }
        requestAutosave()
    }

    // ---- Transport selection -------------------------------------------------------

    fun setLinkMode(mode: LinkMode) {
        prefs.edit().putString(KEY_LINK_MODE, mode.name).apply()
        _uiState.update { it.copy(linkMode = mode) }
        val connection = when (mode) {
            LinkMode.BLUETOOTH -> ConnectionType.BLE
            LinkMode.WIFI -> ConnectionType.WIFI
            LinkMode.SD_CARD -> ConnectionType.SD
        }
        rememberOnActiveDevice { it.copy(connection = connection, address = addressFor(connection)) }
    }

    fun setWifiHost(host: String) {
        prefs.edit().putString(KEY_WIFI_HOST, host).apply()
        _uiState.update { it.copy(wifiHost = host) }
        rememberOnActiveDevice { it.copy(connection = ConnectionType.WIFI, address = addressFor(ConnectionType.WIFI)) }
    }

    fun setWifiPort(port: Int) {
        prefs.edit().putInt(KEY_WIFI_PORT, port).apply()
        _uiState.update { it.copy(wifiPort = port) }
        rememberOnActiveDevice { it.copy(connection = ConnectionType.WIFI, address = addressFor(ConnectionType.WIFI)) }
    }

    fun selectBleDevice(device: BleDevice) {
        prefs.edit().putString(KEY_BLE_ADDRESS, device.address).putString(KEY_BLE_NAME, device.name).apply()
        _uiState.update { it.copy(bleAddress = device.address, bleName = device.name) }
        rememberOnActiveDevice { it.copy(connection = ConnectionType.BLE, address = device.address) }
        stopScan()
    }

    /** The address string a device stores for [connection], from the current link fields. */
    private fun addressFor(connection: ConnectionType): String {
        val s = _uiState.value
        return when (connection) {
            ConnectionType.BLE -> s.bleAddress
            ConnectionType.WIFI -> if (s.wifiPort == 23) s.wifiHost else "${s.wifiHost}:${s.wifiPort}"
            ConnectionType.USB, ConnectionType.SD -> ""
        }
    }

    /** Edits the active device in place, without re-applying it to the link fields (avoids fighting a text box). */
    private fun rememberOnActiveDevice(edit: (Device) -> Device) {
        val active = deviceRepo.state.value.activeDevice ?: return
        deviceRepo.updateDevice(edit(active))
    }

    private fun applyDeviceToLink(device: Device?) {
        if (device == null) return
        _uiState.update { s ->
            when (device.connection) {
                ConnectionType.BLE -> s.copy(
                    linkMode = LinkMode.BLUETOOTH,
                    bleAddress = if (MAC_REGEX.matches(device.address)) device.address else "",
                    bleName = device.name,
                )
                ConnectionType.WIFI -> {
                    val host = device.address.substringBeforeLast(':', device.address)
                    val port = device.address.substringAfterLast(':', "").toIntOrNull()?.takeIf { it in 1..65535 } ?: 23
                    s.copy(linkMode = LinkMode.WIFI, wifiHost = host, wifiPort = port)
                }
                ConnectionType.USB, ConnectionType.SD -> s.copy(linkMode = LinkMode.SD_CARD)
            }
        }
    }

    // ---- Devices, profiles, tools ---------------------------------------------------

    fun deviceById(id: String): Device? = _uiState.value.devices.devices.firstOrNull { it.id == id }

    fun selectDevice(id: String) {
        if (deviceRepo.state.value.activeDeviceId == id) return
        if (session.link != null) session.disconnect() // a different machine needs its own link
        deviceRepo.setActiveDevice(id)
        applyDeviceToLink(deviceRepo.state.value.activeDevice)
        _uiState.update { it.copy(jobReport = null, heightMap = null, useHeightMap = false, autofocus = AutofocusState()) }
    }

    fun toggleFavorite(id: String) {
        val d = deviceById(id) ?: return
        deviceRepo.updateDevice(d.copy(favorite = !d.favorite))
    }

    fun addDevice(device: Device) {
        deviceRepo.addDevice(device)
        selectDevice(device.id)
    }

    fun updateDevice(device: Device) {
        deviceRepo.updateDevice(device)
        if (deviceRepo.state.value.activeDeviceId == device.id) {
            applyDeviceToLink(device)
            _uiState.update { it.copy(jobReport = null) }
        }
    }

    fun removeDevice(id: String) {
        if (deviceRepo.state.value.activeDeviceId == id && session.link != null) session.disconnect()
        deviceRepo.removeDevice(id)
        applyDeviceToLink(deviceRepo.state.value.activeDevice)
    }

    fun setActiveTool(deviceId: String, toolId: String) {
        val d = deviceById(deviceId) ?: return
        updateDevice(d.withActiveTool(toolId))
    }

    fun saveTool(deviceId: String, tool: ToolHead) {
        val d = deviceById(deviceId) ?: return
        updateDevice(d.withTool(tool))
    }

    fun removeTool(deviceId: String, toolId: String) {
        val d = deviceById(deviceId) ?: return
        if (d.tools.size <= 1) { report("A machine needs at least one tool"); return }
        val remaining = d.tools.filterNot { it.id == toolId }
        updateDevice(d.copy(tools = remaining, activeToolId = if (d.activeToolId == toolId) remaining.first().id else d.activeToolId))
    }

    /** "Apply to": copy only the ticked [keys] from [source] onto another tool of the same kind. */
    fun copyToolSettings(source: ToolHead, targetDeviceId: String, targetToolId: String, keys: Set<String>) {
        val d = deviceById(targetDeviceId) ?: return
        val target = d.tools.firstOrNull { it.id == targetToolId } ?: return
        if (keys.isEmpty()) return
        saveTool(targetDeviceId, ToolParams.apply(source, target, keys))
        report("Copied ${keys.size} setting${if (keys.size == 1) "" else "s"} to ${target.name}")
    }

    fun copyProfileSettings(sourceDeviceId: String, targetDeviceId: String, keys: Set<String>) {
        val src = deviceById(sourceDeviceId) ?: return
        val dst = deviceById(targetDeviceId) ?: return
        if (keys.isEmpty()) return
        updateDevice(dst.copy(profile = ProfileParams.apply(src.profile, dst.profile, keys)))
        report("Copied ${keys.size} setting${if (keys.size == 1) "" else "s"} to ${dst.name}")
    }

    private fun dialect(): GcodeDialect = deviceRepo.state.value.activeDevice?.controller?.dialect ?: GcodeDialect.GRBL

    // ---- Materials and QR -------------------------------------------------------------------------

    /** Sets the active tool's cutting settings (and this job's feed/plunge/step) from [material]. */
    fun applyMaterial(material: MaterialPreset) {
        val device = deviceRepo.state.value.activeDevice
        val tool = device?.activeTool
        if (device == null || tool == null) { report("Choose a machine and tool first"); return }
        if (material.forLaser && tool !is feather.model.ToolHead.Laser) {
            report("${material.name} is a laser setting; the active tool is a ${feather.model.ToolParams.familyName(tool)}"); return
        }
        if (!material.forLaser && tool is feather.model.ToolHead.Laser) {
            report("${material.name} is a cutting-tool setting; the active tool is a Laser"); return
        }
        if (material.forLaser) {
            val updated = feather.model.ToolParams.withValue(
                feather.model.ToolParams.withValue(tool, "defaultPowerPercent", material.laserPowerPercent.toDouble()),
                "passes", material.laserPasses.toDouble(),
            )
            saveTool(device.id, updated)
            _uiState.update { it.copy(settings = it.settings.copy(feedRateMmPerMin = material.laserSpeedMmPerMin)) }
        } else {
            _uiState.update {
                it.copy(
                    settings = it.settings.copy(
                        feedRateMmPerMin = material.cutFeedMmPerMin,
                        plungeRateMmPerMin = material.plungeRateMmPerMin,
                        stepDownUm = Micrometers.fromMillimeters(material.stepDownMm),
                    ),
                )
            }
        }
        report("Applied ${material.name} ${fmtMm(material.thicknessMm)} mm to ${tool.name}")
    }

    fun addMaterial(material: MaterialPreset) = materials.add(material)
    fun removeMaterial(id: String) = materials.remove(id)

    /** QR text for [deviceId]'s active tool (defaults to the active device), to show for another phone to scan. */
    fun deviceQrText(deviceId: String? = null): String? {
        val device = (deviceId?.let { id -> deviceRepo.state.value.devices.firstOrNull { it.id == id } }) ?: deviceRepo.state.value.activeDevice ?: return null
        val tool = device.activeTool ?: return null
        return QrPayload.encodeDevice(device.name, device.profile.machineType, device.controller, device.profile, tool)
    }

    fun materialQrText(material: MaterialPreset): String = QrPayload.encodeMaterial(material)

    /** Handles whatever a scanned QR turns out to be. Returns a message to show either way. */
    fun handleScannedQr(text: String): String = when (QrPayload.kindOf(text)) {
        QrPayload.Kind.MATERIAL -> {
            val id = "material-" + UUID.randomUUID().toString().take(8)
            val m = QrPayload.decodeMaterial(text, id)
            if (m == null) "That QR code's material data could not be read." else { materials.add(m); "Added material: ${m.name} ${fmtMm(m.thicknessMm)} mm" }
        }
        QrPayload.Kind.DEVICE -> {
            val imp = QrPayload.decodeDevice(text)
            if (imp == null) "That QR code's machine data could not be read." else {
                val device = feather.model.Presets.fromImport(imp, feather.model.ConnectionType.BLE)
                addDevice(device)
                "Added machine: ${device.name}. Pair it in Machine > Find machine."
            }
        }
        QrPayload.Kind.UNKNOWN -> "That QR code isn't a Rounga material or machine code."
    }

    private fun fmtMm(v: Double) = if (v == Math.rint(v)) v.toLong().toString() else v.toString()


    // ---- Live machine session ---------------------------------------------------------

    fun connectMachine() {
        val s = _uiState.value
        if (s.jobState == JobState.RUNNING || s.jobState == JobState.PAUSED) return
        val target = currentLiveTarget()
        if (target == null) { report("Live control needs Bluetooth or Wi-Fi. File / SD only exports G-code."); return }
        val label = s.devices.activeDevice?.name ?: "machine"
        viewModelScope.launch {
            val link = try {
                buildLink(target)
            } catch (e: MachineLinkException) {
                report(e.message ?: "Could not open the link"); return@launch
            }
            session.connect(link, label)?.let { report(it) }
        }
    }

    fun disconnectMachine() { session.disconnect() }

    /** Sends [gcode] on the live link; problems show in the status line and the console. */
    fun sendCommand(gcode: String) {
        viewModelScope.launch { session.send(gcode)?.let { if (it != "Stopped") report(it) } }
    }

    fun homeMachine() = sendCommand(MachineCommands.home(dialect()))
    fun unlockMachine() = sendCommand(MachineCommands.unlock(dialect()))
    fun setOriginHere(includeZ: Boolean) = sendCommand(MachineCommands.setOrigin(dialect(), includeZ))
    fun goToOrigin() = sendCommand(MachineCommands.goToOrigin())
    fun jog(dxMm: Double, dyMm: Double, dzMm: Double, feed: Double) = sendCommand(MachineCommands.jog(dialect(), dxMm, dyMm, dzMm, feed))

    /** Probe Z down onto a plate of known thickness, then declare that height as Z. */
    fun probeZ(maxDepthMm: Double, plateThicknessMm: Double, feed: Double) {
        val d = dialect()
        sendCommand(MachineCommands.probeZ(maxDepthMm, feed) + "\n" + MachineCommands.setZ(d, plateThicknessMm))
    }

    fun probeEdge(axis: String, distanceMm: Double, feed: Double) = sendCommand(MachineCommands.probeEdge(axis, distanceMm, feed))
    fun zeroAxis(axis: String) = sendCommand(MachineCommands.zeroAxis(dialect(), axis))

    // ---- Auto-level (multi-point Z probing) ---------------------------------------------------------
    //
    // Real material and beds are rarely perfectly flat, so one Z-zero taken in a corner can be off
    // everywhere else. This probes a grid across the bed and builds a HeightMap (see core/HeightMap.kt);
    // GcodeCompensation then nudges Z at every point in the toolpath by the map's local offset — the
    // same technique CNC "auto-level" tools use. It needs a Z axis on the active machine, since that is
    // what is actually being probed and corrected; a laser or plotter with no Z axis has nothing to
    // focus this way; a laser module that does have a motorised Z focus behaves like a CNC's Z here.

    private var autofocusJob: Job? = null

    fun cancelAutofocus() { autofocusJob?.cancel() }

    fun runAutofocusMesh(cols: Int, rows: Int, marginMm: Double, maxDepthMm: Double, probeFeed: Double, retractMm: Double) {
        val device = _uiState.value.devices.activeDevice
        val profile = device?.profile
        if (profile == null || !profile.hasAxis(feather.model.Axis.Z)) {
            report("The active machine has no Z axis to probe"); return
        }
        if (!session.state.value.connected) { report("Connect to the machine first"); return }
        val travelX = profile.travelMm(feather.model.Axis.X)
        val travelY = profile.travelMm(feather.model.Axis.Y)
        if (travelX <= marginMm * 2 || travelY <= marginMm * 2) { report("The margin is too large for this bed"); return }
        val targets = HeightMap.gridTargets(marginMm, marginMm, travelX - marginMm, travelY - marginMm, cols, rows)

        autofocusJob?.cancel()
        autofocusJob = viewModelScope.launch {
            _uiState.update { it.copy(autofocus = AutofocusState(running = true, totalCount = targets.size, message = "Starting...")) }
            val points = ArrayList<feather.core.ProbePoint>(targets.size)
            try {
                for ((i, target) in targets.withIndex()) {
                    val (x, y) = target
                    _uiState.update { it.copy(autofocus = it.autofocus.copy(doneCount = i, message = "Point ${i + 1} of ${targets.size}: X${fmtMm(x)} Y${fmtMm(y)}")) }
                    // Retract, move above the target, then probe straight down.
                    session.send("G91\nG0 Z${fmtMm(retractMm)}\nG90")?.let { throw AutofocusFailure(it) }
                    session.send("G90\nG0 X${fmtMm(x)} Y${fmtMm(y)}")?.let { throw AutofocusFailure(it) }
                    session.clearProbeResult()
                    session.send("G91\nG38.2 Z-${fmtMm(maxDepthMm)} F${fmtMm(probeFeed)}\nG90")?.let { throw AutofocusFailure(it) }
                    val probe = awaitProbeResult(timeoutMs = 15_000)
                        ?: throw AutofocusFailure("No contact at X${fmtMm(x)} Y${fmtMm(y)} within $maxDepthMm mm")
                    points += feather.core.ProbePoint(x, y, probe.z)
                }
                session.send("G91\nG0 Z${fmtMm(retractMm)}\nG90")
                val map = HeightMap(points, cols, rows)
                _uiState.update {
                    it.copy(
                        heightMap = map, useHeightMap = true,
                        autofocus = AutofocusState(message = "Done: ${points.size} points, height varies ${fmtMm((map.maxZ - map.minZ))} mm"),
                    )
                }
                report("Auto-level complete: ${points.size} points probed")
            } catch (e: AutofocusFailure) {
                _uiState.update { it.copy(autofocus = AutofocusState(message = e.message ?: "Auto-level failed")) }
                report(e.message ?: "Auto-level failed")
            } catch (e: CancellationException) {
                _uiState.update { it.copy(autofocus = AutofocusState(message = "Cancelled")) }
                throw e
            }
        }
    }

    private class AutofocusFailure(message: String) : Exception(message)

    private suspend fun awaitProbeResult(timeoutMs: Long): feather.link.MachinePosition? =
        withTimeoutOrNull(timeoutMs) { session.state.map { it.lastProbe }.filterNotNull().first() }

    fun setUseHeightMap(on: Boolean) = _uiState.update { it.copy(useHeightMap = on) }
    fun clearHeightMap() = _uiState.update { it.copy(heightMap = null, useHeightMap = false, autofocus = AutofocusState()) }

    /** Emergency stop: resets the controller whether or not a job is running. */
    fun emergencyStop() {
        if (activeLink != null) stop() else session.reset()
    }


    fun startScan() {
        if (scanJob?.isActive == true) return
        if (!BlePermissions.hasRequiredPermissions(appContext)) { report("Bluetooth permission is needed to scan"); return }
        _uiState.update { it.copy(scanning = true, bleDevices = emptyList()) }
        scanJob = viewModelScope.launch {
            try {
                withTimeoutOrNull(SCAN_MS) {
                    BleScanner(appContext).scan().collect { found ->
                        if (found.name.isNullOrBlank()) return@collect // unnamed devices are noise here
                        _uiState.update { s ->
                            val others = s.bleDevices.filter { d -> d.address != found.address }
                            s.copy(bleDevices = (others + BleDevice(found.address, found.name, found.rssi)).sortedByDescending { d -> d.rssi })
                        }
                    }
                }
            } catch (e: MachineLinkException) {
                report(e.message ?: "Scan failed")
            } finally {
                _uiState.update { it.copy(scanning = false) }
            }
        }
    }

    fun stopScan() { scanJob?.cancel() }

    private fun buildLink(target: LinkTarget): MachineLink = when (target) {
        is LinkTarget.Ble -> {
            val adapter = (appContext.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
                ?: throw MachineLinkException("Bluetooth is not available on this device")
            if (!BlePermissions.hasRequiredPermissions(appContext)) throw MachineLinkException("Bluetooth permission has not been granted")
            if (target.address.isBlank()) throw MachineLinkException("Pick a Bluetooth machine first")
            val device = try {
                adapter.getRemoteDevice(target.address)
            } catch (e: IllegalArgumentException) {
                throw MachineLinkException("Invalid Bluetooth address ${target.address}", e)
            }
            BleMachineLink(appContext, device)
        }
        is LinkTarget.Wifi -> {
            if (target.host.isBlank()) throw MachineLinkException("Enter the controller's IP address")
            WifiMachineLink(target.host.trim(), target.port)
        }
        is LinkTarget.Export -> FileExportLink {
            appContext.contentResolver.openOutputStream(target.uri, "wt") ?: throw IOException("Cannot open the destination")
        }
    }

    /** The target the Bluetooth/Wi-Fi modes currently point at (File mode needs a picker first). */
    fun currentLiveTarget(): LinkTarget? {
        val s = _uiState.value
        return when (s.linkMode) {
            LinkMode.BLUETOOTH -> LinkTarget.Ble(s.bleAddress)
            LinkMode.WIFI -> LinkTarget.Wifi(s.wifiHost, s.wifiPort)
            LinkMode.SD_CARD -> null
        }
    }

    // ---- Start / Pause / Stop -------------------------------------------------------

    fun start(target: LinkTarget) {
        val state = _uiState.value
        if (state.jobState == JobState.RUNNING || state.jobState == JobState.PAUSED) return
        fun fail(message: String) { _uiState.update { it.copy(jobState = JobState.ERROR, statusMessage = message) } }

        if (state.shapes.isEmpty()) { fail("Nothing drawn yet"); return }
        val problems = state.settings.validationErrors()
        if (problems.isNotEmpty()) { fail(problems.first()); return }
        val gcode = try {
            generateGcode(state)
        } catch (e: MachineLimitException) {
            fail(e.message ?: "The job does not fit this machine"); return
        } catch (e: IllegalArgumentException) {
            fail(e.message ?: "Could not generate G-code"); return
        } catch (e: ArithmeticException) {
            fail("A coordinate is out of range"); return
        }
        // A live session already owns the machine's single connection: run the job on it instead of opening a second one.
        val shared = session.link.takeIf { it != null && target !is LinkTarget.Export }
        val ownsLink = shared == null
        val link = shared ?: try {
            buildLink(target)
        } catch (e: MachineLinkException) {
            fail(e.message ?: "Could not open the link"); return
        }

        if (ownsLink) activeLink?.disconnect() else session.jobActive = true
        activeLink = link
        _uiState.update { it.copy(jobState = JobState.RUNNING, progress = 0 to 0, statusMessage = if (ownsLink) "Connecting..." else "Sending...") }

        jobRun = viewModelScope.launch {
            try {
                if (ownsLink) withTimeout(CONNECT_TIMEOUT_MS) { link.connect() }
                _uiState.update { it.copy(statusMessage = "Sending...") }
                link.sendGcode(gcode) { sent, total -> _uiState.update { s -> s.copy(progress = sent to total) } }
                _uiState.update {
                    it.copy(jobState = JobState.DONE, statusMessage = if (target is LinkTarget.Export) "G-code exported" else "Job complete")
                }
            } catch (e: JobStoppedException) {
                _uiState.update { it.copy(jobState = JobState.IDLE, statusMessage = "Stopped") }
            } catch (e: MachineLinkException) {
                fail(e.message ?: "Machine error")
            } catch (e: TimeoutCancellationException) {
                fail("Timed out connecting to the machine")
            } catch (e: CancellationException) {
                _uiState.update { it.copy(jobState = JobState.IDLE, statusMessage = "Cancelled") }
                throw e
            } catch (e: Exception) {
                fail(e.message ?: "Unexpected error")
            } finally {
                if (ownsLink) link.disconnect() else session.jobActive = false
                if (activeLink === link) activeLink = null
            }
        }
    }

    /** G-code for the current drawing on the active device (or machine-agnostic when no device is set). */
    private fun generateGcode(s: FeatherUiState): String {
        val job = CutJob(s.shapes, s.settings)
        val context = s.devices.activeDevice?.toMachineContext()
        val gcode = if (context != null) GCodeGenerator.generate(job, context) else GCodeGenerator.generate(job)
        val map = s.heightMap
        return if (s.useHeightMap && map != null) feather.core.GcodeCompensation.applyToText(gcode, map) else gcode
    }

    /** Emergency stop: goes straight to the link's real-time reset byte. */
    fun stop() {
        val link = activeLink ?: return
        link.stop()
        if (_uiState.value.progress.second == 0) jobRun?.cancel() // still connecting
    }

    fun pause() {
        if (_uiState.value.jobState != JobState.RUNNING) return
        activeLink?.pause()
        _uiState.update { it.copy(jobState = JobState.PAUSED, statusMessage = "Paused (feed hold)") }
    }

    fun resume() {
        if (_uiState.value.jobState != JobState.PAUSED) return
        activeLink?.resume()
        _uiState.update { it.copy(jobState = JobState.RUNNING, statusMessage = "Resumed") }
    }

    override fun onCleared() {
        // viewModelScope is cancelled first, which runs the job's `finally` and disconnects.
        activeLink?.disconnect()
        session.disconnect()
        super.onCleared()
    }

    // ---- Save / open / export ------------------------------------------------------

    private fun snapshotDocument(): FeatherDocument {
        val s = _uiState.value
        return FeatherFile.newDocument(s.shapes, referencedImages, s.calibration, s.settings)
    }

    private fun applyDocument(doc: FeatherDocument, uri: Uri?, name: String?) {
        referencedImages = doc.referencedImages
        undoStack.clear(); redoStack.clear()
        _uiState.update {
            it.copy(
                shapes = doc.shapes, settings = doc.settings, draft = null, measurement = null,
                selection = emptySet(), jobReport = null,
                canUndo = false, canRedo = false, documentUri = uri, documentName = name,
                jobState = JobState.IDLE, progress = 0 to 0,
            )
        }
        fitToDrawing()
        requestAutosave()
    }

    fun saveTo(uri: Uri) {
        val doc = snapshotDocument()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val out = appContext.contentResolver.openOutputStream(uri, "wt") ?: throw IOException("Cannot open the destination")
                    out.use { FeatherFile.write(it, doc) }
                }
                val name = uri.lastPathSegment?.substringAfterLast('/')
                _uiState.update { it.copy(documentUri = uri, documentName = name, statusMessage = "Saved ${name ?: "drawing"}") }
            } catch (e: IOException) {
                report("Save failed: ${e.message}")
            }
        }
    }

    fun openFrom(uri: Uri) {
        viewModelScope.launch {
            try {
                val bytes = withContext(Dispatchers.IO) {
                    val input = appContext.contentResolver.openInputStream(uri) ?: throw IOException("Cannot open the file")
                    input.use { it.readBytes() }
                }
                val name = uri.lastPathSegment?.substringAfterLast('/')
                when (FileSniffer.sniff(bytes)) {
                    FileKind.IMAGE -> startTrace(uri)
                    FileKind.GCODE -> loadGcode(String(bytes, Charsets.UTF_8), name ?: "G-code file")
                    else -> {
                        val doc = FeatherFile.read(bytes.inputStream())
                        applyDocument(doc, uri, name)
                        report("Opened ${name ?: "drawing"}")
                    }
                }
            } catch (e: FeatherFileException) {
                report(e.message ?: "Could not open the file")
            } catch (e: IOException) {
                report("Open failed: ${e.message}")
            }
        }
    }

    // ---- Projects (saved inside the app) ---------------------------------------------------

    fun refreshProjects() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) {
                projectsDir.listFiles { f -> f.isFile && f.name.endsWith(".feather") }
                    ?.map { ProjectFile(it.name.removeSuffix(".feather"), it.absolutePath, it.lastModified(), it.length()) }
                    ?.sortedByDescending { it.modifiedMs }
                    ?: emptyList()
            }
            _uiState.update { it.copy(projects = list) }
        }
    }

    fun saveProject(rawName: String) {
        val name = rawName.trim().replace(Regex("[^A-Za-z0-9 _.-]"), "_").take(60)
        if (name.isBlank()) { report("Give the project a name"); return }
        val doc = snapshotDocument()
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    projectsDir.mkdirs()
                    FeatherFile.save(File(projectsDir, "$name.feather"), doc)
                }
                _uiState.update { it.copy(documentName = "$name.feather", statusMessage = "Saved project $name") }
                refreshProjects()
            } catch (e: IOException) {
                report("Save failed: ${e.message}")
            }
        }
    }

    fun openProject(path: String) {
        viewModelScope.launch {
            try {
                val file = File(path)
                val doc = withContext(Dispatchers.IO) { FeatherFile.load(file) }
                applyDocument(doc, null, file.name)
                report("Opened ${file.nameWithoutExtension}")
            } catch (e: FeatherFileException) {
                report(e.message ?: "Could not open the project")
            } catch (e: IOException) {
                report("Open failed: ${e.message}")
            }
        }
    }

    fun deleteProject(path: String) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { File(path).delete() }
            refreshProjects()
        }
    }

    // ---- Stock (raw material) -------------------------------------------------------------------

    private fun loadStock(): StockDims? {
        val parts = prefs.getString(KEY_STOCK, null)?.split(';')?.mapNotNull { it.toDoubleOrNull() } ?: return null
        return if (parts.size == 3 && parts.all { it.isFinite() && it > 0.0 }) StockDims(parts[0], parts[1], parts[2]) else null
    }

    /** Pass null to clear. */
    fun setStock(stock: StockDims?) {
        if (stock == null) prefs.edit().remove(KEY_STOCK).apply()
        else prefs.edit().putString(KEY_STOCK, "${stock.widthMm};${stock.heightMm};${stock.thicknessMm}").apply()
        _uiState.update { it.copy(stock = stock) }
    }

    // ---- Selection and transforms ----------------------------------------------------------------

    fun selectAll() { _uiState.update { it.copy(selection = it.shapes.indices.toSet()) } }
    fun clearSelection() { _uiState.update { it.copy(selection = emptySet()) } }
    /** Sets the selection directly (e.g. tapping a row in the Layers panel). */
    fun setSelection(indices: Set<Int>) { _uiState.update { it.copy(selection = indices.filter { i -> i in it.shapes.indices }.toSet()) } }

    private fun editSelected(op: (Shape, feather.core.Bounds) -> Shape) {
        val s = _uiState.value
        val picked = s.selection.filter { it in s.shapes.indices }.toSet()
        if (picked.isEmpty()) return
        val bounds = Geometry.bounds(picked.map { s.shapes[it] }) ?: return
        try {
            commit(s.shapes.mapIndexed { i, sh -> if (i in picked) op(sh, bounds) else sh })
        } catch (e: ArithmeticException) {
            report("That would move the drawing out of range")
        }
    }

    private fun centreX(b: feather.core.Bounds) = Math.floorDiv(b.minX + b.maxX, 2L)
    private fun centreY(b: feather.core.Bounds) = Math.floorDiv(b.minY + b.maxY, 2L)

    fun nudgeSelection(dxMm: Double, dyMm: Double) {
        val dx = Micrometers.fromMillimeters(dxMm).raw
        val dy = Micrometers.fromMillimeters(dyMm).raw
        editSelected { sh, _ -> ShapeOps.translate(sh, dx, dy) }
    }

    fun scaleSelection(factor: Double) {
        if (!factor.isFinite() || factor <= 0.0) return
        editSelected { sh, b -> ShapeOps.scale(sh, centreX(b), centreY(b), factor) }
    }

    fun resizeSelectionToWidth(widthMm: Double) {
        val s = _uiState.value
        val b = Geometry.bounds(s.selection.filter { it in s.shapes.indices }.map { s.shapes[it] }) ?: return
        if (b.width <= 0L || !widthMm.isFinite() || widthMm <= 0.0) { report("Cannot resize to that width"); return }
        scaleSelection(widthMm * 1000.0 / b.width)
    }

    /** Scale is kept uniform (proportional) rather than independent per-axis: a Circle has no
     *  separate width/height field, so a non-uniform stretch would have to silently turn it into
     *  a different shape type. Resizing by height reaches the same result resizeSelectionToWidth
     *  would, just anchored on the other dimension. */
    fun resizeSelectionToHeight(heightMm: Double) {
        val s = _uiState.value
        val b = Geometry.bounds(s.selection.filter { it in s.shapes.indices }.map { s.shapes[it] }) ?: return
        if (b.height <= 0L || !heightMm.isFinite() || heightMm <= 0.0) { report("Cannot resize to that height"); return }
        scaleSelection(heightMm * 1000.0 / b.height)
    }

    /** Moves the selection so its bounding-box centre lands at an exact G-code mm position. */
    fun moveSelectionTo(xMm: Double, yMm: Double) {
        if (!validMm(xMm, yMm)) { report("Position is out of range"); return }
        val s = _uiState.value
        val b = Geometry.bounds(s.selection.filter { it in s.shapes.indices }.map { s.shapes[it] }) ?: return
        val targetX = Micrometers.fromMillimeters(xMm).raw
        val targetY = Micrometers.fromMillimeters(yMm).raw
        val dx = targetX - centreX(b)
        val dy = targetY - centreY(b)
        editSelected { sh, _ -> ShapeOps.translate(sh, dx, dy) }
    }

    /** Sets the stroke color of every selected shape. */
    fun setSelectionColor(argb: Int) {
        val s = _uiState.value
        val picked = s.selection.filter { it in s.shapes.indices }.toSet()
        if (picked.isEmpty()) return
        commit(s.shapes.mapIndexed { i, sh -> if (i in picked) sh.withColor(argb) else sh })
    }

    /** Layers panel: show/hide one shape by index. Hidden shapes are skipped by rendering,
     *  hit-testing and G-code export, but stay in the document (undo-able, not deleted). */
    fun setShapeHidden(index: Int, hidden: Boolean) {
        val s = _uiState.value
        if (index !in s.shapes.indices) return
        commit(s.shapes.mapIndexed { i, sh -> if (i == index) sh.withHidden(hidden) else sh })
    }

    /** Layers panel: moves shape [index] one step up/down in draw order (later = drawn on top,
     *  i.e. "closer to the front"). Returns the shape's new index so the caller can keep it selected. */
    fun moveShapeOrder(index: Int, towardFront: Boolean): Int {
        val s = _uiState.value
        val j = if (towardFront) index + 1 else index - 1
        if (index !in s.shapes.indices || j !in s.shapes.indices) return index
        val reordered = s.shapes.toMutableList()
        val tmp = reordered[index]; reordered[index] = reordered[j]; reordered[j] = tmp
        commit(reordered)
        _uiState.update { st ->
            st.copy(selection = st.selection.map { sel -> when (sel) { index -> j; j -> index; else -> sel } }.toSet())
        }
        return j
    }

    fun rotateSelection(degrees: Double) {
        editSelected { sh, b -> ShapeOps.rotate(sh, centreX(b), centreY(b), degrees) }
    }

    fun mirrorSelection(horizontal: Boolean) {
        editSelected { sh, b -> ShapeOps.mirror(sh, centreX(b), centreY(b), horizontal) }
    }

    fun simplifySelection(toleranceUm: Long) {
        val before = _uiState.value.selection.let { sel -> _uiState.value.shapes.filterIndexed { i, _ -> i in sel }.sumOf { ShapeOps.vertexCount(it) } }
        editSelected { sh, _ -> ShapeOps.simplify(sh, toleranceUm) }
        val after = _uiState.value.selection.let { sel -> _uiState.value.shapes.filterIndexed { i, _ -> i in sel }.sumOf { ShapeOps.vertexCount(it) } }
        if (before > 0) report("Simplified: $before points down to $after")
    }

    fun duplicateSelection() {
        val s = _uiState.value
        val picked = s.selection.filter { it in s.shapes.indices }.sorted()
        if (picked.isEmpty()) return
        val offset = Micrometers.fromMillimeters(5.0).raw
        val copies = try {
            picked.map { ShapeOps.translate(s.shapes[it], offset, offset) }
        } catch (e: ArithmeticException) {
            report("That would move the drawing out of range"); return
        }
        commit(s.shapes + copies)
        _uiState.update { st -> st.copy(selection = (s.shapes.size until s.shapes.size + copies.size).toSet()) }
    }

    fun deleteSelection() {
        val s = _uiState.value
        val picked = s.selection.filter { it in s.shapes.indices }.toSet()
        if (picked.isEmpty()) return
        commit(s.shapes.filterIndexed { i, _ -> i !in picked })
        _uiState.update { it.copy(selection = emptySet()) }
    }

    fun addShapes(shapes: List<Shape>) {
        if (shapes.isEmpty()) return
        commit(_uiState.value.shapes + shapes)
    }

    /** Zig-zag rows that misalign at each reversal if an axis has backlash; run it through the normal pipeline. */
    fun addBacklashPattern() {
        val rows = 6
        val pts = ArrayList<PointUm>()
        for (i in 0 until rows) {
            val y = Micrometers.fromMillimeters(10.0 + i * 0.6)
            val left = PointUm(Micrometers.fromMillimeters(10.0), y)
            val right = PointUm(Micrometers.fromMillimeters(40.0), y)
            if (i % 2 == 0) { pts.add(left); pts.add(right) } else { pts.add(right); pts.add(left) }
        }
        addShapes(listOf(Shape.Polyline(pts)))
        report("Added a backlash test pattern at 10 mm, 10 mm")
    }

    // ---- Toolpath report, estimate, validate ---------------------------------------------------------

    /** Builds the G-code the current drawing would run, checks it against the active machine and estimates its time. */
    fun analyzeJob(): JobReport? {
        val s = _uiState.value
        if (s.shapes.isEmpty()) { report("Nothing drawn yet"); return null }
        val device = s.devices.activeDevice
        val problems = ArrayList<String>()
        val job = CutJob(s.shapes, s.settings)
        var gcode: String? = null
        try {
            gcode = generateGcode(s)
        } catch (e: MachineLimitException) {
            problems += e.message ?: "The job does not fit this machine"
        } catch (e: IllegalArgumentException) {
            problems += e.message ?: "The job settings are invalid"
        } catch (e: ArithmeticException) {
            problems += "A coordinate is out of range"
        }
        if (gcode == null) {
            // Still draw it, so the operator can see *where* it does not fit.
            gcode = try { GCodeGenerator.generate(job) } catch (e: Exception) { null }
        }
        if (gcode == null) { report(problems.firstOrNull() ?: "Could not generate G-code"); return null }

        val moves = GcodeAnalysis.parse(gcode)
        val rapid = device?.profile?.maxFeedMmPerMin(Axis.X)?.takeIf { it < 100_000.0 } ?: 3000.0
        val stats = GcodeAnalysis.stats(moves, gcode.lineSequence().count { it.isNotBlank() }, rapid)
        if (problems.isEmpty() && device != null) {
            problems += GcodeAnalysis.boundsProblems(stats, device.profile.travelMm(Axis.X), device.profile.travelMm(Axis.Y))
        }
        val source = "Drawing" + (device?.let { " on ${it.name}" } ?: "")
        val result = JobReport(source, gcode, stats, problems)
        _uiState.update { it.copy(jobReport = result) }
        return result
    }

    /** Preview an existing .gcode file (from Open) against the active machine's bed. */
    fun loadGcode(text: String, name: String) {
        val device = _uiState.value.devices.activeDevice
        val moves = GcodeAnalysis.parse(text)
        val rapid = device?.profile?.maxFeedMmPerMin(Axis.X)?.takeIf { it < 100_000.0 } ?: 3000.0
        val stats = GcodeAnalysis.stats(moves, text.lineSequence().count { it.isNotBlank() }, rapid)
        val problems = if (device != null) {
            GcodeAnalysis.boundsProblems(stats, device.profile.travelMm(Axis.X), device.profile.travelMm(Axis.Y))
        } else emptyList()
        _uiState.update {
            it.copy(
                jobReport = JobReport(name, text, stats, problems),
                simulateRequested = true,
                statusMessage = "Loaded $name: ${stats.moves} moves",
            )
        }
    }

    fun consumeSimulateRequest() { _uiState.update { it.copy(simulateRequested = false) } }

    // ---- Photo tracing ------------------------------------------------------------------------------------

    /** [macro]: a close-up photo of a small object — keep more detail instead of the usual downsample, since the
     *  object already fills the frame. Not a magnification feature by itself; pair it with holding the phone
     *  closer or a clip-on macro lens, and good even lighting so the extra detail is actually sharp. */
    fun startTrace(uri: Uri, macro: Boolean = false) = beginTrace { PhotoImport.load(appContext, uri, if (macro) TRACE_MAX_SIDE_MACRO else TRACE_MAX_SIDE) }

    /** Trace a picture that was edited in the Photo editor (crop, tone and masking already applied). */
    fun startTraceFromBitmap(bitmap: Bitmap, macro: Boolean = false) =
        beginTrace { PhotoImport.fromBitmap(bitmap, if (macro) TRACE_MAX_SIDE_MACRO else TRACE_MAX_SIDE) }

    private fun beginTrace(load: () -> GrayImage) {
        traceJob?.cancel()
        _uiState.update { it.copy(trace = TraceState(loading = true)) }
        viewModelScope.launch {
            try {
                val image = withContext(Dispatchers.IO) { load() }
                val auto = withContext(Dispatchers.Default) { ImageTracer.otsu(image.pixels) }
                traceGray = image
                traceLoops = emptyList()
                _uiState.update {
                    it.copy(
                        trace = TraceState(
                            loading = false,
                            imageWidthPx = image.width,
                            imageHeightPx = image.height,
                            threshold = auto,
                            autoThreshold = auto,
                            widthMm = defaultTraceWidthMm(),
                        ),
                    )
                }
                recomputeTrace(retrace = true)
            } catch (e: IOException) {
                _uiState.update { it.copy(trace = null) }
                report(e.message ?: "Could not read the picture")
            } catch (e: OutOfMemoryError) {
                _uiState.update { it.copy(trace = null) }
                report("That picture is too large to open")
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(trace = null) }
                report("Could not read the picture (${e.message})")
            }
        }
    }

    // ---- Interior plan to drawing -----------------------------------------------------------------

    /** Adds the room outline and furniture footprints to the drawing at 1:[denominator]. */
    fun plotInterior(denominator: Int) {
        val shapes = PlanExport.shapes(interior.state.value.scene, denominator)
        val before = _uiState.value.shapes.size
        addShapes(shapes)
        _uiState.update { it.copy(selection = (before until before + shapes.size).toSet()) }
        fitToDrawing()
        report("Added the floor plan at 1:$denominator (${shapes.size} outlines)")
    }

    /** About 60 % of the smaller bed dimension when a machine is chosen, else 100 mm. */
    private fun defaultTraceWidthMm(): Double {
        val profile = _uiState.value.devices.activeDevice?.profile ?: return 100.0
        val bed = minOf(profile.travelMm(Axis.X), profile.travelMm(Axis.Y))
        return if (bed > 0.0) Math.round(bed * 0.6).toDouble() else 100.0
    }

    fun updateTrace(change: (TraceState) -> TraceState) {
        val before = _uiState.value.trace ?: return
        val after = change(before)
        _uiState.update { it.copy(trace = after) }
        recomputeTrace(retrace = after.threshold != before.threshold || after.invert != before.invert)
    }

    private fun recomputeTrace(retrace: Boolean) {
        val image = traceGray ?: return
        traceJob?.cancel()
        val loopsIn = traceLoops
        traceJob = viewModelScope.launch {
            val settings = _uiState.value.trace ?: return@launch
            _uiState.update { s -> s.trace?.let { s.copy(trace = it.copy(working = true)) } ?: s }
            val (loops, shapes) = withContext(Dispatchers.Default) {
                val l = if (retrace || loopsIn.isEmpty()) {
                    ImageTracer.contours(ImageTracer.threshold(image.pixels, settings.threshold, settings.invert), image.width, image.height)
                } else loopsIn
                val sh = ImageTracer.toShapes(l, image.width, image.height, settings.widthMm, 0L, 0L, settings.simplifyUm, settings.despeckle)
                l to sh
            }
            traceLoops = loops
            _uiState.update { s -> s.trace?.let { s.copy(trace = it.copy(preview = shapes, working = false)) } ?: s }
        }
    }

    fun cancelTrace() {
        traceJob?.cancel()
        traceGray = null
        traceLoops = emptyList()
        _uiState.update { it.copy(trace = null) }
    }

    /** Puts the traced outlines on the drawing, centred on the active machine's bed when they fit. */
    fun commitTrace() {
        val s = _uiState.value
        val trace = s.trace ?: return
        val image = traceGray ?: return
        val loops = traceLoops
        val profile = s.devices.activeDevice?.profile
        val heightMm = trace.heightMm
        val bedX = profile?.travelMm(Axis.X) ?: 0.0
        val bedY = profile?.travelMm(Axis.Y) ?: 0.0
        val originX = if (bedX >= trace.widthMm) (bedX - trace.widthMm) / 2.0 else 0.0
        val originY = if (bedY >= heightMm) (bedY - heightMm) / 2.0 else 0.0
        viewModelScope.launch {
            val shapes = withContext(Dispatchers.Default) {
                ImageTracer.toShapes(
                    loops, image.width, image.height, trace.widthMm,
                    Micrometers.fromMillimeters(originX).raw, Micrometers.fromMillimeters(originY).raw,
                    trace.simplifyUm, trace.despeckle,
                )
            }
            if (shapes.isEmpty()) { report("Nothing to add. Adjust the threshold and try again."); return@launch }
            val before = _uiState.value.shapes.size
            commit(_uiState.value.shapes + shapes)
            _uiState.update { it.copy(trace = null, selection = (before until before + shapes.size).toSet()) }
            traceGray = null
            traceLoops = emptyList()
            fitToDrawing()
            report("Added ${shapes.size} outline${if (shapes.size == 1) "" else "s"} from the picture")
        }
    }
}

/** FeatherViewModel needs a Context and the device's nominal pixel density. */
class FeatherViewModelFactory(context: Context) : ViewModelProvider.Factory {
    private val appContext = context.applicationContext

    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        require(modelClass.isAssignableFrom(FeatherViewModel::class.java)) { "Unknown ViewModel class" }
        // Nominal only: the operator should calibrate against a real ruler (Build > Calibrate).
        val nominal = (appContext.resources.displayMetrics.xdpi / 25.4f).toDouble()
        val pxPerMm = if (nominal in 3.0..40.0) nominal else 10.0
        return FeatherViewModel(appContext, pxPerMm) as T
    }
}
