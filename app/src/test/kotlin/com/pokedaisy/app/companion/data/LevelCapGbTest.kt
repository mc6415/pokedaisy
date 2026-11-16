package com.pokedaisy.app.companion.data

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/**
 * LEVEL CAP on the Game Boy (LevelCapGb.kt): each English cart takes its patch (every byte it replaces the cart's own, in
 * 4-byte writes, the cap where the code reads it), and the saves get their cap from the GUIDE's next boss.
 */
class LevelCapGbTest {
    @After fun tearDown() { romLanguage = 'E'; romGameCode = "" }

    private val dirs = listOf("gb", "gbc").map { File(File(RETAIL_ROM_DIR).parentFile, it) }
    private fun rom(name: String): ByteArray? = dirs.map { File(it, name) }.firstOrNull { it.isFile }?.readBytes()
    private fun sha1(b: ByteArray) = MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }

    private val carts = listOf(
        "Pokemon - Red Version (USA, Europe) (SGB Enhanced).gb",
        "Pokemon - Blue Version (USA, Europe) (SGB Enhanced).gb",
        "Pokemon - Yellow Version - Special Pikachu Edition (USA, Europe) (CGB+SGB Enhanced).gb",
        "Pokemon - Gold Version (USA, Europe) (SGB Enhanced) (GB Compatible).gbc",
        "Pokemon - Silver Version (USA, Europe) (SGB Enhanced) (GB Compatible).gbc",
        "Pokemon - Crystal Version (USA, Europe) (Rev 1).gbc",
    )

    @Test
    fun `every English cart takes its patch`() {
        var checked = 0
        for (name in carts) {
            val b = rom(name) ?: continue
            val found = LevelCapGb.find(sha1(b), b)
            assertNotNull(name, found)
            val writes = LevelCap.writes(found!!, 23)
            assertTrue(name, writes.all { it.value.size in 1..4 && it.expect.size == it.value.size })
            for (w in writes) assertTrue(name, b.copyOfRange(w.addr.toInt(), w.addr.toInt() + w.expect.size).contentEquals(w.expect))
            // The cap is in the code: 23 somewhere in the new bytes, and the jump a `call` into the routine's own bank.
            assertTrue(name, writes.any { w -> w.value.any { it.toInt() == 23 } })
            assertTrue(name, writes.any { w -> (w.value[0].toInt() and 0xFF) == 0xCD })
            checked++
        }
        assumeTrue("no English Game Boy cart on this machine", checked > 0)
    }

    @Test
    fun `a changed cart gets nothing`() {
        val b = rom(carts[0]) ?: return assumeTrue("Red not on this machine", false)
        assertEquals(null, LevelCapGb.find(sha1(b), b.copyOf().also { it[0x57E05] = 1 }))
        assertEquals(null, LevelCapGb.find(sha1(b), b.copyOf().also { it[0x5530B] = 0 }))
    }

    @Test
    fun `the saves' cap is the next boss's top level`() {
        // Yellow's save has beaten the League: no boss left, no cap.
        activeGame = GameKind.YELLOW
        romGameCode = GEN1_YELLOW.gameCode
        val yellow = buildSnapshotView(readGen1Telemetry(FixtureMemoryReader.load("yellow"), GEN1_YELLOW)).copy(game = GameKind.YELLOW)
        assertEquals(true, LevelCap.hasBosses(yellow))
        assertEquals(null, LevelCap.next(yellow))
        // Crystal's (a new game played headless): the first leader ahead.
        activeGame = GameKind.CRYSTAL
        romGameCode = ""
        val crystal = buildSnapshotView(readGen2Telemetry(FixtureMemoryReader.load("crystal"), GEN2_CRYSTAL)).copy(game = GameKind.CRYSTAL)
        assertEquals(true, LevelCap.hasBosses(crystal))
        val cap = LevelCap.next(crystal)
        assertNotNull(cap)
        val guide = gameGuide(guideId(GameKind.CRYSTAL, null))!!
        val p = crystal.progress!!
        val boss = guide.bosses.first { !it.isDone(p) }
        assertEquals(boss.title, cap!!.boss)
        assertEquals(boss.trainers(p).maxOf { guide.world!!.party(it, p)!!.maxOf { m -> m.level } }, cap.level)
        println("crystal: cap ${cap.level} (${cap.boss})")
    }

    @Test
    fun `the DS games are found on melonDS only`() {
        for ((code, rev) in listOf("CPUE" to 1, "IPKE" to 0, "IPGE" to 0, "IRBO" to 0, "IRAO" to 0, "IREO" to 0, "IRDO" to 0))
            assertTrue(code, LevelCap.dsFind(code, rev, dsperate = false, hack = false)?.ds == true)
        assertEquals(null, LevelCap.dsFind("CPUE", 1, dsperate = true, hack = false))
        assertEquals(null, LevelCap.dsFind("CPUE", 1, dsperate = false, hack = true))
        assertEquals(null, LevelCap.dsFind("CPUF", 0, dsperate = false, hack = false))
    }

    @Test
    fun `Platinum's save gets ROARK's top level`() {
        activeGame = GameKind.PLATINUM
        val p = readGen4Progress(FixtureMemoryReader.load("platinum"), GEN4_PLATINUM_US, 0x0227E164L + GEN4_PLATINUM_US.bodyOff, 12)!!
        val snap = SnapshotView(connected = true, game = GameKind.PLATINUM, progress = p)
        assertEquals(true, LevelCap.hasBosses(snap))
        val cap = LevelCap.next(snap)
        assertNotNull(cap)
        println("platinum: cap ${cap!!.level} (${cap.boss})")
        assertEquals(14, cap.level)   // ROARK's RAMPARDOS-line lead (res/trainers/data/leader_roark.json): Lv 14
    }
}
