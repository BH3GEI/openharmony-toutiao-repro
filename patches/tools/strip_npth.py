"""
Drop lib/arm64-v8a/libnpth.so from the apk.

NPTH is ByteDance's crash/APM monitor.  It registers a bionic-shaped fdsan
error callback; musl's close() -> fdsan_close_with_tag invokes it with a
different stack contract and NPTH trips __stack_chk_fail, killing the process
~25s into every run.  Renaming the extracted copies does not help -- the
adapter's app_librarian re-extracts the library from base.apk on each launch --
and LD_PRELOAD interposition of close() does not reach the app namespace.
Crash reporting is inert on this board anyway (TLS is stubbed out), so remove
the library and let NPTH's Java side take its UnsatisfiedLinkError path.
"""
import zipfile, zlib, struct, os
SRC='/tmp/wlbuild/base.final2.apk'
DST='/tmp/wlbuild/base.final3.apk'
DROP={'lib/arm64-v8a/libnpth.so'}
zin=zipfile.ZipFile(SRC)
present=[n for n in zin.namelist() if 'libnpth.so' in n]
print("npth entries in apk:", present)
fin=open(SRC,'rb'); out=open(DST,'wb'); central=[]
def dostime(dt): return ((dt[0]-1980)<<25)|(dt[1]<<21)|(dt[2]<<16)|(dt[3]<<11)|(dt[4]<<5)|(dt[5]//2)
dropped=0
for zi in zin.infolist():
    fin.seek(zi.header_offset); lh=fin.read(30)
    nlen,elen=struct.unpack_from('<HH', lh, 26)
    name=fin.read(nlen); extra=fin.read(elen); data=fin.read(zi.compress_size)
    if zi.filename in DROP:
        dropped+=1; print("dropping", zi.filename); continue
    off=out.tell(); t,dd=struct.unpack('<HH', struct.pack('<I', dostime(zi.date_time)))
    out.write(struct.pack('<IHHHHHIIIHH', 0x04034b50, zi.extract_version, zi.flag_bits & ~0x08,
                          zi.compress_type, t, dd, zi.CRC, zi.compress_size, zi.file_size,
                          len(name), len(extra)))
    out.write(name); out.write(extra); out.write(data)
    central.append((zi,name,extra,off))
cd=out.tell()
for zi,name,extra,off in central:
    t,dd=struct.unpack('<HH', struct.pack('<I', dostime(zi.date_time)))
    out.write(struct.pack('<IHHHHHHIIIHHHHHII', 0x02014b50, zi.create_version, zi.extract_version,
        zi.flag_bits & ~0x08, zi.compress_type, t, dd, zi.CRC, zi.compress_size, zi.file_size,
        len(name), len(extra), 0,0, zi.internal_attr, zi.external_attr, off))
    out.write(name); out.write(extra)
out.write(struct.pack('<IHHHHIIH', 0x06054b50,0,0,len(central),len(central),out.tell()-cd,cd,0))
out.close(); fin.close()
z=zipfile.ZipFile(DST)
print(f"dropped {dropped}; wrote {DST} {os.path.getsize(DST)} | entries {len(z.infolist())} | integrity",
      "OK" if z.testzip() is None else "BAD")
