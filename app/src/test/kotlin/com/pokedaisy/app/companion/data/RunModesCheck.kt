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

    /** GYM TYPES (each boss's own type), RIVAL, WILD LEVELS +25% and HABITATS, a random Pokémon in every encounter (the
     * strength match off, so any Pokémon of a type can be picked). */
    val BOSSES = RomRandomizer.Options(
        RomRandomizer.Starters.THREE_STAGE, RomRandomizer.Wild.RANDOM, true, similarStrength = false,
        bossTypes = RomRandomizer.BossTypes.OWN, rivalStarter = true, wildLevelScale = 25, habitats = true,
    )

    /**
     * [BOSSES] (or its RANDOM [mode]) read back: every boss's Pokémon share a type - their own, as [SpeciesPicker.ownType]
     * finds it in the retail teams, for OWN - the rival's starter-line Pokémon are the new starters' at the same stage
     * ([rival]: the cart has a rival with one), every wild level raised 25%, and every water slot's Pokémon a Water type.
     */
    fun bosses(title: String, gen: Int, retail: CartView, made: CartView, mode: RomRandomizer.BossTypes, rival: Boolean = true) {
        assertEquals(title, retail.trainers.size, made.trainers.size)
        fun line(view: CartView, s: Int) = SpeciesPicker.chain(s) { x -> view.evolutions.filter { it.first == x }.map { it.third } }
        val oldLines = retail.starters.map { line(retail, it) }
        val newLines = made.starters.map { line(made, it) }
        val swapped = made.trainers.indices.filter { i -> made.rivals[i] && oldLines.any { retail.trainers[i].first in it } }
        if (rival) assertTrue("$title: no rival Pokémon in a starter line", swapped.isNotEmpty())
        for (i in swapped) assertEquals("$title rival Pokémon $i", SpeciesPicker.rivalSwap(retail.trainers[i].first, oldLines, newLines), made.trainers[i].first)
        val keys = made.bosses.withIndex().filter { it.value != null && it.index !in swapped }.groupBy({ it.value!! }, { it.index })
        assertTrue("$title has no bosses", keys.isNotEmpty())
        for ((key, idx) in keys) {
            val common = idx.map { made.types(made.trainers[it].first) }.reduce { a, b -> a intersect b }
            assertTrue("$title boss $key's team shares no type: ${idx.map { made.trainers[it].first }}", common.isNotEmpty())
            if (mode == RomRandomizer.BossTypes.OWN) {
                val own = SpeciesPicker.ownType(idx.map { retail.trainers[it].first }, retail.types)
                assertTrue("$title boss $key: their own type $own, the team's $common", own in common)
            }
        }
        assertEquals(title, retail.wildLevels.size, made.wildLevels.size)
        assertTrue("$title has no wild levels", retail.wildLevels.isNotEmpty())
        for ((i, lv) in made.wildLevels.withIndex()) assertEquals("$title wild level $i", SpeciesPicker.scaled(retail.wildLevels[i], 25), lv)
        val water = PokeType.WATER.id(gen)!!
        assertTrue("$title has no water slots", made.waterWild.isNotEmpty())
        for (s in made.waterWild) assertTrue("$title: $s in the water isn't WATER", water in made.types(s))
    }

    /** ZONES (or ZONES BY TERRAIN) with a random Pokémon in every encounter, the strength match off. */
    fun zonesOptions(mode: RomRandomizer.Zones) = RomRandomizer.Options(
        RomRandomizer.Starters.OFF, RomRandomizer.Wild.RANDOM, false, similarStrength = false, zones = mode,
    )

    /**
     * [zonesOptions] read back: every area's land Pokémon share a type, and so do its water ones (its type and Water, or
     * Water); the areas don't all get one type; by TERRAIN a cave's type is a cave's ([SpeciesPicker.Habitat.CAVE]).
     */
    fun zones(title: String, gen: Int, made: CartView, mode: RomRandomizer.Zones) {
        assertTrue("$title has no zones", made.zoned.isNotEmpty())
        val landTypes = HashSet<Int>()
        for ((key, species) in made.zoned) {
            val common = species.map { made.types(it) }.reduce { a, b -> a intersect b }
            assertTrue("$title $key shares no type: $species", common.isNotEmpty())
            if (key.endsWith(":land")) landTypes += common
            if (mode == RomRandomizer.Zones.TERRAIN && key in made.caves) {
                val cave = SpeciesPicker.Habitat.CAVE.zoneTypes(gen)!!
                assertTrue("$title cave $key's type $common", common.any { it in cave })
            }
        }
        assertTrue("$title: only ${landTypes.size} zone types", landTypes.size >= 5)
    }

    /** EASY EVOLUTIONS and WILD ITEMS, nothing else. */
    val EASY = RomRandomizer.Options(RomRandomizer.Starters.OFF, RomRandomizer.Wild.OFF, false, easyEvolutions = true, wildItems = true)

    /** TOUGHER TRAINERS, nothing else. */
    val TOUGH = RomRandomizer.Options(RomRandomizer.Starters.OFF, RomRandomizer.Wild.OFF, false, toughTrainers = true)

    /** TM MOVES and TM LEARNERS, nothing else. */
    val TMS = RomRandomizer.Options(RomRandomizer.Starters.OFF, RomRandomizer.Wild.OFF, false, tmMoves = true, tmLearners = true)

    /** [TMS]' table read back: the TMs all different and mostly new, the HMs' moves as they were and never a TM's. */
    fun tmTable(title: String, retail: List<Int>, made: List<Int>, tms: Int) {
        assertEquals(title, retail.size, made.size)
        assertEquals("$title HMs", retail.drop(tms), made.drop(tms))
        val now = made.take(tms)
        assertEquals("$title: a TM twice", tms, now.toSet().size)
        assertTrue("$title: an HM's move on a TM", now.none { it in retail.drop(tms) })
        assertTrue("$title: few TMs changed", now.indices.count { now[it] != retail[it] } > tms * 3 / 4)
    }

    /** [EASY] read back: every evolution where it was with its target, none left by [gone] (trades and what the game can't
     * do), some changed, and none changed that wasn't one. */
    fun easy(title: String, retail: CartView, made: CartView, gone: Set<Int>) {
        assertEquals(title, retail.evolutions.map { it.first to it.third }, made.evolutions.map { it.first to it.third })
        assertTrue("$title has none of them", retail.evolutions.any { it.second in gone })
        for ((i, e) in made.evolutions.withIndex()) {
            assertTrue("$title: ${e.first} > ${e.third} still by ${e.second}", e.second !in gone)
            if (retail.evolutions[i].second !in gone) assertEquals("$title ${e.first} > ${e.third}", retail.evolutions[i].second, e.second)
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
