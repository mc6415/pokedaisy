package com.pokedaisy.app.companion.data

import okio.ByteString.Companion.toByteString
import kotlin.random.Random

/**
 * RANDOMIZE on the Game Boy games: a copy of the player's own Red, Blue, Yellow (Gen 1), Gold, Silver or Crystal (Gen 2,
 * English) with its starters, wild Pokémon and trainers' teams shuffled by a seed, with [RomRandomizer]'s options.
 *
 * The tables are the decomps' (pret/pokered, pokeyellow, pokegold, pokecrystal, whose builds are these carts byte for byte):
 * the wild Pokémon (Gen 1: WildDataPointers' maps, a grass and a water list of ten (level, species); Gen 2: the grass maps'
 * three times of day x seven slots and the water maps' three), the trainers' parties (Gen 1: a level then species, or
 * (level, species) pairs; Gen 2: a name, a type, then (level, species[, item][, four moves])), and the starters: every byte
 * the decomps' STARTER1-3 (Red / Blue) or Elm's three Poké Ball scripts (pokepic, cry, getmonname, givepoke) become.
 * Yellow's Pikachu and its rival's Eevee are woven through the game: its starters stay.
 *
 * A cart this tool made is told by its bytes with every site (and the global checksum, which is rewritten) zeroed: they
 * hash to [Game.madeSha1]. The companion then treats it as the cart it came from ([recognise]).
 */
object GbRandomizer {
    class Game(
        val title: String,
        val retailSha1: String,
        /** The cart with every site zeroed. */
        val madeSha1: String,
        val gen: Int,
        val wild: List<Int>,
        val trainers: Int,
        val trainerClasses: Int,
        val baseStats: Int,
        val evolutions: Int,
        /** Gen 1: its internal id -> Pokédex number table, and Mew's base stats (Red / Blue keep them apart). */
        val dexOrder: Int = 0,
        val mewStats: Int = 0,
        /** Every place a starter's species is, by starter (0, 1, 2 in the game's own order). */
        val starters: List<Pair<Int, Int>> = emptyList(),
        /** Where the last trainer class's parties end (Gen 1: TrainerAI); 0 = where they stop reading as parties. */
        val trainersEnd: Int = 0,
        /** Moves: move 1's record first, power the third byte (Gen 1's 6-byte records, Gen 2's 7). */
        val moves: Int = 0,
    ) {
        /** The untouched cart's CRC32 (EXPAND's tables go by it). */
        val retailCrc: Int get() = CRCS.getValue(title)
    }

    private val CRCS = mapOf(
        "Red" to 0x9F7FDD53.toInt(), "Blue" to 0xD6DA8A1A.toInt(), "Yellow" to 0x7D527D62, "Gold" to 0x6BDE3C3E,
        "Silver" to 0x8AD48636.toInt(), "Crystal" to 0x3358E30A,
    )

    // pokered: STARTER1 (CHARMANDER), STARTER2 (SQUIRTLE), STARTER3 (BULBASAUR), every byte each became (the decomp rebuilt
    // with one of them changed, diffed against the cart). Blue's are Red's but for its title screen's Pokémon.
    private fun red(blue: Boolean) = listOf(
        0 to 0x1CC84, 0 to 0x1D10E, 0 to 0x1D126, 0 to 0x372B0, 0 to 0x39CF8, 0 to 0x50FB3, 0 to 0x510DD,
        1 to 0x19591, 1 to 0x1CC88, 1 to 0x1CDC8, 1 to 0x1D104, 1 to 0x1D11F, 1 to 0x372B4, 1 to 0x50FAF, 1 to 0x510D9,
        1 to 0x51CAF, 1 to 0x6060E, 1 to 0x61450,
        2 to 0x458A, 2 to 0x19599, 2 to 0x1CDD0, 2 to 0x1D115, 2 to 0x1D130, 2 to 0x372B8, 2 to 0x39CF2, 2 to 0x50FB1,
        2 to 0x510DB, 2 to 0x51CB7, 2 to 0x60616, 2 to 0x61458,
    ) + if (blue) listOf(0 to 0x4589, 1 to 0x4399, 1 to 0x4588, 1 to 0x75F9F, 2 to 0x75FA7)
    else listOf(0 to 0x4399, 0 to 0x4588, 1 to 0x4589, 1 to 0x75F9E, 2 to 0x75FA6)

    /** Elm's lab (pokegold / pokecrystal maps/ElmsLab.asm): each ball script's pokepic, cry, getmonname and givepoke. */
    private fun elm(cyndaquil: Int, totodile: Int, chikorita: Int) = listOf(cyndaquil, totodile, chikorita).flatMapIndexed { which, at ->
        listOf(0, 2, 0x19, 0x24).map { which to at + it }
    }

    val GAMES = listOf(
        Game("Red", "ea9bcae617fdf159b045185467ae58b2e4a48b9a", "e3762b26a29f677b111d5b4a332729a94b26f117", 1, listOf(0xCEEB), 0x39D3B, 47, 0x383DE, 0x3B05C,
            dexOrder = 0x41024, mewStats = 0x425B, starters = red(blue = false), trainersEnd = 0x3A52E, moves = 0x38000),
        Game("Blue", "d7037c83e1ae5b39bde3c30787637ba1d4c48ce2", "2cdaefbc7d31dc1e09af409249ce95961b437492", 1, listOf(0xCEEB), 0x39D3B, 47, 0x383DE, 0x3B05C,
            dexOrder = 0x41024, mewStats = 0x425B, starters = red(blue = true), trainersEnd = 0x3A52E, moves = 0x38000),
        Game("Yellow", "cc7d03262ebfaf2f06772c1a480c7d9d5f4a38e1", "1dd0d9c7bdab87534e4d06a711644bae3b7508e5", 1, listOf(0xCB95), 0x39DD1, 47, 0x383DE, 0x3B1E5,
            dexOrder = 0x410B1, trainersEnd = 0x3A5B2, moves = 0x38000),
        Game("Gold", "d8b8a3600a465308c9953dfa04f0081c05bdcb94", "f2891100d618224fc93c094302dfaf3af246ae09", 2,
            listOf(0x2AB35, 0x2B7C0, 0x2BE1C, -0x2B669, -0x2BD43, -0x2BED9), 0x3993E, 66, 0x51B0B, 0x427BD,
            starters = elm(0x1800D2, 0x180114, 0x180150), moves = 0x41AFE),
        Game("Silver", "49b163f7e57702bc939d642a18f591de55d92dae", "84813da96adc96f1b4cee4398d82c3caff6defae", 2,
            listOf(0x2AB35, 0x2B7C0, 0x2BE1C, -0x2B669, -0x2BD43, -0x2BED9), 0x3993E, 66, 0x51B0B, 0x427BD,
            starters = elm(0x1800D2, 0x180114, 0x180150), moves = 0x41AFE),
        Game("Crystal", "f2f52230b536214ef7c9924f483392993e226cfb", "f5be8c4b9dc0c51a06eb95bea18e4e02ebd35df6", 2,
            listOf(0x2A5E9, 0x2B274, 0x2B8D0, -0x2B11D, -0x2B7F7, -0x2B92F), 0x39999, 67, 0x51424, 0x425B1,
            starters = elm(0x78C7F, 0x78CC1, 0x78CFD), moves = 0x41AFB),
    )

    /** Pokédex numbers of the legendaries and mythicals up to Gen 2. */
    private val LEGENDARY = setOf(144, 145, 146, 150, 151, 243, 244, 245, 249, 250, 251)

    /** The game this cart is when the tool can take it: the untouched cart, or one it made. */
    fun gameOf(rom: ByteArray): Game? = GAMES.firstOrNull { g -> sha1(rom) == g.retailSha1 } ?: recognise(rom)

    /** [rom] is a cart this tool made (not the untouched one): its game. */
    fun recognise(rom: ByteArray): Game? {
        val sha = sha1(rom)
        return GAMES.firstOrNull { g -> g.madeSha1.isNotEmpty() && sha != g.retailSha1 && madeHash(rom, g) == g.madeSha1 }
    }

    /** A randomized copy of [rom], or null when [gameOf] can't take it. */
    fun randomize(rom: ByteArray, options: RomRandomizer.Options, seed: Long): ByteArray? {
        val g = gameOf(rom) ?: return null
        val out = rom.copyOf()
        Run(g, out, options, Random(seed)).all()
        // The global checksum (the sum of every other byte), as the cart had it right.
        var sum = 0
        for (i in out.indices) if (i != 0x14E && i != 0x14F) sum += out[i].toInt() and 0xFF
        out[0x14E] = (sum ushr 8).toByte(); out[0x14F] = sum.toByte()
        check(madeHash(out, g) == g.madeSha1) { "randomizer wrote outside its sites" }
        return out
    }

    fun sha1(b: ByteArray) = b.toByteString().sha1().hex()

    internal fun madeHash(rom: ByteArray, g: Game): String? = runCatching {
        val copy = rom.copyOf()
        for (at in sites(rom, g)) copy[at] = 0
        copy[0x14E] = 0; copy[0x14F] = 0
        sha1(copy)
    }.getOrNull()

    /** Every byte the tool may write (found through tables it never writes). */
    internal fun sites(rom: ByteArray, g: Game): List<Int> {
        val r = Reader(rom, g)
        val out = ArrayList<Int>()
        for ((_, at) in g.starters) out += at
        r.wildSlots { out += it }
        r.trainerMons { species, moves -> out += species; if (moves >= 0) for (k in 0 until 4) out += moves + k }
        r.trainerItems { out += it }
        r.statSites { at, n -> for (k in 0 until n) out += at + k }
        r.learnMoves { out += it }
        require(out.all { it in rom.indices }) { "not this game's tables" }
        return out
    }

    private open class Reader(val b: ByteArray, val g: Game) {
        fun u8(at: Int) = b[at].toInt() and 0xFF
        fun u16(at: Int) = u8(at) or (u8(at + 1) shl 8)
        /** A 2-byte pointer at [at], in [at]'s own bank. */
        fun ptr(at: Int): Int {
            val bank = at / 0x4000
            val p = u16(at)
            return if (bank == 0) p else bank * 0x4000 + (p - 0x4000)
        }

        /** Gen 1's internal ids that are Pokémon (MissingNo's slots aren't), and each one's Pokédex number. */
        val dex: IntArray by lazy {
            if (g.gen == 2) IntArray(252) { it } else IntArray(191) { id -> if (id == 0) 0 else u8(g.dexOrder + id - 1) }
        }

        fun species(id: Int) = id in 1 until dex.size && dex[id] != 0

        /** Each wild slot's species byte. */
        fun wildSlots(each: (Int) -> Unit) {
            if (g.gen == 1) {
                val seen = HashSet<Int>()
                var p = g.wild[0]
                while (u16(p) != 0xFFFF) {
                    val at = ptr(p)
                    p += 2
                    if (!seen.add(at)) continue
                    var q = at
                    repeat(2) {
                        val rate = u8(q++)
                        if (rate != 0) { for (i in 0 until 10) each(q + i * 2 + 1); q += 20 }
                    }
                }
            } else {
                for (t in g.wild) {
                    val grass = t > 0
                    var a = kotlin.math.abs(t)
                    while (u8(a) != 0xFF) {
                        if (grass) { for (i in 0 until 21) each(a + 5 + i * 2 + 1); a += 5 + 42 }
                        else { for (i in 0 until 3) each(a + 3 + i * 2 + 1); a += 3 + 6 }
                    }
                }
            }
        }

        /** Each species' base stats: where they start and how many (Gen 1: HP, ATTACK, DEFENSE, SPEED, SPECIAL; Gen 2: six). */
        fun statSites(each: (at: Int, count: Int) -> Unit) {
            if (g.gen == 2) { for (id in 1..251) each(g.baseStats + (id - 1) * 0x20 + 1, 6); return }
            for (n in 1..151) {
                val at = if (n == 151 && g.mewStats != 0) g.mewStats else g.baseStats + (n - 1) * 28
                each(at + 1, 5)
            }
        }

        /** Every move byte [id] learns, in order: Gen 1's level-1 moves first (its base stats' four, the ones it has), then
         * each level-up entry's (after the evolutions: level, move pairs to a 0). */
        fun movePlaces(id: Int): List<Int> {
            val out = ArrayList<Int>()
            if (g.gen == 1) {
                val n = dex[id]
                val at = if (n == 151 && g.mewStats != 0) g.mewStats else g.baseStats + (n - 1) * 28
                for (k in 15..18) if (u8(at + k) != 0) out += at + k
            }
            var a = evolutions(id).second
            while (u8(a) != 0) { out += a + 1; a += 2 }
            return out
        }

        /** Every move byte any species learns, once. */
        fun learnMoves(each: (at: Int) -> Unit) {
            val seen = HashSet<Int>()
            for (id in 1 until dex.size) if (species(id)) for (at in movePlaces(id)) if (seen.add(at)) each(at)
        }

        /** Gen 2: each trainer Pokémon's held item byte, where its party's type has one. */
        fun trainerItems(each: (at: Int) -> Unit) {
            if (g.gen != 2) return
            val starts = (0 until g.trainerClasses).map { ptr(g.trainers + it * 2) }
            val order = starts.distinct().sorted()
            for ((i, start) in order.withIndex()) {
                val end = order.getOrNull(i + 1) ?: Int.MAX_VALUE
                var a = start
                while (a < end && a < b.size) {
                    val next = gen2Party(a, items = each) ?: break
                    a = next.second
                }
            }
        }

        /** Each trainer Pokémon's species byte, and (Gen 2, a party with its own moves) where its four moves are, else -1.
         * A class's parties run to the next class's; one that doesn't read as a party ends its class. */
        fun trainerMons(each: (species: Int, moves: Int) -> Unit) {
            val starts = (0 until g.trainerClasses).map { ptr(g.trainers + it * 2) }
            val order = starts.distinct().sorted()
            for ((i, start) in order.withIndex()) {
                val end = order.getOrNull(i + 1) ?: if (g.trainersEnd > 0) g.trainersEnd else Int.MAX_VALUE
                var a = start
                while (a < end && a < b.size) {
                    val party = if (g.gen == 1) gen1Party(a) else gen2Party(a)
                    if (party == null) break
                    party.first.forEach { (sp, mv) -> each(sp, mv) }
                    a = party.second
                }
            }
        }

        /** A Gen 1 party at [a]: its Pokémon's species places, and where the next party starts; null if it isn't one. */
        private fun gen1Party(a: Int): Pair<List<Pair<Int, Int>>, Int>? {
            val out = ArrayList<Pair<Int, Int>>()
            var q = a
            if (u8(q) == 0xFF) {
                q++
                while (u8(q) != 0) {
                    if (u8(q) !in 1..100 || !species(u8(q + 1))) return null
                    out += (q + 1) to -1; q += 2
                }
            } else {
                if (u8(q) !in 1..100) return null
                q++
                while (u8(q) != 0) {
                    if (!species(u8(q))) return null
                    out += q to -1; q++
                }
            }
            return if (out.isEmpty() || out.size > 6) null else out to q + 1
        }

        /** A Gen 2 party at [a]: a name (to 0x50), a type, then its Pokémon to 0xFF; [items] hears each held item's place. */
        private fun gen2Party(a: Int, items: ((Int) -> Unit)? = null): Pair<List<Pair<Int, Int>>, Int>? {
            var q = a
            while (q - a <= 12 && u8(q) != 0x50) q++
            if (u8(q) != 0x50) return null
            q++
            val type = u8(q++)
            if (type > 3) return null
            val out = ArrayList<Pair<Int, Int>>()
            while (u8(q) != 0xFF) {
                if (u8(q) !in 1..100 || !species(u8(q + 1))) return null
                val moves = if (type and 1 != 0) q + 2 + (if (type and 2 != 0) 1 else 0) else -1
                out += (q + 1) to moves
                if (type and 2 != 0) items?.invoke(q + 2)
                q += 2 + (if (type and 2 != 0) 1 else 0) + (if (type and 1 != 0) 4 else 0)
                if (out.size > 6) return null
            }
            return if (out.isEmpty()) null else out to q + 1
        }

        /** [id]'s base stat total (Gen 1: five stats, by Pokédex number; Gen 2: six). */
        fun bst(id: Int): Int {
            if (g.gen == 2) return (1..6).sumOf { u8(g.baseStats + (id - 1) * 0x20 + it) }
            val n = dex[id]
            val at = if (n == 151 && g.mewStats != 0) g.mewStats else g.baseStats + (n - 1) * 28
            return (1..5).sumOf { u8(at + it) }
        }

        /** [id]'s evolution records' targets and where its level-up moves start (EvosAttacks / EvosMoves). */
        fun evolutions(id: Int): Pair<List<Int>, Int> {
            var a = ptr(g.evolutions + (id - 1) * 2)
            val out = ArrayList<Int>()
            while (u8(a) != 0) {
                val method = u8(a)
                val len = when {
                    g.gen == 1 -> if (method == 2) 4 else 3
                    method == 5 -> 4
                    else -> 3
                }
                out += u8(a + len - 1)
                a += len
            }
            return out to a + 1
        }

        /** The last four moves [id] has learnt by [level]. */
        fun movesAt(id: Int, level: Int): List<Int> {
            var a = evolutions(id).second
            val known = ArrayList<Int>()
            while (u8(a) != 0) {
                if (u8(a) > level) break
                val move = u8(a + 1)
                if (move !in known) {
                    if (known.size == 4) known.removeAt(0)
                    known += move
                }
                a += 2
            }
            return known
        }
    }

    private class Run(g: Game, b: ByteArray, val o: RomRandomizer.Options, val rnd: Random) : Reader(b, g) {
        val all = (1 until dex.size).filter { species(it) }
        val evolvesTo = all.associateWith { evolutions(it).first.filter { t -> species(t) } }
        val evolvedFrom = evolvesTo.values.flatten().toSet()
        val pool = all.filter { !o.noLegendaries || dex[it] !in LEGENDARY }

        fun pick(from: List<Int>, like: Int): Int {
            if (!o.similarStrength || !species(like)) return from.random(rnd)
            val target = bst(like)
            var band = 0.1
            while (true) {
                val near = from.filter { kotlin.math.abs(bst(it) - target) <= target * band }
                if (near.size >= 6 || band > 1.0) return near.ifEmpty { from }.random(rnd)
                band += 0.05
            }
        }

        fun all() {
            // Stats and learnsets first: the strength match keeps working (the totals stay) and trainers' moves follow.
            if (o.baseStats) baseStats()
            if (o.learnsets) learnsets()
            starters()
            wild()
            if (o.trainers) trainers()
            if (o.trainerItems) trainerItems()
        }

        /** Each species' base stats in another order. */
        fun baseStats() {
            statSites { at, n ->
                val v = (0 until n).map { b[at + it] }.shuffled(rnd)
                v.forEachIndexed { k, x -> b[at + k] = x }
            }
        }

        fun power(move: Int) = u8(g.moves + (move - 1) * (if (g.gen == 1) 6 else 7) + 2)

        /** Random moves at each species' own levels: none twice in a list, the first one with power. */
        fun learnsets() {
            val moves = (1..(if (g.gen == 1) 164 else 251)).filter { it != 165 }
            val damaging = moves.filter { power(it) > 0 }
            val seen = HashSet<Int>()
            for (id in all) {
                val places = movePlaces(id).filter { seen.add(it) }
                val used = HashSet<Int>()
                places.forEachIndexed { k, at ->
                    var move: Int
                    do { move = (if (k == 0) damaging else moves).random(rnd) } while (!used.add(move))
                    b[at] = move.toByte()
                }
            }
        }

        /** Gen 2: every held item a trainer has, one of the items trainers hold anywhere in the game. */
        fun trainerItems() {
            val held = ArrayList<Int>().also { l -> trainerItems { if (u8(it) != 0) l += u8(it) } }.distinct()
            if (held.isEmpty()) return
            trainerItems { b[it] = held.random(rnd).toByte() }
        }

        fun starters() {
            if (g.starters.isEmpty()) return
            val from = when (o.starters) {
                RomRandomizer.Starters.OFF -> return
                RomRandomizer.Starters.RANDOM -> all.filter { dex[it] !in LEGENDARY }
                RomRandomizer.Starters.THREE_STAGE -> all.filter { s ->
                    s !in evolvedFrom && dex[s] !in LEGENDARY && evolvesTo[s].orEmpty().any { evolvesTo[it].orEmpty().isNotEmpty() }
                }
            }.toMutableList()
            val three = List(3) { from.random(rnd).also { from.remove(it) } }
            for ((which, at) in g.starters) b[at] = three[which].toByte()
        }

        fun wild() {
            if (o.wild == RomRandomizer.Wild.OFF) return
            val global = HashMap<Int, Int>()
            if (o.wild == RomRandomizer.Wild.GLOBAL) {
                val left = pool.toMutableList()
                for (s in all) global[s] = pick(left.ifEmpty { pool }, s).also { left.remove(it) }
            }
            // PER_AREA: a map's lists share one swap (a slot's place says which map: they come map by map).
            var area = HashMap<Int, Int>()
            var last = -1
            wildSlots { at ->
                if (last < 0 || at - last > 2) area = HashMap()
                last = at
                val was = u8(at)
                if (!species(was)) return@wildSlots
                b[at] = when (o.wild) {
                    RomRandomizer.Wild.RANDOM -> pick(pool, was)
                    RomRandomizer.Wild.PER_AREA -> area.getOrPut(was) { pick(pool, was) }
                    else -> global.getValue(was)
                }.toByte()
            }
        }

        fun trainers() {
            trainerMons { at, moves ->
                val now = pick(pool, u8(at))
                b[at] = now.toByte()
                if (moves >= 0) {
                    val list = movesAt(now, u8(at - 1))
                    for (k in 0 until 4) b[moves + k] = list.getOrElse(k) { 0 }.toByte()
                }
            }
        }
    }
}
