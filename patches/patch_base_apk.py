#!/usr/bin/env python3
"""
patch_base_apk.py -- one shot: pristine Toutiao base.apk  ->  base.final6.apk

Applies every app-side fix needed to get 今日头条 (com.ss.android.article.news)
to render its MainActivity first frame on the OpenHarmony a2oh adapter.

    python3 patches/patch_base_apk.py <input base.apk> [-o out.apk]

Six edits, all byte-length preserving except the two zip-level ones:

  classes6.dex   AsyncImageView.<clinit>            ColorMatrix
  classes8.dex   HeadsetHelperOpt.p()V              AudioPortEventHandler JNI
  classes15.dex  ArticleMainActivity.delayInit()V   delayInit landmine chain
  classes16.dex  X/4Li.a() "appName is empty" guard AppLog init
  classes20.dex  PrivateApiLancetImpl.<clinit>      MediaStore fields
  + add    classes22.dex                            conscrypt presence shim
  - remove lib/arm64-v8a/libnpth.so                  fdsan ABI clash

Why byte-length preserving matters: every dex patch replaces an instruction with
another of the *same encoded width* (a 4-byte 21c `sget-object` becomes
`return-void; nop`, a 4-byte 21t `if-nez` becomes two `nop`s, ...).  Instruction
count, branch targets and every later offset therefore stay identical, so no
structural dex rewriting -- and no dex verifier surprises.

After editing a dex we recompute its SHA-1 (over bytes[32:]) and adler32 (over
bytes[12:]).  Untouched zip entries are copied as raw compressed streams, so a
138 MB apk repacks in a couple of seconds and 24 800 entries stay bit-identical.

Reproducibility: with the reference input this produces an apk whose per-entry
contents are identical to the verified base.final6.apk.  Check with --verify.
"""

import argparse
import hashlib
import os
import struct
import sys
import zipfile
import zlib

HERE = os.path.dirname(os.path.abspath(__file__))
SHIM_DEX = os.path.join(HERE, "prebuilt", "classes22.dex")

# sha256 of the pristine base.apk this patch set was derived from.
REFERENCE_INPUT_SHA256 = "b6423fdc6d30c07e7f8316755d0657abd184d66aac99891ec81d555880b3e48b"

NOP2 = bytes([0x00, 0x00])
RETURN_VOID = bytes([0x0E, 0x00])

# entry -> list of (file offset, expected original bytes, replacement, description)
DEX_PATCHES = {
    "classes6.dex": [
        (0x7E4CA0, bytes([0x22, 0x01, 0x03, 0x21]), bytes([0x12, 0x01, 0x00, 0x00]),
         "AsyncImageView.<clinit>: new-instance ColorMatrixColorFilter -> const/4 v1,#0 ; nop"),
        (0x7E4CB2, bytes([0x70, 0x20, 0xD2, 0x5E, 0x01, 0x00]), NOP2 * 3,
         "AsyncImageView.<clinit>: invoke-direct <init>([F)V -> nop x3 (field ends up null)"),
    ],
    "classes8.dex": [
        (0x74BB08, bytes([0x62, 0x02, 0x8E, 0x7A]), RETURN_VOID + NOP2,
         "HeadsetHelperOpt.p()V: entry -> return-void ; nop"),
    ],
    "classes15.dex": [
        (0x7D63FC, bytes([0x62, 0x02, 0xA4, 0x7E]), RETURN_VOID + NOP2,
         "ArticleMainActivity.delayInit()V: entry -> return-void ; nop"),
    ],
    "classes16.dex": [
        (0x526E6E, bytes([0x39, 0x00, 0x5A, 0x00]), NOP2 * 2,
         'X/4Li.a(): if-nez v0,+90 (TextUtils.isEmpty(appName)) -> nop x2'),
    ],
    "classes20.dex": [
        (0x70BA6C, bytes([0x62, 0x03, 0xB6, 0x62]), RETURN_VOID + NOP2,
         "PrivateApiLancetImpl.<clinit>: entry -> return-void ; nop"),
    ],
}

ADD_ENTRY = "classes22.dex"
DROP_ENTRIES = {"lib/arm64-v8a/libnpth.so"}


def reseal_dex(raw: bytes) -> bytes:
    b = bytearray(raw)
    b[12:32] = hashlib.sha1(bytes(b[32:])).digest()
    struct.pack_into("<I", b, 8, zlib.adler32(bytes(b[12:])) & 0xFFFFFFFF)
    return bytes(b)


def patch_dex(entry: str, raw: bytes, verbose: bool) -> bytes:
    b = bytearray(raw)
    for off, old, new, what in DEX_PATCHES[entry]:
        assert len(old) == len(new), f"{entry}: replacement must keep the encoded width"
        got = bytes(b[off:off + len(old)])
        if got != old:
            raise SystemExit(
                f"[FAIL] {entry} @0x{off:x}: expected {old.hex()} but found {got.hex()}.\n"
                f"       The input apk is not the expected build -- see REFERENCE_INPUT_SHA256."
            )
        b[off:off + len(new)] = new
        if verbose:
            print(f"    0x{off:08x}  {old.hex()} -> {new.hex()}  {what}")
    return reseal_dex(bytes(b))


def dos_time(dt):
    return ((dt[0] - 1980) << 25) | (dt[1] << 21) | (dt[2] << 16) | \
           (dt[3] << 11) | (dt[4] << 5) | (dt[5] // 2)


def repack(src: str, dst: str, verbose: bool) -> None:
    zin = zipfile.ZipFile(src)
    present = set(zin.namelist())
    for entry in DEX_PATCHES:
        if entry not in present:
            raise SystemExit(f"[FAIL] input apk has no {entry}")
    if ADD_ENTRY in present:
        raise SystemExit(f"[FAIL] input apk already contains {ADD_ENTRY} -- already patched?")
    if not os.path.exists(SHIM_DEX):
        raise SystemExit(f"[FAIL] missing {SHIM_DEX} (conscrypt shim dex)")

    fin = open(src, "rb")
    out = open(dst, "wb")
    central = []

    def emit(name, extra, data, crc, csize, usize, ctype, dt, xver, cver, iattr, eattr):
        off = out.tell()
        t, d = struct.unpack("<HH", struct.pack("<I", dos_time(dt)))
        out.write(struct.pack("<IHHHHHIIIHH", 0x04034B50, xver, 0, ctype, t, d,
                              crc, csize, usize, len(name), len(extra)))
        out.write(name)
        out.write(extra)
        out.write(data)
        central.append((name, extra, crc, csize, usize, ctype, dt, off,
                        xver, cver, iattr, eattr))

    for zi in zin.infolist():
        fin.seek(zi.header_offset)
        lh = fin.read(30)
        nlen, elen = struct.unpack_from("<HH", lh, 26)
        name = fin.read(nlen)
        extra = fin.read(elen)
        data = fin.read(zi.compress_size)

        if zi.filename in DROP_ENTRIES:
            print(f"  - drop   {zi.filename}")
            continue

        crc, csize, usize = zi.CRC, zi.compress_size, zi.file_size
        if zi.filename in DEX_PATCHES:
            print(f"  * patch  {zi.filename}")
            raw = zlib.decompress(data, -15) if zi.compress_type == zipfile.ZIP_DEFLATED else data
            raw = patch_dex(zi.filename, raw, verbose)
            crc = zlib.crc32(raw) & 0xFFFFFFFF
            if zi.compress_type == zipfile.ZIP_DEFLATED:
                c = zlib.compressobj(9, zlib.DEFLATED, -15)
                data = c.compress(raw) + c.flush()
            else:
                data = raw
            usize, csize = len(raw), len(data)

        emit(name, extra, data, crc, csize, usize, zi.compress_type, zi.date_time,
             zi.extract_version, zi.create_version, zi.internal_attr, zi.external_attr)

    shim = open(SHIM_DEX, "rb").read()
    c = zlib.compressobj(9, zlib.DEFLATED, -15)
    comp = c.compress(shim) + c.flush()
    print(f"  + add    {ADD_ENTRY} ({len(shim)} bytes -> {len(comp)})")
    emit(ADD_ENTRY.encode(), b"", comp, zlib.crc32(shim) & 0xFFFFFFFF,
         len(comp), len(shim), zipfile.ZIP_DEFLATED, (2026, 9, 5, 0, 0, 0), 20, 20, 0, 0)

    cd = out.tell()
    for (name, extra, crc, csize, usize, ctype, dt, off,
         xver, cver, iattr, eattr) in central:
        t, d = struct.unpack("<HH", struct.pack("<I", dos_time(dt)))
        out.write(struct.pack("<IHHHHHHIIIHHHHHII", 0x02014B50, cver, xver, 0, ctype,
                              t, d, crc, csize, usize, len(name), len(extra),
                              0, 0, iattr, eattr, off))
        out.write(name)
        out.write(extra)
    out.write(struct.pack("<IHHHHIIH", 0x06054B50, 0, 0, len(central), len(central),
                          out.tell() - cd, cd, 0))
    out.close()
    fin.close()


def main():
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("input", help="pristine com.ss.android.article.news base.apk")
    ap.add_argument("-o", "--output", default="base.final6.apk")
    ap.add_argument("-v", "--verbose", action="store_true", help="show every byte edit")
    ap.add_argument("--verify", metavar="REF_APK",
                    help="compare per-entry contents against a reference apk")
    ap.add_argument("--force", action="store_true",
                    help="proceed even if the input sha256 is unexpected")
    args = ap.parse_args()

    h = hashlib.sha256()
    with open(args.input, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    got = h.hexdigest()
    print(f"input : {args.input}\n  sha256 {got}")
    if got != REFERENCE_INPUT_SHA256:
        msg = ("  !! not the reference build (expected "
               f"{REFERENCE_INPUT_SHA256}).\n"
               "     Offsets are build specific; regenerate them with\n"
               "     patches/tools/dexfind.py + dexdis.py before continuing.")
        if not args.force:
            raise SystemExit(msg + "\n     Re-run with --force to try anyway.")
        print(msg)

    print(f"output: {args.output}")
    repack(args.input, args.output, args.verbose)

    z = zipfile.ZipFile(args.output)
    bad = z.testzip()
    print(f"\nresult: {os.path.getsize(args.output)} bytes, {len(z.infolist())} entries, "
          f"integrity {'OK' if bad is None else 'BAD at ' + bad}")
    oh = hashlib.sha256(open(args.output, "rb").read()).hexdigest()
    print(f"  sha256 {oh}")

    if args.verify:
        ref = zipfile.ZipFile(args.verify)
        a = {n: hashlib.sha256(z.read(n)).hexdigest() for n in z.namelist()}
        b = {n: hashlib.sha256(ref.read(n)).hexdigest() for n in ref.namelist()}
        diff = [n for n in sorted(set(a) | set(b)) if a.get(n) != b.get(n)]
        if diff:
            print(f"\n[VERIFY] {len(diff)} entry/entries differ from {args.verify}:")
            for n in diff[:20]:
                print("   ", n)
            sys.exit(1)
        print(f"\n[VERIFY] all {len(a)} entries identical to {args.verify}  ✓")


if __name__ == "__main__":
    main()
