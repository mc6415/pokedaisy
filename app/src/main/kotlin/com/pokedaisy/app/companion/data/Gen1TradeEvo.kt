package com.pokedaisy.app.companion.data

/**
 * TRADE EVOLUTIONS on Red / Blue / Yellow: the game's own evolution, run the way its link trade runs it. pret's
 * TryEvolvingMon (engine/pokemon/evos_moves.asm) evolves the party slot in wWhichPokemon by its EvosMovesPointerTable
 * entry; an EVOLVE_TRADE one passes only while wLinkState is LINK_STATE_TRADING, which also keeps the evolution from
 * putting the map's tiles and music back (a trade has its own screen to go back to). Its scene, texts, Pokédex flags
 * and new moves are the game's.
 *
 * Gen 1 has no script queue (GbField's PC is the same story): a routine is written into wEnemyMonOT (66 bytes only a
 * battle or a trade sets up) and called from the overworld loop through the game's own Bankswitch
 * (GameCalls.gbCallAt). It brackets the evolution the way DisplayTextID brackets a text on the field - the ROM bank
 * pushed, DisplayTextIDInit (no text box), then CloseTextDisplay, which pops it and redraws the map and its people -
 * with the screen saved before and the tileset, the sprites, the palette and the map's music put back after:
 *
 *     ldh a, [hLoadedROMBank] / push af
 *     wAutoTextBoxDrawingControl = 1, callfar DisplayTextIDInit, wAutoTextBoxDrawingControl = 0
 *     call SaveScreenTilesToBuffer2
 *     wWhichPokemon = slot, wLinkState = LINK_STATE_TRADING, callfar TryEvolvingMon, wLinkState = 0
 *     call ReloadTilesetTilePatterns / RestoreScreenTilesAndReloadTilePatterns / PlayDefaultMusic
 *     jp CloseTextDisplay
 *
 * Which party Pokémon would evolve ([targets]) is read from the cart's own EvosMovesPointerTable (no call). English carts:
 * Yellow, and Red / Blue (one set of addresses), by pret's pokeyellow / pokered symbols, their code checked in the cart.
 * Checked headless: KADABRA to ALAKAZAM and HAUNTER to GENGAR in Blue, GRAVELER to GOLEM in Red, MACHOKE to MACHAMP in
 * Yellow, the map, its people and palette back and the player walking after (docs/DEVELOPMENT.md, beside Gold / Silver /
 * Crystal's).
 */
class Gen1TradeEvo(
    /** OverworldLoopLessDelay past its frame wait, and Bankswitch (GbField.Gen1's). */
    private val loopAfterWait: Int,
    private val bankswitch: Int,
    /** wEnemyMonOT: where the routine goes (66 bytes). */
    private val buf: Int,
    private val linkState: Int,
    private val whichPokemon: Int,
    private val autoTextBox: Int,
    private val displayTextIdInit: Pair<Int, Int>,
    private val tryEvolvingMon: Pair<Int, Int>,
    private val saveScreen: Int,
    private val reloadTileset: Int,
    private val restoreScreen: Int,
    private val playDefaultMusic: Int,
    private val closeTextDisplay: Int,
    /** wWalkCounter (wFontLoaded right before it), wStatusFlags5, wIsInBattle, wStatusFlags6. */
    private val walkCounter: Long,
    private val flags5: Long,
    private val inBattle: Long,
    private val flags6: Long,
    /** wPartyCount, then wPartySpecies. */
    private val partyCount: Long,
    /** wPartyMon1 (party_struct, 44 bytes). */
    private val partyMon1: Long,
    /** EvosMovesPointerTable's place in the ROM file (bank 0x0E). */
    private val evosPointers: Long,
    private val internalToDex: IntArray,
    private val signatures: List<Pair<Long, String>>,
) : GbTradeEvolution {
    override fun codeMatches(rom: (Long, Int) -> ByteArray?): Boolean = signatures.all { (off, hex) ->
        val want = hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        rom(off, want.size)?.contentEquals(want) == true
    }

    /** [species]' (the internal index) trade evolution from the cart's EvosMovesPointerTable, as an internal index; 0 = none. */
    fun target(rom: (Long, Int) -> ByteArray?, species: Int): Int {
        if (species !in 1..0xBE) return 0
        val ptr = rom(evosPointers + (species - 1) * 2L, 2) ?: return 0
        val addr = (ptr[0].toInt() and 0xFF) or ((ptr[1].toInt() and 0xFF) shl 8)
        if (addr !in 0x4000..0x7FFF) return 0
        val evos = rom(EVOS_BANK * 0x4000L + (addr - 0x4000), 16) ?: return 0
        var i = 0
        while (i < evos.size) {
            val type = evos[i].toInt() and 0xFF
            if (type == 0) return 0
            val len = if (type == EVOLVE_ITEM) 4 else 3
            if (i + len > evos.size) return 0
            if (type == EVOLVE_TRADE) return evos[i + 2].toInt() and 0xFF   // its level is 1: every one passes
            i += len
        }
        return 0
    }

    override fun targets(g: GameCalls): Map<Long, Int> {
        val count = u8(g, partyCount).coerceAtMost(6)
        val out = LinkedHashMap<Long, Int>()
        for (slot in 0 until count) {
            val mon = g.read(partyMon1 + slot * MON_SIZE, MON_SIZE)
            val t = target(g::rom, mon[0].toInt() and 0xFF)
            val dex = internalToDex.getOrElse(t) { 0 }
            if (t != 0 && dex != 0) out[gen1MonKey(mon, 0)] = dex
        }
        return out
    }

    /** Free to start: standing still, no text box, joypad on, no scripted move, battle or warp under way. */
    private fun free(g: GameCalls) = u8(g, walkCounter) == 0 && u8(g, walkCounter - 1) and 1 == 0 && u8(g, JOY_IGNORE) == 0 &&
        u8(g, flags5) and 0xA0 == 0 && u8(g, inBattle) == 0 && u8(g, flags6) and 0x1C == 0

    /** The routine for party slot [slot] (see the class comment). */
    fun routine(slot: Int): ByteArray {
        fun lo(v: Int) = v and 0xFF
        fun hi(v: Int) = v shr 8
        fun callfar(f: Pair<Int, Int>) = intArrayOf(0x06, f.first, 0x21, lo(f.second), hi(f.second), 0xCD, lo(bankswitch), hi(bankswitch))
        fun ldA(addr: Int) = intArrayOf(0xEA, lo(addr), hi(addr))
        fun call(addr: Int) = intArrayOf(0xCD, lo(addr), hi(addr))
        val r = intArrayOf(0xF0, H_LOADED_ROM_BANK, 0xF5) +
            intArrayOf(0x3E, 0x01) + ldA(autoTextBox) + callfar(displayTextIdInit) + intArrayOf(0xAF) + ldA(autoTextBox) +
            call(saveScreen) +
            intArrayOf(0x3E, slot) + ldA(whichPokemon) +
            intArrayOf(0x3E, LINK_STATE_TRADING) + ldA(linkState) + callfar(tryEvolvingMon) + intArrayOf(0xAF) + ldA(linkState) +
            call(reloadTileset) + call(restoreScreen) + call(playDefaultMusic) +
            intArrayOf(0xC3, lo(closeTextDisplay), hi(closeTextDisplay))
        check(r.size <= BUF_SIZE)
        return r.map { it.toByte() }.toByteArray()
    }

    override fun start(g: GameCalls, slot: Int, key: Long): TradeEvoResult {
        if (!free(g)) return TradeEvoResult.NOT_NOW
        if (slot !in 0 until u8(g, partyCount).coerceAtMost(6)) return TradeEvoResult.MOVED
        val mon = g.read(partyMon1 + slot * MON_SIZE, MON_SIZE)
        if (gen1MonKey(mon, 0) != key) return TradeEvoResult.MOVED
        if (target(g::rom, mon[0].toInt() and 0xFF) == 0) return TradeEvoResult.NO_EVOLUTION
        if (!g.write(buf.toLong(), routine(slot))) return TradeEvoResult.FAILED
        // Called from the loop at once, or not at all (the loop didn't come by: a menu, a script).
        return if (g.gbCallAt(loopAfterWait, bankswitch, 0, 1, buf)) TradeEvoResult.EVOLVING else TradeEvoResult.NOT_NOW
    }

    /** The call either happened in [start] or didn't: nothing waits. */
    override fun taken(g: GameCalls): Boolean = true

    private fun u8(g: GameCalls, addr: Long) = g.read(addr, 1)[0].toInt() and 0xFF

    companion object {
        const val EVOLVE_ITEM = 2
        const val EVOLVE_TRADE = 3
        const val EVOS_BANK = 0x0E
        const val MON_SIZE = 44
        const val LINK_STATE_TRADING = 0x32
        const val H_LOADED_ROM_BANK = 0xB8
        const val BUF_SIZE = 66
        const val JOY_IGNORE = 0xCD6BL

        /** Yellow (English): pret/pokeyellow's symbols. */
        val YELLOW = Gen1TradeEvo(
            loopAfterWait = 0x0248, bankswitch = 0x3E84, buf = 0xD9AB, linkState = 0xD12A, whichPokemon = 0xCF91,
            autoTextBox = 0xCF0C, displayTextIdInit = 0x01 to 0x6F0E, tryEvolvingMon = 0x0E to 0x6DB8, saveScreen = 0x36EC,
            reloadTileset = 0x2F83, restoreScreen = 0x3DC2, playDefaultMusic = 0x216B, closeTextDisplay = 0x28D8,
            walkCounter = 0xCFC4L, flags5 = 0xD72FL, inBattle = 0xD056L, flags6 = 0xD731L,
            partyCount = 0xD162L, partyMon1 = 0xD16AL, evosPointers = 0x0E * 0x4000L + (0x71E5 - 0x4000),
            internalToDex = gen1InternalToDexYellow,
            signatures = listOf(
                0x245L to "cd641ecd2a34cd6f1ecde10ffac4cfa7c2c303cd",   // OverworldLoopLessDelay
                0x3E84L to "f0b8f578e0b8ea0020cd983e",                   // Bankswitch
                0x3ADB8L to "21d3ccaf77fa91cf4f0601cd",                  // TryEvolvingMon
                0x6F0EL to "afea93cffa0ccfcb472023f0",                   // DisplayTextIDInit
                0x28D8L to "fa5dd3cd8b0f3e90e0b0cd64",                   // CloseTextDisplay
                0x2F83L to "f0b8f5fa5dd3cd8b0fcd6100",                   // ReloadTilesetTilePatterns
                0x3DC2L to "cd82003e01eacacfcd1e3ecd",                   // RestoreScreenTilesAndReloadTilePatterns
                0x36ECL to "21a0c31181cd016801c3b100",                   // SaveScreenTilesToBuffer2
                0x216BL to "cd3e37af4f57eac9cf18120e",                   // PlayDefaultMusic
            ),
        )

        /** Red / Blue (English; one set of addresses): pret/pokered's symbols, Yellow's RAM + 1. */
        val RED_BLUE = Gen1TradeEvo(
            loopAfterWait = 0x0405, bankswitch = 0x35D6, buf = 0xD9AC, linkState = 0xD12B, whichPokemon = 0xCF92,
            autoTextBox = 0xCF0C, displayTextIdInit = 0x01 to 0x7096, tryEvolvingMon = 0x0E to 0x6D0E, saveScreen = 0x36F4,
            reloadTileset = 0x3090, restoreScreen = 0x3DBE, playDefaultMusic = 0x2307, closeTextDisplay = 0x29E8,
            walkCounter = 0xCFC5L, flags5 = 0xD730L, inBattle = 0xD057L, flags6 = 0xD732L,
            partyCount = 0xD163L, partyMon1 = 0xD16BL, evosPointers = 0x0E * 0x4000L + (0x705C - 0x4000),
            internalToDex = gen1InternalToDexYellow,
            signatures = listOf(
                0x402L to "cdaf20cdba20fa36d7cb77c49e03fac5cfa7c2b5",   // OverworldLoopLessDelay
                0x35D6L to "f0b8f578e0b8ea002001e435",                   // Bankswitch
                0x3AD0EL to "21d3ccaf77fa92cf4f0601cd",                  // TryEvolvingMon
                0x7096L to "afea94cffa0ccfcb472026f0",                   // DisplayTextIDInit
                0x29E8L to "fa5ed3cdbc123e90e0b0cdaf",                   // CloseTextDisplay
                0x3090L to "f0b8f5fa5ed3cdbc12cd6100",                   // ReloadTilesetTilePatterns
                0x3DBEL to "cd82003e01eacbcfcd083ecd",                   // RestoreScreenTilesAndReloadTilePatterns
                0x36F4L to "21a0c31181cd016801cdb500",                   // SaveScreenTilesToBuffer2
                0x2307L to "cd4837af4f57eacacf18120e",                   // PlayDefaultMusic
            ),
        )

        /** The running cart's: the English Red, Blue and Yellow only (another language's addresses aren't mapped). */
        fun forGame(game: GameKind?, language: Char): Gen1TradeEvo? = when {
            language != 'E' -> null
            game == GameKind.YELLOW -> YELLOW
            game == GameKind.RED || game == GameKind.BLUE -> RED_BLUE
            else -> null
        }
    }
}
