package feather.model

import java.util.Locale

/** One editable/copyable number on a tool head or machine profile, with a label fit for the UI. */
data class Param(val key: String, val label: String, val unit: String, val value: Double) {
    fun display(): String {
        val v = if (value == Math.rint(value) && Math.abs(value) < 1e9) value.toLong().toString()
        else String.format(Locale.ROOT, "%.3f", value).trimEnd('0').trimEnd('.')
        return if (unit.isEmpty()) v else "$v $unit"
    }
}

/** A parameter as it would change if copied: what the target has now vs. what it would get. */
data class ParamChange(val param: Param, val currentValue: Double?, val differs: Boolean)

/**
 * The single place that knows which numbers each [ToolHead] family has. The
 * tool editor, "Copy settings" and "Apply to" all use it, so what you see in
 * the copy dialog is exactly what gets written.
 */
object ToolParams {

    fun familyName(t: ToolHead): String = when (t) {
        is ToolHead.Spindle -> "Spindle"
        is ToolHead.Laser -> "Laser"
        is ToolHead.Pen -> "Pen"
        is ToolHead.Drill -> "Drill"
        is ToolHead.PcbCutter -> "PCB cutter"
        is ToolHead.Knife -> "Knife"
        is ToolHead.Extruder -> "Extruder"
        is ToolHead.Custom -> "Custom"
    }

    /** Copy/apply only makes sense between heads of the same kind (laser to laser, not laser to pen). */
    fun sameFamily(a: ToolHead, b: ToolHead): Boolean = a::class == b::class

    fun rows(t: ToolHead): List<Param> = when (t) {
        is ToolHead.Spindle -> listOf(
            Param("minRpm", "Min speed", "RPM", t.minRpm.toDouble()),
            Param("maxRpm", "Max speed", "RPM", t.maxRpm.toDouble()),
            Param("defaultRpm", "Run speed", "RPM", t.defaultRpm.toDouble()),
            Param("toolDiameterMm", "Tool diameter", "mm", t.toolDiameterMm),
            Param("maxCutDepthMm", "Max cut depth", "mm", t.maxCutDepthMm),
            Param("passes", "Passes", "", t.passes.toDouble()),
        )
        is ToolHead.Laser -> listOf(
            Param("maxPowerWatts", "Laser power", "W", t.maxPowerWatts),
            Param("defaultPowerPercent", "Power", "%", t.defaultPowerPercent.toDouble()),
            Param("pwmMax", "PWM max (S for 100%)", "", t.pwmMax.toDouble()),
            Param("engraveDepthMm", "Engrave depth", "mm", t.engraveDepthMm),
            Param("passes", "Passes", "", t.passes.toDouble()),
        )
        is ToolHead.Pen -> listOf(
            Param("liftHeightMm", "Pen up height", "mm", t.liftHeightMm),
            Param("downHeightMm", "Pen down height", "mm", t.downHeightMm),
        )
        is ToolHead.Drill -> listOf(
            Param("diameterMm", "Drill diameter", "mm", t.diameterMm),
            Param("plungeRateMmPerMin", "Plunge rate", "mm/min", t.plungeRateMmPerMin),
        )
        is ToolHead.PcbCutter -> listOf(
            Param("diameterMm", "Cutter diameter", "mm", t.diameterMm),
            Param("maxCutDepthMm", "Max cut depth", "mm", t.maxCutDepthMm),
            Param("spindleRpm", "Spindle speed", "RPM", t.spindleRpm.toDouble()),
        )
        is ToolHead.Knife -> listOf(
            Param("bladeOffsetMm", "Blade offset", "mm", t.bladeOffsetMm),
            Param("downForcePercent", "Down force", "%", t.downForcePercent.toDouble()),
        )
        is ToolHead.Extruder -> listOf(
            Param("nozzleDiameterMm", "Nozzle diameter", "mm", t.nozzleDiameterMm),
            Param("filamentDiameterMm", "Filament diameter", "mm", t.filamentDiameterMm),
            Param("targetTempC", "Nozzle temperature", "°C", t.targetTempC.toDouble()),
            Param("bedTempC", "Bed temperature", "°C", t.bedTempC.toDouble()),
        )
        is ToolHead.Custom -> t.params.map { (k, v) -> Param(k, k, "", v) }
    }

    /** A copy of [t] with one parameter replaced. Unknown keys return [t] unchanged. */
    fun withValue(t: ToolHead, key: String, v: Double): ToolHead {
        val i = v.toInt()
        return when (t) {
            is ToolHead.Spindle -> when (key) {
                "minRpm" -> t.copy(minRpm = i)
                "maxRpm" -> t.copy(maxRpm = i)
                "defaultRpm" -> t.copy(defaultRpm = i)
                "toolDiameterMm" -> t.copy(toolDiameterMm = v)
                "maxCutDepthMm" -> t.copy(maxCutDepthMm = v)
                "passes" -> t.copy(passes = maxOf(1, i))
                else -> t
            }
            is ToolHead.Laser -> when (key) {
                "maxPowerWatts" -> t.copy(maxPowerWatts = v)
                "defaultPowerPercent" -> t.copy(defaultPowerPercent = i.coerceIn(0, 100))
                "pwmMax" -> t.copy(pwmMax = maxOf(1, i))
                "engraveDepthMm" -> t.copy(engraveDepthMm = v)
                "passes" -> t.copy(passes = maxOf(1, i))
                else -> t
            }
            is ToolHead.Pen -> when (key) {
                "liftHeightMm" -> t.copy(liftHeightMm = v)
                "downHeightMm" -> t.copy(downHeightMm = v)
                else -> t
            }
            is ToolHead.Drill -> when (key) {
                "diameterMm" -> t.copy(diameterMm = v)
                "plungeRateMmPerMin" -> t.copy(plungeRateMmPerMin = v)
                else -> t
            }
            is ToolHead.PcbCutter -> when (key) {
                "diameterMm" -> t.copy(diameterMm = v)
                "maxCutDepthMm" -> t.copy(maxCutDepthMm = v)
                "spindleRpm" -> t.copy(spindleRpm = i)
                else -> t
            }
            is ToolHead.Knife -> when (key) {
                "bladeOffsetMm" -> t.copy(bladeOffsetMm = v)
                "downForcePercent" -> t.copy(downForcePercent = i.coerceIn(0, 100))
                else -> t
            }
            is ToolHead.Extruder -> when (key) {
                "nozzleDiameterMm" -> t.copy(nozzleDiameterMm = v)
                "filamentDiameterMm" -> t.copy(filamentDiameterMm = v)
                "targetTempC" -> t.copy(targetTempC = i)
                "bedTempC" -> t.copy(bedTempC = i)
                else -> t
            }
            is ToolHead.Custom -> t.copy(params = t.params + (key to v))
        }
    }

    fun withName(t: ToolHead, name: String): ToolHead = when (t) {
        is ToolHead.Spindle -> t.copy(name = name)
        is ToolHead.Laser -> t.copy(name = name)
        is ToolHead.Pen -> t.copy(name = name)
        is ToolHead.Drill -> t.copy(name = name)
        is ToolHead.PcbCutter -> t.copy(name = name)
        is ToolHead.Knife -> t.copy(name = name)
        is ToolHead.Extruder -> t.copy(name = name)
        is ToolHead.Custom -> t.copy(name = name)
    }

    /** What "Apply to [target]" would change, row by row, for the review list. Empty when the families differ. */
    fun preview(source: ToolHead, target: ToolHead): List<ParamChange> {
        if (!sameFamily(source, target)) return emptyList()
        val current = rows(target).associate { it.key to it.value }
        return rows(source).map { p ->
            val now = current[p.key]
            ParamChange(p, now, now == null || now != p.value)
        }
    }

    /** Copy only the ticked [keys] from [source] onto [target]; the target keeps its own id and name. */
    fun apply(source: ToolHead, target: ToolHead, keys: Set<String>): ToolHead {
        if (!sameFamily(source, target)) return target
        val values = rows(source).associate { it.key to it.value }
        var result = target
        for (k in keys) {
            val v = values[k] ?: continue
            result = withValue(result, k, v)
        }
        return result
    }

    /** Names offered by "Add tool". */
    val families: List<String> = listOf("Spindle", "Laser", "Pen", "Drill", "PCB cutter", "Knife", "Extruder")

    fun newOfFamily(family: String, id: String, name: String): ToolHead = when (family) {
        "Spindle" -> ToolHead.Spindle(id, name)
        "Laser" -> ToolHead.Laser(id, name)
        "Pen" -> ToolHead.Pen(id, name)
        "Drill" -> ToolHead.Drill(id, name)
        "PCB cutter" -> ToolHead.PcbCutter(id, name)
        "Knife" -> ToolHead.Knife(id, name)
        "Extruder" -> ToolHead.Extruder(id, name)
        else -> ToolHead.Custom(id, name)
    }

    fun newTool(type: MachineType, id: String): ToolHead = when (type) {
        MachineType.CNC_ROUTER -> ToolHead.Spindle(id)
        MachineType.LASER -> ToolHead.Laser(id)
        MachineType.PLOTTER -> ToolHead.Pen(id)
        MachineType.PCB_MILL -> ToolHead.PcbCutter(id)
        MachineType.PRINTER_3D -> ToolHead.Extruder(id)
        MachineType.PICK_AND_PLACE, MachineType.CUSTOM -> ToolHead.Custom(id, "Custom tool")
    }
}

/** Same idea for the machine profile: numbers per axis plus the homing switch. */
object ProfileParams {

    fun rows(p: MachineProfile): List<Param> {
        val out = ArrayList<Param>()
        for (axis in Axis.values()) {
            val a = p.axes[axis] ?: continue
            out += Param("${axis.name}.travel", "${axis.name} travel", "mm", a.travelMm)
            out += Param("${axis.name}.steps", "${axis.name} steps/mm", "", a.stepsPerMm)
            out += Param("${axis.name}.feed", "${axis.name} max feed", "mm/min", a.maxFeedMmPerMin)
            out += Param("${axis.name}.accel", "${axis.name} acceleration", "mm/s²", a.maxAccelMmPerSec2)
            out += Param("${axis.name}.soft", "${axis.name} soft limit (1=on)", "", if (a.softLimitEnabled) 1.0 else 0.0)
        }
        out += Param("homing", "Homing (1=on)", "", if (p.homingEnabled) 1.0 else 0.0)
        return out
    }

    fun withValue(p: MachineProfile, key: String, v: Double): MachineProfile {
        if (key == "homing") return p.copy(homingEnabled = v >= 0.5)
        val axis = Axis.values().firstOrNull { key.startsWith(it.name + ".") } ?: return p
        val current = p.axes[axis] ?: return p
        val updated = when (key.substringAfter('.')) {
            "travel" -> current.copy(travelMm = v)
            "steps" -> current.copy(stepsPerMm = v)
            "feed" -> current.copy(maxFeedMmPerMin = v)
            "accel" -> current.copy(maxAccelMmPerSec2 = v)
            "soft" -> current.copy(softLimitEnabled = v >= 0.5)
            else -> current
        }
        return p.copy(axes = p.axes + (axis to updated))
    }

    fun preview(source: MachineProfile, target: MachineProfile): List<ParamChange> {
        val current = rows(target).associate { it.key to it.value }
        return rows(source).mapNotNull { p ->
            val now = current[p.key] // axes the target lacks are skipped: nothing to copy them onto
            if (now == null) null else ParamChange(p, now, now != p.value)
        }
    }

    fun apply(source: MachineProfile, target: MachineProfile, keys: Set<String>): MachineProfile {
        val values = rows(source).associate { it.key to it.value }
        var result = target
        for (k in keys) {
            val v = values[k] ?: continue
            result = withValue(result, k, v)
        }
        return result
    }
}
