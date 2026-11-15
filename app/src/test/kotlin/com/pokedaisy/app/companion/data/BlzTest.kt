package com.pokedaisy.app.companion.data

import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/** [Blz] on HeartGold's ARM9 binary and its starter app's overlay (skipped without the cart): decoded, a starter changed,
 * re-encoded to the very same size, decoded back to the change. */
class BlzTest {
    private val cart = File(File(RETAIL_ROM_DIR).parentFile, "nds/Pokemon - HeartGold Version (USA).nds")

    private fun u32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    @Test
    fun `HeartGold's code recompresses to its own size`() {
        assumeTrue("no HeartGold here", cart.isFile)
        FileSystem.SYSTEM.openReadOnly(cart.toOkioPath()).use { h ->
            val c = DsRandomizer.Cart(h)
            val header = c.read(0, 0x200)
            val arm9 = c.read(u32(header, 0x20).toLong(), u32(header, 0x2C))
            val ov = c.file(c.overlayFile(61, allowCompressed = true))
            for ((blob, at) in listOf(arm9 to 0x108514, ov to 0x1A98)) {
                val raw = Blz.decompress(blob)
                assertEquals(152, u32(raw, at))
                val changed = raw.copyOf().also { it[at] = 0xF0.toByte(); it[at + 1] = 0x01 }   // 496
                val again = Blz.compressTo(changed, blob.size)
                assertNotNull(again)
                assertEquals(blob.size, again!!.size)
                assertArrayEquals(changed, Blz.decompress(again))
            }
        }
    }
}
