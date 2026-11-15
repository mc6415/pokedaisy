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
    fun pick(like: Int, avoid: Collection<Int> = emptyList(), keepType: Boolean = false, wild: Boolean = false, from: List<Int> = pool): Int {
        var cands = from.filter { it !in avoid }.ifEmpty { from }
        if (keepType && !monotyped) {
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

    companion object {
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
 * (EVOLUTIONS / ABILITIES) every evolution as (species, method, target) and each species' ability slots (none on the Game Boy). */
internal class CartView(
    val wild: List<List<Int>>, val trainers: List<Pair<Int, Int>>, val types: (Int) -> Set<Int>,
    val evolutions: List<Triple<Int, Int, Int>> = emptyList(), val abilities: Map<Int, List<Int>> = emptyMap(),
)
