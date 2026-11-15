package com.pokedaisy.app.companion.data

import kotlin.random.Random

/**
 * The library's RANDOMIZE tool: a copy of the player's own retail FireRed (rev 1) or Emerald (English) with its
 * starters, wild Pokémon and trainers' teams shuffled by a seed. It writes only bytes Universal Pokémon Randomizer
 * ZX may write ([RandomizedRetailRom.ranges]), so the result is recognised as randomized retail like UPR's
 * ([RandomizedRetail]) and gets every companion feature retail has; [randomize] checks that before it hands the
 * file back. Same ROM, options and seed = the same file.
 */
object RomRandomizer {
    enum class Starters { OFF, RANDOM, THREE_STAGE }
    enum class Wild { OFF, RANDOM, PER_AREA, GLOBAL }

    data class Options(
        val starters: Starters = Starters.THREE_STAGE,
        val wild: Wild = Wild.PER_AREA,
        val trainers: Boolean = true,
        /** Swaps keep to Pokémon of about the same base stat total. */
        val similarStrength: Boolean = true,
        /** No legendary Pokémon in the grass or in trainers' teams (starters never are). */
        val noLegendaries: Boolean = true,
    ) {
        val changesSomething get() = starters != Starters.OFF || wild != Wild.OFF || trainers
    }

    /** Where one retail game keeps what the tool changes (ROM offsets). */
    class Game(
        val code: String,
        val rev: Int,
        val title: String,
        val trainers: Int,
        val trainerCount: Int,
        val learnsets: Int,
        val evolutions: Int,
        /** Each site holding a starter's species (u16), by retail's starter (0, 1, 2 = grass, fire, water). */
        val starterSites: List<Pair<Int, Int>>,
        /** Where the professor's lines naming each starter begin, by starter: rewritten from the name on (FireRed). */
        val starterLines: List<Int> = emptyList(),
    )

    private val GAMES = listOf(
        Game(
            "BPRE", 1, "FireRed", trainers = 0x23EB38, trainerCount = 743, learnsets = 0x25D824, evolutions = 0x2597C4,
            // Oak's lab: each ball's script names its Pokémon twice.
            starterSites = listOf(0 to 0x169C2D, 0 to 0x169C2D + 466, 1 to 0x169C2D + 5, 1 to 0x169C2D + 515,
                2 to 0x169C2D + 520, 2 to 0x169C2D + 461),
            starterLines = listOf(0x18E2F1, 0x18E210, 0x18E283),
        ),
        Game(
            "BPEE", 0, "Emerald", trainers = 0x310030, trainerCount = 855, learnsets = 0x32937C, evolutions = 0x32531C,
            // sStarterMon.
            starterSites = listOf(0 to 0x5B1DF8, 1 to 0x5B1DFA, 2 to 0x5B1DFC),
        ),
    )

    private const val SPECIES = 411
    private const val BASE_STRIDE = 28
    private const val TRAINER_STRIDE = 40
    private const val EVO_STRIDE = 40
    private const val NAME_LEN = 11
    /** Internal ids: 1..251, then Gen 3's from 277 (252..276 are the unused slots between them). */
    private val ALL = (1..251) + (277..SPECIES)
    /** Internal ids of the legendaries and mythicals. */
    private val LEGENDARY = setOf(144, 145, 146, 150, 151, 243, 244, 245, 249, 250, 251) + (401..410)

    /** The game this ROM is and its randomized-retail entry, or null when the tool can't take it. */
    fun gameOf(rom: ByteArray): Pair<Game, RandomizedRetailRom>? {
        if (rom.size < 0x200) return null
        val code = String(rom, 0xAC, 4, Charsets.US_ASCII)
        val rev = rom[0xBC].toInt() and 0xFF
        val game = GAMES.firstOrNull { it.code == code && it.rev == rev } ?: return null
        val entry = RandomizedRetail.matchStream(code, rev, rom.size.toLong()) { okio.Buffer().write(rom) } ?: return null
        return game to entry
    }

    /** A randomized copy of [rom] (retail, or one randomized before), or null when [gameOf] can't take it. */
    fun randomize(rom: ByteArray, options: Options, seed: Long): ByteArray? {
        val (game, entry) = gameOf(rom) ?: return null
        val out = rom.copyOf()
        Run(game, entry, out, options, Random(seed)).all()
        check(onlyMasked(rom, out, entry)) { "randomizer wrote outside the randomizer's sites" }
        check(RandomizedRetail.matchStream(game.code, game.rev, out.size.toLong()) { okio.Buffer().write(out) } === entry)
        return out
    }

    /** Every byte that differs between [a] and [b] is one the randomizer may write. */
    internal fun onlyMasked(a: ByteArray, b: ByteArray, entry: RandomizedRetailRom): Boolean {
        val may = BooleanArray(a.size)
        for (r in entry.ranges + entry.foldRanges) for (i in r) if (i < may.size) may[i] = true
        return a.indices.all { a[it] == b[it] || may[it] }
    }

    private class Run(val g: Game, val entry: RandomizedRetailRom, val b: ByteArray, val o: Options, val rnd: Random) {
        fun u8(at: Int) = b[at].toInt() and 0xFF
        fun u16(at: Int) = u8(at) or (u8(at + 1) shl 8)
        fun ptr(at: Int) = (u16(at) or (u16(at + 2) shl 16)) - 0x08000000
        fun put16(at: Int, v: Int) {
            b[at] = v.toByte()
            b[at + 1] = (v shr 8).toByte()
        }

        val baseStats = (entry.baseStats - 0x08000000).toInt()
        fun bst(s: Int) = (0 until 6).sumOf { u8(baseStats + s * BASE_STRIDE + it) }

        /** Evolution targets of each species. */
        val evolvesTo: Map<Int, List<Int>> = ALL.associateWith { s ->
            (0 until 5).mapNotNull { k ->
                val at = g.evolutions + s * EVO_STRIDE + k * 8
                if (u16(at) == 0) null else u16(at + 4).takeIf { it in 1..SPECIES }
            }
        }
        val evolvedFrom: Set<Int> = evolvesTo.values.flatten().toSet()

        val pool = ALL.filter { !o.noLegendaries || it !in LEGENDARY }

        fun pick(from: List<Int>, like: Int): Int {
            if (!o.similarStrength) return from.random(rnd)
            val target = bst(like)
            var band = 0.1
            while (true) {
                val near = from.filter { kotlin.math.abs(bst(it) - target) <= target * band }
                if (near.size >= 6 || band > 1.0) return (near.ifEmpty { from }).random(rnd)
                band += 0.05
            }
        }

        fun all() {
            starters()
            wild()
            if (o.trainers) trainers()
        }

        fun starters() {
            val pickFrom = when (o.starters) {
                Starters.OFF -> return
                Starters.RANDOM -> ALL.filter { it !in LEGENDARY }
                Starters.THREE_STAGE -> ALL.filter { s ->
                    s !in evolvedFrom && s !in LEGENDARY && evolvesTo[s].orEmpty().any { evolvesTo[it].orEmpty().isNotEmpty() }
                }
            }.toMutableList()
            val three = List(3) { pickFrom.random(rnd).also { pickFrom.remove(it) } }
            for ((which, at) in g.starterSites) put16(at, three[which])
            g.starterLines.forEachIndexed { which, at -> starterLine(at, three[which]) }
        }

        /** The professor's line from the starter's name to its end: "<NAME> is your choice." and the question. */
        fun starterLine(at: Int, species: Int) {
            val range = entry.ranges.first { at in it }
            val end = range.last
            val name = speciesName(species)
            val text = name + enc(" is your choice.") + byteArrayOf(0xFB.toByte()) + enc("So, ") +
                byteArrayOf(0xFD.toByte(), 0x01) + enc(", you'll go with") + byteArrayOf(0xFE.toByte()) + name + enc("?")
            check(at + text.size <= end) { "starter line too long" }
            text.copyInto(b, at)
            for (i in at + text.size..end) b[i] = 0xFF.toByte()
        }

        fun speciesName(s: Int): ByteArray {
            val names = ptr(0x144)
            val at = names + s * NAME_LEN
            var n = 0
            while (n < NAME_LEN - 1 && u8(at + n) != 0xFF) n++
            return b.copyOfRange(at, at + n)
        }

        fun wild() {
            if (o.wild == Wild.OFF) return
            val global = HashMap<Int, Int>()
            if (o.wild == Wild.GLOBAL) {
                val left = pool.toMutableList()
                for (s in ALL) {
                    val to = pick(left.ifEmpty { pool }, s)
                    left.remove(to)
                    global[s] = to
                }
            }
            var h = wildHeaders()
            val seen = HashSet<Int>()
            while (!(u8(h) == 0xFF && u8(h + 1) == 0xFF)) {
                for ((k, slots) in listOf(4 to 12, 8 to 5, 12 to 5, 16 to 10)) {
                    val info = ptr(h + k)
                    if (info !in 0 until b.size || b[info].toInt() == 0) continue
                    val at = ptr(info + 4)
                    if (!seen.add(at)) continue
                    val area = HashMap<Int, Int>()
                    for (i in 0 until slots) {
                        val sp = at + i * 4 + 2
                        val was = u16(sp)
                        if (was !in 1..SPECIES) continue
                        put16(sp, when (o.wild) {
                            Wild.RANDOM -> pick(pool, was)
                            Wild.PER_AREA -> area.getOrPut(was) { pick(pool, was) }
                            else -> global.getValue(was)
                        })
                    }
                }
                h += 20
            }
        }

        /** gWildMonHeaders, found by the code that loads it (as UPR and the mask's generator do). */
        fun wildHeaders(): Int {
            val sig = byteArrayOf(0x03, 0x48, 0x04, 0x80.toByte(), 0x09, 0xE0.toByte(), 0, 0, 0xFF.toByte(), 0xFF.toByte(), 0, 0)
            for (i in 0..b.size - sig.size - 4) {
                var ok = true
                for (j in sig.indices) if (b[i + j] != sig[j]) { ok = false; break }
                if (ok) return ptr(i + 12)
            }
            error("no wild header table")
        }

        fun trainers() {
            for (t in 1 until g.trainerCount) {
                val at = g.trainers + t * TRAINER_STRIDE
                val flags = u8(at)
                val size = u8(at + 32)
                val party = ptr(at + 36)
                val stride = if (flags and 1 != 0) 16 else 8
                for (i in 0 until size) {
                    val m = party + i * stride
                    val was = u16(m + 4)
                    if (was !in 1..SPECIES) continue
                    val now = pick(pool, was)
                    put16(m + 4, now)
                    if (flags and 1 != 0) {
                        val moves = m + if (flags and 2 != 0) 8 else 6
                        val list = movesAt(now, u8(m + 2))
                        for (k in 0 until 4) put16(moves + k * 2, list.getOrElse(k) { 0 })
                    }
                }
            }
        }

        /** The last four moves [species] has learnt by [level], as the game picks a wild one's. */
        fun movesAt(species: Int, level: Int): List<Int> {
            val known = ArrayList<Int>()
            var at = ptr(g.learnsets + species * 4)
            while (u16(at) != 0xFFFF) {
                val v = u16(at)
                if (v shr 9 > level) break
                val move = v and 0x1FF
                if (move !in known) {
                    if (known.size == 4) known.removeAt(0)
                    known += move
                }
                at += 2
            }
            return known
        }
    }

    private fun enc(s: String): ByteArray = ByteArray(s.length) { i ->
        val c = s[i]
        when (c) {
            in 'A'..'Z' -> 0xBB + (c - 'A')
            in 'a'..'z' -> 0xD5 + (c - 'a')
            ' ' -> 0x00
            '.' -> 0xAD
            ',' -> 0xB8
            '?' -> 0xAC
            '\'' -> 0xB4
            else -> error("no glyph for $c")
        }.toByte()
    }
}
