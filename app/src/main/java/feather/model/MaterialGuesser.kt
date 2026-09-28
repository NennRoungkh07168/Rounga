package feather.model

import feather.core.PixelOps

/**
 * A guess, not an identification: [confidence] is a rough 0..1 score from colour, texture and glare
 * only, computed on the phone with no reference data or lab measurement. It cannot see composition,
 * thickness, or grade, and it will be wrong on painted, coated, or unusually lit material. Always
 * confirm by eye and run a small test cut before committing to a real piece.
 */
data class MaterialGuess(val category: MaterialCategory, val confidence: Double, val reason: String)

/**
 * Heuristic material category guesser from a plain colour + texture + glare read of a photo: real
 * groundwork (this is the same kind of feature signal photo-sorting apps use), but explicitly not a
 * chemical, spectral, or "atomic" analysis — a camera's pixels simply don't carry that information,
 * regardless of how the image is processed. Crop or photograph the material to fill the frame with
 * a plain sample for the most useful result.
 */
object MaterialGuesser {

    fun guess(argb: IntArray, w: Int, h: Int, gray: IntArray): List<MaterialGuess> {
        if (argb.isEmpty() || gray.isEmpty()) return emptyList()
        val sat = PixelOps.averageSaturation(argb)
        val hue = PixelOps.averageHue(argb)
        val specular = PixelOps.specularHighlightRatio(argb)
        val (textureMean, textureSpread) = PixelOps.textureStats(gray, w, h)
        val brightness = gray.average()

        val scores = LinkedHashMap<MaterialCategory, Double>()
        val reasons = HashMap<MaterialCategory, MutableList<String>>()
        fun add(cat: MaterialCategory, score: Double, why: String) {
            scores[cat] = (scores[cat] ?: 0.0) + score
            if (score > 0.15) reasons.getOrPut(cat) { mutableListOf() }.add(why)
        }

        // Metal: low colour, noticeable glare, fairly smooth.
        add(MaterialCategory.METAL, (1.0 - sat).coerceIn(0.0, 1.0) * 0.5 + (specular * 6.0).coerceIn(0.0, 1.0) * 0.5, "low colour, some glare")

        // Wood: warm hue band (roughly 15-45 degrees, brown/tan), visible grain texture.
        val woodHue = hue != null && hue in 10.0..50.0
        add(MaterialCategory.WOOD, (if (woodHue) 0.55 else 0.05) + (textureSpread / 60.0).coerceIn(0.0, 0.35), "warm tone, grain texture")

        // Acrylic: fairly saturated, low texture (smooth), moderate glare from the surface sheen.
        add(MaterialCategory.ACRYLIC, sat.coerceIn(0.0, 1.0) * 0.4 + (1.0 - (textureSpread / 40.0).coerceIn(0.0, 1.0)) * 0.3 + (specular * 4.0).coerceIn(0.0, 0.3), "smooth, some sheen")

        // Paper/card: bright, low saturation, low texture.
        add(MaterialCategory.PAPER_CARD, (brightness / 255.0).coerceIn(0.0, 1.0) * 0.4 + (1.0 - sat).coerceIn(0.0, 1.0) * 0.3 + (1.0 - (textureSpread / 30.0).coerceIn(0.0, 1.0)) * 0.3, "bright and flat")

        // Fabric/leather: strong, irregular texture, mid brightness, little glare.
        val fabricTexture = (textureSpread / 50.0).coerceIn(0.0, 1.0)
        add(MaterialCategory.FABRIC, fabricTexture * 0.6 + (1.0 - specular * 6.0).coerceIn(0.0, 0.4), "irregular texture, matte")
        add(MaterialCategory.LEATHER, fabricTexture * 0.4 + (if (hue != null && hue in 15.0..40.0) 0.3 else 0.0) + (1.0 - (brightness / 255.0)).coerceIn(0.0, 0.3), "textured, warm and matte")

        val total = scores.values.sum().coerceAtLeast(0.001)
        return scores.entries
            .map { (cat, score) -> MaterialGuess(cat, (score / total).coerceIn(0.0, 1.0), reasons[cat]?.distinct()?.joinToString(", ") ?: "") }
            .sortedByDescending { it.confidence }
            .take(3)
    }
}
