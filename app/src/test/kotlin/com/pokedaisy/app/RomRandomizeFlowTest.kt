package com.pokedaisy.app

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** RANDOMIZE's own files are told by their names (and by reading as randomized), for the library's DELETE. */
class RomRandomizeFlowTest {
    @Test
    fun `the tool's file names are its own`() {
        assertTrue(RomRandomizeFlow.namedByTool("Pokemon FireRed Randomized (seed 874812).gba"))
        assertTrue(RomRandomizeFlow.namedByTool("Pokemon Platinum Randomized (seed 42) (2).nds"))
        assertFalse(RomRandomizeFlow.namedByTool("Pokemon - FireRed Version (USA, Europe) (Rev 1).gba"))
        assertFalse(RomRandomizeFlow.namedByTool("FireRed randomized seed 42.gba"))
        assertFalse(RomRandomizeFlow.namedByTool("Pokemon FireRed Randomized (seed 874812).zip"))
    }

    @Test
    fun `LAST TIME keeps every setting`() {
        val o = RomRandomizeFlow.optionsOf(RomRandomizeFlow.Style.CHAOS).copy(similarStrength = true, learnsets = false)
        assertEquals(o, RomRandomizeFlow.decode(RomRandomizeFlow.encode(o)))
        assertEquals(null, RomRandomizeFlow.decode(null))
        assertEquals(null, RomRandomizeFlow.decode("NOT,A,SETTING"))
        // The run modes too, and a setup saved before them (nine fields) reads with them off.
        val modes = o.copy(levelScale = 25, noDuplicates = true, allCatchable = true, trainersKeepType = true,
            monotype = com.pokedaisy.app.companion.data.PokeType.DRAGON, gifts = true)
        assertEquals(modes, RomRandomizeFlow.decode(RomRandomizeFlow.encode(modes)))
        assertEquals(o.copy(levelScale = 0, gifts = false, evolutions = false, abilities = false),
            RomRandomizeFlow.decode(RomRandomizeFlow.encode(o).split(',').take(9).joinToString(",")))
        // EVOLUTIONS and ABILITIES (CHAOS has both): a setup saved before them (fifteen fields) reads with them off.
        assertTrue(o.evolutions && o.abilities)
        assertEquals(o.copy(evolutions = false, abilities = false), RomRandomizeFlow.decode(RomRandomizeFlow.encode(o).split(',').take(15).joinToString(",")))
        // GYM TYPES, RIVAL, WILD LEVELS and HABITATS: kept, and off in a setup saved before them (seventeen fields).
        val bosses = o.copy(bossTypes = com.pokedaisy.app.companion.data.RomRandomizer.BossTypes.RANDOM, rivalStarter = true,
            wildLevelScale = 50, habitats = true)
        assertEquals(bosses, RomRandomizeFlow.decode(RomRandomizeFlow.encode(bosses)))
        assertEquals(o, RomRandomizeFlow.decode(RomRandomizeFlow.encode(bosses).split(',').take(17).joinToString(",")))
    }

    @Test
    fun `CLASSIC keeps the leaders' types and the rival's starter, and READY says so`() {
        val classic = RomRandomizeFlow.optionsOf(RomRandomizeFlow.Style.CLASSIC)
        assertEquals(com.pokedaisy.app.companion.data.RomRandomizer.BossTypes.OWN, classic.bossTypes)
        assertTrue(classic.rivalStarter)
        val red = RomRandomizeFlow.Base(java.io.File("red.gb"), "Pokémon Red", "Red", gb = true)
        val lines = RomRandomizeFlow.summary(classic.copy(wildLevelScale = 25, habitats = true), red)
        for (line in listOf("Leaders keep their own types", "Your rival carries a new starter", "Pokémon fit where they live"))
            assertTrue(line, lines.contains(com.pokedaisy.app.companion.i18n.tr(line)))
        assertTrue(lines.contains(com.pokedaisy.app.companion.i18n.tr("Wild levels up {0}%", 25)))
        // Yellow's rival carries no starter: not promised there.
        val yellow = RomRandomizeFlow.Base(java.io.File("yellow.gb"), "Pokémon Yellow", "Yellow", gb = true)
        assertTrue(!RomRandomizeFlow.summary(classic, yellow).contains(com.pokedaisy.app.companion.i18n.tr("Your rival carries a new starter")))
    }

    @Test
    fun `each style changes something, and READY says what`() {
        val red = RomRandomizeFlow.Base(java.io.File("red.gb"), "Pokémon Red", "Red", gb = true)
        for (s in listOf(RomRandomizeFlow.Style.CLASSIC, RomRandomizeFlow.Style.WILD_ONLY, RomRandomizeFlow.Style.CHAOS)) {
            val o = RomRandomizeFlow.optionsOf(s)
            assertTrue(s.name, o.changesSomething)
            assertTrue(s.name, RomRandomizeFlow.summary(o, red).isNotEmpty())
        }
        assertEquals(false, RomRandomizeFlow.optionsOf(RomRandomizeFlow.Style.WILD_ONLY).trainers)
        // Red's trainers hold nothing and its Pokémon have no abilities: CHAOS's held items and abilities aren't promised there.
        val redChaos = RomRandomizeFlow.summary(RomRandomizeFlow.optionsOf(RomRandomizeFlow.Style.CHAOS), red)
        assertEquals(9, redChaos.size)
        assertTrue(redChaos.contains(com.pokedaisy.app.companion.i18n.tr("Random evolutions")))
        assertTrue(!redChaos.contains(com.pokedaisy.app.companion.i18n.tr("Random abilities")))
        val nothing = com.pokedaisy.app.companion.data.RomRandomizer.Options(
            com.pokedaisy.app.companion.data.RomRandomizer.Starters.OFF, com.pokedaisy.app.companion.data.RomRandomizer.Wild.OFF, false,
        )
        assertTrue(RomRandomizeFlow.summary(nothing, red).isEmpty())
    }
}
