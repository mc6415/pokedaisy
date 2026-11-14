package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * TRADE EVOLUTIONS on Gold / Silver / Crystal ([GbTradeEvo]): which party Pokémon evolve, read from the cart's EvosAttacks,
 * and the queued script. Over a fake RAM and ROM laid out as Crystal's; the carts' own tables when they're here.
 */
class GbTradeEvoTest {
    private val evo = GbTradeEvo.CRYSTAL

    /** Crystal's RAM and a ROM holding EvosAttacksPointers -> one entry list per species we use. */
    private class Fake : GameCalls {
        val ram = HashMap<Long, Byte>()
        val rom = ByteArray(0x200000)
        override fun call(fn: Long, a0: Int, a1: Int, a2: Int, a3: Int) = 0L
        override fun read(addr: Long, len: Int) = ByteArray(len) { ram[addr + it] ?: 0 }
        override fun write(addr: Long, bytes: ByteArray): Boolean { bytes.forEachIndexed { i, b -> ram[addr + i] = b }; return true }
        override fun rom(off: Long, len: Int) = rom.copyOfRange(off.toInt(), off.toInt() + len)
        fun put(addr: Long, vararg v: Int) = v.forEachIndexed { i, x -> ram[addr + i] = x.toByte() }

        init {
            val ptrs = 0x10 * 0x4000 + (0x65B1 - 0x4000)
            var at = 0x7000
            // KADABRA (64): trade, any item -> 65. ONIX (95): trade with METAL COAT (0x8F) -> 208. PIKACHU (25): an item.
            // GOLBAT (42): a happiness entry only. SLOWPOKE (79): a level entry, then trade with KING'S ROCK -> SLOWKING 199.
            val lists = mapOf(
                64 to intArrayOf(3, 0xFF, 65, 0), 95 to intArrayOf(3, 0x8F, 208, 0), 25 to intArrayOf(2, 0x16, 26, 0),
                42 to intArrayOf(4, 1, 169, 0), 79 to intArrayOf(1, 37, 80, 3, 0x52, 199, 0),
            )
            for ((sp, l) in lists) {
                rom[ptrs + (sp - 1) * 2] = (at and 0xFF).toByte(); rom[ptrs + (sp - 1) * 2 + 1] = (at shr 8).toByte()
                l.forEachIndexed { i, b -> rom[0x10 * 0x4000 + (at - 0x4000) + i] = b.toByte() }
                at += 16
            }
        }

        /** Party slot [slot]: [species] holding [item], OT ID 0x1234, DVs 0xABCD. */
        fun mon(slot: Int, species: Int, item: Int) {
            put(wramx(1, 0xDCD8) + slot, species)
            val m = wramx(1, 0xDCDF) + slot * 0x30
            put(m, species, item); put(m + 6, 0x12, 0x34); put(m + 21, 0xAB, 0xCD)
        }
    }

    private fun key(species: Int) = gbMonKey(species, 0x1234, 0xABCD)

    @Test
    fun targetsFollowTheTable() {
        val g = Fake()
        g.put(wramx(1, 0xDCD7), 6)
        g.mon(0, 64, 0)            // KADABRA: evolves
        g.mon(1, 95, 0x8F)         // ONIX holding a Metal Coat: evolves
        g.mon(2, 95, 0)            // ONIX holding nothing: no
        g.mon(3, 64, GbTradeEvo.EVERSTONE)   // KADABRA holding an Everstone: no
        g.mon(4, 79, 0x52)         // SLOWPOKE holding a King's Rock: past its level entry, evolves
        g.mon(5, 25, 0x16)         // PIKACHU with a stone held: an item evolution, not a trade
        val t = evo.targets(g)
        assertEquals(mapOf(key(64) to 65, key(95) to 208, key(79) to 199), t)
        // An egg in wPartySpecies is never one.
        g.put(wramx(1, 0xDCD8), GbTradeEvo.EGG)
        assertEquals(setOf(key(95), key(79)), evo.targets(g).keys)
    }

    @Test
    fun startQueuesTheScript() {
        val g = Fake()
        g.put(wramx(1, 0xDCD7), 2)
        g.mon(0, 25, 0)
        g.mon(1, 64, 0)
        assertEquals(TradeEvoResult.EVOLVING, evo.start(g, 1, key(64)))
        // The script: callasm 01:C7EE, reloadmap, end; the routine: link mode 2, farcall 10:61D8, link mode 0, ret.
        val bytes = g.read(0xC7E8, 22).map { it.toInt() and 0xFF }
        assertEquals(listOf(0x0E, 0x01, 0xEE, 0xC7, 0x7B, 0x91), bytes.take(6))
        assertEquals(listOf(0x3E, 0x02, 0xEA, 0xDC, 0xC2, 0x3E, 0x10, 0x21, 0xD8, 0x61, 0xCF, 0xAF, 0xEA, 0xDC, 0xC2, 0xC9), bytes.drop(6))
        assertEquals(1, g.read(wramx(1, 0xD109), 1)[0].toInt())
        assertEquals(listOf(1, 1, 0xE8, 0xC7), g.read(wramx(1, 0xD45C), 4).map { it.toInt() and 0xFF })
        // Not taken by the game a step later: withdrawn.
        assertTrue(!evo.taken(g))
        assertEquals(0, g.read(wramx(1, 0xD45C), 1)[0].toInt())
        assertTrue(evo.taken(g))
    }

    @Test
    fun startRefuses() {
        val g = Fake()
        g.put(wramx(1, 0xDCD7), 2)
        g.mon(0, 25, 0)
        g.mon(1, 64, 0)
        assertEquals(TradeEvoResult.MOVED, evo.start(g, 0, key(64)))           // another Pokémon in the slot
        assertEquals(TradeEvoResult.MOVED, evo.start(g, 3, key(64)))           // past the party
        assertEquals(TradeEvoResult.NO_EVOLUTION, evo.start(g, 0, key(25)))    // it doesn't evolve by trading
        g.put(wramx(1, 0xD22D), 1)
        assertEquals(TradeEvoResult.NOT_NOW, evo.start(g, 1, key(64)))         // in a battle
        g.put(wramx(1, 0xD22D), 0); g.put(wramx(1, 0xD438), 1)
        assertEquals(TradeEvoResult.NOT_NOW, evo.start(g, 1, key(64)))         // a script running
        g.put(wramx(1, 0xD438), 0); g.put(wramx(1, 0xD45C), 1)
        assertEquals(TradeEvoResult.NOT_NOW, evo.start(g, 1, key(64)))         // something queued already
    }

    /** The carts' own EvosAttacks: Gen 2's trade evolutions (with their items) and the code checks. Skips a cart that isn't here. */
    @Test
    fun theCartsTables() {
        val dir = File(System.getProperty("romDir") ?: System.getenv("POKEDAISY_ROM_DIR") ?: return).parentFile.resolve("gbc")
        val carts = listOf(
            "Pokemon - Crystal Version (USA, Europe) (Rev 1).gbc" to GbTradeEvo.CRYSTAL,
            "Pokemon - Gold Version (USA, Europe) (SGB Enhanced) (GB Compatible).gbc" to GbTradeEvo.GOLD_SILVER,
            "Pokemon - Silver Version (USA, Europe) (SGB Enhanced) (GB Compatible).gbc" to GbTradeEvo.GOLD_SILVER,
        ).filter { dir.resolve(it.first).isFile }
        assumeTrue(carts.isNotEmpty())
        // species, held item -> target: KADABRA, MACHOKE, GRAVELER, HAUNTER (any item); ONIX / SCYTHER + Metal Coat,
        // POLIWHIRL / SLOWPOKE + King's Rock, SEADRA + Dragon Scale, PORYGON + Up-Grade.
        val want = mapOf(
            (64 to 0) to 65, (67 to 0) to 68, (75 to 0) to 76, (93 to 0) to 94, (95 to 0x8F) to 208, (123 to 0x8F) to 212,
            (61 to 0x52) to 186, (79 to 0x52) to 199, (117 to 0x97) to 230, (137 to 0xAC) to 233,
            (95 to 0) to 0, (64 to GbTradeEvo.EVERSTONE) to 0, (25 to 0) to 0, (133 to 0) to 0,
        )
        for ((name, e) in carts) {
            val b = dir.resolve(name).readBytes()
            val rom: (Long, Int) -> ByteArray? = { off, len -> b.copyOfRange(off.toInt(), off.toInt() + len) }
            assertTrue(name, e.codeMatches(rom))
            for ((k, t) in want) assertEquals("$name ${k.first} holding ${k.second}", t, e.target(rom, k.first, k.second))
        }
    }
}
