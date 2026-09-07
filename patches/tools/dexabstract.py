#!/usr/bin/env python3
"""
dexabstract.py <dex-or-jar> <Lclass/Name;>

List a class's methods with access flags and fully decoded signatures.
Written for one question: what exactly does a concrete subclass of
android.webkit.WebSettings have to implement?

    python3 patches/tools/dexabstract.py framework-classes.dex.jar \
        'Landroid/webkit/WebSettings;'
"""
import zipfile, struct, sys, os

ACC = {0x1:'public',0x2:'private',0x4:'protected',0x8:'static',
       0x10:'final',0x20:'synchronized',0x400:'abstract'}

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
        params=[]
        if po:
            n=struct.unpack_from('<I', s.b, po)[0]
            for k in range(n):
                params.append(s.typ(struct.unpack_from('<H', s.b, po+4+2*k)[0]))
        return s.typ(rt), params

PRIM = {'V':'void','Z':'boolean','B':'byte','S':'short','C':'char',
        'I':'int','J':'long','F':'float','D':'double'}

def java(desc):
    dims = 0
    while desc.startswith('['):
        dims += 1; desc = desc[1:]
    base = PRIM.get(desc) or desc[1:-1].replace('/', '.')
    return base + '[]'*dims

def flags(af):
    return ' '.join(n for bit, n in sorted(ACC.items()) if af & bit)

def dexes(path):
    if path.endswith('.dex'):
        yield os.path.basename(path), bytearray(open(path,'rb').read()); return
    z = zipfile.ZipFile(path)
    for n in sorted(x for x in z.namelist() if x.endswith('.dex')):
        yield n, bytearray(z.read(n))

def main():
    path, want = sys.argv[1], sys.argv[2]
    for name, b in dexes(path):
        d = Dex(b)
        for ci in range(d.cls_n):
            o = d.cls_o + 32*ci
            if d.typ(struct.unpack_from('<I', b, o)[0]) != want: continue
            sup = d.typ(struct.unpack_from('<I', b, o+8)[0])
            print(f"# {name}: {want} extends {sup}")
            cd = struct.unpack_from('<I', b, o+24)[0]
            if not cd: print("# no class_data"); return
            p = cd
            sf,p=uleb(b,p); inf,p=uleb(b,p); dm,p=uleb(b,p); vm,p=uleb(b,p)
            for _ in range(sf+inf): _x,p=uleb(b,p); _y,p=uleb(b,p)
            for grp,cnt in (('direct',dm),('virtual',vm)):
                idx=0
                for _ in range(cnt):
                    di,p=uleb(b,p); af,p=uleb(b,p); co,p=uleb(b,p)
                    idx+=di
                    _c,nm,pr = d.meth(idx)
                    ret,params = d.proto(pr)
                    sig = ', '.join(f"{java(t)} a{k}" for k,t in enumerate(params))
                    print(f"{'ABSTRACT' if af & 0x400 else 'concrete'}\t{flags(af)}\t"
                          f"{java(ret)}\t{nm}({sig})")
                idx=0
            return
    print("class not found", file=sys.stderr); sys.exit(1)

main()
