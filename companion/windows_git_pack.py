"""Bounded reads of selected original packed Git objects, not a generic checkout.

Only the registered repository's pinned idx/pack pair is used. A pack name or
index lookup is not object verification: reconstruct and hash the requested
canonical object. No multi-pack fallback, alternates, thin-pack fetch or writes.
"""
import hashlib
import os
import re
import struct
import zlib


class PackUnavailable(Exception):
    def __init__(self, reason): self.reason = reason


def _bad():
    raise PackUnavailable("invalid_pack")


def _bound():
    raise PackUnavailable("pack_limit")


class Budget:
    def __init__(self, tree_walk, content, checkpoint):
        self.checkpoint = checkpoint
        self.bytes_left = (256 if content else 32 if tree_walk else 12)*1024*1024
        self.work_left = (384 if content else 32 if tree_walk else 16)*1024*1024
        self.reads_left, self.ops_left = (240000,2000000) if content else (32768,200000) if tree_walk else (8192,100000)

    def charge(self, size):
        self.checkpoint()
        self.bytes_left -= size
        if self.bytes_left < 0: _bound()

    def at(self, reader, position, size):
        if position < 0 or size < 0 or position+size > reader.size: _bad()
        self.charge(size)
        self.reads_left -= 1
        if self.reads_left < 0: _bound()
        reader.seek(position)
        return reader.read(size)

    def work(self, size):
        self.work_left -= size
        if self.work_left < 0: _bound()


class Cursor:
    def __init__(self, data): self.data, self.pos = data, 0

    def take(self, size):
        if size > len(self.data)-self.pos: _bad()
        start = self.pos
        self.pos += size
        return self.data[start:self.pos]

    def byte(self):
        if self.pos >= len(self.data): _bad()
        self.pos += 1
        return self.data[self.pos-1]

    def size(self):
        result = 0
        for shift in range(0, 35, 7):
            part = self.byte()
            result |= (part & 127) << shift
            if part < 128: return result
        _bound()


def _delta(base, instructions, limit, budget):
    wire = Cursor(instructions)
    if wire.size() != len(base): _bad()
    size = wire.size()
    if size > limit: _bound()
    budget.work(size)
    out = bytearray()
    while wire.pos < len(instructions):
        budget.ops_left -= 1
        if budget.ops_left < 0: _bound()
        if not budget.ops_left%256: budget.checkpoint()
        mask = wire.byte()
        if mask & 128:
            offset = sum(wire.byte() << (8*i) for i in range(4) if mask & (1 << i))
            length = sum(wire.byte() << (8*i) for i in range(3) if mask & (16 << i)) or 65536
            if offset+length > len(base) or length > size-len(out): _bad()
            out.extend(base[offset:offset+length])
        elif mask and mask <= size-len(out):
            out.extend(wire.take(mask))
        else:
            _bad()
    if len(out) != size: _bad()
    return bytes(out)


def _inflate(reader, start, end, size, prefix, budget):
    # Pack entries are adjacent zlib streams. A read may include the start of
    # the next entry: count only bytes consumed by THIS stream in its CRC.
    decoder = zlib.decompressobj()
    data, crc, pos = bytearray(), zlib.crc32(prefix), start
    budget.work(size)
    while not decoder.eof:
        n = min(8192, end-pos, size+65536-(pos-start))
        if n <= 0: _bad()
        chunk = budget.at(reader,pos,n)
        pos += n
        try: data.extend(decoder.decompress(chunk, size+1-len(data)))
        except zlib.error: _bad()
        if len(data)>size or decoder.unconsumed_tail: _bad()
        crc = zlib.crc32(chunk[:len(chunk)-len(decoder.unused_data)],crc)
    if len(data) != size: _bad()
    return bytes(data), crc


class Index:
    START = 8+256*4

    def __init__(self, source, name):
        self.source, self.width, self.name = source, source.width, name
        self.index = source.pins.reader(".git/objects/pack/"+name+".idx",512*1024*1024)
        if self.index is None: raise PackUnavailable("object_unavailable")
        self.file = None
        header = self.iread(0,self.START)
        if header[:8] != b"\xfftOc\0\0\0\2": raise PackUnavailable("pack_layout")
        self.fan = struct.unpack(">256I",header[8:])
        self.count = self.fan[-1]
        if self.count > 8000000: _bound()
        if any(a>b for a,b in zip(self.fan,self.fan[1:])): _bad()
        self.crcs = self.START + self.count*self.width
        self.offsets = self.crcs + self.count*4
        self.large = self.offsets + self.count*4
        extra = self.index.size-2*self.width-self.large
        if extra<0 or extra%8 or extra//8>self.count: _bad()
        self.large_count = extra//8
        self.trailer = self.iread(self.index.size-2*self.width,self.width)
        if self.trailer.hex()!=name[5:]: _bad()

    def iread(self, pos, size): return self.source.budget.at(self.index,pos,size)

    def locate(self, name):
        first = name[0]
        low, high = (0 if first==0 else self.fan[first-1]), self.fan[first]
        while low < high:
            mid = (low+high)//2
            value = self.iread(self.START+mid*self.width,self.width)
            if value < name: low = mid+1
            elif value > name: high = mid
            else:
                off = int.from_bytes(self.iread(self.offsets+mid*4,4),"big")
                if off & 0x80000000:
                    slot = off & 0x7fffffff
                    if slot>=self.large_count: _bad()
                    off = int.from_bytes(self.iread(self.large+slot*8,8),"big")
                crc = int.from_bytes(self.iread(self.crcs+mid*4,4),"big")
                return off, crc
        return None

    def open_pack(self):
        if self.file is not None: return
        self.file = self.source.pins.reader(".git/objects/pack/"+self.name+".pack",16*1024**3)
        if self.file is None: raise PackUnavailable("object_unavailable")
        self.end = self.file.size-self.width
        if self.end<12: _bad()
        marker,ver,num = struct.unpack(">4sII", self.source.budget.at(self.file,0,12))
        if (marker!=b"PACK" or ver not in (2,3) or num!=self.count
                or self.source.budget.at(self.file,self.end,self.width)!=self.trailer): _bad()

    def body(self, off, checksum, kind, limit, ancestors, retained=0):
        if len(ancestors)>12: _bound()
        if off in ancestors or not 12<=off<self.end: _bad()
        budget = self.source.budget
        header = Cursor(budget.at(self.file,off,min(64,self.end-off)))
        first = header.byte()
        objtype, size, part, shift = (first>>4)&7, first&15, first, 4
        while part & 128:
            if shift>32: _bound()
            part = header.byte(); size |= (part&127) << shift; shift += 7
        expected = {b"commit":1,b"tree":2,b"blob":3}.get(kind)
        if objtype not in (expected,6,7) or expected is None: _bad()
        if size > (limit if objtype==expected else limit+65536): _bound()
        if retained+size > max(4*1024*1024,2*limit+2*1024*1024): _bound()
        ref = base_off = base_crc = None
        if objtype==6:
            part = header.byte()
            distance = part&127
            while part&128:
                if distance>=off: _bad()
                part = header.byte(); distance = ((distance+1)<<7) | (part&127)
            if not 0<distance<=off-12: _bad()
            base_off = off-distance
        elif objtype==7:
            ref = header.take(self.width)
            match = self.locate(ref)
            if match is None: raise PackUnavailable("pack_layout")  # no external thin base
            base_off, base_crc = match
        raw, actual_crc = _inflate(self.file,off+header.pos,self.end,size,header.data[:header.pos],budget)
        if checksum is not None and checksum!=actual_crc: _bad()
        if base_off is None: return raw
        base = self.body(base_off,base_crc,kind,limit,ancestors | {off},retained+size)
        if ref is not None: self.source.verify(ref,kind,base)
        return _delta(base,raw,limit,budget)


class PinnedPacks:
    def __init__(self, pins, algo, *, tree_walk=False, content=False, checkpoint=lambda: None):
        self.pins, self.algo = pins, algo
        self.width = hashlib.new(algo).digest_size
        self.names, self.indexes, self.budget = None, {}, Budget(tree_walk,content,checkpoint)

    def _names(self):
        directory = self.pins.node(".git/objects/pack")
        if directory is None: return []
        if not directory[2]: _bad()
        pattern = re.compile(r"pack-[a-f0-9]{"+str(self.width*2)+r"}\.idx")
        names = []
        # Inspect names only in this pinned original directory, never follow a
        # directory entry elsewhere. Open every selected child as its own handle.
        with os.scandir(directory[0]) as items:
            for n,item in enumerate(items):
                if n>=128: _bound()
                if pattern.fullmatch(item.name): names.append(item.name[:-4])
                if len(names)>16: _bound()
        return sorted(names)

    def verify(self, oid, kind, body):
        digest = hashlib.new(self.algo,kind+b" "+str(len(body)).encode()+b"\0")
        digest.update(body)
        if digest.digest() != oid: _bad()

    def get(self, oid, kind, limit):
        if self.names is None: self.names = self._names()
        target = bytes.fromhex(oid)
        for name in self.names:
            if name not in self.indexes: self.indexes[name] = Index(self,name)
            index = self.indexes[name]
            row = index.locate(target)
            if row is None: continue
            index.open_pack()
            body = index.body(*row,kind,limit,set())
            self.verify(target,kind,body)
            return body
        raise PackUnavailable("object_unavailable")
