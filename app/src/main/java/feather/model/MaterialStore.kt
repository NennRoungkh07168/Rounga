package feather.model

import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException

class MaterialStoreException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Persists only the user's own materials (the built-in library is code, not a file). */
class MaterialStore(private val file: File) {

    fun load(): List<MaterialPreset> {
        if (!file.exists()) return emptyList()
        return try {
            val arr = JSONArray(file.readText(Charsets.UTF_8))
            (0 until arr.length()).map { fromJson(arr.getJSONObject(it)) }
        } catch (e: JSONException) {
            throw MaterialStoreException("The saved materials file is damaged.", e)
        } catch (e: IOException) {
            throw MaterialStoreException("Could not read the saved materials.", e)
        }
    }

    fun save(materials: List<MaterialPreset>) {
        try {
            val arr = JSONArray()
            materials.forEach { arr.put(toJson(it)) }
            val tmp = File(file.parentFile, file.name + ".tmp")
            tmp.writeText(arr.toString(), Charsets.UTF_8)
            if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
        } catch (e: IOException) {
            throw MaterialStoreException("Could not save the materials.", e)
        }
    }

    private fun toJson(m: MaterialPreset) = JSONObject().apply {
        put("id", m.id); put("name", m.name); put("category", m.category.name)
        put("thicknessMm", m.thicknessMm); put("forLaser", m.forLaser)
        put("laserPowerPercent", m.laserPowerPercent); put("laserSpeedMmPerMin", m.laserSpeedMmPerMin); put("laserPasses", m.laserPasses)
        put("cutFeedMmPerMin", m.cutFeedMmPerMin); put("plungeRateMmPerMin", m.plungeRateMmPerMin)
        put("stepDownMm", m.stepDownMm); put("spindleRpm", m.spindleRpm); put("notes", m.notes)
    }

    private fun fromJson(o: JSONObject) = MaterialPreset(
        id = o.getString("id"), name = o.getString("name"),
        category = runCatching { MaterialCategory.valueOf(o.getString("category")) }.getOrDefault(MaterialCategory.OTHER),
        thicknessMm = o.getDouble("thicknessMm"), forLaser = o.getBoolean("forLaser"),
        laserPowerPercent = o.optInt("laserPowerPercent", 60), laserSpeedMmPerMin = o.optDouble("laserSpeedMmPerMin", 300.0),
        laserPasses = o.optInt("laserPasses", 1), cutFeedMmPerMin = o.optDouble("cutFeedMmPerMin", 800.0),
        plungeRateMmPerMin = o.optDouble("plungeRateMmPerMin", 200.0), stepDownMm = o.optDouble("stepDownMm", 1.0),
        spindleRpm = o.optInt("spindleRpm", 12_000), notes = o.optString("notes", ""), builtIn = false,
    )
}

/** Built-in library plus the user's own, with add/remove and persistence — the same shape as DeviceRepository. */
class MaterialLibrary(private val store: MaterialStore) {

    private val _userMaterials = kotlinx.coroutines.flow.MutableStateFlow(safeLoad())

    /** Built-in presets first, then the user's own. */
    private val _all = kotlinx.coroutines.flow.MutableStateFlow(MaterialPresets.builtIn() + _userMaterials.value)
    val all: kotlinx.coroutines.flow.StateFlow<List<MaterialPreset>> = _all

    private fun safeLoad(): List<MaterialPreset> = try { store.load() } catch (e: MaterialStoreException) { emptyList() }

    private fun persist(user: List<MaterialPreset>) {
        _userMaterials.value = user
        _all.value = MaterialPresets.builtIn() + user
        try { store.save(user) } catch (e: MaterialStoreException) { /* keep in-memory; retry on next edit */ }
    }

    fun add(material: MaterialPreset) {
        val withoutSameId = _userMaterials.value.filterNot { it.id == material.id }
        persist(withoutSameId + material)
    }

    fun remove(id: String) {
        persist(_userMaterials.value.filterNot { it.id == id })
    }
}
