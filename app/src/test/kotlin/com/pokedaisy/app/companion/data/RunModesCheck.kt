package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/** RANDOMIZE's run modes, checked the same way on every cart: what each one promises, read back off the made carts. */
internal object RunModesCheck {
    /** ONE TYPE (WATER), LEVELS +50% and NO DUPLICATES with a random Pokémon in every encounter. */
    val THEMED = RomRandomizer.Options(
        RomRandomizer.Starters.OFF, RomRandomizer.Wild.RANDOM, true, similarStrength = false,
        levelScale = 50, noDuplicates = true, monotype = PokeType.WATER,
    )
    /** EVERY POKéMON and trainers keeping their types, area by area. */
    val SPREAD = RomRandomizer.Options(RomRandomizer.Starters.OFF, RomRandomizer.Wild.PER_AREA, true, allCatchable = true, trainersKeepType = true)

    fun themed(title: String, gen: Int, retail: CartView, made: CartView) {
        val water = PokeType.WATER.id(gen)!!
        val pool = made.wild.flatten().toSet().size
        for (area in made.wild) {
            for (s in area) assertTrue("$title wild $s isn't WATER", water in made.types(s))
            if (area.size <= pool) assertEquals("$title: an area has a Pokémon twice", area.size, area.toSet().size)
        }
        assertEquals(title, retail.trainers.size, made.trainers.size)
        for ((i, t) in made.trainers.withIndex()) {
            assertTrue("$title trainer ${t.first} isn't WATER", water in made.types(t.first))
            assertEquals("$title trainer $i's level", SpeciesPicker.scaled(retail.trainers[i].second, 50), t.second)
        }
    }

    fun spread(title: String, retail: CartView, made: CartView, without: CartView) {
        for ((i, t) in made.trainers.withIndex()) {
            val was = retail.types(retail.trainers[i].first)
            assertTrue("$title trainer $i: ${t.first} shares no type with ${retail.trainers[i].first}", made.types(t.first).any { it in was })
        }
        val on = made.wild.flatten().toSet().size
        val off = without.wild.flatten().toSet().size
        assertTrue("$title: EVERY POKéMON placed $on kinds, $off without it", on > off)
    }
}

/** EVOLUTIONS and ABILITIES (with the strength match off, so any Pokémon can be picked). */
internal val EVOLVE_ABILITIES = RomRandomizer.Options(
    RomRandomizer.Starters.THREE_STAGE, RomRandomizer.Wild.OFF, false, similarStrength = false, evolutions = true, abilities = true,
)

/**
 * EVOLUTIONS and ABILITIES, read back off a made cart: every evolution where it was, the same way (method), most to a new
 * Pokémon, never to itself or to one of its own other targets, and no chain that loops; ABILITIES (where the cart has them:
 * [gen] 3-5) changes most Pokémon's, keeps empty slots empty and kept ones kept, never hands out the banned ones, never
 * gives one twice to a Pokémon. A starter still evolves twice.
 */
internal fun checkEvolveAbilities(title: String, gen: Int, retail: CartView, made: CartView, starters: List<Int> = emptyList()) {
    assertEquals("$title: evolutions", retail.evolutions.size, made.evolutions.size)
    assertTrue("$title has no evolutions", retail.evolutions.isNotEmpty())
    for ((i, e) in made.evolutions.withIndex()) {
        val was = retail.evolutions[i]
        assertEquals("$title evolution $i moved", was.first, e.first)
        assertEquals("$title evolution $i's method", was.second, e.second)
        assertTrue("$title: ${e.first} evolves into itself", e.third != e.first)
    }
    val changed = made.evolutions.indices.count { made.evolutions[it].third != retail.evolutions[it].third }
    assertTrue("$title: $changed of ${made.evolutions.size} evolutions changed", changed > made.evolutions.size / 2)
    val graph = made.evolutions.groupBy({ it.first }, { it.third })
    for ((s, to) in graph) assertEquals("$title: $s has a target twice: $to", to.size, to.toSet().size)
    fun reaches(from: Int, to: Int, seen: MutableSet<Int> = HashSet()): Boolean =
        from == to || seen.add(from) && graph[from].orEmpty().any { reaches(it, to, seen) }
    for ((s, to) in graph) for (t in to) assertTrue("$title: $s -> $t loops back", !reaches(t, s))
    for (s in starters) assertTrue("$title: starter $s doesn't evolve twice", graph[s].orEmpty().any { graph[it].orEmpty().isNotEmpty() })
    if (gen < 3) { assertTrue("$title has abilities", made.abilities.isEmpty()); return }
    assertEquals("$title: abilities", retail.abilities.keys, made.abilities.keys)
    val choices = SpeciesPicker.abilityChoices(gen).toSet()
    var differ = 0
    for ((s, now) in made.abilities) {
        val was = retail.abilities.getValue(s)
        if (now != was) differ++
        for ((k, a) in now.withIndex()) {
            when {
                was[k] == 0 -> assertEquals("$title $s slot $k was empty", 0, a)
                was[k] in SpeciesPicker.KEPT_ABILITIES -> assertEquals("$title $s slot $k is kept", was[k], a)
                else -> assertTrue("$title $s slot $k: $a", a in choices)
            }
        }
        // A kept one can be there twice as the game had it (Black's SHEDINJA: its first and hidden slots).
        val set = now.filter { it != 0 && it !in SpeciesPicker.KEPT_ABILITIES }
        assertEquals("$title $s: an ability twice $now", set.size, set.toSet().size)
    }
    assertTrue("$title: $differ of ${made.abilities.size} Pokémon's abilities changed", differ > made.abilities.size / 2)
}

/** GIFTS, read back: each gift / static's places agree, most are new, and none twice. */
internal fun checkGifts(title: String, retail: List<List<Int>>, made: List<List<Int>>) {
    assertEquals(title, retail.size, made.size)
    assertTrue("$title has no gifts", retail.isNotEmpty())
    for ((i, one) in made.withIndex()) assertEquals("$title gift $i's places disagree: $one", 1, one.toSet().size)
    val now = made.map { it[0] }
    assertTrue("$title: ${now.indices.count { now[it] != retail[it][0] }} of ${now.size} gifts changed",
        now.indices.count { now[it] != retail[it][0] } > now.size / 2)
    assertEquals("$title: a gift twice", now.size, now.toSet().size)
}
