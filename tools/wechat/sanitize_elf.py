#!/usr/bin/env python3
# Faithful reimplementation of westlake bionic_compat/tools/sanitize_android_elf.c:
# trim trailing null/-1 sentinels from DT_INIT_ARRAY / DT_FINI_ARRAY (which bionic
# skips but OHOS-musl calls -> null jump), by shrinking the DT_*_ARRAYSZ tag.
# Aborts (leaves file unchanged) on embedded null/-1 or if a trailing sentinel is
# relocated (real constructor). Honors DT_RELA and DT_RELR (safer than the C tool).
#   sanitize_elf.py in.so out.so   ->  exit 0 = trimmed, 3 = nothing to do, 1 = abort
import sys, struct

DT_RELA=7; DT_RELASZ=8; DT_INIT_ARRAY=25; DT_FINI_ARRAY=26
DT_INIT_ARRAYSZ=27; DT_FINI_ARRAYSZ=28; DT_RELR=36; DT_RELRSZ=35
DT_ANDROID_RELA=0x60000011; DT_ANDROID_RELASZ=0x60000012
U64=0xffffffffffffffff

def die(msg): print("  abort:", msg, file=sys.stderr); sys.exit(1)

def main():
    src,dst=sys.argv[1],sys.argv[2]
    b=bytearray(open(src,'rb').read())
    if b[:4]!=b'\x7fELF' or b[4]!=2 or b[5]!=1 or struct.unpack_from('<H',b,18)[0]!=0xB7:
        die("not little-endian ELF64 AArch64")
    e_phoff=struct.unpack_from('<Q',b,0x20)[0]
    e_phentsize=struct.unpack_from('<H',b,0x36)[0]; e_phnum=struct.unpack_from('<H',b,0x38)[0]
    phdrs=[]
    for i in range(e_phnum):
        o=e_phoff+i*e_phentsize
        p_type=struct.unpack_from('<I',b,o)[0]
        p_offset=struct.unpack_from('<Q',b,o+8)[0]; p_vaddr=struct.unpack_from('<Q',b,o+16)[0]
        p_filesz=struct.unpack_from('<Q',b,o+32)[0]
        phdrs.append((p_type,p_offset,p_vaddr,p_filesz))
    def v2o(vaddr,need):
        for (t,off,va,fsz) in phdrs:
            if t==1 and va<=vaddr<va+fsz and vaddr+need<=va+fsz:  # PT_LOAD
                return off+(vaddr-va)
        die("vaddr 0x%x not in a file LOAD segment"%vaddr)
    dyn=next((p for p in phdrs if p[0]==2),None)  # PT_DYNAMIC
    if not dyn: die("no PT_DYNAMIC")
    doff=dyn[1]; dcount=dyn[3]//16
    tags={}; sz_tag_off={}
    for i in range(dcount):
        o=doff+i*16
        tag=struct.unpack_from('<q',b,o)[0]; val=struct.unpack_from('<Q',b,o+8)[0]
        if tag==0: break
        tags[tag]=val
        if tag in (DT_INIT_ARRAYSZ,DT_FINI_ARRAYSZ): sz_tag_off[tag]=o+8
    # collect relocated addresses (RELA + RELR)
    reloc=set()
    if DT_RELA in tags and DT_RELASZ in tags and tags[DT_RELA]:
        ro=v2o(tags[DT_RELA],tags[DT_RELASZ])
        for k in range(tags[DT_RELASZ]//24):
            reloc.add(struct.unpack_from('<Q',b,ro+k*24)[0])
    if DT_ANDROID_RELA in tags and tags.get(DT_ANDROID_RELASZ):
        # packed format not decoded; be conservative -> mark presence
        die("DT_ANDROID_RELA present (packed relocs not decoded); refuse to trim")
    if DT_RELR in tags and tags.get(DT_RELRSZ):
        rro=v2o(tags[DT_RELR],tags[DT_RELRSZ]); base=0
        for k in range(tags[DT_RELRSZ]//8):
            e=struct.unpack_from('<Q',b,rro+k*8)[0]
            if e&1==0:
                reloc.add(e); base=e+8
            else:
                bit=base
                e>>=1
                while e:
                    if e&1: reloc.add(bit)
                    bit+=8; e>>=1
                base+=63*8
    removed_total=0
    def trim(arr_tag,sz_tag,name):
        nonlocal removed_total
        if arr_tag not in tags or sz_tag not in tags: return
        addr=tags[arr_tag]; size=tags[sz_tag]
        if addr==0 or size==0: return
        if size%8: die(name+" size malformed")
        off=v2o(addr,size); n=size//8
        vals=[struct.unpack_from('<Q',b,off+j*8)[0] for j in range(n)]
        def is_sentinel(j):
            # true sentinel: file value null/-1 AND no relocation at that slot.
            # (RELATIVE-relocated slots are file-0 too but are real constructors.)
            return vals[j] in (0,U64) and (addr+j*8) not in reloc
        retained=n
        while retained>0 and is_sentinel(retained-1): retained-=1
        if retained==n: return
        for j in range(retained):
            if is_sentinel(j): die(name+" has embedded null/-1 sentinel")
        struct.pack_into('<Q',b,sz_tag_off[sz_tag],retained*8)
        rem=n-retained; removed_total+=rem
        print("  trimmed %d trailing null/-1 %s (reloc-aware)"%(rem,name))
    trim(DT_INIT_ARRAY,DT_INIT_ARRAYSZ,"DT_INIT_ARRAY")
    trim(DT_FINI_ARRAY,DT_FINI_ARRAYSZ,"DT_FINI_ARRAY")
    if removed_total==0: sys.exit(3)
    open(dst,'wb').write(b); sys.exit(0)

main()
