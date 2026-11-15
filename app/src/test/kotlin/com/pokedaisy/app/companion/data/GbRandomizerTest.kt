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
 * RANDOMIZE on the Game Boy carts ([GbRandomizer], the player's own in `gb/` beside the GBA ROM folder; each skipped where
 * it isn't here): only its sites change, the result is recognised as its own, the same seed makes the same cart, every
 * starter site of one starter agrees and the starters evolve twice, every wild / trainer Pokémon is a real one.
 */
class GbRandomizerTest {
    private val carts = mapOf(
        "Red" to "Pokemon - Red Version (USA, Europe) (SGB Enhanced).gb",
        "Blue" to "Pokemon - Blue Version (USA, Europe) (SGB Enhanced).gb",
        "Yellow" to "Pokemon - Yellow Version - Special Pikachu Edition (USA, Europe) (CGB+SGB Enhanced).gb",
        "Gold" to "Pokemon - Gold Version (USA, Europe) (SGB Enhanced) (GB Compatible).gbc",
        "Silver" to "Pokemon - Silver Version (USA, Europe) (SGB Enhanced) (GB Compatible).gbc",
        "Crystal" to "Pokemon - Crystal Version (USA, Europe) (Rev 1).gbc",
    )

    private fun each(body: (GbRandomizer.Game, ByteArray) -> Unit) {
        var ran = 0
        for (g in GbRandomizer.GAMES) {
            val f = File(File(RETAIL_ROM_DIR).parentFile, "gb/" + carts.getValue(g.title))
            if (!f.isFile) continue
            ran++
            body(g, f.readBytes())
        }
        assumeTrue("no Game Boy carts here", ran > 0)
    }

    @Test
    fun `hashes are the cart's`() {
        val found = ArrayList<Triple<GbRandomizer.Game, String, String?>>()
        each { g, rom -> found += Triple(g, GbRandomizer.sha1(rom), GbRandomizer.madeHash(rom, g)) }
        // GB_HASHES=<file>: each cart's made hash written there (for a change to the sites), not checked.
        System.getenv("GB_HASHES")?.let { out -> File(out).writeText(found.joinToString("") { "${it.first.madeSha1} ${it.third}\n" }); return }
        for ((g, sha, made) in found) {
            assertEquals(g.title, g.retailSha1, sha)
            assertEquals(g.title, g.madeSha1, made)
        }
    }

    @Test
    fun `the cart randomizes, is recognised, and the same seed makes the same cart`() = each { g, rom ->
        assertSame(g, GbRandomizer.gameOf(rom))
        assertNull(GbRandomizer.recognise(rom))
        val sites = GbRandomizer.sites(rom, g).toSet() + setOf(0x14E, 0x14F)
        val a = GbRandomizer.randomize(rom, RomRandomizer.Options(), 7L)!!
        assertTrue(g.title, rom.indices.all { rom[it] == a[it] || it in sites })
        assertFalse(a.contentEquals(rom))
        assertSame(g, GbRandomizer.recognise(a))
        assertArrayEquals(a, GbRandomizer.randomize(rom, RomRandomizer.Options(), 7L))
        assertFalse(a.contentEquals(GbRandomizer.randomize(rom, RomRandomizer.Options(), 8L)))
        assertNotNull(GbRandomizer.randomize(a, RomRandomizer.Options(), 9L))
        // The global checksum is right.
        var sum = 0
        for (i in a.indices) if (i != 0x14E && i != 0x14F) sum += a[i].toInt() and 0xFF
        assertEquals(sum and 0xFFFF, ((a[0x14E].toInt() and 0xFF) shl 8) or (a[0x14F].toInt() and 0xFF))
        // Each starter's sites agree, the three differ.
        if (g.starters.isNotEmpty()) {
            val by = g.starters.groupBy({ it.first }, { a[it.second].toInt() and 0xFF })
            for ((_, v) in by) assertEquals(g.title, 1, v.toSet().size)
            assertEquals(3, by.values.map { it.first() }.toSet().size)
        }
        // Every trainer and wild species is a Pokémon.
        val dexOk: (Int) -> Boolean = if (g.gen == 2) { s -> s in 1..251 } else { s -> s in 1..190 && (a[g.dexOrder + s - 1].toInt() and 0xFF) != 0 }
        for (at in GbRandomizer.sites(a, g)) if (g.starters.none { it.second == at }) {
            val v = a[at].toInt() and 0xFF
            if (g.gen == 2 && v < 0xFF && v > 251) error("${g.title}: ${Integer.toHexString(at)} = $v")
        }
        assertTrue(g.starters.all { dexOk(a[it.second].toInt() and 0xFF) })
    }

    /** A randomized cart gets its original's art (Gen1Art / Gen2Art go by the original's hash): its icons, font and map. */
    @Test
    fun `a randomized cart gets its art`() = each { g, rom ->
        val a = GbRandomizer.randomize(rom, RomRandomizer.Options(), 7L)!!
        val dir = okio.FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "gbrand-art-${g.title}-${System.nanoTime()}"
        okio.FileSystem.SYSTEM.createDirectories(dir)
        try {
            val wrote = if (g.gen == 2) Gen2Art.extractTo(a, dir) else Gen1Art.extractTo(a, dir)
            assertTrue("${g.title}: ${wrote.size}", wrote.size > 100)
        } finally {
            okio.FileSystem.SYSTEM.deleteRecursively(dir)
        }
    }

    @Test
    fun `base stats, learnsets and held items are what was asked`() = each { g, rom ->
        val o = RomRandomizer.Options(RomRandomizer.Starters.OFF, RomRandomizer.Wild.OFF, false,
            trainerItems = true, baseStats = true, learnsets = true)
        val a = GbRandomizer.randomize(rom, o, 5L)!!
        assertSame(g, GbRandomizer.recognise(a))
        // The move table is the right one: POUND (1) has 40 power.
        assertEquals(g.title, 40, rom[g.moves + 2].toInt() and 0xFF)
        val stride = if (g.gen == 1) 6 else 7
        // Base stats: the same five / six, in another order.
        val count = if (g.gen == 1) 5 else 6
        val firsts = if (g.gen == 1) (1..151).map { n -> if (n == 151 && g.mewStats != 0) g.mewStats else g.baseStats + (n - 1) * 28 }
            else (1..251).map { g.baseStats + (it - 1) * 0x20 }
        var moved = 0
        for (at in firsts) {
            val was = (1..count).map { rom[at + it].toInt() and 0xFF }
            val now = (1..count).map { a[at + it].toInt() and 0xFF }
            assertEquals(g.title, was.sorted(), now.sorted())
            if (was != now) moved++
        }
        assertTrue(g.title, moved > 100)
        // Every byte that changed is a site (levels, the rest of the records, untouched).
        val sites = GbRandomizer.sites(rom, g).toSet() + setOf(0x14E, 0x14F)
        assertTrue(g.title, rom.indices.all { rom[it] == a[it] || it in sites })
        // Gen 1's level-1 moves: the first one does damage.
        if (g.gen == 1) for (at in firsts) {
            val first = a[at + 15].toInt() and 0xFF
            if (first != 0) assertTrue("${g.title} ${Integer.toHexString(at)}", a[g.moves + (first - 1) * stride + 2].toInt() and 0xFF > 0)
        }
    }

    @Test
    fun `field items are shuffled among their places`() = each { g, rom ->
        val places = GbRandomizer.fieldSites(rom, g)
        // pokered: 106 item balls (fewer the key items and the two empty ones) and 54 hidden items; pokecrystal: 263 places.
        assertTrue("${g.title} ${places.size}", places.size > if (g.gen == 1) 140 else 210)
        val a = GbRandomizer.randomize(rom, RomRandomizer.Options(RomRandomizer.Starters.OFF, RomRandomizer.Wild.OFF, false,
            fieldItems = true), 3L)!!
        assertSame(g, GbRandomizer.recognise(a))
        assertEquals(g.title, places.map { rom[it] }.sorted(), places.map { a[it] }.sorted())
        assertTrue(g.title, places.count { rom[it] != a[it] } > places.size / 2)
        // Nothing but those places (and the checksum) changed: key items and HMs stay where they were.
        val changed = rom.indices.filter { rom[it] != a[it] }.toSet() - setOf(0x14E, 0x14F)
        assertTrue(g.title, places.toSet().containsAll(changed))
    }

    @Test
    fun `anything else is refused`() = each { g, rom ->
        val other = rom.copyOf().also { it[0x2000] = (it[0x2000] + 1).toByte() }
        assertNull(GbRandomizer.gameOf(other))
        assertNull(GbRandomizer.randomize(other, RomRandomizer.Options(), 1L))
    }
}
