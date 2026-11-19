#!/usr/bin/env python3
"""Write an empty-room test structure (stone floor, air above) as a gzipped structure NBT.

usage: python make_platform.py <out.nbt> [sx sy sz] [data_version]
Block states use the 26.x "id" key (older versions used "Name").
Function-based GameTests build their own setup in Java; this gives them a known
floor at y=0 and a bounding box the framework clears between runs.
"""
import gzip, struct, sys

def s(t): b = t.encode(); return struct.pack(">H", len(b)) + b
def tag(tid, name, payload): return bytes([tid]) + s(name) + payload
def i(v): return struct.pack(">i", v)
def lst(tid, items): return bytes([tid]) + i(len(items)) + b"".join(items)
def compound(*entries): return b"".join(entries) + b"\x00"

def main():
    out = sys.argv[1]
    sx, sy, sz = (int(x) for x in sys.argv[2:5]) if len(sys.argv) >= 5 else (9, 6, 9)
    dv = int(sys.argv[5]) if len(sys.argv) >= 6 else 5023
    palette = [compound(tag(8, "id", s("minecraft:stone"))), compound(tag(8, "id", s("minecraft:air")))]
    blocks = []
    for y in range(sy):
        for z in range(sz):
            for x in range(sx):
                blocks.append(compound(tag(9, "pos", lst(3, [i(x), i(y), i(z)])), tag(3, "state", i(0 if y == 0 else 1))))
    root = tag(10, "", compound(
        tag(3, "DataVersion", i(dv)),
        tag(9, "size", lst(3, [i(sx), i(sy), i(sz)])),
        tag(9, "palette", lst(10, palette)),
        tag(9, "blocks", lst(10, blocks)),
        tag(9, "entities", lst(10, [])),
    ))
    with gzip.open(out, "wb") as f:
        f.write(root)
    print(f"wrote {out} ({sx}x{sy}x{sz}, DataVersion {dv})")

main()
