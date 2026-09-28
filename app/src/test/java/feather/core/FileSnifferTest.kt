package feather.core

import org.junit.Assert.assertEquals
import org.junit.Test

class FileSnifferTest {

    @Test
    fun `a JPEG opened as a drawing is recognised as a picture`() {
        val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte(), 0, 0x10, 'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte())
        assertEquals(FileKind.IMAGE, FileSniffer.sniff(jpeg))
    }

    @Test
    fun `json and gcode and empty files`() {
        assertEquals(FileKind.FEATHER, FileSniffer.sniff("  {\"formatVersion\":1}".toByteArray()))
        assertEquals(FileKind.GCODE, FileSniffer.sniff("; header\nG21\nG0 X1".toByteArray()))
        assertEquals(FileKind.EMPTY, FileSniffer.sniff(ByteArray(0)))
        assertEquals(FileKind.OTHER, FileSniffer.sniff("hello world".toByteArray()))
    }

    @Test(expected = FeatherFileException::class)
    fun `reading a picture as a drawing gives a readable error`() {
        val png = byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte(), 13, 10, 26, 10)
        FeatherFile.read(png.inputStream())
    }
}
