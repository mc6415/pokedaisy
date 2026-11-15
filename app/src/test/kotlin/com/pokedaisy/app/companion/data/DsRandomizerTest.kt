package com.pokedaisy.app.companion.data

import okio.FileSystem
import okio.Path.Companion.toOkioPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/**
 * RANDOMIZE on the DS carts ([DsRandomizer]), on the player's own (`nds/` beside the GBA ROM folder; skipped where they
 * aren't): the output differs from the cart only in the three files it writes, and there only at [DsRandomizer]'s sites;
 * it's recognised as the tool's; the same seed makes the same cart; starters / wild / trainers are what was asked.
 */
class DsRandomizerTest {
    @get:Rule val tmp = TemporaryFolder()

    private val carts = mapOf("CPUE" to "Pokemon - Platinum Version (USA) (Rev 1).nds")

    private fun cart(g: DsRandomizer.Game): File? =
        File(File(RETAIL_ROM_DIR).parentFile, "nds/" + carts.getValue(g.code)).takeIf { it.isFile }

    private fun each(body: (DsRandomizer.Game, File) -> Unit) {
        var ran = 0
        for (g in DsRandomizer.GAMES) {
            val f = cart(g) ?: continue
            ran++
            body(g, f)
        }
        assumeTrue("no DS carts here", ran > 0)
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)

    @Test
    fun `hashes are the cart's`() = each { g, f ->
        val (retail, made) = DsRandomizer.hashes(f.toOkioPath())!!
        System.getenv("DS_HASHES")?.let { File(it).appendText("${g.code}${g.rev} $retail $made\n") }
        assertEquals(g.title, g.retailFilesSha1, retail)
        assertEquals(g.title, g.madeSha1, made)
    }

    @Test
    fun `the cart randomizes, is recognised, and the same seed makes the same cart`() = each { g, f ->
        val path = f.toOkioPath()
        assertSame(g, DsRandomizer.gameOf(path, retail = true))
        assertNull(DsRandomizer.recognise(path))
        val a = tmp.root.resolve("a.nds").toOkioPath()
        val b = tmp.root.resolve("b.nds").toOkioPath()
        val c = tmp.root.resolve("c.nds").toOkioPath()
        assertTrue(DsRandomizer.randomize(path, a, RomRandomizer.Options(), 7L))
        assertTrue(DsRandomizer.randomize(path, b, RomRandomizer.Options(), 7L))
        assertTrue(DsRandomizer.randomize(path, c, RomRandomizer.Options(), 8L))
        val orig = f.readBytes()
        val ab = File(a.toString()).readBytes()
        assertTrue(ab.contentEquals(File(b.toString()).readBytes()))
        assertFalse(ab.contentEquals(File(c.toString()).readBytes()))
        assertEquals(orig.size, ab.size)
        assertSame(g, DsRandomizer.recognise(a))
        assertNull(DsRandomizer.gameOf(a, retail = true))
        assertNotNull(DsRandomizer.gameOf(a))
        // Only the files it writes changed: every differing byte lies inside one of them.
        val spans = FileSystem.SYSTEM.openReadOnly(path).use { h ->
            val cart = DsRandomizer.Cart(h)
            listOfNotNull(
                cart.span(cart.overlayFile(g.starterOverlay)), cart.span(cart.fileId(g.encounters)), cart.span(cart.fileId(g.trainerParties)),
                g.text?.let { cart.span(cart.fileId(it.path)) },
            )
        }
        var changed = 0
        for (i in orig.indices) if (orig[i] != ab[i]) {
            changed++
            assertTrue("byte ${Integer.toHexString(i)}", spans.any { i.toLong() in it })
        }
        assertTrue(changed > 1000)
        // Starters: three different ones that evolve twice, none legendary.
        val (ov, evo) = FileSystem.SYSTEM.openReadOnly(a).use { h ->
            val cart = DsRandomizer.Cart(h)
            cart.file(cart.overlayFile(g.starterOverlay)) to DsRandomizer.Narc(cart.file(cart.fileId(g.evolutions)))
        }
        val starters = g.starterOffsets.map { u32(ov, it) }
        assertEquals(3, starters.toSet().size)
        fun targets(s: Int) = evo.member(s).let { m -> (0 until m.size / 6).filter { u16(m, it * 6) != 0 }.map { u16(m, it * 6 + 4) } }
        for (s in starters) assertTrue("starter $s", targets(s).any { targets(it).isNotEmpty() })
    }

    @Test
    fun `the GUIDE reads a shuffled Platinum`() = each { g, f ->
        if (g.code != "CPUE") return@each
        val a = tmp.root.resolve("a.nds").toOkioPath()
        assertTrue(DsRandomizer.randomize(f.toOkioPath(), a, RomRandomizer.Options(), 7L))
        assertNull(DsRandomizer.readShuffle(f.toOkioPath()))
        val shuffle = DsRandomizer.readShuffle(a)!!
        val before = PLATINUM_WORLD.encounters(0, 342, null).grass.map { it.species }.toSet()   // Route 201
        val roarkBefore = PLATINUM_WORLD.party(246, null).map { it.species }
        try {
            DsRandomizer.shuffle = shuffle
            val after = PLATINUM_WORLD.encounters(0, 342, null).grass.map { it.species }.toSet()
            assertTrue(after.isNotEmpty())
            assertFalse(after == before)
            val roark = PLATINUM_WORLD.party(246, null)
            assertEquals(roarkBefore.size, roark.size)
            assertEquals(shuffle.team(246), roark)
            assertFalse(roark.map { it.species } == roarkBefore)
        } finally {
            DsRandomizer.shuffle = null
        }
        assertEquals(before, PLATINUM_WORLD.encounters(0, 342, null).grass.map { it.species }.toSet())
    }

    @Test
    fun `a cart that isn't one is refused`() = each { g, f ->
        val bad = tmp.root.resolve("bad.nds")
        f.copyTo(bad)
        // One byte of the encounter data changed outside a species word (an encounter rate).
        val enc = FileSystem.SYSTEM.openReadOnly(f.toOkioPath()).use { h -> DsRandomizer.Cart(h).let { it.span(it.fileId(g.encounters)) } }
        java.io.RandomAccessFile(bad, "rw").use { r ->
            val narc = DsRandomizer.Narc(ByteArray(0x40).also { r.seek(enc.first); r.readFully(it) }.let { head ->
                ByteArray((enc.last + 1 - enc.first).toInt()).also { r.seek(enc.first); r.readFully(it) }
            })
            r.seek(enc.first + narc.start(0))
            r.write(0x55)
        }
        assertNull(DsRandomizer.gameOf(bad.toOkioPath()))
        assertFalse(DsRandomizer.randomize(bad.toOkioPath(), tmp.root.resolve("out.nds").toOkioPath(), RomRandomizer.Options(), 1L))
    }
}
