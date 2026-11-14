package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the NUZLOCKE tracker reads ([NuzlockeSample.of]) on every GBA battle capture: in battle, wild or trainer, the
 * foe, and the party's personalities (each the run's id for a Pokémon).
 */
class NuzlockeFixturesTest {
    private val fixtures = listOf(
        "emerald_de_battle" to NATIVE_EMERALD_DE, "emerald_es_battle" to NATIVE_EMERALD_ES, "emerald_fr_battle" to NATIVE_EMERALD_FR,
        "emerald_it_battle" to NATIVE_EMERALD_IT, "emerald_ja_battle" to NATIVE_EMERALD_JA, "sapphire_rev1_battle" to NATIVE_SAPPHIRE,
        "emerald_rogue_battle" to NATIVE_EMERALD_ROGUE,
        "unbound_battle" to NATIVE_UNBOUND_WITH_DEX, "soulgold_battle" to NATIVE_SOULGOLD, "soulgold_v12_battle" to NATIVE_SOULGOLD_V1_2,
        "soulgold_v12b_battle" to NATIVE_SOULGOLD_V1_2B, "soulgold_v12c_battle" to NATIVE_SOULGOLD_V1_2C,
        "soulgold_v121_battle" to NATIVE_SOULGOLD_V1_2_1,
        "emerald_kaizo_battle" to NATIVE_EMERALD_KAIZO, "emerald_legacy_battle" to NATIVE_EMERALD_LEGACY, "radical_red_battle" to NATIVE_RADICAL_RED_V4_1,
        "gaia_battle" to NATIVE_GAIA_V3_2, "glazed_battle" to NATIVE_GLAZED, "lazarus_battle" to NATIVE_LAZARUS,
        "odyssey_battle" to NATIVE_ODYSSEY, "amethyst_battle" to NATIVE_AMETHYST, "sword_shield_up_battle" to NATIVE_SWORD_SHIELD,
        "sword_shield_up_casual_battle" to NATIVE_SWORD_SHIELD, "sword_shield_up_performance_battle" to NATIVE_SWORD_SHIELD,
        "sword_shield_up_casual_performance_battle" to NATIVE_SWORD_SHIELD,
        "emerald_seaglass_battle" to NATIVE_EMERALD_SEAGLASS, "tmt2_battle" to NATIVE_TMT2, "heart_and_soul_battle" to NATIVE_HEART_AND_SOUL,
        "orange_islands_battle" to NATIVE_ORANGE_ISLANDS, "ultra_violet_battle" to NATIVE_ULTRA_VIOLET, "celia_battle" to NATIVE_CELIA,
        "rocket_edition_battle" to NATIVE_ROCKET_EDITION, "ash_gray_battle" to NATIVE_ASH_GRAY, "ash_gray_fork_battle" to NATIVE_ASH_GRAY,
        "ghost_grey_battle" to NATIVE_GHOST_GREY, "ghost_grey_v101_battle" to NATIVE_GHOST_GREY_V1_0_1,
        "recharged_yellow_v180_battle" to NATIVE_RECHARGED_YELLOW_V1_8_0, "recharged_yellow_v190_battle" to NATIVE_RECHARGED_YELLOW_V1_9_0,
        "recharged_yellow_v194_battle" to NATIVE_RECHARGED_YELLOW_V1_9_4, "recharged_yellow_battle" to NATIVE_RECHARGED_YELLOW_V1_9_7,
        "recharged_yellow_trainer" to NATIVE_RECHARGED_YELLOW_V1_9_7,
        "mariomon_v1_5_1_battle" to NATIVE_MARIOMON_V1_5_1, "mariomon_v1_5_2_battle" to NATIVE_MARIOMON_V1_5_2, "mariomon_v1_3_battle" to NATIVE_MARIOMON_V1_3,
        "mariomon_v1_2_2_battle" to NATIVE_MARIOMON_V1_2_2, "mariomon_v1_2_1_battle" to NATIVE_MARIOMON_V1_2_1,
        "quetzal_battle" to NATIVE_QUETZAL, "quetzal_es_battle" to NATIVE_QUETZAL_ES,
        "quetzal_v1_battle" to NATIVE_QUETZAL_V1, "quetzal_es_v1_battle" to NATIVE_QUETZAL_ES_V1, "imperium_battle" to NATIVE_IMPERIUM, "rowe_battle" to NATIVE_ROWE,
        "quetzal_084_battle" to NATIVE_QUETZAL_V084, "quetzal_es_084_battle" to NATIVE_QUETZAL_V084_ES,
        "quetzal_moemon_084_battle" to NATIVE_QUETZAL_MOEMON_V084, "quetzal_moemon_es_084_battle" to NATIVE_QUETZAL_MOEMON_V084_ES,
        "gs_chronicles_battle" to NATIVE_GS_CHRONICLES,
        "recharged_emerald_v225_battle" to NATIVE_RECHARGED_EMERALD_V2_2_5, "recharged_emerald_battle" to NATIVE_RECHARGED_EMERALD_V2_2_8,
        "recharged_emerald_rebalanced_battle" to NATIVE_RECHARGED_EMERALD_REBALANCED_V2_2_8,
        "elite_redux_battle" to NATIVE_ELITE_REDUX_V2_65_4_1B, "elite_redux_v2653_battle" to NATIVE_ELITE_REDUX_V2_65_3B,
        "elite_redux_trainer" to NATIVE_ELITE_REDUX_V2_65_4_1B, "elite_redux_v2653_trainer" to NATIVE_ELITE_REDUX_V2_65_3B,
        "team_rocket_battle" to NATIVE_TEAM_ROCKET, "team_rocket_trainer" to NATIVE_TEAM_ROCKET,
        "monomyth_battle" to NATIVE_MONOMYTH, "monomyth_trainer" to NATIVE_MONOMYTH,
        "monomyth_v1_1_battle" to NATIVE_MONOMYTH_V1_1, "monomyth_v1_1_trainer" to NATIVE_MONOMYTH_V1_1,
    )

    // Battles whose foe isn't read (the tracker counts no encounter there): Unbound's capture is a scripted battle
    // against a Lv0 foe; Celia's battlers don't decode (its BATTLE tab shows the same).
    private val noFoe = setOf("unbound_battle", "celia_battle")

    @Test
    fun everyCaptureGivesTheTrackerWhatItReads() {
        for ((name, cfg) in fixtures) {
            val t = readNativeTelemetry(FixtureMemoryReader.load(name), cfg)
            assertTrue(name, t.inBattle)
            // Emerald Rogue's capture is a trainer's.
            assertEquals(name, name.endsWith("_trainer") || name == "emerald_rogue_battle", t.isTrainerBattle)
            assertTrue(name, t.party.isNotEmpty() && t.party.all { it.personality != 0L })
            val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
            if (name !in noFoe) assertTrue("$name: ${foe.species} Lv${foe.level}", foe.species != 0 && foe.level in 1..100)
        }
    }

    @Test
    fun aFoeTheGameCantNameIsNoFoe() {
        activeGame = GameKind.FIRERED
        fun foe(species: Int, level: Int) = MonView(species, "", level, 10, 10, "", emptyList(), null)
        val v = SnapshotView(connected = true, inBattle = true, isTrainerBattle = false)
        assertEquals(25, NuzlockeSample.of(v.copy(battleOpponent = listOf(foe(25, 5)))).foeSpecies)
        assertEquals(0, NuzlockeSample.of(v.copy(battleOpponent = listOf(foe(61676, 3)))).foeSpecies)
        assertEquals(0, NuzlockeSample.of(v.copy(battleOpponent = listOf(foe(25, 0)))).foeSpecies)
    }

    @Test
    fun theGamesItFollows() {
        val n = com.pokedaisy.app.companion.Nuzlocke
        assertTrue(n.supports(GameKind.FIRERED) && n.supports(GameKind.EMERALD) && n.supports(GameKind.UNBOUND))
        // The Game Boy and DS games too, now their readers tell wild from trainer.
        assertTrue(n.supports(GameKind.YELLOW) && n.supports(GameKind.CRYSTAL) && n.supports(GameKind.POLISHED_CRYSTAL))
        assertTrue(n.supports(GameKind.PLATINUM) && n.supports(GameKind.HEARTGOLD) && n.supports(GameKind.BLACK2))
        // A roguelike walks the same areas every run.
        assertFalse(n.supports(GameKind.EMERALD_ROGUE) || n.supports(null))
    }

    /**
     * The Game Boy and DS battle captures: each tells a wild battle from a trainer's (Gen 1's wIsInBattle, Gen 2's
     * wBattleMode, Gen 4's battle type, Gen 5's competitor), and gives a foe and a party with ids.
     */
    @Test
    fun gameBoyAndDsCapturesGiveTheTrackerWhatItReads() {
        romLanguage = 'E'
        romGameCode = ""
        data class Capture(val fixture: String, val game: GameKind, val trainer: Boolean, val read: (MemoryReader) -> Telemetry)
        fun gen1(f: String, g: GameKind, c: Gen1Config) = Capture(f, g, false) { readGen1Telemetry(it, c) }
        fun gen2(f: String, g: GameKind, c: Gen2Config, trainer: Boolean = false) =
            Capture(f, g, trainer) { activeGen2 = c; readGen2Telemetry(it, c) }
        val captures = listOf(
            gen1("yellow_battle", GameKind.YELLOW, GEN1_YELLOW), gen1("red_battle", GameKind.RED, GEN1_RED),
            gen1("blue_battle", GameKind.BLUE, GEN1_BLUE), gen1("purered_battle", GameKind.PURE_RED, GEN1_PURE_RED),
            gen1("pureblue_battle", GameKind.PURE_BLUE, GEN1_PURE_BLUE), gen1("yellow_legacy_battle", GameKind.YELLOW_LEGACY, GEN1_YELLOW_LEGACY),
            gen2("crystal_battle", GameKind.CRYSTAL, GEN2_CRYSTAL), gen2("gold_battle", GameKind.GOLD, GEN2_GOLD),
            gen2("gold_trainer", GameKind.GOLD, GEN2_GOLD, trainer = true), gen2("silver_battle", GameKind.SILVER, GEN2_SILVER),
            gen2("crystal_clear_battle", GameKind.CRYSTAL_CLEAR, GEN2_CRYSTAL_CLEAR_V2_6_0),
            gen2("crystal_clear_trainer", GameKind.CRYSTAL_CLEAR, GEN2_CRYSTAL_CLEAR_V2_6_0, trainer = true),
            gen2("crystal_legacy_battle", GameKind.CRYSTAL_LEGACY, GEN2_CRYSTAL_LEGACY),
            gen2("prism_battle", GameKind.PRISM, GEN2_PRISM), gen2("prism_trainer", GameKind.PRISM, GEN2_PRISM, trainer = true),
            gen2("polished_crystal_battle", GameKind.POLISHED_CRYSTAL, GEN2_POLISHED_CRYSTAL),
            gen2("polished_crystal_trainer", GameKind.POLISHED_CRYSTAL, GEN2_POLISHED_CRYSTAL, trainer = true),
            Capture("platinum_battle", GameKind.PLATINUM, true) { readGen4Telemetry(it, GEN4_PLATINUM_US) },
            Capture("black_battle", GameKind.BLACK, true) { readGen5Telemetry(it, GEN5_BLACK) },
            Capture("black_wild", GameKind.BLACK, false) { readGen5Telemetry(it, GEN5_BLACK) },
            Capture("white_wild", GameKind.WHITE, false) { readGen5Telemetry(it, GEN5_WHITE) },
            Capture("black2_wild", GameKind.BLACK2, false) { readGen5Telemetry(it, GEN5_BLACK2) },
        )
        for (c in captures) {
            activeGame = c.game
            val t = c.read(FixtureMemoryReader.load(c.fixture))
            assertTrue(c.fixture, t.inBattle)
            assertEquals(c.fixture, c.trainer, t.isTrainerBattle)
            // The player's Black save (black*, white*) has three Pokémon whose personality really is 0 (LATIOS, SUICUNE,
            // TERRAKION: as a save editor makes them) - the tracker can't tell such ones apart and leaves them out.
            assertTrue(c.fixture, t.party.isNotEmpty() && t.party.any { it.personality != 0L })
            if (!c.fixture.startsWith("black_") && !c.fixture.startsWith("white_")) assertTrue(c.fixture, t.party.all { it.personality != 0L })
            val foe = t.battleMons[BATTLE_POS_OPPONENT_LEFT]
            assertTrue("${c.fixture}: ${foe.species} Lv${foe.level}", foe.species != 0 && foe.level in 1..100)
        }
        activeGen2 = GEN2_CRYSTAL
    }

    /** A real wild battle of each generation, through the companion's snapshot into the tracker: the area's first encounter. */
    @Test
    fun aWildBattleIsTheAreasEncounter() {
        romLanguage = 'E'
        romGameCode = ""
        val wild = listOf<Triple<String, GameKind, (MemoryReader) -> Telemetry>>(
            Triple("yellow_battle", GameKind.YELLOW) { readGen1Telemetry(it, GEN1_YELLOW) },
            Triple("crystal_battle", GameKind.CRYSTAL) { activeGen2 = GEN2_CRYSTAL; readGen2Telemetry(it, GEN2_CRYSTAL) },
            Triple("polished_crystal_battle", GameKind.POLISHED_CRYSTAL) { activeGen2 = GEN2_POLISHED_CRYSTAL; readGen2Telemetry(it, GEN2_POLISHED_CRYSTAL) },
            Triple("black2_wild", GameKind.BLACK2) { readGen5Telemetry(it, GEN5_BLACK2) },
        )
        for ((fixture, game, read) in wild) {
            activeGame = game
            val v = buildSnapshotView(read(FixtureMemoryReader.load(fixture))).copy(game = game)
            val sample = NuzlockeSample.of(v)
            assertTrue(fixture, sample.foeSpecies != 0 && sample.trainer == false && sample.areaName.isNotBlank())
            val tracker = NuzlockeTracker()
            val events = (0..NuzlockeTracker.FOE_SAMPLES + 1).flatMap { tracker.feed(sample) }
            assertTrue("$fixture: $events", events.any { it is NuzlockeEvent.FirstEncounter })
            val e = tracker.run.encounters.single()
            assertEquals(fixture, sample.foeSpecies, e.species)
            assertEquals(fixture, NuzlockeOutcome.NOW, e.outcome)
        }
        activeGen2 = GEN2_CRYSTAL
    }

    /** A Game Boy Pokémon keeps its run id when it evolves (OT ID + DVs, not the species), and Gen 1's maps count by place. */
    @Test
    fun gameBoyIdsSurviveEvolutionAndGen1MapsGroup() {
        val bulbasaur = gbMonKey(1, 0x1234, 0xABCD)
        val ivysaur = gbMonKey(2, 0x1234, 0xABCD)
        assertTrue(bulbasaur != ivysaur)
        assertEquals(NuzlockeSample.runId(bulbasaur, GameKind.YELLOW), NuzlockeSample.runId(ivysaur, GameKind.YELLOW))
        assertEquals(bulbasaur, NuzlockeSample.runId(bulbasaur, GameKind.FIRERED))   // a real personality stays as it is
        // Every Gen 1 map with a town-map name counts as the first map of that name: one area per place.
        val byName = mapSecDataYellow.entries.groupBy({ it.value.name }, { it.key })
        val multi = byName.values.first { it.size > 1 }
        assertEquals(1, multi.map { NuzlockeSample.areaOf(it, GameKind.YELLOW) }.toSet().size)
        assertEquals(multi[1], NuzlockeSample.areaOf(multi[1], GameKind.CRYSTAL))     // Gen 2's are places already
    }
}
