package com.pokedaisy.app.companion.data

import okio.FileSystem
import okio.Path

/**
 * FireRed and LeafGreen stop every evolution into a Pokémon past the first 151 until the National Dex (pret's
 * evolution_scene.c: the scene starts, then cancels itself with "......?"; pokemon.c's GetEvolutionTargetSpecies keeps a
 * trade's held item and evolves nothing). A copy RANDOMIZE made ([RomRandomizer.recognise]) hands out Pokémon from
 * every generation from the start, so there it's lifted while the game runs: the two scene checks' `ble` become `b`
 * (always past the cancel) and the trade check's `bhi` a `nop`. Never written into the file: its bytes must stay
 * what the randomizer made, for the app to know it. Each site is found by its code, all three or none.
 */
object EvolutionUnlock {
    private const val ROM_BASE = 0x08000000L

    /** `ldrsh r0, [r5, r2]; cmp r0, #151; ble` (Task_EvolutionScene), `ldrsh r0, [r4, r3]; ...; ble` (the trade's), and
     * `mov r1, r10; cmp r1, #151; bhi` (GetEvolutionTargetSpecies' trade item): each with what its branch becomes. */
    private val SITES = listOf(
        "a85e972814dd" to "14e0",
        "e05e97280fdd" to "0fe0",
        "5146972907d8" to "c046",
    )

    /** The writes for [path] when it's a FireRed / LeafGreen RANDOMIZE made, else null. */
    fun find(path: Path): HmFree.Patch? = runCatching {
        val rom = FileSystem.SYSTEM.read(path) { readByteArray() }
        find(rom)
    }.getOrNull()

    fun find(rom: ByteArray): HmFree.Patch? {
        val g = RomRandomizer.recognise(rom) ?: return null
        if (g.code != "BPRE" && g.code != "BPGE") return null
        val writes = ArrayList<InstantText.Write>()
        for ((code, branch) in SITES) {
            val pat = hex(code)
            val at = only(rom, pat) ?: return null
            val b = at + pat.size - 2
            writes += InstantText.Write(ROM_BASE + b, rom.copyOfRange(b, b + 2), hex(branch))
        }
        return HmFree.Patch(writes)
    }

    /** Where [pat] is in [rom]'s first 2 MB (the code), when it's there exactly once. */
    private fun only(rom: ByteArray, pat: ByteArray): Int? {
        var found: Int? = null
        val end = minOf(rom.size, 0x200000) - pat.size
        var i = 0
        while (i <= end) {
            if (rom[i] == pat[0] && (pat.indices).all { rom[i + it] == pat[it] }) {
                if (found != null) return null
                found = i
            }
            i += 2
        }
        return found
    }

    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
}
