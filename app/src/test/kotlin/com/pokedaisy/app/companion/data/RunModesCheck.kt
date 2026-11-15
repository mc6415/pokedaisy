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
