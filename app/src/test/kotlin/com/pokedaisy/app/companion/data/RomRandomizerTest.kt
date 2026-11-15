package com.pokedaisy.app.companion.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The RANDOMIZE tool ([RomRandomizer]) on the player's retail carts (each skipped where it isn't here): it writes only its
 * [RomRandomizer.sites] (so the result is recognised as its output, and FireRed / Emerald's as randomized retail too), the
 * same seed gives the same file, and what it promises holds - starters that evolve twice, every wild slot / trainer
 * Pokémon a real one, trainers' own moves ones their Pokémon learns.
 */
class RomRandomizerTest {
    private val roms = mapOf(
        "BPRE" to "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba",
        "BPGE" to "Pokemon - LeafGreen Version (USA, Europe) (Rev 1).gba",
        "AXVE" to "Pokemon - Ruby Version (USA, Europe) (Rev 2).gba",
        "AXPE" to "Pokemon - Sapphire Version (USA, Europe) (Rev 2).gba",
        "BPEE" to "Pokemon - Emerald Version (USA, Europe).gba",
    )

    private fun each(body: (RomRandomizer.Game, ByteArray) -> Unit) {
        var ran = 0
        for (g in RomRandomizer.GAMES) {
            val f = File(RETAIL_ROM_DIR + roms.getValue(g.code))
            if (!f.isFile) continue
            ran++
            body(g, f.readBytes())
        }
        assumeTrue("no retail ROMs here", ran > 0)
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    @Test
    fun `the result is the tool's own, the same seed the same file`() = each { g, rom ->
        val name = g.title
        assertSame(name, g, RomRandomizer.gameOf(rom))
        assertNull(name, RomRandomizer.recognise(rom))
        val sites = BooleanArray(rom.size).also { m -> for (r in RomRandomizer.sites(rom, g)) for (i in r) m[i] = true }
        for (opts in listOf(
            RomRandomizer.Options(),
            RomRandomizer.Options(RomRandomizer.Starters.RANDOM, RomRandomizer.Wild.RANDOM, true, similarStrength = false, noLegendaries = false),
            RomRandomizer.Options(RomRandomizer.Starters.OFF, RomRandomizer.Wild.GLOBAL, false),
        )) {
            val a = RomRandomizer.randomize(rom, opts, 1234L)!!
            assertTrue(name, rom.indices.all { rom[it] == a[it] || sites[it] })
            assertFalse(name, a.contentEquals(rom))
            assertSame(name, g, RomRandomizer.recognise(a))
            if (RANDOMIZED_RETAIL.any { it.code == g.code && it.rev == g.rev }) {
                assertNotNull(name, RandomizedRetail.matchStream(g.code, g.rev, a.size.toLong()) { okio.Buffer().write(a) })
            }
            assertArrayEquals(name, a, RomRandomizer.randomize(rom, opts, 1234L))
            assertFalse(name, a.contentEquals(RomRandomizer.randomize(rom, opts, 99L)))
            // A randomized ROM can be randomized again.
            assertNotNull(name, RomRandomizer.randomize(a, opts, 5L))
            if (opts.starters == RomRandomizer.Starters.OFF) {
                for ((_, at) in g.starterSites) assertEquals(name, u16(rom, at), u16(a, at))
            }
        }
    }

    @Test
    fun `starters, wild and trainers are what was asked`() = each { g, rom ->
        val name = g.title
        val out = RomRandomizer.randomize(rom, RomRandomizer.Options(), 7L)!!
        fun targets(s: Int) = (0 until 5).filter { u16(out, g.evolutions + s * 40 + it * 8) != 0 }.map { u16(out, g.evolutions + s * 40 + it * 8 + 4) }
        val starters = g.starterSites.groupBy({ it.first }, { u16(out, it.second) })
        assertEquals(name, 3, starters.size)
        for ((_, ss) in starters) {
            assertEquals(name, 1, ss.toSet().size)
            assertTrue(name, targets(ss[0]).any { targets(it).isNotEmpty() })
        }
        assertEquals(name, 3, starters.values.map { it[0] }.toSet().size)
        var custom = 0
        for (t in 1 until g.trainerCount) {
            val at = g.trainers + t * 40
            val flags = out[at].toInt() and 0xFF
            val party = (u16(out, at + 36) or (u16(out, at + 38) shl 16)) - 0x08000000
            for (i in 0 until (out[at + 32].toInt() and 0xFF)) {
                val m = party + i * (if (flags and 1 != 0) 16 else 8)
                val s = u16(out, m + 4)
                assertTrue("$name trainer $t: $s", s in 1..251 || s in 277..411)
                if (flags and 1 != 0) {
                    custom++
                    val moves = (0 until 4).map { u16(out, m + (if (flags and 2 != 0) 8 else 6) + it * 2) }.filter { it != 0 }
                    assertTrue(name, moves.isNotEmpty())
                    val lp = (u16(out, g.learnsets + s * 4) or (u16(out, g.learnsets + s * 4 + 2) shl 16)) - 0x08000000
                    val learnt = generateSequence(lp) { it + 2 }.map { u16(out, it) }.takeWhile { it != 0xFFFF }.map { it and 0x1FF }.toSet()
                    assertTrue("$name trainer $t", learnt.containsAll(moves))
                }
            }
        }
        assertTrue(name, custom > 50)
    }

    @Test
    fun `anything that isn't one of those games is refused`() = each { g, rom ->
        val other = rom.copyOf().also { it[0x1000] = (it[0x1000] + 1).toByte() }
        assertNull(g.title, RomRandomizer.gameOf(other))
        assertNull(g.title, RomRandomizer.recognise(other))
        assertNull(g.title, RomRandomizer.randomize(other, RomRandomizer.Options(), 1L))
        // A hack under the cart's header whose tables point anywhere (a party pointer out of the ROM): no match, no crash.
        val hack = rom.copyOf()
        val party = g.trainers + 40 + 36
        for (i in 0 until 4) hack[party + i] = 0x78
        assertNull(g.title, RomRandomizer.gameOf(hack))
        assertNull(g.title, RomRandomizer.recognise(hack))
    }
}
