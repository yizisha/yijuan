"""Restores the few strings a GBK round trip could not reconstruct.

The bulk repair in `repair_mojibake.py` fixes every character whose original UTF-8 bytes formed a
complete GBK pair; the leftovers are names that were misaligned, so they are restored here by
matching the ASCII context around them (the ARGB literal they are keyed to, the English words in
the sentence).
"""

import pathlib
import re
import sys

TARGET = pathlib.Path(
    r"C:\Users\lenovo\.dsh\agy-link\media\flowreader\app\src\main\java"
    r"\com\flowreader\app\ui\screens\reader\components\ReaderSettingsSheet.kt"
)

# (pattern, replacement) — every pattern is anchored on ASCII so it cannot touch other text.
FIXES = [
    (r'"[^"\n]*" to 0xFFFFFFFFL', '"纯白" to 0xFFFFFFFFL'),
    (r'"[^"\n]* to 0xFFCCE8CFL', '"护眼绿" to 0xFFCCE8CFL'),
    (r'"[^"\n]*" to 0xFF000000L', '"纯黑" to 0xFF000000L'),
    (r'"[^"\n]*" to 0xFF33302AL', '"深棕" to 0xFF33302AL'),
    (r"cluttered [^a-zA-Z\n]*the reader", "cluttered — the reader"),
    (r"\* [^a-zA-Z\n]*re-measuring", "* — re-measuring"),
    (r"write [^a-zA-Z\n]*are debounced", "write — are debounced"),
    (r"Public because [^a-zA-Z\n]*字体", "Public because 设置 → 阅读 → 字体"),
    (r"controls [^a-zA-Z\n]*one implementation", "controls — one implementation"),
    (r"at all [^a-zA-Z\n]*the image is", "at all — the image is"),
]


def main() -> int:
    text = TARGET.read_text(encoding="utf-8")
    for pattern, replacement in FIXES:
        text, count = re.subn(pattern, replacement, text)
        print(f"{count:2d}  {pattern[:40]}")
    TARGET.write_text(text, encoding="utf-8")
    return 0


if __name__ == "__main__":
    sys.exit(main())
