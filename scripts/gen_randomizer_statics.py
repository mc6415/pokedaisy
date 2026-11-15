#!/usr/bin/env python3
"""RANDOMIZE's gift and static Pokémon: where each one's species is written, per cart the library's RANDOMIZE takes.

    scripts/gen_randomizer_statics.py <UPR config dir> <ROMs dir>

<UPR config dir> is Universal Pokemon Randomizer ZX's src/com/dabomstew/pkrandom/config (gen1-5_offsets.ini, GPLv3:
only the offsets are taken, as facts, never its code); <ROMs dir> holds the English carts (No-Intro names, gba/ gb/
gbc/ nds/ beside each other: the folder RETAIL_ROM_DIR points at, or its parent). Each StaticPokemon entry lists every
place its species is written (the battle or gift, its cry, its picture, a check that it's the one): each one is read
off the cart and the entry kept only when all of them hold the same species, so a wrong offset can't slip in. Roamers
(RoamingPokemon) aren't taken: UPR rewrites code to move them.

Writes app/src/main/kotlin/com/pokedaisy/app/companion/data/RandomizerStaticsGen.kt: per cart (GBA by game code and
revision, Game Boy by title, DS by game code) a list of statics, each an IntArray: its retail species (as the cart numbers it), then its species places - file offsets
(GBA u16, Game Boy u8), or for the DS (member, offset) pairs in the cart's script archive (u16).
"""
import os
import re
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import gen_gen5_guide as G  # noqa: E402 (its NDS reader)

GBA = [  # key, UPR section, file
    ("BPRE1", "Fire Red (U) 1.1", "gba/Pokemon - FireRed Version (USA, Europe) (Rev 1).gba"),
    ("BPGE1", "Leaf Green (U) 1.1", "gba/Pokemon - LeafGreen Version (USA, Europe) (Rev 1).gba"),
    ("AXVE2", "Ruby (U/E) 1.2", "gba/Pokemon - Ruby Version (USA, Europe) (Rev 2).gba"),
    ("AXPE2", "Sapphire (U/E) 1.2", "gba/Pokemon - Sapphire Version (USA, Europe) (Rev 2).gba"),
    ("BPEE0", "Emerald (U)", "gba/Pokemon - Emerald Version (USA, Europe).gba"),
]
GB = [
    ("Red", "Red (U)", "gb/Pokemon - Red Version (USA, Europe) (SGB Enhanced).gb", 1),
    ("Blue", "Blue (U)", "gb/Pokemon - Blue Version (USA, Europe) (SGB Enhanced).gb", 1),
    ("Yellow", "Yellow (U)", "gb/Pokemon - Yellow Version - Special Pikachu Edition (USA, Europe) (CGB+SGB Enhanced).gb", 1),
    ("Gold", "Gold (U)", "gbc/Pokemon - Gold Version (USA, Europe) (SGB Enhanced) (GB Compatible).gbc", 2),
    ("Silver", "Silver (U)", "gbc/Pokemon - Silver Version (USA, Europe) (SGB Enhanced) (GB Compatible).gbc", 2),
    ("Crystal", "Crystal (U 1.1)", "gbc/Pokemon - Crystal Version (USA, Europe) (Rev 1).gbc", 2),
]
DS = [  # key, ini, section, file, script archive
    ("CPUE", "gen4", "Platinum (U Rev 1)", "nds/Pokemon - Platinum Version (USA) (Rev 1).nds", "/fielddata/script/scr_seq.narc"),
    ("IPKE", "gen4", "HeartGold (U)", "nds/Pokemon - HeartGold Version (USA).nds", "/a/0/1/2"),
    ("IPGE", "gen4", "SoulSilver (U)", "nds/Pokemon - SoulSilver Version (USA, Australia).nds", "/a/0/1/2"),
    ("IRBO", "gen5", "Black (U)", "nds/Pokemon - Black Version (USA, Europe) (NDSi Enhanced).nds", "/a/0/5/7"),
    ("IRAO", "gen5", "White (U)", "nds/Pokemon - White Version (USA, Europe) (NDSi Enhanced).nds", "/a/0/5/7"),
    ("IREO", "gen5", "Black 2 (U)", "nds/Pokemon - Black Version 2 (USA, Europe) (NDSi Enhanced).nds", "/a/0/5/6"),
    ("IRDO", "gen5", "White 2 (U)", "nds/Pokemon - White Version 2 (USA, Europe) (NDSi Enhanced).nds", "/a/0/5/6"),
]

ENTRY = re.compile(r"^StaticPokemon\{\}=\{Species=\[([^\]]*)\]")


def sections(path):
    """Each [section]'s static entries (lists of offset strings) and its CopyFrom."""
    out, cur = {}, None
    for line in open(path, encoding="utf-8", errors="replace"):
        line = line.strip()
        if line.startswith("[") and line.endswith("]"):
            cur = out.setdefault(line[1:-1], {"static": [], "from": None})
        elif cur is not None:
            m = ENTRY.match(line)
            if m:
                cur["static"].append([x.strip() for x in m.group(1).split(",") if x.strip()])
            elif line.startswith("CopyFrom="):
                cur["from"] = line.split("=", 1)[1].strip()
            elif line.startswith("Game=") and "game" not in cur:
                cur["game"] = line.split("=", 1)[1].strip()
    return out


def statics(secs, name):
    """A section's statics: its own, else (CopyStaticPokemon / no list of its own) its CopyFrom's."""
    seen = set()
    while name and name not in seen:
        seen.add(name)
        # CopyFrom names a section, or (Gen 5) a game code.
        s = secs.get(name) or next(v for v in secs.values() if v.get("game") == name)
        if s["static"]:
            return s["static"]
        name = s["from"]
    return []


def main():
    cfg, roms = sys.argv[1], sys.argv[2]
    if os.path.basename(os.path.normpath(roms)) == "gba":
        roms = os.path.dirname(os.path.normpath(roms))
    out = {}
    g3 = sections(os.path.join(cfg, "gen3_offsets.ini"))
    for key, sec, f in GBA:
        d = open(os.path.join(roms, f), "rb").read()
        keep = []
        for e in statics(g3, sec):
            at = [int(x, 16) for x in e]
            vals = {d[a] | d[a + 1] << 8 for a in at}
            if len(vals) == 1 and 0 < next(iter(vals)) <= 411:
                keep.append([next(iter(vals))] + at)
        out[key] = keep
        print(f"{key}: {len(keep)} of {len(statics(g3, sec))} statics")
    for gen in (1, 2):
        secs = sections(os.path.join(cfg, f"gen{gen}_offsets.ini"))
        for key, sec, f, g in GB:
            if g != gen:
                continue
            d = open(os.path.join(roms, f), "rb").read()
            keep = []
            for e in statics(secs, sec):
                at = [int(x, 16) for x in e]
                vals = {d[a] for a in at}
                if len(vals) == 1 and 0 < next(iter(vals)) < 0xFF:
                    keep.append([next(iter(vals))] + at)
            out[key] = keep
            print(f"{key}: {len(keep)} of {len(statics(secs, sec))} statics")
    ini = {n: sections(os.path.join(cfg, f"{n}_offsets.ini")) for n in ("gen4", "gen5")}
    for key, which, sec, f, scripts in DS:
        rom = G.Rom(os.path.join(roms, f))
        narc = rom.narc(scripts)
        keep, all_ = [], statics(ini[which], sec)
        for e in all_:
            pairs = []
            for x in e:
                if ":" not in x:
                    break
                m, o = x.split(":")
                pairs.append((int(m), int(o, 16)))
            else:
                vals = set()
                for m, o in pairs:
                    member = narc[m]
                    vals.add(member[o] | member[o + 1] << 8 if o + 1 < len(member) else -1)
                if len(vals) == 1 and 0 < next(iter(vals)) <= 649:
                    keep.append([next(iter(vals))] + [v for p in pairs for v in p])
        out[key] = keep
        print(f"{key}: {len(keep)} of {len(all_)} statics")
    dst = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..",
                       "app/src/main/kotlin/com/pokedaisy/app/companion/data/RandomizerStaticsGen.kt")
    with open(dst, "w") as w:
        w.write("// Generated by scripts/gen_randomizer_statics.py from Universal Pokemon Randomizer ZX's offsets (facts only),\n"
                "// each checked against the cart - do not edit.\n")
        w.write("package com.pokedaisy.app.companion.data\n\n")
        w.write("/**\n * RANDOMIZE's gift and static Pokémon, per cart: each one's retail species, then its species places (GBA: u16 file offsets; Game Boy: u8;\n"
                " * DS: (member, offset) pairs in its script archive, u16). Keys: GBA code + revision, Game Boy title, DS code.\n */\n")
        w.write("internal val RANDOMIZER_STATICS: Map<String, List<IntArray>> by lazy {\n    mapOf(\n")
        for key, keep in out.items():
            w.write(f'        "{key}" to listOf(\n')
            for at in keep:
                w.write("            intArrayOf(" + ", ".join("0x%X" % a if a > 9 else str(a) for a in at) + "),\n")
            w.write("        ),\n")
        w.write("    )\n}\n")
    print("wrote", dst)


if __name__ == "__main__":
    main()
