package feather.model

/** What a material is made of, for grouping and icon/colour only. */
enum class MaterialCategory(val label: String) {
    WOOD("Wood"), ACRYLIC("Acrylic"), MDF_HARDBOARD("MDF / hardboard"), PAPER_CARD("Paper / card"),
    LEATHER("Leather"), FABRIC("Fabric"), FOAM_RUBBER("Foam / rubber"), METAL("Metal"), OTHER("Other"),
}

/**
 * A named material and thickness with starting cut settings. These are sensible starting points
 * gathered from common hobby-laser/CNC practice, not a guarantee for a specific machine or lot of
 * material — always run a small test cut on scrap first, especially on a new sheet or a new laser tube.
 */
data class MaterialPreset(
    val id: String,
    val name: String,
    val category: MaterialCategory,
    val thicknessMm: Double,
    /** True for a laser (uses the laser fields below), false for a rotating tool (uses the CNC fields). */
    val forLaser: Boolean,
    val laserPowerPercent: Int = 60,
    val laserSpeedMmPerMin: Double = 300.0,
    val laserPasses: Int = 1,
    val cutFeedMmPerMin: Double = 800.0,
    val plungeRateMmPerMin: Double = 200.0,
    val stepDownMm: Double = 1.0,
    val spindleRpm: Int = 12_000,
    val notes: String = "",
    val builtIn: Boolean = false,
) {
    /** One line for a list row. */
    fun summary(): String = if (forLaser) {
        "$laserPowerPercent% power, ${fmt(laserSpeedMmPerMin)} mm/min, ${laserPasses}x pass"
    } else {
        "${fmt(cutFeedMmPerMin)} mm/min feed, ${fmt(stepDownMm)} mm/pass, ${spindleRpm} RPM"
    }

    private fun fmt(v: Double) = if (v == Math.rint(v)) v.toLong().toString() else v.toString()
}

/** The built-in starter library. Values are conservative starting points for a small hobby machine. */
object MaterialPresets {

    fun builtIn(): List<MaterialPreset> = listOf(
        laser("Plywood", MaterialCategory.WOOD, 3.0, power = 70, speed = 240.0, passes = 2, notes = "Test cut: burn marks are normal on the underside."),
        laser("Basswood", MaterialCategory.WOOD, 4.0, power = 65, speed = 200.0, passes = 2),
        laser("Balsa", MaterialCategory.WOOD, 3.0, power = 35, speed = 400.0, passes = 1),
        laser("MDF", MaterialCategory.MDF_HARDBOARD, 3.0, power = 75, speed = 200.0, passes = 2, notes = "MDF chars easily; keep air assist on."),
        laser("Acrylic (cast)", MaterialCategory.ACRYLIC, 3.0, power = 80, speed = 180.0, passes = 1, notes = "Cast acrylic cuts cleaner than extruded."),
        laser("Acrylic (cast)", MaterialCategory.ACRYLIC, 5.0, power = 90, speed = 100.0, passes = 2),
        laser("Cardboard", MaterialCategory.PAPER_CARD, 2.0, power = 30, speed = 400.0, passes = 1),
        laser("Card stock", MaterialCategory.PAPER_CARD, 0.3, power = 15, speed = 600.0, passes = 1),
        laser("Leather (veg-tan)", MaterialCategory.LEATHER, 2.0, power = 45, speed = 250.0, passes = 1, notes = "Ventilate well; leather fumes are unpleasant."),
        laser("Felt", MaterialCategory.FABRIC, 3.0, power = 25, speed = 350.0, passes = 1),
        laser("EVA foam", MaterialCategory.FOAM_RUBBER, 5.0, power = 40, speed = 220.0, passes = 1, notes = "Foam fumes can be harsh; ventilate well."),
        cnc("Plywood", MaterialCategory.WOOD, 6.0, feed = 900.0, plunge = 250.0, step = 2.0, rpm = 12_000),
        cnc("MDF", MaterialCategory.MDF_HARDBOARD, 6.0, feed = 1000.0, plunge = 250.0, step = 2.0, rpm = 12_000, notes = "MDF dust is fine; use dust collection."),
        cnc("Acrylic (cast)", MaterialCategory.ACRYLIC, 5.0, feed = 600.0, plunge = 150.0, step = 1.0, rpm = 16_000, notes = "Single-flute upcut bit, slower feed avoids melting."),
        cnc("Aluminium (light cuts)", MaterialCategory.METAL, 3.0, feed = 250.0, plunge = 60.0, step = 0.3, rpm = 10_000, notes = "Use cutting fluid/lubricant and take shallow passes."),
        cnc("FR4 / PCB", MaterialCategory.OTHER, 1.6, feed = 300.0, plunge = 80.0, step = 0.15, rpm = 10_000, notes = "Wear a mask: PCB dust should not be inhaled."),
    )

    private fun laser(
        name: String, cat: MaterialCategory, thickness: Double,
        power: Int, speed: Double, passes: Int, notes: String = "",
    ) = MaterialPreset(
        id = "builtin-${name.lowercase().replace(" ", "-").replace("(", "").replace(")", "")}-${thickness}mm-laser",
        name = name, category = cat, thicknessMm = thickness, forLaser = true,
        laserPowerPercent = power, laserSpeedMmPerMin = speed, laserPasses = passes, notes = notes, builtIn = true,
    )

    private fun cnc(
        name: String, cat: MaterialCategory, thickness: Double,
        feed: Double, plunge: Double, step: Double, rpm: Int, notes: String = "",
    ) = MaterialPreset(
        id = "builtin-${name.lowercase().replace(" ", "-").replace("(", "").replace(")", "")}-${thickness}mm-cnc",
        name = name, category = cat, thicknessMm = thickness, forLaser = false,
        cutFeedMmPerMin = feed, plungeRateMmPerMin = plunge, stepDownMm = step, spindleRpm = rpm, notes = notes, builtIn = true,
    )
}
