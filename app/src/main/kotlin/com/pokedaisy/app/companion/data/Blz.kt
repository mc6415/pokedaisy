package com.pokedaisy.app.companion.data

/**
 * Nintendo's backward LZ, the DS's in-place compression for the ARM9 binary and its overlays (HeartGold / SoulSilver keep
 * their starters in both): the data is decoded from its end towards its start, into the same buffer, a flag byte before
 * each eight tokens (bit set = a 2-byte match: 4 bits length - 3, 12 bits distance - 3, copied from later data; clear = a
 * literal byte). A footer gives the compressed stream's length with its header (u24) + the header's length (u8), then
 * how much bigger the data gets. Whatever lies before the stream stays as it is.
 *
 * [compressTo] makes a blob of an exact size (the original's), so a cart's header, file table and secure area never
 * change: an optimal parse, then as much of the front left uncompressed as needed (which also keeps the in-place decode
 * from writing over input it hasn't read yet), then padding.
 */
object Blz {
    fun decompress(blob: ByteArray): ByteArray {
        val n = blob.size
        val head = u32(blob, n - 8)
        val extra = u32(blob, n - 4)
        val encLen = head and 0xFFFFFF
        val headLen = head ushr 24
        val out = ByteArray(n + extra)
        blob.copyInto(out, 0, 0, n)
        var src = n - headLen
        var dst = out.size
        val end = n - encLen
        while (src > end) {
            var flags = blob[--src].toInt() and 0xFF
            for (b in 0 until 8) {
                if (src <= end) break
                if (flags and 0x80 != 0) {
                    src -= 2
                    val v = (blob[src].toInt() and 0xFF) or ((blob[src + 1].toInt() and 0xFF) shl 8)
                    val len = ((v ushr 12) and 0xF) + 3
                    val disp = (v and 0xFFF) + 3
                    repeat(len) { dst--; out[dst] = out[dst + disp] }
                } else {
                    dst--
                    out[dst] = blob[--src]
                }
                flags = flags shl 1
            }
        }
        return out
    }

    /** [raw] compressed into exactly [size] bytes, or null when it can't be (it wouldn't fit). */
    fun compressTo(raw: ByteArray, size: Int): ByteArray? {
        val n = raw.size
        val r = ByteArray(n) { raw[n - 1 - it] }   // the decoder's order: from the end
        val tokens = parse(r)
        // g = bytes consumed - bytes produced after each token (its flag byte counted as its group starts). Cutting after
        // token t leaves raw[0 until n - produced] as it is: the blob is n + g + 8 + pad bytes, and the in-place decode is
        // safe when g there is the lowest so far (the output never catches up with unread input).
        var consumed = 0
        var produced = 0
        var low = Int.MAX_VALUE
        val target = size - n - 8
        for ((t, tok) in tokens.withIndex()) {
            if (t % 8 == 0) consumed++
            if (tok > 0) { consumed += 2; produced += tok ushr 16 } else { consumed++; produced++ }
            val g = consumed - produced
            if (g <= low) {
                low = g
                val pad = target - g
                if (pad in 0..247) return build(raw, r, tokens, t + 1, produced, pad)
            }
        }
        return null
    }

    /**
     * Tokens of an optimal parse of [r]: a literal (0) or a match (length << 16 | distance). Costs in bits: a literal 9, a
     * match 17 (each with its flag bit).
     */
    private fun parse(r: ByteArray): IntArray {
        val n = r.size
        val head = IntArray(1 shl 16) { -1 }
        val prev = IntArray(n) { -1 }
        val bestLen = IntArray(n)
        val bestDist = IntArray(n)
        for (i in 0 until n - 2) {
            val h = ((r[i].toInt() and 0xFF) shl 8 xor (r[i + 1].toInt() and 0xFF) shl 4 xor (r[i + 2].toInt() and 0xFF)) and 0xFFFF
            var j = head[h]
            prev[i] = j
            head[h] = i
            var steps = 0
            while (j >= 0 && i - j <= 0x1002 && steps < 256) {
                val d = i - j
                if (d >= 3) {
                    var l = 0
                    while (l < 18 && i + l < n && r[j + l] == r[i + l]) l++
                    if (l > bestLen[i]) { bestLen[i] = l; bestDist[i] = d }
                    if (l == 18) break
                }
                j = prev[j]
                steps++
            }
        }
        val cost = IntArray(n + 1)
        val pick = IntArray(n + 1)
        for (i in n - 1 downTo 0) {
            var c = cost[i + 1] + 9
            var p = 0
            if (bestLen[i] >= 3) for (l in 3..bestLen[i]) {
                val cc = cost[i + l] + 17
                if (cc < c) { c = cc; p = (l shl 16) or bestDist[i] }
            }
            cost[i] = c
            pick[i] = p
        }
        val out = ArrayList<Int>()
        var i = 0
        while (i < n) {
            out += pick[i]
            i += if (pick[i] > 0) pick[i] ushr 16 else 1
        }
        return out.toIntArray()
    }

    /** The blob: raw's first n - [produced] bytes, the first [count] tokens' stream, [pad] bytes, the footer. */
    private fun build(raw: ByteArray, r: ByteArray, tokens: IntArray, count: Int, produced: Int, pad: Int): ByteArray {
        val stream = java.io.ByteArrayOutputStream()
        var i = 0
        var t = 0
        while (t < count) {
            val flagAt = stream.size()
            stream.write(0)
            var flags = 0
            val bytes = java.io.ByteArrayOutputStream()
            for (b in 0 until 8) {
                if (t >= count) break
                val tok = tokens[t++]
                if (tok > 0) {
                    val l = tok ushr 16
                    val v = ((l - 3) shl 12) or ((tok and 0xFFFF) - 3)
                    bytes.write(v ushr 8); bytes.write(v and 0xFF)
                    flags = flags or (0x80 ushr b)
                    i += l
                } else {
                    bytes.write(r[i].toInt() and 0xFF)
                    i++
                }
            }
            val s = stream.toByteArray().also { it[flagAt] = flags.toByte() }
            stream.reset(); stream.write(s); stream.write(bytes.toByteArray())
        }
        val fwd = stream.toByteArray()
        val keep = raw.size - produced
        val out = ByteArray(keep + fwd.size + pad + 8)
        raw.copyInto(out, 0, 0, keep)
        for (k in fwd.indices) out[keep + k] = fwd[fwd.size - 1 - k]
        for (k in 0 until pad) out[keep + fwd.size + k] = 0xFF.toByte()
        val headLen = 8 + pad
        val encLen = fwd.size + pad + 8
        put32(out, out.size - 8, encLen or (headLen shl 24))
        put32(out, out.size - 4, raw.size - out.size)
        return out
    }

    private fun u32(b: ByteArray, o: Int) =
        (b[o].toInt() and 0xFF) or ((b[o + 1].toInt() and 0xFF) shl 8) or ((b[o + 2].toInt() and 0xFF) shl 16) or ((b[o + 3].toInt() and 0xFF) shl 24)

    private fun put32(b: ByteArray, o: Int, v: Int) {
        b[o] = v.toByte(); b[o + 1] = (v ushr 8).toByte(); b[o + 2] = (v ushr 16).toByte(); b[o + 3] = (v ushr 24).toByte()
    }
}
