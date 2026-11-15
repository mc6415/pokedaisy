package com.pokedaisy.app.companion.data

import okio.ByteString.Companion.toByteString
import okio.FileHandle
import okio.FileSystem
import okio.Path
import kotlin.random.Random

/**
 * RANDOMIZE on the DS games (Platinum, HeartGold / SoulSilver; Black / White, Black 2 / White 2): a copy of the player's own cart with its starters, wild Pokémon
 * and trainers' teams shuffled by a seed, like [RomRandomizer] on the GBA ones and with the same [RomRandomizer.Options].
 *
 * The data lives in the cart's file system: the wild Pokémon a record per encounter set (Platinum's WildEncounters in
 * `pl_enc_data.narc`; Gen 5's 0xE8-byte blocks, one per season, in `/a/1/2/7`), the trainers' teams their party archive
 * (the layout from the trainer records), the starters three words in an overlay (Platinum) or in event scripts (Gen 5:
 * [StarterSite]s, plus the choice screen's own list, [PackedStarters]). Every swap keeps each file its size, so the cart is copied and those bytes written in place:
 * no file moves, the header and the file table stay the cart's.
 *
 * A cart this tool made is told by the files it writes ([Game.madeSha1]: their bytes with every site zeroed, hashed
 * together), which is cheap next to hashing a 128-512 MB cart; [readShuffle] gives the GUIDE the shuffled wild sets and teams.
 */
object DsRandomizer {
    class Game(
        val code: String,
        val rev: Int,
        val title: String,
        /** The files the tool writes as the retail cart has them, hashed together. */
        val retailFilesSha1: String,
        /** The same with every site zeroed: what every cart made from it hashes to. */
        val madeSha1: String,
        /** 4: Platinum's and HeartGold's layouts; 5: Black / White's. */
        val gen: Int = 4,
        /** HeartGold / SoulSilver's encounter records (EncounterData, u16 species) rather than Platinum's. */
        val hgss: Boolean = false,
        /** The starters' words in compressed code ([Blz]): HeartGold / SoulSilver's ARM9 binary and starter app. */
        val packedStarters: PackedStarters? = null,
        /** The overlay with the starters' words (Platinum), or null. */
        val starterOverlay: Int? = null,
        /** The starters' words in [starterOverlay], in the order the game shows them (grass, fire, water). */
        val starterOffsets: List<Int> = emptyList(),
        /** The starters' places in event scripts (Gen 5), or null. */
        val starterScripts: StarterScripts? = null,
        val encounters: String,
        val trainers: String,
        val trainerParties: String,
        val personal: String,
        val evolutions: String,
        val learnsets: String,
        /** The moves' records (power at +3 in both generations). */
        val moves: String,
        val speciesCount: Int,
        /** The starter screen's own copies of the three species, rewritten to ask the starters' lookup ([SpritePatch]). */
        val sprites: SpritePatch? = null,
        /** The starter screen's "<category> Pokémon <NAME>! Will you take this Pokémon?" lines ([StarterText]). */
        val text: StarterText? = null,
        /** The item balls' scripts ([Balls]), or null. */
        val balls: Balls? = null,
    )

    /**
     * The item balls' scripts: member [member] of script archive [path], one script per ball (the balls run 7000 + n), each
     * starting, [at] bytes in, with setvar <var> <item>, setvar <var + 1> <count> (opcode 0x28 or HeartGold's 0x29, then the
     * var, then the value: u16s). Hidden items aren't read: their item is in a table in code, not a script.
     */
    class Balls(val path: String, val member: Int, val at: Int = 0, val events: String)

    /**
     * Platinum's MakePokemonSprites builds each starter's species inline (sprite slot offset minus a constant: 0x204 - 0x81 =
     * TURTWIG), so there's nothing to swap: its three calls ([at], [len] bytes in [Game.starterOverlay]) become a loop over
     * GetSelectedSpecies ([getSpecies], the lookup [Game.starterOffsets] feed), and the screen shows what you'll get.
     */
    class SpritePatch(val at: Int, val len: Int, val getSpecies: Int, val makeSprite: Int)

    /**
     * The message [bank] in [path] whose [lines] ask about each starter, by starter; species names are bank [names].
     * Platinum's ("<category> Pokémon TURTWIG! Will you take this Pokémon?") keep what follows the "!"; HeartGold's
     * [inBall] lines ("CHIKORITA, the Grass-type Pokémon, is in this Poké Ball!") become "<NAME> is in this Poké Ball!", its
     * [lines] ("Professor Elm: So, you like CHIKORITA, the Grass-type Pokémon?") "Professor Elm: So, you like <NAME>?".
     */
    class StarterText(val path: String, val bank: Int, val lines: List<Int>, val names: Int, val inBall: List<Int> = emptyList(), val hgss: Boolean = false)

    /**
     * Starters held in compressed code: three words of [width] bytes at [arm9Offset] of the decompressed ARM9 binary
     * (HeartGold's choose_starter.c `species`: what you're given; null = none there) and at [overlayOffset] of overlay
     * [overlay] (HeartGold's choose_starter_app.c `sSpecies`, the cries; Gen 5's choice screen, which draws its three from
     * this list while the scripts hand one over). Both are recompressed to their own size ([Blz.compressTo]), so the cart's
     * header, secure area and tables stay as they are.
     */
    class PackedStarters(val arm9Offset: Int?, val overlay: Int, val overlayOffset: Int, val width: Int = 4)

    /** A starter's species word (u16) in script [member] at [offset]: [which] starter it is (0, 1, 2 = grass, fire, water). */
    class StarterSite(val member: Int, val offset: Int, val which: Int)

    /** The script archive [path] and every place in it a starter's species is named (set, compared, put in text). */
    class StarterScripts(val path: String, val sites: List<StarterSite>)

    // Black 2 / White 2 (the same in both): Aspertia's starter scene (854: the choice var 0x8025 and the names put in its
    // text), the rival's pick (794: the player's starter compared), later scenes naming them (808, 878).
    private val B2W2_STARTERS = StarterScripts("/a/0/5/6", listOf(
        StarterSite(794, 0xB3, 0), StarterSite(794, 0x105, 1), StarterSite(794, 0x157, 2), StarterSite(808, 0xB4, 0),
        StarterSite(808, 0x188, 1), StarterSite(808, 0x25C, 2), StarterSite(854, 0x58B, 0), StarterSite(854, 0x590, 0),
        StarterSite(854, 0x595, 0), StarterSite(854, 0x5C0, 1), StarterSite(854, 0x5C5, 1), StarterSite(854, 0x5CA, 1),
        StarterSite(854, 0x5E2, 2), StarterSite(854, 0x5E7, 2), StarterSite(854, 0x5EC, 2), StarterSite(854, 0x7D0, 0),
        StarterSite(854, 0x7EE, 1), StarterSite(854, 0x7F9, 2), StarterSite(854, 0xB33, 1), StarterSite(854, 0xB51, 2),
        StarterSite(854, 0xB6F, 0), StarterSite(854, 0xEB0, 0), StarterSite(854, 0xECE, 1), StarterSite(854, 0xED9, 2),
        StarterSite(854, 0x106F, 0), StarterSite(854, 0x108D, 1), StarterSite(854, 0x1098, 2), StarterSite(878, 0x1DB, 2),
        StarterSite(878, 0x1F9, 1), StarterSite(878, 0x204, 0),
    ))

    // Black / White (the same in both): the starter scene in the player's room (782: the choice var 0x8021 and the names put
    // in its text, then given from the var), and every later scene naming them (12, 76, 112, 304, 634, 782). Each a word after
    // a SetVar or a species-name buffer command (0x0057), the only two ways these scripts name a starter.
    private val BW_STARTERS = StarterScripts("/a/0/5/7", listOf(
        StarterSite(12, 0x3D6, 0), StarterSite(12, 0x3F9, 1), StarterSite(12, 0x409, 2),
        StarterSite(76, 0x196, 2), StarterSite(76, 0x1B4, 0), StarterSite(76, 0x1BF, 1),
        StarterSite(76, 0x269, 2), StarterSite(76, 0x287, 0), StarterSite(76, 0x292, 1),
        StarterSite(112, 0x2C7, 2), StarterSite(112, 0x2E5, 0), StarterSite(112, 0x2F0, 1),
        StarterSite(304, 0xF9, 0), StarterSite(304, 0x11C, 1), StarterSite(304, 0x12C, 2),
        StarterSite(304, 0x19C, 0), StarterSite(304, 0x1C4, 1), StarterSite(304, 0x1D9, 2),
        StarterSite(634, 0x1E5, 2), StarterSite(634, 0x203, 0), StarterSite(634, 0x20E, 1),
        StarterSite(634, 0x58F, 0), StarterSite(634, 0x5AD, 1), StarterSite(634, 0x5B8, 2),
        StarterSite(782, 0x27F, 0), StarterSite(782, 0x284, 0), StarterSite(782, 0x2AF, 1), StarterSite(782, 0x2B4, 1),
        StarterSite(782, 0x2CC, 2), StarterSite(782, 0x2D1, 2), StarterSite(782, 0x338, 2), StarterSite(782, 0x356, 1),
        StarterSite(782, 0x361, 0), StarterSite(782, 0x5D4, 2), StarterSite(782, 0x5F2, 1), StarterSite(782, 0x5FD, 0),
    ))

    val GAMES = listOf(
        Game(
            "CPUE", 1, "Platinum", "55ac97b09ff4333d5a4aad24fb26ac1133f7dfc9", "e49e0e0cac331493d39c1e59a1ca9a1c335d727f",
            starterOverlay = 78, starterOffsets = listOf(0x1BC0, 0x1BC4, 0x1BC8),
            encounters = "/fielddata/encountdata/pl_enc_data.narc",
            trainers = "/poketool/trainer/trdata.narc", trainerParties = "/poketool/trainer/trpoke.narc",
            personal = "/poketool/personal/pl_personal.narc", evolutions = "/poketool/personal/evo.narc",
            learnsets = "/poketool/personal/wotbl.narc", moves = "/poketool/waza/pl_waza_tbl.narc", speciesCount = 493,
            // choose_starter_app.c (pret/pokeplatinum's xMAP): MakePokemonSprites' calls 0x021D1410-0x021D143A,
            // GetSelectedSpecies 0x021D291C, MakePokemonSprite 0x021D1468; text bank 360 (unk_0360), species names 412.
            sprites = SpritePatch(at = 0x690, len = 42, getSpecies = 0x021D291C, makeSprite = 0x021D1468),
            text = StarterText("/msgdata/pl_msg.narc", bank = 360, lines = listOf(1, 2, 3), names = 412),
            balls = Balls("/fielddata/script/scr_seq.narc", 404, events = "/fielddata/eventdata/zone_event.narc"),
        ),
    ) + listOf(
        Triple("IPKE", "HeartGold", "900df66d390866f97f82e1ff2c2edc98870209ad" to "ebcf06e071c3d1d19aaad70476d288ef644a2b38"),
        Triple("IPGE", "SoulSilver", "4562c3ef0374f1e08591342f43075b0d3d44195b" to "12b28c2ff99d7dc4bdd078a25a2b72c0e59a0555"),
    ).map { (code, title, hashes) ->
        // pret/pokeheartgold: choose_starter.c's species (ARM9), choose_starter_app.c's sSpecies (overlay 61), msg_0190.
        Game(
            code, 0, title, hashes.first, hashes.second, hgss = true, packedStarters = PackedStarters(0x108514, 61, 0x1A98),
            encounters = "/a/0/3/7", trainers = "/a/0/5/5", trainerParties = "/a/0/5/6",
            personal = "/a/0/0/2", evolutions = "/a/0/3/4", learnsets = "/a/0/3/3", moves = "/a/0/1/1", speciesCount = 493,
            text = StarterText("/a/0/2/7", bank = 190, lines = listOf(1, 2, 3), names = 237, inBall = listOf(4, 5, 6), hgss = true),
            balls = Balls("/a/0/1/2", 141, events = "/a/0/3/2"),
        )
    } + listOf(
        Triple("IRBO", "Black", "0a92d35ef541f9c915fc9cb70d7b171267402ca0" to "7c0927e50101fb202476fe1addd59c1ec9614036"),
        Triple("IRAO", "White", "09e827becf04aa7577e617bb9906ef1fcbe1e65a" to "b4caa2a084d6477a134cf6e51769ef0e71763645"),
    ).map { (code, title, hashes) ->
        // No decomp: the archives from the cart (scripts/gen_gen5_guide.py's FILES_BW), the choice screen overlay 223's list.
        Game(
            code, 0, title, hashes.first, hashes.second, gen = 5, starterScripts = BW_STARTERS,
            packedStarters = PackedStarters(null, 223, 0x3170, width = 2),
            encounters = "/a/1/2/6", trainers = "/a/0/9/2", trainerParties = "/a/0/9/3",
            personal = "/a/0/1/6", evolutions = "/a/0/1/9", learnsets = "/a/0/1/8", moves = "/a/0/2/1", speciesCount = 649,
            balls = Balls("/a/0/5/7", 864, at = 2, events = "/a/1/2/5"),
        )
    } + listOf(
        Triple("IREO", "Black 2", "74ea944a90b5c067579a318c05b2e3385ad3f7c5" to "d4d9f718bc40a751067c2b5dbac06c729a170c34"),
        Triple("IRDO", "White 2", "d831b4f7e464b2acc51a6abfcb85c3132376cc66" to "baed0d5289644766fd96c090527c1878d3373422"),
    ).map { (code, title, hashes) ->
        // pret has no decomp of these: the archives from the cart (scripts/gen_gen5_guide.py reads the same ones).
        Game(
            code, 0, title, hashes.first, hashes.second, gen = 5, starterScripts = B2W2_STARTERS,
            packedStarters = PackedStarters(null, 316, 0x2C14, width = 2),
            encounters = "/a/1/2/7", trainers = "/a/0/9/1", trainerParties = "/a/0/9/2",
            personal = "/a/0/1/6", evolutions = "/a/0/1/9", learnsets = "/a/0/1/8", moves = "/a/0/2/1", speciesCount = 649,
            balls = Balls("/a/0/5/6", 1240, at = 2, events = "/a/1/2/6"),
        )
    }

    /** National numbers of the legendaries and mythicals up to Gen 5. */
    private val LEGENDARY = setOf(144, 145, 146, 150, 151, 243, 244, 245, 249, 250, 251) + (377..386) + (480..494) + (638..649)

    // Platinum's struct WildEncounters (include/struct_defs/wild_encounters.h), 0x1A8 bytes: every species word.
    private val GRASS = (0 until 12).map { 8 + it * 8 }
    private val TIMED = listOf(100, 104, 108, 112, 116, 120) + (0 until 4).map { 124 + it * 4 } + (0 until 10).map { 164 + it * 4 }
    private fun water(base: Int) = (0 until 5).map { base + 8 + it * 8 }
    private val SURF = water(204)
    private val OLD_ROD = water(292)
    private val GOOD_ROD = water(336)
    private val SUPER_ROD = water(380)
    private val ENCOUNTER_SPECIES = GRASS + TIMED + SURF + OLD_ROD + GOOD_ROD + SUPER_ROD

    // HeartGold / SoulSilver's EncounterData (include/wild_encounter.h), 0xC4 bytes: every u16 species word.
    private val HG_MORNING = (0 until 12).map { 0x14 + it * 2 }
    private val HG_DAY = (0 until 12).map { 0x2C + it * 2 }
    private val HG_NIGHT = (0 until 12).map { 0x44 + it * 2 }
    private fun hgSlots(base: Int, count: Int) = (0 until count).map { base + it * 4 + 2 }
    private val HG_SURF = hgSlots(0x64, 5)
    private val HG_ROCK = hgSlots(0x78, 2)
    private val HG_OLD_ROD = hgSlots(0x80, 5)
    private val HG_GOOD_ROD = hgSlots(0x94, 5)
    private val HG_SUPER_ROD = hgSlots(0xA8, 5)
    private const val HG_NIGHT_FISH = 0xC0
    private val HGSS_SPECIES = HG_MORNING + HG_DAY + HG_NIGHT + listOf(0x5C, 0x5E, 0x60, 0x62) + HG_SURF + HG_ROCK +
        HG_OLD_ROD + HG_GOOD_ROD + HG_SUPER_ROD + listOf(0xBC, 0xBE, HG_NIGHT_FISH, 0xC2)

    /** Gen 5's encounter block (0xE8 bytes): 56 slots of species (11 bits) + form, min level, max level, from +8. */
    private const val GEN5_BLOCK = 0xE8
    private const val GEN5_SLOTS = 56

    /** A trainer Pokémon's record by the party type (bit 0 its moves, bit 1 an item; struct_defs/trainer_data.h, Gen 5 the
     * same bits): 8 bytes (Platinum: IVs, level, species, seal; Gen 5: IVs, gender / ability, level, species, form), then the
     * item, then the moves (Platinum's before the seal). */
    private fun monStride(type: Int) = 8 + (if (type and 2 != 0) 2 else 0) + (if (type and 1 != 0) 8 else 0)
    private fun itemAt(gen: Int, type: Int) = if (type and 2 != 0) (if (gen == 5) 8 else 6) else -1
    private fun movesAt(gen: Int, type: Int) = if (type and 1 != 0) (if (gen == 5) 8 else 6) + (if (type and 2 != 0) 2 else 0) else -1
    private fun speciesMask(gen: Int) = if (gen == 5) 0x7FF else 0x3FF

    /** Every species word in encounter archive [narc]: its place and width (Platinum's are u32, Gen 5's u16). */
    private fun encounterSites(gen: Int, narc: Narc, each: (member: Int, at: Int, width: Int) -> Unit, hgss: Boolean = false) {
        for (m in 0 until narc.size) {
            val start = narc.start(m)
            val size = narc.end(m) - start
            if (hgss) {
                for (s in HGSS_SPECIES) if (s + 2 <= size) each(m, start + s, 2)
            } else if (gen == 5) {
                for (b in 0 until size / GEN5_BLOCK) for (i in 0 until GEN5_SLOTS) each(m, start + b * GEN5_BLOCK + 8 + i * 4, 2)
            } else {
                for (s in ENCOUNTER_SPECIES) if (s + 4 <= size) each(m, start + s, 4)
            }
        }
    }

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

        fun overlayFile(overlay: Int, allowCompressed: Boolean = false): Int {
            val table = read(u32(header, 0x50).toLong(), u32(header, 0x54))
            for (i in 0 until table.size / 32) if (u32(table, i * 32) == overlay) {
                require(allowCompressed || table[i * 32 + 31].toInt() and 1 == 0) { "overlay $overlay is compressed" }
                return u32(table, i * 32 + 24)
            }
            error("no overlay $overlay")
        }

        /** Overlay [overlay] is compressed ([Blz]). */
        fun overlayCompressed(overlay: Int): Boolean {
            val table = read(u32(header, 0x50).toLong(), u32(header, 0x54))
            for (i in 0 until table.size / 32) if (u32(table, i * 32) == overlay) return table[i * 32 + 31].toInt() and 1 != 0
            error("no overlay $overlay")
        }

        /** The ARM9 binary: where it is in the cart and its bytes (compressed or not, as the cart has it). */
        fun arm9Place(): Long = u32(header, 0x20).toLong()
        fun arm9(): ByteArray = read(u32(header, 0x20).toLong(), u32(header, 0x2C))

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
        val overlayId = g.starterOverlay?.let { cart.overlayFile(it) }
        val overlayRam = g.starterOverlay?.let { cart.overlayRam(it) } ?: 0
        val encId = cart.fileId(g.encounters)
        val partiesId = cart.fileId(g.trainerParties)
        val msgId = g.text?.let { cart.fileId(it.path) }
        val scriptsId = g.starterScripts?.let { cart.fileId(it.path) }
        val overlay = overlayId?.let { cart.file(it) } ?: ByteArray(0)
        val enc = cart.file(encId)
        val parties = cart.file(partiesId)
        val msg = msgId?.let { cart.file(it) }
        val scripts = scriptsId?.let { cart.file(it) }
        // Species records and learnsets: BASE STATS and LEARNSETS write them.
        val personalId = cart.fileId(g.personal)
        val learnId = cart.fileId(g.learnsets)
        val personal = cart.file(personalId)
        val learn = cart.file(learnId)
        val trdata = Narc(cart.file(cart.fileId(g.trainers)))
        // The item balls' script archive: Gen 5's is the starters' own ([scripts]), so it's the same array then.
        val ballsId = g.balls?.let { cart.fileId(it.path) }
        val ballsShared = ballsId != null && ballsId == scriptsId
        val balls = if (ballsShared) scripts else ballsId?.let { cart.file(it) }
        // HeartGold / SoulSilver: the ARM9 binary and the starter app's overlay, as stored and decompressed.
        val arm9Blob = g.packedStarters?.takeIf { it.arm9Offset != null }?.let { cart.arm9() }
        val arm9 = arm9Blob?.let { Blz.decompress(it) }
        val packedOverlayId = g.packedStarters?.let { cart.overlayFile(it.overlay, allowCompressed = true) }
        val packedOverlayBlob = packedOverlayId?.let { cart.file(it) }
        val packedOverlay = packedOverlayBlob?.let { b -> if (cart.overlayCompressed(g.packedStarters!!.overlay)) Blz.decompress(b) else b }

        /** Each file written, at its place in the cart (compressed code re-packed to its own size). */
        fun written(): List<Pair<Long, ByteArray>> = listOfNotNull(
            overlayId?.let { cart.span(it).first to overlay }, cart.span(encId).first to enc, cart.span(partiesId).first to parties,
            msgId?.let { cart.span(it).first to msg!! }, scriptsId?.let { cart.span(it).first to scripts!! },
            cart.span(personalId).first to personal, cart.span(learnId).first to learn,
            ballsId?.takeIf { !ballsShared }?.let { cart.span(it).first to balls!! },
            arm9?.let { cart.arm9Place() to (Blz.compressTo(it, arm9Blob!!.size) ?: error("ARM9 doesn't fit")) },
            packedOverlay?.let { ov ->
                cart.span(packedOverlayId!!).first to
                    (if (cart.overlayCompressed(g.packedStarters!!.overlay)) Blz.compressTo(ov, packedOverlayBlob!!.size) ?: error("overlay doesn't fit") else ov)
            },
        )

        /** The starter sites' absolute places in [scripts]. */
        fun scriptSites(sc: ByteArray): List<Pair<Int, Int>> {
            val n = Narc(sc)
            return g.starterScripts!!.sites.map { n.start(it.member) + it.offset to it.which }
        }

        fun hash(zeroed: Boolean): String {
            val o = overlay.copyOf(); val e = enc.copyOf(); val p = parties.copyOf(); val m = msg?.copyOf() ?: ByteArray(0)
            val sc = scripts?.copyOf() ?: ByteArray(0)
            val a9 = arm9?.copyOf() ?: ByteArray(0)
            val po = packedOverlay?.copyOf() ?: ByteArray(0)
            val pe = personal.copyOf(); val le = learn.copyOf()
            val bl = if (ballsShared) sc else balls?.copyOf() ?: ByteArray(0)
            if (zeroed) {
                statSites(pe) { at -> for (i in 0 until 6) pe[at + i] = 0 }
                learnSites(le) { at, len -> for (i in 0 until len) le[at + i] = 0 }
                g.packedStarters?.let { ps ->
                    for (k in 0 until 3 * ps.width) { ps.arm9Offset?.let { a9[it + k] = 0 }; po[ps.overlayOffset + k] = 0 }
                }
                for (at in g.starterOffsets) for (i in 0 until 4) o[at + i] = 0
                g.sprites?.let { sp -> for (i in 0 until sp.len) o[sp.at + i] = 0 }
                encounterSites(g.gen, Narc(e), { _, at, width -> for (i in 0 until width) e[at + i] = 0 }, g.hgss)
                trainerMons(Narc(p)) { at, moves ->
                    p[at + 2] = 0
                    p[at + 4] = 0; p[at + 5] = 0
                    // Gen 5's form word goes with the species.
                    if (g.gen == 5) { p[at + 6] = 0; p[at + 7] = 0 }
                    if (moves >= 0) for (i in 0 until 8) p[moves + i] = 0
                }
                trainerItems(Narc(p)) { at -> p[at] = 0; p[at + 1] = 0 }
                g.text?.let { t ->
                    val mn = Narc(m)
                    val bank = mn.start(t.bank)
                    val file = mn.member(t.bank)
                    for (line in t.lines + t.inBall) {
                        val (off, len) = Gen4Text.span(file, line)
                        for (i in 0 until len * 2) m[bank + off + i] = 0
                    }
                }
                if (scripts != null) for ((at, _) in scriptSites(sc)) { sc[at] = 0; sc[at + 1] = 0 }
                if (balls != null) ballSites(bl) { at -> bl[at] = 0; bl[at + 1] = 0 }
            }
            return (o + e + p + m + sc + a9 + po + pe + le + (if (ballsShared) ByteArray(0) else bl)).toByteString().sha1().hex()
        }

        /** An item an item ball keeps: HMs and key items (Gen 4: 420 on; Gen 5: 420-536 and 615-638, but its TM93-95). */
        fun keepsItem(item: Int) = item == 0 || if (g.gen == 4) item >= 420 else item in 420..536 || item in 615..638 && item !in 618..620

        /** Each item ball's item word in [d] (the script archive [Balls] names), but the ones [keepsItem] keeps. */
        fun ballSites(d: ByteArray, each: (at: Int) -> Unit) {
            val b = g.balls ?: return
            val n = Narc(d)
            val start = n.start(b.member)
            val end = n.end(b.member)
            var k = 0
            while (start + 4 * k + 4 <= end && u16(d, start + 4 * k) != 0xFD13 && k < 4096) {
                val s = start + 4 * k + 4 + u32(d, start + 4 * k) + b.at
                k++
                if (s + 12 > end) continue
                val op = u16(d, s)
                if ((op == 0x28 || op == 0x29) && u16(d, s + 6) == op && u16(d, s + 8) == u16(d, s + 2) + 1 &&
                    !keepsItem(u16(d, s + 4))) each(s + 4)
            }
        }

        /** Each species' base stats in [d] (the species records): their place (six bytes from it). */
        fun statSites(d: ByteArray, each: (at: Int) -> Unit) {
            val n = Narc(d)
            for (s in 1..minOf(g.speciesCount, n.size - 1)) if (n.end(s) - n.start(s) >= 6) each(n.start(s))
        }

        /** Each level-up entry in [d] (the learnsets): its place and size (Gen 4's move | level << 9, Gen 5's move, level). */
        fun learnSites(d: ByteArray, each: (at: Int, len: Int) -> Unit) {
            val n = Narc(d)
            val step = if (g.gen == 5) 4 else 2
            for (s in 1..minOf(g.speciesCount, n.size - 1)) {
                var at = n.start(s)
                while (at + step <= n.end(s) && u16(d, at) != 0xFFFF) { each(at, step); at += step }
            }
        }

        /** Each trainer Pokémon's held item word in [narc] (trpoke), where its record has one. */
        fun trainerItems(narc: Narc, each: (at: Int) -> Unit) {
            for (t in 0 until minOf(trdata.size, narc.size)) {
                val head = trdata.member(t)
                if (head.size < 4) continue
                val type = head[0].toInt() and 0xFF
                val item = itemAt(g.gen, type)
                if (item < 0) continue
                val count = head[3].toInt() and 0xFF
                val stride = monStride(type)
                if (narc.end(t) - narc.start(t) < count * stride) continue
                for (i in 0 until count) each(narc.start(t) + i * stride + item)
            }
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
                    each(at, movesAt(g.gen, type).let { if (it < 0) -1 else at + it })
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
    class Shuffle(
        val game: Game,
        private val sets: List<ByteArray>,
        private val teams: Map<Int, List<TrainerMon>>,
        items: Map<Int, Int> = emptyMap(),
    ) {
        /** HERE's item balls as this cart has them, by their flags ([ballItems]); hidden items stay the game's. */
        val guide = RandomizedGuide(items, trades = null, statics = null, keepUnknown = true)

        /** Encounter set [set]'s species at byte [offset] of its record, or null when it has none there. */
        fun speciesAt(set: Int, offset: Int): Int? = sets.getOrNull(set)?.let { m ->
            if (game.gen == 5) m.takeIf { offset + 2 <= it.size }?.let { u16(it, offset) and 0x7FF }
            else if (game.hgss) m.takeIf { offset + 2 <= it.size }?.let { u16(it, offset) and 0x3FF }
            else m.takeIf { offset + 4 <= it.size }?.let { u32(it, offset) }
        }

        /** Gen 5: encounter set [set]'s species in season block [block] (0 when the set has one), way slot [slot] (0-55). */
        fun gen5Species(set: Int, block: Int, slot: Int): Int? {
            val blocks = (sets.getOrNull(set)?.size ?: 0) / GEN5_BLOCK
            if (blocks == 0) return null
            return speciesAt(set, (if (blocks == 1) 0 else block) * GEN5_BLOCK + 8 + slot * 4)
        }
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
                    val moves = movesAt(g.gen, type).let { o -> if (o < 0) emptyList() else (0 until 4).map { u16(m, at + o + it * 2) }.filter { it != 0 } }
                    val item = itemAt(g.gen, type).let { o -> if (o < 0) 0 else u16(m, at + o) }
                    TrainerMon(u16(m, at + 4) and speciesMask(g.gen), m[at + 2].toInt() and 0xFF, item, moves)
                }
            }
            Shuffle(g, (0 until enc.size).map { enc.member(it) }, teams, ballItems(cart, g, f))
        }
    }.getOrNull()

    /**
     * Each item ball's item by its object's flag, as the GUIDE's area data names it: a person in the event archive
     * ([Balls.events]) running ball script 7000 + n has its hide flag in the word before the script's, in every DS game's
     * layout (Gen 4's 32-byte object events, Gen 5's 0x24-byte people), so every member is searched for that pair.
     */
    private fun ballItems(cart: Cart, g: Game, f: Files): Map<Int, Int> {
        val b = g.balls ?: return emptyMap()
        val d = f.balls ?: return emptyMap()
        val n = Narc(d)
        val start = n.start(b.member)
        val items = ArrayList<Int>()
        while (u16(d, start + 4 * items.size) != 0xFD13 && items.size < 4096) {
            val k = items.size
            items += u16(d, start + 4 * k + 4 + u32(d, start + 4 * k) + b.at + 4)
        }
        val events = Narc(cart.file(cart.fileId(b.events)))
        val out = HashMap<Int, Int>()
        for (m in 0 until events.size) {
            val e = events.member(m)
            var p = 0
            while (p + 4 <= e.size) {
                val sc = u16(e, p + 2) - 7000
                val flag = u16(e, p)
                if (sc in items.indices && flag != 0) out.putIfAbsent(flag, items[sc])
                p += 2
            }
        }
        return out
    }

    /** [path] as the run modes' tests look at it ([CartView]): each encounter member's species, every trainer Pokémon. */
    internal fun view(path: Path): CartView? = FileSystem.SYSTEM.openReadOnly(path).use { h ->
        val cart = Cart(h)
        val g = gameOfHeader(cart) ?: return@use null
        val f = Files(cart, g)
        val wild = LinkedHashMap<Int, MutableList<Int>>()
        encounterSites(g.gen, Narc(f.enc), hgss = g.hgss, each = { m, at, width ->
            val raw = if (width == 4) u32(f.enc, at) else u16(f.enc, at)
            val s = raw and speciesMask(g.gen)
            if (s in 1..g.speciesCount) wild.getOrPut(m) { ArrayList() } += s
        })
        val trainers = ArrayList<Pair<Int, Int>>()
        f.trainerMons(Narc(f.parties)) { at, _ -> trainers += (u16(f.parties, at + 4) and speciesMask(g.gen)) to (f.parties[at + 2].toInt() and 0xFF) }
        val personal = Narc(f.personal)
        CartView(wild.values.toList(), trainers) { s ->
            if (s !in 1..g.speciesCount) emptySet() else personal.member(s).let { m -> setOf(m[6].toInt() and 0xFF, m[7].toInt() and 0xFF) }
        }
    }

    /** [path]'s item balls by flag ([ballItems]), any cart of a known game (the tests check retail's against the GUIDE). */
    internal fun ballItems(path: Path): Map<Int, Int>? = FileSystem.SYSTEM.openReadOnly(path).use { h ->
        val cart = Cart(h)
        val g = gameOfHeader(cart) ?: return@use null
        ballItems(cart, g, Files(cart, g))
    }

    /** Platinum's [Gen4World] places: a grass slot's, the day / night pair's, a water slot's species word in the record. */
    internal fun grassOffset(slot: Int) = GRASS[slot]
    internal fun dayOffset(i: Int) = TIMED[2 + i]
    internal fun nightOffset(i: Int) = TIMED[4 + i]
    internal fun surfOffset(slot: Int) = SURF[slot]
    internal fun rodOffset(rod: Int, slot: Int) = listOf(OLD_ROD, GOOD_ROD, SUPER_ROD)[rod][slot]

    /** HeartGold / SoulSilver's places in an EncounterData record, for [Gen4World]. */
    internal fun hgGrassOffset(time: Int, slot: Int) = listOf(HG_MORNING, HG_DAY, HG_NIGHT)[time][slot]
    internal fun hgSurfOffset(slot: Int) = HG_SURF[slot]
    internal fun hgRockOffset(slot: Int) = HG_ROCK[slot]
    internal fun hgRodOffset(rod: Int, slot: Int) = listOf(HG_OLD_ROD, HG_GOOD_ROD, HG_SUPER_ROD)[rod][slot]
    internal const val HG_NIGHT_FISH_OFFSET = HG_NIGHT_FISH

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
        // The cheap check first: a wrong cart isn't copied.
        if (gameOf(input) == null) return false
        copyWithProgress(input, output) { onProgress(it * 0.9f) }
        return randomizeInPlace(output, options, seed).also { onProgress(1f) }
    }

    /** [cart] (a copy of the player's: the caller's own file, an unpacked archive) shuffled where it is. False, and nothing
     * written, when the tool can't take it. */
    fun randomizeInPlace(cart: Path, options: RomRandomizer.Options, seed: Long): Boolean {
        val shuffled = FileSystem.SYSTEM.openReadOnly(cart).use { h ->
            val c = Cart(h)
            val g = gameOfHeader(c) ?: return false
            val f = Files(c, g)
            if (f.hash(false) != g.retailFilesSha1 && f.hash(true) != g.madeSha1) return false
            Run(c, g, f, options, Random(seed)).all()
            check(f.hash(true) == g.madeSha1) { "randomizer wrote outside its sites" }
            f.written()
        }
        FileSystem.SYSTEM.openReadWrite(cart).use { h -> for ((at, bytes) in shuffled) h.write(at, bytes, 0, bytes.size) }
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
        // The species records and learnsets as written (BASE STATS / LEARNSETS change them in place).
        val personal get() = Narc(f.personal)
        val evo = Narc(cart.file(cart.fileId(g.evolutions)))
        val learn get() = Narc(f.learn)
        val moveData = Narc(cart.file(cart.fileId(g.moves)))
        val all = (1..g.speciesCount).toList()

        fun bst(s: Int) = personal.member(s).let { m -> (0 until 6).sumOf { m[it].toInt() and 0xFF } }

        val evolvesTo: Map<Int, List<Int>> = all.associateWith { s ->
            val m = evo.member(s)
            (0 until m.size / 6).mapNotNull { k -> if (u16(m, k * 6) == 0) null else u16(m, k * 6 + 4).takeIf { it in 1..g.speciesCount } }
        }
        val evolvedFrom = evolvesTo.values.flatten().toSet()
        /** Each species record's two type bytes (+6, +7), read once (BASE STATS shuffles only the six stats before them). */
        private val typeOf: Map<Int, Set<Int>> by lazy {
            val n = personal
            all.associateWith { sp -> n.member(sp).let { m -> setOf(m[6].toInt() and 0xFF, m[7].toInt() and 0xFF) } }
        }
        fun types(s: Int) = typeOf[s].orEmpty()

        val picker = SpeciesPicker(
            all.filter { !o.noLegendaries || it !in LEGENDARY }, o, rnd,
            bst = { if (it in 1..g.speciesCount) bst(it) else 0 }, types = { if (it in 1..g.speciesCount) types(it) else emptySet() },
            monotype = o.monotype?.id(g.gen),
        )
        val pool get() = picker.pool

        fun all() {
            // Stats and learnsets first: the strength match keeps working (the totals stay) and trainers' moves follow.
            if (o.baseStats) baseStats()
            if (o.learnsets) learnsets()
            starters()
            wild()
            if (o.levelScale > 0) levels()
            if (o.trainers) trainers()
            if (o.trainerItems) trainerItems()
            if (o.fieldItems) fieldItems()
        }

        /** LEVELS: every trainer Pokémon's level (the byte at +2 of its record) raised. */
        fun levels() {
            f.trainerMons(Narc(f.parties)) { at, _ ->
                f.parties[at + 2] = SpeciesPicker.scaled(f.parties[at + 2].toInt() and 0xFF, o.levelScale).toByte()
            }
        }

        /** The item balls' items, shuffled among the same balls (HMs and key items not among them). */
        fun fieldItems() {
            val d = f.balls ?: return
            val places = ArrayList<Int>().also { l -> f.ballSites(d) { l += it } }
            val items = places.map { u16(d, it) }.shuffled(rnd)
            places.forEachIndexed { i, at -> put16(d, at, items[i]) }
        }

        /** Each species' six base stats in another order (SHEDINJA's 1 HP left as it is). */
        fun baseStats() {
            f.statSites(f.personal) { at ->
                if (f.personal[at].toInt() and 0xFF == 1) return@statSites
                val six = (0 until 6).map { f.personal[at + it] }.shuffled(rnd)
                six.forEachIndexed { i, v -> f.personal[at + i] = v }
            }
        }

        /** Random moves at each species' own levels: none twice in a list, the first one with power. */
        fun learnsets() {
            val moves = (1 until moveData.size).filter { it != STRUGGLE && moveData.end(it) - moveData.start(it) > 3 }
            val damaging = moves.filter { (moveData.member(it)[3].toInt() and 0xFF) > 0 }
            var species = -1
            var used = HashSet<Int>()
            var first = true
            val n = Narc(f.learn)
            f.learnSites(f.learn) { at, len ->
                val s = (1..g.speciesCount).firstOrNull { at >= n.start(it) && at < n.end(it) } ?: return@learnSites
                if (s != species) { species = s; used = HashSet(); first = true }
                var move: Int
                do { move = (if (first) damaging else moves).random(rnd) } while (!used.add(move))
                first = false
                if (len == 4) put16(f.learn, at, move)
                else put16(f.learn, at, (u16(f.learn, at) and 0xFE00) or move)
            }
        }

        /** Every held item word a trainer has: one of the items trainers hold anywhere in the game. */
        fun trainerItems() {
            val held = ArrayList<Int>().also { l -> f.trainerItems(Narc(f.parties)) { at -> u16(f.parties, at).takeIf { it != 0 }?.let { l += it } } }.distinct()
            if (held.isEmpty()) return
            f.trainerItems(Narc(f.parties)) { at -> put16(f.parties, at, held.random(rnd)) }
        }

        fun starters() {
            val from = when (o.starters) {
                RomRandomizer.Starters.OFF -> return
                RomRandomizer.Starters.RANDOM -> picker.themed(all.filter { it !in LEGENDARY })
                RomRandomizer.Starters.THREE_STAGE -> picker.themed(all.filter { s ->
                    s !in evolvedFrom && s !in LEGENDARY && evolvesTo[s].orEmpty().any { evolvesTo[it].orEmpty().isNotEmpty() }
                })
            }.toMutableList()
            val three = List(3) { from.random(rnd).also { from.remove(it) } }
            g.starterOffsets.forEachIndexed { i, at -> put32(f.overlay, at, three[i]) }
            f.scripts?.let { sc -> for ((at, which) in f.scriptSites(sc)) put16(sc, at, three[which]) }
            g.packedStarters?.let { ps ->
                for (i in 0 until 3) {
                    ps.arm9Offset?.let { put32(f.arm9!!, it + i * 4, three[i]) }
                    if (ps.width == 2) put16(f.packedOverlay!!, ps.overlayOffset + i * 2, three[i])
                    else put32(f.packedOverlay!!, ps.overlayOffset + i * 4, three[i])
                }
            }
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
            if (t.hgss) return heartGoldText(t, three)
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

        /** HeartGold's lines (see [StarterText]): the game's own words round the new name. */
        fun heartGoldText(t: StarterText, three: List<Int>) {
            val msg = Narc(f.msg!!)
            val names = msg.member(t.names)
            val file = msg.member(t.bank)
            fun plain(codes: IntArray): List<Int> {
                // Its characters, control codes ({COLOR}) left out.
                val out = ArrayList<Int>()
                var j = 0
                while (j < codes.size && codes[j] != 0xFFFF) {
                    if (codes[j] == 0xFFFE) { j += 3 + codes.getOrElse(j + 2) { 0 }; continue }
                    out += codes[j++]
                }
                return out
            }
            t.lines.forEachIndexed { i, line ->
                val old = Gen4Text.codes(file, line)
                // Up to the name's colour code ("Professor Elm: So, you like "), the name, the line's last character ("?").
                val cut = old.indexOf(0xFFFE).also { check(it > 0) { "no name in line $line" } }
                val last = plain(old).last()
                val name = Gen4Text.codes(names, three[i]).takeWhile { it != 0xFFFF }
                Gen4Text.rewrite(file, line, (old.take(cut) + name + last).toIntArray())
            }
            t.inBall.forEachIndexed { i, line ->
                val text = plain(Gen4Text.codes(file, line))
                // From " is" on: " is\nin this Poké Ball!".
                val at = (0 until text.size - 2).first {
                    Gen4Text.charOf(text[it]) == " " && Gen4Text.charOf(text[it + 1]) == "i" && Gen4Text.charOf(text[it + 2]) == "s"
                }
                val name = Gen4Text.codes(names, three[i]).takeWhile { it != 0xFFFF }
                Gen4Text.rewrite(file, line, (name + text.drop(at)).toIntArray())
            }
            file.copyInto(f.msg, msg.start(t.bank))
        }

        fun wild() {
            if (o.wild == RomRandomizer.Wild.OFF) return
            val global = HashMap<Int, Int>()
            if (o.wild == RomRandomizer.Wild.GLOBAL) {
                val left = pool.toMutableList()
                for (s in all) global[s] = picker.pick(s, from = left.ifEmpty { pool }, wild = true).also { left.remove(it) }
            }
            // An area is an encounter archive member; NO DUPLICATES avoids what it already has.
            var area = HashMap<Int, Int>()
            var used = HashSet<Int>()
            var member = -1
            encounterSites(g.gen, Narc(f.enc), hgss = g.hgss, each = { m, at, width ->
                if (m != member) { area = HashMap(); used = HashSet(); member = m }
                val raw = if (width == 4) u32(f.enc, at) else u16(f.enc, at)
                val was = raw and speciesMask(g.gen)
                if (was !in 1..g.speciesCount) return@encounterSites
                val avoid = if (o.noDuplicates) used else emptySet<Int>()
                val now = when (o.wild) {
                    RomRandomizer.Wild.RANDOM -> picker.pick(was, avoid, wild = true)
                    RomRandomizer.Wild.PER_AREA -> area.getOrPut(was) { picker.pick(was, avoid, wild = true) }
                    else -> global.getValue(was)
                }
                used += now
                // Gen 5's form bits go with the old species: the new one is its plain form.
                if (width == 4) put32(f.enc, at, now) else put16(f.enc, at, now)
            })
        }

        fun trainers() {
            f.trainerMons(Narc(f.parties)) { at, moves ->
                val was = u16(f.parties, at + 4) and speciesMask(g.gen)
                if (was !in 1..g.speciesCount) return@trainerMons
                val now = picker.pick(was, keepType = o.trainersKeepType)
                put16(f.parties, at + 4, now)
                // Gen 5's form is its own word: the new species' plain form.
                if (g.gen == 5) put16(f.parties, at + 6, 0)
                if (moves >= 0) {
                    val list = movesAt(now, f.parties[at + 2].toInt() and 0xFF)
                    for (k in 0 until 4) put16(f.parties, moves + k * 2, list.getOrElse(k) { 0 })
                }
            }
        }

        /** The last four moves [species] has learnt by [level] (Platinum's wotbl: move | level << 9, to 0xFFFF; Gen 5's:
         * (move, level) pairs, to a 0xFFFF move). */
        fun movesAt(species: Int, level: Int): List<Int> {
            val m = learn.member(species)
            val known = ArrayList<Int>()
            val step = if (g.gen == 5) 4 else 2
            var i = 0
            while (i + step <= m.size && u16(m, i) != 0xFFFF) {
                val move = if (g.gen == 5) u16(m, i) else u16(m, i) and 0x1FF
                val at = if (g.gen == 5) u16(m, i + 2) else u16(m, i) shr 9
                if (at > level) break
                if (move !in known) {
                    if (known.size == 4) known.removeAt(0)
                    known += move
                }
                i += step
            }
            return known
        }
    }

    private const val STRUGGLE = 165

    private fun u16(b: ByteArray, at: Int) = (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, at: Int) = u16(b, at) or (u16(b, at + 2) shl 16)
    private fun put16(b: ByteArray, at: Int, v: Int) {
        b[at] = v.toByte(); b[at + 1] = (v shr 8).toByte()
    }
    private fun put32(b: ByteArray, at: Int, v: Int) {
        put16(b, at, v); put16(b, at + 2, v shr 16)
    }
}
