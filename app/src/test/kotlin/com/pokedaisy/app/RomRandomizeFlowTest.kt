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
            monotype = com.pokedaisy.app.companion.data.PokeType.DRAGON)
        assertEquals(modes, RomRandomizeFlow.decode(RomRandomizeFlow.encode(modes)))
        assertEquals(o.copy(levelScale = 0), RomRandomizeFlow.decode(RomRandomizeFlow.encode(o).split(',').take(9).joinToString(",")))
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
        // Red's trainers hold nothing: CHAOS's held items aren't promised there.
        assertEquals(7, RomRandomizeFlow.summary(RomRandomizeFlow.optionsOf(RomRandomizeFlow.Style.CHAOS), red).size)
        val nothing = com.pokedaisy.app.companion.data.RomRandomizer.Options(
            com.pokedaisy.app.companion.data.RomRandomizer.Starters.OFF, com.pokedaisy.app.companion.data.RomRandomizer.Wild.OFF, false,
        )
        assertTrue(RomRandomizeFlow.summary(nothing, red).isEmpty())
    }
}
