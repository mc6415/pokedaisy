package com.pokedaisy.app.companion.data

import okio.Path.Companion.toOkioPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * The summary's LEARN list on a DS game (Black 2's fixture, the cart's learnsets and machines): the next level-up moves,
 * then the bag's TMs / HMs the Pokémon can learn - no verdicts, which are the original GBA games' alone.
 * Skips without the ROM (`-DromDir=`'s ../nds/).
 */
class DsMoveLearningTest {
    private val rom = File(File(RETAIL_ROM_DIR).parentFile, "nds/Pokemon - Black Version 2 (USA, Europe) (NDSi Enhanced).nds")
    private var dir: File? = null

    @After fun tearDown() {
        NdsArt.dir = null
        dir?.deleteRecursively()
    }

    @Test fun `level-up moves and the bag's machines`() {
        assumeTrue("no Black 2 ROM at $rom", rom.isFile)
        activeGame = GameKind.BLACK2
        dir = kotlin.io.path.createTempDirectory("nds-art").toFile().also { assertTrue(NdsArt.extractTo(rom.toOkioPath(), it.toOkioPath())); NdsArt.dir = it.toOkioPath() }
        val v = buildSnapshotView(readGen5Telemetry(FixtureMemoryReader.load("black2"), GEN5_BLACK2))
        val t = v.pokedex!!.tables
        assertTrue(MoveLearning.available(t, v.guideTables))
        // A retail cart: its move records give the advice (DsMoveStats), Gen 5's sure-hit 101 read as Gen 3's 0.
        assertTrue(MoveLearning.advises)
        assertTrue(DsMoveStats.load())
        assertEquals(MoveStats("", 100, 15, 0, 0).copy(effect = vanillaMoveStats[57]!!.effect, effectChance = vanillaMoveStats[57]!!.effectChance),
            DsMoveStats.stats(57))                       // SURF
        assertEquals(1, DsMoveStats.stats(98)!!.priority) // QUICK ATTACK
        assertEquals(0, DsMoveStats.stats(129)!!.accuracy) // SWIFT never misses
        assertTrue(DsMoveStats.stats(468) != null)        // a Gen 5 move: stats from the cart, no Gen 3 effect
        assertEquals("", DsMoveStats.stats(468)!!.effect)
        val lead = v.party[0]
        // At level 1 every learnset move it doesn't know is still to come: the first four, in level order.
        val young = lead.copy(level = 1)
        val levels = MoveLearning.options(t, v.guideTables, young, emptyList())
        val expected = DexDetails.levelUp(t, v.guideTables, lead.species)!!
            .filter { m -> lead.moves.none { it.id == m.move } }.distinctBy { it.move }.take(4)
        assertEquals(expected.map { it.move }, levels.map { it.move })
        assertTrue(levels.all { it.tm.isEmpty() })
        // ... each with a verdict, or none (UNSURE) for a status move past the original games'
        levels.forEach { o -> if (MoveAdvisor.unsure(o.move)) assertEquals(Verdict.UNSURE, o.advice.verdict) else assertTrue(o.advice.verdict != Verdict.UNSURE) }
        // The bag's machines: each one the lead can learn and doesn't know, under the bag's own name.
        val tms = MoveLearning.options(t, v.guideTables, lead, v.items).filter { it.tm.isNotEmpty() }
        assertTrue(tms.isNotEmpty())
        val teach = DexDetails.teachable(t, v.guideTables, lead.species)!!.associate { it.label to it.move }
        val bagNames = v.items.map { it.name.uppercase().replace(" ", "") }.toSet()
        tms.forEach { o ->
            assertEquals(teach[o.tm], o.move)
            assertTrue(o.tm in bagNames)
            assertTrue(lead.moves.none { it.id == o.move })
        }
        // The bag tab's TM row: each machine item's move, and who can learn it
        val tmItems = v.items.filter { MoveLearning.isTm(it.itemId) }
        assertTrue(tmItems.isNotEmpty())
        tmItems.forEach { item ->
            assertEquals(item.name.uppercase().replace(" ", ""), MoveLearning.tmLabel(item.itemId))
            val move = MoveLearning.tmMove(t, item.itemId)!!
            assertEquals(teach[MoveLearning.tmLabel(item.itemId)] ?: move, move)
            val learners = MoveLearning.learners(t, v.guideTables, v.party, item.itemId)!!
            assertEquals(v.party.size, learners.size)
        }
        assertEquals("TM93", MoveLearning.tmLabel(618))
        assertEquals("HM01", MoveLearning.tmLabel(420))
        assertTrue(MoveLearning.tmMoveNames(t).isNotEmpty())
    }

    @Test fun `Platinum's move stats`() {
        val pl = File(File(RETAIL_ROM_DIR).parentFile, "nds/Pokemon - Platinum Version (USA) (Rev 1).nds")
        assumeTrue("no Platinum ROM at $pl", pl.isFile)
        activeGame = GameKind.PLATINUM
        dir = kotlin.io.path.createTempDirectory("nds-art").toFile().also { assertTrue(NdsArt.extractTo(pl.toOkioPath(), it.toOkioPath())); NdsArt.dir = it.toOkioPath() }
        assertTrue(DsMoveStats.load())
        assertEquals(100, DsMoveStats.stats(57)!!.accuracy)   // SURF
        assertEquals(15, DsMoveStats.stats(57)!!.pp)
        assertEquals(1, DsMoveStats.stats(98)!!.priority)
        assertEquals(0, DsMoveStats.stats(129)!!.accuracy)   // SWIFT: Gen 4 says 0
        assertFalse(DsMoveStats.physical(57))
        assertTrue(DsMoveStats.physical(98))
    }
}
