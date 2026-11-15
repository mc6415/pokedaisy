package com.pokedaisy.app.companion.data

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import okio.Path

/**
 * REUSABLE TMS (SETTINGS > GAMEPLAY, GitHub #135): a TM stays in the bag after it teaches a move, as an HM does.
 *
 * The games use a TM up in the party menu's code, right after the move goes in: `if (item < ITEM_HM01)
 * RemoveBagItem(item, 1)`, which agbcc built as `movs r0, #0xA9; lsls r0, r0, #1; cmp rN, r0; bhi skip; adds r0, rN,
 * #0; movs r1, #1; bl RemoveBagItem` (rN = r1 or r4, the item). Turning that `bhi` into a plain `b` skips the removal
 * for every TM, so each one acts like an HM - and nothing else changes (the friendship it gives, the quest log, selling
 * and tossing all stay the game's). The FireRed engine has three such spots (CB2_UseItem after the TM case's animation,
 * CB2_UseTMHMAfterForgettingMove, Task_LearnedMove), Emerald and Ruby / Sapphire one (the party menu's last step).
 *
 * Found in the ROM by that code, no table per game ([find]): every site must call the same function, and there must be
 * as many as the engine has ([expectedSites], by the header's game code) - so retail in every language and revision,
 * and the hacks that kept that code (Emerald Legacy's agbcc rebuild, Kaizo, Orange Islands, FireRed Rocket Edition,
 * Ultra Violet, Celia), all get it, and a hack that changed some of them (Ash Gray, Ghost Grey) gets none: it would
 * still use TMs up on the other paths. Radical Red's, Unbound's and SoulGold's TMs never run out by themselves ([BUILT_IN]), nor
 * do Gen 5's (Black).
 *
 * Patched through [com.pokedaisy.app.MgbaCore.pkRomPatch]: only where the ROM holds the whole site, the file's bytes
 * kept for the Poller's hash and RetroAchievements' (pk_cheats.c).
 */
object ReusableTms {
    /** One spot: [expect] is the ROM from [at] (the compare through the RemoveBagItem call); the branch to patch sits
     * [BRANCH] bytes in. */
    class Site(val at: Long, val expect: ByteArray, val branch: Long = at + BRANCH, val value: ByteArray = ALWAYS_SKIP) {
        /** Where its call goes: the build's RemoveBagItem. */
        val calls: Long get() = blTarget(at + 12, expect, 12)
    }

    /** Where the `bhi` sits in a [Site]: after `movs`, `lsls`, `cmp`. */
    const val BRANCH = 6L
    /** A site's length: the six instructions and the call. */
    const val SITE_LEN = 16
    /** `b` to the same place as the `bhi` it replaces (offset 3 halfwords, as the `bhi`'s). */
    val ALWAYS_SKIP = byteArrayOf(0x03, 0xE0.toByte())

    /** Games whose own TMs never run out, so the row says so instead of offering it: Radical Red, Unbound and SoulGold
     * checked headless (a TM taught on their saves stayed in the bag: SoulGold v1.1.4 and v1.2's third build), Prism and
     * Polished Crystal (a bit per TM). */
    /** The DS carts the RAM patch was proved on (nds/pk_nds.cpp's TM_PATCHES: the party menu's TeachMove keeps the TM),
     * by game code and revision. Black, White and their sequels keep TMs by themselves ([BUILT_IN]). */
    val DS_CARTS = setOf("CPUE" to 1, "IPKE" to 0, "IPGE" to 0)

    fun dsProved(code: String, revision: Int?): Boolean = (code to revision) in DS_CARTS

    val BUILT_IN = setOf(GameKind.RADICAL_RED, GameKind.UNBOUND, GameKind.SOULGOLD, GameKind.PRISM, GameKind.POLISHED_CRYSTAL, GameKind.BLACK, GameKind.WHITE, GameKind.BLACK2, GameKind.WHITE2)

    /** How many sites the engine behind [code] (the header's game code) has: the FireRed engine (FireRed, LeafGreen,
     * Ultra Violet's PUVV) three, Emerald / Ruby / Sapphire one; 0 = not a Gen 3 code we know. */
    fun expectedSites(code: String): Int = when {
        code.startsWith("BPR") || code.startsWith("BPG") || code.startsWith("PUV") -> 3
        code.startsWith("BPE") || code.startsWith("AXV") || code.startsWith("AXP") -> 1
        else -> 0
    }

    /**
     * Gold / Silver / Crystal: pret's ConsumeTM (engine/items/tmhm.asm, TeachTMHM's one call after a TM's move went in:
     * `call ConvertCurItemIntoCurTMHM; ld a, [wTempTMHM]; dec a; ld hl, wTMsHMs; ...; dec a; ld [hl], a`) starts with a
     * `ret` (C9), so the TM's count is never lowered - everything else, the friendship it gives included, stays the game's.
     * Its 20 bytes in the ROM file, by the English carts' hashes (every Crystal release keeps it at 0B:4B0C, Gold and
     * Silver at 0B:4911), checked again by the patch itself. Taught in the game's own PACK headless on all three: TM10 kept
     * at 3 (2 without the patch).
     */
    private class GbConsumeTm(val sha1s: Set<String>, val offset: Long, val hex: String)

    private val GB_CONSUME_TM = listOf(
        GbConsumeTm(TelemetrySampler.CRYSTAL_SHA1S, 0x2CB0C, "cda747fa65d23d2159d806004f097ea7c83d77c0"),
        GbConsumeTm(setOf(TelemetrySampler.GOLD_SHA1, TelemetrySampler.SILVER_SHA1), 0x2C911, "cdb245fa51d13d217ed506004f097ea7c83d77c0"),
    )

    /** `ret`, over ConsumeTM's first byte. */
    val GB_RET = byteArrayOf(0xC9.toByte())

    /** A Game Boy cart's site ([GB_CONSUME_TM]) for the ROM file whose SHA-1 is [sha1], if its bytes are ConsumeTM's. */
    fun gbSites(sha1: String, rom: ByteArray): List<Site> {
        val c = GB_CONSUME_TM.firstOrNull { sha1 in it.sha1s } ?: return emptyList()
        val expect = c.hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray()
        val at = c.offset.toInt()
        if (rom.size < at + expect.size || !rom.copyOfRange(at, at + expect.size).contentEquals(expect)) return emptyList()
        return listOf(Site(c.offset, expect, branch = c.offset, value = GB_RET))
    }

    private val cache = HashMap<String, List<Site>>()

    private val lock = SynchronizedObject()

    /** The sites in [rom], all or none. Reads the file (a window at a time, ~100 ms for 32 MB): call it off the UI thread; cached
     * per file. */
    fun find(rom: Path): List<Site> {
        val key = fileKey(rom)
        synchronized(lock) { cache[key]?.let { return it } }
        val found = runCatching {
            withRomFile(rom) { find(FileLittleEndian(it)) }
        }.getOrDefault(emptyList())
        synchronized(lock) { cache[key] = found }
        return found
    }

    fun find(rom: LittleEndianReader): List<Site> {
        if (rom.limit() < 0xC0) return emptyList()
        val code = asciiString(ByteArray(4) { rom.get(0xAC + it) })
        val want = expectedSites(code)
        if (want == 0) return emptyList()
        val sites = ArrayList<Site>()
        var o = 0
        val end = rom.limit() - SITE_LEN
        while (o <= end) {
            // movs r0, #0xA9 = A9 20; lsls r0, r0, #1 = 40 00.
            if (rom.get(o) == 0xA9.toByte() && rom.get(o + 1) == 0x20.toByte() && rom.getShort(o + 2) == 0x0040.toShort()) {
                val cmp = rom.getShort(o + 4).toInt() and 0xFFFF
                val n = cmp and 7
                if (cmp == 0x4280 or n &&                                              // cmp rN, r0
                    rom.getShort(o + 6) == 0xD803.toShort() &&                         // bhi past the call
                    (rom.getShort(o + 8).toInt() and 0xFFFF) == (0x1C00 or (n shl 3)) && // adds r0, rN, #0
                    rom.getShort(o + 10) == 0x2101.toShort() &&                        // movs r1, #1
                    (rom.getShort(o + 12).toInt() and 0xF800) == 0xF000 &&             // bl
                    (rom.getShort(o + 14).toInt() and 0xF800) == 0xF800
                ) {
                    sites += Site(0x08000000L + o, ByteArray(SITE_LEN) { rom.get(o + it) })
                }
            }
            o += 2
        }
        return sites.takeIf { it.size == want && it.map { s -> s.calls }.distinct().size == 1 } ?: emptyList()
    }

    private fun blTarget(at: Long, b: ByteArray, off: Int): Long {
        val h1 = (b[off].toInt() and 0xFF) or ((b[off + 1].toInt() and 0xFF) shl 8)
        val h2 = (b[off + 2].toInt() and 0xFF) or ((b[off + 3].toInt() and 0xFF) shl 8)
        var hi = h1 and 0x7FF
        if (hi and 0x400 != 0) hi -= 0x800
        return at + 4 + (hi.toLong() shl 12) + ((h2 and 0x7FF).toLong() shl 1)
    }
}
