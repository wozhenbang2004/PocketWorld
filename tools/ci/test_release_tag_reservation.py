import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location(
    "reservation", Path(__file__).with_name("release-tag-reservation.py"))
reservation = importlib.util.module_from_spec(spec)
spec.loader.exec_module(reservation)


class ReleaseTagReservationTest(unittest.TestCase):
    def tag(self, code):
        if code < 1000:
            raise ValueError("Not a canonical public version")
        return "v0.1." + str(code - 1000)

    def test_first_free_number_above_published_baseline(self):
        seen = []
        def occupied(tag):
            seen.append(tag)
            return tag in {"v0.1.109"}
        self.assertEqual(1110, reservation.select_version(1108, self.tag, occupied))
        self.assertEqual(["v0.1.109", "v0.1.110"], seen)

    def test_no_reservation_keeps_next_version(self):
        self.assertEqual(1109, reservation.select_version(1108, self.tag, lambda tag: False))

    def test_bounded_when_every_candidate_is_taken(self):
        with self.assertRaisesRegex(ValueError, "manual release inspection"):
            reservation.select_version(1108, self.tag, lambda tag: True, max_candidates=2)

    def test_never_swallow_lookup_failures(self):
        def broken(tag):
            raise RuntimeError("GitHub permission or rate limit failure")
        with self.assertRaisesRegex(RuntimeError, "rate limit"):
            reservation.select_version(1108, self.tag, broken)

    def test_invalid_inputs_rejected(self):
        for floor in (None, "1108", 998, 2_100_000_000, True):
            with self.assertRaises(ValueError):
                reservation.select_version(floor, self.tag, lambda tag: False)


if __name__ == "__main__":
    unittest.main()
