"""
Generic: make a dex method return immediately.

Usage: neutralize.py <in.apk> <out.apk> <entry.dex>:<code_off>[:<label>] ...

The delayInit path in ArticleMainActivity walks into one adapter gap after
another, each one an UnsatisfiedLinkError / NoSuchFieldError on the *main*
thread, i.e. instantly fatal.  All the victims so far start with the ByteDance
hotfix preamble

    sget-object vN, <...>->changeQuickRedirect:Lcom/bytedance/hotfix/base/ChangeQuickRedirect;

which is a 4-byte 21c instruction, so overwriting it with `return-void ; nop`
keeps every later offset and branch target byte-identical.  Only valid for
methods returning void (or whose result is ignored) -- checked by the caller.
"""
import zipfile, zlib, hashlib, struct, os, sys

def neutralize(raw, code_off, label):
    b=bytearray(raw)
    insns=code_off+16
    op=b[insns]
    assert op==0x62, f"{label}: expected sget-object (0x62) at method entry, got 0x{op:02x}"
    b[insns:insns+4]=bytes([0x0e,0x00,0x00,0x00])   # return-void ; nop
    b[12:32]=hashlib.sha1(bytes(b[32:])).digest()
    struct.pack_into('<I', b, 8, zlib.adler32(bytes(b[12:])) & 0xffffffff)
    print(f"  {label}: code_off=0x{code_off:x} entry -> return-void")
    return bytes(b)

SRC, DST = sys.argv[1], sys.argv[2]
jobs={}
for spec in sys.argv[3:]:
    parts=spec.split(':')
    entry, off = parts[0], int(parts[1],16)
    label = parts[2] if len(parts)>2 else entry
    jobs.setdefault(entry, []).append((off,label))

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
        for off,label in jobs[zi.filename]:
            raw=neutralize(raw, off, label)
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
