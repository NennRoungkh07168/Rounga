package feather.model

/**
 * One physical tool head, with the parameters that are specific to *that*
 * tool rather than to the machine it's bolted on. A [MachineProfile] doesn't
 * know or care which of these is mounted; a [Device] holds a list of them and
 * an active selection so switching tool is a UI action, not a re-flash.
 *
 * Named `ToolHead` rather than `Tool` on purpose: `feather.link.Tool` already
 * names the drawing-tool enum (pan/line/rect/...), and the two must never collide.
 */
sealed class ToolHead {
    abstract val id: String
    abstract val name: String

    data class Spindle(
        override val id: String,
        override val name: String = "Spindle",
        val minRpm: Int = 5_000,
        val maxRpm: Int = 24_000,
        val defaultRpm: Int = 12_000,
        val toolDiameterMm: Double = 3.175,
        val maxCutDepthMm: Double = 20.0,
        val passes: Int = 1,
    ) : ToolHead()

    data class Laser(
        override val id: String,
        override val name: String = "Laser",
        val maxPowerWatts: Double = 10.0,
        val defaultPowerPercent: Int = 60,
        /** S-value the firmware treats as 100% power (GRBL $30, Marlin M3 S-range). */
        val pwmMax: Int = 1_000,
        val engraveDepthMm: Double = 0.0,
        val passes: Int = 1,
    ) : ToolHead()

    data class Pen(
        override val id: String,
        override val name: String = "Pen",
        val liftHeightMm: Double = 5.0,
        val downHeightMm: Double = 0.0,
    ) : ToolHead()

    data class Drill(
        override val id: String,
        override val name: String = "Drill",
        val diameterMm: Double = 0.8,
        val plungeRateMmPerMin: Double = 100.0,
    ) : ToolHead()

    data class PcbCutter(
        override val id: String,
        override val name: String = "PCB Cutter",
        val diameterMm: Double = 0.2,
        val maxCutDepthMm: Double = 1.6,
        val spindleRpm: Int = 10_000,
    ) : ToolHead()

    data class Knife(
        override val id: String,
        override val name: String = "Knife",
        val bladeOffsetMm: Double = 0.5,
        val downForcePercent: Int = 50,
    ) : ToolHead()

    data class Extruder(
        override val id: String,
        override val name: String = "Extruder",
        val nozzleDiameterMm: Double = 0.4,
        val filamentDiameterMm: Double = 1.75,
        val targetTempC: Int = 200,
        val bedTempC: Int = 60,
    ) : ToolHead()

    /** Escape hatch for a tool this model hasn't named yet: raw on/off G-code plus free-form params. */
    data class Custom(
        override val id: String,
        override val name: String,
        val params: Map<String, Double> = emptyMap(),
        val onGcode: String = "M3",
        val offGcode: String = "M5",
    ) : ToolHead()
}
