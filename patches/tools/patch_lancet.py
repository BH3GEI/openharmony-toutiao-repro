"""
Short-circuit PrivateApiLancetImpl.<clinit>.

Once the app gets past the consent gate it waits for the feed, and
onWaitFeedTimeout -> delayInitNew -> checkAccessibilityService touches
com.bytedance.bdauditsdkbase.privacy.hook.PrivateApiLancetImpl for the first
time.  Its <clinit> builds two privacy blocklists and reads eight
MediaStore.*_CONTENT_URI fields:

  0x002a sget-object MediaStore$Images$Media->EXTERNAL_CONTENT_URI
  0x0033 sget-object MediaStore$Images$Media->INTERNAL_CONTENT_URI
  0x003c/0x0045 MediaStore$Audio$Media  external/internal
  0x004e/0x0057 MediaStore$Video$Media  external/internal
  0x0066/0x006f MediaStore$Downloads    external/internal

adapter-mainline-stubs.jar does not carry those fields, so the first one throws
NoSuchFieldError.  On background threads the adapter's W-ROOM-SURVIVE swallowed
it ("<clinit> failed for class ...; see exception in other thread"), but on this
path it lands on the main thread and kills it -- right as the fallback first
frame was about to be produced.

Patch: make instruction 0 `return-void`.  The method already has an early
`return-void` at 0x0011 for the hotfix fast path, so this just takes that exit
immediately.  The 4-byte sget-object is replaced by return-void + nop, so every
subsequent offset and branch target is untouched.

Consequence: QUERY_CONTROL_URI / QUERY_CONTROL_AUTHORITY stay empty, i.e. the
privacy hook stops intercepting those URIs.  That is strictly no worse than
today -- the class currently fails initialisation altogether, so the sets are
already unusable -- and this board has no telephony/contacts/media providers.
"""
import zipfile, zlib, hashlib, struct, os

SRC='/tmp/wlbuild/base.final3.apk'
DST='/tmp/wlbuild/base.final4.apk'
ENTRY='classes20.dex'
CODE_OFF=0x70ba5c
INSNS=CODE_OFF+16                      # 0x70ba6c
OLD=bytes([0x62,0x03,0xb6,0x62])       # sget-object v3, changeQuickRedirect
NEW=bytes([0x0e,0x00,0x00,0x00])       # return-void ; nop

def patch(raw):
    b=bytearray(raw)
    got=bytes(b[INSNS:INSNS+4])
    assert got==OLD, f"first insn mismatch: {got.hex()} != {OLD.hex()}"
    b[INSNS:INSNS+4]=NEW
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
        print(f"patched {ENTRY}: <clinit>@0x{CODE_OFF:x} insn0 -> return-void")
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
