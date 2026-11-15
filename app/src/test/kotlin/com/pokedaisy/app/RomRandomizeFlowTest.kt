package com.pokedaisy.app

import org.junit.Assert.assertFalse
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
}
