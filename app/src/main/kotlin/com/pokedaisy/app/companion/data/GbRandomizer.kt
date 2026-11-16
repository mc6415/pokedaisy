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
        /** Where the maps' item balls and hidden items are found (null: none read). */
        val field: Field? = null,
    ) {
        /** The untouched cart's CRC32 (EXPAND's tables go by it). */
        val retailCrc: Int get() = CRCS.getValue(title)
    }

    /**
     * The tables an item ball or hidden item is found through, as file offsets.
     * Gen 1 (pokered / pokeyellow): MapHeaderPointers, MapHeaderBanks, HiddenEventMaps, HiddenEventPointers (0: Yellow's
     * 3-byte map + pointer entries), HiddenItems (the hidden events' function for an item) and KeyItemFlags.
     * Gen 2 (pokegold / pokecrystal): MapGroupPointers and ItemAttributes ([keyItems]); the rest unused.
     */
    class Field(
        val maps: Int,
        val keyItems: Int,
        val mapBanks: Int = 0,
        val hiddenMaps: Int = 0,
        val hiddenPointers: Int = 0,
        val hiddenItems: Int = 0,
        /** Gen 1: ToggleableObjectStates (map, object, state: an item ball's flag is its place there) and HiddenItemCoords
         * (map, y, x: a hidden item's). */
        val toggles: Int = 0,
        val hiddenCoords: Int = 0,
    )

    private fun redField(hiddenItems: Int) = Field(0x01AE, 0xE799, 0xC23D, 0x46A40, 0x46A96, hiddenItems, 0xCAEA, hiddenItems + 0x30)

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
        Game("Red", "ea9bcae617fdf159b045185467ae58b2e4a48b9a", "9be7ad21c3c62f5f5194a96751d29f1ebebdf3e5", 1, listOf(0xCEEB), 0x39D3B, 47, 0x383DE, 0x3B05C,
            dexOrder = 0x41024, mewStats = 0x425B, starters = red(blue = false), trainersEnd = 0x3A52E, moves = 0x38000,
            field = redField(0x76688)),
        Game("Blue", "d7037c83e1ae5b39bde3c30787637ba1d4c48ce2", "02860b473d2fe6822706d641100d27db0e4b5e2c", 1, listOf(0xCEEB), 0x39D3B, 47, 0x383DE, 0x3B05C,
            dexOrder = 0x41024, mewStats = 0x425B, starters = red(blue = true), trainersEnd = 0x3A52E, moves = 0x38000,
            field = redField(0x76689)),
        Game("Yellow", "cc7d03262ebfaf2f06772c1a480c7d9d5f4a38e1", "2363a42659fb3688dea07683ccb1f11754244f97", 1, listOf(0xCB95), 0x39DD1, 47, 0x383DE, 0x3B1E5,
            dexOrder = 0x410B1, trainersEnd = 0x3A5B2, moves = 0x38000,
            field = Field(0xFC1F2, 0xE6DD, 0xFC3E4, 0xF268D, 0, 0x75F74, 0xC892, 0x75FAA)),
        Game("Gold", "d8b8a3600a465308c9953dfa04f0081c05bdcb94", "b04a379e25dea7e1b66bd2f414dba018f2cc186f", 2,
            listOf(0x2AB35, 0x2B7C0, 0x2BE1C, -0x2B669, -0x2BD43, -0x2BED9), 0x3993E, 66, 0x51B0B, 0x427BD,
            starters = elm(0x1800D2, 0x180114, 0x180150), moves = 0x41AFE, field = Field(0x940ED, 0x68A0)),
        Game("Silver", "49b163f7e57702bc939d642a18f591de55d92dae", "d42e66790e3007a1393629cac6b6098013511d72", 2,
            listOf(0x2AB35, 0x2B7C0, 0x2BE1C, -0x2B669, -0x2BD43, -0x2BED9), 0x3993E, 66, 0x51B0B, 0x427BD,
            starters = elm(0x1800D2, 0x180114, 0x180150), moves = 0x41AFE, field = Field(0x940ED, 0x68A0)),
        Game("Crystal", "f2f52230b536214ef7c9924f483392993e226cfb", "f771b93a2ac72eb398ce30ed2bb109d53d11d91e", 2,
            listOf(0x2A5E9, 0x2B274, 0x2B8D0, -0x2B11D, -0x2B7F7, -0x2B92F), 0x39999, 67, 0x51424, 0x425B1,
            starters = elm(0x78C7F, 0x78CC1, 0x78CFD), moves = 0x41AFB, field = Field(0x94000, 0x67C1)),
    )

    // Trainer classes (pret's pokered / pokecrystal constants; Gold's are Crystal's). Gen 1: each leader a class of their
    // own (Giovanni's, BRUNO .. SABRINA), the Elite Four, the rival's three (the last the Champion). Gen 2: the Johto and
    // Kanto leaders, the Elite Four, LANCE (CHAMPION), RED; the rival's two.
    private val GEN1_BOSSES = setOf(0x1D) + (0x21..0x28) + setOf(0x2B, 0x2C, 0x2E, 0x2F)
    private val GEN1_RIVALS = setOf(0x19, 0x2A, 0x2B)
    private val GEN2_BOSSES = (1..8).toSet() + setOf(11, 13, 14, 15, 16, 17, 18, 19, 21, 26, 35, 46, 63, 64)
    private val GEN2_RIVALS = setOf(9, 42)

    /** More map headers than any Game Boy game has (Crystal: ~400): past it the table isn't one. */
    private const val MAX_MAP_HEADERS = 2048

    /** Pokédex numbers of the legendaries and mythicals up to Gen 2. */
    private val LEGENDARY = setOf(144, 145, 146, 150, 151, 243, 244, 245, 249, 250, 251)

    /** The game this cart is when the tool can take it: the untouched cart, or one it made. */
    fun gameOf(rom: ByteArray): Game? = GAMES.firstOrNull { g -> sha1(rom) == g.retailSha1 } ?: recognise(rom)

    /** [rom] is a cart this tool made (not the untouched one): its game. Only the game its header names is tried: another
     * game's tables read off this cart are garbage, and Gold's map walk on plain Red never ended (the app hung opening it). */
    fun recognise(rom: ByteArray): Game? {
        val sha = sha1(rom)
        return GAMES.firstOrNull { g ->
            headerIs(rom, g) && g.madeSha1.isNotEmpty() && sha != g.retailSha1 && madeHash(rom, g) == g.madeSha1
        }
    }

    /** Each cart's header title (0x134-0x142; the tool never writes the header but its checksum). */
    private val HEADER_TITLES = mapOf(
        "Red" to "POKEMON RED", "Blue" to "POKEMON BLUE", "Yellow" to "POKEMON YELLOW",
        "Gold" to "POKEMON_GLDAAUE", "Silver" to "POKEMON_SLVAAXE", "Crystal" to "PM_CRYSTAL\u0000BYTE",
    )

    /** [rom]'s header title is [g]'s. */
    internal fun headerIs(rom: ByteArray, g: Game): Boolean {
        val want = HEADER_TITLES[g.title] ?: return false
        if (rom.size < 0x150) return false
        return (0 until 15).all { i -> (rom[0x134 + i].toInt() and 0xFF) == (want.getOrNull(i)?.code ?: 0) }
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
    /** [rom] as the run modes' tests look at it ([CartView]): species as the cart numbers them. */
    internal fun view(rom: ByteArray, g: Game): CartView {
        val r = Reader(rom, g)
        val wild = ArrayList<MutableList<Int>>()
        var last = -1
        r.wildSlots { at ->
            if (last < 0 || at - last > 2) wild += ArrayList<Int>()
            last = at
            if (r.species(r.u8(at))) wild.last() += r.u8(at)
        }
        val levels = ArrayList<Int>().also { l -> r.trainerLevels { l += it } }
        val trainers = ArrayList<Pair<Int, Int>>()
        val bosses = ArrayList<String?>()
        val rivals = ArrayList<Boolean>()
        r.trainerMonsOf { at, _, cls, _ ->
            // Its level: the byte before it, or a Gen 1 party's one level for all (the last level place before it).
            val lv = levels.filter { it < at }.maxOrNull() ?: return@trainerMonsOf
            trainers += r.u8(at) to r.u8(lv)
            bosses += cls.toString().takeIf { cls in r.bossClasses }
            rivals += cls in r.rivalClasses
        }
        val evolutions = ArrayList<Triple<Int, Int, Int>>().also { l -> r.evolutionTargets { id, at, method -> l += Triple(id, method, r.u8(at)) } }
        val wildLevels = ArrayList<Int>().also { l -> r.wildSlots { l += r.u8(it - 1) } }
        val water = ArrayList<Int>().also { l -> r.wildLists { at, isWater, _ -> if (isWater && r.species(r.u8(at))) l += r.u8(at) } }
        val starters = (0 until 3).mapNotNull { which -> g.starters.firstOrNull { it.first == which }?.second?.let(r::u8) }
        return CartView(wild, trainers, { s -> if (r.species(s)) r.types(s) else emptySet() }, evolutions,
            wildLevels = wildLevels, waterWild = water, bosses = bosses, rivals = rivals, starters = starters)
    }

    internal fun sites(rom: ByteArray, g: Game): List<Int> {
        val r = Reader(rom, g)
        val out = ArrayList<Int>()
        for ((_, at) in g.starters) out += at
        // Each wild slot's level (WILD LEVELS: the byte before its species) and species.
        r.wildSlots { out += it; out += it - 1 }
        r.trainerMons { species, moves -> out += species; if (moves >= 0) for (k in 0 until 4) out += moves + k }
        r.trainerLevels { out += it }
        for (one in statics(g)) for (at in one) out += at
        r.trainerItems { out += it }
        r.statSites { at, n -> for (k in 0 until n) out += at + k }
        r.learnMoves { out += it }
        r.evolutionTargets { _, at, _ -> out += at }
        r.fieldItems { out += it }
        require(out.all { it in rom.indices }) { "not this game's tables" }
        return out
    }

    /**
     * What a RANDOMIZE cart's GUIDE reads from it: each map's grass and surfing species by slot (Gen 1 by wCurMap, Gen 2 by
     * group << 8 | number; National Dex numbers) and any trainer's team, as the game builds it.
     */
    class Shuffle(val game: Game, rom: ByteArray) {
        private val r = Reader(rom, game)
        val grass = HashMap<Int, IntArray>()
        val water = HashMap<Int, IntArray>()

        init {
            if (game.gen == 1) {
                var m = 0
                var p = game.wild[0]
                while (u16(rom, p) != 0xFFFF && m < 256) {
                    var q = r.ptr(p)
                    for (which in 0 until 2) {
                        val rate = r.u8(q++)
                        if (rate != 0) {
                            (if (which == 0) grass else water)[m] = IntArray(10) { r.dex.getOrElse(r.u8(q + it * 2 + 1)) { 0 } }
                            q += 20
                        }
                    }
                    p += 2; m++
                }
            } else for (t in game.wild) {
                var a = kotlin.math.abs(t)
                while (r.u8(a) != 0xFF) {
                    val key = r.u8(a) shl 8 or r.u8(a + 1)
                    // The first table naming a map is its own: Crystal's swarm tables come after (the guide leaves swarms out).
                    if (t > 0) { grass.putIfAbsent(key, IntArray(21) { r.u8(a + 5 + it * 2 + 1) }); a += 5 + 42 }
                    else { water.putIfAbsent(key, IntArray(3) { r.u8(a + 3 + it * 2 + 1) }); a += 3 + 6 }
                }
            }
        }

        /** HERE's item balls and hidden items as this cart has them: by their flags, trades and gifts as the game's. */
        val guide: RandomizedGuide by lazy {
            // Gifts by Pokédex number (the area data's), when only one gift has that species.
            val was = RANDOMIZER_STATICS[game.title].orEmpty().map { it[0] }
            val places = statics(game)
            val gifts = was.indices.filter { i -> was.count { it == was[i] } == 1 && r.species(was[i]) && r.species(r.u8(places[i][0])) }
                .associate { i -> r.dex[was[i]] to r.dex[r.u8(places[i][0])] }
            RandomizedGuide(r.itemsByFlag(), trades = null, statics = gifts, keepUnknown = true)
        }

        /** Trainer class [cls]'s [no]th party (both from 1), or null when it doesn't read as one. */
        fun party(cls: Int, no: Int): List<TrainerMon>? = runCatching { r.party(cls, no) }.getOrNull()
    }

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    /** The cart [Shuffle] the GUIDE reads: set as a Game Boy game loads (Gen1Art.prefetch, which runs for every one), null for any other. */
    @Volatile var shuffle: Shuffle? = null

    /** [rom]'s [Shuffle] when it's a cart this tool made, else null. */
    fun readShuffle(rom: ByteArray): Shuffle? = recognise(rom)?.let { Shuffle(it, rom) }

    /** [g]'s gift and static Pokémon: each one's species places ([RANDOMIZER_STATICS], its retail species dropped). */
    internal fun statics(g: Game): List<IntArray> = RANDOMIZER_STATICS[g.title].orEmpty().map { it.copyOfRange(1, it.size) }

    /** The item balls' and hidden items' item bytes, but the kept ones. */
    internal fun fieldSites(rom: ByteArray, g: Game): List<Int> = ArrayList<Int>().also { l -> Reader(rom, g).fieldItems { l += it } }

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
        fun wildSlots(each: (Int) -> Unit) = wildLists { at, _, _ -> each(at) }

        /** [wildSlots] with each list's water (else grass) and whether its map is a cave ([isCave]). */
        fun wildLists(each: (at: Int, water: Boolean, cave: Boolean) -> Unit) {
            if (g.gen == 1) {
                val seen = HashSet<Int>()
                var p = g.wild[0]
                while (u16(p) != 0xFFFF) {
                    val at = ptr(p)
                    val map = (p - g.wild[0]) / 2
                    p += 2
                    if (!seen.add(at)) continue
                    var q = at
                    // Grass, then water.
                    for (water in listOf(false, true)) {
                        val rate = u8(q++)
                        if (rate != 0) { for (i in 0 until 10) each(q + i * 2 + 1, water, !water && isCave(map)); q += 20 }
                    }
                }
            } else {
                for (t in g.wild) {
                    val grass = t > 0
                    var a = kotlin.math.abs(t)
                    while (u8(a) != 0xFF) {
                        val cave = grass && isCave(u8(a) shl 8 or u8(a + 1))
                        if (grass) { for (i in 0 until 21) each(a + 5 + i * 2 + 1, false, cave); a += 5 + 42 }
                        else { for (i in 0 until 3) each(a + 3 + i * 2 + 1, true, false); a += 3 + 6 }
                    }
                }
            }
        }

        /** HABITATS: a map is a cave - Gen 1's (by map id) with the CAVERN tileset, Gen 2's (group << 8 | number) a CAVE or
         * DUNGEON environment; false when its header can't be read. */
        fun isCave(map: Int): Boolean = runCatching {
            val f = g.field ?: return false
            if (g.gen == 1) {
                val bank = u8(f.mapBanks + map)
                val p = u16(f.maps + map * 2)
                bank != 0 && p in 0x4000 until 0x8000 && u8(far(bank, p)) == 0x11
            } else {
                val group = map shr 8
                val num = map and 0xFF
                if (group == 0 || num == 0) return false
                val h = far(f.maps / 0x4000, u16(f.maps + (group - 1) * 2)) + (num - 1) * 9
                u8(h + 2) == 4 || u8(h + 2) == 7
            }
        }.getOrDefault(false)

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

        /** A pointer in [bank] as a file offset. */
        fun far(bank: Int, p: Int) = if (p < 0x4000) p else bank * 0x4000 + (p - 0x4000)

        /** An item the field keeps where it is: a key item, an HM, or no item at all. */
        fun keeps(item: Int): Boolean {
            val f = g.field ?: return true
            // KeyItemFlags covers the items (to 0x53); the floors, HMs and TMs after them aren't in it.
            return if (g.gen == 1) item == 0 || item in 0x54..0xC8 ||
                item < 0x54 && (u8(f.keyItems + (item - 1) / 8) shr ((item - 1) % 8)) and 1 != 0
            else item == 0 || item >= 0xF3 || u8(f.keyItems + (item - 1) * 7 + 5) == 2
        }

        /** Each item ball's and hidden item's item byte, once, but the ones [keeps] keeps. */
        fun fieldItems(each: (at: Int) -> Unit) {
            val f = g.field ?: return
            val seen = HashSet<Int>()
            fun put(at: Int, @Suppress("UNUSED_PARAMETER") flag: Int) { if (at in b.indices && !keeps(u8(at)) && seen.add(at)) each(at) }
            if (g.gen == 1) gen1Field(f, ::put) else gen2Field(f, ::put)
        }

        /** Every item ball's and hidden item's item, by the flag the GUIDE's area data gives it (Gen 1: 0x10000 + its
         * toggleable object, 0x20000 + its HiddenItemCoords entry; Gen 2: its event flag). */
        fun itemsByFlag(): Map<Int, Int> {
            val f = g.field ?: return emptyMap()
            val out = HashMap<Int, Int>()
            fun put(at: Int, flag: Int) { if (at in b.indices && flag >= 0) out.putIfAbsent(flag, u8(at)) }
            if (g.gen == 1) gen1Field(f, ::put) else gen2Field(f, ::put)
            return out
        }

        /** Gen 1: object [obj] (from 1) of map [map]'s toggleable object number, or -1. */
        private fun toggle(f: Field, map: Int, obj: Int): Int {
            var i = 0
            while (u8(f.toggles + i * 3) != 0xFF && i < 512) {
                if (u8(f.toggles + i * 3) == map && u8(f.toggles + i * 3 + 1) == obj) return i
                i++
            }
            return -1
        }

        /** Gen 1: the hidden item at [y], [x] on [map]'s HiddenItemCoords number, or -1. */
        private fun hiddenCoord(f: Field, map: Int, y: Int, x: Int): Int {
            var i = 0
            while (u8(f.hiddenCoords + i * 3) != 0xFF && i < 256) {
                val a = f.hiddenCoords + i * 3
                if (u8(a) == map && u8(a + 1) == y && u8(a + 2) == x) return i
                i++
            }
            return -1
        }

        /** Gen 1: object events with the ITEM bit (an item byte follows the text id), and hidden events calling HiddenItems. */
        private fun gen1Field(f: Field, put: (at: Int, flag: Int) -> Unit) {
            val objects = HashSet<Int>()
            for (m in 0 until 0xF8) {
                val bank = u8(f.mapBanks + m)
                val p = u16(f.maps + m * 2)
                // Unused map slots point anywhere: a header has a real tileset and size.
                if (bank == 0 || p !in 0x4000 until 0x8000) continue
                val h = far(bank, p)
                if (u8(h) >= 0x18 || u8(h + 1) == 0 || u8(h + 2) == 0) continue
                val at = h + 10 + 11 * Integer.bitCount(u8(h + 9) and 0xF)
                if (u16(at) !in 0x4000 until 0x8000) continue
                val o = far(bank, u16(at))
                if (!objects.add(o)) continue
                var a = o + 1
                a += 1 + u8(a) * 4
                a += 1 + u8(a) * 3
                val n = u8(a++)
                for (k in 1..n) {
                    val text = u8(a + 5)
                    a += 6
                    when {
                        text and 0x40 != 0 -> a += 2
                        text and 0x80 != 0 -> put(a++, toggle(f, m, k).let { if (it < 0) -1 else G1_MISSABLE_FLAG + it })
                    }
                }
            }
            val bank = f.hiddenItems / 0x4000
            val fn = f.hiddenItems % 0x4000 + 0x4000
            // Each map's hidden events (the first map naming a list).
            val lists = LinkedHashMap<Int, Int>()
            var i = 0
            val step = if (f.hiddenPointers == 0) 3 else 1
            while (u8(f.hiddenMaps + i * step) != 0xFF && i < 256) {
                val start = if (f.hiddenPointers == 0) far(f.hiddenMaps / 0x4000, u16(f.hiddenMaps + i * 3 + 1))
                    else far(f.hiddenPointers / 0x4000, u16(f.hiddenPointers + i * 2))
                lists.putIfAbsent(start, u8(f.hiddenMaps + i * step))
                i++
            }
            for ((start, map) in lists) {
                var a = start
                while (u8(a) != 0xFF) {
                    if (u8(a + 3) == bank && u16(a + 4) == fn)
                        put(a + 2, hiddenCoord(f, map, u8(a), u8(a + 1)).let { if (it < 0) -1 else G1_HIDDEN_ITEM_FLAG + it })
                    a += 6
                }
            }
        }

        /** Gen 2: every map's ITEMBALL objects (their script is item, amount) and BGEVENT_ITEM signs (flag, item). */
        private fun gen2Field(f: Field, put: (at: Int, flag: Int) -> Unit) {
            val groups = ArrayList<Int>()
            val first = far(f.maps / 0x4000, u16(f.maps))
            var g0 = f.maps
            while (g0 < first) { groups += far(f.maps / 0x4000, u16(g0)); g0 += 2 }
            val headers = ArrayList<Int>()
            for ((k, start) in groups.withIndex()) {
                var a = start
                // The last group runs up to the first map's attributes, which follow it.
                // Bounded: a map table read wrong (not this game's) must end, not walk forever.
                while (headers.size < MAX_MAP_HEADERS && a + 9 <= b.size &&
                    if (k + 1 < groups.size) a < groups[k + 1] else headers.none { far(f.maps / 0x4000, u16(it + 3)) <= a }) {
                    headers += a
                    a += 9
                }
            }
            for (h in headers) {
                val attr = far(f.maps / 0x4000, u16(h + 3))
                val bank = u8(attr + 6)
                var a = far(bank, u16(attr + 9)) + 2
                a += 1 + u8(a) * 5
                a += 1 + u8(a) * 8
                repeat(u8(a++)) {
                    // hiddenitem: dw flag, db item.
                    if (u8(a + 2) == 7) far(bank, u16(a + 3)).let { sc -> put(sc + 2, u16(sc)) }
                    a += 5
                }
                repeat(u8(a++)) {
                    // The object's event flag (its last word) marks the ball picked up.
                    if (u8(a + 7) and 0xF == 1) put(far(bank, u16(a + 9)), u16(a + 11))
                    a += 13
                }
            }
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
        fun trainerMons(each: (species: Int, moves: Int) -> Unit) = trainerMonsOf { sp, mv, _, _ -> each(sp, mv) }

        /** [trainerMons] with each one's trainer class (from 1) and its party's place (one party = one trainer). */
        fun trainerMonsOf(each: (species: Int, moves: Int, cls: Int, party: Int) -> Unit) {
            val starts = (0 until g.trainerClasses).map { ptr(g.trainers + it * 2) }
            val order = starts.distinct().sorted()
            for ((i, start) in order.withIndex()) {
                val cls = starts.indexOf(start) + 1
                val end = order.getOrNull(i + 1) ?: if (g.trainersEnd > 0) g.trainersEnd else Int.MAX_VALUE
                var a = start
                while (a < end && a < b.size) {
                    val party = if (g.gen == 1) gen1Party(a) else gen2Party(a)
                    if (party == null) break
                    party.first.forEach { (sp, mv) -> each(sp, mv, cls, a) }
                    a = party.second
                }
            }
        }

        val bossClasses get() = if (g.gen == 1) GEN1_BOSSES else GEN2_BOSSES
        val rivalClasses get() = if (g.gen == 1) GEN1_RIVALS else GEN2_RIVALS

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
        /** Each evolution's target byte (its entry's last; the method and its level / item are never written), with the
         * species it's on, once. */
        fun evolutionTargets(each: (species: Int, at: Int, method: Int) -> Unit) {
            val seen = HashSet<Int>()
            for (id in (1 until dex.size).filter { species(it) }) {
                var a = ptr(g.evolutions + (id - 1) * 2)
                var n = 0
                while (a in b.indices && u8(a) != 0 && n++ < 8) {
                    val method = u8(a)
                    val len = when {
                        g.gen == 1 -> if (method == 2) 4 else 3
                        method == 5 -> 4
                        else -> 3
                    }
                    if (seen.add(a + len - 1)) each(id, a + len - 1, method)
                    a += len
                }
            }
        }

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
        /** Class [cls]'s [no]th party as the game reads it: Gen 1 / 2's level-up moves where the party gives none. */
        fun party(cls: Int, no: Int): List<TrainerMon>? {
            var a = ptr(g.trainers + (cls - 1) * 2)
            if (g.gen == 1) {
                repeat(no - 1) { a = gen1Party(a)?.second ?: return null }
                val (mons, _) = gen1Party(a) ?: return null
                val flat = u8(a) != 0xFF
                return mons.map { (at, _) ->
                    val level = if (flat) u8(a) else u8(at - 1)
                    TrainerMon(dex[u8(at)], level, 0, gen1Moves(u8(at), level))
                }
            }
            // ReadTrainerParty: scan to each trainer's 0xFF.
            repeat(no - 1) { while (u8(a) != 0xFF) a++; a++ }
            val (mons, _) = gen2Party(a) ?: return null
            val type = gen2Type(a)
            return mons.map { (at, moves) ->
                val level = u8(at - 1)
                val item = if (type and 2 != 0) u8(at + 1) else 0
                TrainerMon(u8(at), level, item, if (moves >= 0) (0 until 4).map { u8(moves + it) }.filter { it != 0 } else movesAt(u8(at), level))
            }
        }

        /** A Gen 2 party's type byte (after its name). */
        private fun gen2Type(a: Int): Int { var q = a; while (u8(q) != 0x50) q++; return u8(q + 1) }

        /** [id]'s two type bytes (Gen 1: base stats +6 / +7 by Pokédex number; Gen 2: +7 / +8). */
        fun types(id: Int): Set<Int> {
            if (g.gen == 2) return setOf(u8(g.baseStats + (id - 1) * 0x20 + 7), u8(g.baseStats + (id - 1) * 0x20 + 8))
            val n = dex[id]
            val at = if (n == 151 && g.mewStats != 0) g.mewStats else g.baseStats + (n - 1) * 28
            return setOf(u8(at + 6), u8(at + 7))
        }

        /** Each trainer Pokémon's level byte, once (a Gen 1 party with one level for all: its first byte). */
        fun trainerLevels(each: (at: Int) -> Unit) {
            val seen = HashSet<Int>()
            val starts = (0 until g.trainerClasses).map { ptr(g.trainers + it * 2) }
            val order = starts.distinct().sorted()
            for ((i, start) in order.withIndex()) {
                val end = order.getOrNull(i + 1) ?: if (g.trainersEnd > 0) g.trainersEnd else Int.MAX_VALUE
                var a = start
                while (a < end && a < b.size) {
                    val party = (if (g.gen == 1) gen1Party(a) else gen2Party(a)) ?: break
                    for ((sp, _) in party.first) {
                        val lv = if (g.gen == 1 && u8(a) != 0xFF) a else sp - 1
                        if (seen.add(lv)) each(lv)
                    }
                    a = party.second
                }
            }
        }

        /** Gen 1: the four level-1 moves in the base stats, then the level-up learnset to [level] (AddPartyMon / WriteMonMoves). */
        fun gen1Moves(id: Int, level: Int): List<Int> {
            val n = dex[id]
            val at = if (n == 151 && g.mewStats != 0) g.mewStats else g.baseStats + (n - 1) * 28
            val known = (15..18).map { u8(at + it) }.filter { it != 0 }.toMutableList()
            var a = evolutions(id).second
            while (u8(a) != 0 && u8(a) <= level) {
                val move = u8(a + 1)
                if (move !in known) {
                    if (known.size == 4) known.removeAt(0)
                    known += move
                }
                a += 2
            }
            return known
        }

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
        /** Read again after EVOLUTIONS, so the starters' "evolve twice" follows the new ones. */
        var evolvesTo = all.associateWith { evolutions(it).first.filter { t -> species(t) } }
        val evolvedFrom get() = evolvesTo.values.flatten().toSet()
        val picker = SpeciesPicker(
            all.filter { !o.noLegendaries || dex[it] !in LEGENDARY }, o, rnd,
            bst = { if (species(it)) bst(it) else 0 }, types = { if (species(it)) types(it) else emptySet() }, monotype = o.monotype?.id(g.gen),
        )
        val pool get() = picker.pool

        /** RIVAL: the starters' lines as the cart had them (before EVOLUTIONS and STARTERS), by starter (Yellow: none). */
        val oldStarters = (0 until 3).mapNotNull { which -> g.starters.firstOrNull { it.first == which }?.second?.let(::u8) }
        val oldLines = oldStarters.map { s -> SpeciesPicker.chain(s) { evolvesTo[it].orEmpty() } }
        var newStarters = oldStarters

        fun all() {
            // Stats and learnsets first: the strength match keeps working (the totals stay) and trainers' moves follow.
            if (o.baseStats) baseStats()
            if (o.learnsets) learnsets()
            // Before the starters: "evolve twice" goes by the new evolutions.
            if (o.evolutions) evolutions()
            starters()
            wild()
            if (o.wildLevelScale > 0) wildLevels()
            if (o.gifts) gifts()
            if (o.levelScale > 0) levels()
            // RIVAL's slots from the teams as the cart has them (TRAINERS may put a starter line's Pokémon anywhere).
            planRivals()
            if (o.trainers) trainers()
            rivals()
            if (o.trainerItems) trainerItems()
            if (o.fieldItems) fieldItems()
        }

        /** WILD LEVELS: every wild slot's level (the byte before its species) raised. */
        fun wildLevels() {
            wildSlots { at -> b[at - 1] = SpeciesPicker.scaled(u8(at - 1), o.wildLevelScale).toByte() }
        }

        /** RIVAL: [SpeciesPicker.rivalSwap] for a rival's Pokémon, or null. */
        fun rivalNew(cls: Int, species: Int): Int? =
            if (!o.rivalStarter || cls !in rivalClasses || oldLines.isEmpty()) null
            else SpeciesPicker.rivalSwap(species, oldLines, newStarters.map { s -> SpeciesPicker.chain(s) { evolvesTo[it].orEmpty() } })

        /** RIVAL: each of the rival's starter-line Pokémon's species byte, its new species and its moves' place. */
        var rivalPlan: Map<Int, Pair<Int, Int>> = emptyMap()

        fun planRivals() {
            val plan = HashMap<Int, Pair<Int, Int>>()
            trainerMonsOf { at, moves, cls, _ -> rivalNew(cls, u8(at))?.let { plan[at] = it to moves } }
            rivalPlan = plan
        }

        /** RIVAL: the planned Pokémon made the new starters' (Gen 2's own moves as the game picks them). */
        fun rivals() {
            for ((at, p) in rivalPlan) {
                val (now, moves) = p
                b[at] = now.toByte()
                if (moves >= 0) {
                    val list = movesAt(now, u8(at - 1))
                    for (k in 0 until 4) b[moves + k] = list.getOrElse(k) { 0 }.toByte()
                }
            }
        }

        /** EVOLUTIONS: every evolution's target ([SpeciesPicker.evolutions]); how it evolves stays. */
        fun evolutions() {
            val sites = ArrayList<Pair<Int, Int>>().also { l -> evolutionTargets { id, at, _ -> if (species(u8(at))) l += id to at } }
            val now = picker.evolutions(sites.map { (id, at) -> id to u8(at) })
            sites.forEachIndexed { i, (_, at) -> b[at] = now[i].toByte() }
            evolvesTo = all.associateWith { evolutions(it).first.filter { t -> species(t) } }
        }

        /** The item balls' and hidden items' items, shuffled among the same places. */
        fun fieldItems() {
            val places = ArrayList<Int>().also { l -> fieldItems { l += it } }
            val items = places.map { b[it] }.shuffled(rnd)
            places.forEachIndexed { i, at -> b[at] = items[i] }
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
                RomRandomizer.Starters.RANDOM -> picker.themed(all.filter { dex[it] !in LEGENDARY })
                RomRandomizer.Starters.THREE_STAGE -> picker.themed(all.filter { s ->
                    s !in evolvedFrom && dex[s] !in LEGENDARY && evolvesTo[s].orEmpty().any { evolvesTo[it].orEmpty().isNotEmpty() }
                })
            }.toMutableList()
            val three = List(3) { from.random(rnd).also { from.remove(it) } }
            newStarters = three
            for ((which, at) in g.starters) b[at] = three[which].toByte()
        }

        fun wild() {
            if (o.wild == RomRandomizer.Wild.OFF) return
            val global = HashMap<Int, Int>()
            if (o.wild == RomRandomizer.Wild.GLOBAL) {
                val left = pool.toMutableList()
                for (s in all) global[s] = picker.pick(s, from = left.ifEmpty { pool }, wild = true).also { left.remove(it) }
            }
            // PER_AREA: a map's lists share one swap (a slot's place says which map: they come map by map); NO DUPLICATES
            // avoids what the map already has.
            var area = HashMap<Pair<Int, SpeciesPicker.Habitat>, Int>()
            var used = HashSet<Int>()
            var last = -1
            wildLists { at, water, cave ->
                if (last < 0 || at - last > 2) { area = HashMap(); used = HashSet() }
                last = at
                val was = u8(at)
                if (!species(was)) return@wildLists
                val avoid = if (o.noDuplicates) used else emptySet<Int>()
                // HABITATS: the water lists, a cave's grass.
                val habitat = when {
                    !o.habitats -> SpeciesPicker.Habitat.LAND
                    water -> SpeciesPicker.Habitat.WATER
                    cave -> SpeciesPicker.Habitat.CAVE
                    else -> SpeciesPicker.Habitat.LAND
                }
                val only = habitat.types(g.gen)
                val now = when (o.wild) {
                    RomRandomizer.Wild.RANDOM -> picker.pick(was, avoid, wild = true, only = only)
                    RomRandomizer.Wild.PER_AREA -> area.getOrPut(was to habitat) { picker.pick(was, avoid, wild = true, only = only) }
                    else -> global.getValue(was)
                }
                used += now
                b[at] = now.toByte()
            }
        }

        /** GIFTS: each gift / static Pokémon swapped (every place its species is written), none twice. */
        fun gifts() {
            val used = HashSet<Int>()
            for (one in statics(g)) {
                val was = u8(one[0])
                if (!species(was)) continue
                val now = picker.pick(was, used)
                used += now
                for (at in one) b[at] = now.toByte()
            }
        }

        /** LEVELS: every trainer level byte raised (a Gen 1 party with one level for all has one). */
        fun levels() {
            trainerLevels { at -> b[at] = SpeciesPicker.scaled(u8(at), o.levelScale).toByte() }
        }

        fun trainers() {
            // GYM TYPES: each boss's type (Gen 1 / 2 give every boss a class of their own), from the teams as they are now.
            val teams = LinkedHashMap<Int, MutableList<Int>>()
            trainerMonsOf { at, _, cls, _ -> if (cls in bossClasses) teams.getOrPut(cls) { ArrayList() } += u8(at) }
            val themes = picker.themes(teams, o.bossTypes, PokeType.entries.mapNotNull { it.id(g.gen) })
            trainerMonsOf { at, moves, cls, _ ->
                // RIVAL's starter line: [rivals] gives it the new starters'.
                if (at in rivalPlan) return@trainerMonsOf
                val theme = themes[cls]
                val now = picker.pick(u8(at), keepType = o.trainersKeepType, only = theme?.let { setOf(it) })
                b[at] = now.toByte()
                if (moves >= 0) {
                    val list = movesAt(now, u8(at - 1))
                    for (k in 0 until 4) b[moves + k] = list.getOrElse(k) { 0 }.toByte()
                }
            }
        }
    }
}
