import zipfile, struct, sys
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
NAMES={0x12:'const/4',0x13:'const/16',0x14:'const',0x1a:'const-string',0x1c:'const-class',
 0x22:'new-instance',0x0e:'return-void',0x70:'invoke-direct',0x71:'invoke-static',
 0x6e:'invoke-virtual',0x6f:'invoke-super',0x72:'invoke-interface',0x0c:'move-result-object',
 0x0a:'move-result',0x62:'sget-object',0x69:'sput-object',0x1f:'check-cast',0x00:'nop',
 0x27:'throw',0x11:'return-object',0x38:'if-eqz',0x39:'if-nez',0x28:'goto',0x54:'iget-object',
 0x52:'iget',0x20:'instance-of',0x21:'array-length',0x0f:'return',0x1d:'monitor-enter'}

def disasm(d,b,code_off,label):
    regs,ins,outs,tries,dbg,n=struct.unpack_from('<HHHHII', b, code_off)
    base=code_off+16; pc=0
    print(f"--- {label}  regs={regs} insns={n} ---")
    while pc<n:
        u=struct.unpack_from('<H', b, base+2*pc)[0]; op=u&0xff; w=W.get(op,1)
        if op==0x00 and (u>>8)!=0: break
        extra=''
        try:
            if op in (0x22,0x1c,0x1f,0x23,0x20):
                extra=' '+d.typ(struct.unpack_from('<H',b,base+2*(pc+1))[0])
            elif 0x6e<=op<=0x72 or 0x74<=op<=0x78:
                mi=struct.unpack_from('<H',b,base+2*(pc+1))[0]; c,nm,pr=d.meth(mi)
                extra=f' {c}->{nm}{d.proto_desc(pr)}'
            elif 0x60<=op<=0x6d:
                fi=struct.unpack_from('<H',b,base+2*(pc+1))[0]; c,t,nm=d.fld(fi)
                extra=f' {c}->{nm}:{t}'
            elif op in (0x1a,):
                extra=' "'+d.string(struct.unpack_from('<H',b,base+2*(pc+1))[0])[:60]+'"'
        except Exception as e: extra=' <?>'
        print(f'  {pc:04x}: {NAMES.get(op,hex(op)):<18}{extra}')
        pc+=w

TARGET=sys.argv[1]
WANT=sys.argv[2] if len(sys.argv)>2 else None
z=zipfile.ZipFile('/tmp/wlbuild/base.apk')
for name in sorted(n for n in z.namelist() if n.endswith('.dex')):
    b=bytearray(z.read(name)); d=Dex(b)
    co=d.find_class(TARGET)
    if co is None: continue
    print("### class in", name)
    cd=struct.unpack_from('<I', b, co+24)[0]
    o=cd
    sf,o=uleb(b,o); inf,o=uleb(b,o); dm,o=uleb(b,o); vm,o=uleb(b,o)
    for _ in range(sf+inf):
        _x,o=uleb(b,o); _y,o=uleb(b,o)
    for grp,cnt in (('direct',dm),('virtual',vm)):
        idx=0
        for _ in range(cnt):
            di,o=uleb(b,o); af,o=uleb(b,o); c2,o=uleb(b,o)
            idx+=di
            cls,nm,pr=d.meth(idx)
            sig=f"{nm}{d.proto_desc(pr)}"
            if WANT is None:
                print(f"  [{grp}] {sig} code_off=0x{c2:x}")
            elif nm==WANT and c2:
                disasm(d,b,c2,sig)
        idx=0
    break
