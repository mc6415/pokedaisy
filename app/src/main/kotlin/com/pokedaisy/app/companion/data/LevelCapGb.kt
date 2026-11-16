package com.pokedaisy.app.companion.data

/**
 * LEVEL CAP on the Game Boy (the English Red, Blue, Yellow, Gold, Silver, Crystal): no Pokémon past the next boss's top
 * level, as [LevelCap] does on the GBA.
 *
 * Both generations' battle EXP (pret's pokered GainExperience, pokecrystal GiveExperiencePoints) add the win's EXP, then
 * cap it at what MAX_LEVEL takes (`ld d, MAX_LEVEL` + a far call to CalcExperience / CalcExpAtLevel), then work the level out
 * from the EXP. That cap becomes ours: the level the call is asked for is the cap, or the Pokémon's own level when it's
 * already past the cap (a Rare Candy, a catch) - never lower, so no Pokémon loses a level. A Pokémon that reaches the cap
 * mid-win stops there with that level's EXP. Our few bytes go in the routine's bank's unused end, the `ld d` + far call
 * become a `call` to them and `nop`s; every byte a write replaces is checked against the cart first (all or nothing).
 * Rare Candies and the Day-Care aren't battle EXP: they still raise a level.
 *
 * Gen 1 (hand-assembled, pret's .sym addresses):
 *     ld a, [wWhichPokemon]; ld hl, wPartyMon1Level; ld bc, PARTYMON_STRUCT_LENGTH; call AddNTimes
 *     ld a, CAP; cp [hl]; jr nc, .ok; ld a, [hl]
 *  .ok: ld d, a; ld hl, CalcExperience; ld b, BANK(CalcExperience); jp Bankswitch
 * Gen 2 (bc = the party Pokémon there):
 *     ld hl, MON_LEVEL; add hl, bc; ld a, CAP; cp [hl]; jr nc, .ok; ld a, [hl]
 *  .ok: ld d, a; ld hl, CalcExpAtLevel; ld a, BANK(CalcExpAtLevel); rst FarCall; ret
 * Gen 2's battle EXP bar (AnimateExpBar) works the same sum out again on wTempMon to fill the bar; its `ld d, MAX_LEVEL` +
 * far call get the same treatment 0x20 bytes on (`ld hl, wTempMonLevel` for the first two instructions), else the bar
 * would fill past the cap on screen.
 */
object LevelCapGb {
    /** A cart's patch: [site] (the `ld d, MAX_LEVEL` + far call, [was] as the cart has it) becomes a call to [code] at
     * [at] (file offsets; [codeAddr] its banked address), the cap the byte [capAt] into the code. */
    class Cart(val name: String, val sha1s: Set<String>, val site: Int, val was: String, val at: Int, val codeAddr: Int, val code: String, val capAt: Int,
               val bar: Int = -1, val barCode: String = "", val barCapAt: Int = 0)

    private val CARTS = listOf(
        gen1("RED", setOf(TelemetrySampler.RED_SHA1), 0x5530B, "1664216a4f0616cdd635", 0x57E00, wWhich = 0xCF92, level = 0xD18C,
            addN = 0x3A87, calc = 0x4F6A, bankswitch = 0x35D6),
        gen1("BLUE", setOf(TelemetrySampler.BLUE_SHA1), 0x5530B, "1664216a4f0616cdd635", 0x57E00, wWhich = 0xCF92, level = 0xD18C,
            addN = 0x3A87, calc = 0x4F6A, bankswitch = 0x35D6),
        gen1("YELLOW", setOf(TelemetrySampler.YELLOW_SHA1), 0x5531A, "166421c04d0616cd843e", 0x57E00, wWhich = 0xCF91, level = 0xD18B,
            addN = 0x3A74, calc = 0x4DC0, bankswitch = 0x3E84),
        gen2("GOLD", setOf(TelemetrySampler.GOLD_SHA1), 0x3ED7B, 0x3EFA8, "1664215055" + "3e14cf", 0x3FF00, calc = 0x5550, tempLevel = 0xD02A),
        gen2("SILVER", setOf(TelemetrySampler.SILVER_SHA1), 0x3ED7B, 0x3EFA8, "1664215055" + "3e14cf", 0x3FF00, calc = 0x5550, tempLevel = 0xD02A),
        gen2("CRYSTAL", TelemetrySampler.CRYSTAL_SHA1S, 0x3EF4F, 0x3F186, "1664" + "21474e" + "3e14cf", 0x3FF00, calc = 0x4E47, tempLevel = 0xD12D),
    )

    private fun le(v: Int) = "%02x%02x".format(v and 0xFF, (v shr 8) and 0xFF)
    private fun bank(file: Int) = (file and 0x3FFF) + 0x4000

    private fun gen1(name: String, sha1s: Set<String>, site: Int, was: String, at: Int, wWhich: Int, level: Int, addN: Int, calc: Int, bankswitch: Int) =
        Cart(name, sha1s, site, was, at, bank(at),
            "fa" + le(wWhich) + "21" + le(level) + "012c00" + "cd" + le(addN) + "3e00" + "be" + "3001" + "7e" + "57" +
                "21" + le(calc) + "0616" + "c3" + le(bankswitch),
            capAt = 13)

    private fun gen2(name: String, sha1s: Set<String>, site: Int, bar: Int, was: String, at: Int, calc: Int, tempLevel: Int) =
        Cart(name, sha1s, site, was, at, bank(at),
            "211f00" + "09" + "3e00" + "be" + "3001" + "7e" + "57" + "21" + le(calc) + "3e14" + "cf" + "c9",
            capAt = 5, bar = bar,
            barCode = "21" + le(tempLevel) + "00" + "3e00" + "be" + "3001" + "7e" + "57" + "21" + le(calc) + "3e14" + "cf" + "c9",
            barCapAt = 5)

    /** The patch for the cart whose SHA-1 is [sha1] and whose bytes are [rom] (null: none, the row shows N/A). */
    fun find(sha1: String, rom: ByteArray): LevelCap.Found? {
        val cart = CARTS.firstOrNull { sha1 in it.sha1s } ?: return null
        val was = hex(cart.was)
        val code = hex(cart.code)
        fun same(at: Int, b: ByteArray) = at >= 0 && at + b.size <= rom.size && rom.copyOfRange(at, at + b.size).contentEquals(b)
        if (!same(cart.site, was) || !same(cart.at, ByteArray(code.size))) return null
        if (cart.bar >= 0 && (!same(cart.bar, was) || !same(cart.at + BAR, ByteArray(hex(cart.barCode).size)))) return null
        return LevelCap.Found(emptyList(), custom = { cap -> writes(cart, cap) })
    }

    /** [cart]'s writes for [cap], 4 bytes at most each (pkRomPatch's), over the bytes the cart has. */
    fun writes(cart: Cart, cap: Int): List<InstantText.Write> {
        val code = hex(cart.code).also { it[cart.capAt] = cap.toByte() }
        val was = hex(cart.was)
        val jump = ByteArray(was.size).also { j -> j[0] = 0xCD.toByte(); j[1] = cart.codeAddr.toByte(); j[2] = (cart.codeAddr shr 8).toByte() }
        val main = chunks(cart.at, ByteArray(code.size), code) + chunks(cart.site, was, jump)
        if (cart.bar < 0) return main
        val bar = hex(cart.barCode).also { it[cart.barCapAt] = cap.toByte() }
        val barAddr = cart.codeAddr + BAR
        val barJump = ByteArray(was.size).also { j -> j[0] = 0xCD.toByte(); j[1] = barAddr.toByte(); j[2] = (barAddr shr 8).toByte() }
        return main + chunks(cart.at + BAR, ByteArray(bar.size), bar) + chunks(cart.bar, was, barJump)
    }

    /** The EXP bar's routine, this far past the first. */
    private const val BAR = 0x20

    private fun chunks(at: Int, expect: ByteArray, value: ByteArray) = (value.indices step 4).map { i ->
        val n = minOf(4, value.size - i)
        InstantText.Write((at + i).toLong(), expect.copyOfRange(i, i + n), value.copyOfRange(i, i + n))
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
