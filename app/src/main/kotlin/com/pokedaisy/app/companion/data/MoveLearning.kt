package com.pokedaisy.app.companion.data

import java.util.concurrent.ConcurrentHashMap

/** A move a party Pokémon could learn: at [level] (a level-up), or from the bag's [tm] ("TM35", "HM03"). */
data class LearnOption(val move: Int, val level: Int, val tm: String, val advice: MoveAdvice)

/** Whether a TM / HM suits a party Pokémon, as the game's own party screen says it (ABLE / NOT ABLE / LEARNED). */
enum class TmFit { ABLE, NOT_ABLE, LEARNED }

/** A party member ([index]) for a TM: whether it can learn it and, if so, the [advice]. */
data class TmLearner(val index: Int, val fit: TmFit, val advice: MoveAdvice?)

/**
 * The move advice's data: a party Pokémon's upcoming level-up moves and the TMs / HMs in the bag it
 * can learn ([options], the summary's CAN LEARN page), and who a TM suits ([learners], the ITEMS
 * tab). Reads the ROM (learnsets, gTMHMMoves, the TM compatibility bits), so - like [DexDetails] -
 * it runs on Compose's IO dispatcher and keeps what it worked out for the UI to start from.
 * The advice is for the original games only ([movesAreVanilla]: their TM items are 289..346, their moves' effects known);
 * the DS games ([Gen4DexTables], their learnsets and machines from the cart) get the list alone ([advises] false).
 */
object MoveLearning {
    /** Upcoming level-up moves listed, at most. */
    private const val UPCOMING = 4

    private val optionsCache = ConcurrentHashMap<List<Any?>, List<LearnOption>>()
    private val learnersCache = ConcurrentHashMap<List<Any?>, List<TmLearner>>()

    /** Whether the running game gets the CAN LEARN list at all (no ROM read). */
    fun available(t: PokedexTables?, g: GuideTables?): Boolean =
        t != null && (t.gen4 != null || movesAreVanilla && (hasLearnsets(t, g) || DexDetails.hasTeachable(t)))

    /** Whether its options carry a verdict: the original games' moves ([moveStats]), or a retail DS cart's from its own
     * move records ([DsMoveStats]; a hack's are its own). */
    val advises: Boolean get() = movesAreVanilla || DsMoveStats.known()

    /** No verdict (a DS game whose move stats didn't load): nothing drawn. */
    private fun noAdvice(move: Int) = MoveAdvice(move, Verdict.UNSURE, 0, emptyList(), 0f)

    // The DS games' machine items: TM01-TM92 328-419, then the HMs from 420; Black / White and their sequels' TM93-95 at
    // 618-620 (their records list TM01..TM95, then HM01..HM06).
    private const val DS_TM01 = 328
    private const val DS_HM01 = 420
    private const val DS_TM93 = 618

    /** The DS machine item [itemId]'s place in [t]'s list (TMs, then HMs); null = not one. */
    private fun dsMachine(t: Gen4DexTables, itemId: Int): Int? = when {
        itemId in DS_TM01 until DS_HM01 -> itemId - DS_TM01
        t.gen5 && itemId in DS_TM93 until DS_TM93 + (t.tmCount - 92) -> 92 + itemId - DS_TM93
        itemId in DS_HM01 until DS_HM01 + (t.moves.size - t.tmCount) -> t.tmCount + itemId - DS_HM01
        else -> null
    }

    /** The item id of [t]'s machine number [i] (the reverse of [dsMachine]). */
    private fun dsMachineItem(t: Gen4DexTables, i: Int): Int = when {
        i < 92 -> DS_TM01 + i
        i < t.tmCount -> DS_TM93 + i - 92
        else -> DS_HM01 + i - t.tmCount
    }

    /** A DS bag's TMs / HMs ("TM06", "HM01": the item's own name, as the cart spells it) it can learn, in machine order. */
    private fun dsMachines(t: PokedexTables, g: GuideTables?, mon: MonView, bag: List<ItemView>, known: Collection<Int>): List<LearnOption> {
        val held = bag.map { it.name.uppercase().replace(" ", "") }.filter { MACHINE.matches(it) }.toSet()
        return DexDetails.teachable(t, g, mon.species).orEmpty()
            .filter { it.label in held && it.move !in known }
            .map { LearnOption(it.move, 0, it.label, noAdvice(it.move)) }
    }
    private val MACHINE = Regex("^(TM|HM)\\d{2}$")

    fun isTm(itemId: Int): Boolean = if (activeGame.isDsGame) DsMoveStats.known() &&
        (itemId in DS_TM01 until DS_HM01 + 8 || activeGame.isGen5 && itemId in DS_TM93 until DS_TM93 + 3)
        else movesAreVanilla && itemId in VANILLA_ITEM_TM01..VANILLA_ITEM_HM08

    /** "TM06" / "HM01" for a TM item. */
    fun tmLabel(itemId: Int): String {
        if (activeGame.isDsGame) return when {
            itemId >= DS_TM93 -> "TM%02d".format(93 + itemId - DS_TM93)
            itemId >= DS_HM01 -> "HM%02d".format(1 + itemId - DS_HM01)
            else -> "TM%02d".format(1 + itemId - DS_TM01)
        }
        val i = itemId - VANILLA_ITEM_TM01
        return if (i < 50) "TM${(i + 1).pad(2)}" else "HM${(i - 49).pad(2)}"
    }

    /** The move TM / HM item [itemId] teaches; null = not a TM or the table isn't read. */
    fun tmMove(t: PokedexTables, itemId: Int): Int? = if (!isTm(itemId)) null
        else t.gen4?.let { d -> dsMachine(d, itemId)?.let { d.moves.getOrNull(it) }?.takeIf { it > 0 } }
            ?: DexDetails.tmhmMoves(t)?.getOrNull(itemId - VANILLA_ITEM_TM01)?.takeIf { it > 0 && t.gen4 == null }

    /** Every TM / HM item's move name ("TOXIC" for TM06), for the bag's rows; empty where the TMs aren't read. */
    fun tmMoveNames(t: PokedexTables): Map<Int, String> {
        t.gen4?.let { d ->
            if (!DsMoveStats.known()) return emptyMap()
            return d.moves.withIndex().filter { it.value > 0 }.associate { (i, move) -> dsMachineItem(d, i) to lookupMove(move).name }
        }
        if (!movesAreVanilla) return emptyMap()
        val moves = DexDetails.tmhmMoves(t) ?: return emptyMap()
        return moves.withIndex().filter { it.value > 0 }.associate { (i, move) -> VANILLA_ITEM_TM01 + i to lookupMove(move).name }
    }

    fun adviceMon(mon: MonView): AdviceMon = AdviceMon(
        types = mon.types.map(::typeIdOf).filter { it != TYPE_NONE },
        attack = mon.stats?.stats?.getOrNull(STAT_ATK),
        spAttack = mon.stats?.stats?.getOrNull(STAT_SPATK),
        level = mon.level,
        moves = mon.moves.map { it.id }.filter { it > 0 },
    )

    private fun hmMoves(t: PokedexTables): Set<Int> = t.gen4?.let { it.moves.drop(it.tmCount).toSet() }
        ?: DexDetails.tmhmMoves(t)?.drop(50)?.toSet() ?: VANILLA_HM_MOVES

    private fun optionsKey(t: PokedexTables, g: GuideTables?, mon: MonView, bag: List<ItemView>): List<Any?> =
        listOf(t, g, activeGame, mon.species, mon.level, mon.moves.map { it.id }, mon.stats?.stats, bag.map { it.itemId }.filter(::isTm).sorted(), if (advises) null else bag.map { it.name }.sorted())

    fun cachedOptions(t: PokedexTables, g: GuideTables?, mon: MonView, bag: List<ItemView>): List<LearnOption>? =
        optionsCache[optionsKey(t, g, mon, bag)]

    /**
     * [mon]'s next few level-up moves it doesn't know (its own level's too: that's the move the game is asking about while
     * it wants another forgotten), then the TMs / HMs in [bag] it can learn, each
     * with its [MoveAdvice] - the bag's best first. Empty for an egg or where nothing applies.
     */
    fun options(t: PokedexTables, g: GuideTables?, mon: MonView, bag: List<ItemView>): List<LearnOption> {
        val key = optionsKey(t, g, mon, bag)
        optionsCache[key]?.let { return it }
        if (mon.isEgg || mon.species <= 0 || !available(t, g)) return emptyList()
        // A DS cart's move stats from its extracted records (IO, here): without them, the list alone.
        if (!movesAreVanilla && !DsMoveStats.load()) {
            val known = mon.moves.map { it.id }.filter { it > 0 }
            val levelUps = upcoming(DexDetails.levelUp(t, g, mon.species).orEmpty(), mon.level, known)
                .map { LearnOption(it.move, it.level, "", noAdvice(it.move)) }
            return (levelUps + dsMachines(t, g, mon, bag, known)).also { if (optionsCache.size > 64) optionsCache.clear(); optionsCache[key] = it }
        }
        val am = adviceMon(mon)
        val hms = hmMoves(t)
        fun advise(move: Int) = MoveAdvisor.advise(am, move, hms)
        val levelUps = upcoming(DexDetails.levelUp(t, g, mon.species).orEmpty(), mon.level, am.moves)
            .map { LearnOption(it.move, it.level, "", advise(it.move)) }
        val teach = DexDetails.teachable(t, g, mon.species).orEmpty().map { it.move }.toSet()
        val tms = bag.map { it.itemId }.filter(::isTm).distinct().sorted().mapNotNull { item ->
            val move = tmMove(t, item) ?: return@mapNotNull null
            if (move !in teach || move in am.moves) null else LearnOption(move, 0, tmLabel(item), advise(move))
        }.sortedWith(compareBy<LearnOption> { it.advice.verdict == Verdict.SKIP }.thenByDescending { it.advice.gain })
        return (levelUps + tms).also { if (optionsCache.size > 64) optionsCache.clear(); optionsCache[key] = it }
    }

    /** The level-up moves still to come at [level] (its own too), [known] left out. */
    internal fun upcoming(moves: List<LevelMove>, level: Int, known: Collection<Int>): List<LevelMove> =
        moves.filter { it.level >= level && it.move !in known }.distinctBy { it.move }.take(UPCOMING)

    private fun learnersKey(t: PokedexTables, g: GuideTables?, party: List<MonView>, itemId: Int): List<Any?> =
        listOf(t, g, activeGame, itemId, party.map { listOf(it.species, it.level, it.isEgg, it.moves.map { m -> m.id }, it.stats?.stats) })

    fun cachedLearners(t: PokedexTables, g: GuideTables?, party: List<MonView>, itemId: Int): List<TmLearner>? =
        learnersCache[learnersKey(t, g, party, itemId)]

    /** Who in [party] can learn TM / HM [itemId], and the advice for each who can; null = not a TM here. */
    fun learners(t: PokedexTables, g: GuideTables?, party: List<MonView>, itemId: Int): List<TmLearner>? {
        val key = learnersKey(t, g, party, itemId)
        learnersCache[key]?.let { return it }
        if (t.gen4 != null && !DsMoveStats.load()) return null
        val move = tmMove(t, itemId) ?: return null
        val hms = hmMoves(t)
        return party.mapIndexed { i, mon ->
            val am = adviceMon(mon)
            when {
                mon.isEgg -> TmLearner(i, TmFit.NOT_ABLE, null)
                move in am.moves -> TmLearner(i, TmFit.LEARNED, null)
                DexDetails.teachable(t, g, mon.species).orEmpty().none { it.move == move } -> TmLearner(i, TmFit.NOT_ABLE, null)
                else -> TmLearner(i, TmFit.ABLE, MoveAdvisor.advise(am, move, hms))
            }
        }.also { if (learnersCache.size > 64) learnersCache.clear(); learnersCache[key] = it }
    }

    /** The party member a TM suits best: the biggest gain among those it's worth teaching. */
    fun best(learners: List<TmLearner>): Int? = learners
        .filter { it.advice != null && it.advice.verdict != Verdict.SKIP && it.advice.verdict != Verdict.UNSURE }
        .maxByOrNull { it.advice!!.gain }?.index
}
