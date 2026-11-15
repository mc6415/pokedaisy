package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * TRADE EVOLUTIONS on Red / Blue / Yellow ([Gen1TradeEvo]): which party Pokémon evolve, read from the cart's
 * EvosMovesPointerTable, the routine and its call. Over a fake RAM and ROM laid out as Red's; the carts' own tables when
 * they're here.
 */
class Gen1TradeEvoTest {
    private val evo = Gen1TradeEvo.RED_BLUE

    private class Fake : GameCalls {
        val ram = HashMap<Long, Byte>()
        val rom = ByteArray(0x80000)
        val calls = ArrayList<List<Int>>()
        var loopComesBy = true
        override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int) = 0L
        override fun read(addr: Long, len: Int) = ByteArray(len) { ram[addr + it] ?: 0 }
        override fun write(addr: Long, bytes: ByteArray): Boolean { bytes.forEachIndexed { i, b -> ram[addr + i] = b }; return true }
        override fun rom(off: Long, len: Int) = rom.copyOfRange(off.toInt(), off.toInt() + len)
        override fun gbCallAt(at: Int, fn: Int, a: Int, b: Int, hl: Int): Boolean {
            calls += listOf(at, fn, a, b, hl)
            return loopComesBy
        }
        fun put(addr: Long, vararg v: Int) = v.forEachIndexed { i, x -> ram[addr + i] = x.toByte() }

        init {
            val ptrs = 0x0E * 0x4000 + (0x705C - 0x4000)
            var at = 0x7800
            // KADABRA (0x26): trade -> ALAKAZAM (0x95). PIKACHU (0x54): an item. CHARMANDER (0xB0): a level.
            val lists = mapOf(0x26 to intArrayOf(3, 1, 0x95, 0), 0x54 to intArrayOf(2, 0x21, 1, 0x55, 0), 0xB0 to intArrayOf(1, 16, 0xB2, 0))
            for ((sp, l) in lists) {
                rom[ptrs + (sp - 1) * 2] = (at and 0xFF).toByte(); rom[ptrs + (sp - 1) * 2 + 1] = (at shr 8).toByte()
                l.forEachIndexed { i, b -> rom[0x0E * 0x4000 + (at - 0x4000) + i] = b.toByte() }
                at += 16
            }
        }

        /** Party slot [slot]: [species] (internal), OT ID 0x1234, DVs 0xABCD. */
        fun mon(slot: Int, species: Int) {
            put(0xD164L + slot, species)
            val m = 0xD16BL + slot * 44
            put(m, species); put(m + 12, 0x12, 0x34); put(m + 27, 0xAB, 0xCD)
        }
    }

    private fun key(species: Int) = gbMonKey(species, 0x1234, 0xABCD)

    @Test
    fun targetsFollowTheTable() {
        val g = Fake()
        g.put(0xD163L, 3)
        g.mon(0, 0x26)   // KADABRA: evolves, into ALAKAZAM, No. 65
        g.mon(1, 0x54)   // PIKACHU: a stone, not a trade
        g.mon(2, 0xB0)   // CHARMANDER: a level
        assertEquals(mapOf(key(0x26) to 65), evo.targets(g))
    }

    @Test
    fun startCallsTheRoutineFromTheLoop() {
        val g = Fake()
        g.put(0xD163L, 2)
        g.mon(0, 0x54)
        g.mon(1, 0x26)
        assertEquals(TradeEvoResult.EVOLVING, evo.start(g, 1, key(0x26)))
        // Bankswitch from OverworldLoopLessDelay into wEnemyMonOT.
        assertEquals(listOf(listOf(0x0405, 0x35D6, 0, 1, 0xD9AC)), g.calls)
        // The routine run headless on Blue (KADABRA / HAUNTER) and Red (GRAVELER), byte for byte.
        val want = listOf(
            0xF0, 0xB8, 0xF5,
            0x3E, 0x01, 0xEA, 0x0C, 0xCF, 0x06, 0x01, 0x21, 0x96, 0x70, 0xCD, 0xD6, 0x35, 0xAF, 0xEA, 0x0C, 0xCF,
            0xCD, 0xF4, 0x36,
            0x3E, 0x01, 0xEA, 0x92, 0xCF,
            0x3E, 0x32, 0xEA, 0x2B, 0xD1, 0x06, 0x0E, 0x21, 0x0E, 0x6D, 0xCD, 0xD6, 0x35, 0xAF, 0xEA, 0x2B, 0xD1,
            0xCD, 0x90, 0x30, 0xCD, 0xBE, 0x3D, 0xCD, 0x07, 0x23,
            0xC3, 0xE8, 0x29,
        )
        assertEquals(want, g.read(0xD9AC, want.size).map { it.toInt() and 0xFF })
        assertTrue(evo.taken(g))
    }

    @Test
    fun startRefuses() {
        val g = Fake()
        g.put(0xD163L, 2)
        g.mon(0, 0x54)
        g.mon(1, 0x26)
        assertEquals(TradeEvoResult.MOVED, evo.start(g, 0, key(0x26)))             // another Pokémon in the slot
        assertEquals(TradeEvoResult.MOVED, evo.start(g, 3, key(0x26)))             // past the party
        assertEquals(TradeEvoResult.NO_EVOLUTION, evo.start(g, 0, key(0x54)))      // it doesn't evolve by trading
        g.put(0xD057L, 1)
        assertEquals(TradeEvoResult.NOT_NOW, evo.start(g, 1, key(0x26)))           // in a battle
        g.put(0xD057L, 0); g.put(0xCFC4L, 1)
        assertEquals(TradeEvoResult.NOT_NOW, evo.start(g, 1, key(0x26)))           // a text box up
        g.put(0xCFC4L, 0); g.put(0xCFC5L, 3)
        assertEquals(TradeEvoResult.NOT_NOW, evo.start(g, 1, key(0x26)))           // mid-step
        g.put(0xCFC5L, 0)
        g.loopComesBy = false
        assertEquals(TradeEvoResult.NOT_NOW, evo.start(g, 1, key(0x26)))           // the loop didn't come by
        assertTrue(g.calls.size == 1)
    }

    /** The carts' own tables: Gen 1's four trade evolutions and the code checks. Skips a cart that isn't here. */
    @Test
    fun theCartsTables() {
        val dir = File(System.getProperty("romDir") ?: System.getenv("POKEDAISY_ROM_DIR") ?: return).parentFile.resolve("gbc")
        val carts = listOf(
            "Pokemon - Yellow Version - Special Pikachu Edition (USA, Europe) (CGB+SGB Enhanced).gb" to Gen1TradeEvo.YELLOW,
            "Pokemon - Red Version (USA, Europe) (SGB Enhanced).gb" to Gen1TradeEvo.RED_BLUE,
            "Pokemon - Blue Version (USA, Europe) (SGB Enhanced).gb" to Gen1TradeEvo.RED_BLUE,
        ).filter { dir.resolve(it.first).isFile }
        assumeTrue(carts.isNotEmpty())
        // KADABRA, MACHOKE, GRAVELER, HAUNTER -> ALAKAZAM, MACHAMP, GOLEM, GENGAR; PIKACHU, EEVEE, CHARMANDER, ALAKAZAM none.
        val want = mapOf(0x26 to 0x95, 0x29 to 0x7E, 0x27 to 0x31, 0x93 to 0x0E, 0x54 to 0, 0x66 to 0, 0xB0 to 0, 0x95 to 0)
        for ((name, e) in carts) {
            val b = dir.resolve(name).readBytes()
            val rom: (Long, Int) -> ByteArray? = { off, len -> b.copyOfRange(off.toInt(), off.toInt() + len) }
            assertTrue(name, e.codeMatches(rom))
            assertFalse(name, (if (e === Gen1TradeEvo.YELLOW) Gen1TradeEvo.RED_BLUE else Gen1TradeEvo.YELLOW).codeMatches(rom))
            for ((sp, t) in want) assertEquals("$name 0x${sp.toString(16)}", t, e.target(rom, sp))
        }
    }
}
