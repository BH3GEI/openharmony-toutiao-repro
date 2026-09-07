#!/usr/bin/env python3
"""
list_methods.py <Java source> [...]

Inventory the methods a source file declares, for the freeze manifest.

Deliberately narrow: it only accepts declarations at one indent level inside
the class, which is exactly how ActivityManagerRouting is written.  Signatures
that wrap across lines and generic return types with commas (Map<String,
Integer>) both occur there, so the whole file is read and logical declarations
are rejoined before matching -- an earlier line-at-a-time version silently
dropped five real methods, which is worse than reporting none.
"""
import re, sys, os

DECL = re.compile(
    r'^    (?:@Override\s+)?'
    r'((?:public|private|protected|static|final|synchronized|volatile|abstract|native)\s+'
    r'(?:public|private|protected|static|final|synchronized|volatile|abstract|native|\s)*)'
    r'([\w.$]+(?:<[^()]*?>)?(?:\[\])*)\s+'
    r'(\w+)\s*\(([^;{]*?)\)\s*'
    r'(?:throws [\w.,\s]+?)?\{', re.S)

KEYWORDS = {'if', 'for', 'while', 'switch', 'catch', 'synchronized', 'return', 'new'}

def logical_lines(text):
    """Join a declaration that wraps until its opening brace."""
    out, buf = [], ''
    for line in text.split('\n'):
        if buf:
            buf += ' ' + line.strip()
        elif line.startswith('    ') and not line.startswith('     ') \
                and re.match(r'^    (?:@Override\s+)?[a-zA-Z]', line) and '{' not in line \
                and line.rstrip().endswith(('(', ',', ')')) or \
                (line.startswith('    ') and '(' in line and '{' not in line
                 and not line.strip().startswith(('//', '*', '/*'))):
            buf = line.rstrip()
        else:
            out.append(line); continue
        if '{' in buf or ';' in buf:
            out.append(buf); buf = ''
    if buf: out.append(buf)
    return out

def main():
    total = 0
    for path in sys.argv[1:]:
        text = open(path, encoding='utf-8').read()
        rows, seen = [], set()
        for line in logical_lines(text):
            m = DECL.match(line)
            if not m: continue
            mods, ret, name, args = m.groups()
            if name in KEYWORDS or (name, ret) in seen: continue
            seen.add((name, ret))
            rows.append((name, ret, ' '.join(mods.split()), ' '.join(args.split())))
        rows.sort()
        print(f"## {os.path.basename(path)} -- {len(rows)} methods\n")
        for name, ret, mods, args in rows:
            short = (args[:60] + '...') if len(args) > 60 else args
            print(f"- `{name}({short})` -> `{ret}`")
        print()
        total += len(rows)
    print(f"total: {total}")

main()
