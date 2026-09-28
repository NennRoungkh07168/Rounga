package feather.core

/** What a picked file really is, judged by its first bytes (file names and MIME types lie). */
enum class FileKind { FEATHER, IMAGE, GCODE, EMPTY, OTHER }

object FileSniffer {

    fun sniff(bytes: ByteArray): FileKind {
        if (bytes.isEmpty()) return FileKind.EMPTY
        fun b(i: Int): Int = if (i < bytes.size) bytes[i].toInt() and 0xFF else -1
        fun c(i: Int, ch: Char): Boolean = b(i) == ch.code

        if (b(0) == 0xFF && b(1) == 0xD8) return FileKind.IMAGE                                   // JPEG
        if (b(0) == 0x89 && c(1, 'P') && c(2, 'N') && c(3, 'G')) return FileKind.IMAGE             // PNG
        if (c(0, 'G') && c(1, 'I') && c(2, 'F') && c(3, '8')) return FileKind.IMAGE                // GIF
        if (c(0, 'B') && c(1, 'M')) return FileKind.IMAGE                                          // BMP
        if (c(0, 'R') && c(1, 'I') && c(2, 'F') && c(3, 'F') && c(8, 'W') && c(9, 'E')) return FileKind.IMAGE // WebP
        if (c(4, 'f') && c(5, 't') && c(6, 'y') && c(7, 'p')) return FileKind.IMAGE                // HEIC / AVIF

        val headLen = minOf(bytes.size, 512)
        val text = String(bytes, 0, headLen, Charsets.UTF_8).trimStart('\uFEFF', ' ', '\t', '\r', '\n')
        if (text.startsWith("{")) return FileKind.FEATHER
        return if (looksLikeGcode(text)) FileKind.GCODE else FileKind.OTHER
    }

    /** True when the first meaningful line is a G/M/T word, `$` setting, or a comment typical of G-code. */
    fun looksLikeGcode(head: String): Boolean {
        for (raw in head.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty()) continue
            if (line.startsWith(";") || line.startsWith("(") || line.startsWith("%")) continue
            val first = line[0].uppercaseChar()
            return (first == 'G' || first == 'M' || first == 'T' || first == '$') && line.length > 1 &&
                (line[1].isDigit() || line[1] == '$')
        }
        return false
    }
}
