"""Redirect libtttext_lite.so's ICU dlopen to our unversioned-symbol shim.

Equal-length names, so this is a pure in-place byte patch -- no ELF surgery.
"""
SRC='/tmp/wlbuild/libtttext_lite.so'
DST='/tmp/wlbuild/libtttext_lite.patched.so'
PAIRS=[(b'libicuuc.so\x00',   b'libwlicu.so\x00'),
       (b'libicui18n.so\x00', b'libwlic18n.so\x00')]
b=bytearray(open(SRC,'rb').read())
for old,new in PAIRS:
    assert len(old)==len(new), (old,new)
    n=b.count(old)
    print(f"{old[:-1].decode()} -> {new[:-1].decode()}: {n} occurrence(s)")
    assert n>0
    b=bytearray(bytes(b).replace(old,new))
open(DST,'wb').write(bytes(b))
print("wrote", DST, len(b), "bytes (size unchanged:", len(b)==len(open(SRC,'rb').read()), ")")
