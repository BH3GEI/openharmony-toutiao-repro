"""Append an extra dex to the (already ColorMatrix-patched) apk.

ART's DexPathList enumerates classes.dex, classes2.dex, ... until one is
missing, so dropping in the next number in sequence gets it loaded by the app
class loader with no manifest or loader changes.
"""
import zipfile, zlib, struct, os
SRC='/tmp/wlbuild/base.patched.apk'
DST='/tmp/wlbuild/base.final.apk'
NEW='/tmp/wlbuild/classes22.dex'
NEWNAME='classes22.dex'

zin=zipfile.ZipFile(SRC)
assert NEWNAME not in zin.namelist(), "entry already present"
infos=zin.infolist()
fin=open(SRC,'rb'); out=open(DST,'wb'); central=[]

def dostime(dt):
    return ((dt[0]-1980)<<25)|(dt[1]<<21)|(dt[2]<<16)|(dt[3]<<11)|(dt[4]<<5)|(dt[5]//2)

def emit(name, data, crc, csize, usize, ctype, dt, xver=20, cver=20, iattr=0, eattr=0, extra=b''):
    off=out.tell()
    t,d=struct.unpack('<HH', struct.pack('<I', dostime(dt)))
    out.write(struct.pack('<IHHHHHIIIHH', 0x04034b50, xver, 0, ctype, t, d,
                          crc, csize, usize, len(name), len(extra)))
    out.write(name); out.write(extra); out.write(data)
    central.append((name, extra, crc, csize, usize, ctype, dt, off, xver, cver, iattr, eattr))

for zi in infos:
    fin.seek(zi.header_offset)
    lh=fin.read(30); nlen,elen=struct.unpack_from('<HH', lh, 26)
    name=fin.read(nlen); extra=fin.read(elen); data=fin.read(zi.compress_size)
    emit(name, data, zi.CRC, zi.compress_size, zi.file_size, zi.compress_type,
         zi.date_time, zi.extract_version, zi.create_version,
         zi.internal_attr, zi.external_attr, extra)

raw=open(NEW,'rb').read()
c=zlib.compressobj(9, zlib.DEFLATED, -15); comp=c.compress(raw)+c.flush()
emit(NEWNAME.encode(), comp, zlib.crc32(raw)&0xffffffff, len(comp), len(raw),
     zipfile.ZIP_DEFLATED, (2026,9,5,0,0,0))
print(f"appended {NEWNAME}: {len(raw)} -> {len(comp)} bytes")

cd_off=out.tell()
for name,extra,crc,csize,usize,ctype,dt,off,xver,cver,iattr,eattr in central:
    t,d=struct.unpack('<HH', struct.pack('<I', dostime(dt)))
    out.write(struct.pack('<IHHHHHHIIIHHHHHII', 0x02014b50, cver, xver, 0, ctype, t, d,
        crc, csize, usize, len(name), len(extra), 0, 0, iattr, eattr, off))
    out.write(name); out.write(extra)
cd_size=out.tell()-cd_off
out.write(struct.pack('<IHHHHIIH', 0x06054b50, 0, 0, len(central), len(central), cd_size, cd_off, 0))
out.close(); fin.close()
z=zipfile.ZipFile(DST)
print("wrote", DST, os.path.getsize(DST), "| entries:", len(z.infolist()),
      "| integrity:", "OK" if z.testzip() is None else "BAD")
print("dex entries:", len([n for n in z.namelist() if n.endswith('.dex')]))
