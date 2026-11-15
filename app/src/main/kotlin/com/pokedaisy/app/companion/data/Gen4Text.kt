package com.pokedaisy.app.companion.data

/**
 * A DS game's text, as its own message files hold it (pret/pokeheartgold's tools/msgenc): a count and a key, a table of
 * (offset, length) pairs - each XORed with (765 x line x key), its low half repeated - then the lines, 16-bit codes
 * XORed with a key that starts at line x 596947 and goes up by 18749 a code. Codes become characters by [GEN4_CHAR_CODES]
 * (the game's charmap); 0xFFFF ends a line, 0xFFFE starts a control code (skipped with its arguments). Used for the DEX's
 * pages, read from the player's own cart: none of the games' text is bundled.
 */
object Gen4Text {
    private val chars: Map<Int, String> by lazy {
        val values = GEN4_CHAR_VALUES.split('\u0000')
        HashMap<Int, String>(GEN4_CHAR_CODES.length * 2).also { m ->
            for ((i, c) in GEN4_CHAR_CODES.withIndex()) m[c.code] = values.getOrElse(i) { "" }
            // 0xF1 is the Japanese carts' katakana long vowel (マスターボール), which pret's charmap writes as a full-width
            // hyphen: the Japanese names would read マスタ－ボ－ル.
            m[0xF1] = "ー"
        }
    }

    /** The character [code] stands for ("" when none): for finding a known character in a line's codes. */
    internal fun charOf(code: Int): String = chars[code].orEmpty()

    /** Line [i]'s place in message file [file] (its codes' first byte) and its length in codes. */
    internal fun span(file: ByteArray, i: Int): Pair<Int, Int> {
        val key = u16(file, 2)
        var k = (765 * (i + 1) * key) and 0xFFFF
        k = k or (k shl 16)
        return (u32(file, 4 + i * 8) xor k.toLong()).toInt() to (u32(file, 8 + i * 8) xor k.toLong()).toInt()
    }

    /** Line [i]'s codes, decrypted (control codes and all). */
    internal fun codes(file: ByteArray, i: Int): IntArray {
        val (off, len) = span(file, i)
        var seed = ((i + 1) * 596947) and 0xFFFF
        return IntArray(len) { j -> (u16(file, off + j * 2) xor seed).also { seed = (seed + 18749) and 0xFFFF } }
    }

    /** Line [i] rewritten in place with [codes] (no longer than it, 0xFFFF-padded): the file's table is untouched. */
    internal fun rewrite(file: ByteArray, i: Int, codes: IntArray) {
        val (off, len) = span(file, i)
        require(codes.size <= len) { "line $i too long" }
        var seed = ((i + 1) * 596947) and 0xFFFF
        for (j in 0 until len) {
            val v = (codes.getOrElse(j) { 0xFFFF }) xor seed
            file[off + j * 2] = v.toByte(); file[off + j * 2 + 1] = (v shr 8).toByte()
            seed = (seed + 18749) and 0xFFFF
        }
    }

    /** Every line of the message file [file] (1-based in the game, here from 0). */
    fun lines(file: ByteArray): List<String> {
        if (file.size < 4) return emptyList()
        val count = u16(file, 0)
        val key = u16(file, 2)
        return (0 until count).map { i ->
            val n = i + 1
            var k = (765 * n * key) and 0xFFFF
            k = k or (k shl 16)
            val off = (u32(file, 4 + i * 8) xor k.toLong()).toInt()
            val len = (u32(file, 8 + i * 8) xor k.toLong()).toInt()
            if (off < 0 || len < 0 || off + len * 2 > file.size) return@map ""
            var seed = (n * 596947) and 0xFFFF
            val codes = IntArray(len) { j ->
                val v = u16(file, off + j * 2) xor seed
                seed = (seed + 18749) and 0xFFFF
                v
            }
            decode(codes)
        }
    }

    /** A name in a Pokémon's record ([max] codes from [off], 0xFFFF-ended): the nickname. */
    fun name(b: ByteArray, off: Int, max: Int): String =
        decode(IntArray(max) { if (off + it * 2 + 1 < b.size) u16(b, off + it * 2) else 0xFFFF })

    private fun decode(codes: IntArray): String {
        val sb = StringBuilder()
        var j = 0
        while (j < codes.size) {
            val c = codes[j]
            when {
                c == 0xFFFF -> break
                c == 0xFFFE -> {
                    // {COMMAND args}: the command code, its argument count, the arguments
                    val args = codes.getOrElse(j + 2) { 0 }
                    j += 3 + args
                    continue
                }
                else -> chars[c]?.let(sb::append)
            }
            j++
        }
        return sb.toString()
    }

    private fun u16(b: ByteArray, o: Int) = (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, o: Int) = (u16(b, o).toLong() or (u16(b, o + 2).toLong() shl 16))
}
