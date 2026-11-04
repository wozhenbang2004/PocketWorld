"""Real RTP parser and pinned-runtime checks; inert sender for boundary cases."""
import asyncio
from fractions import Fraction
import importlib.util
from types import SimpleNamespace
import unittest
from unittest.mock import patch

from desktop_rtp import TransmittedFrameObserver, verify_runtime_contract

HAS_RTC = importlib.util.find_spec("aiortc") is not None and importlib.util.find_spec("av") is not None


@unittest.skipUnless(HAS_RTC, "Prepared aiortc required for actual RTP parser/contract checks")
class TransmittedFrameContracts(unittest.IsolatedAsyncioTestCase):
    async def asyncSetUp(self):
        from aiortc.rtp import RtpPacket
        self.Packet = RtpPacket
        self.sent, self.published, self.invalidated = [], [], []
        self.fail_send = False
        self.evidence = 0
        self.duplicate_capture = False
        async def send(raw):
            await asyncio.sleep(0)
            if self.fail_send:
                raise ConnectionError("inert transport failure")
            self.sent.append(raw)
        self.transport = SimpleNamespace(_send_rtp=send)
        async def encode(codec):
            self.evidence += 1
            evidence = {"fixtureFrame": self.evidence}
            # Deliberately different from encoded timestamp, like PyAV's real
            # rescaling. The verified single capture/encode call is the binding.
            self.observer.capture(SimpleNamespace(pts=11, time_base=Fraction(1, 90000)), evidence)
            if self.duplicate_capture:
                self.observer.capture(SimpleNamespace(pts=12, time_base=Fraction(1, 90000)), evidence)
            return SimpleNamespace(timestamp=9000 * self.evidence, payloads=[b"part-a", b"part-b"])
        self.sender = SimpleNamespace(_ssrc=123, transport=self.transport, _next_encoded_frame=encode)
        # This fixture replaces only private-shape admission. Real class/source
        # checks are exercised below and by the real DTLS/SRTP loopback test.
        with patch("desktop_rtp._verify_sender"):
            self.observer = TransmittedFrameObserver(self.sender, lambda evidence, timestamp: self.published.append((evidence, timestamp)),
                                                      lambda: self.invalidated.append(True))
        self.codec = SimpleNamespace(mimeType="video/VP8", payloadType=96)
        self.sequence = 65534

    def packet(self, timestamp, payload, *, marker, ssrc=123, payload_type=96):
        packet = self.Packet(payload_type=payload_type, sequence_number=self.sequence % 65536, timestamp=timestamp)
        self.sequence += 1
        packet.ssrc, packet.payload, packet.marker = ssrc, payload, marker
        return packet.serialize()

    async def frame(self, timestamp):
        await self.sender._next_encoded_frame(self.codec)
        count = len(self.published)
        await self.transport._send_rtp(self.packet(timestamp, b"part-a", marker=0))
        self.assertEqual(count, len(self.published), "No metadata until the last actual packet completed")
        await self.transport._send_rtp(self.packet(timestamp, b"part-b", marker=1))

    async def test_uint32_zero_wrap_and_sequence_wrap_keep_actual_timestamps(self):
        for timestamp in (4294967294, 4294967295, 0, 1):
            await self.frame(timestamp)
        self.assertEqual([4294967294, 4294967295, 0, 1], [timestamp for _, timestamp in self.published])
        self.assertEqual([1, 2, 3, 4], [evidence["fixtureFrame"] for evidence, _ in self.published])
        self.assertEqual(8, len(self.sent))
        self.assertFalse(self.invalidated)

    async def test_rtx_and_retransmit_do_not_publish_again(self):
        await self.sender._next_encoded_frame(self.codec)
        first = self.packet(0, b"part-a", marker=0)
        await self.transport._send_rtp(first)
        # A separate RTX task may interleave while the original frame is active.
        saved = self.sequence
        rtx = self.packet(0, b"rtx-payload", marker=1, ssrc=999, payload_type=97)
        self.sequence = saved
        await asyncio.create_task(self.transport._send_rtp(rtx))
        await self.transport._send_rtp(self.packet(0, b"part-b", marker=1))
        await asyncio.create_task(self.transport._send_rtp(first))
        self.assertEqual([({"fixtureFrame": 1}, 0)], self.published)
        self.assertTrue(self.observer.available)

    async def test_failed_final_send_has_no_input_authority(self):
        await self.sender._next_encoded_frame(self.codec)
        await self.transport._send_rtp(self.packet(10, b"part-a", marker=0))
        self.fail_send = True
        with self.assertRaises(ConnectionError):
            await self.transport._send_rtp(self.packet(10, b"part-b", marker=1))
        self.assertFalse(self.published)
        self.assertFalse(self.observer.available)
        self.assertTrue(self.invalidated)

    async def test_payload_mismatch_and_ambiguous_capture_leave_video_readonly(self):
        await self.sender._next_encoded_frame(self.codec)
        await self.transport._send_rtp(self.packet(10, b"different-frame", marker=0))
        self.assertFalse(self.observer.available)
        self.assertFalse(self.published)
        self.assertEqual(1, len(self.sent), "The observer must not rewrite/suppress video packets")
        with patch("desktop_rtp._verify_sender"):
            self.observer = TransmittedFrameObserver(self.sender, lambda *_: self.fail("Unexpected authority"), lambda: None)
        self.duplicate_capture = True
        await self.sender._next_encoded_frame(self.codec)
        self.assertFalse(self.observer.available)

    async def test_duplicate_or_backward_frame_timestamp_disables_input(self):
        await self.frame(100)
        await self.frame(100)
        self.assertEqual(1, len(self.published))
        self.assertFalse(self.observer.available)

    async def test_actual_version_source_and_loaded_methods_are_required(self):
        verify_runtime_contract()
        with patch.dict("desktop_rtp._SOURCE_HASHES", {"rtcrtpsender.py": "0" * 64}):
            with self.assertRaises(ValueError):
                verify_runtime_contract()
        from aiortc.rtcrtpsender import RTCRtpSender
        async def unexpected(self, codec):
            return None
        with patch.object(RTCRtpSender, "_next_encoded_frame", unexpected):
            with self.assertRaises((ValueError, StopIteration)):
                verify_runtime_contract()


if __name__ == "__main__":
    unittest.main()
