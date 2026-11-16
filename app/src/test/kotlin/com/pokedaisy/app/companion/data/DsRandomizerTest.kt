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

    private val carts = mapOf(
        "CPUE" to "Pokemon - Platinum Version (USA) (Rev 1).nds",
        "IPKE" to "Pokemon - HeartGold Version (USA).nds",
        "IPGE" to "Pokemon - SoulSilver Version (USA, Australia).nds",
        "IRBO" to "Pokemon - Black Version (USA, Europe) (NDSi Enhanced).nds",
        "IRAO" to "Pokemon - White Version (USA, Europe) (NDSi Enhanced).nds",
        "IREO" to "Pokemon - Black Version 2 (USA, Europe) (NDSi Enhanced).nds",
        "IRDO" to "Pokemon - White Version 2 (USA, Europe) (NDSi Enhanced).nds",
    )

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

    /** Calls [each] with every place [x] and [y] (the same size) differ, a chunk at a time (the carts run to 512 MB). */
    private fun diff(x: File, y: File, each: (Long) -> Unit) {
        x.inputStream().buffered(1 shl 20).use { ix ->
            y.inputStream().buffered(1 shl 20).use { iy ->
                val bx = ByteArray(1 shl 20); val by = ByteArray(1 shl 20)
                var at = 0L
                while (true) {
                    val n = ix.readNBytes(bx, 0, bx.size)
                    iy.readNBytes(by, 0, n)
                    if (n <= 0) break
                    if (!java.util.Arrays.equals(bx, 0, n, by, 0, n)) for (i in 0 until n) if (bx[i] != by[i]) each(at + i)
                    at += n
                }
            }
        }
    }

    private fun sameFiles(x: File, y: File): Boolean {
        var same = true
        diff(x, y) { same = false }
        return same
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = u16(b, o) or (u16(b, o + 2) shl 16)

    @Test
    fun `hashes are the cart's`() = each { g, f ->
        val (retail, made) = DsRandomizer.hashes(f.toOkioPath())!!
        // DS_HASHES=<file>: each cart's hashes written there (for a change to the files the tool writes), not checked.
        System.getenv("DS_HASHES")?.let { File(it).appendText("${g.code}${g.rev} ${g.retailFilesSha1} ${g.madeSha1} $retail $made\n"); return@each }
        assertEquals(g.title, g.retailFilesSha1, retail)
        assertEquals(g.title, g.madeSha1, made)
    }

    @Test
    fun `the cart randomizes, is recognised, and the same seed makes the same cart`() = each { g, f ->
        val path = f.toOkioPath()
        assertSame(g, DsRandomizer.gameOf(path, retail = true))
        assertNull(DsRandomizer.recognise(path))
        val a = tmp.root.resolve("${g.code}-a.nds").toOkioPath()
        val b = tmp.root.resolve("${g.code}-b.nds").toOkioPath()
        val c = tmp.root.resolve("${g.code}-c.nds").toOkioPath()
        assertTrue(DsRandomizer.randomize(path, a, RomRandomizer.Options(), 7L))
        assertTrue(DsRandomizer.randomize(path, b, RomRandomizer.Options(), 7L))
        assertTrue(DsRandomizer.randomize(path, c, RomRandomizer.Options(), 8L))
        assertTrue(sameFiles(File(a.toString()), File(b.toString())))
        assertFalse(sameFiles(File(a.toString()), File(c.toString())))
        assertEquals(f.length(), File(a.toString()).length())
        assertSame(g, DsRandomizer.recognise(a))
        assertNull(DsRandomizer.gameOf(a, retail = true))
        assertNotNull(DsRandomizer.gameOf(a))
        // Only the files it writes changed: every differing byte lies inside one of them.
        val spans = FileSystem.SYSTEM.openReadOnly(path).use { h ->
            val cart = DsRandomizer.Cart(h)
            listOfNotNull(
                g.starterOverlay?.let { cart.span(cart.overlayFile(it)) }, cart.span(cart.fileId(g.encounters)), cart.span(cart.fileId(g.trainerParties)),
                g.text?.let { cart.span(cart.fileId(it.path)) }, g.starterScripts?.let { cart.span(cart.fileId(it.path)) },
                g.packedStarters?.let { cart.arm9Place() until cart.arm9Place() + cart.arm9().size },
                g.packedStarters?.let { cart.span(cart.overlayFile(it.overlay, allowCompressed = true)) },
            )
        }
        var changed = 0
        diff(f, File(a.toString())) { i ->
            changed++
            assertTrue("byte ${java.lang.Long.toHexString(i)}", spans.any { i in it })
        }
        assertTrue(changed > 1000)
        // Starters: three different ones that evolve twice; every place a starter is named agrees.
        val (starters, evo) = FileSystem.SYSTEM.openReadOnly(a).use { h ->
            val cart = DsRandomizer.Cart(h)
            val evo = DsRandomizer.Narc(cart.file(cart.fileId(g.evolutions)))
            val three = if (g.packedStarters?.arm9Offset != null) {
                // What you're given (ARM9) and the starter app's copy (overlay 61) agree.
                val ps = g.packedStarters!!
                val a9 = Blz.decompress(cart.arm9())
                val ov = Blz.decompress(cart.file(cart.overlayFile(ps.overlay, allowCompressed = true)))
                val given = (0 until 3).map { u32(a9, ps.arm9Offset!! + it * 4) }
                assertEquals(given, (0 until 3).map { u32(ov, ps.overlayOffset + it * 4) })
                given
            } else if (g.starterOverlay != null) {
                val ov = cart.file(cart.overlayFile(g.starterOverlay!!))
                g.starterOffsets.map { u32(ov, it) }
            } else {
                val sc = DsRandomizer.Narc(cart.file(cart.fileId(g.starterScripts!!.path)))
                val bySite = g.starterScripts!!.sites.groupBy({ it.which }, { site -> sc.member(site.member).let { u16(it, site.offset) } })
                for ((_, v) in bySite) assertEquals(1, v.toSet().size)
                val given = (0 until 3).map { bySite.getValue(it).first() }
                // Gen 5: the choice screen's own list (a compressed overlay) shows the same three.
                g.packedStarters?.let { ps ->
                    val ov = Blz.decompress(cart.file(cart.overlayFile(ps.overlay, allowCompressed = true)))
                    assertEquals(given, (0 until 3).map { u16(ov, ps.overlayOffset + it * 2) })
                }
                given
            }
            three to evo
        }
        assertEquals(3, starters.toSet().size)
        fun targets(s: Int) = evo.member(s).let { m -> (0 until m.size / 6).filter { u16(m, it * 6) != 0 }.map { u16(m, it * 6 + 4) } }
        for (s in starters) assertTrue("starter $s", targets(s).any { targets(it).isNotEmpty() })
    }

    @Test
    fun `base stats, learnsets and held items are what was asked`() = each { g, f ->
        val a = tmp.root.resolve("${g.code}-extra.nds").toOkioPath()
        val o = RomRandomizer.Options(RomRandomizer.Starters.OFF, RomRandomizer.Wild.OFF, false,
            trainerItems = true, baseStats = true, learnsets = true)
        assertTrue(DsRandomizer.randomize(f.toOkioPath(), a, o, 3L))
        fun read(p: okio.Path) = FileSystem.SYSTEM.openReadOnly(p).use { h ->
            val c = DsRandomizer.Cart(h)
            Triple(DsRandomizer.Narc(c.file(c.fileId(g.personal))), DsRandomizer.Narc(c.file(c.fileId(g.learnsets))),
                DsRandomizer.Narc(c.file(c.fileId(g.moves))))
        }
        val (p0, l0, moves) = read(f.toOkioPath())
        val (p1, l1, _) = read(a)
        // The moves' archive is the right one: POUND (1) has 40 power.
        assertEquals(g.title, 40, moves.member(1)[3].toInt() and 0xFF)
        var moved = 0
        val step = if (g.gen == 5) 4 else 2
        for (sp in 1..g.speciesCount) {
            val was = (0 until 6).map { p0.member(sp)[it].toInt() and 0xFF }
            val now = (0 until 6).map { p1.member(sp)[it].toInt() and 0xFF }
            assertEquals("${g.title} $sp", was.sorted(), now.sorted())
            if (was != now) moved++
            val m0 = l0.member(sp); val m1 = l1.member(sp)
            val list = ArrayList<Int>()
            var i = 0
            while (i + step <= m1.size && u16(m1, i) != 0xFFFF) {
                val level = { m: ByteArray -> if (g.gen == 5) u16(m, i + 2) else u16(m, i) shr 9 }
                assertEquals("${g.title} $sp", level(m0), level(m1))
                list += if (g.gen == 5) u16(m1, i) else u16(m1, i) and 0x1FF
                i += step
            }
            assertEquals("${g.title} $sp", list.size, list.toSet().size)
            if (list.isNotEmpty()) assertTrue("${g.title} $sp", moves.member(list[0])[3].toInt() and 0xFF > 0)
        }
        assertTrue(g.title, moved > 400)
        assertEquals(g.madeSha1, DsRandomizer.hashes(a)!!.second)
    }

    @Test
    fun `item balls are shuffled among themselves`() = each { g, f ->
        val a = tmp.root.resolve("${g.code}-field.nds").toOkioPath()
        val o = RomRandomizer.Options(RomRandomizer.Starters.OFF, RomRandomizer.Wild.OFF, false, fieldItems = true)
        assertTrue(DsRandomizer.randomize(f.toOkioPath(), a, o, 4L))
        val b = g.balls!!
        fun items(p: okio.Path) = FileSystem.SYSTEM.openReadOnly(p).use { h ->
            val c = DsRandomizer.Cart(h)
            val n = DsRandomizer.Narc(c.file(c.fileId(b.path)))
            val m = n.member(b.member)
            val out = ArrayList<Int>()
            var k = 0
            while (u16(m, 4 * k) != 0xFD13) {
                val s = 4 * k + 4 + (u16(m, 4 * k) or (u16(m, 4 * k + 2) shl 16)) + b.at
                if (u16(m, s) in 0x28..0x29 && u16(m, s + 6) == u16(m, s)) out += u16(m, s + 4)
                k++
            }
            out
        }
        val was = items(f.toOkioPath())
        val now = items(a)
        // Platinum 327 balls, HeartGold 255, Black 306, Black 2 400 (scripts/gen_gen4_guide.py / gen_gen5_guide.py read them).
        assertTrue("${g.title} ${was.size}", was.size > 250)
        assertEquals(g.title, was.sorted(), now.sorted())
        assertTrue(g.title, was.indices.count { was[it] != now[it] } > was.size / 2)
        // HMs stay where they are.
        for (i in was.indices) if (was[i] in 420..427) assertEquals(g.title, was[i], now[i])
        assertSame(g, DsRandomizer.recognise(a))
        assertEquals(g.madeSha1, DsRandomizer.hashes(a)!!.second)
    }

    @Test
    fun `HERE lists a randomized cart's own item balls`() = each { g, f ->
        val id = when (g.code) {
            "CPUE" -> GuideId.PLATINUM; "IPKE" -> GuideId.HEARTGOLD; "IPGE" -> GuideId.SOULSILVER; "IRBO" -> GuideId.BLACK
            "IRAO" -> GuideId.WHITE; "IREO" -> GuideId.BLACK2; else -> GuideId.WHITE2
        }
        fun balls() = allAreaThings(id).values.flatten().filter { it.kind == AreaKind.ITEM }
        try {
            DsRandomizer.shuffle = null
            val retail = allAreaThings(id)
            val before = balls()
            val mine = DsRandomizer.ballItems(f.toOkioPath())!!
            for (t in before) assertEquals("${g.title} flag ${t.flag}", t.id, mine[t.flag])
            DsRandomizer.shuffle = DsRandomizer.Shuffle(g, emptyList(), emptyMap(), mine)
            assertEquals(g.title, retail, allAreaThings(id))
            val a = tmp.root.resolve("${g.code}-here.nds").toOkioPath()
            assertTrue(DsRandomizer.randomize(f.toOkioPath(), a, RomRandomizer.Options(fieldItems = true), 2L))
            DsRandomizer.shuffle = DsRandomizer.readShuffle(a)!!
            val after = balls()
            assertEquals(g.title, before.size, after.size)
            assertTrue("${g.title} ${before.size}", before.indices.count { before[it].id != after[it].id } > before.size / 3)
        } finally {
            DsRandomizer.shuffle = null
        }
    }


    @Test
    fun `run modes do what they say`() = each { g, f ->
        val retail = DsRandomizer.view(f.toOkioPath())!!
        fun made(o: RomRandomizer.Options, seed: Long, name: String): CartView {
            val a = tmp.root.resolve("${g.code}-$name.nds").toOkioPath()
            assertTrue(DsRandomizer.randomize(f.toOkioPath(), a, o, seed))
            return DsRandomizer.view(a)!!.also { java.io.File(a.toString()).delete() }
        }
        RunModesCheck.themed(g.title, g.gen, retail, made(RunModesCheck.THEMED, 21L, "themed"))
        RunModesCheck.spread(g.title, retail, made(RunModesCheck.SPREAD, 22L, "spread"), made(RunModesCheck.SPREAD.copy(allCatchable = false), 22L, "without"))
        for ((mode, seed) in listOf(RomRandomizer.BossTypes.OWN to 41L, RomRandomizer.BossTypes.RANDOM to 42L))
            RunModesCheck.bosses(g.title, g.gen, retail, made(RunModesCheck.BOSSES.copy(bossTypes = mode), seed, "bosses-$mode"), mode)
        for ((mode, seed) in listOf(RomRandomizer.Zones.RANDOM to 51L, RomRandomizer.Zones.TERRAIN to 52L))
            RunModesCheck.zones(g.title, g.gen, made(RunModesCheck.zonesOptions(mode), seed, "zones-$mode"), mode)
    }

    @Test
    fun `EVOLUTIONS and ABILITIES do what they say`() = each { g, f ->
        val a = tmp.root.resolve("${g.code}-evolve.nds").toOkioPath()
        assertTrue(DsRandomizer.randomize(f.toOkioPath(), a, EVOLVE_ABILITIES, 31L))
        assertEquals(g.title, g, DsRandomizer.recognise(a))
        checkEvolveAbilities(g.title, g.gen, DsRandomizer.view(f.toOkioPath())!!, DsRandomizer.view(a)!!)
        java.io.File(a.toString()).delete()
    }


    @Test
    fun `GIFTS swaps the gift and static Pokemon`() = each { g, f ->
        val a = tmp.root.resolve("${g.code}-gifts.nds").toOkioPath()
        val o = RomRandomizer.Options(RomRandomizer.Starters.OFF, RomRandomizer.Wild.OFF, false, gifts = true)
        assertTrue(DsRandomizer.randomize(f.toOkioPath(), a, o, 31L))
        assertSame(g, DsRandomizer.recognise(a))
        checkGifts(g.title, DsRandomizer.giftSpecies(f.toOkioPath())!!, DsRandomizer.giftSpecies(a)!!)
        java.io.File(a.toString()).delete()
    }

    @Test
    fun `the GUIDE reads a shuffled Black 2`() = each { g, f ->
        if (g.code != "IREO") return@each
        val a = tmp.root.resolve("${g.code}-a.nds").toOkioPath()
        assertTrue(DsRandomizer.randomize(f.toOkioPath(), a, RomRandomizer.Options(), 7L))
        val shuffle = DsRandomizer.readShuffle(a)!!
        val zone = 6   // its wild set is 0, all year: a fishing / surfing spot
        val before = BLACK2_WORLD.encounters(0, zone, null).let { it.water + it.superRod }.map { it.species }.toSet()
        val cheren = BLACK2_WORLD.party(156, null)
        val white2Cheren = WHITE2_WORLD.party(156, null)
        try {
            DsRandomizer.shuffle = shuffle
            val after = BLACK2_WORLD.encounters(0, zone, null).let { it.water + it.superRod }.map { it.species }.toSet()
            assertTrue(after.isNotEmpty())
            assertFalse(after == before)
            assertEquals(shuffle.team(156), BLACK2_WORLD.party(156, null))
            assertFalse(BLACK2_WORLD.party(156, null).map { it.species } == cheren.map { it.species })
            // White 2's GUIDE isn't Black 2's cart.
            assertEquals(white2Cheren, WHITE2_WORLD.party(156, null))
        } finally {
            DsRandomizer.shuffle = null
        }
    }

    @Test
    fun `the GUIDE reads a shuffled Black`() = each { g, f ->
        if (g.code != "IRBO") return@each
        val a = tmp.root.resolve("${g.code}-guide.nds").toOkioPath()
        assertTrue(DsRandomizer.randomize(f.toOkioPath(), a, RomRandomizer.Options(), 7L))
        val shuffle = DsRandomizer.readShuffle(a)!!
        fun wild(z: Int) = BLACK_WORLD.encounters(0, z, null).let { it.grass + it.water + it.superRod }.map { it.species }.toSet()
        val zone = (0 until 700).first { wild(it).isNotEmpty() }
        val trainer = (1 until 900).first { BLACK_WORLD.party(it, null).isNotEmpty() }
        val before = wild(zone)
        val team = BLACK_WORLD.party(trainer, null)
        val whiteTeam = WHITE_WORLD.party(trainer, null)
        try {
            DsRandomizer.shuffle = shuffle
            assertFalse(wild(zone) == before)
            assertEquals(shuffle.team(trainer), BLACK_WORLD.party(trainer, null))
            assertFalse(BLACK_WORLD.party(trainer, null).map { it.species } == team.map { it.species })
            // White's GUIDE isn't Black's cart.
            assertEquals(whiteTeam, WHITE_WORLD.party(trainer, null))
        } finally {
            DsRandomizer.shuffle = null
        }
    }

    @Test
    fun `the GUIDE reads a shuffled Platinum`() = each { g, f ->
        if (g.code != "CPUE") return@each
        val a = tmp.root.resolve("${g.code}-a.nds").toOkioPath()
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
    fun `the GUIDE reads a shuffled HeartGold`() = each { g, f ->
        if (g.code != "IPKE") return@each
        val a = tmp.root.resolve("hg-a.nds").toOkioPath()
        assertTrue(DsRandomizer.randomize(f.toOkioPath(), a, RomRandomizer.Options(), 7L))
        val shuffle = DsRandomizer.readShuffle(a)!!
        fun all(m: MapEncounters) = (m.grass + m.water + m.rockSmash + m.oldRod + m.goodRod + m.superRod).map { it.species }.toSet()
        // Every map the GUIDE knows: some of them change, none read the cart for SoulSilver.
        val maps = (0 until 600)
        val before = maps.associateWith { all(HEARTGOLD_WORLD.encounters(0, it, null)) }
        val ssBefore = maps.associateWith { all(SOULSILVER_WORLD.encounters(0, it, null)) }
        val falkner = HEARTGOLD_WORLD.party(20, null)
        try {
            DsRandomizer.shuffle = shuffle
            val changed = maps.count { all(HEARTGOLD_WORLD.encounters(0, it, null)) != before[it] }
            assertTrue("maps changed: $changed", changed > 20)
            assertEquals(ssBefore, maps.associateWith { all(SOULSILVER_WORLD.encounters(0, it, null)) })
            assertEquals(shuffle.team(20), HEARTGOLD_WORLD.party(20, null))
            assertFalse(HEARTGOLD_WORLD.party(20, null).map { it.species } == falkner.map { it.species })
        } finally {
            DsRandomizer.shuffle = null
        }
    }

    @Test
    fun `a cart that isn't one is refused`() = each { g, f ->
        val bad = tmp.root.resolve("${g.code}-bad.nds")
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
        assertFalse(DsRandomizer.randomize(bad.toOkioPath(), tmp.root.resolve("${g.code}-out.nds").toOkioPath(), RomRandomizer.Options(), 1L))
    }
}
