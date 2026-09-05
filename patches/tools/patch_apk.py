"""
Neutralise com.ss.android.image.AsyncImageView.<clinit>'s ColorMatrixColorFilter.

The adapter's BCP stub android.graphics.ColorMatrix has no set(float[]), so
framework's ColorMatrixColorFilter.<init>([F)V throws NoSuchMethodError, the
<clinit> dies, and every layout containing an AsyncImageView (i.e. the whole
Toutiao feed) fails to inflate.  The field being initialised is
    static ColorFilter mNightColorFilter
a night-mode grey-scale tint.  Storing null there is well-defined -- Paint
.setColorFilter(null) simply applies no filter -- so we rewrite:

    new-instance  v1, ColorMatrixColorFilter   ->  const/4 v1, #0 ; nop
    invoke-direct {v1,v0}, <init>([F)V         ->  nop ; nop ; nop

Instruction count and every branch/payload offset stay byte-identical.
"""
import zipfile, zlib, hashlib, struct, shutil, os, sys

SRC='/tmp/wlbuild/base.apk'
DST='/tmp/wlbuild/base.patched.apk'
ENTRY='classes6.dex'
INSNS=0x7e4c7c
OLD_NEW  = bytes([0x22,0x01,0x03,0x21])
NEW_NEW  = bytes([0x12,0x01,0x00,0x00])
OLD_CALL = bytes([0x70,0x20,0xd2,0x5e,0x01,0x00])
NEW_CALL = bytes([0x00]*6)

def patch_dex(b):
    b=bytearray(b)
    o1=INSNS+2*0x12
    o2=INSNS+2*0x1b
    assert bytes(b[o1:o1+4])==OLD_NEW,  f"new-instance mismatch: {bytes(b[o1:o1+4]).hex()}"
    assert bytes(b[o2:o2+6])==OLD_CALL, f"invoke-direct mismatch: {bytes(b[o2:o2+6]).hex()}"
    b[o1:o1+4]=NEW_NEW
    b[o2:o2+6]=NEW_CALL
    # dex signature = SHA-1 over bytes[32:], checksum = adler32 over bytes[12:]
    sig=hashlib.sha1(bytes(b[32:])).digest()
    b[12:32]=sig
    struct.pack_into('<I', b, 8, zlib.adler32(bytes(b[12:])) & 0xffffffff)
    return bytes(b)

zin=zipfile.ZipFile(SRC)
infos=zin.infolist()
fin=open(SRC,'rb')
out=open(DST,'wb')
central=[]
for zi in infos:
    fin.seek(zi.header_offset)
    lh=fin.read(30)
    assert lh[:4]==b'PK\x03\x04', "bad local header"
    nlen,elen=struct.unpack_from('<HH', lh, 26)
    name=fin.read(nlen); extra=fin.read(elen)
    data=fin.read(zi.compress_size)

    if zi.filename==ENTRY:
        raw=zlib.decompress(data,-15) if zi.compress_type==zipfile.ZIP_DEFLATED else data
        raw=patch_dex(raw)
        crc=zlib.crc32(raw)&0xffffffff
        if zi.compress_type==zipfile.ZIP_DEFLATED:
            c=zlib.compressobj(9,zlib.DEFLATED,-15); data=c.compress(raw)+c.flush()
        else:
            data=raw
        usize=len(raw); csize=len(data)
        print(f"patched {ENTRY}: {usize} bytes uncompressed, {csize} compressed")
    else:
        crc=zi.CRC; usize=zi.file_size; csize=zi.compress_size

    off=out.tell()
    flags=zi.flag_bits & ~0x08          # no data descriptor; sizes are in the header
    hdr=struct.pack('<IHHHHHIIIHH', 0x04034b50, zi.extract_version, flags,
                    zi.compress_type, *struct.unpack('<HH', struct.pack('<I',
                        ((zi.date_time[0]-1980)<<25)|(zi.date_time[1]<<21)|(zi.date_time[2]<<16)|
                        (zi.date_time[3]<<11)|(zi.date_time[4]<<5)|(zi.date_time[5]//2))),
                    crc, csize, usize, len(name), len(extra))
    out.write(hdr); out.write(name); out.write(extra); out.write(data)
    central.append((zi, off, crc, csize, usize, name, extra, flags))

cd_off=out.tell()
for zi,off,crc,csize,usize,name,extra,flags in central:
    dt=((zi.date_time[0]-1980)<<25)|(zi.date_time[1]<<21)|(zi.date_time[2]<<16)|\
       (zi.date_time[3]<<11)|(zi.date_time[4]<<5)|(zi.date_time[5]//2)
    t,d=struct.unpack('<HH', struct.pack('<I', dt))
    out.write(struct.pack('<IHHHHHHIIIHHHHHII', 0x02014b50, zi.create_version,
        zi.extract_version, flags, zi.compress_type, t, d, crc, csize, usize,
        len(name), len(extra), 0, 0, zi.internal_attr, zi.external_attr, off))
    out.write(name); out.write(extra)
cd_size=out.tell()-cd_off
out.write(struct.pack('<IHHHHIIH', 0x06054b50, 0, 0, len(central), len(central), cd_size, cd_off, 0))
out.close(); fin.close()
print("wrote", DST, os.path.getsize(DST), "bytes")
z2=zipfile.ZipFile(DST); bad=z2.testzip()
print("zip integrity:", "OK" if bad is None else f"BAD at {bad}", "| entries:", len(z2.infolist()))
