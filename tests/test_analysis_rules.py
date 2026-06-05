from __future__ import annotations

import unittest

from tools.analysis_rules import (
    ActivityMonth,
    classify_activity_months,
    evaluate_walking_session,
)


class ActivityMonthSignalTest(unittest.TestCase):
    def test_detects_peak_against_previous_months_not_static_threshold(self) -> None:
        signals = classify_activity_months(
            [
                ActivityMonth("2025-10", 31, 310_000),
                ActivityMonth("2025-11", 30, 300_000),
                ActivityMonth("2025-12", 31, 310_000),
                ActivityMonth("2026-01", 31, 310_000),
                ActivityMonth("2026-02", 28, 280_000),
                ActivityMonth("2026-03", 31, 310_000),
                ActivityMonth("2026-04", 30, 600_000),
            ]
        )

        april = signals["2026-04"]
        self.assertEqual(april.label, "peak")
        self.assertAlmostEqual(april.ratio_to_baseline or 0.0, 2.0, places=2)
        self.assertEqual(signals["2025-10"].label, "baseline")

    def test_detects_slump_and_ignores_partial_month(self) -> None:
        signals = classify_activity_months(
            [
                ActivityMonth("2025-10", 31, 310_000),
                ActivityMonth("2025-11", 30, 300_000),
                ActivityMonth("2025-12", 31, 310_000),
                ActivityMonth("2026-01", 31, 310_000),
                ActivityMonth("2026-02", 28, 280_000),
                ActivityMonth("2026-03", 31, 310_000),
                ActivityMonth("2026-04", 30, 150_000),
                ActivityMonth("2026-05", 5, 100_000),
            ]
        )

        self.assertEqual(signals["2026-04"].label, "slump")
        self.assertEqual(signals["2026-05"].label, "partial")
        self.assertIsNone(signals["2026-05"].ratio_to_baseline)


class WalkingSessionFilterTest(unittest.TestCase):
    def test_accepts_credible_fitness_walk_and_assigns_band(self) -> None:
        decision = evaluate_walking_session(
            distance_meters=5_000,
            duration_seconds=3_600,
            pace_seconds_per_km=720,
            avg_heart_rate_bpm=120,
            fitness_mode=True,
        )

        self.assertTrue(decision.accepted)
        self.assertEqual(decision.distance_band, "3-6 km")
        self.assertEqual(decision.reasons, ())

    def test_rejects_paused_or_impossible_walks(self) -> None:
        too_slow = evaluate_walking_session(
            distance_meters=5_000,
            duration_seconds=20_000,
            pace_seconds_per_km=4_000,
            avg_heart_rate_bpm=120,
            fitness_mode=True,
        )
        bad_heart = evaluate_walking_session(
            distance_meters=5_000,
            duration_seconds=3_600,
            pace_seconds_per_km=720,
            avg_heart_rate_bpm=260,
            fitness_mode=True,
        )

        self.assertFalse(too_slow.accepted)
        self.assertIn("duration_too_long", too_slow.reasons)
        self.assertIn("pace_too_slow_or_paused", too_slow.reasons)
        self.assertFalse(bad_heart.accepted)
        self.assertIn("heart_rate_not_credible", bad_heart.reasons)

    def test_fitness_mode_requires_comparable_distance_band(self) -> None:
        short_walk = evaluate_walking_session(
            distance_meters=2_000,
            duration_seconds=1_500,
            pace_seconds_per_km=750,
            fitness_mode=True,
        )
        credible_but_not_fitness = evaluate_walking_session(
            distance_meters=2_000,
            duration_seconds=1_500,
            pace_seconds_per_km=750,
            fitness_mode=False,
        )

        self.assertFalse(short_walk.accepted)
        self.assertIn("distance_too_short", short_walk.reasons)
        self.assertTrue(credible_but_not_fitness.accepted)
        self.assertEqual(credible_but_not_fitness.distance_band, "1-3 km")


if __name__ == "__main__":
    unittest.main()
