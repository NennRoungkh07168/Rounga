package feather.model

enum class DeviceStatus { CONNECTED, DISCONNECTED, CONNECTING, ERROR }

/**
 * One entry in "My Devices": a saved machine the app can talk to. This is the
 * top of the per-machine stack —
 *
 *     Device
 *      ├── ConnectionType + address   (how to reach it: BLE/Wi-Fi/USB/SD)
 *      ├── Controller                 (board + firmware dialect it speaks)
 *      ├── MachineProfile             (axes, travel, feed limits, homing)
 *      └── tools: List<ToolHead>      (every head this machine can carry)
 *
 * The design workspace and G-code generator never see more than one
 * [MachineContext] at a time; everything above is what builds that context
 * for whichever device the operator selected.
 */
data class Device(
    val id: String,
    val name: String,
    val connection: ConnectionType,
    /** MAC address, IP:port, serial path, or empty for a plain SD/file export. */
    val address: String = "",
    val controller: Controller,
    val profile: MachineProfile,
    val tools: List<ToolHead> = emptyList(),
    val activeToolId: String? = tools.firstOrNull()?.id,
    val status: DeviceStatus = DeviceStatus.DISCONNECTED,
    /** Pinned to the top of My Devices (the star in the presets list). */
    val favorite: Boolean = false,
) {
    val activeTool: ToolHead? get() = tools.firstOrNull { it.id == activeToolId }

    fun withTool(tool: ToolHead): Device {
        val existing = tools.indexOfFirst { it.id == tool.id }
        val updated = if (existing >= 0) tools.toMutableList().also { it[existing] = tool } else tools + tool
        return copy(tools = updated, activeToolId = activeToolId ?: tool.id)
    }

    fun withActiveTool(toolId: String): Device =
        if (tools.any { it.id == toolId }) copy(activeToolId = toolId) else this

    /** Nothing to run without at least one tool and a profile that has axes. */
    fun isReadyToRun(): Boolean = activeTool != null && profile.axes.isNotEmpty()
}
