package feather.model

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

/**
 * Turns a [MaterialPreset] or a [Device] into a short, prefixed JSON string that fits a QR code, and
 * back again. A device is encoded generically as its profile and tool rows (the same [Param] lists
 * [ProfileParams] and [ToolParams] already use for Copy settings), so decoding it only needs those
 * two objects' existing `withValue`, with no separate device-shaped parser to keep in sync.
 */
object QrPayload {

    private const val MATERIAL_PREFIX = "FEATHER-MATERIAL-1:"
    private const val DEVICE_PREFIX = "FEATHER-DEVICE-1:"

    fun encodeMaterial(m: MaterialPreset): String {
        val o = JSONObject().apply {
            put("name", m.name); put("category", m.category.name); put("thicknessMm", m.thicknessMm)
            put("forLaser", m.forLaser)
            put("laserPowerPercent", m.laserPowerPercent); put("laserSpeedMmPerMin", m.laserSpeedMmPerMin); put("laserPasses", m.laserPasses)
            put("cutFeedMmPerMin", m.cutFeedMmPerMin); put("plungeRateMmPerMin", m.plungeRateMmPerMin)
            put("stepDownMm", m.stepDownMm); put("spindleRpm", m.spindleRpm); put("notes", m.notes)
        }
        return MATERIAL_PREFIX + o.toString()
    }

    /** Null if [text] isn't a material QR this app made, or its JSON is unreadable. */
    fun decodeMaterial(text: String, newId: String): MaterialPreset? {
        if (!text.startsWith(MATERIAL_PREFIX)) return null
        return try {
            val o = JSONObject(text.removePrefix(MATERIAL_PREFIX))
            MaterialPreset(
                id = newId, name = o.getString("name"),
                category = runCatching { MaterialCategory.valueOf(o.getString("category")) }.getOrDefault(MaterialCategory.OTHER),
                thicknessMm = o.getDouble("thicknessMm"), forLaser = o.getBoolean("forLaser"),
                laserPowerPercent = o.optInt("laserPowerPercent", 60), laserSpeedMmPerMin = o.optDouble("laserSpeedMmPerMin", 300.0),
                laserPasses = o.optInt("laserPasses", 1), cutFeedMmPerMin = o.optDouble("cutFeedMmPerMin", 800.0),
                plungeRateMmPerMin = o.optDouble("plungeRateMmPerMin", 200.0), stepDownMm = o.optDouble("stepDownMm", 1.0),
                spindleRpm = o.optInt("spindleRpm", 12_000), notes = o.optString("notes", ""), builtIn = false,
            )
        } catch (e: JSONException) {
            null
        }
    }

    fun encodeDevice(name: String, machineType: MachineType, controller: Controller, profile: MachineProfile, tool: ToolHead): String {
        val o = JSONObject().apply {
            put("name", name); put("machineType", machineType.name); put("controller", controller.name)
            put("toolFamily", ToolParams.familyName(tool)); put("toolName", tool.name)
            put("profileRows", rowsToJson(ProfileParams.rows(profile)))
            put("toolRows", rowsToJson(ToolParams.rows(tool)))
        }
        return DEVICE_PREFIX + o.toString()
    }

    private fun rowsToJson(rows: List<Param>): JSONArray {
        val arr = JSONArray()
        rows.forEach { arr.put(JSONObject().apply { put("key", it.key); put("value", it.value) }) }
        return arr
    }

    /** What a decoded device QR carries, before it becomes a real [Device] (that needs a fresh id). */
    data class DeviceImport(
        val name: String, val machineType: MachineType, val controller: Controller,
        val toolFamily: String, val toolName: String,
        val profileRows: List<Pair<String, Double>>, val toolRows: List<Pair<String, Double>>,
    )

    fun decodeDevice(text: String): DeviceImport? {
        if (!text.startsWith(DEVICE_PREFIX)) return null
        return try {
            val o = JSONObject(text.removePrefix(DEVICE_PREFIX))
            fun rows(key: String): List<Pair<String, Double>> {
                val arr = o.getJSONArray(key)
                return (0 until arr.length()).map { i -> arr.getJSONObject(i).let { it.getString("key") to it.getDouble("value") } }
            }
            DeviceImport(
                name = o.getString("name"),
                machineType = runCatching { MachineType.valueOf(o.getString("machineType")) }.getOrElse { return null },
                controller = runCatching { Controller.valueOf(o.getString("controller")) }.getOrElse { return null },
                toolFamily = o.getString("toolFamily"), toolName = o.getString("toolName"),
                profileRows = rows("profileRows"), toolRows = rows("toolRows"),
            )
        } catch (e: JSONException) {
            null
        }
    }

    enum class Kind { MATERIAL, DEVICE, UNKNOWN }

    fun kindOf(text: String): Kind = when {
        text.startsWith(MATERIAL_PREFIX) -> Kind.MATERIAL
        text.startsWith(DEVICE_PREFIX) -> Kind.DEVICE
        else -> Kind.UNKNOWN
    }
}
