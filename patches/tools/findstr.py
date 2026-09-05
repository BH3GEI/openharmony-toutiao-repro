import zipfile, struct
exec(open('/tmp/dexcore.py').read())
W={}
def setw(a,b,w):
    for o in range(a,b+1): W[o]=w
setw(0x00,0x01,1); setw(0x02,0x02,2); setw(0x03,0x03,3); setw(0x04,0x04,1)
setw(0x05,0x05,2); setw(0x06,0x06,3); setw(0x07,0x07,1); setw(0x08,0x08,2)
setw(0x09,0x09,3); setw(0x0a,0x12,1); setw(0x13,0x13,2); setw(0x14,0x14,3)
setw(0x15,0x15,2); setw(0x16,0x16,2); setw(0x17,0x17,3); setw(0x18,0x18,5)
setw(0x19,0x1a,2); setw(0x1b,0x1b,3); setw(0x1c,0x1c,2); setw(0x1d,0x1e,1)
setw(0x1f,0x20,2); setw(0x21,0x21,1); setw(0x22,0x23,2); setw(0x24,0x26,3)
setw(0x27,0x28,1); setw(0x29,0x29,2); setw(0x2a,0x2a,3); setw(0x2b,0x2c,3)
setw(0x2d,0x31,2); setw(0x32,0x3d,2); setw(0x3e,0x43,1); setw(0x44,0x6d,2)
setw(0x6e,0x72,3); setw(0x73,0x73,1); setw(0x74,0x78,3); setw(0x79,0x7a,1)
setw(0x7b,0x8f,1); setw(0x90,0xaf,2); setw(0xb0,0xcf,1); setw(0xd0,0xe2,2)
setw(0xe3,0xf9,1); setw(0xfa,0xfb,4); setw(0xfc,0xfd,3); setw(0xfe,0xff,2)
TARGET=32075
z=zipfile.ZipFile('/tmp/wlbuild/base.apk')
b=bytearray(z.read('classes16.dex')); d=Dex(b)
for ci in range(d.cls_n):
    o=d.cls_o+32*ci
    cd=struct.unpack_from('<I', b, o+24)[0]
    if not cd: continue
    desc=d.typ(struct.unpack_from('<I', b, o)[0])
    p=cd
    sf,p=uleb(b,p); inf,p=uleb(b,p); dm,p=uleb(b,p); vm,p=uleb(b,p)
    for _ in range(sf+inf):
        _x,p=uleb(b,p); _y,p=uleb(b,p)
    for grp,cnt in (('direct',dm),('virtual',vm)):
        idx=0
        for _ in range(cnt):
            di,p=uleb(b,p); af,p=uleb(b,p); co,p=uleb(b,p)
            idx+=di
            if not co: continue
            regs,ins,outs,tries,dbg,n=struct.unpack_from('<HHHHII', b, co)
            base=co+16; pc=0
            while pc<n:
                u=struct.unpack_from('<H', b, base+2*pc)[0]; op=u&0xff; w=W.get(op,1)
                if op==0x00 and (u>>8)!=0: break
                if op==0x1a:
                    si=struct.unpack_from('<H', b, base+2*(pc+1))[0]
                    if si==TARGET:
                        cls,nm,pr=d.meth(idx)
                        print(f"HIT {desc}->{nm}{d.proto_desc(pr)} code_off=0x{co:x} at pc=0x{pc:x} (file 0x{base+2*pc:x})")
                pc+=w
