package com.pokedaisy.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.pokedaisy.app.companion.data.RomRandomizer
import com.pokedaisy.app.companion.i18n.tk
import com.pokedaisy.app.companion.i18n.tr
import com.pokedaisy.app.companion.ui.GbaText
import com.pokedaisy.app.companion.ui.GbaTextMetrics
import com.pokedaisy.app.companion.ui.OptionButton
import com.pokedaisy.app.companion.ui.OptionColors
import com.pokedaisy.app.companion.ui.OptionLine
import com.pokedaisy.app.companion.ui.OptionListWindow
import com.pokedaisy.app.companion.ui.OptionOverlay
import com.pokedaisy.app.companion.ui.OptionSelector
import com.pokedaisy.app.companion.ui.OptionTitleWindow
import java.io.File
import okio.Path.Companion.toOkioPath

/**
 * RANDOMIZE, the library's TOOLS: the player's retail FireRed (rev 1) / Emerald looked for among every ROM the app knows,
 * the options and a seed, then a new randomized ROM ([RomRandomizer]) written beside their other GBA games
 * ([RomPatcher.outputDir]); the base ROM is never touched. The work runs off the UI thread; [step] is what
 * [RandomizeWindow] shows.
 */
class RomRandomizeFlow(
    private val activity: androidx.activity.ComponentActivity,
    /** A randomized ROM was written: rescan / refresh the library. */
    private val onAdded: (File) -> Unit,
) {
    /** A ROM the tool can take: [label] is the library's name for it. */
    class Base(val file: File, val label: String, val game: String, val ds: Boolean = false, val gb: Boolean = false) {
        val platform get() = when { ds -> Platform.DS; gb -> Platform.GB; else -> Platform.GBA }
    }

    /** GAME's first pick (the games of one, its second), newest first like [ORDER]. */
    enum class Platform(val label: String) { DS(tk("NINTENDO DS")), GBA(tk("GBA")), GB(tk("GAME BOY")) }

    sealed class Step {
        class Looking(val progress: Float) : Step()
        /** None of the carts the tool takes in the library. */
        data object NotFound : Step()
        class Ready(val bases: List<Base>) : Step()
        class Making(val base: Base, val progress: Float = -1f) : Step()
        class Done(val file: File, val label: String) : Step()
        class Failed(val message: String) : Step()
    }

    /** The wizard's pages, in order: which game (its console first when there are several), a [Style], then READY; CUSTOM
     * puts its four short pages of settings between STYLE and READY. */
    enum class Page { GAME, GAME_LIST, STYLE, POKEMON, WILD, RULES, TRAINERS, ITEMS, EXTRAS, READY }

    /** STYLE's choices: a ready-made set of [RomRandomizer.Options], the last run's, or CUSTOM's pages. */
    enum class Style(val label: String, val blurb: String) {
        CLASSIC(tk("CLASSIC"), tk("New starters, wild Pokémon by area and shuffled trainers.")),
        WILD_ONLY(tk("WILD ONLY"), tk("New starters and wild Pokémon. Trainers keep their teams.")),
        CHAOS(tk("CHAOS"), tk("Everything shuffled: encounters, items, stats, abilities, moves and evolutions.")),
        LAST_TIME(tk("LAST TIME"), tk("The settings you used last time.")),
        CUSTOM(tk("CUSTOM"), tk("Pick each setting yourself.")),
    }

    /** The window shows while non-null. */
    var step by mutableStateOf<Step?>(null)
    var page by mutableStateOf(Page.GAME)
    /** GAME_LIST's console. */
    var listPlatform by mutableStateOf<Platform?>(null)
    /** The STYLE picked (null until one is). */
    var style by mutableStateOf<Style?>(null)
    private val history = ArrayList<Page>()
    var base by mutableStateOf(0)
    var options by mutableStateOf(RomRandomizer.Options())
    var seed by mutableStateOf(kotlin.random.Random.nextLong(100_000, 1_000_000))

    private var run = 0
    private val prefs by lazy { Prefs(activity) }
    private val importedDir by lazy { File(activity.getExternalFilesDir(null), "roms") }

    /** TOOLS > RANDOMIZE. */
    fun start() {
        val token = ++run
        step = Step.Looking(0f)
        Thread({
            val files = RomFolder.allRomFiles(activity, prefs).filter { f ->
                runCatching { RomArchive.romSize(f) }.getOrDefault(-1L) in 0x800000L..0x1000000L &&
                    RomArchive.readAt(f, 0xAC, 0x11)?.let { h ->
                        val code = String(h, 0, 4, Charsets.US_ASCII)
                        val rev = h[0x10].toInt() and 0xFF
                        RomRandomizer.GAMES.any { it.code == code && it.rev == rev }
                    } == true
            }
            // DS carts by their header, then (a plain .nds) their files; a zipped one is checked once it's unpacked (make).
            val carts = RomFolder.allRomFiles(activity, prefs).filter { f ->
                val nds = if (RomArchive.isArchive(f)) RomArchive.romEntry(f)?.extension == "nds" else f.extension.equals("nds", ignoreCase = true)
                nds && RomArchive.readAt(f, 0xC, 0x13)?.let { h ->
                    val code = String(h, 0, 4, Charsets.US_ASCII)
                    val rev = h[0x12].toInt() and 0xFF
                    com.pokedaisy.app.companion.data.DsRandomizer.GAMES.any { it.code == code && it.rev == rev }
                } == true
            }
            // Game Boy carts (2 MB at most, zipped ones too): read whole.
            val gbCarts = RomFolder.allRomFiles(activity, prefs).filter { f ->
                runCatching { RomIdentity.isGameBoy(f) && RomArchive.romSize(f) <= 0x200000 }.getOrDefault(false)
            }
            val total = (files.size + carts.size + gbCarts.size).coerceAtLeast(1)
            val found = ArrayList<Base>()
            carts.forEachIndexed { i, f ->
                val game = if (RomArchive.isArchive(f)) {
                    // Unpacking 512 MB to look would be slow: by the header here, the files when it's made.
                    RomArchive.readAt(f, 0xC, 0x13)?.let { h ->
                        val code = String(h, 0, 4, Charsets.US_ASCII)
                        com.pokedaisy.app.companion.data.DsRandomizer.GAMES.firstOrNull { it.code == code && it.rev == (h[0x12].toInt() and 0xFF) }
                    }?.takeIf { !GameTitles.isRandomized(activity, f) }
                } else {
                    com.pokedaisy.app.companion.data.DsRandomizer.gameOf(f.toOkioPath(), retail = true)
                }
                if (game != null) found += Base(f, GameTitles.label(activity, prefs, f), game.title, ds = true)
                postFor(token) { if (step is Step.Looking) step = Step.Looking((i + 1f) / total) }
            }
            gbCarts.forEachIndexed { i, f ->
                val bytes = runCatching { RomArchive.open(f).use { it.readBytes() } }.getOrNull()
                val game = bytes?.let { b ->
                    com.pokedaisy.app.companion.data.GbRandomizer.GAMES.firstOrNull { com.pokedaisy.app.companion.data.GbRandomizer.sha1(b) == it.retailSha1 }
                }
                if (game != null) found += Base(f, GameTitles.label(activity, prefs, f), game.title, gb = true)
                postFor(token) { if (step is Step.Looking) step = Step.Looking((carts.size + files.size + i + 1f) / total) }
            }
            files.forEachIndexed { i, f ->
                val bytes = runCatching { RomArchive.open(f).use { it.readBytes() } }.getOrNull()
                // Only the player's own carts: copies already randomized (by this tool or another) are left out.
                val game = bytes?.let { b -> runCatching { RomRandomizer.gameOf(b)?.takeIf { RomRandomizer.sha1(b) == it.retailSha1 } }.getOrNull() }
                if (game != null) {
                    found += Base(f, GameTitles.label(activity, prefs, f), game.title)
                }
                postFor(token) { if (step is Step.Looking) step = Step.Looking((carts.size + i + 1f) / total) }
            }
            // Each pair of versions together, newest first; copies of one game by their library names.
            val sorted = found.sortedWith(compareBy<Base>({ ORDER.indexOf(it.game).let { i -> if (i < 0) ORDER.size else i } }, { it.label }))
            postFor(token) {
                base = 0
                style = null
                history.clear()
                step = if (sorted.isEmpty()) Step.NotFound else Step.Ready(sorted)
                page = firstPage(sorted)
            }
        }, "pokedaisy-randomize-look").apply { isDaemon = true; start() }
    }

    /** The wizard's first page: GAME, its one console's games, or STYLE when there's one game. */
    fun firstPage(bases: List<Base>): Page {
        val platforms = bases.map { it.platform }.distinct()
        listPlatform = platforms.singleOrNull()
        return when {
            bases.size == 1 -> Page.STYLE
            platforms.size == 1 -> Page.GAME_LIST
            else -> Page.GAME
        }
    }

    /** On to [p]; back to it when it's already behind (READY after a change made from it). */
    fun forward(p: Page) {
        val at = history.lastIndexOf(p)
        if (at >= 0) {
            while (history.size > at) history.removeAt(history.size - 1)
        } else history += page
        page = p
    }

    /** BACK: the page before, or close on the first. */
    fun back() {
        if (step !is Step.Ready || history.isEmpty()) { dismiss(); return }
        page = history.removeAt(history.size - 1)
    }

    /** GAME_LIST's pick: STYLE, or READY when a style is already picked (the game changed from READY). */
    fun pickGame(index: Int) {
        base = index
        forward(if (style != null && Page.READY in history) Page.READY else Page.STYLE)
    }

    /** STYLE's pick: its options and READY, or CUSTOM's pages. */
    fun pickStyle(s: Style) {
        style = s
        when (s) {
            Style.CUSTOM -> forward(Page.POKEMON)
            Style.LAST_TIME -> { options = decode(prefs.randomizeLast) ?: RomRandomizer.Options(); forward(Page.READY) }
            else -> { options = optionsOf(s); forward(Page.READY) }
        }
    }

    /** The styles STYLE offers: LAST TIME once a game has been made. */
    fun styles(): List<Style> = Style.entries.filter { it != Style.LAST_TIME || decode(prefs.randomizeLast) != null }

    /** The options as [b]'s game takes them ([takesFieldItems], [takesHeldItems]). */
    fun optionsFor(b: Base) = options.copy(
        fieldItems = options.fieldItems && takesFieldItems(b), trainerItems = options.trainerItems && takesHeldItems(b),
        monotype = options.monotype?.takeIf { it in typesFor(b) },
    )

    /** RANDOMIZE. */
    fun make() {
        val ready = step as? Step.Ready ?: return
        val b = ready.bases.getOrNull(base) ?: return
        val opts = optionsFor(b)
        val s = seed
        prefs.randomizeLast = encode(options)
        step = Step.Making(b)
        val token = run
        Thread({
            val result = runCatching {
                if (b.ds) return@runCatching makeDs(b, opts, s, token)
                if (b.gb) {
                    val bytes = RomArchive.open(b.file).use { it.readBytes() }
                    val out = com.pokedaisy.app.companion.data.GbRandomizer.randomize(bytes, opts, s) ?: error("not randomizable")
                    val platform = RomPatcher.platformOf(out)
                    val ext = RomPatcher.extensionFor(b.file, platform)
                    val dir = RomPatcher.outputDir(platform, b.file, prefs.romsFolders, importedDir)
                    return@runCatching write(out, dir, RomUris.sanitizeFileName("Pokemon ${b.game} Randomized (seed $s).$ext"))
                }
                val bytes = RomArchive.open(b.file).use { it.readBytes() }
                val out = RomRandomizer.randomize(bytes, opts, s) ?: error("not randomizable")
                val dir = RomPatcher.outputDir(RomPatcher.Platform.GBA, b.file, prefs.romsFolders, importedDir)
                write(out, dir, outputName(b, s))
            }
            val file = result.getOrNull()
            if (file == null) {
                val message = if (result.exceptionOrNull() is NotTheCart) tr("This copy of the game has been changed, so RANDOMIZE can't use it. It needs the original cart's file.")
                else tr("The randomized ROM couldn't be saved. Check there's free space and try again.")
                postFor(token) { step = Step.Failed(message) }
                return@Thread
            }
            RomAdded.record(activity, listOf(file), initial = false)
            prefs.madeRoms = prefs.madeRoms + file.absolutePath
            if (file.absoluteFile.parentFile == importedDir.absoluteFile) RomFolder.checkImported(activity) else RomFolder.scan(activity, prefs)
            GameTitles.identify(activity, listOf(file))
            val label = GameTitles.label(activity, prefs, file)
            postFor(token) {
                step = Step.Done(file, label)
                onAdded(file)
            }
        }, "pokedaisy-randomize").apply { isDaemon = true; start() }
    }

    /** The picked cart (a zipped one, checked only once unpacked) turned out not to be the original. */
    private class NotTheCart : Exception()

    /** A DS cart: copied, then its shuffled files written in place ([com.pokedaisy.app.companion.data.DsRandomizer]). */
    private fun makeDs(b: Base, opts: RomRandomizer.Options, s: Long, token: Int): File {
        val dir = RomPatcher.outputDir(RomPatcher.Platform.NDS, b.file, prefs.romsFolders, importedDir)
        dir.mkdirs()
        val name = RomUris.sanitizeFileName("Pokemon ${b.game} Randomized (seed $s).nds")
        var out = File(dir, name)
        var n = 2
        while (out.exists()) { out = File(dir, "${name.substringBeforeLast('.')} ($n).nds"); n++ }
        val tmp = File(dir, ".${out.name}.part")
        tmp.delete()
        var last = -1
        try {
            val ok = if (RomArchive.isArchive(b.file)) {
                // Unpacked straight into the new file, then shuffled where it is.
                RomArchive.extract(b.file, tmp) ?: error("couldn't unpack ${b.file.name}")
                postFor(token) { if (step is Step.Making) step = Step.Making(b, 0.9f) }
                com.pokedaisy.app.companion.data.DsRandomizer.randomizeInPlace(tmp.toOkioPath(), opts, s)
            } else {
                com.pokedaisy.app.companion.data.DsRandomizer.randomize(b.file.toOkioPath(), tmp.toOkioPath(), opts, s) { p ->
                    val pct = (p * 100).toInt()
                    if (pct != last) { last = pct; postFor(token) { if (step is Step.Making) step = Step.Making(b, p) } }
                }
            }
            if (!ok) throw NotTheCart()
            if (!tmp.renameTo(out)) throw java.io.IOException("couldn't name ${out.name}")
        } finally {
            tmp.delete()
        }
        return out
    }

    fun newSeed(): Long = kotlin.random.Random.nextLong(100_000, 1_000_000).also { seed = it }

    /** CLOSE (never while it's writing). */
    fun dismiss() {
        if (step is Step.Making) return
        step = null
        run++
    }

    private fun postFor(token: Int, block: () -> Unit) = activity.runOnUiThread { if (token == run) block() }

    private fun write(bytes: ByteArray, dir: File, name: String): File {
        dir.mkdirs()
        val stem = name.substringBeforeLast('.')
        var out = File(dir, name)
        var n = 2
        while (out.exists()) {
            if (out.length() == bytes.size.toLong() && out.readBytes().contentEquals(bytes)) return out
            out = File(dir, "$stem ($n).${name.substringAfterLast('.')}")
            n++
        }
        val tmp = File(dir, ".${out.name}.part")
        try {
            tmp.writeBytes(bytes)
            if (!tmp.renameTo(out)) throw java.io.IOException("couldn't name ${out.name}")
        } finally {
            tmp.delete()
        }
        return out
    }

    companion object {
        /** The GAME list's order (by [Base.game]): versions in pairs, the newest games first. */
        private val ORDER = listOf(
            "Black 2", "White 2", "Black", "White", "HeartGold", "SoulSilver", "Platinum",
            "Emerald", "FireRed", "LeafGreen", "Ruby", "Sapphire", "Crystal", "Gold", "Silver", "Yellow", "Red", "Blue",
        )

        /** A file named the way this tool names its output ("... Randomized (seed 123456).gba", " (2)" and all). */
        private val MADE_NAME = Regex("""^Pokemon .+ Randomized \(seed \d+\)( \(\d+\))?\.(gba|nds|gb|gbc)$""")

        /** [rom] is a game RANDOMIZE made: written by it ([Prefs.madeRoms]), or named as it names them and read as randomized. */
        fun madeByTool(prefs: Prefs, rom: File, randomized: Boolean): Boolean =
            rom.absolutePath in prefs.madeRoms || randomized && namedByTool(rom.name)

        internal fun namedByTool(name: String) = MADE_NAME.matches(name)

        /** "Pokemon FireRed Randomized (seed 123456).gba". */
        fun outputName(b: Base, seed: Long) = RomUris.sanitizeFileName("Pokemon ${b.game} Randomized (seed $seed).gba")

        /** FIELD ITEMS: every game (the DS games' item balls; the others' hidden items too). */
        @Suppress("UNUSED_PARAMETER")
        fun takesFieldItems(b: Base) = true

        /** ONE TYPE's choices for [b]'s game: every type but Red / Blue / Yellow's missing DARK and STEEL. */
        fun typesFor(b: Base): List<com.pokedaisy.app.companion.data.PokeType> = com.pokedaisy.app.companion.data.PokeType.entries.filter {
            !(b.gb && b.game in setOf("Red", "Blue", "Yellow")) || it.gen1 != null
        }

        /** LEVELS' choices, in percent. */
        val LEVEL_STEPS = listOf(0, 10, 25, 50)

        fun levelsLabel(p: Int) = if (p == 0) tr("AS THEY ARE") else "+$p%"

        fun typeName(t: com.pokedaisy.app.companion.data.PokeType) = com.pokedaisy.app.companion.data.typeLabel(t.name)

        /** HELD ITEMS: not Red / Blue / Yellow, whose trainers hold nothing. */
        fun takesHeldItems(b: Base) = !(b.gb && b.game in setOf("Red", "Blue", "Yellow"))

        /** A ready-made [Style]'s options (LAST TIME and CUSTOM aren't). */
        fun optionsOf(s: Style): RomRandomizer.Options = when (s) {
            Style.WILD_ONLY -> RomRandomizer.Options(trainers = false, gifts = true)
            Style.CHAOS -> RomRandomizer.Options(
                starters = RomRandomizer.Starters.RANDOM, wild = RomRandomizer.Wild.RANDOM, trainers = true,
                similarStrength = false, noLegendaries = false, fieldItems = true, trainerItems = true, baseStats = true, learnsets = true,
                gifts = true, evolutions = true, abilities = true,
            )
            // CLASSIC: the bosses keep their types and the rival their starter, so the story's battles still feel like it.
            else -> RomRandomizer.Options(gifts = true, bossTypes = RomRandomizer.BossTypes.OWN, rivalStarter = true)
        }

        /** [o] as [Prefs.randomizeLast] keeps it: its settings in order, `,`-separated. */
        fun encode(o: RomRandomizer.Options): String = listOf(
            o.starters.name, o.wild.name, o.trainers, o.similarStrength, o.noLegendaries, o.fieldItems, o.trainerItems, o.baseStats, o.learnsets,
            o.levelScale, o.noDuplicates, o.allCatchable, o.trainersKeepType, o.monotype?.name ?: "", o.gifts, o.evolutions, o.abilities,
            o.bossTypes.name, o.rivalStarter, o.wildLevelScale, o.habitats,
        ).joinToString(",")

        fun decode(s: String?): RomRandomizer.Options? = runCatching {
            val f = s!!.split(',')
            RomRandomizer.Options(
                RomRandomizer.Starters.valueOf(f[0]), RomRandomizer.Wild.valueOf(f[1]), f[2].toBooleanStrict(),
                similarStrength = f[3].toBooleanStrict(), noLegendaries = f[4].toBooleanStrict(), fieldItems = f[5].toBooleanStrict(),
                trainerItems = f[6].toBooleanStrict(), baseStats = f[7].toBooleanStrict(), learnsets = f[8].toBooleanStrict(),
                // The run modes (a setup saved before them has nine fields: they're off).
                levelScale = f.getOrNull(9)?.toInt() ?: 0, noDuplicates = f.getOrNull(10)?.toBooleanStrict() ?: false,
                allCatchable = f.getOrNull(11)?.toBooleanStrict() ?: false, trainersKeepType = f.getOrNull(12)?.toBooleanStrict() ?: false,
                monotype = f.getOrNull(13)?.takeIf { it.isNotEmpty() }?.let { com.pokedaisy.app.companion.data.PokeType.valueOf(it) },
                gifts = f.getOrNull(14)?.toBooleanStrict() ?: false,
                // EVOLUTIONS and ABILITIES (off in a setup saved before them).
                evolutions = f.getOrNull(15)?.toBooleanStrict() ?: false, abilities = f.getOrNull(16)?.toBooleanStrict() ?: false,
                // GYM TYPES, RIVAL, WILD LEVELS and HABITATS (off in a setup saved before them).
                bossTypes = f.getOrNull(17)?.let { RomRandomizer.BossTypes.valueOf(it) } ?: RomRandomizer.BossTypes.OFF,
                rivalStarter = f.getOrNull(18)?.toBooleanStrict() ?: false,
                wildLevelScale = f.getOrNull(19)?.toInt() ?: 0, habitats = f.getOrNull(20)?.toBooleanStrict() ?: false,
            )
        }.getOrNull()

        /** READY's plain words: one line per thing [o] changes in [b]'s game (English keys, translated here). */
        fun summary(o: RomRandomizer.Options, b: Base): List<String> = buildList {
            when (o.starters) {
                RomRandomizer.Starters.THREE_STAGE -> add(tr("New starters that evolve twice"))
                RomRandomizer.Starters.RANDOM -> add(tr("Any Pokémon as a starter"))
                RomRandomizer.Starters.OFF -> Unit
            }
            when (o.wild) {
                RomRandomizer.Wild.PER_AREA -> add(tr("Wild Pokémon shuffled area by area"))
                RomRandomizer.Wild.RANDOM -> add(tr("A random wild Pokémon in every encounter"))
                RomRandomizer.Wild.GLOBAL -> add(tr("Each wild Pokémon swapped for another everywhere"))
                RomRandomizer.Wild.OFF -> Unit
            }
            if (o.gifts) add(tr("Gift and static Pokémon swapped"))
            if (o.trainers) add(tr("Trainers' teams shuffled"))
            val swaps = o.starters != RomRandomizer.Starters.OFF || o.wild != RomRandomizer.Wild.OFF || o.trainers || o.gifts
            o.monotype?.takeIf { swaps }?.let { add(tr("Only {0} Pokémon", typeName(it))) }
            if (o.trainers && o.trainersKeepType && o.monotype == null) add(tr("Trainers keep their types"))
            if (o.trainers && o.monotype == null) when (o.bossTypes) {
                RomRandomizer.BossTypes.OWN -> add(tr("Leaders keep their own types"))
                RomRandomizer.BossTypes.RANDOM -> add(tr("Leaders get a random type each"))
                RomRandomizer.BossTypes.OFF -> Unit
            }
            if (o.rivalStarter && o.starters != RomRandomizer.Starters.OFF && takesRival(b)) add(tr("Your rival carries a new starter"))
            if (o.levelScale > 0) add(tr("Trainers' levels up {0}%", o.levelScale))
            if (o.wildLevelScale > 0) add(tr("Wild levels up {0}%", o.wildLevelScale))
            if (o.habitats && o.monotype == null && (o.wild == RomRandomizer.Wild.RANDOM || o.wild == RomRandomizer.Wild.PER_AREA))
                add(tr("Pokémon fit where they live"))
            if (o.wild != RomRandomizer.Wild.OFF && o.wild != RomRandomizer.Wild.GLOBAL && o.noDuplicates) add(tr("No Pokémon twice in one area"))
            if (o.wild != RomRandomizer.Wild.OFF && o.allCatchable) add(tr("As many kinds as possible in the wild"))
            if (swaps && o.similarStrength) add(tr("Swaps keep to Pokémon of similar strength"))
            if (swaps && !o.noLegendaries) add(tr("Legendaries can turn up"))
            if (o.fieldItems && takesFieldItems(b)) add(tr("Items lying around shuffled"))
            if (o.trainerItems && takesHeldItems(b)) add(tr("Trainers hold random items"))
            if (o.baseStats) add(tr("Base stats shuffled"))
            if (o.abilities && takesAbilities(b)) add(tr("Random abilities"))
            if (o.learnsets) add(tr("Random moves learned by level"))
            if (o.evolutions) add(tr("Random evolutions"))
        }

        /** RIVAL: every game but Yellow, whose rival carries an Eevee rather than a starter. */
        fun takesRival(b: Base) = !(b.gb && b.game == "Yellow")

        fun bossTypesLabel(t: RomRandomizer.BossTypes) = when (t) {
            RomRandomizer.BossTypes.OFF -> tk("AS THEY ARE")
            RomRandomizer.BossTypes.OWN -> tk("OWN TYPE")
            RomRandomizer.BossTypes.RANDOM -> tk("RANDOM TYPE")
        }

        fun bossTypesInfo(t: RomRandomizer.BossTypes) = when (t) {
            RomRandomizer.BossTypes.OFF -> tk("Leaders' new teams can be any type.")
            RomRandomizer.BossTypes.OWN -> tk("Leaders, the Elite Four and the Champion keep their own types.")
            RomRandomizer.BossTypes.RANDOM -> tk("Leaders, the Elite Four and the Champion each get a random type.")
        }

        /** ABILITIES: the GBA and DS games (the Game Boy games' Pokémon have none). */
        fun takesAbilities(b: Base) = !b.gb

        /** What each choice does, a line under it (English keys: [OptionLine] translates subtitles). */
        fun startersInfo(s: RomRandomizer.Starters) = when (s) {
            RomRandomizer.Starters.THREE_STAGE -> tk("Three that evolve twice, like the game's own.")
            RomRandomizer.Starters.RANDOM -> tk("Any three Pokémon but legendaries.")
            RomRandomizer.Starters.OFF -> tk("The game's own three.")
        }

        fun wildInfo(w: RomRandomizer.Wild) = when (w) {
            RomRandomizer.Wild.PER_AREA -> tk("Each kind swapped for another, route by route.")
            RomRandomizer.Wild.RANDOM -> tk("Anything can turn up anywhere.")
            RomRandomizer.Wild.GLOBAL -> tk("Each kind becomes one other kind, all game.")
            RomRandomizer.Wild.OFF -> tk("The game's own wild Pokémon.")
        }

        fun startersLabel(s: RomRandomizer.Starters) = when (s) {
            RomRandomizer.Starters.OFF -> tk("AS THEY ARE")
            RomRandomizer.Starters.RANDOM -> tk("ANY POKéMON")
            RomRandomizer.Starters.THREE_STAGE -> tk("EVOLVE TWICE")
        }

        fun wildLabel(w: RomRandomizer.Wild) = when (w) {
            RomRandomizer.Wild.OFF -> tk("AS THEY ARE")
            RomRandomizer.Wild.RANDOM -> tk("EVERY ENCOUNTER")
            RomRandomizer.Wild.PER_AREA -> tk("PER AREA")
            RomRandomizer.Wild.GLOBAL -> tk("SWAPPED EVERYWHERE")
        }
    }
}

/**
 * [flow]'s window, a wizard: GAME (its console, then the game), STYLE (cards: CLASSIC, WILD ONLY, CHAOS, LAST TIME, CUSTOM -
 * CUSTOM's settings on three short pages), READY (what will change in plain words, the seed, RANDOMIZE). BACK, the title's
 * arrow and a tap outside step back a page; the first page's close the window.
 */
@Composable
fun RandomizeWindow(flow: RomRandomizeFlow, m: GbaTextMetrics, small: GbaTextMetrics, onPlay: ((File) -> Unit)?) {
    val step = flow.step ?: return
    var choosing by remember { mutableStateOf<String?>(null) }
    val ready = step as? RomRandomizeFlow.Step.Ready
    val page = flow.page.takeIf { ready != null }
    OptionOverlay(onDismiss = { flow.back() }, modifier = Modifier.widthIn(max = 900.dp)) {
        Column {
            OptionTitleWindow(tr("RANDOMIZE"), m, onBack = { flow.back() }.takeIf { step !is RomRandomizeFlow.Step.Making })
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = m.u * 2)) {
                    if (page == RomRandomizeFlow.Page.STYLE) {
                        // The cards scroll by themselves (the TOOLS page's): never inside another scroll.
                        Padded(m) { Message(tr("How random should it be?"), m, value = true) }
                        com.pokedaisy.app.companion.ui.ToolsPage(
                            flow.styles().map { s -> com.pokedaisy.app.companion.ui.LibraryTool("STYLE_${s.name}", s.label, s.blurb, null) { flow.pickStyle(s) } },
                            m, small, androidx.compose.foundation.layout.PaddingValues(horizontal = m.u * 6, vertical = m.u * 2),
                            Modifier.weight(1f, fill = false),
                        )
                    } else Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
                        when (step) {
                            is RomRandomizeFlow.Step.Looking -> Padded(m) {
                                Message(tr("Looking for your games… {0}%", (step.progress * 100).toInt()), m)
                                Spacer(Modifier.height(m.u * 3))
                                SyncProgressBar(step.progress, m)
                            }
                            RomRandomizeFlow.Step.NotFound -> Padded(m) {
                                Message(tr("NO GAME TO RANDOMIZE"), m, value = true)
                                Spacer(Modifier.height(m.u * 2))
                                Message(tr("RANDOMIZE works on the English Red, Blue, Yellow, Gold, Silver, Crystal (Rev 1), FireRed and LeafGreen (Rev 1), Ruby and Sapphire (Rev 2), Emerald, Platinum (Rev 1), HeartGold, SoulSilver, Black, White, Black 2 and White 2. Add one to your library or a ROMs folder, then try again."), small)
                            }
                            is RomRandomizeFlow.Step.Ready -> WizardPage(flow, step, m, small) { choosing = it }
                            is RomRandomizeFlow.Step.Making -> Padded(m) {
                                Message(tr("Randomizing {0}…", step.base.label), m)
                                if (step.progress >= 0f) {
                                    Spacer(Modifier.height(m.u * 3))
                                    SyncProgressBar(step.progress, m)
                                }
                            }
                            is RomRandomizeFlow.Step.Done -> Padded(m) {
                                Message(tr("RANDOMIZED!"), m, value = true)
                                Spacer(Modifier.height(m.u * 2))
                                Message(tr("{0} is saved in {1}.", step.file.name, step.file.parent ?: ""), small)
                                Spacer(Modifier.height(m.u * 2))
                                Message(tr("{0} is in your library.", step.label), small)
                            }
                            is RomRandomizeFlow.Step.Failed -> Padded(m) {
                                Message(tr("RANDOMIZE FAILED"), m, value = true)
                                Spacer(Modifier.height(m.u * 2))
                                Message(step.message, small)
                            }
                        }
                    }
                    Row(
                        Modifier.fillMaxWidth().padding(start = m.u * 6, end = m.u * 6, top = m.u * 6, bottom = m.u * 2),
                        horizontalArrangement = Arrangement.spacedBy(m.u * 4),
                    ) {
                        when (step) {
                            is RomRandomizeFlow.Step.Ready -> when (page) {
                                RomRandomizeFlow.Page.POKEMON, RomRandomizeFlow.Page.WILD, RomRandomizeFlow.Page.RULES, RomRandomizeFlow.Page.TRAINERS,
                                RomRandomizeFlow.Page.ITEMS, RomRandomizeFlow.Page.EXTRAS -> {
                                    OptionButton(tr("BACK"), m, modifier = Modifier.weight(1f), onClick = { flow.back() })
                                    OptionButton(tr("NEXT"), m, emphasis = true, modifier = Modifier.weight(1f), onClick = {
                                        flow.forward(when (page) {
                                            RomRandomizeFlow.Page.POKEMON -> RomRandomizeFlow.Page.WILD
                                            RomRandomizeFlow.Page.WILD -> RomRandomizeFlow.Page.RULES
                                            RomRandomizeFlow.Page.RULES -> RomRandomizeFlow.Page.TRAINERS
                                            RomRandomizeFlow.Page.TRAINERS -> RomRandomizeFlow.Page.ITEMS
                                            RomRandomizeFlow.Page.ITEMS -> RomRandomizeFlow.Page.EXTRAS
                                            else -> RomRandomizeFlow.Page.READY
                                        })
                                    })
                                }
                                RomRandomizeFlow.Page.READY -> {
                                    OptionButton(tr("BACK"), m, modifier = Modifier.weight(1f), onClick = { flow.back() })
                                    OptionButton(tr("RANDOMIZE"), m, emphasis = true, enabled = flow.optionsFor(step.bases.getOrElse(flow.base) { step.bases[0] }).changesSomething, modifier = Modifier.weight(1f), onClick = { flow.make() })
                                }
                                else -> OptionButton(tr("CANCEL"), m, modifier = Modifier.weight(1f), onClick = { flow.dismiss() })
                            }
                            is RomRandomizeFlow.Step.Making ->
                                OptionButton(tr("RANDOMIZE"), m, emphasis = true, enabled = false, modifier = Modifier.weight(1f), onClick = {})
                            is RomRandomizeFlow.Step.Done -> {
                                OptionButton(tr("CLOSE"), m, emphasis = onPlay == null, modifier = Modifier.weight(1f), onClick = { flow.dismiss() })
                                if (onPlay != null) {
                                    OptionButton(tr("PLAY"), m, emphasis = true, modifier = Modifier.weight(1f), onClick = { flow.dismiss(); onPlay(step.file) })
                                }
                            }
                            else -> OptionButton(tr("CLOSE"), m, modifier = Modifier.weight(1f), onClick = { flow.dismiss() })
                        }
                    }
                }
            }
        }
    }
    when (choosing.takeIf { ready != null }) {
        "TYPE" -> {
            val base = ready!!.bases.getOrElse(flow.base) { ready.bases[0] }
            OptionSelector(
                tr("ONE TYPE"), listOf<com.pokedaisy.app.companion.data.PokeType?>(null) + RomRandomizeFlow.typesFor(base), flow.options.monotype,
                { it?.let(RomRandomizeFlow::typeName) ?: tr("OFF") }, m,
                onPick = { flow.options = flow.options.copy(monotype = it); choosing = null }, onDismiss = { choosing = null },
            )
        }
        "LEVELS" -> OptionSelector(
            tr("LEVELS"), RomRandomizeFlow.LEVEL_STEPS, flow.options.levelScale, { RomRandomizeFlow.levelsLabel(it) }, m,
            onPick = { flow.options = flow.options.copy(levelScale = it); choosing = null }, onDismiss = { choosing = null },
        )
        "WILD LEVELS" -> OptionSelector(
            tr("WILD LEVELS"), RomRandomizeFlow.LEVEL_STEPS, flow.options.wildLevelScale, { RomRandomizeFlow.levelsLabel(it) }, m,
            onPick = { flow.options = flow.options.copy(wildLevelScale = it); choosing = null }, onDismiss = { choosing = null },
        )
        "GYM TYPES" -> OptionSelector(
            tr("GYM TYPES"), RomRandomizer.BossTypes.entries, flow.options.bossTypes, { tr(RomRandomizeFlow.bossTypesLabel(it)) }, m,
            onPick = { flow.options = flow.options.copy(bossTypes = it); choosing = null }, onDismiss = { choosing = null },
            description = { RomRandomizeFlow.bossTypesInfo(it) },
        )
        "STARTERS" -> OptionSelector(
            tr("STARTERS"), RomRandomizer.Starters.entries, flow.options.starters, { tr(RomRandomizeFlow.startersLabel(it)) }, m,
            onPick = { flow.options = flow.options.copy(starters = it); choosing = null }, onDismiss = { choosing = null },
            description = { RomRandomizeFlow.startersInfo(it) },
        )
        "WILD" -> OptionSelector(
            tr("WILD POKéMON"), RomRandomizer.Wild.entries, flow.options.wild, { tr(RomRandomizeFlow.wildLabel(it)) }, m,
            onPick = { flow.options = flow.options.copy(wild = it); choosing = null }, onDismiss = { choosing = null },
            description = { RomRandomizeFlow.wildInfo(it) },
        )
    }
}

/** The wizard's page ([RomRandomizeFlow.page]) but STYLE's cards: a heading, then its rows. */
@Composable
private fun WizardPage(
    flow: RomRandomizeFlow, step: RomRandomizeFlow.Step.Ready, m: GbaTextMetrics, small: GbaTextMetrics, choose: (String) -> Unit,
) {
    val o = flow.options
    fun onOff(b: Boolean) = tr(if (b) "ON" else "OFF")
    fun shuffled(b: Boolean) = tr(if (b) "SHUFFLED" else "AS THEY ARE")
    val base = step.bases.getOrElse(flow.base) { step.bases[0] }
    @Composable fun heading(text: String) { Padded(m) { Message(text, m, value = true) } }
    // A setting with what its value does in a grey line under it.
    val described = m.rowHeight + com.pokedaisy.app.companion.ui.subtitleMetrics(m).lineHeight + m.u * 2
    @Composable fun row(label: String, value: String, info: String, last: Boolean = false, onClick: () -> Unit) =
        OptionLine(label, value, false, m, height = described, divider = !last, subtitle = info, onClick = onClick)
    when (flow.page) {
        RomRandomizeFlow.Page.GAME -> {
            heading(tr("Which game?"))
            val platforms = step.bases.map { it.platform }.distinct().sorted()
            platforms.forEachIndexed { i, p ->
                OptionLine(tr(p.label), step.bases.count { it.platform == p }.toString(), false, m, divider = i < platforms.size - 1) {
                    flow.listPlatform = p
                    flow.forward(RomRandomizeFlow.Page.GAME_LIST)
                }
            }
        }
        RomRandomizeFlow.Page.GAME_LIST -> {
            val p = flow.listPlatform ?: step.bases[0].platform
            heading(if (step.bases.map { it.platform }.distinct().size > 1) tr(p.label) else tr("Which game?"))
            val games = step.bases.indices.filter { step.bases[it].platform == p }
            games.forEachIndexed { i, g ->
                OptionLine(step.bases[g].label, null, g == flow.base, m, divider = i < games.size - 1) { flow.pickGame(g) }
            }
        }
        RomRandomizeFlow.Page.STYLE -> Unit
        // CUSTOM's pages: each row says, in a grey line under it, what its value does.
        RomRandomizeFlow.Page.POKEMON -> {
            heading(tr("POKéMON"))
            row(tr("STARTERS"), tr(RomRandomizeFlow.startersLabel(o.starters)), RomRandomizeFlow.startersInfo(o.starters)) { choose("STARTERS") }
            // Only where the picked game's rival carries a starter (not Yellow's).
            if (RomRandomizeFlow.takesRival(base)) {
                row(tr("RIVAL"), onOff(o.rivalStarter),
                    if (o.rivalStarter) tk("Your rival carries one of the new starters and evolves it.") else tk("Your rival's starter isn't matched to the new ones.")) {
                    flow.options = o.copy(rivalStarter = !o.rivalStarter)
                }
            }
            row(tr("GIFTS"), shuffled(o.gifts),
                if (o.gifts) tk("Gifts, prizes and Pokémon waiting on the map swapped.") else tk("The game's own gifts and waiting Pokémon.")) {
                flow.options = o.copy(gifts = !o.gifts)
            }
            row(tr("SIMILAR STRENGTH"), onOff(o.similarStrength),
                if (o.similarStrength) tk("Swaps stay close in strength.") else tk("A swap can be much stronger or weaker."), last = true) {
                flow.options = o.copy(similarStrength = !o.similarStrength)
            }
        }
        RomRandomizeFlow.Page.WILD -> {
            heading(tr("WILD POKéMON"))
            row(tr("WILD POKéMON"), tr(RomRandomizeFlow.wildLabel(o.wild)), RomRandomizeFlow.wildInfo(o.wild)) { choose("WILD") }
            row(tr("WILD LEVELS"), RomRandomizeFlow.levelsLabel(o.wildLevelScale),
                if (o.wildLevelScale > 0) tr("Wild Pokémon {0}% higher.", o.wildLevelScale) else tk("The game's own levels.")) {
                choose("WILD LEVELS")
            }
            row(tr("HABITATS"), onOff(o.habitats),
                if (o.habitats) tk("Water types in the water, Rock and Ground types in caves.") else tk("Any Pokémon can turn up anywhere."), last = true) {
                flow.options = o.copy(habitats = !o.habitats)
            }
        }
        // The run modes.
        RomRandomizeFlow.Page.RULES -> {
            heading(tr("RULES"))
            row(tr("LEGENDARIES"), tr(if (o.noLegendaries) "NONE" else "ALLOWED"),
                if (o.noLegendaries) tk("Kept out of the grass and trainers' teams.") else tk("Can turn up in the grass and in teams.")) {
                flow.options = o.copy(noLegendaries = !o.noLegendaries)
            }
            row(tr("NO DUPLICATES"), onOff(o.noDuplicates),
                if (o.noDuplicates) tk("No Pokémon twice in one area.") else tk("An area can have a Pokémon twice.")) {
                flow.options = o.copy(noDuplicates = !o.noDuplicates)
            }
            row(tr("EVERY POKéMON"), onOff(o.allCatchable),
                if (o.allCatchable) tk("Swaps spread out so as many kinds as possible turn up.") else tk("Some Pokémon may never turn up.")) {
                flow.options = o.copy(allCatchable = !o.allCatchable)
            }
            val type = flow.optionsFor(base).monotype
            row(tr("ONE TYPE"), type?.let(RomRandomizeFlow::typeName) ?: tr("OFF"),
                if (type != null) tr("Only {0} Pokémon, everywhere.", RomRandomizeFlow.typeName(type)) else tk("Pokémon of every type."), last = true) {
                choose("TYPE")
            }
        }
        RomRandomizeFlow.Page.TRAINERS -> {
            heading(tr("TRAINERS"))
            row(tr("TEAMS"), shuffled(o.trainers),
                if (o.trainers) tk("Every trainer gets a new team at the same levels.") else tk("Trainers keep their own teams.")) {
                flow.options = o.copy(trainers = !o.trainers)
            }
            row(tr("KEEP TYPES"), onOff(o.trainersKeepType),
                if (o.trainersKeepType) tk("A new Pokémon shares a type with the one it replaces.") else tk("A new Pokémon can be any type.")) {
                flow.options = o.copy(trainersKeepType = !o.trainersKeepType)
            }
            row(tr("GYM TYPES"), tr(RomRandomizeFlow.bossTypesLabel(o.bossTypes)), RomRandomizeFlow.bossTypesInfo(o.bossTypes)) {
                choose("GYM TYPES")
            }
            row(tr("LEVELS"), RomRandomizeFlow.levelsLabel(o.levelScale),
                if (o.levelScale > 0) tr("Every trainer's Pokémon {0}% higher.", o.levelScale) else tk("The game's own levels."), last = true) {
                choose("LEVELS")
            }
        }
        RomRandomizeFlow.Page.ITEMS -> {
            heading(tr("ITEMS"))
            val held = RomRandomizeFlow.takesHeldItems(base)
            row(tr("FIELD ITEMS"), shuffled(o.fieldItems),
                if (o.fieldItems) tk("Items swap places. Key items stay put.") else tk("Items stay where the game puts them."), last = !held) {
                flow.options = o.copy(fieldItems = !o.fieldItems)
            }
            // Only where the picked game takes them: Red / Blue / Yellow's trainers hold nothing.
            if (held) {
                row(tr("HELD ITEMS"), onOff(o.trainerItems),
                    if (o.trainerItems) tk("Trainers' Pokémon hold items picked at random.") else tk("Held items as the game has them."), last = true) {
                    flow.options = o.copy(trainerItems = !o.trainerItems)
                }
            }
        }
        RomRandomizeFlow.Page.EXTRAS -> {
            heading(tr("EXTRAS"))
            row(tr("BASE STATS"), shuffled(o.baseStats),
                if (o.baseStats) tk("Each Pokémon's stats in a new order, the same total.") else tk("Stats as the game has them.")) {
                flow.options = o.copy(baseStats = !o.baseStats)
            }
            // Only where the picked game has them: the Game Boy games' Pokémon have no abilities.
            if (RomRandomizeFlow.takesAbilities(base)) {
                row(tr("ABILITIES"), tr(if (o.abilities) "RANDOM" else "AS THEY ARE"),
                    if (o.abilities) tk("Each Pokémon gets abilities picked at random.") else tk("Abilities as the game has them.")) {
                    flow.options = o.copy(abilities = !o.abilities)
                }
            }
            row(tr("LEARNSETS"), tr(if (o.learnsets) "RANDOM" else "AS THEY ARE"),
                if (o.learnsets) tk("New moves learned at the same levels.") else tk("Moves as the game has them.")) {
                flow.options = o.copy(learnsets = !o.learnsets)
            }
            row(tr("EVOLUTIONS"), tr(if (o.evolutions) "RANDOM" else "AS THEY ARE"),
                if (o.evolutions) tk("Each evolves into another Pokémon, the same way.") else tk("Evolutions as the game has them."), last = true) {
                flow.options = o.copy(evolutions = !o.evolutions)
            }
        }
        RomRandomizeFlow.Page.READY -> {
            OptionLine(tr("GAME"), base.label, false, m, divider = true, enabled = step.bases.size > 1) {
                flow.forward(flow.firstPage(step.bases).takeIf { it != RomRandomizeFlow.Page.STYLE } ?: RomRandomizeFlow.Page.GAME)
            }
            OptionLine(tr("STYLE"), flow.style?.let { tr(it.label) } ?: tr("CUSTOM"), false, m, divider = true) {
                flow.forward(RomRandomizeFlow.Page.STYLE)
            }
            OptionLine(tr("SEED"), flow.seed.toString(), false, m) { flow.newSeed() }
            Spacer(Modifier.height(m.u * 4))
            Padded(m) {
                val lines = RomRandomizeFlow.summary(flow.optionsFor(base), base)
                if (lines.isEmpty()) Message(tr("Nothing would change: pick a style that shuffles something."), small)
                @Composable fun bullets(part: List<String>, modifier: Modifier) = Column(modifier) {
                    part.forEach { line ->
                        Row {
                            Message("•", small)
                            Spacer(Modifier.width(m.u * 3))
                            Message(line, small)
                        }
                    }
                }
                // A long list (CHAOS's eight) in two columns, so the page doesn't scroll on a wide screen.
                androidx.compose.foundation.layout.BoxWithConstraints {
                    if (lines.size > 4 && maxWidth > 560.dp) Row {
                        val half = (lines.size + 1) / 2
                        bullets(lines.take(half), Modifier.weight(1f))
                        Spacer(Modifier.width(m.u * 6))
                        bullets(lines.drop(half), Modifier.weight(1f))
                    } else bullets(lines, Modifier)
                }
                Spacer(Modifier.height(m.u * 4))
                Message(tr("Tap SEED for another. Your ROM stays as it is: this makes a new file."), small)
            }
        }
    }
}

@Composable
private fun Padded(m: GbaTextMetrics, content: @Composable () -> Unit) {
    Column(Modifier.padding(horizontal = m.u * 6, vertical = m.u * 2)) { content() }
}

@Composable
private fun Message(text: String, m: GbaTextMetrics, value: Boolean = false) {
    if (value) GbaText(text, OptionColors.value, OptionColors.valueShadow, m, maxLines = Int.MAX_VALUE)
    else GbaText(text, OptionColors.label, OptionColors.labelShadow, m, maxLines = Int.MAX_VALUE)
}
