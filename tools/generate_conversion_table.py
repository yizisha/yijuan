"""Generate a compact simplified<->traditional character table for FlowReader.

Source: OpenCC (https://github.com/BYVoid/OpenCC), Apache-2.0.
`STCharacters.txt` maps one simplified char to one or more traditional variants; we keep the first
(most common) candidate. `TSCharacters.txt` is the reverse direction. Only 1:1 char mappings are
kept, which is what makes the conversion length-preserving and therefore safe to apply at render
time without invalidating stored annotation offsets.
"""

import urllib.request

RAW = "https://raw.githubusercontent.com/BYVoid/OpenCC/master/data/dictionary/{}.txt"


def fetch(name):
    req = urllib.request.Request(RAW.format(name), headers={"User-Agent": "Mozilla/5.0"})
    with urllib.request.urlopen(req, timeout=60) as resp:
        return resp.read().decode("utf-8")


def pairs(text):
    result = {}
    for line in text.splitlines():
        line = line.strip()
        if not line or line.startswith("#"):
            continue
        parts = line.split("\t")
        if len(parts) < 2:
            continue
        src = parts[0]
        targets = parts[1].split()
        if len(src) != 1 or not targets:
            continue
        target = targets[0]
        if len(target) != 1 or target == src:
            continue
        # Characters that would need escaping inside a Kotlin literal are not worth the risk in a
        # conversion table; none of them are meaningful for Chinese text.
        if src in '\\"$' or target in '\\"$':
            continue
        # Kotlin strings are UTF-16: a supplementary-plane character takes two code units, which
        # would shift the fixed-width pair alignment in ChineseConverter.buildMap. Those characters
        # are vanishingly rare in Chinese text, so drop them instead of complicating the decoder.
        if utf16_units(src) != 1 or utf16_units(target) != 1:
            continue
        result[src] = target
    return result


def utf16_units(char):
    return len(char.encode("utf-16-le")) // 2


s2t = pairs(fetch("STCharacters"))
t2s = pairs(fetch("TSCharacters"))


def chunks(mapping, target_chars=90):
    """Emit chunks on pair boundaries so a character (incl. surrogate pairs) is never split."""
    out, current = [], ""
    for src, dst in sorted(mapping.items()):
        if len(current) + len(src) + len(dst) > target_chars:
            out.append(current)
            current = ""
        current += src + dst
    if current:
        out.append(current)
    return out


def kotlin_block(name, mapping, comment):
    pieces = chunks(mapping)
    lines = [f"    /** {comment} — {len(mapping)} pairs. */", f"    internal const val {name} ="]
    for index, piece in enumerate(pieces):
        suffix = "" if index == len(pieces) - 1 else " +"
        # ktlint (android_studio style) wants the first operand at +8 and the rest of the
        # concatenation one level deeper at +12.
        indent = "        " if index == 0 else "            "
        lines.append(f'{indent}"{piece}"{suffix}')
    return "\n".join(lines)


header = '''package com.flowreader.app.core.util

/**
 * Simplified <-> traditional character pairs, generated from OpenCC.
 *
 * Source: https://github.com/BYVoid/OpenCC (Apache-2.0), `STCharacters.txt` / `TSCharacters.txt`.
 * Only 1:1 single-character mappings are kept, taking OpenCC's first (most common) candidate for
 * ambiguous characters such as 发 -> 發 (髮 is not chosen). Because every pair is one character to
 * one character the conversion preserves length, so it can be applied purely at render time without
 * shifting stored annotation or bookmark offsets.
 *
 * This is a *reading aid*, not a full OpenCC port: phrase-level disambiguation (e.g. 头发 vs 发现)
 * is not performed.
 */
internal object ChineseConversionTable {

'''

body = "\n\n".join([
    kotlin_block("SIMPLIFIED_TO_TRADITIONAL", s2t, "Simplified -> traditional"),
    kotlin_block("TRADITIONAL_TO_SIMPLIFIED", t2s, "Traditional -> simplified"),
])

target = r"core/src/main/java/com/flowreader/app/core/util/ChineseConversionTable.kt"
with open(target, "w", encoding="utf-8", newline="\n") as handle:
    handle.write(header + body + "\n}\n")

print("s2t", len(s2t), "t2s", len(t2s), "->", target)
