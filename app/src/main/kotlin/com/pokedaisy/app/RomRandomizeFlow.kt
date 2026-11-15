package com.pokedaisy.app

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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

    /** The window shows while non-null. */
    var step by mutableStateOf<Step?>(null)
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
                step = if (sorted.isEmpty()) Step.NotFound else Step.Ready(sorted)
            }
        }, "pokedaisy-randomize-look").apply { isDaemon = true; start() }
    }

    /** The options as [b]'s game takes them ([takesFieldItems], [takesHeldItems]). */
    fun optionsFor(b: Base) = options.copy(fieldItems = options.fieldItems && takesFieldItems(b), trainerItems = options.trainerItems && takesHeldItems(b))

    /** RANDOMIZE. */
    fun make() {
        val ready = step as? Step.Ready ?: return
        val b = ready.bases.getOrNull(base) ?: return
        val opts = optionsFor(b)
        val s = seed
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

        /** FIELD ITEMS: the GBA games so far. */
        fun takesFieldItems(b: Base) = !b.ds && !b.gb

        /** HELD ITEMS: not Red / Blue / Yellow, whose trainers hold nothing. */
        fun takesHeldItems(b: Base) = !(b.gb && b.game in setOf("Red", "Blue", "Yellow"))

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

/** [flow]'s window: which game, the options and the seed, then RANDOMIZE. */
@Composable
fun RandomizeWindow(flow: RomRandomizeFlow, m: GbaTextMetrics, small: GbaTextMetrics, onPlay: ((File) -> Unit)?) {
    val step = flow.step ?: return
    var choosing by remember { mutableStateOf<String?>(null) }
    var platform by remember { mutableStateOf<RomRandomizeFlow.Platform?>(null) }
    OptionOverlay(onDismiss = { flow.dismiss() }, modifier = Modifier.widthIn(max = 900.dp)) {
        Column {
            OptionTitleWindow(tr("RANDOMIZE"), m, onBack = { flow.dismiss() }.takeIf { step !is RomRandomizeFlow.Step.Making })
            Spacer(Modifier.height(m.u * 4))
            OptionListWindow(m, Modifier.fillMaxWidth()) {
                Column(Modifier.padding(vertical = m.u * 2)) {
                    Column(Modifier.weight(1f, fill = false).verticalScroll(rememberScrollState())) {
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
                            is RomRandomizeFlow.Step.Ready -> ReadyRows(flow, step, m, small) { choosing = it }
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
                            is RomRandomizeFlow.Step.Ready -> {
                                OptionButton(tr("CANCEL"), m, modifier = Modifier.weight(1f), onClick = { flow.dismiss() })
                                OptionButton(tr("RANDOMIZE"), m, emphasis = true, enabled = (step as RomRandomizeFlow.Step.Ready).let { r -> flow.optionsFor(r.bases.getOrElse(flow.base) { r.bases[0] }).changesSomething }, modifier = Modifier.weight(1f), onClick = { flow.make() })
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
    val ready = step as? RomRandomizeFlow.Step.Ready
    when (choosing.takeIf { ready != null }) {
        // Two steps: the platform, then its games (BACK from the games goes back to the platforms). One platform: its games.
        "GAME" -> {
            val platforms = ready!!.bases.map { it.platform }.distinct().sorted()
            val current = ready.bases.getOrElse(flow.base) { ready.bases[0] }.platform
            if (platforms.size == 1) {
                LaunchedEffect(Unit) { platform = platforms[0]; choosing = "GAME_LIST" }
            } else {
                OptionSelector(
                    tr("GAME"), platforms, platform ?: current, { tr(it.label) }, m,
                    onPick = { platform = it; choosing = "GAME_LIST" }, onDismiss = { choosing = null; platform = null },
                )
            }
        }
        "GAME_LIST" -> {
            val p = platform ?: ready!!.bases[0].platform
            val games = ready!!.bases.indices.filter { ready.bases[it].platform == p }
            val many = ready.bases.map { it.platform }.distinct().size > 1
            OptionSelector(
                tr(p.label), games, flow.base.takeIf { it in games } ?: games[0], { ready.bases[it].label }, m,
                onPick = { flow.base = it; choosing = null; platform = null },
                onDismiss = { if (many) choosing = "GAME" else { choosing = null; platform = null } },
            )
        }
        "STARTERS" -> OptionSelector(
            tr("STARTERS"), RomRandomizer.Starters.entries, flow.options.starters, { tr(RomRandomizeFlow.startersLabel(it)) }, m,
            onPick = { flow.options = flow.options.copy(starters = it); choosing = null }, onDismiss = { choosing = null },
        )
        "WILD" -> OptionSelector(
            tr("WILD POKéMON"), RomRandomizer.Wild.entries, flow.options.wild, { tr(RomRandomizeFlow.wildLabel(it)) }, m,
            onPick = { flow.options = flow.options.copy(wild = it); choosing = null }, onDismiss = { choosing = null },
        )
    }
}

@Composable
private fun ReadyRows(
    flow: RomRandomizeFlow, step: RomRandomizeFlow.Step.Ready, m: GbaTextMetrics, small: GbaTextMetrics, choose: (String) -> Unit,
) {
    val o = flow.options
    fun onOff(b: Boolean) = tr(if (b) "ON" else "OFF")
    val base = step.bases.getOrElse(flow.base) { step.bases[0] }
    OptionLine(tr("GAME"), base.label, false, m, divider = true, enabled = step.bases.size > 1) { choose("GAME") }
    OptionLine(tr("STARTERS"), tr(RomRandomizeFlow.startersLabel(o.starters)), false, m, divider = true) { choose("STARTERS") }
    OptionLine(tr("WILD POKéMON"), tr(RomRandomizeFlow.wildLabel(o.wild)), false, m, divider = true) { choose("WILD") }
    OptionLine(tr("TRAINERS"), onOff(o.trainers), false, m, divider = true) {
        flow.options = o.copy(trainers = !o.trainers)
    }
    OptionLine(tr("SIMILAR STRENGTH"), onOff(o.similarStrength), false, m, divider = true) {
        flow.options = o.copy(similarStrength = !o.similarStrength)
    }
    OptionLine(tr("LEGENDARIES"), tr(if (o.noLegendaries) "NONE" else "ALLOWED"), false, m, divider = true) {
        flow.options = o.copy(noLegendaries = !o.noLegendaries)
    }
    fun shuffled(b: Boolean) = tr(if (b) "SHUFFLED" else "AS THEY ARE")
    // Only where the picked game takes them (RomRandomizeFlow.takesFieldItems / takesHeldItems).
    if (RomRandomizeFlow.takesFieldItems(base)) {
        OptionLine(tr("FIELD ITEMS"), shuffled(o.fieldItems), false, m, divider = true) { flow.options = o.copy(fieldItems = !o.fieldItems) }
    }
    if (RomRandomizeFlow.takesHeldItems(base)) {
        OptionLine(tr("HELD ITEMS"), onOff(o.trainerItems), false, m, divider = true) { flow.options = o.copy(trainerItems = !o.trainerItems) }
    }
    OptionLine(tr("BASE STATS"), shuffled(o.baseStats), false, m, divider = true) { flow.options = o.copy(baseStats = !o.baseStats) }
    OptionLine(tr("LEARNSETS"), tr(if (o.learnsets) "RANDOM" else "AS THEY ARE"), false, m, divider = true) {
        flow.options = o.copy(learnsets = !o.learnsets)
    }
    OptionLine(tr("SEED"), flow.seed.toString(), false, m) { flow.newSeed() }
    Spacer(Modifier.height(m.u * 4))
    Padded(m) { Message(tr("Tap SEED for another: the same seed always makes the same game. Your ROM stays as it is, the randomized game is saved as a new file."), small) }
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
