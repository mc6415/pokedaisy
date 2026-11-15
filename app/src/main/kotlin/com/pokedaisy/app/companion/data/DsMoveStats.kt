package com.pokedaisy.app.companion.data


/**
 * The DS games' move stats for the move advice ([MoveAdvisor]): each move's accuracy, PP and priority from the player's
 * own cart (NdsArt writes them when it extracts the cart: 4 bytes a move id), and its effect from the original games'
 * table where the move is one of theirs (moves 1-354 kept their ids and what they do). A Gen 4 / 5 move has no effect name
 * here: a hit is scored on its power alone, and a status move gets no verdict ([MoveAdvisor.unsure]).
 *
 * Retail carts only: Renegade Platinum, the Redux hacks and Origin HeartGold changed their moves.
 */
object DsMoveStats {
    const val PLATINUM = "nds/platinum/moves.bin"
    const val HEARTGOLD = "nds/heartgold/moves.bin"
    const val BLACK = "nds/black/moves.bin"
    const val BLACK2 = "nds/black2/moves.bin"

    /** The original games' moves: their effects stand for the DS's. */
    const val GEN3_MOVES = 354

    /** Never misses: Gen 5's records say 101 (Gen 4's 0, as [MoveStats] has it). */
    private const val GEN5_SURE_HIT = 101

    @Volatile private var loaded: Pair<String, ByteArray>? = null

    /** The running game's file, or null where there's no advice (not a DS game, a hack). */
    private fun path(): String? = when {
        renegadePlatinum || black2Redux || originHeartGold -> null
        activeGame == GameKind.PLATINUM -> PLATINUM
        activeGame.isHgss -> HEARTGOLD
        activeGame.isBw2 -> BLACK2
        activeGame.isGen5 -> BLACK
        else -> null
    }

    /** The running game's stats as loaded ([load]); never reads the disk (composition reads these). */
    private fun bytes(): ByteArray? {
        val p = path() ?: return null
        return loaded?.takeIf { it.first == p }?.second
    }

    /** Reads the running game's file if it isn't loaded yet: IO only (MoveLearning's work calls it first). */
    fun load(): Boolean {
        val p = path() ?: return false
        if (loaded?.first == p) return true
        val dir = NdsArt.dir ?: return false
        val b = runCatching { (dir / p).readBytes() }.getOrNull() ?: return false
        loaded = p to b
        return true
    }

    /** Whether the running game can have them (a retail DS cart: no disk read). */
    fun known(): Boolean = path() != null

    /** [id]'s stats in the running DS game; null where there are none. */
    fun stats(id: Int): MoveStats? {
        val b = bytes() ?: return null
        if (id <= 0 || id * 4 + 3 >= b.size) return null
        val acc = b[id * 4].toInt() and 0xFF
        val pp = b[id * 4 + 1].toInt() and 0xFF
        if (pp == 0) return null
        val gen3 = if (id <= GEN3_MOVES) vanillaMoveStats.getOrNull(id) else null
        return MoveStats(
            effect = gen3?.effect ?: "",
            accuracy = if (activeGame.isGen5 && acc >= GEN5_SURE_HIT) 0 else acc,
            pp = pp,
            effectChance = gen3?.effectChance ?: 0,
            priority = b[id * 4 + 2].toInt(),
        )
    }

    /** The DS games keep a category on each move ([moveKind]): physical unless it's one of the special ones. */
    fun physical(id: Int): Boolean = id !in dsSpecialMoves
}
