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

    companion object {
        /** A trainer's level with LEVELS' [percent] added, at most 100. */
        fun scaled(level: Int, percent: Int): Int =
            if (percent <= 0 || level <= 0) level else minOf(100, (level * (100 + percent) + 50) / 100)
    }
}

/** A cart as the run modes' tests look at it: each area's wild species, every trainer Pokémon (species, level), types. */
internal class CartView(val wild: List<List<Int>>, val trainers: List<Pair<Int, Int>>, val types: (Int) -> Set<Int>)
