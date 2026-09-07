#!/usr/bin/env python3
# Flip one or more UND global symbols to WEAK in an ELF64's .dynsym, so a missing
# symbol resolves to 0 at load instead of failing relocation. Isolated to the
# given .so.  Usage: elf_weaken.py in.so out.so sym [sym...]
import sys, struct

def main():
    src,dst=sys.argv[1],sys.argv[2]
    syms=set(sys.argv[3:])
    b=bytearray(open(src,'rb').read())
    assert b[:4]==b'\x7fELF' and b[4]==2, "not ELF64"
    e_shoff=struct.unpack_from('<Q',b,0x28)[0]
    e_shentsize=struct.unpack_from('<H',b,0x3a)[0]
    e_shnum=struct.unpack_from('<H',b,0x3c)[0]
    # find .dynsym (type 11) and its linked .dynstr
    dynsym=dynstr=None
    for i in range(e_shnum):
        o=e_shoff+i*e_shentsize
        sh_type=struct.unpack_from('<I',b,o+4)[0]
        if sh_type==11:  # SHT_DYNSYM
            sh_off=struct.unpack_from('<Q',b,o+0x18)[0]
            sh_size=struct.unpack_from('<Q',b,o+0x20)[0]
            sh_link=struct.unpack_from('<I',b,o+0x28)[0]
            sh_entsize=struct.unpack_from('<Q',b,o+0x38)[0]
            dynsym=(sh_off,sh_size,sh_entsize)
            lo=e_shoff+sh_link*e_shentsize
            dstr_off=struct.unpack_from('<Q',b,lo+0x18)[0]
            dstr_size=struct.unpack_from('<Q',b,lo+0x20)[0]
            dynstr=(dstr_off,dstr_size)
            break
    assert dynsym and dynstr, "no .dynsym/.dynstr"
    so,ssz,sent=dynsym; dso,dsz=dynstr
    def name(nidx):
        p=dso+nidx; e=b.index(b'\x00',p); return b[p:e].decode('latin1')
    n=ssz//sent; changed=0
    for i in range(n):
        e=so+i*sent
        st_name=struct.unpack_from('<I',b,e)[0]
        st_info=b[e+4]
        st_shndx=struct.unpack_from('<H',b,e+6)[0]
        nm=name(st_name)
        if nm in syms:
            bind=st_info>>4; typ=st_info&0xf
            und = (st_shndx==0)
            newinfo=(2<<4)|typ   # STB_WEAK
            b[e+4]=newinfo
            print(f"  {nm}: st_info 0x{st_info:02x}->0x{newinfo:02x} (bind {bind}->2 weak) UND={und}")
            changed+=1
    open(dst,'wb').write(b)
    print(f"wrote {dst}, {changed} symbol(s) weakened")

main()
