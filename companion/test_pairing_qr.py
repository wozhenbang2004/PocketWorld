"""QR software roundtrip, not a physical camera or native Windows window test."""
import copy
import ctypes
import ctypes.util
import importlib.metadata
from pathlib import Path
import sqlite3
import tempfile
import threading
import time
import unittest
from unittest.mock import patch

import device_identity as identity
import device_pairing as pairing
import device_protocol as protocol
from pairing_qr import invitation_matrix, raster_bgra
from platform_backend import PlatformError

try:
    PINNED_QR = importlib.metadata.version("qrcode") == "8.2"
except importlib.metadata.PackageNotFoundError:
    PINNED_QR = False


class PairingQrTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="pw-ephemeral-qr-test-")
        self.local = identity.provision_identity(Path(self.temp.name) / "ephemeral-only-identity")
        self.database = sqlite3.connect(":memory:")
        trust = pairing.DeviceTrust(self.database, threading.RLock()); trust.create_schema()
        self.owner = pairing.PairingCoordinator(trust, self.local.device_id, self.local.instance_id)
        self.now = time.time_ns() // 1000000
        self.invitation = self.owner.create_invitation(self.local, None, self.now, connection_hint={"kind": "lan", "host": "192.0.2.1",
            "candidatePort": 41001, "completionPort": 41002, "controlPort": 41003, "bulkPort": 41004})

    def tearDown(self):
        self.database.close(); self.temp.cleanup()

    def test_unknown_or_missing_dependency_is_explicit_not_runtime_download_or_new_carrier(self):
        with patch("pairing_qr.importlib.metadata.version", return_value="unreviewed"):
            with self.assertRaises(PlatformError): invitation_matrix(self.invitation, self.now)
        with patch("pairing_qr.importlib.metadata.version", side_effect=importlib.metadata.PackageNotFoundError):
            with self.assertRaises(PlatformError): invitation_matrix(self.invitation, self.now)

    def test_bad_profile_expired_or_tampered_certificate_is_not_encoded(self):
        for changes in ({"protocolProfile": "unknown"}, {"expiresAt": self.now - 1}, {"certificateFingerprint": "0" * 64}):
            with self.assertRaises((ValueError, PermissionError, PlatformError)):
                invitation_matrix({**self.invitation, **changes}, self.now)
        self.assertEqual([], self.owner.store.peers())

    @unittest.skipUnless(PINNED_QR, "Requires the hash-pinned qrcode 8.2 build dependency; never claim native/camera validation")
    def test_complete_unique_invitation_canonical_matrix_and_integer_pixels(self):
        matrix = invitation_matrix(self.invitation, self.now)
        self.assertEqual(matrix, invitation_matrix(dict(reversed(list(self.invitation.items()))), self.now))
        self.assertTrue(all(type(cell) is bool for row in matrix for cell in row))
        size = len(matrix)
        self.assertFalse(any(matrix[y][x] for y in range(size) for x in range(size) if min(y, x, size - 1 - y, size - 1 - x) < 4))
        raw = raster_bgra(matrix, 3)
        self.assertEqual((size * 3) ** 2 * 4, len(raw))
        self.assertEqual({0, 255}, set(raw)); self.assertTrue(all(raw[index] == 255 for index in range(3, len(raw), 4)))
        with self.assertRaises(PlatformError): raster_bgra(matrix, 2)

    @unittest.skipUnless(PINNED_QR and ctypes.util.find_library("zbar"), "Optional actual installed libzbar software decoder, not Windows camera acceptance")
    def test_real_installed_decoder_returns_exact_unique_invitation_bytes(self):
        matrix = invitation_matrix(self.invitation, self.now); side = len(matrix) * 4
        bgra = raster_bgra(matrix, 4); samples = ctypes.create_string_buffer(bytes(bgra[::4]))
        library = ctypes.CDLL(ctypes.util.find_library("zbar"))
        signatures = {
            "zbar_image_create": ([], ctypes.c_void_p), "zbar_image_destroy": ([ctypes.c_void_p], None),
            "zbar_image_set_format": ([ctypes.c_void_p, ctypes.c_ulong], None),
            "zbar_image_set_size": ([ctypes.c_void_p, ctypes.c_uint, ctypes.c_uint], None),
            "zbar_image_set_data": ([ctypes.c_void_p, ctypes.c_void_p, ctypes.c_ulong, ctypes.c_void_p], None),
            "zbar_image_scanner_create": ([], ctypes.c_void_p), "zbar_image_scanner_destroy": ([ctypes.c_void_p], None),
            "zbar_image_scanner_set_config": ([ctypes.c_void_p, ctypes.c_int, ctypes.c_int, ctypes.c_int], ctypes.c_int),
            "zbar_scan_image": ([ctypes.c_void_p, ctypes.c_void_p], ctypes.c_int),
            "zbar_image_first_symbol": ([ctypes.c_void_p], ctypes.c_void_p), "zbar_symbol_next": ([ctypes.c_void_p], ctypes.c_void_p),
            "zbar_symbol_get_data": ([ctypes.c_void_p], ctypes.c_void_p), "zbar_symbol_get_data_length": ([ctypes.c_void_p], ctypes.c_uint),
        }
        for name, (args, result) in signatures.items(): getattr(library, name).argtypes, getattr(library, name).restype = args, result
        image, scanner = library.zbar_image_create(), library.zbar_image_scanner_create()
        self.assertTrue(image and scanner)
        try:
            self.assertEqual(0, library.zbar_image_scanner_set_config(scanner, 0, 0, 1))
            library.zbar_image_set_format(image, int.from_bytes(b"Y800", "little")); library.zbar_image_set_size(image, side, side)
            library.zbar_image_set_data(image, samples, side * side, None)
            self.assertEqual(1, library.zbar_scan_image(scanner, image))
            symbol = library.zbar_image_first_symbol(image); self.assertTrue(symbol)
            result = ctypes.string_at(library.zbar_symbol_get_data(symbol), library.zbar_symbol_get_data_length(symbol))
            self.assertEqual(protocol.canonical_bytes(self.invitation), result)
            self.assertFalse(library.zbar_symbol_next(symbol))
            self.assertEqual(self.invitation, pairing.validate_invitation_for_preview(protocol.parse_json_object(result), self.now))
        finally:
            library.zbar_image_destroy(image); library.zbar_image_scanner_destroy(scanner)


if __name__ == "__main__": unittest.main()
