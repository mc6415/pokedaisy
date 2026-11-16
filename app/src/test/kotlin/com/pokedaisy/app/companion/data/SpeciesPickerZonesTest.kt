package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** ZONES' planner ([SpeciesPicker.zones], [SpeciesPicker.zoneNeed]) over a made-up pool. */
class SpeciesPickerZonesTest {
    private val fire = PokeType.FIRE.gen3
    private val water = PokeType.WATER.gen3
    private val grass = PokeType.GRASS.gen3
    private val rock = PokeType.ROCK.gen3
    private val ghost = PokeType.GHOST.gen3
    private val dragon = PokeType.DRAGON.gen3

    // 10 of each but Dragon's 3; two Grass / Water ones, no Fire / Water one.
    private val typesOf: Map<Int, Set<Int>> = buildMap {
        var id = 1
        for (t in listOf(fire, water, grass, rock, ghost)) repeat(10) { put(id++, setOf(t)) }
        repeat(3) { put(id++, setOf(dragon)) }
        put(id++, setOf(grass, water)); put(id, setOf(grass, water))
    }

    private fun picker(seed: Long) = SpeciesPicker(
        typesOf.keys.toList(), RomRandomizer.Options(similarStrength = false), Random(seed), { 300 }, { typesOf[it].orEmpty() }, null,
    )

    @Test
    fun `neighbours never share a type, small types never come up`() {
        val areas = (0 until 200).map { SpeciesPicker.Zone(it, SpeciesPicker.Habitat.LAND, if (it == 0) emptySet() else setOf(it - 1)) }
        val plan = picker(1).zones(areas, RomRandomizer.Zones.RANDOM, 3)
        assertEquals(200, plan.size)
        for (i in 1 until 200) assertNotEquals("area $i", plan[i - 1], plan[i])
        assertTrue(dragon !in plan.values)
        assertTrue(plan.values.toSet().size >= 4)
    }

    @Test
    fun `by terrain a cave gets a cave's type and the sea the water's`() {
        val areas = (0 until 60).map {
            SpeciesPicker.Zone(it, if (it % 2 == 0) SpeciesPicker.Habitat.CAVE else SpeciesPicker.Habitat.WATER, emptySet())
        }
        val plan = picker(2).zones(areas, RomRandomizer.Zones.TERRAIN, 3)
        for ((k, t) in plan) {
            if (k % 2 == 0) assertTrue("cave $k: $t", t == rock || t == ghost)
            else assertEquals("sea $k", water, t)
        }
    }

    @Test
    fun `a zone's water slots keep its type where they can`() {
        val p = picker(3)
        assertEquals(setOf(grass, water), p.zoneNeed(grass, water = true, gen = 3))
        assertEquals(setOf(water), p.zoneNeed(fire, water = true, gen = 3))
        assertEquals(setOf(fire), p.zoneNeed(fire, water = false, gen = 3))
    }

    @Test
    fun `ONE TYPE leaves no zones`() {
        val p = SpeciesPicker(typesOf.keys.toList(), RomRandomizer.Options(), Random(4), { 300 }, { typesOf[it].orEmpty() }, fire)
        assertTrue(p.zones(listOf(SpeciesPicker.Zone(0, SpeciesPicker.Habitat.LAND, emptySet())), RomRandomizer.Zones.RANDOM, 3).isEmpty())
    }
}
