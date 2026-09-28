package feather.model

import feather.core.CutJob
import feather.core.Geometry
import java.util.Locale

/**
 * Everything [feather.core.GCodeGenerator] needs about *which machine* a job
 * is headed for, collapsed to one immutable snapshot so the generator never
 * has to reach back into [Device], [DeviceStore], or Compose state.
 *
 * Build one with [Device.toMachineContext] right before "Save & Send" / "Export".
 */
data class MachineContext(
    val profile: MachineProfile,
    val tool: ToolHead,
    val dialect: GcodeDialect,
) {
    /** Human-readable problems; empty means safe to run on this machine. */
    fun validate(job: CutJob): List<String> {
        val errors = ArrayList<String>()
        val bounds = Geometry.bounds(job.shapes)
        if (bounds != null) {
            val minXmm = bounds.minX / 1000.0
            val maxXmm = bounds.maxX / 1000.0
            val minYmm = bounds.minY / 1000.0
            val maxYmm = bounds.maxY / 1000.0
            val travelX = profile.travelMm(Axis.X)
            val travelY = profile.travelMm(Axis.Y)
            if (profile.hasAxis(Axis.X)) {
                if (minXmm < -0.001) {
                    errors += "The design reaches ${fmt(-minXmm)} mm left of the machine origin (X 0). Move it right."
                } else if (maxXmm > travelX + 0.001) {
                    errors += "The design reaches X ${fmt(maxXmm)} mm but ${profile.name} only travels ${fmt(travelX)} mm."
                }
            }
            if (profile.hasAxis(Axis.Y)) {
                if (minYmm < -0.001) {
                    errors += "The design reaches ${fmt(-minYmm)} mm below the machine origin (Y 0). Move it up."
                } else if (maxYmm > travelY + 0.001) {
                    errors += "The design reaches Y ${fmt(maxYmm)} mm but ${profile.name} only travels ${fmt(travelY)} mm."
                }
            }
        }
        val depthMm = job.settings.totalDepthUm.raw / 1000.0
        if (profile.hasAxis(Axis.Z) && !profile.isWithinTravel(Axis.Z, depthMm)) {
            errors += "Cut depth ${fmt(depthMm)} mm exceeds this machine's Z travel of " +
                "${fmt(profile.travelMm(Axis.Z))} mm."
        }
        val maxFeed = profile.maxFeedMmPerMin(Axis.X)
        if (job.settings.feedRateMmPerMin > maxFeed) {
            errors += "Feed rate ${fmt(job.settings.feedRateMmPerMin)} mm/min exceeds this machine's " +
                "max of ${fmt(maxFeed)} mm/min."
        }
        if (tool is ToolHead.Spindle && job.settings.totalDepthUm.raw / 1000.0 > tool.maxCutDepthMm) {
            errors += "Cut depth exceeds ${tool.name}'s rated max of ${fmt(tool.maxCutDepthMm)} mm."
        }
        return errors
    }

    /** G-code that turns the active tool on, at its configured power/speed. */
    fun toolOnGcode(): String = when (val t = tool) {
        is ToolHead.Spindle -> "M3 S${t.defaultRpm}"
        is ToolHead.Laser -> "M3 S${(t.defaultPowerPercent / 100.0 * t.pwmMax).toInt()}"
        is ToolHead.PcbCutter -> "M3 S${t.spindleRpm}"
        is ToolHead.Knife -> "M3 S${t.downForcePercent}"
        is ToolHead.Pen -> "G1 Z${fmt(t.downHeightMm)} ; pen down"
        is ToolHead.Drill -> "M3"
        is ToolHead.Extruder -> "M104 S${t.targetTempC}\nM109 S${t.targetTempC} ; wait for nozzle"
        is ToolHead.Custom -> t.onGcode
    }

    /** G-code that turns the active tool off / retracts it, dialect-agnostic. */
    fun toolOffGcode(): String = when (val t = tool) {
        is ToolHead.Pen -> "G1 Z${fmt(t.liftHeightMm)} ; pen up"
        is ToolHead.Extruder -> "M104 S0 ; nozzle off"
        is ToolHead.Custom -> t.offGcode
        else -> "M5"
    }

    /** Program-end line: GRBL/Smoothie and Marlin both accept M30, kept for every dialect. */
    fun programEndGcode(): String = "M30"

    private fun fmt(mm: Double): String =
        if (mm == Math.rint(mm)) mm.toLong().toString() else String.format(Locale.ROOT, "%.2f", mm)
}

fun Device.toMachineContext(): MachineContext? {
    val tool = activeTool ?: return null
    return MachineContext(profile, tool, controller.dialect)
}
