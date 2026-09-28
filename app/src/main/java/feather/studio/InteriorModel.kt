package feather.studio

import feather.interior.ItemType
import feather.interior.Room
import feather.interior.RoomItem
import feather.interior.Scene
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.UUID

data class InteriorState(
    val scene: Scene = Scene.starter(),
    val selectedId: String? = null,
    val canUndo: Boolean = false,
) {
    val selected: RoomItem? get() = scene.items.firstOrNull { it.id == selectedId }
}

/** The room being designed: edits, undo, and autosave to one JSON file in app storage. */
class InteriorModel(private val file: File, private val scope: CoroutineScope) {

    private companion object {
        const val MAX_UNDO = 40
        const val MIN_ROOM = 150.0
        const val MAX_ROOM = 3000.0
        const val MIN_ITEM = 2.0
        const val MAX_ITEM = 1000.0
    }

    private val _state = MutableStateFlow(InteriorState())
    val state: StateFlow<InteriorState> = _state.asStateFlow()

    private val undoStack = ArrayList<Scene>()
    private var saveJob: Job? = null
    private var moveOrigin: Scene? = null

    init {
        try {
            if (file.exists()) _state.value = InteriorState(scene = fromJson(JSONObject(file.readText(Charsets.UTF_8))))
        } catch (e: JSONException) {
            // A damaged file must never stop the app: start from the sample room instead.
        } catch (e: IOException) {
        } catch (e: IllegalArgumentException) {
        }
    }

    // ---- Edits ----------------------------------------------------------------------------------

    /** Applies [change] to the scene as one undoable step. */
    private fun commit(change: (Scene) -> Scene) {
        val before = _state.value.scene
        val after = change(before)
        if (after == before) return
        undoStack.add(before)
        if (undoStack.size > MAX_UNDO) undoStack.removeAt(0)
        _state.update { it.copy(scene = after, canUndo = true) }
        save()
    }

    private fun updateSelected(change: (RoomItem) -> RoomItem) {
        val id = _state.value.selectedId ?: return
        commit { s -> s.copy(items = s.items.map { if (it.id == id) change(it) else it }) }
    }

    fun select(id: String?) { _state.update { it.copy(selectedId = id) } }

    fun add(type: ItemType) {
        val room = _state.value.scene.room
        val id = "item-" + UUID.randomUUID().toString().take(8)
        val count = _state.value.scene.items.count { it.type == type } + 1
        val item = RoomItem(
            id, type, if (count == 1) type.label else "${type.label} $count",
            room.widthCm / 2, room.depthCm / 2,
            type.widthCm, type.depthCm, type.heightCm, type.elevationCm, 0.0, type.color,
        )
        commit { it.copy(items = it.items + item) }
        select(id)
    }

    /** Drag: history is recorded once, at [endMove]. */
    fun beginMove() { moveOrigin = _state.value.scene }

    fun moveSelectedTo(xCm: Double, yCm: Double) {
        val id = _state.value.selectedId ?: return
        _state.update { st ->
            val room = st.scene.room
            val nx = xCm.coerceIn(0.0, room.widthCm)
            val ny = yCm.coerceIn(0.0, room.depthCm)
            st.copy(scene = st.scene.copy(items = st.scene.items.map { if (it.id == id) it.copy(xCm = nx, yCm = ny) else it }))
        }
    }

    fun endMove() {
        val origin = moveOrigin ?: return
        moveOrigin = null
        if (origin != _state.value.scene) {
            undoStack.add(origin)
            if (undoStack.size > MAX_UNDO) undoStack.removeAt(0)
            _state.update { it.copy(canUndo = true) }
            save()
        }
    }

    fun nudge(dxCm: Double, dyCm: Double) {
        val room = _state.value.scene.room
        updateSelected { it.copy(xCm = (it.xCm + dxCm).coerceIn(0.0, room.widthCm), yCm = (it.yCm + dyCm).coerceIn(0.0, room.depthCm)) }
    }

    fun rotate(deltaDeg: Double) { updateSelected { it.copy(rotationDeg = ((it.rotationDeg + deltaDeg) % 360.0 + 360.0) % 360.0) } }

    fun resize(widthCm: Double, depthCm: Double, heightCm: Double) {
        if (listOf(widthCm, depthCm, heightCm).any { !it.isFinite() || it < MIN_ITEM || it > MAX_ITEM }) return
        updateSelected { it.copy(widthCm = widthCm, depthCm = depthCm, heightCm = heightCm) }
    }

    fun setColor(argb: Int) { updateSelected { it.copy(color = argb) } }

    fun duplicate() {
        val src = _state.value.selected ?: return
        val id = "item-" + UUID.randomUUID().toString().take(8)
        val room = _state.value.scene.room
        val copy = src.copy(id = id, xCm = (src.xCm + 30).coerceAtMost(room.widthCm), yCm = (src.yCm + 30).coerceAtMost(room.depthCm))
        commit { it.copy(items = it.items + copy) }
        select(id)
    }

    fun delete() {
        val id = _state.value.selectedId ?: return
        commit { s -> s.copy(items = s.items.filterNot { it.id == id }) }
        select(null)
    }

    fun setRoom(widthCm: Double, depthCm: Double, heightCm: Double) {
        if (listOf(widthCm, depthCm, heightCm).any { !it.isFinite() || it < MIN_ROOM || it > MAX_ROOM }) return
        commit { it.copy(room = it.room.copy(widthCm = widthCm, depthCm = depthCm, heightCm = heightCm)) }
    }

    fun setFloorColor(argb: Int) { commit { it.copy(room = it.room.copy(floorColor = argb)) } }
    fun setWallColor(argb: Int) { commit { it.copy(room = it.room.copy(wallColor = argb)) } }

    fun clearRoom() { commit { it.copy(items = emptyList()) }; select(null) }
    fun loadStarter() { commit { Scene.starter() }; select(null) }

    fun undo() {
        if (undoStack.isEmpty()) return
        val prev = undoStack.removeAt(undoStack.size - 1)
        _state.update { it.copy(scene = prev, canUndo = undoStack.isNotEmpty(), selectedId = it.selectedId?.takeIf { id -> prev.items.any { i -> i.id == id } }) }
        save()
    }

    // ---- Persistence ----------------------------------------------------------------------------

    private fun save() {
        val scene = _state.value.scene
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(600)
            withContext(Dispatchers.IO) {
                try {
                    val tmp = File(file.parentFile, file.name + ".tmp")
                    tmp.writeText(toJson(scene).toString(2), Charsets.UTF_8)
                    if (!tmp.renameTo(file)) { file.delete(); tmp.renameTo(file) }
                } catch (e: IOException) {
                    // Best effort; the next edit tries again.
                }
            }
        }
    }

    private fun toJson(s: Scene) = JSONObject().apply {
        put("formatVersion", 1)
        put("room", JSONObject().apply {
            put("w", s.room.widthCm); put("d", s.room.depthCm); put("h", s.room.heightCm)
            put("floor", s.room.floorColor); put("wall", s.room.wallColor)
        })
        put("items", JSONArray().also { arr ->
            s.items.forEach { i ->
                arr.put(JSONObject().apply {
                    put("id", i.id); put("type", i.type.name); put("name", i.name)
                    put("x", i.xCm); put("y", i.yCm); put("w", i.widthCm); put("d", i.depthCm); put("h", i.heightCm)
                    put("e", i.elevationCm); put("r", i.rotationDeg); put("c", i.color)
                })
            }
        })
    }

    private fun fromJson(o: JSONObject): Scene {
        val r = o.getJSONObject("room")
        val room = Room(r.getDouble("w"), r.getDouble("d"), r.getDouble("h"), r.getInt("floor"), r.getInt("wall"))
        val arr = o.getJSONArray("items")
        val items = (0 until arr.length()).map { idx ->
            val i = arr.getJSONObject(idx)
            RoomItem(
                i.getString("id"), ItemType.valueOf(i.getString("type")), i.getString("name"),
                i.getDouble("x"), i.getDouble("y"), i.getDouble("w"), i.getDouble("d"), i.getDouble("h"),
                i.optDouble("e", 0.0), i.optDouble("r", 0.0), i.getInt("c"),
            )
        }
        return Scene(room, items)
    }
}
