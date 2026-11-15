package com.pokedaisy.app.companion.data

import okio.ByteString.Companion.toByteString
import okio.FileHandle
import okio.FileSystem
import okio.Path
import kotlin.random.Random

/**
 * RANDOMIZE on the DS games (Platinum first): a copy of the player's own cart with its starters, wild Pokémon and
 * trainers' teams shuffled by a seed, like [RomRandomizer] on the GBA ones and with the same [RomRandomizer.Options].
 *
 * The data lives in the cart's file system: the starters are three words in overlay 78's literal pool (choose_starter_app.c's
 * STARTER_OPTION_*), the wild Pokémon a WildEncounters record per encounter set (`pl_enc_data.narc`), the trainers' teams
 * `trpoke.narc` (the party type from `trdata.narc`). Every swap keeps each file its size, so the cart is copied and those
 * bytes written in place: no file moves, the header and the file table stay the cart's.
 *
 * A cart this tool made is told by those three files ([Game.madeSha1]: their bytes with [sites] zeroed, hashed together),
 * which is cheap next to hashing a 128 MB cart; [readCart] gives the GUIDE the shuffled wild sets and teams.
 */
object DsRandomizer {
    class Game(
        val code: String,
        val rev: Int,
        val title: String,
        /** The three files ([starterOverlay], [encounters], [trainerParties]) as the retail cart has them, hashed together. */
        val retailFilesSha1: String,
        /** The same with [sites] zeroed: what every cart made from it hashes to. */
        val madeSha1: String,
        val starterOverlay: Int,
        /** The starters' words in [starterOverlay], in the order the game shows them (grass, fire, water). */
        val starterOffsets: List<Int>,
        val encounters: String,
        val trainers: String,
        val trainerParties: String,
        val personal: String,
        val evolutions: String,
        val learnsets: String,
        val speciesCount: Int,
        /** The starter screen's own copies of the three species, rewritten to ask the starters' lookup ([SpritePatch]). */
        val sprites: SpritePatch? = null,
        /** The starter screen's "<category> Pokémon <NAME>! Will you take this Pokémon?" lines ([StarterText]). */
        val text: StarterText? = null,
    )

    /**
     * Platinum's MakePokemonSprites builds each starter's species inline (sprite slot offset minus a constant: 0x204 - 0x81 =
     * TURTWIG), so there's nothing to swap: its three calls ([at], [len] bytes in [Game.starterOverlay]) become a loop over
     * GetSelectedSpecies ([getSpecies], the lookup [Game.starterOffsets] feed), and the screen shows what you'll get.
     */
    class SpritePatch(val at: Int, val len: Int, val getSpecies: Int, val makeSprite: Int)

    /** The message [bank] in [path] whose [lines] ask about each starter; species names are bank [names]. */
    class StarterText(val path: String, val bank: Int, val lines: List<Int>, val names: Int)

    val GAMES = listOf(
        Game(
            "CPUE", 1, "Platinum", "03abfbcd7c99152bcaaccffdeb729d72c0f382cc", "6aacfe6af2c159707c9b4399d023f292bf843d9e",
            starterOverlay = 78, starterOffsets = listOf(0x1BC0, 0x1BC4, 0x1BC8),
            encounters = "/fielddata/encountdata/pl_enc_data.narc",
            trainers = "/poketool/trainer/trdata.narc", trainerParties = "/poketool/trainer/trpoke.narc",
            personal = "/poketool/personal/pl_personal.narc", evolutions = "/poketool/personal/evo.narc",
            learnsets = "/poketool/personal/wotbl.narc", speciesCount = 493,
            // choose_starter_app.c (pret/pokeplatinum's xMAP): MakePokemonSprites' calls 0x021D1410-0x021D143A,
            // GetSelectedSpecies 0x021D291C, MakePokemonSprite 0x021D1468; text bank 360 (unk_0360), species names 412.
            sprites = SpritePatch(at = 0x690, len = 42, getSpecies = 0x021D291C, makeSprite = 0x021D1468),
            text = StarterText("/msgdata/pl_msg.narc", bank = 360, lines = listOf(1, 2, 3), names = 412),
        ),
    )

    /** National numbers of the legendaries and mythicals up to Gen 4. */
    private val LEGENDARY = setOf(144, 145, 146, 150, 151, 243, 244, 245, 249, 250, 251) + (377..386) + (480..493)

    // Platinum's struct WildEncounters (include/struct_defs/wild_encounters.h), 0x1A8 bytes: every species word.
    private val GRASS = (0 until 12).map { 8 + it * 8 }
    private val TIMED = listOf(100, 104, 108, 112, 116, 120) + (0 until 4).map { 124 + it * 4 } + (0 until 10).map { 164 + it * 4 }
    private fun water(base: Int) = (0 until 5).map { base + 8 + it * 8 }
    private val SURF = water(204)
    private val OLD_ROD = water(292)
    private val GOOD_ROD = water(336)
    private val SUPER_ROD = water(380)
    private val ENCOUNTER_SPECIES = GRASS + TIMED + SURF + OLD_ROD + GOOD_ROD + SUPER_ROD

    /** Bytes of one trainer Pokémon by trdata's party type (struct_defs/trainer_data.h); its moves' place, or -1. */
    private fun monStride(type: Int) = when (type) { 1 -> 16; 2 -> 10; 3 -> 18; else -> 8 }
    private fun movesAt(type: Int) = when (type) { 1 -> 6; 3 -> 8; else -> -1 }

    /** The cart's file system: its header, file table and names. */
    class Cart(private val h: FileHandle) {
        private val header = read(0, 0x200)
        val code = String(header, 0xC, 4, Charsets.US_ASCII)
        val rev = header[0x1E].toInt() and 0xFF
        private val fnt = read(u32(header, 0x40).toLong(), u32(header, 0x44))
        private val fatOff = u32(header, 0x48).toLong()

        fun read(at: Long, len: Int): ByteArray {
            val out = ByteArray(len)
            var done = 0
            while (done < len) {
                val n = h.read(at + done, out, done, len - done)
                if (n <= 0) error("short read")
                done += n
            }
            return out
        }

        /** File [id]'s place in the cart: start, end. */
        fun span(id: Int): LongRange {
            val e = read(fatOff + id * 8L, 8)
            return u32(e, 0).toLong() until u32(e, 4).toLong()
        }

        /** Where overlay [overlay] is loaded. */
        fun overlayRam(overlay: Int): Int {
            val table = read(u32(header, 0x50).toLong(), u32(header, 0x54))
            for (i in 0 until table.size / 32) if (u32(table, i * 32) == overlay) return u32(table, i * 32 + 4)
            error("no overlay $overlay")
        }

        fun overlayFile(overlay: Int): Int {
            val table = read(u32(header, 0x50).toLong(), u32(header, 0x54))
            for (i in 0 until table.size / 32) if (u32(table, i * 32) == overlay) {
                require(table[i * 32 + 31].toInt() and 1 == 0) { "overlay $overlay is compressed" }
                return u32(table, i * 32 + 24)
            }
            error("no overlay $overlay")
        }

        /** The file id at [path] ("/a/b/c.narc"). */
        fun fileId(path: String): Int {
            var dir = 0
            val parts = path.trim('/').split('/')
            for ((k, name) in parts.withIndex()) {
                var at = u32(fnt, dir * 8)
                var id = u16(fnt, dir * 8 + 4)
                var found = -1
                while (true) {
                    val len = fnt[at].toInt() and 0xFF
                    if (len == 0) break
                    val isDir = len and 0x80 != 0
                    val n = String(fnt, at + 1, len and 0x7F, Charsets.US_ASCII)
                    at += 1 + (len and 0x7F)
                    if (isDir) {
                        val sub = u16(fnt, at) and 0xFFF
                        at += 2
                        if (n == name && k < parts.size - 1) { found = sub; break }
                    } else {
                        if (n == name && k == parts.size - 1) return id
                        id++
                    }
                }
                if (found < 0) error("no $path")
                dir = found
            }
            error("no $path")
        }

        fun file(id: Int): ByteArray = span(id).let { read(it.first, (it.last + 1 - it.first).toInt()) }
    }

    /** A NARC's members: each one's place in the archive's bytes. */
    class Narc(val d: ByteArray) {
        private val count: Int
        private val fat: Int
        private val data: Int

        init {
            require(String(d, 0, 4, Charsets.US_ASCII) == "NARC")
            fat = 0x10
            require(String(d, fat, 4, Charsets.US_ASCII) == "BTAF")
            count = u16(d, fat + 8)
            val fnt = fat + u32(d, fat + 4)
            val gmif = fnt + u32(d, fnt + 4)
            data = gmif + 8
        }

        val size get() = count
        fun start(i: Int) = data + u32(d, fat + 12 + i * 8)
        fun end(i: Int) = data + u32(d, fat + 16 + i * 8)
        fun member(i: Int): ByteArray = d.copyOfRange(start(i), end(i))
    }

    /** The files the tool writes (the starters' overlay, the encounters, the trainers' parties, the starter screen's text),
     * read from [cart]. */
    private class Files(val cart: Cart, val g: Game) {
        val overlayId = cart.overlayFile(g.starterOverlay)
        val overlayRam = cart.overlayRam(g.starterOverlay)
        val encId = cart.fileId(g.encounters)
        val partiesId = cart.fileId(g.trainerParties)
        val msgId = g.text?.let { cart.fileId(it.path) }
        val overlay = cart.file(overlayId)
        val enc = cart.file(encId)
        val parties = cart.file(partiesId)
        val msg = msgId?.let { cart.file(it) }
        val trdata = Narc(cart.file(cart.fileId(g.trainers)))

        /** Each file written, at its place in the cart. */
        fun written(): List<Pair<Long, ByteArray>> = listOfNotNull(
            cart.span(overlayId).first to overlay, cart.span(encId).first to enc, cart.span(partiesId).first to parties,
            msgId?.let { cart.span(it).first to msg!! },
        )

        fun hash(zeroed: Boolean): String {
            val o = overlay.copyOf(); val e = enc.copyOf(); val p = parties.copyOf(); val m = msg?.copyOf() ?: ByteArray(0)
            if (zeroed) {
                for (at in g.starterOffsets) for (i in 0 until 4) o[at + i] = 0
                g.sprites?.let { sp -> for (i in 0 until sp.len) o[sp.at + i] = 0 }
                val en = Narc(e)
                for (k in 0 until en.size) for (s in ENCOUNTER_SPECIES) if (en.start(k) + s + 4 <= en.end(k)) for (i in 0 until 4) e[en.start(k) + s + i] = 0
                trainerMons(Narc(p)) { at, moves ->
                    p[at + 4] = 0; p[at + 5] = 0
                    if (moves >= 0) for (i in 0 until 8) p[moves + i] = 0
                }
                g.text?.let { t ->
                    val mn = Narc(m)
                    val bank = mn.start(t.bank)
                    val file = mn.member(t.bank)
                    for (line in t.lines) {
                        val (off, len) = Gen4Text.span(file, line)
                        for (i in 0 until len * 2) m[bank + off + i] = 0
                    }
                }
            }
            return (o + e + p + m).toByteString().sha1().hex()
        }

        /** Each trainer Pokémon in [narc] (trpoke): its record and its moves' place (-1 none), as absolute places in it. */
        fun trainerMons(narc: Narc, each: (at: Int, moves: Int) -> Unit) {
            for (t in 0 until minOf(trdata.size, narc.size)) {
                val head = trdata.member(t)
                if (head.size < 4) continue
                val type = head[0].toInt() and 0xFF
                val count = head[3].toInt() and 0xFF
                val stride = monStride(type)
                if (narc.end(t) - narc.start(t) < count * stride) continue
                for (i in 0 until count) {
                    val at = narc.start(t) + i * stride
                    each(at, movesAt(type).let { if (it < 0) -1 else at + it })
                }
            }
        }
    }

    private fun gameOfHeader(cart: Cart) = GAMES.firstOrNull { it.code == cart.code && it.rev == cart.rev }

    /** [path]'s game when the tool can take it: [retail] = the untouched cart only; else a cart it made counts too. */
    fun gameOf(path: Path, retail: Boolean = false): Game? = runCatching {
        FileSystem.SYSTEM.openReadOnly(path).use { h ->
            val cart = Cart(h)
            val g = gameOfHeader(cart) ?: return@use null
            val f = Files(cart, g)
            g.takeIf { f.hash(false) == it.retailFilesSha1 || !retail && f.hash(true) == it.madeSha1 }
        }
    }.getOrNull()

    /** [path] is a cart this tool made (not the untouched one): its game. */
    fun recognise(path: Path): Game? = runCatching {
        FileSystem.SYSTEM.openReadOnly(path).use { h ->
            val cart = Cart(h)
            val g = gameOfHeader(cart) ?: return@use null
            val f = Files(cart, g)
            g.takeIf { it.madeSha1.isNotEmpty() && f.hash(true) == it.madeSha1 && f.hash(false) != it.retailFilesSha1 }
        }
    }.getOrNull()

    /**
     * What a cart this tool made has instead of the original's, for the GUIDE (Platinum's [Gen4World]): each encounter set's
     * species words in struct order ([speciesAt], by byte offset) and each trainer's team.
     */
    class Shuffle(val game: Game, private val sets: List<ByteArray>, private val teams: Map<Int, List<TrainerMon>>) {
        /** Encounter set [set]'s species at byte [offset] of its record, or null when it has none there. */
        fun speciesAt(set: Int, offset: Int): Int? = sets.getOrNull(set)?.takeIf { offset + 4 <= it.size }?.let { u32(it, offset) }
        fun team(trainer: Int): List<TrainerMon>? = teams[trainer]
    }

    /** The cart [DsRandomizer.Shuffle]s the GUIDE reads: set as a DS game loads (NdsArt.prefetch), null for any other cart. */
    @Volatile var shuffle: Shuffle? = null

    /** [path]'s [Shuffle] when it's a cart this tool made, else null. Blocking (reads a few files). */
    fun readShuffle(path: Path): Shuffle? = runCatching {
        FileSystem.SYSTEM.openReadOnly(path).use { h ->
            val cart = Cart(h)
            val g = gameOfHeader(cart) ?: return@use null
            val f = Files(cart, g)
            if (f.hash(true) != g.madeSha1 || f.hash(false) == g.retailFilesSha1) return@use null
            val enc = Narc(f.enc)
            val teams = HashMap<Int, List<TrainerMon>>()
            val parties = Narc(f.parties)
            for (t in 0 until minOf(f.trdata.size, parties.size)) {
                val head = f.trdata.member(t)
                if (head.size < 4) continue
                val type = head[0].toInt() and 0xFF
                val stride = monStride(type)
                val m = parties.member(t)
                teams[t] = (0 until (head[3].toInt() and 0xFF)).mapNotNull { i ->
                    val at = i * stride
                    if (at + stride > m.size) return@mapNotNull null
                    val moves = movesAt(type).let { o -> if (o < 0) emptyList() else (0 until 4).map { u16(m, at + o + it * 2) }.filter { it != 0 } }
                    TrainerMon(u16(m, at + 4) and 0x3FF, u16(m, at + 2), if (type == 2 || type == 3) u16(m, at + 6) else 0, moves)
                }
            }
            Shuffle(g, (0 until enc.size).map { enc.member(it) }, teams)
        }
    }.getOrNull()

    /** Platinum's [Gen4World] places: a grass slot's, the day / night pair's, a water slot's species word in the record. */
    internal fun grassOffset(slot: Int) = GRASS[slot]
    internal fun dayOffset(i: Int) = TIMED[2 + i]
    internal fun nightOffset(i: Int) = TIMED[4 + i]
    internal fun surfOffset(slot: Int) = SURF[slot]
    internal fun rodOffset(rod: Int, slot: Int) = listOf(OLD_ROD, GOOD_ROD, SUPER_ROD)[rod][slot]

    /** The two hashes of [path]'s files, for filling in a new [Game]: retail, made. */
    internal fun hashes(path: Path): Pair<String, String>? = FileSystem.SYSTEM.openReadOnly(path).use { h ->
        val cart = Cart(h)
        val g = gameOfHeader(cart) ?: return@use null
        val f = Files(cart, g)
        f.hash(false) to f.hash(true)
    }

    /**
     * A randomized copy of [input] written to [output] (which mustn't exist). False when the tool can't take [input]; throws
     * on an IO error (the half-written [output] is the caller's to delete).
     */
    fun randomize(input: Path, output: Path, options: RomRandomizer.Options, seed: Long, onProgress: (Float) -> Unit = {}): Boolean {
        val shuffled = FileSystem.SYSTEM.openReadOnly(input).use { h ->
            val cart = Cart(h)
            val g = gameOfHeader(cart) ?: return false
            val f = Files(cart, g)
            if (f.hash(false) != g.retailFilesSha1 && f.hash(true) != g.madeSha1) return false
            val run = Run(cart, g, f, options, Random(seed))
            run.all()
            check(f.hash(true) == g.madeSha1) { "randomizer wrote outside its sites" }
            f.written()
        }
        onProgress(0.1f)
        copyWithProgress(input, output) { onProgress(0.1f + it * 0.85f) }
        FileSystem.SYSTEM.openReadWrite(output).use { h -> for ((at, bytes) in shuffled) h.write(at, bytes, 0, bytes.size) }
        onProgress(1f)
        return true
    }

    private fun copyWithProgress(input: Path, output: Path, onProgress: (Float) -> Unit) {
        val total = FileSystem.SYSTEM.metadata(input).size ?: 0L
        FileSystem.SYSTEM.openReadOnly(input).use { src ->
            FileSystem.SYSTEM.openReadWrite(output, mustCreate = true).use { dst ->
                val buf = ByteArray(1 shl 20)
                var at = 0L
                while (true) {
                    val n = src.read(at, buf, 0, buf.size)
                    if (n <= 0) break
                    dst.write(at, buf, 0, n)
                    at += n
                    if (total > 0) onProgress(at.toFloat() / total)
                }
            }
        }
    }

    private class Run(val cart: Cart, val g: Game, val f: Files, val o: RomRandomizer.Options, val rnd: Random) {
        val personal = Narc(cart.file(cart.fileId(g.personal)))
        val evo = Narc(cart.file(cart.fileId(g.evolutions)))
        val learn = Narc(cart.file(cart.fileId(g.learnsets)))
        val all = (1..g.speciesCount).toList()

        fun bst(s: Int) = personal.member(s).let { m -> (0 until 6).sumOf { m[it].toInt() and 0xFF } }

        val evolvesTo: Map<Int, List<Int>> = all.associateWith { s ->
            val m = evo.member(s)
            (0 until m.size / 6).mapNotNull { k -> if (u16(m, k * 6) == 0) null else u16(m, k * 6 + 4).takeIf { it in 1..g.speciesCount } }
        }
        val evolvedFrom = evolvesTo.values.flatten().toSet()
        val pool = all.filter { !o.noLegendaries || it !in LEGENDARY }

        fun pick(from: List<Int>, like: Int): Int {
            if (!o.similarStrength || like !in 1..g.speciesCount) return from.random(rnd)
            val target = bst(like)
            var band = 0.1
            while (true) {
                val near = from.filter { kotlin.math.abs(bst(it) - target) <= target * band }
                if (near.size >= 6 || band > 1.0) return near.ifEmpty { from }.random(rnd)
                band += 0.05
            }
        }

        fun all() {
            starters()
            wild()
            if (o.trainers) trainers()
        }

        fun starters() {
            val from = when (o.starters) {
                RomRandomizer.Starters.OFF -> return
                RomRandomizer.Starters.RANDOM -> all.filter { it !in LEGENDARY }
                RomRandomizer.Starters.THREE_STAGE -> all.filter { s ->
                    s !in evolvedFrom && s !in LEGENDARY && evolvesTo[s].orEmpty().any { evolvesTo[it].orEmpty().isNotEmpty() }
                }
            }.toMutableList()
            val three = g.starterOffsets.map { from.random(rnd).also { from.remove(it) } }
            g.starterOffsets.forEachIndexed { i, at -> put32(f.overlay, at, three[i]) }
            g.sprites?.let { spritePatch(it) }
            g.text?.let { starterText(it, three) }
        }

        /** MakePokemonSprites' three calls as a loop over GetSelectedSpecies (see [SpritePatch]). */
        fun spritePatch(p: SpritePatch) {
            val code = ArrayList<Int>()
            fun at() = f.overlayRam + p.at + code.size * 2
            fun bl(target: Int) {
                val off = target - (at() + 4)
                code += 0xF000 or ((off shr 12) and 0x7FF)
                code += 0xF800 or ((off shr 1) and 0x7FF)
            }
            code += 0x2481          // movs r4, #0x81
            code += 0x00A4          // lsls r4, r4, #2: the sprites' offset in the app, 0x204
            code += 0x192C          // adds r4, r5, r4: &app->sprites[0]
            code += 0x2600          // movs r6, #0: the cursor position
            val loop = code.size
            code += 0x1C30          // adds r0, r6, #0
            bl(p.getSpecies)        // GetSelectedSpecies(position)
            code += 0x1C02          // adds r2, r0, #0: the species
            code += 0x1C20          // adds r0, r4, #0: &app->sprites[position]
            code += 0x1C29          // adds r1, r5, #0: app
            bl(p.makeSprite)        // MakePokemonSprite(sprite, app, species)
            code += 0x3404          // adds r4, #4
            code += 0x3601          // adds r6, #1
            code += 0x2E03          // cmp r6, #3
            code += 0xDB00 or (((loop - (code.size + 2)) and 0xFF))   // blt loop
            while (code.size * 2 < p.len) code += 0x46C0   // nop
            check(code.size * 2 == p.len)
            code.forEachIndexed { i, v -> put16(f.overlay, p.at + i * 2, v) }
        }

        /** The starter screen's questions: the new species' name, then each line's own "!" and question. */
        fun starterText(t: StarterText, three: List<Int>) {
            val msg = Narc(f.msg!!)
            val names = Narc(cart.file(cart.fileId(t.path))).member(t.names)
            val file = msg.member(t.bank)
            t.lines.forEachIndexed { i, line ->
                val old = Gen4Text.codes(file, line)
                // The first "!" outside a control code: what follows it is the question, kept as the game words it.
                var j = 0
                var bang = -1
                while (j < old.size) {
                    if (old[j] == 0xFFFE) { j += 3 + old.getOrElse(j + 2) { 0 }; continue }
                    if (Gen4Text.charOf(old[j]) == "!") { bang = j; break }
                    j++
                }
                check(bang > 0) { "no ! in line $line" }
                val name = Gen4Text.codes(names, three[i]).takeWhile { it != 0xFFFF }
                Gen4Text.rewrite(file, line, (name + old.drop(bang).takeWhile { it != 0xFFFF }).toIntArray())
            }
            file.copyInto(f.msg, msg.start(t.bank))
        }

        fun wild() {
            if (o.wild == RomRandomizer.Wild.OFF) return
            val global = HashMap<Int, Int>()
            if (o.wild == RomRandomizer.Wild.GLOBAL) {
                val left = pool.toMutableList()
                for (s in all) global[s] = pick(left.ifEmpty { pool }, s).also { left.remove(it) }
            }
            val enc = Narc(f.enc)
            for (m in 0 until enc.size) {
                val area = HashMap<Int, Int>()
                for (s in ENCOUNTER_SPECIES) {
                    val at = enc.start(m) + s
                    if (at + 4 > enc.end(m)) continue
                    val was = u32(f.enc, at)
                    if (was !in 1..g.speciesCount) continue
                    put32(f.enc, at, when (o.wild) {
                        RomRandomizer.Wild.RANDOM -> pick(pool, was)
                        RomRandomizer.Wild.PER_AREA -> area.getOrPut(was) { pick(pool, was) }
                        else -> global.getValue(was)
                    })
                }
            }
        }

        fun trainers() {
            f.trainerMons(Narc(f.parties)) { at, moves ->
                val was = u16(f.parties, at + 4) and 0x3FF
                if (was !in 1..g.speciesCount) return@trainerMons
                val now = pick(pool, was)
                put16(f.parties, at + 4, now)
                if (moves >= 0) {
                    val list = movesAt(now, u16(f.parties, at + 2))
                    for (k in 0 until 4) put16(f.parties, moves + k * 2, list.getOrElse(k) { 0 })
                }
            }
        }

        /** The last four moves [species] has learnt by [level] (wotbl: move | level << 9, to 0xFFFF). */
        fun movesAt(species: Int, level: Int): List<Int> {
            val m = learn.member(species)
            val known = ArrayList<Int>()
            var i = 0
            while (i + 1 < m.size && u16(m, i) != 0xFFFF) {
                val v = u16(m, i)
                if (v shr 9 > level) break
                val move = v and 0x1FF
                if (move !in known) {
                    if (known.size == 4) known.removeAt(0)
                    known += move
                }
                i += 2
            }
            return known
        }
    }

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, at: Int) = u16(b, at) or (u16(b, at + 2) shl 16)
    private fun put16(b: ByteArray, at: Int, v: Int) {
        b[at] = v.toByte(); b[at + 1] = (v shr 8).toByte()
    }
    private fun put32(b: ByteArray, at: Int, v: Int) {
        put16(b, at, v); put16(b, at + 2, v shr 16)
    }
}
