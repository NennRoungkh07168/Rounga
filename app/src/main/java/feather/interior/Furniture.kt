package feather.interior

/** One box of a piece of furniture, in the item's local frame (centre of the footprint = 0,0). */
data class Part(
    val lx: Double,
    val lz: Double,
    /** Height of the bottom face above the item's elevation. */
    val y0: Double,
    val w: Double,
    val d: Double,
    val h: Double,
    /** Brightness multiplier, so the parts of one piece are distinguishable. */
    val shade: Double = 1.0,
    val colorOverride: Int? = null,
)

/** Procedural furniture: every type is a handful of boxes sized from the item's own width, depth and height. */
object Furniture {

    private val DARK = 0xFF2B2D33.toInt()
    private val LEAF = 0xFF52B788.toInt()
    private val LEAF_LIGHT = 0xFF74C69D.toInt()
    private val POT = 0xFFB56576.toInt()
    private val GLASS = 0xFFB3E5FC.toInt()
    private val BRASS = 0xFFD4A017.toInt()

    fun partsOf(item: RoomItem): List<Part> {
        val w = item.widthCm
        val d = item.depthCm
        val h = item.heightCm
        return when (item.type) {
            ItemType.SOFA, ItemType.ARMCHAIR -> sofa(w, d, h)
            ItemType.BED -> bed(w, d, h)
            ItemType.DINING_TABLE, ItemType.COFFEE_TABLE -> table(w, d, h)
            ItemType.CHAIR -> chair(w, d, h)
            ItemType.DESK -> desk(w, d, h)
            ItemType.WARDROBE -> wardrobe(w, d, h)
            ItemType.SHELF -> shelf(w, d, h)
            ItemType.TV_UNIT -> tvUnit(w, d, h)
            ItemType.RUG -> listOf(Part(0.0, 0.0, 0.0, w, d, h))
            ItemType.PLANT -> plant(w, d, h)
            ItemType.LAMP -> lamp(w, d, h)
            ItemType.DOOR -> door(w, d, h)
            ItemType.WINDOW -> window(w, d, h)
        }
    }

    private fun sofa(w: Double, d: Double, h: Double) = listOf(
        Part(0.0, 0.0, 0.0, w, d, 0.5 * h, 0.9),
        Part(0.0, -d / 2 + 0.15 * d, 0.5 * h, w, 0.3 * d, 0.5 * h, 1.0),
        Part(-w / 2 + 0.075 * w, 0.15 * d, 0.5 * h, 0.15 * w, 0.7 * d, 0.2 * h, 0.85),
        Part(w / 2 - 0.075 * w, 0.15 * d, 0.5 * h, 0.15 * w, 0.7 * d, 0.2 * h, 0.85),
        Part(0.0, 0.15 * d, 0.5 * h, 0.7 * w, 0.7 * d, 0.08 * h, 1.12),
    )

    private fun bed(w: Double, d: Double, h: Double) = listOf(
        Part(0.0, 0.0, 0.0, w, d, 0.3 * h, 0.8),
        Part(0.0, 0.03 * d, 0.3 * h, w * 0.94, d * 0.9, 0.22 * h, 1.12),
        Part(0.0, -d / 2 + 0.02 * d, 0.0, w, 0.04 * d, h, 0.75),
        Part(-0.22 * w, -d / 2 + 0.17 * d, 0.52 * h, 0.36 * w, 0.16 * d, 0.08 * h, 1.25),
        Part(0.22 * w, -d / 2 + 0.17 * d, 0.52 * h, 0.36 * w, 0.16 * d, 0.08 * h, 1.25),
    )

    private fun table(w: Double, d: Double, h: Double): List<Part> {
        val top = 4.0
        val leg = 5.0
        val lh = h - top
        return listOf(
            Part(0.0, 0.0, lh, w, d, top, 1.05),
            Part(-(w / 2 - leg), -(d / 2 - leg), 0.0, leg, leg, lh, 0.8),
            Part(w / 2 - leg, -(d / 2 - leg), 0.0, leg, leg, lh, 0.8),
            Part(-(w / 2 - leg), d / 2 - leg, 0.0, leg, leg, lh, 0.8),
            Part(w / 2 - leg, d / 2 - leg, 0.0, leg, leg, lh, 0.8),
        )
    }

    private fun chair(w: Double, d: Double, h: Double): List<Part> {
        val seatTop = 0.5 * h
        val leg = 3.5
        return listOf(
            Part(0.0, 0.0, seatTop - 4, w, d, 4.0, 1.05),
            Part(-(w / 2 - leg), -(d / 2 - leg), 0.0, leg, leg, seatTop - 4, 0.8),
            Part(w / 2 - leg, -(d / 2 - leg), 0.0, leg, leg, seatTop - 4, 0.8),
            Part(-(w / 2 - leg), d / 2 - leg, 0.0, leg, leg, seatTop - 4, 0.8),
            Part(w / 2 - leg, d / 2 - leg, 0.0, leg, leg, seatTop - 4, 0.8),
            Part(0.0, -d / 2 + 1.5, seatTop, w, 3.0, h - seatTop, 0.95),
        )
    }

    private fun desk(w: Double, d: Double, h: Double) = listOf(
        Part(0.0, 0.0, h - 3, w, d, 3.0, 1.05),
        Part(-(w / 2 - 2), 0.0, 0.0, 4.0, d, h - 3, 0.85),
        Part(w / 2 - 2, 0.0, 0.0, 4.0, d, h - 3, 0.85),
        Part(0.0, -d / 2 + 1.5, 25.0, (w - 8).coerceAtLeast(1.0), 3.0, (h - 28).coerceAtLeast(1.0), 0.8),
    )

    private fun wardrobe(w: Double, d: Double, h: Double) = listOf(
        Part(0.0, 0.0, 0.0, w, d, h, 1.0),
        Part(0.0, d / 2 + 0.2, 4.0, 1.0, 0.6, h - 8, 0.45),
        Part(-0.06 * w, d / 2 + 1.0, 0.5 * h, 2.5, 2.0, 14.0, 0.55, BRASS),
        Part(0.06 * w, d / 2 + 1.0, 0.5 * h, 2.5, 2.0, 14.0, 0.55, BRASS),
    )

    private fun shelf(w: Double, d: Double, h: Double): List<Part> {
        val out = ArrayList<Part>()
        out += Part(-(w / 2 - 1.5), 0.0, 0.0, 3.0, d, h, 1.0)
        out += Part(w / 2 - 1.5, 0.0, 0.0, 3.0, d, h, 1.0)
        out += Part(0.0, -d / 2 + 0.75, 0.0, w, 1.5, h, 0.8)
        for (i in 0..4) out += Part(0.0, 0.0, i * 0.25 * (h - 3), w - 6, d, 3.0, 1.06)
        return out
    }

    private fun tvUnit(w: Double, d: Double, h: Double): List<Part> {
        val tvW = 0.7 * w
        return listOf(
            Part(0.0, 0.0, 0.0, w, d, h, 1.0),
            Part(0.0, -d / 2 + 8, h, 18.0, 6.0, 6.0, 0.6, DARK),
            Part(0.0, -d / 2 + 8, h + 6, tvW, 4.0, tvW * 0.5625, 0.6, DARK),
        )
    }

    private fun plant(w: Double, d: Double, h: Double) = listOf(
        Part(0.0, 0.0, 0.0, 0.6 * w, 0.6 * d, 0.25 * h, 1.0, POT),
        Part(0.0, 0.0, 0.25 * h, 0.9 * w, 0.9 * d, 0.35 * h, 1.0, LEAF),
        Part(0.0, 0.0, 0.6 * h, 0.65 * w, 0.65 * d, 0.4 * h, 1.0, LEAF_LIGHT),
    )

    private fun lamp(w: Double, d: Double, h: Double) = listOf(
        Part(0.0, 0.0, 0.0, 0.6 * w, 0.6 * d, 3.0, 0.5, DARK),
        Part(0.0, 0.0, 3.0, 3.0, 3.0, 0.7 * h, 0.5, DARK),
        Part(0.0, 0.0, 0.72 * h, w, d, 0.28 * h, 1.1),
    )

    private fun door(w: Double, d: Double, h: Double) = listOf(
        Part(0.0, 0.0, 0.0, w, d, h, 0.9),
        Part(0.0, d / 2 + 0.3, 5.0, w - 10, 0.6, h - 10, 1.05),
        Part(w / 2 - 14, d / 2 + 1.5, 100.0, 5.0, 3.0, 5.0, 0.6, BRASS),
    )

    private fun window(w: Double, d: Double, h: Double) = listOf(
        Part(0.0, 0.0, 0.0, w, d, h, 0.85, 0xFFEDEDED.toInt()),
        Part(0.0, d / 2 * 0.4, 4.0, w - 8, d * 0.5, h - 8, 1.0, GLASS),
        Part(0.0, d / 2 + 0.6, 4.0, 2.0, 0.8, h - 8, 0.9, 0xFFEDEDED.toInt()),
    )
}
