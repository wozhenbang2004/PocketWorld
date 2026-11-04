"""Exact encoded-frame → transmitted RTP evidence for pinned aiortc 1.15.0.

aiortc adds a random RTP timestamp origin after encoding. Track PTS alone is
therefore not a browser frame identity. This small observer watches the original
sender task and its actual plaintext RTP packets immediately around the real
DTLS/SRTP send. It never sends, retries or rewrites a packet. A mismatch leaves
video running and removes input authority.

Private seams are admitted only with the official source hashes AND matching
loaded method code. The prepared freezer carries those .py source materials;
missing/different source or code means viewing only, never a guessed mapping.
"""
from __future__ import annotations

import asyncio
import hashlib
import importlib.metadata
import importlib.resources
import sys
from types import CodeType


_SOURCE_HASHES = {
    "rtcrtpsender.py": "f90cef75d1f58cbe415ff4329e340bf1425ca8664020e4cb83cb45f06c33c36b",
    "rtcdtlstransport.py": "af315bbdf74e6d3228dbd7959e1341c54921469955d0f2eaa8b7973c02cad805",
    "codecs/vpx.py": "c88488063022e113ba1b87ff5c5a53c2a60bbb6470baeeb4f84796eeac1522ae",
}


def _same_code(actual, expected):
    fields = ("co_code", "co_names", "co_varnames", "co_freevars", "co_cellvars", "co_argcount",
              "co_posonlyargcount", "co_kwonlyargcount", "co_flags")
    if any(getattr(actual, key) != getattr(expected, key) for key in fields) or len(actual.co_consts) != len(expected.co_consts):
        return False
    return all(_same_code(left, right) if isinstance(left, CodeType) and isinstance(right, CodeType)
               else type(left) is type(right) and left == right for left, right in zip(actual.co_consts, expected.co_consts))


def _verified_method(source, class_name, name, actual):
    raw = importlib.resources.files("aiortc").joinpath(source).read_bytes()
    if hashlib.sha256(raw).hexdigest() != _SOURCE_HASHES[source]:
        raise ValueError("Unreviewed aiortc source")
    module = compile(raw, source, "exec", dont_inherit=True, optimize=sys.flags.optimize)
    cls = next(value for value in module.co_consts if isinstance(value, CodeType) and value.co_name == class_name)
    expected = next(value for value in cls.co_consts if isinstance(value, CodeType) and value.co_name == name)
    if not _same_code(actual.__code__, expected):
        raise ValueError("Loaded media method differs from reviewed source")


def verify_runtime_contract():
    import aiortc
    from aiortc.codecs.vpx import Vp8Encoder
    from aiortc.rtcdtlstransport import RTCDtlsTransport
    from aiortc.rtcrtpsender import RTCRtpSender
    if importlib.metadata.version("aiortc") != "1.15.0" or aiortc.__version__ != "1.15.0":
        raise ValueError("Unreviewed media runtime version")
    for source, cls, method in (("rtcrtpsender.py", "RTCRtpSender", RTCRtpSender._run_rtp),
                                ("rtcrtpsender.py", "RTCRtpSender", RTCRtpSender._next_encoded_frame),
                                ("rtcdtlstransport.py", "RTCDtlsTransport", RTCDtlsTransport._send_rtp),
                                ("codecs/vpx.py", "Vp8Encoder", Vp8Encoder.encode)):
        _verified_method(source, cls, method.__name__, method)


def _verify_sender(sender):
    from aiortc.rtcdtlstransport import RTCDtlsTransport
    from aiortc.rtcrtpsender import RTCRtpSender
    verify_runtime_contract()
    if (type(sender) is not RTCRtpSender or type(sender.transport) is not RTCDtlsTransport
            or sender._next_encoded_frame.__func__ is not RTCRtpSender._next_encoded_frame
            or sender._run_rtp.__func__ is not RTCRtpSender._run_rtp
            or sender.transport._send_rtp.__func__ is not RTCDtlsTransport._send_rtp):
        raise ValueError("Unreviewed media sender shape")


class TransmittedFrameObserver:
    def __init__(self, sender, publish, invalidate):
        self.available = False
        self.encoding_call = None
        self.encoded = None
        self.last_timestamp = None
        self.sender, self.publish, self.invalidate = sender, publish, invalidate
        try:
            _verify_sender(sender)
            self._install()
            self.available = True
        except Exception:
            self.disable()

    def disable(self):
        self.available = False
        self.encoding_call = None
        self.encoded = None
        self.invalidate()

    def capture(self, frame, evidence):
        if not self.available:
            return
        if type(frame.pts) is not int or frame.time_base.numerator != 1 or frame.time_base.denominator != 90000:
            self.disable()
            return
        call = self.encoding_call
        if call is None or call["task"] is not asyncio.current_task() or call["evidence"] is not None:
            self.disable()
            return
        # The pinned _next_encoded_frame awaits exactly one track.recv and
        # encodes that returned frame. PyAV may rescale PTS during encoding;
        # correlate this actual call, never infer identity from pre-encode PTS.
        call["evidence"] = evidence

    def _install(self):
        from aiortc.rtp import RtpPacket, is_rtcp
        sender = self.sender
        original_next, original_send = sender._next_encoded_frame, sender.transport._send_rtp

        async def next_encoded(codec):
            if not self.available:
                return await original_next(codec)
            if self.encoding_call is not None:
                self.disable()
                return await original_next(codec)
            call = {"task": asyncio.current_task(), "evidence": None}
            self.encoding_call = call
            try:
                encoded = await original_next(codec)
            finally:
                self.encoding_call = None
            if not self.available:
                return encoded
            if encoded is None:
                self.encoded = None
                return None
            evidence = call["evidence"]
            payloads = tuple(encoded.payloads)
            if (codec.mimeType.lower() != "video/vp8" or evidence is None or not payloads
                    or any(not isinstance(payload, bytes) for payload in payloads)
                    or sum(map(len, payloads)) > 8 * 1024 * 1024):
                self.disable()
                return encoded
            self.encoded = {"task": asyncio.current_task(), "evidence": evidence, "payloads": payloads,
                            "index": 0, "timestamp": None, "sequence": None, "payloadType": codec.payloadType}
            return encoded

        async def send_rtp(raw):
            # Inspect only this sender's original transmission task. RTX/RTCP
            # and retransmits cannot mint a second authority for an old frame.
            expected = self.encoded
            observed = None
            if self.available and expected is not None and asyncio.current_task() is expected["task"]:
                try:
                    if is_rtcp(raw):
                        raise ValueError("RTCP in original video sender")
                    packet = RtpPacket.parse(raw)
                    index = expected["index"]
                    last = index == len(expected["payloads"]) - 1
                    if (packet.ssrc != sender._ssrc or packet.payload_type != expected["payloadType"]
                            or index >= len(expected["payloads"]) or packet.payload != expected["payloads"][index]
                            or packet.marker != int(last)
                            or expected["timestamp"] is not None and packet.timestamp != expected["timestamp"]
                            or expected["sequence"] is not None and packet.sequence_number != (expected["sequence"] + 1) % 65536):
                        raise ValueError("Encoded frame and actual RTP packet diverged")
                    observed = packet
                except Exception:
                    self.disable()
            try:
                await original_send(raw)
            except BaseException:
                self.disable()
                raise
            if observed is not None and self.available and self.encoded is expected:
                expected["timestamp"], expected["sequence"] = observed.timestamp, observed.sequence_number
                expected["index"] += 1
                if expected["index"] == len(expected["payloads"]):
                    self.encoded = None
                    if self.last_timestamp is not None and not 0 < (observed.timestamp - self.last_timestamp) % (1 << 32) < (1 << 31):
                        self.disable()
                        return
                    self.last_timestamp = observed.timestamp
                    try:
                        self.publish(expected["evidence"], observed.timestamp)
                    except Exception:
                        self.disable()

        sender._next_encoded_frame = next_encoded
        sender.transport._send_rtp = send_rtp
