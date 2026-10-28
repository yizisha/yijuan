"""Prints the lines of a source file that contain CJK text, for eyeballing a mojibake repair."""

import pathlib
import re
import sys

TARGET = pathlib.Path(sys.argv[1])
CJK = re.compile(r"[\u3000-\u9fff\uff00-\uffef]")


def main() -> int:
    text = TARGET.read_text(encoding="utf-8")
    hits = [line for line in text.splitlines() if CJK.search(line)]
    print(f"{len(hits)} lines with CJK")
    out = pathlib.Path(sys.argv[2])
    out.write_text("\n".join(hits), encoding="utf-8")
    print(f"written to {out}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
