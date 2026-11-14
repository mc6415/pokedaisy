package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Gen 2's shiny check on DVs (pokecrystal's CheckShininess) and Polished Crystal's personality bit. */
class GbShinyTest {
    @Test fun gen2RuleOverEveryDvPair() {
        // Attack 2, 3, 6, 7, 10, 11, 14, 15 with Defense, Speed and Special 10: 8 of 65536 pairs.
        var count = 0
        for (atkDef in 0..0xFF) for (spdSpc in 0..0xFF) if (gen2ShinyDvs(atkDef, spdSpc)) {
            count++
            assertTrue(atkDef shr 4 in setOf(2, 3, 6, 7, 10, 11, 14, 15))
            assertEquals(10, atkDef and 0x0F)
            assertEquals(0xAA, spdSpc)
        }
        assertEquals(8, count)
        assertTrue(gen2ShinyDvs(0xAA, 0xAA))
        assertFalse(gen2ShinyDvs(0x9A, 0xAA))
        assertFalse(gen2ShinyDvs(0xFF, 0xFF))
    }

    @Test fun polishedBit7() {
        assertTrue(polishedShiny(0x80))
        assertTrue(polishedShiny(0xFF))
        assertFalse(polishedShiny(0x7F))
    }

    @Test fun onlyRetailGen1Trades() {
        assertTrue(GameKind.RED.tradesToGen2)
        assertTrue(GameKind.BLUE.tradesToGen2)
        assertTrue(GameKind.YELLOW.tradesToGen2)
        assertFalse(GameKind.PURE_RED.tradesToGen2)
        assertFalse(GameKind.YELLOW_LEGACY.tradesToGen2)
        assertFalse(GameKind.CRYSTAL.tradesToGen2)
        assertFalse((null as GameKind?).tradesToGen2)
    }

    @Test fun gen1PartyMonDecodesTradeShiny() {
        // A 44-byte party_struct with DVs Atk 10 / Def 10, Spd 10 / Spc 10 at +27 / +28.
        val raw = ByteArray(44)
        raw[0] = 0x54 // PIKACHU's internal index
        raw[33] = 5 // level
        raw[27] = 0xAA.toByte(); raw[28] = 0xAA.toByte()
        assertTrue(decodeGen1PartyMon(raw, 0, GEN1_YELLOW)!!.gen2Shiny)
        raw[27] = 0x9A.toByte()
        assertFalse(decodeGen1PartyMon(raw, 0, GEN1_YELLOW)!!.gen2Shiny)
    }
}
