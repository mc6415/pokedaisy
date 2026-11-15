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
    class Base(val file: File, val label: String, val game: String)

    sealed class Step {
        class Looking(val progress: Float) : Step()
        /** No retail FireRed rev 1 / Emerald in the library. */
        data object NotFound : Step()
        class Ready(val bases: List<Base>) : Step()
        class Making(val base: Base) : Step()
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
            val found = ArrayList<Base>()
            files.forEachIndexed { i, f ->
                val bytes = runCatching { RomArchive.open(f).use { it.readBytes() } }.getOrNull()
                // Only the player's own carts: copies already randomized (by this tool or another) are left out.
                val game = bytes?.let { b -> runCatching { RomRandomizer.gameOf(b)?.takeIf { RomRandomizer.sha1(b) == it.retailSha1 } }.getOrNull() }
                if (game != null) {
                    found += Base(f, GameTitles.label(activity, prefs, f), game.title)
                }
                postFor(token) { if (step is Step.Looking) step = Step.Looking((i + 1f) / files.size) }
            }
            postFor(token) {
                base = 0
                step = if (found.isEmpty()) Step.NotFound else Step.Ready(found)
            }
        }, "pokedaisy-randomize-look").apply { isDaemon = true; start() }
    }

    /** RANDOMIZE. */
    fun make() {
        val ready = step as? Step.Ready ?: return
        val b = ready.bases.getOrNull(base) ?: return
        val opts = options
        val s = seed
        step = Step.Making(b)
        val token = run
        Thread({
            val result = runCatching {
                val bytes = RomArchive.open(b.file).use { it.readBytes() }
                val out = RomRandomizer.randomize(bytes, opts, s) ?: error("not randomizable")
                val dir = RomPatcher.outputDir(RomPatcher.Platform.GBA, b.file, prefs.romsFolders, importedDir)
                write(out, dir, outputName(b, s))
            }
            val file = result.getOrNull()
            if (file == null) {
                postFor(token) { step = Step.Failed(tr("The randomized ROM couldn't be saved. Check there's free space and try again.")) }
                return@Thread
            }
            RomAdded.record(activity, listOf(file), initial = false)
            if (file.absoluteFile.parentFile == importedDir.absoluteFile) RomFolder.checkImported(activity) else RomFolder.scan(activity, prefs)
            GameTitles.identify(activity, listOf(file))
            val label = GameTitles.label(activity, prefs, file)
            postFor(token) {
                step = Step.Done(file, label)
                onAdded(file)
            }
        }, "pokedaisy-randomize").apply { isDaemon = true; start() }
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
            out = File(dir, "$stem ($n).gba")
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
        /** "Pokemon FireRed Randomized (seed 123456).gba". */
        fun outputName(b: Base, seed: Long) = RomUris.sanitizeFileName("Pokemon ${b.game} Randomized (seed $seed).gba")

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
                                Message(tr("RANDOMIZE works on the English FireRed and LeafGreen (Rev 1), Ruby and Sapphire (Rev 2) and Emerald. Add one to your library or a ROMs folder, then try again."), small)
                            }
                            is RomRandomizeFlow.Step.Ready -> ReadyRows(flow, step, m, small) { choosing = it }
                            is RomRandomizeFlow.Step.Making -> Padded(m) {
                                Message(tr("Randomizing {0}…", step.base.label), m)
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
                                OptionButton(tr("RANDOMIZE"), m, emphasis = true, enabled = flow.options.changesSomething, modifier = Modifier.weight(1f), onClick = { flow.make() })
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
        "GAME" -> OptionSelector(
            tr("GAME"), ready!!.bases.indices.toList(), flow.base, { ready.bases[it].label }, m,
            onPick = { flow.base = it; choosing = null }, onDismiss = { choosing = null },
        )
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
