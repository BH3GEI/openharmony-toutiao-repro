#!/usr/bin/env python3
"""
findmethod.py <apk> <Lclass/Name;> <method|*>

Print every matching method with its code_off, return type, and the first
bytes of its instruction stream -- i.e. everything neutralize.py needs.

    python3 patches/tools/findmethod.py base.apk 'Lcom/x/Y;' '*'
"""
import zipfile, struct, sys

def uleb(b, o):
    r=0; s=0
    while True:
        x=b[o]; o+=1; r |= (x&0x7f)<<s; s+=7
        if not (x&0x80): break
    return r,o

class Dex:
    def __init__(s, b):
        s.b=b
        h=struct.unpack_from('<20I', b, 56)
        (s.str_n,s.str_o,s.typ_n,s.typ_o,s.pro_n,s.pro_o,s.fld_n,s.fld_o,
         s.mth_n,s.mth_o,s.cls_n,s.cls_o,s.dat_n,s.dat_o)=h[:14]
    def string(s,i):
        off=struct.unpack_from('<I', s.b, s.str_o+4*i)[0]
        n,off=uleb(s.b,off)
        e=s.b.index(b'\x00',off)
        return s.b[off:e].decode('utf-8','replace')
    def typ(s,i): return s.string(struct.unpack_from('<I', s.b, s.typ_o+4*i)[0])
    def meth(s,i):
        c,p,n=struct.unpack_from('<HHI', s.b, s.mth_o+8*i)
        return s.typ(c), s.string(n), p
    def proto(s,i):
        sh,rt,po=struct.unpack_from('<III', s.b, s.pro_o+12*i)
        return s.typ(rt), s.string(sh)

def main():
    apk, want_cls, want_m = sys.argv[1], sys.argv[2], sys.argv[3]
    z=zipfile.ZipFile(apk)
    hits=0
    for entry in sorted(n for n in z.namelist()
                        if n.startswith('classes') and n.endswith('.dex')):
        b=bytearray(z.read(entry)); d=Dex(b)
        for ci in range(d.cls_n):
            o=d.cls_o+32*ci
            if d.typ(struct.unpack_from('<I', b, o)[0]) != want_cls: continue
            cd=struct.unpack_from('<I', b, o+24)[0]
            if not cd: continue
            p=cd
            sf,p=uleb(b,p); inf,p=uleb(b,p); dm,p=uleb(b,p); vm,p=uleb(b,p)
            for _ in range(sf+inf):
                _x,p=uleb(b,p); _y,p=uleb(b,p)
            for grp,cnt in (('direct',dm),('virtual',vm)):
                idx=0
                for _ in range(cnt):
                    di,p=uleb(b,p); af,p=uleb(b,p); co,p=uleb(b,p)
                    idx+=di
                    cls,nm,pr=d.meth(idx)
                    if want_m!='*' and nm!=want_m: continue
                    ret,shorty=d.proto(pr)
                    ins=co+16 if co else 0
                    regs = struct.unpack_from('<H', b, co)[0] if co else 0
                    print(f"{entry} {want_cls}->{nm}  ret={ret} shorty={shorty} [{grp}]\n"
                          f"    code_off=0x{co:x} insns@0x{ins:x} regs={regs} "
                          f"first8={bytes(b[ins:ins+8]).hex() if co else ''}")
                    hits+=1
                idx=0
    if not hits:
        print("no match", file=sys.stderr); sys.exit(1)

main()
