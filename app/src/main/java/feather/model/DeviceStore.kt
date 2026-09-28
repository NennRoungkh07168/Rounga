package feather.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class DeviceStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class DeviceCollection(val devices: List<Device> = emptyList(), val activeDeviceId: String? = null) {
    val activeDevice: Device? get() = devices.firstOrNull { it.id == activeDeviceId }
}

/**
 * Persists every saved [Device] (and which one is active) to a single JSON
 * file — `devices.json` in app-private storage — the same plain-JSON,
 * atomic-write approach [feather.core.FeatherFile] uses for `.feather` project files.
 *
 * This is what makes "My Devices" survive an app restart: without it, every
 * device profile, tool list, and machine limit would have to be re-entered
 * each session.
 */
class DeviceStore(private val file: File) {

    fun load(): DeviceCollection {
        if (!file.exists()) return DeviceCollection()
        return try {
            fromJson(JSONObject(file.readText(Charsets.UTF_8)))
        } catch (e: JSONException) {
            throw DeviceStoreException("Corrupt device list (${e.message})", e)
        }
    }

    fun save(collection: DeviceCollection) {
        val tmp = File(file.parentFile, file.name + ".tmp")
        tmp.writeText(toJson(collection).toString(2), Charsets.UTF_8)
        try {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (e: AtomicMoveNotSupportedException) {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: IOException) {
            tmp.delete()
            throw DeviceStoreException("Could not save device list: ${e.message}", e)
        }
    }

    // ---- JSON <-> model ----------------------------------------------------

    private fun toJson(c: DeviceCollection): JSONObject = JSONObject().apply {
        put("formatVersion", 1)
        put("activeDeviceId", c.activeDeviceId ?: JSONObject.NULL)
        put("devices", JSONArray().also { arr -> c.devices.forEach { arr.put(deviceToJson(it)) } })
    }

    private fun fromJson(json: JSONObject): DeviceCollection {
        val devicesJson = json.optJSONArray("devices") ?: JSONArray()
        val devices = (0 until devicesJson.length()).map { deviceFromJson(devicesJson.getJSONObject(it)) }
        val activeId = if (json.isNull("activeDeviceId")) null else json.optString("activeDeviceId", null)
        return DeviceCollection(devices, activeId)
    }

    private fun deviceToJson(d: Device) = JSONObject().apply {
        put("id", d.id)
        put("name", d.name)
        put("connection", d.connection.name)
        put("address", d.address)
        put("controller", d.controller.name)
        put("status", d.status.name)
        put("favorite", d.favorite)
        put("activeToolId", d.activeToolId ?: JSONObject.NULL)
        put("profile", profileToJson(d.profile))
        put("tools", JSONArray().also { arr -> d.tools.forEach { arr.put(toolToJson(it)) } })
    }

    private fun deviceFromJson(o: JSONObject): Device {
        val tools = o.getJSONArray("tools").let { arr -> (0 until arr.length()).map { toolFromJson(arr.getJSONObject(it)) } }
        return Device(
            id = o.getString("id"),
            name = o.getString("name"),
            connection = ConnectionType.valueOf(o.getString("connection")),
            address = o.optString("address", ""),
            controller = Controller.valueOf(o.getString("controller")),
            profile = profileFromJson(o.getJSONObject("profile")),
            tools = tools,
            activeToolId = if (o.isNull("activeToolId")) null else o.optString("activeToolId", null),
            status = DeviceStatus.valueOf(o.optString("status", DeviceStatus.DISCONNECTED.name)),
            favorite = o.optBoolean("favorite", false),
        )
    }

    private fun profileToJson(p: MachineProfile) = JSONObject().apply {
        put("id", p.id)
        put("name", p.name)
        put("machineType", p.machineType.name)
        put("homingEnabled", p.homingEnabled)
        put("units", p.units.name)
        put("coordinateSystem", p.coordinateSystem)
        put(
            "axes",
            JSONArray().also { arr ->
                p.axes.forEach { (axis, limits) -> arr.put(axisToJson(limits).put("axis", axis.name)) }
            },
        )
    }

    private fun profileFromJson(o: JSONObject): MachineProfile {
        val axesJson = o.getJSONArray("axes")
        val axes = (0 until axesJson.length()).associate { i ->
            val entry = axesJson.getJSONObject(i)
            Axis.valueOf(entry.getString("axis")) to axisFromJson(entry)
        }
        return MachineProfile(
            id = o.getString("id"),
            name = o.getString("name"),
            machineType = MachineType.valueOf(o.getString("machineType")),
            axes = axes,
            homingEnabled = o.optBoolean("homingEnabled", true),
            units = LengthUnit.valueOf(o.optString("units", LengthUnit.MM.name)),
            coordinateSystem = o.optString("coordinateSystem", "G54"),
        )
    }

    private fun axisToJson(a: AxisLimits) = JSONObject().apply {
        put("travelMm", a.travelMm)
        put("stepsPerMm", a.stepsPerMm)
        put("maxFeedMmPerMin", a.maxFeedMmPerMin)
        put("maxAccelMmPerSec2", a.maxAccelMmPerSec2)
        put("softLimitEnabled", a.softLimitEnabled)
    }

    private fun axisFromJson(o: JSONObject) = AxisLimits(
        travelMm = o.getDouble("travelMm"),
        stepsPerMm = o.optDouble("stepsPerMm", 80.0),
        maxFeedMmPerMin = o.optDouble("maxFeedMmPerMin", 3_000.0),
        maxAccelMmPerSec2 = o.optDouble("maxAccelMmPerSec2", 500.0),
        softLimitEnabled = o.optBoolean("softLimitEnabled", true),
    )

    private fun toolToJson(t: ToolHead): JSONObject {
        val o = JSONObject()
        o.put("id", t.id)
        o.put("name", t.name)
        when (t) {
            is ToolHead.Spindle -> o.put("type", "spindle")
                .put("minRpm", t.minRpm).put("maxRpm", t.maxRpm).put("defaultRpm", t.defaultRpm)
                .put("toolDiameterMm", t.toolDiameterMm).put("maxCutDepthMm", t.maxCutDepthMm).put("passes", t.passes)
            is ToolHead.Laser -> o.put("type", "laser")
                .put("maxPowerWatts", t.maxPowerWatts).put("defaultPowerPercent", t.defaultPowerPercent)
                .put("pwmMax", t.pwmMax).put("engraveDepthMm", t.engraveDepthMm).put("passes", t.passes)
            is ToolHead.Pen -> o.put("type", "pen")
                .put("liftHeightMm", t.liftHeightMm).put("downHeightMm", t.downHeightMm)
            is ToolHead.Drill -> o.put("type", "drill")
                .put("diameterMm", t.diameterMm).put("plungeRateMmPerMin", t.plungeRateMmPerMin)
            is ToolHead.PcbCutter -> o.put("type", "pcb_cutter")
                .put("diameterMm", t.diameterMm).put("maxCutDepthMm", t.maxCutDepthMm).put("spindleRpm", t.spindleRpm)
            is ToolHead.Knife -> o.put("type", "knife")
                .put("bladeOffsetMm", t.bladeOffsetMm).put("downForcePercent", t.downForcePercent)
            is ToolHead.Extruder -> o.put("type", "extruder")
                .put("nozzleDiameterMm", t.nozzleDiameterMm).put("filamentDiameterMm", t.filamentDiameterMm)
                .put("targetTempC", t.targetTempC).put("bedTempC", t.bedTempC)
            is ToolHead.Custom -> {
                o.put("type", "custom")
                o.put("onGcode", t.onGcode).put("offGcode", t.offGcode)
                o.put(
                    "params",
                    JSONArray().also { arr -> t.params.forEach { (k, v) -> arr.put(JSONObject().put("key", k).put("value", v)) } },
                )
            }
        }
        return o
    }

    private fun toolFromJson(o: JSONObject): ToolHead {
        val id = o.getString("id")
        val name = o.getString("name")
        return when (val type = o.getString("type")) {
            "spindle" -> ToolHead.Spindle(
                id, name, o.optInt("minRpm", 5_000), o.optInt("maxRpm", 24_000), o.optInt("defaultRpm", 12_000),
                o.optDouble("toolDiameterMm", 3.175), o.optDouble("maxCutDepthMm", 20.0), o.optInt("passes", 1),
            )
            "laser" -> ToolHead.Laser(
                id, name, o.optDouble("maxPowerWatts", 10.0), o.optInt("defaultPowerPercent", 60),
                o.optInt("pwmMax", 1_000), o.optDouble("engraveDepthMm", 0.0), o.optInt("passes", 1),
            )
            "pen" -> ToolHead.Pen(id, name, o.optDouble("liftHeightMm", 5.0), o.optDouble("downHeightMm", 0.0))
            "drill" -> ToolHead.Drill(id, name, o.optDouble("diameterMm", 0.8), o.optDouble("plungeRateMmPerMin", 100.0))
            "pcb_cutter" -> ToolHead.PcbCutter(
                id, name, o.optDouble("diameterMm", 0.2), o.optDouble("maxCutDepthMm", 1.6), o.optInt("spindleRpm", 10_000),
            )
            "knife" -> ToolHead.Knife(id, name, o.optDouble("bladeOffsetMm", 0.5), o.optInt("downForcePercent", 50))
            "extruder" -> ToolHead.Extruder(
                id, name, o.optDouble("nozzleDiameterMm", 0.4), o.optDouble("filamentDiameterMm", 1.75),
                o.optInt("targetTempC", 200), o.optInt("bedTempC", 60),
            )
            "custom" -> {
                val paramsJson = o.optJSONArray("params") ?: JSONArray()
                val params = (0 until paramsJson.length()).associate { i ->
                    val entry = paramsJson.getJSONObject(i)
                    entry.getString("key") to entry.getDouble("value")
                }
                ToolHead.Custom(id, name, params, o.optString("onGcode", "M3"), o.optString("offGcode", "M5"))
            }
            else -> throw DeviceStoreException("Unknown tool type \"$type\" in device list")
        }
    }
}

/**
 * In-memory front for [DeviceStore]: exposes the device list + active device
 * as a [StateFlow] for Compose, and writes through to disk on every change.
 * One instance lives for the app's process (see `FeatherViewModel`).
 */
class DeviceRepository(private val store: DeviceStore) {

    private val _state = MutableStateFlow(loadSafely())
    val state: StateFlow<DeviceCollection> = _state.asStateFlow()

    private fun loadSafely(): DeviceCollection = try {
        store.load()
    } catch (e: DeviceStoreException) {
        DeviceCollection() // corrupt file: start clean rather than crash the app
    }

    private fun persist(mutator: (DeviceCollection) -> DeviceCollection) {
        _state.update(mutator)
        try {
            store.save(_state.value)
        } catch (e: DeviceStoreException) {
            // Keep the in-memory change; the next successful save writes everything.
        }
    }

    fun addDevice(device: Device) = persist { c ->
        c.copy(devices = c.devices + device, activeDeviceId = c.activeDeviceId ?: device.id)
    }

    fun updateDevice(device: Device) = persist { c ->
        c.copy(devices = c.devices.map { if (it.id == device.id) device else it })
    }

    fun removeDevice(deviceId: String) = persist { c ->
        val remaining = c.devices.filterNot { it.id == deviceId }
        c.copy(
            devices = remaining,
            activeDeviceId = if (c.activeDeviceId == deviceId) remaining.firstOrNull()?.id else c.activeDeviceId,
        )
    }

    fun setActiveDevice(deviceId: String) = persist { c ->
        if (c.devices.any { it.id == deviceId }) c.copy(activeDeviceId = deviceId) else c
    }

    fun setDeviceStatus(deviceId: String, status: DeviceStatus) = persist { c ->
        c.copy(devices = c.devices.map { if (it.id == deviceId) it.copy(status = status) else it })
    }

    fun newId(): String = UUID.randomUUID().toString()
}
