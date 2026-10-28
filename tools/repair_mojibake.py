"""Repairs a source file that was round-tripped through PowerShell 5.1 text cmdlets.

PowerShell read the UTF-8 file as ANSI (codepage 936 on a Chinese Windows) and wrote the result
back as UTF-8, so CJK characters became their GBK-misread forms. The transformation is reversible
character by character: encode each character back to cp936 and decode the resulting bytes as
UTF-8. Characters that do not survive the round trip are left untouched, and the script reports
how many those were so the result can be reviewed.
"""

import pathlib
import sys

TARGET = pathlib.Path(
    r"C:\Users\lenovo\.dsh\agy-link\media\flowreader\app\src\main\java"
    r"\com\flowreader\app\ui\screens\reader\components\ReaderSettingsSheet.kt"
)


def repair(text: str) -> tuple[str, int]:
    out: list[str] = []
    buffer = bytearray()
    failed = 0

    def flush() -> None:
        nonlocal failed
        if not buffer:
            return
        try:
            out.append(bytes(buffer).decode("utf-8"))
        except UnicodeDecodeError:
            # Not a complete UTF-8 sequence: keep the original characters.
            failed += len(buffer)
            out.append(bytes(buffer).decode("gbk", errors="replace"))
        buffer.clear()

    for char in text:
        if ord(char) < 128:
            flush()
            out.append(char)
            continue
        try:
            buffer.extend(char.encode("gbk"))
        except UnicodeEncodeError:
            flush()
            failed += 1
            out.append(char)
    flush()
    return "".join(out), failed


def main() -> int:
    raw = TARGET.read_bytes()
    text = raw.decode("utf-8-sig")
    repaired, failed = repair(text)
    repaired = repaired.replace("\r\n", "\n")
    TARGET.write_bytes(repaired.encode("utf-8"))
    print(f"bytes {len(raw)} -> {len(repaired.encode('utf-8'))}, unrecoverable chars: {failed}")
    for probe in ("排版", "翻页方式", "阅读主题", "护眼与屏幕", "字号"):
        print(f"  {probe}: {probe in repaired}")
    print("bom:", TARGET.read_bytes()[:3] == b"\xef\xbb\xbf")
    return 0


if __name__ == "__main__":
    sys.exit(main())
