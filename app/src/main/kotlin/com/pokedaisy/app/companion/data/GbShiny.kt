package com.pokedaisy.app.companion.data

/**
 * Gen 2's shiny check on a Pokémon's two DV bytes (Attack | Defense, Speed | Special, high nibble first): pokecrystal's
 * CheckShininess - Attack's DV with bit 1 set (2, 3, 6, 7, 10, 11, 14 or 15), Defense, Speed and Special all 10 (1 in 8192).
 * Gold / Silver / Crystal and the hacks on that engine draw such a Pokémon shiny; a Gen 1 Pokémon's DVs cross the Time Capsule
 * unchanged, so one of Red / Blue / Yellow's that passes it is shiny once traded up ([tradesToGen2]).
 */
fun gen2ShinyDvs(atkDef: Int, spdSpc: Int): Boolean =
    atkDef and 0x20 != 0 && atkDef and 0x0F == 10 && spdSpc and 0xFF == 0xAA

/** Polished Crystal keeps shininess in its personality byte instead (MON_SHINY = MON_PERSONALITY, MON_SHINY_F 7). */
fun polishedShiny(personality: Int): Boolean = personality and 0x80 != 0

/** The Gen 1 carts whose Pokémon reach Gold / Silver / Crystal by the Time Capsule: retail Red, Blue and Yellow (every
 * language). The hacks on Gen 1's engine (PureRGB, Yellow Legacy) aren't marked: they don't trade with the retail Gen 2 games. */
val GameKind?.tradesToGen2: Boolean get() = this == GameKind.RED || this == GameKind.BLUE || this == GameKind.YELLOW
