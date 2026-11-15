package com.pokedaisy.app.companion.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The RANDOMIZE tool ([RomRandomizer]) on the player's retail FireRed rev 1 and Emerald (skipped where they aren't
 * here): it writes only the randomizer's sites (so the result reads as randomized retail), the same seed gives the
 * same file, and what it promises holds - starters that evolve twice, every wild slot / trainer Pokémon a real one,
 * trainers' own moves ones their Pokémon learns.
 */
class RomRandomizerTest {
    private val roms = listOf(
        "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba",
        "Pokemon - Emerald Version (USA, Europe).gba",
    )

    private fun each(body: (String, ByteArray) -> Unit) {
        var ran = 0
        for (n in roms) {
            val f = File(RETAIL_ROM_DIR + n)
            if (!f.isFile) continue
            ran++
            body(n, f.readBytes())
        }
        assumeTrue("no retail ROMs here", ran > 0)
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)

    @Test
    fun `the result is randomized retail, the same seed the same file`() = each { name, rom ->
        val (game, entry) = RomRandomizer.gameOf(rom)!!
        for (opts in listOf(
            RomRandomizer.Options(),
            RomRandomizer.Options(RomRandomizer.Starters.RANDOM, RomRandomizer.Wild.RANDOM, true, similarStrength = false, noLegendaries = false),
            RomRandomizer.Options(RomRandomizer.Starters.OFF, RomRandomizer.Wild.GLOBAL, false),
        )) {
            val a = RomRandomizer.randomize(rom, opts, 1234L)!!
            assertTrue(name, RomRandomizer.onlyMasked(rom, a, entry))
            assertFalse(name, a.contentEquals(rom))
            assertArrayEquals(name, a, RomRandomizer.randomize(rom, opts, 1234L))
            assertFalse(name, a.contentEquals(RomRandomizer.randomize(rom, opts, 99L)))
            // A randomized ROM can be randomized again.
            assertNotNull(name, RomRandomizer.randomize(a, opts, 5L))
            if (opts.starters == RomRandomizer.Starters.OFF) {
                for ((_, at) in game.starterSites) assertEquals(name, u16(rom, at), u16(a, at))
            }
        }
    }

    @Test
    fun `starters, wild and trainers are what was asked`() = each { name, rom ->
        val (game, _) = RomRandomizer.gameOf(rom)!!
        val out = RomRandomizer.randomize(rom, RomRandomizer.Options(), 7L)!!
        val evo = game.evolutions
        fun targets(s: Int) = (0 until 5).filter { u16(out, evo + s * 40 + it * 8) != 0 }.map { u16(out, evo + s * 40 + it * 8 + 4) }
        val starters = game.starterSites.groupBy({ it.first }, { u16(out, it.second) })
        assertEquals(name, 3, starters.size)
        for ((_, ss) in starters) {
            assertEquals(name, 1, ss.toSet().size)
            assertTrue(name, targets(ss[0]).any { targets(it).isNotEmpty() })
        }
        assertEquals(name, 3, starters.values.map { it[0] }.toSet().size)
        // Every trainer Pokémon a real species; custom moves inside its learnset.
        var custom = 0
        for (t in 1 until game.trainerCount) {
            val at = game.trainers + t * 40
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
                    val lp = (u16(out, game.learnsets + s * 4) or (u16(out, game.learnsets + s * 4 + 2) shl 16)) - 0x08000000
                    val learnt = generateSequence(lp) { it + 2 }.map { u16(out, it) }.takeWhile { it != 0xFFFF }.map { it and 0x1FF }.toSet()
                    assertTrue("$name trainer $t", learnt.containsAll(moves))
                }
            }
        }
        assertTrue(name, custom > 50)
    }

    @Test
    fun `anything that isn't one of those games is refused`() = each { name, rom ->
        val other = rom.copyOf().also { it[0x1000] = (it[0x1000] + 1).toByte() }
        assertNull(name, RomRandomizer.gameOf(other))
        assertNull(name, RomRandomizer.randomize(other, RomRandomizer.Options(), 1L))
    }
}
