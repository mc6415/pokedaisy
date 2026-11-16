package com.pokedaisy.app.companion.data

import kotlin.random.Random

/**
 * A type RANDOMIZE's ONE TYPE run can keep to, by its number in each generation's tables: Gen 1 / 2 (pret's TYPE
 * constants: the physical ones from 0, the special ones from 0x14; STEEL and DARK are Gen 2's), Gen 3 / 4 (??? at 9) and
 * Gen 5 (no ???, so FIRE and on are one lower). Null: the generation hasn't the type.
 */
enum class PokeType(val gen1: Int?, val gen2: Int?, val gen3: Int, val gen5: Int) {
    NORMAL(0, 0, 0, 0), FIRE(0x14, 0x14, 10, 9), WATER(0x15, 0x15, 11, 10), GRASS(0x16, 0x16, 12, 11),
    ELECTRIC(0x17, 0x17, 13, 12), ICE(0x19, 0x19, 15, 14), FIGHTING(1, 1, 1, 1), POISON(3, 3, 3, 3),
    GROUND(4, 4, 4, 4), FLYING(2, 2, 2, 2), PSYCHIC(0x18, 0x18, 14, 13), BUG(7, 7, 6, 6), ROCK(5, 5, 5, 5),
    GHOST(8, 8, 7, 7), DRAGON(0x1A, 0x1A, 16, 15), DARK(null, 0x1B, 17, 16), STEEL(null, 9, 8, 8);

    /** This type's number in generation [gen]'s tables, or null when it hasn't the type. */
    fun id(gen: Int): Int? = when (gen) {
        1 -> gen1
        2 -> gen2
        5 -> gen5
        else -> gen3
    }
}

/**
 * RANDOMIZE's species choices with its run modes, one for every cart's randomizer (GBA, Game Boy, DS): the strength
 * match ([RomRandomizer.Options.similarStrength]), ONE TYPE ([RomRandomizer.Options.monotype]: only that type's
 * Pokémon), trainers keeping a type ([RomRandomizer.Options.trainersKeepType]), no species twice in an area
 * ([RomRandomizer.Options.noDuplicates], the caller passes the area's) and EVERY POKéMON
 * ([RomRandomizer.Options.allCatchable]: wild picks go to species not placed yet first).
 */
internal class SpeciesPicker(
    pool: List<Int>,
    private val o: RomRandomizer.Options,
    private val rnd: Random,
    private val bst: (Int) -> Int,
    private val types: (Int) -> Set<Int>,
    /** [o]'s ONE TYPE as this generation numbers it (null: none, or the generation hasn't it). */
    monotype: Int?,
) {
    /** The species a pick comes from: the pool, kept to ONE TYPE's when it has any. */
    val pool: List<Int> = if (monotype == null) pool else pool.filter { monotype in types(it) }.ifEmpty { pool }
    private val monotyped = monotype != null

    /** Wild picks not made yet (EVERY POKéMON). */
    private val unplaced = this.pool.toMutableSet()

    /** The type filter for a list of candidates (starters, a GLOBAL swap's leftovers): ONE TYPE's when it has any. */
    fun themed(from: List<Int>): List<Int> = if (!monotyped) from else from.filter { it in pool }.ifEmpty { from }

    /**
     * A species for a slot that held [like]: never one of [avoid] while others are left; sharing a type with [like]
     * when [keepType] (unless ONE TYPE already decides); for a wild slot ([wild]) one not placed yet when EVERY POKéMON
     * asks; then about [like]'s strength when SIMILAR STRENGTH asks.
     */
    fun pick(
        like: Int, avoid: Collection<Int> = emptyList(), keepType: Boolean = false, wild: Boolean = false, from: List<Int> = pool,
        /** Only Pokémon with one of these types (a boss's theme, a habitat), while there are any; ONE TYPE wins over it. */
        only: Set<Int>? = null,
        /** Only Pokémon with every one of these types (a zone's, [zoneNeed]), while there are any; ONE TYPE wins over it. */
        need: Set<Int>? = null,
    ): Int {
        var cands = from.filter { it !in avoid }.ifEmpty { from }
        if (need != null && !monotyped) {
            cands.filter { s -> types(s).containsAll(need) }.takeIf { it.isNotEmpty() }?.let { cands = it }
        } else if (only != null && !monotyped) {
            cands.filter { s -> types(s).any { it in only } }.takeIf { it.isNotEmpty() }?.let { cands = it }
        } else if (keepType && !monotyped) {
            val mine = types(like)
            cands.filter { s -> types(s).any { it in mine } }.takeIf { it.isNotEmpty() }?.let { cands = it }
        }
        val fresh = if (wild && o.allCatchable) cands.filter { it in unplaced } else emptyList()
        val chosen = if (fresh.isNotEmpty()) near(fresh, like, widest = 3.0) else near(cands, like, widest = 1.0)
        if (wild) unplaced.remove(chosen)
        return chosen
    }

    /** One of [from] about [like]'s base stat total (a band widened till it holds a few), or any when strength isn't kept. */
    private fun near(from: List<Int>, like: Int, widest: Double): Int {
        if (!o.similarStrength) return from.random(rnd)
        val target = bst(like)
        var band = 0.1
        while (true) {
            val close = from.filter { kotlin.math.abs(bst(it) - target) <= target * band }
            if (close.size >= 6 || band > widest) return close.ifEmpty { from }.random(rnd)
            band += 0.05
        }
    }

    /**
     * EVOLUTIONS ([RomRandomizer.Options.evolutions]): a new target for each evolution entry ([entries]: the species it's
     * on and its old target, in table order), in the same order. The way it evolves (level, stone, trade) stays its own;
     * the target comes from the pool (ONE TYPE's, NO LEGENDARIES'), about the old target's strength when SIMILAR STRENGTH
     * asks; never the species itself, never one of its other targets, and never one that would evolve back into it (no
     * loops: each pick checks the chains picked so far). With nothing left to pick an entry keeps its old target.
     */
    fun evolutions(entries: List<Pair<Int, Int>>): List<Int> {
        val graph = HashMap<Int, MutableSet<Int>>()
        fun reaches(from: Int, to: Int): Boolean {
            val seen = HashSet<Int>()
            val stack = ArrayDeque(listOf(from))
            while (stack.isNotEmpty()) {
                val x = stack.removeLast()
                if (x == to) return true
                if (seen.add(x)) graph[x]?.let { stack.addAll(it) }
            }
            return false
        }
        val out = IntArray(entries.size)
        val mine = HashMap<Int, MutableSet<Int>>()
        for (i in entries.indices.shuffled(rnd)) {
            val (species, was) = entries[i]
            val taken = mine.getOrPut(species) { HashSet() }
            val cands = pool.filter { it != species && it !in taken && !reaches(it, species) }
            val now = if (cands.isEmpty()) was else pick(was, from = cands)
            out[i] = now
            taken += now
            graph.getOrPut(species) { HashSet() } += now
        }
        return out.toList()
    }

    /**
     * GYM TYPES: a type for each boss ([teams]: their key - a class, or a class and name - to the species of every team
     * they have), by [mode]: [RomRandomizer.BossTypes.OWN] the type most of their Pokémon have (on a tie, not NORMAL), and
     * [RomRandomizer.BossTypes.RANDOM] one picked for each from [choices] (the generation's types with a few Pokémon in the
     * pool), none twice while there are others. Nothing while ONE TYPE decides.
     */
    fun <K> themes(teams: Map<K, List<Int>>, mode: RomRandomizer.BossTypes, choices: List<Int>): Map<K, Int> {
        if (mode == RomRandomizer.BossTypes.OFF || monotyped) return emptyMap()
        val usable = choices.filter { t -> pool.count { t in types(it) } >= 4 }
        if (usable.isEmpty()) return emptyMap()
        val left = usable.toMutableList()
        return teams.mapValues { (_, species) ->
            if (mode == RomRandomizer.BossTypes.OWN) ownType(species, types) ?: usable.random(rnd)
            else (left.ifEmpty { usable.toMutableList().also { left.addAll(it) } }).random(rnd).also { left.remove(it) }
        }
    }

    /** Where a wild table is ([RomRandomizer.Options.habitats]): what its swaps lean to; and for ZONES BY TERRAIN the
     * types an area of it may get ([zoneKinds]). */
    enum class Habitat(private val kinds: List<PokeType>, private val zoneKinds: List<PokeType>) {
        LAND(emptyList(), emptyList()),
        WATER(listOf(PokeType.WATER), listOf(PokeType.WATER, PokeType.ICE, PokeType.FLYING)),
        CAVE(
            listOf(PokeType.ROCK, PokeType.GROUND, PokeType.POISON, PokeType.STEEL, PokeType.DARK),
            listOf(PokeType.ROCK, PokeType.GROUND, PokeType.GHOST, PokeType.DARK, PokeType.STEEL),
        ),
        ROCKS(listOf(PokeType.ROCK, PokeType.GROUND), listOf(PokeType.ROCK, PokeType.GROUND));

        /** The types as generation [gen] numbers them; null: anything goes ([LAND]). */
        fun types(gen: Int): Set<Int>? = kinds.mapNotNull { it.id(gen) }.toSet().ifEmpty { null }

        /** ZONES BY TERRAIN: the types an area of this kind may get, as [gen] numbers them; null: any. */
        fun zoneTypes(gen: Int): Set<Int>? = zoneKinds.mapNotNull { it.id(gen) }.toSet().ifEmpty { null }
    }

    /** ZONES: one area of a cart's wild tables (a town map place): its [key], what it is ([Habitat.CAVE], [Habitat.WATER]
     * with no land at all, else [Habitat.LAND]) and the areas it's joined to. */
    class Zone<K>(val key: K, val terrain: Habitat, val next: Set<K>)

    /**
     * ZONES ([RomRandomizer.Options.zones]): a type for each area, in [areas]' order. Only types with [MIN_ZONE] Pokémon in
     * the pool or more (Gen 1's Dragon or Ghost would be one line); each picked as often as the pool has Pokémon of it (the
     * games' own spread: Water, Normal, Bug lead); never an area's neighbour's while another is left; by TERRAIN a cave's
     * from Rock, Ground, Ghost, Dark, Steel and the water's from Water, Ice, Flying. Nothing while ONE TYPE decides.
     */
    fun <K> zones(areas: List<Zone<K>>, mode: RomRandomizer.Zones, gen: Int): Map<K, Int> {
        if (mode == RomRandomizer.Zones.OFF || monotyped) return emptyMap()
        val count = PokeType.entries.mapNotNull { it.id(gen) }.distinct()
            .associateWith { t -> pool.count { t in types(it) } }.filterValues { it >= MIN_ZONE }
        if (count.isEmpty()) return emptyMap()
        // Joined both ways, whichever side named the other.
        val joined = HashMap<K, MutableSet<K>>()
        for (a in areas) for (n in a.next) if (n != a.key) {
            joined.getOrPut(a.key) { HashSet() } += n
            joined.getOrPut(n) { HashSet() } += a.key
        }
        val out = LinkedHashMap<K, Int>()
        for (a in areas) {
            if (a.key in out) continue
            var cands = count.keys.toList()
            if (mode == RomRandomizer.Zones.TERRAIN) {
                a.terrain.zoneTypes(gen)?.let { kinds -> cands.filter { it in kinds }.takeIf { it.isNotEmpty() }?.let { cands = it } }
            }
            val taken = joined[a.key].orEmpty().mapNotNull { out[it] }.toSet()
            cands.filter { it !in taken }.takeIf { it.isNotEmpty() }?.let { cands = it }
            var roll = rnd.nextInt(cands.sumOf { count.getValue(it) })
            out[a.key] = cands.first { t -> roll -= count.getValue(t); roll < 0 }
        }
        return out
    }

    /** ZONES: what a slot of an area of [type] asks for: that type; in the water that type and Water while the pool has a
     * couple of both (a Grass pond's Lotad line), else Water. */
    fun zoneNeed(type: Int, water: Boolean, gen: Int): Set<Int> {
        val w = PokeType.WATER.id(gen)!!
        if (!water || type == w) return setOf(type)
        return if (pool.count { s -> types(s).let { type in it && w in it } } >= 2) setOf(type, w) else setOf(w)
    }

    companion object {
        /** ZONES: the fewest Pokémon of a type in the pool for an area to get it. */
        const val MIN_ZONE = 6

        /** A boss's own type: the one most of [species] have (each Pokémon's types counted once); on a tie, not NORMAL. */
        fun ownType(species: List<Int>, types: (Int) -> Set<Int>): Int? {
            val count = HashMap<Int, Int>()
            for (s in species) for (t in types(s)) count[t] = (count[t] ?: 0) + 1
            val best = count.values.maxOrNull() ?: return null
            val top = count.filterValues { it == best }.keys.sorted()
            return top.firstOrNull { it != PokeType.NORMAL.gen3 } ?: top.first()
        }

        /** [s]'s line from it: itself, what it evolves into first, and what that evolves into first ([evolvesTo]). */
        fun chain(s: Int, evolvesTo: (Int) -> List<Int>): List<Int> {
            val out = mutableListOf(s)
            while (out.size < 3) out += evolvesTo(out.last()).firstOrNull()?.takeIf { it !in out } ?: break
            return out
        }

        /**
         * RIVAL: for a species in one of the old starters' lines ([oldLines], by starter), the new starter's line member at
         * the same stage ([newLines], the line's last when it's shorter); null when [species] is in none.
         */
        fun rivalSwap(species: Int, oldLines: List<List<Int>>, newLines: List<List<Int>>): Int? {
            for ((i, line) in oldLines.withIndex()) {
                val stage = line.indexOf(species)
                if (stage < 0) continue
                val now = newLines.getOrNull(i) ?: return null
                return now.getOrNull(stage) ?: now.last()
            }
            return null
        }

        /**
         * ABILITIES ([RomRandomizer.Options.abilities]) never hands these out (by the games' shared ability numbers):
         * WONDER GUARD (25: only super-effective hits land), the ones that hold their own Pokémon back (TRUANT 54, SLOW START
         * 112, DEFEATIST 129), CACOPHONY (76, an unused copy of another) and the ones tied to one Pokémon's forms or trick
         * (FORECAST 59, MULTITYPE 121, ILLUSION 149, ZEN MODE 161).
         */
        val NEVER_ABILITIES = setOf(25, 54, 59, 76, 112, 121, 129, 149, 161)

        /** A Pokémon that has one of these keeps it: it is what that Pokémon is built around (SHEDINJA's 1 HP, the form changers). */
        val KEPT_ABILITIES = setOf(25, 59, 121, 149, 161)

        /** The abilities a generation has (Gen 3: 1-77, Gen 4: to 123, Gen 5: to 164), less [NEVER_ABILITIES]. */
        fun abilityChoices(gen: Int): List<Int> =
            (1..when (gen) { 3 -> 77; 4 -> 123; else -> 164 }).filter { it !in NEVER_ABILITIES }

        /** One species' new abilities for its slots [old] (0 = an empty slot, which stays empty): none twice, [KEPT_ABILITIES] kept. */
        fun abilities(old: List<Int>, choices: List<Int>, rnd: Random): List<Int> {
            val used = old.filter { it in KEPT_ABILITIES }.toMutableSet()
            return old.map { a ->
                when {
                    a == 0 -> 0
                    a in KEPT_ABILITIES -> a
                    else -> choices.filter { it !in used }.random(rnd).also { used += it }
                }
            }
        }

        /** A trainer's level with LEVELS' [percent] added, at most 100. */
        fun scaled(level: Int, percent: Int): Int =
            if (percent <= 0 || level <= 0) level else minOf(100, (level * (100 + percent) + 50) / 100)
    }
}

/** A cart as the run modes' tests look at it: each area's wild species, every trainer Pokémon (species, level), types; and
 * (EVOLUTIONS / ABILITIES) every evolution as (species, method, target) and each species' ability slots (none on the Game Boy).
 * For GYM TYPES, RIVAL, WILD LEVELS and HABITATS: every wild level byte, the species in water slots, each trainer Pokémon's
 * boss key (null: not a boss) and whether it's the rival's (both in [trainers]' order), and the starters (by starter); for
 * ZONES each area's species ([zoned]). */
internal class CartView(
    val wild: List<List<Int>>, val trainers: List<Pair<Int, Int>>, val types: (Int) -> Set<Int>,
    val evolutions: List<Triple<Int, Int, Int>> = emptyList(), val abilities: Map<Int, List<Int>> = emptyMap(),
    val wildLevels: List<Int> = emptyList(), val waterWild: List<Int> = emptyList(),
    val bosses: List<String?> = emptyList(), val rivals: List<Boolean> = emptyList(), val starters: List<Int> = emptyList(),
    /** ZONES: each area's wild species, land and water apart ("<area>:land" / "<area>:water"), and the areas that are caves. */
    val zoned: Map<String, List<Int>> = emptyMap(), val caves: Set<String> = emptySet(),
)

/**
 * EASY EVOLUTIONS ([RomRandomizer.Options.easyEvolutions]): every evolution a run can't make on its own becomes one it can,
 * as each engine numbers its methods ([Rules]). A trade becomes level 37, or a stone when the Pokémon already evolves by
 * level (SLOWPOKE's second, CLAMPERL's); a trade holding an item becomes, where the game has it (the DS), levelling up
 * holding that item by day, else the same as a plain trade; an evolution the game can't do at all (FireRed's day / night
 * friendship with no clock; a beauty, a mossy rock or a magnet where the game has none) becomes the stone [Rules.impossible]
 * names, or the first one the Pokémon doesn't already use. The target never changes, and nothing else does.
 */
internal object EasyEvolutions {
    class Rules(
        /** The game's level method, its use-an-item method, and its other methods whose parameter is an item. */
        val level: Int, val useItem: Int, val itemMethods: Set<Int> = emptySet(),
        /** Trades (Gen 5's trade for a species too), and a trade holding an item. */
        val trades: Set<Int>, val tradeItem: Int?,
        /** Levelling up holding an item by day (the DS); null: the game has none. */
        val holdDay: Int?,
        /** Methods the game can't do, and the stone each becomes (null: the first free one). */
        val impossible: Map<Int, Int?> = emptyMap(),
        /** The stones, in the order handed out; empty: never a stone (Gen 1's item entries are a byte longer). */
        val stones: List<Int>,
    )

    /** One species' evolutions ([entries]: method, parameter) as they become. */
    fun convert(entries: List<Pair<Int, Int>>, r: Rules): List<Pair<Int, Int>> {
        val used = entries.filter { it.first == r.useItem || it.first in r.itemMethods }.map { it.second }.toMutableSet()
        var hasLevel = entries.any { it.first == r.level }
        fun stone(want: Int? = null): Pair<Int, Int>? {
            val s = want?.takeIf { it !in used } ?: r.stones.firstOrNull { it !in used } ?: return null
            used += s
            return r.useItem to s
        }
        fun levelOrStone(): Pair<Int, Int> =
            if (!hasLevel) { hasLevel = true; r.level to LEVEL } else stone() ?: (r.level to LEVEL)
        return entries.map { (m, p) ->
            when {
                m == r.tradeItem && r.holdDay != null -> r.holdDay to p
                m in r.trades || (m == r.tradeItem && r.tradeItem != null) -> levelOrStone()
                m in r.impossible -> stone(r.impossible[m]) ?: levelOrStone()
                else -> m to p
            }
        }
    }

    /** What a trade becomes: UPR's level for it. */
    const val LEVEL = 37
}

/**
 * TM MOVES / TM LEARNERS ([RomRandomizer.Options.tmMoves], [RomRandomizer.Options.tmLearners]), the same on every engine: a
 * new move for each TM (the HMs keep theirs: the story needs them), none twice, most of them ones that do damage; and who
 * learns each TM picked again, likelier for a Pokémon sharing the move's type, a fair few for a NORMAL move, fewer for any
 * other. HMs' learners stay the game's.
 */
internal object TmPicks {
    /** [count] TM moves from [pool] (never an HM's), about two in three of them [damaging]. */
    fun moves(count: Int, pool: List<Int>, damaging: Set<Int>, rnd: Random): List<Int> {
        val hits = pool.filter { it in damaging }.shuffled(rnd)
        val rest = pool.filter { it !in damaging }.shuffled(rnd)
        val want = (count * 2 + 2) / 3
        val out = (hits.take(want) + rest.take(count - minOf(want, hits.size))).toMutableList()
        // Too few of either kind: fill from what's left.
        val left = (hits.drop(want) + rest.drop(count - minOf(want, hits.size))).shuffled(rnd)
        out += left.take(count - out.size)
        return out.shuffled(rnd).take(count)
    }

    /** Whether a Pokémon of [types] learns a move of [moveType] ([normal]: the generation's NORMAL). */
    fun learns(types: Set<Int>, moveType: Int, normal: Int, rnd: Random): Boolean = rnd.nextInt(100) < when {
        moveType in types -> 90
        moveType == normal -> 50
        else -> 25
    }
}

/**
 * TOUGHER TRAINERS ([RomRandomizer.Options.toughTrainers]): the four moves a trainer's Pokémon fights with, from all it has
 * learnt by its level ([pick]): its three best hits of different types (power x accuracy, half again for its own type),
 * then its newest move that doesn't do damage (a stat boost, sleep, poison), else its next best hit, else what's left.
 */
internal object BestMoves {
    class Move(val power: Int, val accuracy: Int, val type: Int)

    fun pick(known: List<Int>, info: (Int) -> Move, own: Set<Int>): List<Int> {
        if (known.size <= 4) return known
        fun score(m: Int) = info(m).let { it.power * (if (it.accuracy in 1..100) it.accuracy else 100) * (if (it.type in own) 3 else 2) }
        // Power 1 is the games' "varies" (fixed-damage moves and the like): neither a hit to rank nor a status move.
        val hits = known.filter { info(it).power > 1 }.sortedByDescending(::score)
        val out = ArrayList<Int>()
        val types = HashSet<Int>()
        for (m in hits) if (out.size < 3 && types.add(info(m).type)) out += m
        known.lastOrNull { info(it).power == 0 }?.let { if (out.size < 4) out += it }
        for (m in hits) if (out.size < 4 && m !in out) out += m
        for (m in known.asReversed()) if (out.size < 4 && m !in out) out += m
        return out
    }
}
