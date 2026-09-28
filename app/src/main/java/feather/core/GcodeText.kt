package feather.core

/** Text-level helpers shared by every transport. */
object GcodeText {

    /** Remove `; line` and `( inline )` comments and surrounding whitespace. */
    fun stripComments(line: String): String {
        val sb = StringBuilder(line.length)
        var inParen = false
        for (ch in line) {
            if (inParen) {
                if (ch == ')') inParen = false
                continue
            }
            when (ch) {
                '(' -> inParen = true
                ';' -> break
                else -> sb.append(ch)
            }
        }
        return sb.toString().trim()
    }

    /**
     * Lines actually worth sending to a controller: comments and blank lines
     * removed (fewer round trips, and GRBL's 80-character line limit is never
     * eaten by comment text).
     */
    fun prepareForStreaming(gcode: String): List<String> =
        gcode.lineSequence().map { stripComments(it) }.filter { it.isNotEmpty() }.toList()
}
