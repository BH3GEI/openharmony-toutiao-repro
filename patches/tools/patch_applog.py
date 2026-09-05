"""
Let AppLog initialise with an empty appName.

Once the consent gate is passed, MainActivity.onCreate dies in a lifecycle
observer:

  Failed to call observer method
  Caused by: java.lang.IllegalArgumentException: appName is empty
      at X.4Li.a  (com.bytedance.bdinstall.Builder.build)
      at com.bytedance.applog.bdinstall.BdInstallImpl.init
      ... AppLogInitiator.doInit <- AppInitHook.tryInit <- onActivityPostCreated

X/4Li.a() (classes16.dex, code_off=0x526dd4):
  0x0041 invoke-static TextUtils.isEmpty(appName)
  0x0044 move-result v0
  0x0045 if-nez v0, +90   -> 0x9f: throw new IllegalArgumentException("appName is empty")

Replace the guard with two nops (same 4 bytes, format 21t -> 2x 10x) so the
build always falls through.  Analytics then registers without an app name,
which is inert here: this adapter stubs TLS out entirely
("TLS shim: no real networking (construct-only SSLContext on OH)"), so AppLog
cannot report anywhere regardless.
"""
import zipfile, zlib, hashlib, struct, os

SRC='/tmp/wlbuild/base.final.apk'
DST='/tmp/wlbuild/base.final2.apk'
ENTRY='classes16.dex'
OFF=0x526e6e
OLD=bytes([0x39,0x00,0x5a,0x00])   # if-nez v0, +0x5a
NEW=bytes([0x00,0x00,0x00,0x00])   # nop ; nop

def patch(raw):
    b=bytearray(raw)
    assert bytes(b[OFF:OFF+4])==OLD, f"guard mismatch: {bytes(b[OFF:OFF+4]).hex()}"
    b[OFF:OFF+4]=NEW
    b[12:32]=hashlib.sha1(bytes(b[32:])).digest()
    struct.pack_into('<I', b, 8, zlib.adler32(bytes(b[12:])) & 0xffffffff)
    return bytes(b)

zin=zipfile.ZipFile(SRC); fin=open(SRC,'rb'); out=open(DST,'wb'); central=[]
def dostime(dt): return ((dt[0]-1980)<<25)|(dt[1]<<21)|(dt[2]<<16)|(dt[3]<<11)|(dt[4]<<5)|(dt[5]//2)
for zi in zin.infolist():
    fin.seek(zi.header_offset); lh=fin.read(30)
    nlen,elen=struct.unpack_from('<HH', lh, 26)
    name=fin.read(nlen); extra=fin.read(elen); data=fin.read(zi.compress_size)
    crc, csize, usize = zi.CRC, zi.compress_size, zi.file_size
    if zi.filename==ENTRY:
        raw=zlib.decompress(data,-15) if zi.compress_type==zipfile.ZIP_DEFLATED else data
        raw=patch(raw); crc=zlib.crc32(raw)&0xffffffff
        c=zlib.compressobj(9,zlib.DEFLATED,-15); data=c.compress(raw)+c.flush()
        usize, csize = len(raw), len(data)
        print(f"patched {ENTRY}")
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
print("wrote", DST, os.path.getsize(DST), "| entries:", len(z.infolist()),
      "| integrity:", "OK" if z.testzip() is None else "BAD")
