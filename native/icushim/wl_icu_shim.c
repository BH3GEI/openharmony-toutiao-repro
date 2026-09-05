/*
 * libwlicu.so — unversioned ICU entry points for ByteDance's libtttext_lite.so.
 *
 * tttext's ICUWrapper ctor does:
 *     h = dlopen("libicuuc.so"); fn = dlsym(h, "ubidi_openSized"); ...
 * i.e. it wants the *unversioned* ICU4C surface that stock Android exposes via
 * the NDK's libandroidicu.so.  This adapter ships ICU 72 with version-suffixed
 * exports only (ubidi_openSized_72, ...) and no libandroidicu.so, so every
 * dlsym returns NULL, the ctor stores NULL function pointers and the first call
 * lands on address 0:
 *
 *   Reason:Signal:SIGSEGV(SEGV_MAPERR)@0000000000000000
 *   #00 pc 0  Not mapped
 *   #01 libtttext_lite.so(ttoffice::tttext::ICUWrapper::ICUWrapper()+100)
 *
 * Each entry below is a bare tail branch to the _72 symbol, so arguments,
 * return values and varargs pass through untouched -- no signatures needed.
 * libtttext_lite.so is byte-patched to dlopen "libwlicu.so" instead (same
 * length, so the patch is in-place).
 */

/*
 * The version suffix is NOT empty: ICUWrapper's pthread_once init scans
 * /system/usr/icu, parses the digits out of the icudt<NN>l.dat filename
 * (atoi(d_name + strlen("icudt")), d_name at musl dirent offset 19), keeps the
 * max, and bails out entirely if it is < 44:
 *
 *     1acac: cmp w19, #44
 *     1acb0: b.lt <skip dlopen>          <-- no dlopen, pointers stay NULL
 *     ...    snprintf(buf, "_%d", ver)   <-- suffix
 *     1ace4: dlopen("libicui18n.so")
 *     1acfc: dlopen("libicuuc.so")       <-- handle used by every dlsym
 *   then dlsym(handle, "<name>" "<suffix>").
 *
 * That directory belongs to OpenHarmony's own ICU and holds icudt74l.dat, so
 * tttext asks for ubrk_open_74 -- but the *adapter's* Android-side ICU is
 * version 72 (/system/android/etc/icu/icudt72l.dat, libicuuc.so exporting
 * ubrk_open_72).  Every dlsym returns NULL and the first call goes to 0.
 *
 * So export the _74 names (plus the bare ones, in case a future probe path
 * comes out with an empty suffix) and tail-branch them to _72.  Bare `b`
 * keeps arguments, return value and varargs untouched, so no ICU headers or
 * signatures are needed.
 */
#define FWD2(alias, target) \
    __asm__(".text\n.globl " #alias "\n.type " #alias ", %function\n" \
            #alias ":\n\tb " #target "\n.size " #alias ", .-" #alias "\n")

#define FWD(name) FWD2(name, name##_72); FWD2(name##_74, name##_72)

FWD(u_charType);
FWD(u_hasBinaryProperty);
FWD(ubidi_close);
FWD(ubidi_getDirection);
FWD(ubidi_getLevels);
FWD(ubidi_getLogicalMap);
FWD(ubidi_getVisualMap);
FWD(ubidi_openSized);
FWD(ubidi_setPara);
FWD(ubrk_close);
FWD(ubrk_first);
FWD(ubrk_next);
FWD(ubrk_open);
FWD(ubrk_setText);
FWD(uscript_getScript);
