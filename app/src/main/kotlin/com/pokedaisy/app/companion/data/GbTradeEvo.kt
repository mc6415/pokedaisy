package com.pokedaisy.app.companion.data

/**
 * TRADE EVOLUTIONS on Gold / Silver / Crystal: the game's own evolution, run the way its link trade runs it. pret's
 * EvolvePokemon (engine/pokemon/evolve.asm) evolves the party slot in wCurPartyMon by its EvosAttacks entry; an
 * EVOLVE_TRADE one passes only while wLinkMode is set (and not to the Time Capsule) and the Pokémon holds no Everstone,
 * and a held item it needs is taken off by the evolution itself. The evolution stones already call it from the field's
 * party menu, so its scene, texts, Pokédex entry and new moves are the game's.
 *
 * From the free field, a map script is queued (RunMemScript: wMapReentryScriptQueueFlag / Bank / Address, as PC and
 * FLY are, [GbField]) whose bytes sit in wUnusedMapBuffer (24 bytes left from the 1997 prototype, only zeroed when a map
 * loads): `callasm` a routine right after it - wLinkMode = LINK_TRADECENTER, farcall EvolvePokemon, wLinkMode = 0 -
 * then `reloadmap` (MapSetupScript_ReloadMap: the map, its palettes and its music back) and `end`. wCurPartyMon is
 * written when the script is queued, as FLY writes wDefaultSpawnpoint.
 *
 * Which party Pokémon would evolve ([targets]) is read from the cart's own EvosAttacks (no call), with the held item and
 * an Everstone counted as EvolvePokemon counts them. Addresses are pret's pokecrystal / pokegold symbols; every Crystal
 * release (rev 0, rev 1, AU) shares them, as do Gold and Silver. Checked headless in Crystal rev 1, Gold and Silver from
 * the fixtures' saves: KADABRA to ALAKAZAM, ONIX holding a Metal Coat to STEELIX (the coat gone), an Everstone left alone,
 * the map back with its music and the player walking after (docs/ROM_FEATURES.md, the Game Boy trade evolution note).
 */
class GbTradeEvo(
    /** wUnusedMapBuffer: the script (6 bytes), then the routine (16). */
    private val buf: Int,
    private val linkMode: Int,
    private val curPartyMon: Long,
    private val evolveBank: Int,
    private val evolveAddr: Int,
    /** wMapReentryScriptQueueFlag, then Bank, Address (lo, hi). */
    private val queue: Long,
    private val scriptRunning: Long,
    private val mapEventStatus: Long,
    private val battleMode: Long,
    /** wPartyCount, then wPartySpecies. */
    private val partyCount: Long,
    /** wPartyMon1 (party_struct, 48 bytes). */
    private val partyMon1: Long,
    /** EvosAttacksPointers' place in the ROM file (bank 0x10). */
    private val evosPointers: Long,
    private val reloadmapOp: Int,
    private val endOp: Int,
    /** The code each step relies on, by its place in the ROM file. */
    val signatures: List<Pair<Long, String>>,
) {
    fun codeMatches(rom: (Long, Int) -> ByteArray?): Boolean = signatures.all { (off, hex) ->
        val want = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        rom(off, want.size)?.contentEquals(want) == true
    }

    /** [species]' trade evolution as EvolvePokemon sees it with [item] held, from the cart's EvosAttacks; 0 = none. */
    fun target(rom: (Long, Int) -> ByteArray?, species: Int, item: Int): Int {
        if (species !in 1..251 || item == EVERSTONE) return 0
        val ptr = rom(evosPointers + (species - 1) * 2L, 2) ?: return 0
        val addr = (ptr[0].toInt() and 0xFF) or ((ptr[1].toInt() and 0xFF) shl 8)
        if (addr !in 0x4000..0x7FFF) return 0
        val evos = rom(EVOS_BANK * 0x4000L + (addr - 0x4000), 32) ?: return 0
        var i = 0
        while (i < evos.size) {
            val type = evos[i].toInt() and 0xFF
            if (type == 0) return 0
            val len = if (type == EVOLVE_STAT) 4 else 3
            if (i + len > evos.size) return 0
            if (type == EVOLVE_TRADE) {
                val need = evos[i + 1].toInt() and 0xFF
                if (need == ANY_ITEM || need == item) return evos[i + 2].toInt() and 0xFF
            }
            i += len
        }
        return 0
    }

    /** The party's trade evolutions: each evolving Pokémon by its key ([gen2MonKey]) to its target species. */
    fun targets(g: GameCalls): Map<Long, Int> {
        val count = u8(g, partyCount).coerceAtMost(6)
        val species = g.read(partyCount + 1, 6)
        val out = LinkedHashMap<Long, Int>()
        for (slot in 0 until count) {
            if ((species[slot].toInt() and 0xFF) == EGG) continue
            val mon = g.read(partyMon1 + slot * MON_SIZE, MON_SIZE)
            val t = target(g::rom, mon[0].toInt() and 0xFF, mon[1].toInt() and 0xFF)
            if (t != 0) out[gen2MonKey(mon, 0)] = t
        }
        return out
    }

    /** Free to start: no script, map event, battle or queued script. */
    private fun free(g: GameCalls) = u8(g, scriptRunning) == 0 && u8(g, mapEventStatus) == 0 && u8(g, battleMode) == 0 &&
        u8(g, queue) == 0

    /** Queues the evolution of party slot [slot], which must still hold the Pokémon [key] and still evolve by trade. */
    fun start(g: GameCalls, slot: Int, key: Long): TradeEvoResult {
        if (!free(g)) return TradeEvoResult.NOT_NOW
        if (slot !in 0 until u8(g, partyCount).coerceAtMost(6)) return TradeEvoResult.MOVED
        val mon = g.read(partyMon1 + slot * MON_SIZE, MON_SIZE)
        if (gen2MonKey(mon, 0) != key || (g.read(partyCount + 1 + slot, 1)[0].toInt() and 0xFF) == EGG) return TradeEvoResult.MOVED
        if (target(g::rom, mon[0].toInt() and 0xFF, mon[1].toInt() and 0xFF) == 0) return TradeEvoResult.NO_EVOLUTION
        val code = buf + SCRIPT_LEN
        val script = intArrayOf(CALLASM, 0x01, code and 0xFF, code shr 8, reloadmapOp, endOp)
        val routine = intArrayOf(
            0x3E, LINK_TRADECENTER, 0xEA, linkMode and 0xFF, linkMode shr 8,                // ld a, 2; ld [wLinkMode], a
            0x3E, evolveBank, 0x21, evolveAddr and 0xFF, evolveAddr shr 8, 0xCF,           // farcall EvolvePokemon
            0xAF, 0xEA, linkMode and 0xFF, linkMode shr 8, 0xC9,                           // xor a; ld [wLinkMode], a; ret
        )
        val bytes = (script + routine).map { it.toByte() }.toByteArray()
        check(bytes.size <= BUF_SIZE)
        if (!g.write(buf.toLong(), bytes)) return TradeEvoResult.FAILED
        g.write(curPartyMon, byteArrayOf(slot.toByte()))
        g.write(queue + 1, byteArrayOf(0x01, (buf and 0xFF).toByte(), (buf shr 8).toByte()))
        g.write(queue, byteArrayOf(1))
        return TradeEvoResult.EVOLVING
    }

    /** True once the game took the queued script; one it didn't take is withdrawn ([GbField.taken]'s way). */
    fun taken(g: GameCalls): Boolean {
        if (u8(g, queue) == 0) return true
        g.write(queue, byteArrayOf(0))
        return false
    }

    private fun u8(g: GameCalls, addr: Long) = g.read(addr, 1)[0].toInt() and 0xFF

    companion object {
        const val EVERSTONE = 0x70
        const val EGG = 0xFD
        const val ANY_ITEM = 0xFF
        const val EVOLVE_TRADE = 3
        const val EVOLVE_STAT = 5
        const val EVOS_BANK = 0x10
        const val MON_SIZE = 0x30
        const val LINK_TRADECENTER = 2
        const val CALLASM = 0x0E
        const val SCRIPT_LEN = 6
        const val BUF_SIZE = 24

        /** Crystal (rev 0, rev 1, Australia): pret/pokecrystal's symbols. */
        val CRYSTAL = GbTradeEvo(
            buf = 0xC7E8, linkMode = 0xC2DC, curPartyMon = wramx(1, 0xD109), evolveBank = 0x10, evolveAddr = 0x61D8,
            queue = wramx(1, 0xD45C), scriptRunning = wramx(1, 0xD438), mapEventStatus = wramx(1, 0xD433),
            battleMode = wramx(1, 0xD22D), partyCount = wramx(1, 0xDCD7), partyMon1 = wramx(1, 0xDCDF),
            evosPointers = 0x10 * 0x4000L + (0x65B1 - 0x4000), reloadmapOp = 0x7B, endOp = 0x91,
            signatures = listOf(
                0x10 * 0x4000L + (0x61D8 - 0x4000) to "21e8d1af77fa09d14f0601cd7765afea",   // EvolvePokemon
                0x25 * 0x4000L + (0x7C30 - 0x4000) to "fa5cd4a7c8215ed42a666ffa5dd4cd1f",   // RunMemScript
            ),
        )

        /** Gold / Silver (one set of addresses): pret/pokegold's symbols. */
        val GOLD_SILVER = GbTradeEvo(
            buf = 0xC6E8, linkMode = 0xD042, curPartyMon = wramx(1, 0xD005), evolveBank = 0x10, evolveAddr = 0x61DB,
            queue = wramx(1, 0xD183), scriptRunning = wramx(1, 0xD15F), mapEventStatus = wramx(1, 0xD15A),
            battleMode = wramx(1, 0xD116), partyCount = wramx(1, 0xDA22), partyMon1 = wramx(1, 0xDA2A),
            evosPointers = 0x10 * 0x4000L + (0x67BD - 0x4000), reloadmapOp = 0x7A, endOp = 0x90,
            signatures = listOf(
                0x10 * 0x4000L + (0x61DB - 0x4000) to "21d1d0af77fa05d04f0601cd7365afea",   // EvolvePokemon
                0x25 * 0x4000L + (0x7A61 - 0x4000) to "fa83d1a7c82185d12a666ffa84d1cd48",   // RunMemScript
            ),
        )

        /** The running cart's, by the config its hash picked (the English carts only), or null. */
        fun forConfig(cfg: Gen2Config?): GbTradeEvo? = when (cfg) {
            GEN2_CRYSTAL -> CRYSTAL
            GEN2_GOLD, GEN2_SILVER -> GOLD_SILVER
            else -> null
        }
    }
}
