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
        h=struct.unpack_from('<20I', b, 56)  # from string_ids_size
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
    def fld(s,i):
        c,t,n=struct.unpack_from('<HHI', s.b, s.fld_o+8*i)
        return s.typ(c), s.typ(t), s.string(n)
    def proto_desc(s,i):
        sh,ret,par=struct.unpack_from('<III', s.b, s.pro_o+12*i)
        args=[]
        if par:
            cnt=struct.unpack_from('<I', s.b, par)[0]
            for k in range(cnt):
                args.append(s.typ(struct.unpack_from('<H', s.b, par+4+2*k)[0]))
        return '('+''.join(args)+')'+s.typ(ret)
    def find_class(s, desc):
        for i in range(s.cls_n):
            o=s.cls_o+32*i
            ci=struct.unpack_from('<I', s.b, o)[0]
            if s.typ(ci)==desc: return o
        return None

TARGET='Lcom/ss/android/image/AsyncImageView;'
z=zipfile.ZipFile('/tmp/wlbuild/base.apk')
for name in sorted(n for n in z.namelist() if n.endswith('.dex')):
    b=bytearray(z.read(name))
    d=Dex(b)
    co=d.find_class(TARGET)
    if co is None: continue
    print("FOUND in", name)
    cd_off=struct.unpack_from('<I', b, co+24)[0]
    o=cd_off
    sf,o=uleb(b,o); inf,o=uleb(b,o); dm,o=uleb(b,o); vm,o=uleb(b,o)
    # skip fields
    for _ in range(sf+inf):
        _x,o=uleb(b,o); _y,o=uleb(b,o)
    idx=0
    for _ in range(dm):
        di,o=uleb(b,o); af,o=uleb(b,o); co2,o=uleb(b,o)
        idx+=di
        cls,nm,pr=d.meth(idx)
        if nm=='<clinit>':
            print(f"  <clinit> code_off=0x{co2:x} access=0x{af:x}")
            regs,ins,outs,tries,dbg,insns_n=struct.unpack_from('<HHHHII', b, co2)
            print(f"  registers={regs} insns_size={insns_n} units, insns at 0x{co2+16:x}")
            sys.exit(0)
    print("  <clinit> NOT FOUND among", dm, "direct methods")
    sys.exit(0)
print("class not found in any dex")
