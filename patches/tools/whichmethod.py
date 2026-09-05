import zipfile, struct, sys
exec(open('/tmp/dexcore.py').read())
apk, entry, target_insns = sys.argv[1], sys.argv[2], int(sys.argv[3],16)
code_off = target_insns - 16
b=bytearray(zipfile.ZipFile(apk).read(entry)); d=Dex(b)
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
            if co==code_off:
                cls,nm,pr=d.meth(idx)
                print(f"MATCH {desc}->{nm}{d.proto_desc(pr)}  [{grp}] code_off=0x{co:x}")
                sys.exit(0)
        idx=0
print("no method with that code_off")
