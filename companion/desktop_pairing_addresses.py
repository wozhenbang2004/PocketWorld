"""Read-only Windows LAN choices for the physical pairing window; no socket or probe."""
import ctypes as c
from dataclasses import dataclass
import ipaddress
import os
import unicodedata

from platform_backend import PlatformError


_U, _P = c.c_uint32, c.c_void_p
_PRIVATE = tuple(ipaddress.ip_network(value) for value in ("10.0.0.0/8", "172.16.0.0/12", "192.168.0.0/16", "fc00::/7"))


class _Socket(c.Structure):
    _fields_ = [("address", _P), ("length", c.c_int32)]


class _Unicast(c.Structure):  # IP_ADAPTER_UNICAST_ADDRESS_LH prefix (iptypes.h).
    _fields_ = [("length", _U), ("flags", _U), ("next", _P), ("socket", _Socket),
                ("prefix", _U), ("suffix", _U), ("dad", _U), ("valid", _U), ("preferred", _U)]


class _Adapter(c.Structure):  # IP_ADAPTER_ADDRESSES_LH through OperStatus; native x64 layout.
    _fields_ = [("length", _U), ("index", _U), ("next", _P), ("id", _P), ("first", _P),
                ("anycast", _P), ("multicast", _P), ("dns", _P), ("suffix", _P), ("description", _P),
                ("friendly", _P), ("physical", c.c_ubyte * 8), ("physical_len", _U), ("flags", _U),
                ("mtu", _U), ("kind", _U), ("status", _U)]


@dataclass(frozen=True)
class LanChoice:
    adapter: str
    address: str
    label: str


def local_choices():
    """Only existing Up, preferred private unicast addresses on Wi-Fi/Ethernet.

    Guesses from hostnames, the default gateway, DNS, VPNs or public-IP services
    are not interface identity. Never fill, bind, probe, or publish an address here.
    """
    if os.name != "nt": raise PlatformError("只有 Windows 本机能读取可选网卡地址；也可手动填写。")
    get = c.WinDLL("iphlpapi").GetAdaptersAddresses
    get.argtypes, get.restype = [_U, _U, _P, _P, c.POINTER(_U)], _U
    size = _U(15 * 1024)
    for _ in range(3):
        if not 0 < size.value <= 2 * 1024 * 1024: raise PlatformError("本机接口数据超出显示范围，请手动填写地址。")
        buf = c.create_string_buffer(size.value)
        code = get(0, 0x02 | 0x04 | 0x08, None, c.byref(buf), c.byref(size))  # AF_UNSPEC, only unicast.
        if code == 232: return []  # ERROR_NO_DATA
        if code == 0: break
        if code != 111: raise PlatformError("Windows 未能读取当前网卡，可重选或手动填写；没有检测手机。")
    else: raise PlatformError("本机网卡仍在变化，请稍后重选或手动填写地址。")
    start, end = c.addressof(buf), c.addressof(buf) + c.sizeof(buf)

    def inside(p, length):
        return bool(p) and start <= p <= end - length

    def entries(pointer, shape):
        seen = set()
        while pointer:
            if pointer in seen or len(seen) >= 512 or not inside(pointer, c.sizeof(shape)):
                raise PlatformError("无法完整核对本机接口记录，请手动填写地址。")
            seen.add(pointer)
            row = shape.from_buffer(buf, pointer - start)
            if row.length < c.sizeof(shape) or not inside(pointer, row.length):
                raise PlatformError("本机接口记录不完整，请手动填写地址。")
            yield row
            pointer = row.next

    def name(pointer, wide=False):
        if not inside(pointer, 2 if wide else 1): return ""
        step = 2 if wide else 1
        data = c.string_at(pointer, min(end - pointer, 512 * step))
        zero = b"\x00" * step
        for pos in range(0, len(data) - step + 1, step):
            if data[pos:pos + step] == zero:
                try: return data[:pos].decode("utf-16-le" if wide else "ascii")
                except UnicodeError: return ""
        return ""

    choices, owners = {}, {}
    for adapter in entries(start, _Adapter):
        if adapter.status != 1: continue  # IfOperStatusUp
        identity = name(adapter.id)
        friendly = "".join(ch for ch in name(adapter.friendly, True) if not unicodedata.category(ch).startswith("C"))[:64]
        for addr in entries(adapter.first, _Unicast):
            p, count = addr.socket.address, addr.socket.length
            if not inside(p, count) or count not in (16, 28): continue
            raw = c.string_at(p, count)
            family = int.from_bytes(raw[:2], "little")
            if family == 2 and count == 16: ip = ipaddress.ip_address(raw[4:8])
            elif family == 23 and count == 28 and raw[24:] == bytes(4): ip = ipaddress.ip_address(raw[8:24])
            else: continue
            if not any(ip in net for net in _PRIVATE): continue
            # The original listener binds a literal, not an interface index.
            # Never offer an address shared by different active adapters.
            owners.setdefault(ip, set()).add((identity, adapter.index))
            if (not identity or adapter.kind not in (6, 71) or addr.dad != 4 or not addr.valid or not addr.preferred
                    or addr.flags & 2 or adapter.flags & 8): continue  # preferred, non-transient, not ReceiveOnly
            label = f"{'Wi-Fi' if adapter.kind == 71 else '以太网'} · {friendly or ('网卡 ' + str(adapter.index))} · {ip}"
            choices[ip] = LanChoice(identity, str(ip), label)
    result = [row for ip, row in choices.items() if len(owners[ip]) == 1]
    if len(result) > 48: raise PlatformError("当前网络地址较多，请手动填写希望绑定的准确地址。")
    return sorted(result, key=lambda row: (ipaddress.ip_address(row.address).version, row.label))
