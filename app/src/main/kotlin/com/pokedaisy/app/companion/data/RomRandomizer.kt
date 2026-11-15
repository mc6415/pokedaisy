package com.pokedaisy.app.companion.data

import okio.ByteString.Companion.toByteString
import kotlin.random.Random

/**
 * The library's RANDOMIZE tool: a copy of the player's own retail English FireRed (rev 1), LeafGreen (rev 1), Ruby / Sapphire
 * (rev 2) or Emerald with its starters, wild Pokémon and trainers' teams shuffled by a seed. Same ROM, options and seed = the
 * same file.
 *
 * It writes only [sites]: the starters' species words (and FireRed / LeafGreen's Oak lines naming them), every wild slot's
 * species, every trainer Pokémon's species and its own moves. Those are found through pointers it never changes, so the
 * same list comes out of the retail cart and of any file made from it, and a file is this tool's output when its bytes with
 * [sites] zeroed hash to the game's [Game.madeSha1] ([recognise]): the companion then treats it as the retail cart (the
 * Poller's `retailRom`, for the battle cursors, SHINY ODDS, BOXES). FireRed and Emerald's sites are also all inside
 * Universal Pokémon Randomizer's ([RandomizedRetailRom.ranges]), so they read as randomized retail like UPR's.
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
        /** The untouched cart's SHA-1. */
        val retailSha1: String,
        /** The cart with [sites] zeroed: what every file made from it hashes to the same way. */
        val madeSha1: String,
        val trainers: Int,
        val trainerCount: Int,
        val learnsets: Int,
        val evolutions: Int,
        val baseStats: Int,
        val wildHeaders: Int,
        /** Each site holding a starter's species (u16), by retail's starter (0, 1, 2 = grass, fire, water). */
        val starterSites: List<Pair<Int, Int>>,
        /** Where Oak's lines naming each starter begin, by starter (FireRed / LeafGreen): rewritten from the name on. */
        val starterLines: List<Int> = emptyList(),
        /** gSpeciesNames (Oak's lines take the names from it). */
        val speciesNames: Int = 0,
    )

    /** Oak's lab: each ball's script names its Pokémon twice, at these offsets from the first. */
    private fun oaksLab(at: Int) = listOf(0 to at, 0 to at + 466, 1 to at + 5, 1 to at + 515, 2 to at + 520, 2 to at + 461)
    /** sStarterMon: three u16s. */
    private fun starterMon(at: Int) = listOf(0 to at, 1 to at + 2, 2 to at + 4)

    val GAMES = listOf(
        Game(
            "BPRE", 1, "FireRed", "dd5945db9b930750cb39d00c84da8571feebf417", "d6e82f31d54fb00d8679da8fbc1091c9e1d57d83",
            trainers = 0x23EB38, trainerCount = 743, learnsets = 0x25D824, evolutions = 0x2597C4, baseStats = 0x2547F4,
            wildHeaders = 0x3C9D28, starterSites = oaksLab(0x169C2D), starterLines = listOf(0x18E2F1, 0x18E210, 0x18E283),
            speciesNames = 0x245F50,
        ),
        Game(
            "BPGE", 1, "LeafGreen", "7862c67bdecbe21d1d69ce082ce34327e1c6ed5e", "177fe07b98b53ac0bb340d4270f20cbbde5043f0",
            trainers = 0x23EB14, trainerCount = 743, learnsets = 0x25D804, evolutions = 0x2597A4, baseStats = 0x2547D0,
            wildHeaders = 0x3C9B64, starterSites = oaksLab(0x169C09), starterLines = listOf(0x18E2CD, 0x18E1EC, 0x18E25F),
            speciesNames = 0x245F2C,
        ),
        Game(
            "AXVE", 2, "Ruby", "5b64eacf892920518db4ec664e62a086dd5f5bc8", "aab48d17cd004decde01a8470dd75e42daf614be",
            trainers = 0x1F0514, trainerCount = 694, learnsets = 0x207BE0, evolutions = 0x203B80, baseStats = 0x1FEC30,
            wildHeaders = 0x39D46C, starterSites = starterMon(0x3F76E0),
        ),
        Game(
            "AXPE", 2, "Sapphire", "89b45fb172e6b55d51fc0e61989775187f6fe63c", "c1cecb442be82d9ffcbdd236993f4c336bb87a00",
            trainers = 0x1F04A4, trainerCount = 694, learnsets = 0x207B70, evolutions = 0x203B10, baseStats = 0x1FEBC0,
            wildHeaders = 0x39D2B4, starterSites = starterMon(0x3F773C),
        ),
        Game(
            "BPEE", 0, "Emerald", "f3ae088181bf583e55daf962a92bb46f4f1d07b7", "07da11e6dfb4a3f0a2b2ba1b4dd3f313a31d793d",
            trainers = 0x310030, trainerCount = 855, learnsets = 0x32937C, evolutions = 0x32531C, baseStats = 0x3203CC,
            wildHeaders = 0x552D48, starterSites = starterMon(0x5B1DF8),
        ),
    )

    private const val SPECIES = 411
    private const val BASE_STRIDE = 28
    private const val TRAINER_STRIDE = 40
    private const val EVO_STRIDE = 40
    private const val NAME_LEN = 11
    private const val SIZE = 0x1000000
    /** Internal ids: 1..251, then Gen 3's from 277 (252..276 are the unused slots between them). */
    private val ALL = (1..251) + (277..SPECIES)
    /** Internal ids of the legendaries and mythicals. */
    private val LEGENDARY = setOf(144, 145, 146, 150, 151, 243, 244, 245, 249, 250, 251) + (401..410)

    private fun header(rom: ByteArray): Game? {
        if (rom.size != SIZE) return null
        val code = String(rom, 0xAC, 4, Charsets.US_ASCII)
        val rev = rom[0xBC].toInt() and 0xFF
        return GAMES.firstOrNull { it.code == code && it.rev == rev }
    }

    /** The game this ROM is, when the tool can take it: the retail cart, or a file it made from one. */
    fun gameOf(rom: ByteArray): Game? {
        val game = header(rom) ?: return null
        return game.takeIf { sha1(rom) == it.retailSha1 || madeHash(rom, it) == it.madeSha1 }
    }

    /** [rom] is one this tool made (not the untouched cart): its game, else null. For the Poller and the library. */
    fun recognise(rom: ByteArray): Game? {
        val game = header(rom) ?: return null
        return game.takeIf { it.madeSha1.isNotEmpty() && madeHash(rom, it) == it.madeSha1 && sha1(rom) != it.retailSha1 }
    }

    /** A randomized copy of [rom] (retail, or one randomized before), or null when [gameOf] can't take it. */
    fun randomize(rom: ByteArray, options: Options, seed: Long): ByteArray? {
        val game = gameOf(rom) ?: return null
        val out = rom.copyOf()
        Run(game, out, options, Random(seed)).all()
        check(madeHash(out, game) == game.madeSha1) { "randomizer wrote outside its sites" }
        // FireRed and Emerald: still randomized retail to the app's UPR check.
        RandomizedRetail.matchStream(game.code, game.rev, out.size.toLong()) { okio.Buffer().write(out) }
            ?: check(RANDOMIZED_RETAIL.none { it.code == game.code && it.rev == game.rev }) { "not randomized retail" }
        return out
    }

    fun sha1(b: ByteArray) = b.toByteString().sha1().hex()

    /** [rom] with [sites] zeroed, hashed. */
    internal fun madeHash(rom: ByteArray, game: Game): String? {
        val sites = runCatching { sites(rom, game) }.getOrNull() ?: return null
        val copy = rom.copyOf()
        for (r in sites) for (i in r) copy[i] = 0
        // The cart's clock registers: a state load writes them into the running ROM (the Poller's hashes skip them too).
        for (i in 0xC4..0xC9) copy[i] = 0
        return sha1(copy)
    }

    /** Every byte the tool may write in [rom] (found through pointers it never writes, so the same in its output). */
    internal fun sites(rom: ByteArray, g: Game): List<IntRange> {
        val r = Reader(rom)
        val out = ArrayList<IntRange>()
        for ((_, at) in g.starterSites) out += at..at + 1
        // Oak's lines: from the name to the end of the 0xFF run that ends the text (the tool pads its shorter line with 0xFF).
        for (at in g.starterLines) {
            var end = at
            while (r.u8(end) != 0xFF) end++
            while (r.u8(end + 1) == 0xFF) end++
            out += at..end
        }
        r.wildSlots(g.wildHeaders) { slot, _ -> out += slot + 2..slot + 3 }
        r.trainerMons(g) { mon, moves ->
            out += mon + 4..mon + 5
            if (moves >= 0) out += moves until moves + 8
        }
        return out
    }

    private open class Reader(val b: ByteArray) {
        fun u8(at: Int) = b[at].toInt() and 0xFF
        fun u16(at: Int) = u8(at) or (u8(at + 1) shl 8)
        fun ptr(at: Int) = (u16(at) or (u16(at + 2) shl 16)) - 0x08000000

        /** Each wild slot (minLevel, maxLevel, species) of every encounter table, once. */
        fun wildSlots(headers: Int, each: (slot: Int, table: Int) -> Unit) {
            var h = headers
            val seen = HashSet<Int>()
            while (!(u8(h) == 0xFF && u8(h + 1) == 0xFF)) {
                for ((k, slots) in listOf(4 to 12, 8 to 5, 12 to 5, 16 to 10)) {
                    val info = ptr(h + k)
                    if (info !in 0 until b.size - 8 || b[info].toInt() == 0) continue
                    val at = ptr(info + 4)
                    if (at !in 0 until b.size || !seen.add(at)) continue
                    for (i in 0 until slots) each(at + i * 4, at)
                }
                h += 20
            }
        }

        /** Each trainer Pokémon: its record and, when the trainer picks its moves, where they are (else -1). */
        fun trainerMons(g: Game, each: (mon: Int, moves: Int) -> Unit) {
            for (t in 1 until g.trainerCount) {
                val at = g.trainers + t * TRAINER_STRIDE
                val flags = u8(at)
                val party = ptr(at + 36)
                val stride = if (flags and 1 != 0) 16 else 8
                for (i in 0 until u8(at + 32)) {
                    val m = party + i * stride
                    each(m, if (flags and 1 != 0) m + if (flags and 2 != 0) 8 else 6 else -1)
                }
            }
        }
    }

    private class Run(val g: Game, b: ByteArray, val o: Options, val rnd: Random) : Reader(b) {
        fun put16(at: Int, v: Int) {
            b[at] = v.toByte()
            b[at + 1] = (v shr 8).toByte()
        }

        fun bst(s: Int) = (0 until 6).sumOf { u8(g.baseStats + s * BASE_STRIDE + it) }

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

        /** Oak's line from the starter's name to its end: "<NAME> is your choice." and the question. */
        fun starterLine(at: Int, species: Int) {
            var end = at
            while (u8(end) != 0xFF) end++
            while (u8(end + 1) == 0xFF) end++
            val name = speciesName(species)
            val text = name + enc(" is your choice.") + byteArrayOf(0xFB.toByte()) + enc("So, ") +
                byteArrayOf(0xFD.toByte(), 0x01) + enc(", you'll go with") + byteArrayOf(0xFE.toByte()) + name + enc("?")
            check(at + text.size <= end) { "starter line too long" }
            text.copyInto(b, at)
            for (i in at + text.size..end) b[i] = 0xFF.toByte()
        }

        fun speciesName(s: Int): ByteArray {
            val at = g.speciesNames + s * NAME_LEN
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
            // PER_AREA: one map per encounter table.
            var area = HashMap<Int, Int>()
            var table = -1
            wildSlots(g.wildHeaders) { slot, at ->
                if (at != table) { area = HashMap(); table = at }
                val sp = slot + 2
                val was = u16(sp)
                if (was !in 1..SPECIES) return@wildSlots
                put16(sp, when (o.wild) {
                    Wild.RANDOM -> pick(pool, was)
                    Wild.PER_AREA -> area.getOrPut(was) { pick(pool, was) }
                    else -> global.getValue(was)
                })
            }
        }

        fun trainers() {
            trainerMons(g) { m, moves ->
                val was = u16(m + 4)
                if (was !in 1..SPECIES) return@trainerMons
                val now = pick(pool, was)
                put16(m + 4, now)
                if (moves >= 0) {
                    val list = movesAt(now, u8(m + 2))
                    for (k in 0 until 4) put16(moves + k * 2, list.getOrElse(k) { 0 })
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
