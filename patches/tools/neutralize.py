"""
Generic: make a dex method return immediately.

Usage: neutralize.py <in.apk> <out.apk> <entry.dex>:<code_off>[:<label>[:null]] ...

`:null` makes an object-returning method return null instead of returning void.

The delayInit path in ArticleMainActivity walks into one adapter gap after
another, each one an UnsatisfiedLinkError / NoSuchFieldError on the *main*
thread, i.e. instantly fatal.  All the victims so far start with the ByteDance
hotfix preamble

    sget-object vN, <...>->changeQuickRedirect:Lcom/bytedance/hotfix/base/ChangeQuickRedirect;

which is a 4-byte 21c instruction, so overwriting it with `return-void ; nop`
keeps every later offset and branch target byte-identical.  Only valid for
methods returning void (or whose result is ignored) -- checked by the caller.

Methods without that preamble open with some other field read instead -- an
iget/sget of their own state.  Those are 4 bytes too (21c or 22c), so the same
rewrite applies; the allowlist below is just there to refuse anything narrower
or wider, which would shift every later offset.
"""
import zipfile, zlib, hashlib, struct, os, sys

# opcode -> mnemonic, 4-byte 21c/22c instructions that commonly open a method
ENTRY_OPS = {0x22: 'new-instance', 0x52: 'iget', 0x54: 'iget-object',
             0x60: 'sget', 0x62: 'sget-object'}

# return-void ; nop            -- void methods
RET_VOID = bytes([0x0e, 0x00, 0x00, 0x00])
# const/4 v0,#0 ; return-object v0  -- reference-returning methods
RET_NULL = bytes([0x12, 0x00, 0x11, 0x00])

def neutralize(raw, code_off, label, ret_null=False):
    b=bytearray(raw)
    insns=code_off+16
    op=b[insns]
    assert op in ENTRY_OPS, (f"{label}: entry opcode 0x{op:02x} is not a 4-byte field "
                             f"access ({', '.join(ENTRY_OPS.values())}); refusing to "
                             f"rewrite, it would change the encoded width")
    if ret_null:
        # `const/4 v0` needs v0 to exist.  registers_size is the first u2 of the
        # code item; every real method has at least one register, but check.
        regs=struct.unpack_from('<H', b, code_off)[0]
        assert regs >= 1, f"{label}: registers_size={regs}, no v0 to null out"
    b[insns:insns+4]=RET_NULL if ret_null else RET_VOID
    b[12:32]=hashlib.sha1(bytes(b[32:])).digest()
    struct.pack_into('<I', b, 8, zlib.adler32(bytes(b[12:])) & 0xffffffff)
    what='return null' if ret_null else 'return-void'
    print(f"  {label}: code_off=0x{code_off:x} entry -> {what}")
    return bytes(b)

SRC, DST = sys.argv[1], sys.argv[2]
jobs={}
for spec in sys.argv[3:]:
    parts=spec.split(':')
    entry, off = parts[0], int(parts[1],16)
    label = parts[2] if len(parts)>2 else entry
    ret_null = len(parts)>3 and parts[3]=='null'
    jobs.setdefault(entry, []).append((off,label,ret_null))

zin=zipfile.ZipFile(SRC); fin=open(SRC,'rb'); out=open(DST,'wb'); central=[]
def dostime(dt): return ((dt[0]-1980)<<25)|(dt[1]<<21)|(dt[2]<<16)|(dt[3]<<11)|(dt[4]<<5)|(dt[5]//2)
for zi in zin.infolist():
    fin.seek(zi.header_offset); lh=fin.read(30)
    nlen,elen=struct.unpack_from('<HH', lh, 26)
    name=fin.read(nlen); extra=fin.read(elen); data=fin.read(zi.compress_size)
    crc, csize, usize = zi.CRC, zi.compress_size, zi.file_size
    if zi.filename in jobs:
        raw=zlib.decompress(data,-15) if zi.compress_type==zipfile.ZIP_DEFLATED else data
        print(f"patching {zi.filename}")
        for off,label,ret_null in jobs[zi.filename]:
            raw=neutralize(raw, off, label, ret_null)
        crc=zlib.crc32(raw)&0xffffffff
        c=zlib.compressobj(9,zlib.DEFLATED,-15); data=c.compress(raw)+c.flush()
        usize, csize = len(raw), len(data)
    off=out.tell(); t,dd=struct.unpack('<HH', struct.pack('<I', dostime(zi.date_time)))
    out.write(struct.pack('<IHHHHHIIIHH', 0x04034b50, zi.extract_version, zi.flag_bits & ~0x08,
                          zi.compress_type, t, dd, crc, csize, usize, len(name), len(extra)))
    out.write(name); out.write(extra); out.write(data)
    central.append((zi,name,extra,crc,csize,usize,off))
cd=out.tell()
for zi,name,extra,crc,csize,usize,off in central:
    t,dd=struct.unpack('<HH', struct.pack('<I', dostime(zi.date_time)))
    out.write(struct.pack('<IHHHHHHIIIHHHHHII', 0x02014b50, zi.create_version, zi.extract_version,
        zi.flag_bits & ~0x08, zi.compress_type, t, dd, crc, csize, usize, len(name), len(extra),
        0,0, zi.internal_attr, zi.external_attr, off))
    out.write(name); out.write(extra)
out.write(struct.pack('<IHHHHIIH', 0x06054b50,0,0,len(central),len(central),out.tell()-cd,cd,0))
out.close(); fin.close()
z=zipfile.ZipFile(DST)
print("wrote", DST, os.path.getsize(DST), "| integrity:", "OK" if z.testzip() is None else "BAD")
