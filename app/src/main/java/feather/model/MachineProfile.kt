package feather.model

/** What kind of machine a profile describes. Drives which tools/UI make sense, nothing else. */
enum class MachineType(val displayName: String, val defaultAxes: List<Axis>) {
    CNC_ROUTER("CNC Router", listOf(Axis.X, Axis.Y, Axis.Z)),
    LASER("Laser Engraver", listOf(Axis.X, Axis.Y)),
    PLOTTER("Plotter", listOf(Axis.X, Axis.Y)),
    PCB_MILL("PCB Mill", listOf(Axis.X, Axis.Y, Axis.Z)),
    PRINTER_3D("3D Printer", listOf(Axis.X, Axis.Y, Axis.Z)),
    PICK_AND_PLACE("Pick & Place", listOf(Axis.X, Axis.Y, Axis.Z)),
    CUSTOM("Custom machine", listOf(Axis.X, Axis.Y)),
}

enum class Axis { X, Y, Z, A, B, C }

enum class LengthUnit { MM, INCH }

/**
 * Everything the generator needs to know about one axis before it trusts a
 * move to that axis: how far it can travel, how fast, and whether the
 * firmware itself will refuse an out-of-range move (soft limits).
 */
data class AxisLimits(
    val travelMm: Double,
    val stepsPerMm: Double = 80.0,
    val maxFeedMmPerMin: Double = 3_000.0,
    val maxAccelMmPerSec2: Double = 500.0,
    val softLimitEnabled: Boolean = true,
)

/**
 * A saved, reusable description of one physical machine — separate from any
 * device connection and separate from any tool mounted on it. Two [feather.link.MachineLink]
 * transports and three [Tool]s can all point at the same [MachineProfile].
 */
data class MachineProfile(
    val id: String,
    val name: String,
    val machineType: MachineType,
    val axes: Map<Axis, AxisLimits>,
    val homingEnabled: Boolean = true,
    val units: LengthUnit = LengthUnit.MM,
    /** Work coordinate system to select before a job (G54..G59), GRBL/Marlin/Smoothie all support it. */
    val coordinateSystem: String = "G54",
) {
    fun travelMm(axis: Axis): Double = axes[axis]?.travelMm ?: 0.0
    fun maxFeedMmPerMin(axis: Axis): Double = axes[axis]?.maxFeedMmPerMin ?: Double.MAX_VALUE
    fun hasAxis(axis: Axis): Boolean = axes.containsKey(axis)

    /** True if a point at [positionMm] on [axis] is inside this machine's travel, origin at 0. */
    fun isWithinTravel(axis: Axis, positionMm: Double): Boolean {
        val limit = axes[axis] ?: return true
        return positionMm >= -0.001 && positionMm <= limit.travelMm + 0.001
    }

    companion object {
        /** A reasonable starting profile so "add device" never opens on a blank, invalid form. */
        fun default(id: String, machineType: MachineType): MachineProfile {
            val axes = machineType.defaultAxes.associateWith { axis ->
                when (axis) {
                    Axis.Z -> AxisLimits(travelMm = 80.0, maxFeedMmPerMin = 800.0)
                    else -> AxisLimits(travelMm = 300.0, maxFeedMmPerMin = 3_000.0)
                }
            }
            return MachineProfile(id, machineType.displayName, machineType, axes)
        }
    }
}
