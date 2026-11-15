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
            RomRandomizer.Options(fieldItems = true, trainerItems = true, baseStats = true, learnsets = true),
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
    fun `field items, trainers' items, base stats and learnsets are what was asked`() = each { g, rom ->
        val name = g.title
        val o = RomRandomizer.Options(RomRandomizer.Starters.OFF, RomRandomizer.Wild.OFF, false,
            fieldItems = true, trainerItems = true, baseStats = true, learnsets = true)
        val out = RomRandomizer.randomize(rom, o, 11L)!!
        // Base stats: each species' six in another order, the same total.
        var moved = 0
        for (s in (1..251) + (277..411)) {
            val at = g.baseStats + s * 28
            val was = (0 until 6).map { rom[at + it].toInt() and 0xFF }
            val now = (0 until 6).map { out[at + it].toInt() and 0xFF }
            assertEquals("$name $s", was.sorted(), now.sorted())
            if (was != now) moved++
        }
        assertTrue(name, moved > 300)
        // Learnsets: the same levels, no move twice, the first one with power.
        fun ptr(b: ByteArray, at: Int) = (u16(b, at) or (u16(b, at + 2) shl 16)) - 0x08000000
        for (s in (1..251) + (277..411)) {
            var at = ptr(out, g.learnsets + s * 4)
            val moves = ArrayList<Int>()
            while (u16(out, at) != 0xFFFF) {
                assertEquals(name, u16(rom, at) shr 9, u16(out, at) shr 9)
                moves += u16(out, at) and 0x1FF
                at += 2
            }
            assertEquals("$name $s", moves.size, moves.toSet().size)
            assertTrue("$name $s", moves.isEmpty() || out[g.battleMoves + moves[0] * 12 + 1].toInt() != 0)
        }
        // Everything else stays as it was: the only changes are the tool's sites (field items, held items among them).
        val sites = RomRandomizer.sites(rom, g).map { it.first }.toSet()
        val diff = rom.indices.filter { rom[it] != out[it] }
        assertTrue(name, diff.all { i -> sites.any { i in it..it + 1 } || RomRandomizer.sites(rom, g).any { i in it } })
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

    @Test
    fun `HERE lists a randomized cart's own item balls and hidden items`() = each { g, rom ->
        val id = when (g.code) { "BPRE" -> GuideId.FIRERED; "BPGE" -> GuideId.LEAFGREEN; "AXVE" -> GuideId.RUBY; "AXPE" -> GuideId.SAPPHIRE; else -> GuideId.EMERALD }
        fun items() = allAreaThings(id).values.flatten().filter { it.kind == AreaKind.ITEM || it.kind == AreaKind.HIDDEN }
        try {
            ownRandomizedGuide = null
            val retail = allAreaThings(id)
            val before = items()
            // Read the guide's way off the retail cart: every line as it was, none dropped.
            ownRandomizedGuide = id to RomRandomizer.guide(rom, g)
            assertEquals(g.title, retail, allAreaThings(id))
            val a = RomRandomizer.randomize(rom, RomRandomizer.Options(fieldItems = true), 8L)!!
            ownRandomizedGuide = id to RomRandomizer.guide(a, g)
            val after = items()
            assertEquals(g.title, before.size, after.size)
            assertTrue("${g.title} ${before.size}", before.indices.count { before[it].id != after[it].id } > before.size / 2)
            assertEquals(g.title, before.map { it.id }.sorted().size, after.size)
        } finally {
            ownRandomizedGuide = null
        }
    }

    @Test
    fun `run modes do what they say`() = each { g, rom ->
        val retail = RomRandomizer.view(rom, g)
        val themed = RomRandomizer.randomize(rom, RunModesCheck.THEMED, 21L)!!
        assertEquals(g.title, g, RomRandomizer.recognise(themed))
        RunModesCheck.themed(g.title, 3, retail, RomRandomizer.view(themed, g))
        val spread = RomRandomizer.randomize(rom, RunModesCheck.SPREAD, 22L)!!
        val without = RomRandomizer.randomize(rom, RunModesCheck.SPREAD.copy(allCatchable = false), 22L)!!
        RunModesCheck.spread(g.title, retail, RomRandomizer.view(spread, g), RomRandomizer.view(without, g))
    }
}
