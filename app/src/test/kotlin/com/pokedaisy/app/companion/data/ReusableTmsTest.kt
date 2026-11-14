package com.pokedaisy.app.companion.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * REUSABLE TMS' sites, found in each ROM by their code ([ReusableTms.find]): the TM-number compare, the `bhi` the
 * patch turns into a `b` and the call to the game's RemoveBagItem - which must be the RemoveBagItem FieldItems has for
 * that build (found its own way: port_field_items.py, the decomps). Played headless with mgba_dump's `rompatch`
 * (docs/ROM_FEATURES.md, note 15). Skips a ROM that isn't here.
 */
class ReusableTmsTest {
    private val cases = listOf(
        "Pokemon - FireRed Version (USA, Europe) (Rev 1).gba" to 3,
        "Pokemon - FireRed Version (USA).gba" to 3,
        "Pokemon - LeafGreen Version (USA).gba" to 3,
        "Pokemon - LeafGreen Version (USA, Europe) (Rev 1).gba" to 3,
        "Pokemon - Emerald Version (USA, Europe).gba" to 1,
        "Pokemon - Ruby Version (USA, Europe) (Rev 1).gba" to 1,
        "Pokemon - Sapphire Version (USA, Europe) (Rev 2).gba" to 1,
        // Hacks that kept that code.
        "Pokemon Emerald Kaizo (v2.0).gba" to 1,
        "Pokemon Emerald Legacy (v1.1.4).gba" to 1,
        "Pokemon Orange Islands (Beta 5.7).gba" to 3,
        "Pokemon FireRed Rocket Edition.gba" to 3,
        "Pokemon Ultra Violet (v1.22).gba" to 3,
        "Pokemon Celia's Stupid Romhack (v1.1.4).gba" to 3,
        // One of FireRed's three left: none, it would still use TMs up elsewhere.
        "Pokemon Ash Gray (v4.5.3).gba" to 0,
        "Pokemon Ghost Grey (v1.0.4).gba" to 0,
        // Let's Go: none of FireRed's three sites kept (its TM code isn't checked: the row stays off).
        "Pokemon Let's Go Pikachu.gba" to 0,
        // Their own TM code: Radical Red / Unbound / SoulGold's TMs never run out ([ReusableTms.BUILT_IN]).
        "Pokemon Radical Red (v4.1).gba" to 0,
        "Pokemon Unbound (v2.1.1.1).gba" to 0,
        "Pokemon SoulGold (v1.1.4).gba" to 0,
        "Pokemon SoulGold (v1.2) (Alt 2).gba" to 0,
        "Pokemon SoulGold (v1.2.1).gba" to 0,
        "Pokemon Glazed (v9.2.0).gba" to 0,
        "Pokemon Lazarus (v2.0).gba" to 0,
    )

    @Test
    fun findsEachGamesSites() {
        for ((name, want) in cases) {
            val rom = File(RETAIL_ROM_DIR + name)
            if (!rom.isFile) continue
            val sites = ReusableTms.find(rom)
            assertEquals(name, want, sites.size)
            sites.forEach { assertEquals(name, 0x03, it.expect[ReusableTms.BRANCH.toInt()].toInt()) }
        }
    }

    @Test
    fun sitesCallTheBuildsRemoveBagItem() {
        val rom = File(RETAIL_ROM_DIR + "Pokemon Emerald Legacy (v1.1.4).gba")
        if (rom.isFile) assertEquals("%08X".format(FIELD_ITEMS_EMERALD_LEGACY.removeBagItem), "%08X".format(ReusableTms.find(rom).single().calls))
    }

    /** Every retail cart in the retail ROM dir (-DmultiRomDir=...): as many sites as its engine
     * has, each calling the RemoveBagItem of that build's field-item table. */
    @Test
    fun everyRetailBuild() {
        val dir = File(System.getProperty("multiRomDir") ?: RETAIL_ROM_DIR)
        assumeTrue(dir.isDirectory)
        var checked = 0
        for (f in retailRomFiles(dir).filter { listOf("Pirate", "Debug").none { x -> x in it.name } }) {
            val h = f.inputStream().use { ByteArray(0xC0).also { b -> it.read(b) } }
            val code = String(h, 0xAC, 4)
            val sites = ReusableTms.find(f)
            assertEquals(f.name, ReusableTms.expectedSites(code), sites.size)
            val remove = fieldItemsFor(code, h[0xBC].toInt())?.removeBagItem ?: continue
            sites.forEach { assertEquals(f.name, "%08X".format(remove), "%08X".format(it.calls)) }
            checked++
        }
        assertTrue("checked $checked", checked >= 30)
    }

    @Test
    fun alwaysSkipLandsWhereTheBhiDid() {
        // bhi +3 (D803) and b +3 (E003): both to the instruction after the call.
        assertTrue(ReusableTms.ALWAYS_SKIP.contentEquals(byteArrayOf(0x03, 0xE0.toByte())))
        assertEquals(3, ReusableTms.expectedSites("BPRF"))
        assertEquals(1, ReusableTms.expectedSites("AXPJ"))
        assertEquals(0, ReusableTms.expectedSites("CGB0"))
    }

    @Test fun dsCartsProved() {
        org.junit.Assert.assertTrue(ReusableTms.dsProved("CPUE", 1))
        org.junit.Assert.assertTrue(ReusableTms.dsProved("IPKE", 0))
        org.junit.Assert.assertTrue(ReusableTms.dsProved("IPGE", 0))
        org.junit.Assert.assertFalse(ReusableTms.dsProved("CPUE", 0))
        org.junit.Assert.assertFalse(ReusableTms.dsProved("IPKD", 0))
        org.junit.Assert.assertFalse(ReusableTms.dsProved("IRBO", 0))
    }

    /** Gold / Silver / Crystal ([ReusableTms.gbSites]): ConsumeTM by the cart's hash, its bytes checked; `ret` over its first. */
    @Test fun gameBoySites() {
        fun rom(off: Int, hex: String) = ByteArray(0x200000).also { b ->
            hex.chunked(2).forEachIndexed { i, h -> b[off + i] = h.toInt(16).toByte() }
        }
        val crystal = rom(0x2CB0C, "cda747fa65d23d2159d806004f097ea7c83d77c0")
        for (sha1 in TelemetrySampler.CRYSTAL_SHA1S) {
            val s = ReusableTms.gbSites(sha1, crystal).single()
            assertEquals(0x2CB0CL, s.at)
            assertEquals(0x2CB0CL, s.branch)
            assertTrue(s.value.contentEquals(byteArrayOf(0xC9.toByte())))
            assertEquals(20, s.expect.size)
        }
        val gold = rom(0x2C911, "cdb245fa51d13d217ed506004f097ea7c83d77c0")
        for (sha1 in listOf(TelemetrySampler.GOLD_SHA1, TelemetrySampler.SILVER_SHA1)) {
            assertEquals(0x2C911L, ReusableTms.gbSites(sha1, gold).single().at)
        }
        // Another cart's hash, or bytes that aren't ConsumeTM's: nothing.
        assertTrue(ReusableTms.gbSites(TelemetrySampler.GOLD_DE_SHA1, gold).isEmpty())
        assertTrue(ReusableTms.gbSites(TelemetrySampler.GOLD_SHA1, crystal).isEmpty())
        assertTrue(ReusableTms.gbSites(TelemetrySampler.CRYSTAL_REV1_SHA1, ByteArray(0x1000)).isEmpty())
    }

    /** Each English cart here, its real bytes. Skips a ROM that isn't here. */
    @Test fun gameBoySitesInTheCarts() {
        val dir = File(System.getProperty("romDir") ?: System.getenv("POKEDAISY_ROM_DIR") ?: return).parentFile.resolve("gbc")
        val carts = listOf(
            "Pokemon - Crystal Version (USA, Europe) (Rev 1).gbc",
            "Pokemon - Gold Version (USA, Europe) (SGB Enhanced) (GB Compatible).gbc",
            "Pokemon - Silver Version (USA, Europe) (SGB Enhanced) (GB Compatible).gbc",
        ).map { dir.resolve(it) }.filter { it.isFile }
        assumeTrue(carts.isNotEmpty())
        for (f in carts) {
            val b = f.readBytes()
            val sha1 = java.security.MessageDigest.getInstance("SHA-1").digest(b).joinToString("") { "%02x".format(it) }
            assertEquals(f.name, 1, ReusableTms.gbSites(sha1, b).size)
        }
    }
}
