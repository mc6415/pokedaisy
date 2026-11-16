package com.pokedaisy.app.companion.data

import okio.ByteString.Companion.toByteString
import kotlin.random.Random

/**
 * The library's RANDOMIZE tool: a copy of the player's own retail English FireRed (rev 1), LeafGreen (rev 1), Ruby / Sapphire
 * (rev 2) or Emerald with its starters, wild Pokémon and trainers' teams shuffled by a seed. Same ROM, options and seed = the
 * same file.
 *
 * It writes only [sites]: the starters' species words (and FireRed / LeafGreen's Oak lines naming them), every wild slot's
 * species, every trainer Pokémon's species, its own moves and its held item, each species' base stats and level-up moves,
 * and the item of every item ball and hidden item but the key items and HMs. Those are found through pointers it never changes, so the
 * same list comes out of the retail cart and of any file made from it, and a file is this tool's output when its bytes with
 * [sites] zeroed hash to the game's [Game.madeSha1] ([recognise]): the companion then treats it as the retail cart (the
 * Poller's `retailRom`, for the battle cursors, SHINY ODDS, BOXES). FireRed and Emerald's sites are also all inside
 * Universal Pokémon Randomizer's ([RandomizedRetailRom.ranges]), so they read as randomized retail like UPR's.
 */
object RomRandomizer {
    enum class Starters { OFF, RANDOM, THREE_STAGE }
    enum class Wild { OFF, RANDOM, PER_AREA, GLOBAL }

    /** ZONES' choices ([Options.zones]). */
    enum class Zones { OFF, RANDOM, TERRAIN }
    /** GYM TYPES: the bosses' teams as they are, each boss's team on its own type, or on a type picked for it. */
    enum class BossTypes { OFF, OWN, RANDOM }

    data class Options(
        val starters: Starters = Starters.THREE_STAGE,
        val wild: Wild = Wild.PER_AREA,
        val trainers: Boolean = true,
        /** Swaps keep to Pokémon of about the same base stat total. */
        val similarStrength: Boolean = true,
        /** No legendary Pokémon in the grass or in trainers' teams (starters never are). */
        val noLegendaries: Boolean = true,
        /** Item balls and hidden items swapped among themselves (key items and HMs stay where they are). */
        val fieldItems: Boolean = false,
        /** Every trainer Pokémon that can hold an item holds one of the items trainers hold in the game. */
        val trainerItems: Boolean = false,
        /** Each Pokémon's six base stats shuffled among themselves: the same total, spread another way. */
        val baseStats: Boolean = false,
        /** Each Pokémon learns random moves at its own levels, the first always one that does damage. */
        val learnsets: Boolean = false,
        /** Trainers' levels raised by this percent (0, 10, 25, 50), at most 100. */
        val levelScale: Int = 0,
        /** No species twice in one area's wild list (as far as there are others to pick). */
        val noDuplicates: Boolean = false,
        /** Wild swaps go to species not placed yet first, so every one turns up somewhere. */
        val allCatchable: Boolean = false,
        /** A trainer's new Pokémon shares a type with the one it replaces (a leader keeps their theme). */
        val trainersKeepType: Boolean = false,
        /** Only this type's Pokémon in the grass, the starters and trainers' teams (null: any). */
        val monotype: PokeType? = null,
        /** Gift and static Pokémon (handed over, sitting on the map, prizes) swapped too ([RANDOMIZER_STATICS]). */
        val gifts: Boolean = false,
        /** Each evolution leads to another Pokémon, the same way (level, stone, trade) ([SpeciesPicker.evolutions]). */
        val evolutions: Boolean = false,
        /** Each Pokémon's abilities picked at random (GBA and DS: the Game Boy games have none) ([SpeciesPicker.abilities]). */
        val abilities: Boolean = false,
        /** GYM TYPES: with [trainers], every Gym Leader's, Elite Four member's and Champion's new team keeps to one type
         * ([SpeciesPicker.themes]): their own (what most of their team was) or one picked for each. */
        val bossTypes: BossTypes = BossTypes.OFF,
        /** RIVAL: the rival's starter line in their teams becomes the new starters' (the one in the same place, at the same
         * stage), so the rival carries the starter that beats yours and evolves it as the game goes on ([SpeciesPicker.chain]). */
        val rivalStarter: Boolean = false,
        /** Wild Pokémon's levels raised by this percent (0, 10, 25, 50), at most 100. */
        val wildLevelScale: Int = 0,
        /** HABITATS: a swap in the water is a Water type, in a cave or a smashed rock a Rock / Ground type and the like
         * ([SpeciesPicker.Habitat]); everywhere else anything. */
        val habitats: Boolean = false,
        /** ZONES: every wild Pokémon of an area (a town map place: a cave's floors together) shares one type, picked for it
         * at random or, by TERRAIN, one that suits the place ([SpeciesPicker.zones]); [habitats] gives way to it. */
        val zones: Zones = Zones.OFF,
        /** EASY EVOLUTIONS: trades and the evolutions the game can't do become a level or a stone ([EasyEvolutions]). */
        val easyEvolutions: Boolean = false,
        /** WILD ITEMS: wild Pokémon hold random items, at the game's own rates ([Options] per engine). */
        val wildItems: Boolean = false,
        /** TM MOVES: each TM teaches another move (HMs stay); TM LEARNERS: which Pokémon learn each TM / HM picked anew,
         * likelier for one of its own type ([TmPicks]). */
        val tmMoves: Boolean = false,
        val tmLearners: Boolean = false,
        /** TOUGHER TRAINERS: every trainer Pokémon with chosen moves fights with its best ([BestMoves]); on the GBA and DS
         * the bosses' and the rival's Pokémon get perfect IVs too. */
        val toughTrainers: Boolean = false,
    ) {
        val changesSomething get() = starters != Starters.OFF || wild != Wild.OFF || trainers || fieldItems || trainerItems ||
            baseStats || learnsets || levelScale > 0 || gifts || evolutions || abilities || wildLevelScale > 0 || easyEvolutions || wildItems || tmMoves || tmLearners || toughTrainers ||
            (rivalStarter && starters != Starters.OFF)
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
        /** gItems (44-byte struct Item, its pocket at +26), gBattleMoves (12 bytes a move, power at +1), gMapGroups. */
        val items: Int,
        val battleMoves: Int,
        val mapGroups: Int,
        /** The key items' pocket: FireRed / LeafGreen's 2, Ruby / Sapphire / Emerald's 5. */
        val keyPocket: Int,
        /** GYM TYPES: the trainer classes of the Gym Leaders, the Elite Four and the Champion (a trainer record's +1). */
        val bossClasses: Set<Int> = emptySet(),
        /** RIVAL: the classes of the rival who carries a starter (FireRed's rival, Ruby's / Emerald's Brendan and May). */
        val rivalClasses: Set<Int> = emptySet(),
        /** TM MOVES: sTMHMMoves (u16 TM01-TM50, then the HMs), each copy the game keeps (FireRed / Emerald's party menu has
         * its own); TM LEARNERS: gTMHMLearnsets (8 bytes a species, a bit a TM / HM from bit 0). */
        val tmMoves: List<Int> = emptyList(),
        val tmLearnsets: Int = 0,
        /** GIFTS' trades: sIngameTrades (0x3C a trade: the species you get at +0x0C, the one wanted at +0x38) and each
         * trade's retail (get, wanted), for the GUIDE. */
        val tradeTable: Int = 0,
        val tradeRetail: List<Pair<Int, Int>> = emptyList(),
    )

    // Trainer classes, read off the carts (each trainer record's +1, by the names the game gives them): Gym Leaders, Elite
    // Four, Champion; and the rival who carries a starter (FireRed's three rival classes, the last the Champion).
    private val FRLG_BOSSES = setOf(0x54, 0x57, 0x5A)
    private val FRLG_RIVALS = setOf(0x51, 0x59, 0x5A)
    private val RS_BOSSES = setOf(0x19, 0x18, 0x20)
    private val RS_RIVALS = setOf(0x2E)

    /** Oak's lab: each ball's script names its Pokémon twice, at these offsets from the first. */
    private fun oaksLab(at: Int) = listOf(0 to at, 0 to at + 466, 1 to at + 5, 1 to at + 515, 2 to at + 520, 2 to at + 461)
    /** sStarterMon: three u16s. */
    private fun starterMon(at: Int) = listOf(0 to at, 1 to at + 2, 2 to at + 4)

    val GAMES = listOf(
        Game(
            "BPRE", 1, "FireRed", "dd5945db9b930750cb39d00c84da8571feebf417", "52c93fb53e73cfa1b877c830b4621daab101e46b",
            trainers = 0x23EB38, trainerCount = 743, learnsets = 0x25D824, evolutions = 0x2597C4, baseStats = 0x2547F4,
            wildHeaders = 0x3C9D28, starterSites = oaksLab(0x169C2D), starterLines = listOf(0x18E2F1, 0x18E210, 0x18E283),
            speciesNames = 0x245F50, items = 0x3DB098, battleMoves = 0x250C74, mapGroups = 0x352718, keyPocket = 2,
            bossClasses = FRLG_BOSSES, rivalClasses = FRLG_RIVALS,
            tmMoves = listOf(0x45A604, 0x45A86C), tmLearnsets = 0x252C38,
            tradeTable = 0x26CFFC, tradeRetail = listOf(122 to 63, 124 to 61, 29 to 32, 83 to 21, 30 to 33, 108 to 55, 101 to 26, 114 to 48, 86 to 77),
        ),
        Game(
            "BPGE", 1, "LeafGreen", "7862c67bdecbe21d1d69ce082ce34327e1c6ed5e", "5a815a0d8e07a971226ae9e4b64482c418382fe5",
            trainers = 0x23EB14, trainerCount = 743, learnsets = 0x25D804, evolutions = 0x2597A4, baseStats = 0x2547D0,
            wildHeaders = 0x3C9B64, starterSites = oaksLab(0x169C09), starterLines = listOf(0x18E2CD, 0x18E1EC, 0x18E25F),
            speciesNames = 0x245F2C, items = 0x3DAED4, battleMoves = 0x250C50, mapGroups = 0x3526F8, keyPocket = 2,
            bossClasses = FRLG_BOSSES, rivalClasses = FRLG_RIVALS,
            tmMoves = listOf(0x45A034, 0x45A29C), tmLearnsets = 0x252C14,
            tradeTable = 0x26CFDC, tradeRetail = listOf(122 to 63, 124 to 61, 32 to 29, 83 to 21, 33 to 30, 108 to 80, 101 to 26, 114 to 48, 86 to 77),
        ),
        Game(
            "AXVE", 2, "Ruby", "5b64eacf892920518db4ec664e62a086dd5f5bc8", "4bbf1b48400c54242c7b4cd69076fd1795a44b0e",
            trainers = 0x1F0514, trainerCount = 694, learnsets = 0x207BE0, evolutions = 0x203B80, baseStats = 0x1FEC30,
            wildHeaders = 0x39D46C, starterSites = starterMon(0x3F76E0),
            items = 0x3C5580, battleMoves = 0x1FB144, mapGroups = 0x3085A0, keyPocket = 5,
            bossClasses = RS_BOSSES, rivalClasses = RS_RIVALS,
            tmMoves = listOf(0x37651C), tmLearnsets = 0x1FD108,
            tradeTable = 0x215ADC, tradeRetail = listOf(335 to 364, 315 to 25, 222 to 182),
        ),
        Game(
            "AXPE", 2, "Sapphire", "89b45fb172e6b55d51fc0e61989775187f6fe63c", "cabc26796093aca0bd4f3f982fbb0d8eef6f497f",
            trainers = 0x1F04A4, trainerCount = 694, learnsets = 0x207B70, evolutions = 0x203B10, baseStats = 0x1FEBC0,
            wildHeaders = 0x39D2B4, starterSites = starterMon(0x3F773C),
            items = 0x3C55DC, battleMoves = 0x1FB0D4, mapGroups = 0x308530, keyPocket = 5,
            bossClasses = RS_BOSSES, rivalClasses = RS_RIVALS,
            tmMoves = listOf(0x3764AC), tmLearnsets = 0x1FD098,
            tradeTable = 0x215A6C, tradeRetail = listOf(335 to 364, 315 to 25, 222 to 182),
        ),
        Game(
            "BPEE", 0, "Emerald", "f3ae088181bf583e55daf962a92bb46f4f1d07b7", "d2b5dff56d8741616a3094ae63e837fc8feb489d",
            trainers = 0x310030, trainerCount = 855, learnsets = 0x32937C, evolutions = 0x32531C, baseStats = 0x3203CC,
            wildHeaders = 0x552D48, starterSites = starterMon(0x5B1DF8),
            items = 0x5839A0, battleMoves = 0x31C898, mapGroups = 0x486578, keyPocket = 5,
            bossClasses = setOf(0x20, 0x1F, 0x26), rivalClasses = setOf(0x32, 0x41),
            tmMoves = listOf(0x615B94, 0x616040), tmLearnsets = 0x31E898,
            tradeTable = 0x338ED0, tradeRetail = listOf(298 to 392, 353 to 386, 116 to 395, 52 to 315),
        ),
    )

    private const val SPECIES = 411
    private const val BASE_STRIDE = 28
    private const val TRAINER_STRIDE = 40
    private const val EVO_STRIDE = 40
    private const val NAME_LEN = 11
    private const val ITEM_STRIDE = 44
    private const val MOVE_STRIDE = 12
    /** Moves 1..354, but STRUGGLE (165). */
    private val MOVES = (1..354).filter { it != 165 }
    /** Item ids past the last (Emerald's 376): a hidden-item event holding one isn't an item. */
    private const val ITEM_COUNT = 377
    /** HM01-08's items. */
    private val HM_ITEMS = 339..346
    /** Gen 3's Mail (never handed to a wild Pokémon) and stones (EASY EVOLUTIONS: Water, Thunder, Fire, Leaf, Moon, Sun). */
    private val MAIL = 121..132
    private const val TMS = 50
    private const val TRADE_STRIDE = 0x3C
    private fun r16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private const val SUN_STONE = 93
    private const val MOON_STONE = 94
    private val GEN3_STONES = listOf(97, 96, 95, 98, MOON_STONE, SUN_STONE)
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

    /**
     * The GUIDE's view of a cart this tool made ([recognise]): each item ball's item by its object's flag and each hidden
     * item's by its flag (FireRed / LeafGreen's hidden items count from flag 1000, Ruby / Sapphire's 600, Emerald's 500:
     * the event's hidden item number added, as the GUIDE's area data has them). Trades and gift Pokémon stay retail's.
     */
    fun guide(rom: ByteArray, g: Game): RandomizedGuide {
        val hiddenFlags = when (g.code.take(3)) { "BPR", "BPG" -> 1000; "AXV", "AXP" -> 600; else -> 500 }
        val r = Reader(rom)
        val items = HashMap<Int, Int>()
        fun isPtr(v: Int) = v in 0 until rom.size
        var group = 0
        while (group < 64 && isPtr(r.ptr(g.mapGroups + group * 4))) {
            val maps = r.ptr(g.mapGroups + group * 4)
            var m = 0
            while (m < 256 && isPtr(r.ptr(maps + m * 4))) {
                val e = r.ptr(r.ptr(maps + m * 4) + 4)
                if (isPtr(e)) {
                    val objects = r.ptr(e + 4)
                    if (isPtr(objects)) for (k in 0 until r.u8(e)) {
                        val o = objects + k * 24
                        val at = r.ptr(o + 16)
                        if (isPtr(at) && at + 12 <= rom.size && r.u8(at) == 0x1A && r.u16(at + 1) == 0x8000 && r.u8(at + 5) == 0x1A)
                            items[r.u16(o + 20)] = r.u16(at + 3)
                    }
                    val bgs = r.ptr(e + 16)
                    if (isPtr(bgs)) for (k in 0 until r.u8(e + 3)) {
                        val b = bgs + k * 12
                        // Not an item id: FireRed's one stray hidden event (43264), whose flag a real one has.
                        if (r.u8(b + 5) == 7 && r.u16(b + 8) in 1 until ITEM_COUNT) items[hiddenFlags + r.u8(b + 10)] = r.u16(b + 8)
                    }
                }
                m++
            }
            group++
        }
        // Trades by retail's "get/wanted" (internal ids, the area data's), as this cart has them.
        val trades = g.tradeRetail.withIndex().associate { (k, was) ->
            val at = g.tradeTable + k * TRADE_STRIDE
            "${was.first}/${was.second}" to (r16(rom, at + 0x0C) to r16(rom, at + 0x38))
        }
        return RandomizedGuide(items, trades = trades, statics = giftsNow(rom, g), keepUnknown = true)
    }

    /** Each gift / static Pokémon's species in [rom] by its retail species (when only one has that species). */
    private fun giftsNow(rom: ByteArray, g: Game): Map<Int, Int> {
        val was = staticSpecies(g)
        val places = statics(g)
        return was.indices.filter { i -> was.count { it == was[i] } == 1 }
            .associate { i -> was[i] to ((rom[places[i][0]].toInt() and 0xFF) or ((rom[places[i][0] + 1].toInt() and 0xFF) shl 8)) }
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
    internal fun madeHash(rom: ByteArray, game: Game): String? = runCatching {
        // A file that isn't the cart (a hack under its header) can point anywhere: sites() throws on that, and it's no match.
        val sites = sites(rom, game)
        val copy = rom.copyOf()
        for (r in sites) for (i in r) copy[i] = 0
        // The cart's clock registers: a state load writes them into the running ROM (the Poller's hashes skip them too).
        for (i in 0xC4..0xC9) copy[i] = 0
        sha1(copy)
    }.getOrNull()

    /** Every byte the tool may write in [rom] (found through pointers it never writes, so the same in its output). */
    /** ZONES' areas of [rom] (tests). */
    internal fun zoneAreas(rom: ByteArray, g: Game) = Reader(rom).zoneAreas(g)

    /** [rom] as the run modes' tests look at it ([CartView]). */
    internal fun view(rom: ByteArray, g: Game): CartView {
        val r = Reader(rom)
        val wild = LinkedHashMap<Int, MutableList<Int>>()
        r.wildSlots(g.wildHeaders) { slot, table -> wild.getOrPut(table) { ArrayList() } += r.u16(slot + 2) }
        val trainers = ArrayList<Pair<Int, Int>>()
        val bosses = ArrayList<String?>()
        val rivals = ArrayList<Boolean>()
        r.trainerMonsOf(g) { t, m, _ ->
            trainers += r.u16(m + 4) to r.u16(m + 2)
            bosses += r.bossKey(t).takeIf { r.trainerClass(t) in g.bossClasses }
            rivals += r.trainerClass(t) in g.rivalClasses
        }
        val evolutions = ArrayList<Triple<Int, Int, Int>>().also { l -> r.evolutionTargets(g) { s, at -> l += Triple(s, r.u16(at - 4), r.u16(at)) } }
        val abilities = ALL.associateWith { s -> (22..23).map { r.u8(g.baseStats + s * BASE_STRIDE + it) } }
        val levels = ArrayList<Int>().also { l -> r.wildSlots(g.wildHeaders) { slot, _ -> l += r.u8(slot); l += r.u8(slot + 1) } }
        val water = ArrayList<Int>().also { l -> r.wildTables(g.wildHeaders) { slot, _, kind, _ -> if (kind == 8 || kind == 16) l += r.u16(slot + 2) } }
        val starters = (0 until 3).map { which -> r.u16(g.starterSites.first { it.first == which }.second) }
        val zoned = LinkedHashMap<String, MutableList<Int>>()
        r.wildTables(g.wildHeaders) { slot, _, kind, map ->
            zoned.getOrPut("${r.mapSec(g, map)}:" + if (kind == 8 || kind == 16) "water" else "land") { ArrayList() } += r.u16(slot + 2)
        }
        val caves = r.zoneAreas(g).filter { it.terrain == SpeciesPicker.Habitat.CAVE }.map { "${it.key}:land" }.toSet()
        return CartView(wild.values.toList(), trainers, { s -> setOf(r.u8(g.baseStats + s * BASE_STRIDE + 6), r.u8(g.baseStats + s * BASE_STRIDE + 7)) },
            evolutions, abilities, levels, water, bosses, rivals, starters, zoned, caves)
    }

    /** [g]'s gift and static Pokémon: each one's species places ([RANDOMIZER_STATICS]). */
    internal fun statics(g: Game): List<IntArray> = RANDOMIZER_STATICS["${g.code}${g.rev}"].orEmpty().map { it.copyOfRange(1, it.size) }

    /** [g]'s gift and static Pokémon's retail species, in [statics]' order. */
    internal fun staticSpecies(g: Game): List<Int> = RANDOMIZER_STATICS["${g.code}${g.rev}"].orEmpty().map { it[0] }

    internal fun sites(rom: ByteArray, g: Game): List<IntRange> {
        val r = Reader(rom)
        val out = ArrayList<IntRange>()
        for ((_, at) in g.starterSites) out += at..at + 1
        // Oak's lines: from the name to the end of the 0xFF run that ends the text (the tool pads its shorter line with 0xFF).
        for (at in g.starterLines) {
            var end = at
            while (r.u8(end) != 0xFF && end - at < 0x200) end++
            while (r.u8(end + 1) == 0xFF && end - at < 0x200) end++
            out += at..end
        }
        // Each wild slot's levels (WILD LEVELS) and species.
        r.wildSlots(g.wildHeaders) { slot, _ -> out += slot..slot + 3 }
        r.trainerMons(g) { mon, moves ->
            // TOUGHER TRAINERS: the IV byte (u16).
            out += mon..mon + 1
            out += mon + 2..mon + 3
            out += mon + 4..mon + 5
            if (moves >= 0) out += moves until moves + 8
        }
        r.trainerItems(g) { item -> out += item..item + 1 }
        for (s in ALL) out += g.baseStats + s * BASE_STRIDE until g.baseStats + s * BASE_STRIDE + 6
        // ABILITIES: both slots (+22, +23).
        for (s in ALL) out += g.baseStats + s * BASE_STRIDE + 22..g.baseStats + s * BASE_STRIDE + 23
        // Each evolution's whole entry: EASY EVOLUTIONS writes its method and level / item too.
        r.evolutionTargets(g) { _, at -> out += at - 4..at + 1 }
        // WILD ITEMS: both held items.
        for (s in ALL) out += g.baseStats + s * BASE_STRIDE + 12..g.baseStats + s * BASE_STRIDE + 15
        // TM MOVES: TM01-TM50 in each table; TM LEARNERS: every species' first 7 bytes (TM50's bit is byte 6's second).
        for (t in g.tmMoves) out += t until t + TMS * 2
        if (g.tmLearnsets != 0) for (s in ALL) out += g.tmLearnsets + s * 8 until g.tmLearnsets + s * 8 + 7
        r.learnsetEntries(g) { at -> out += at..at + 1 }
        r.fieldItems(g) { at -> out += at..at + 1 }
        for (one in statics(g)) for (at in one) out += at..at + 1
        // GIFTS' trades: each trade's two species.
        for (k in g.tradeRetail.indices) { val at = g.tradeTable + k * TRADE_STRIDE; out += at + 0x0C..at + 0x0D; out += at + 0x38..at + 0x39 }
        require(out.all { it.first >= 0 && it.last < rom.size && it.last - it.first < 0x200 }) { "not this game's tables" }
        return out
    }

    private open class Reader(val b: ByteArray) {
        fun u8(at: Int) = b[at].toInt() and 0xFF
        fun u16(at: Int) = u8(at) or (u8(at + 1) shl 8)
        fun ptr(at: Int) = (u16(at) or (u16(at + 2) shl 16)) - 0x08000000

        /** Each wild slot (minLevel, maxLevel, species) of every encounter table, once. */
        fun wildSlots(headers: Int, each: (slot: Int, table: Int) -> Unit) = wildTables(headers) { slot, table, _, _ -> each(slot, table) }

        /** [wildSlots] with each table's kind (the header's 4 land, 8 water, 12 rock smash, 16 fishing) and its map
         * (group << 8 | number). */
        fun wildTables(headers: Int, each: (slot: Int, table: Int, kind: Int, map: Int) -> Unit) {
            var h = headers
            val seen = HashSet<Int>()
            while (!(u8(h) == 0xFF && u8(h + 1) == 0xFF)) {
                for ((k, slots) in listOf(4 to 12, 8 to 5, 12 to 5, 16 to 10)) {
                    val info = ptr(h + k)
                    if (info !in 0 until b.size - 8 || b[info].toInt() == 0) continue
                    val at = ptr(info + 4)
                    if (at !in 0 until b.size || !seen.add(at)) continue
                    for (i in 0 until slots) each(at + i * 4, at, k, u8(h) shl 8 or u8(h + 1))
                }
                h += 20
            }
        }

        /** A map's type (its header's +0x17: 4 underground - a cave), by group << 8 | number through gMapGroups; -1 unknown. */
        fun mapType(g: Game, map: Int): Int = mapHeader(g, map).let { if (it < 0) -1 else u8(it + 0x17) }

        /** A map's header (group << 8 | number through gMapGroups), or -1. */
        fun mapHeader(g: Game, map: Int): Int {
            val group = ptr(g.mapGroups + (map shr 8) * 4)
            if (group !in 0 until b.size) return -1
            val header = ptr(group + (map and 0xFF) * 4)
            return if (header in 0 until b.size - 0x18) header else -1
        }

        /** ZONES: a map's town map place (its header's regionMapSectionId, +0x14), or -1. */
        fun mapSec(g: Game, map: Int): Int = mapHeader(g, map).let { if (it < 0) -1 else u8(it + 0x14) }

        /** ZONES: the maps a map's edges lead into (its header's MapConnections, +0x0C: a count, then 12-byte records with
         * the map's group and number at +8). */
        fun joinedMaps(g: Game, map: Int): List<Int> {
            val h = mapHeader(g, map)
            if (h < 0) return emptyList()
            val p = ptr(h + 0x0C)
            if (p !in 0 until b.size - 8) return emptyList()
            val n = u16(p)
            val list = ptr(p + 4)
            if (n !in 1..8 || list !in 0 until b.size - n * 12) return emptyList()
            return (0 until n).map { u8(list + it * 12 + 8) shl 8 or u8(list + it * 12 + 9) }
        }

        /**
         * ZONES: the cart's wild areas, a town map place each, in its tables' order: a cave when one of its maps is, the
         * water when it has no grass, joined to the places its maps' edges lead into (and on through a town without wild
         * Pokémon, so Route 101 and Route 102 count as neighbours across Oldale).
         */
        fun zoneAreas(g: Game): List<SpeciesPicker.Zone<Int>> {
            val maps = LinkedHashMap<Int, MutableSet<Int>>()
            val land = HashSet<Int>()
            wildTables(g.wildHeaders) { _, _, kind, map ->
                val sec = mapSec(g, map)
                maps.getOrPut(sec) { LinkedHashSet() } += map
                if (kind == 4) land += sec
            }
            return maps.map { (sec, own) ->
                val next = HashSet<Int>()
                for (m in own) for (j in joinedMaps(g, m)) {
                    val js = mapSec(g, j)
                    next += js
                    if (js !in maps) for (k in joinedMaps(g, j)) next += mapSec(g, k)
                }
                val terrain = when {
                    own.any { mapType(g, it) == 4 } -> SpeciesPicker.Habitat.CAVE
                    sec in land -> SpeciesPicker.Habitat.LAND
                    else -> SpeciesPicker.Habitat.WATER
                }
                SpeciesPicker.Zone(sec, terrain, next - sec)
            }
        }

        /** Each trainer Pokémon's held item (u16) where its record has one (flag bit 1: at +6). */
        fun trainerItems(g: Game, each: (item: Int) -> Unit) {
            for (t in 1 until g.trainerCount) {
                val at = g.trainers + t * TRAINER_STRIDE
                val flags = u8(at)
                if (flags and 2 == 0) continue
                val party = ptr(at + 36)
                val stride = if (flags and 1 != 0) 16 else 8
                for (i in 0 until u8(at + 32)) each(party + i * stride + 6)
            }
        }

        /** Every level-up move entry (u16: level << 9 | move) of every species, the 0xFFFF end left out. */
        fun learnsetEntries(g: Game, each: (at: Int) -> Unit) {
            val seen = HashSet<Int>()
            for (s in ALL) {
                var at = ptr(g.learnsets + s * 4)
                if (!seen.add(at)) continue
                var n = 0
                while (u16(at) != 0xFFFF && n++ < 64) { each(at); at += 2 }
            }
        }

        /** Each evolution's target word (+4 of its 8-byte entry; the method at +0 is never written, so this finds the same
         * entries on a made cart), with the species it's on. */
        fun evolutionTargets(g: Game, each: (species: Int, at: Int) -> Unit) {
            for (s in ALL) for (k in 0 until 5) {
                val at = g.evolutions + s * EVO_STRIDE + k * 8
                if (u16(at) != 0) each(s, at + 4)
            }
        }

        /** An item's pocket in gItems. */
        fun pocket(g: Game, item: Int) = u8(g.items + item * ITEM_STRIDE + 26)

        /** An item the field keeps where it is: a key item or an HM. */
        fun keeps(g: Game, item: Int) = item !in 1 until ITEM_COUNT || item in HM_ITEMS || pocket(g, item) == g.keyPocket

        /**
         * Each item ball's and hidden item's item word (u16), once, but the key items', HMs' and anything not an item id: an item ball is an object
         * event whose script starts with the game's find-item lines (setorcopyvar 0x8000 item, setorcopyvar 0x8001 amount,
         * callstd 1); a hidden item a background event of kind 7, its item the data's low half. Every map by gMapGroups.
         */
        fun fieldItems(g: Game, each: (at: Int) -> Unit) {
            val seen = HashSet<Int>()
            fun rom(p: Int) = p - 0x08000000
            fun isPtr(v: Int) = v in 0x08000000 until 0x08000000 + b.size
            fun u32(at: Int) = u16(at) or (u16(at + 2) shl 16)
            var group = 0
            while (group < 64 && isPtr(u32(g.mapGroups + group * 4))) {
                val maps = rom(u32(g.mapGroups + group * 4))
                var m = 0
                while (m < 256 && isPtr(u32(maps + m * 4))) {
                    val header = rom(u32(maps + m * 4))
                    val ev = u32(header + 4)
                    if (isPtr(ev)) {
                        val e = rom(ev)
                        val objects = u32(e + 4)
                        if (isPtr(objects)) for (k in 0 until u8(e)) {
                            val sc = u32(rom(objects) + k * 24 + 16)
                            if (!isPtr(sc)) continue
                            val at = rom(sc)
                            if (at + 12 > b.size) continue
                            if (u8(at) == 0x1A && u16(at + 1) == 0x8000 && u8(at + 5) == 0x1A && u16(at + 6) == 0x8001 &&
                                u8(at + 10) == 0x09 && u8(at + 11) == 0x01 && !keeps(g, u16(at + 3)) && seen.add(at + 3)) each(at + 3)
                        }
                        val bgs = u32(e + 16)
                        if (isPtr(bgs)) for (k in 0 until u8(e + 3)) {
                            val at = rom(bgs) + k * 12
                            if (u8(at + 5) == 7 && !keeps(g, u16(at + 8)) && seen.add(at + 8)) each(at + 8)
                        }
                    }
                    m++
                }
                group++
            }
        }

        /** Each trainer Pokémon: its record and, when the trainer picks its moves, where they are (else -1). */
        fun trainerMons(g: Game, each: (mon: Int, moves: Int) -> Unit) = trainerMonsOf(g) { _, m, moves -> each(m, moves) }

        /** [trainerMons] with each one's trainer record. */
        fun trainerMonsOf(g: Game, each: (trainer: Int, mon: Int, moves: Int) -> Unit) {
            for (t in 1 until g.trainerCount) {
                val at = g.trainers + t * TRAINER_STRIDE
                val flags = u8(at)
                val party = ptr(at + 36)
                val stride = if (flags and 1 != 0) 16 else 8
                for (i in 0 until u8(at + 32)) {
                    val m = party + i * stride
                    each(at, m, if (flags and 1 != 0) m + if (flags and 2 != 0) 8 else 6 else -1)
                }
            }
        }

        /** A trainer record's class (+1). */
        fun trainerClass(trainer: Int) = u8(trainer + 1)

        /** GYM TYPES' key for a trainer record: its class and its name (Gym Leaders share a class). */
        fun bossKey(trainer: Int) = trainerClass(trainer).toString() + ":" + b.copyOfRange(trainer + 4, trainer + 16).toByteString().hex()
    }

    private class Run(val g: Game, b: ByteArray, val o: Options, val rnd: Random) : Reader(b) {
        fun put16(at: Int, v: Int) {
            b[at] = v.toByte()
            b[at + 1] = (v shr 8).toByte()
        }

        fun bst(s: Int) = (0 until 6).sumOf { u8(g.baseStats + s * BASE_STRIDE + it) }

        /** Evolution targets of each species (read again after EVOLUTIONS, so the starters' "evolve twice" follows them). */
        fun readEvolvesTo(): Map<Int, List<Int>> = ALL.associateWith { s ->
            (0 until 5).mapNotNull { k ->
                val at = g.evolutions + s * EVO_STRIDE + k * 8
                if (u16(at) == 0) null else u16(at + 4).takeIf { it in 1..SPECIES }
            }
        }
        var evolvesTo: Map<Int, List<Int>> = readEvolvesTo()
        val evolvedFrom: Set<Int> get() = evolvesTo.values.flatten().toSet()

        /** Base stats' two type bytes (+6, +7). */
        fun types(s: Int) = setOf(u8(g.baseStats + s * BASE_STRIDE + 6), u8(g.baseStats + s * BASE_STRIDE + 7))

        val picker = SpeciesPicker(ALL.filter { !o.noLegendaries || it !in LEGENDARY }, o, rnd, ::bst, ::types, o.monotype?.id(3))
        val pool get() = picker.pool

        /** RIVAL: the starters' lines as the cart had them (before EVOLUTIONS and STARTERS change them), by starter. */
        val oldStarters = (0 until 3).map { which -> g.starterSites.first { it.first == which }.second.let(::u16) }
        val oldLines = oldStarters.map { s -> SpeciesPicker.chain(s) { evolvesTo[it].orEmpty() } }
        var newStarters = oldStarters

        fun all() {
            // Stats and learnsets first: the strength match keeps working (the totals stay) and trainers' moves follow.
            if (o.baseStats) baseStats()
            if (o.learnsets) learnsets()
            if (o.abilities) abilities()
            if (o.easyEvolutions) easyEvolutions()
            if (o.wildItems) wildItems()
            if (o.tmMoves) tmMoves()
            if (o.tmLearners) tmLearners()
            // Before the starters: "evolve twice" goes by the new evolutions.
            if (o.evolutions) evolutions()
            starters()
            wild()
            if (o.wildLevelScale > 0) wildLevels()
            if (o.gifts) gifts()
            // GIFTS' trades: after the wild swaps, so a trade asks for one that lives in the grass.
            if (o.gifts) trades()
            if (o.levelScale > 0) levels()
            // RIVAL's slots from the teams as the cart has them (TRAINERS may put a starter line's Pokémon anywhere).
            planRivals()
            if (o.trainers) trainers()
            rivals()
            if (o.trainerItems) trainerItems()
            if (o.toughTrainers) toughTrainers()
            if (o.fieldItems) fieldItems()
        }

        /** TOUGHER TRAINERS: each trainer Pokémon with chosen moves gets its best ([BestMoves]); the bosses' and the rival's
         * IV byte (a party Pokémon's +0: 0-255 = 0-31 in every stat) goes to 255. */
        fun toughTrainers() {
            fun info(m: Int) = BestMoves.Move(power(m), u8(g.battleMoves + m * MOVE_STRIDE + 3), u8(g.battleMoves + m * MOVE_STRIDE + 2))
            trainerMonsOf(g) { t, m, moves ->
                val species = u16(m + 4)
                if (species !in ALL) return@trainerMonsOf
                if (moves >= 0) {
                    val own = setOf(u8(g.baseStats + species * BASE_STRIDE + 6), u8(g.baseStats + species * BASE_STRIDE + 7))
                    val list = BestMoves.pick(knownAt(species, u16(m + 2)), ::info, own)
                    for (k in 0 until 4) put16(moves + k * 2, list.getOrElse(k) { 0 })
                }
                val cls = trainerClass(t)
                if (cls in g.bossClasses || cls in g.rivalClasses) put16(m, 255)
            }
        }

        /** WILD LEVELS: every wild slot's two levels raised. */
        fun wildLevels() {
            wildSlots(g.wildHeaders) { slot, _ ->
                for (k in 0..1) b[slot + k] = SpeciesPicker.scaled(u8(slot + k), o.wildLevelScale).toByte()
            }
        }

        /** RIVAL: [rivalSwap] for a rival's Pokémon (or null: not the rival's, or not a starter line's). */
        fun rivalNew(trainer: Int, species: Int): Int? =
            if (!o.rivalStarter || trainerClass(trainer) !in g.rivalClasses) null
            else SpeciesPicker.rivalSwap(species, oldLines, newStarters.map { s -> SpeciesPicker.chain(s) { evolvesTo[it].orEmpty() } })

        /** RIVAL: each of the rival's starter-line Pokémon's record, its new species and its moves' place. */
        var rivalPlan: Map<Int, Pair<Int, Int>> = emptyMap()

        fun planRivals() {
            val plan = HashMap<Int, Pair<Int, Int>>()
            trainerMonsOf(g) { t, m, moves -> rivalNew(t, u16(m + 4))?.let { plan[m] = it to moves } }
            rivalPlan = plan
        }

        /** RIVAL: the planned Pokémon made the new starters' (their moves as the game picks them). */
        fun rivals() {
            for ((m, p) in rivalPlan) {
                val (now, moves) = p
                put16(m + 4, now)
                if (moves >= 0) {
                    val list = movesAt(now, u8(m + 2))
                    for (k in 0 until 4) put16(moves + k * 2, list.getOrElse(k) { 0 })
                }
            }
        }

        /** Each Pokémon's six base stats in another order (SHEDINJA's 1 HP left as it is). */
        fun baseStats() {
            for (s in ALL) {
                val at = g.baseStats + s * BASE_STRIDE
                if (u8(at) == 1) continue
                val six = (0 until 6).map { u8(at + it) }.shuffled(rnd)
                six.forEachIndexed { i, v -> b[at + i] = v.toByte() }
            }
        }

        /** ABILITIES: both slots of every species (+22, +23; an empty second slot stays empty). */
        fun abilities() {
            val choices = SpeciesPicker.abilityChoices(3)
            for (s in ALL) {
                val at = g.baseStats + s * BASE_STRIDE + 22
                SpeciesPicker.abilities(listOf(u8(at), u8(at + 1)), choices, rnd).forEachIndexed { i, a -> b[at + i] = a.toByte() }
            }
        }

        /** EASY EVOLUTIONS ([EasyEvolutions]): Gen 3's methods (4 level, 7 stone, 5 trade, 6 trade holding an item); FireRed /
         * LeafGreen have no clock and no Pokéblocks, so their day / night friendship (2, 3) become the Sun / Moon Stone and
         * beauty (15) a stone. */
        fun easyEvolutions() {
            val frlg = g.code == "BPRE" || g.code == "BPGE"
            val rules = EasyEvolutions.Rules(
                level = 4, useItem = 7, trades = setOf(5), tradeItem = 6, holdDay = null,
                impossible = if (frlg) mapOf(2 to SUN_STONE, 3 to MOON_STONE, 15 to null) else emptyMap(),
                stones = GEN3_STONES,
            )
            for (s in ALL) {
                val at = (0 until 5).map { g.evolutions + s * EVO_STRIDE + it * 8 }.filter { u16(it) != 0 }
                val now = EasyEvolutions.convert(at.map { u16(it) to u16(it + 2) }, rules)
                at.forEachIndexed { i, a -> put16(a, now[i].first); put16(a + 2, now[i].second) }
            }
        }

        /** WILD ITEMS: each species' two held items (base stats +12, 50%; +14, 5%) from the items with a hold effect (struct
         * Item +0x12), neither a key item, Mail nor an HM; about a quarter of species get a common one, most a rare one. */
        fun wildItems() {
            val pool = (1 until ITEM_COUNT).filter { u8(g.items + it * ITEM_STRIDE + 0x12) != 0 && !keeps(g, it) && it !in MAIL }
            if (pool.isEmpty()) return
            for (s in ALL) {
                val at = g.baseStats + s * BASE_STRIDE + 12
                val common = if (rnd.nextInt(100) < 25) pool.random(rnd) else 0
                var rare = if (rnd.nextInt(100) < 60) pool.random(rnd) else 0
                // The same item in both slots means always held: never that.
                if (rare == common) rare = 0
                put16(at, common)
                put16(at + 2, rare)
            }
        }

        /** TM MOVES ([TmPicks]): TM01-TM50 in every copy of the table; the HMs stay, and none of their moves is a TM's. */
        fun tmMoves() {
            val first = g.tmMoves.firstOrNull() ?: return
            val hms = (TMS until TMS + 8).map { u16(first + it * 2) }.toSet()
            val pool = MOVES.filter { it !in hms }
            val now = TmPicks.moves(TMS, pool, pool.filter { power(it) > 0 }.toSet(), rnd)
            for (table in g.tmMoves) now.forEachIndexed { i, m -> put16(table + i * 2, m) }
        }

        /** TM LEARNERS ([TmPicks]): each species' TM bits (gTMHMLearnsets' first 50); the HMs' stay. */
        fun tmLearners() {
            val first = g.tmMoves.firstOrNull() ?: return
            if (g.tmLearnsets == 0) return
            val types = (0 until TMS).map { u8(g.battleMoves + u16(first + it * 2) * MOVE_STRIDE + 2) }
            for (s in ALL) {
                val at = g.tmLearnsets + s * 8
                val mine = setOf(u8(g.baseStats + s * BASE_STRIDE + 6), u8(g.baseStats + s * BASE_STRIDE + 7))
                for (tm in 0 until TMS) {
                    val bit = 1 shl (tm % 8)
                    val v = u8(at + tm / 8)
                    b[at + tm / 8] = (if (TmPicks.learns(mine, types[tm], 0, rnd)) v or bit else v and bit.inv()).toByte()
                }
            }
        }

        /** EVOLUTIONS: every evolution's target ([SpeciesPicker.evolutions]); its method and level / item stay. */
        fun evolutions() {
            val sites = ArrayList<Pair<Int, Int>>().also { l -> evolutionTargets(g) { s, at -> if (u16(at) in ALL) l += s to at } }
            val now = picker.evolutions(sites.map { (s, at) -> s to u16(at) })
            sites.forEachIndexed { i, (_, at) -> put16(at, now[i]) }
            evolvesTo = readEvolvesTo()
        }

        fun power(move: Int) = u8(g.battleMoves + move * MOVE_STRIDE + 1)

        /** Random moves at each species' own levels: none twice, the first one with power. */
        fun learnsets() {
            val damaging = MOVES.filter { power(it) > 0 }
            val seen = HashSet<Int>()
            for (s in ALL) {
                var at = ptr(g.learnsets + s * 4)
                if (!seen.add(at)) continue
                val used = HashSet<Int>()
                var first = true
                while (u16(at) != 0xFFFF) {
                    val level = u16(at) shr 9
                    var move: Int
                    do { move = (if (first) damaging else MOVES).random(rnd) } while (!used.add(move))
                    first = false
                    put16(at, (level shl 9) or move)
                    at += 2
                }
            }
        }

        /** Every held item slot a trainer has: one of the items trainers hold anywhere in the game. */
        fun trainerItems() {
            val held = ArrayList<Int>().also { l -> trainerItems(g) { at -> if (u16(at) != 0) l += u16(at) } }.distinct()
            if (held.isEmpty()) return
            trainerItems(g) { at -> put16(at, held.random(rnd)) }
        }

        /** The item balls' and hidden items' items, shuffled among the same places (key items and HMs not among them). */
        fun fieldItems() {
            val places = ArrayList<Int>().also { l -> fieldItems(g) { l += it } }
            val items = places.map { u16(it) }.shuffled(rnd)
            places.forEachIndexed { i, at -> put16(at, items[i]) }
        }

        fun starters() {
            val pickFrom = when (o.starters) {
                Starters.OFF -> return
                Starters.RANDOM -> picker.themed(ALL.filter { it !in LEGENDARY })
                Starters.THREE_STAGE -> picker.themed(ALL.filter { s ->
                    s !in evolvedFrom && s !in LEGENDARY && evolvesTo[s].orEmpty().any { evolvesTo[it].orEmpty().isNotEmpty() }
                })
            }.toMutableList()
            val three = List(3) { pickFrom.random(rnd).also { pickFrom.remove(it) } }
            newStarters = three
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
                    val to = picker.pick(s, from = left.ifEmpty { pool }, wild = true)
                    left.remove(to)
                    global[s] = to
                }
            }
            // PER_AREA: one map per encounter table; NO DUPLICATES: what the table already has is avoided.
            var area = HashMap<Int, Int>()
            var used = HashSet<Int>()
            var table = -1
            // ZONES: each place's type.
            val zones = if (o.zones == Zones.OFF) emptyMap() else picker.zones(zoneAreas(g), o.zones, 3)
            wildTables(g.wildHeaders) { slot, at, kind, map ->
                if (at != table) { area = HashMap(); used = HashSet(); table = at }
                val sp = slot + 2
                val was = u16(sp)
                if (was !in 1..SPECIES) return@wildTables
                val avoid = if (o.noDuplicates) used else emptySet<Int>()
                val need = zones[mapSec(g, map)]?.let { picker.zoneNeed(it, kind == 8 || kind == 16, 3) }
                // HABITATS: water and fishing tables, rock smash, a cave's land.
                val only = if (!o.habitats || need != null) null else when (kind) {
                    8, 16 -> SpeciesPicker.Habitat.WATER
                    12 -> SpeciesPicker.Habitat.ROCKS
                    else -> if (mapType(g, map) == 4) SpeciesPicker.Habitat.CAVE else SpeciesPicker.Habitat.LAND
                }.types(3)
                val now = when (o.wild) {
                    Wild.RANDOM -> picker.pick(was, avoid, wild = true, only = only, need = need)
                    Wild.PER_AREA -> area.getOrPut(was) { picker.pick(was, avoid, wild = true, only = only, need = need) }
                    else -> global.getValue(was)
                }
                used += now
                put16(sp, now)
            }
        }

        fun trainers() {
            // GYM TYPES: each boss's type, from the teams as they are now.
            val teams = LinkedHashMap<String, MutableList<Int>>()
            trainerMonsOf(g) { t, m, _ -> if (trainerClass(t) in g.bossClasses) teams.getOrPut(bossKey(t)) { ArrayList() } += u16(m + 4) }
            val themes = picker.themes(teams, o.bossTypes, PokeType.entries.map { it.gen3 })
            trainerMonsOf(g) { t, m, moves ->
                val was = u16(m + 4)
                if (was !in 1..SPECIES) return@trainerMonsOf
                // RIVAL's starter line: [rivals] gives it the new starters'.
                if (m in rivalPlan) return@trainerMonsOf
                val theme = if (trainerClass(t) in g.bossClasses) themes[bossKey(t)] else null
                val now = picker.pick(was, keepType = o.trainersKeepType, only = theme?.let { setOf(it) })
                put16(m + 4, now)
                if (moves >= 0) {
                    val list = movesAt(now, u8(m + 2))
                    for (k in 0 until 4) put16(moves + k * 2, list.getOrElse(k) { 0 })
                }
            }
        }

        /** GIFTS' trades: what each person gives you ([SpeciesPicker.pick], none twice) and the one they want, picked from
         * the species in the wild now (so every trade can be made); the game's script names both from the table. */
        fun trades() {
            if (g.tradeTable == 0) return
            val wild = HashSet<Int>().also { l -> wildSlots(g.wildHeaders) { slot, _ -> u16(slot + 2).takeIf { it in ALL }?.let(l::add) } }
            val wanted = wild.filter { it in picker.pool }.ifEmpty { picker.pool }
            val used = HashSet<Int>()
            for (k in g.tradeRetail.indices) {
                val at = g.tradeTable + k * TRADE_STRIDE
                val get = picker.pick(u16(at + 0x0C), used).also { used += it }
                val want = picker.pick(u16(at + 0x38), setOf(get), from = wanted)
                put16(at + 0x0C, get)
                put16(at + 0x38, want)
            }
        }

        /** GIFTS: each gift / static Pokémon swapped (every place its species is written), none twice. */
        fun gifts() {
            val used = HashSet<Int>()
            for (one in statics(g)) {
                val was = u16(one[0])
                if (was !in 1..SPECIES) continue
                val now = picker.pick(was, used)
                used += now
                for (at in one) put16(at, now)
            }
        }

        /** LEVELS: every trainer Pokémon's level (u16 at +2) raised, before [trainers] works out a new team's moves for them. */
        fun levels() {
            // Hand-picked moves stay as they are when the team isn't shuffled.
            trainerMons(g) { m, _ -> put16(m + 2, SpeciesPicker.scaled(u16(m + 2), o.levelScale)) }
        }

        /** The last four moves [species] has learnt by [level], as the game picks a wild one's. */
        fun movesAt(species: Int, level: Int): List<Int> = knownAt(species, level).takeLast(4)

        /** Every move [species] has learnt by [level], once each, in the order it learns them. */
        fun knownAt(species: Int, level: Int): List<Int> {
            val known = ArrayList<Int>()
            var at = ptr(g.learnsets + species * 4)
            while (u16(at) != 0xFFFF) {
                val v = u16(at)
                if (v shr 9 > level) break
                val move = v and 0x1FF
                if (move !in known) known += move
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
