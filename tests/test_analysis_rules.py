from __future__ import annotations

import unittest

from tools.analysis_rules import (
    ActivityMonth,
    WalkingBandYear,
    classify_activity_months,
    compare_latest_walking_band_years,
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


class WalkingBandTrendTest(unittest.TestCase):
    def test_detects_better_walks_inside_same_distance_band(self) -> None:
        trends = compare_latest_walking_band_years(
            [
                WalkingBandYear("2025", "3-6 km", 20, 90.0, 900.0, 108.0, 72.0, 45.0),
                WalkingBandYear("2026", "3-6 km", 4, 18.0, 780.0, 102.0, 64.0, 45.0),
            ]
        )

        trend = trends[0]
        self.assertEqual(trend.distance_band, "3-6 km")
        self.assertEqual(trend.confidence, "Medium")
        self.assertEqual(trend.pace_seconds_per_km_delta, -120.0)
        self.assertEqual(trend.avg_heart_rate_bpm_delta, -6.0)
        self.assertIn("sygnal poprawy", trend.interpretation)

    def test_detects_heavier_long_walks(self) -> None:
        trends = compare_latest_walking_band_years(
            [
                WalkingBandYear("2025", "15+ km", 14, 270.0, 790.0, 108.0, 68.0, 46.0),
                WalkingBandYear("2026", "15+ km", 4, 80.0, 995.0, 114.0, 74.5, 45.0),
            ]
        )

        trend = trends[0]
        self.assertEqual(trend.confidence, "Medium")
        self.assertEqual(trend.pace_seconds_per_km_delta, 205.0)
        self.assertEqual(trend.avg_heart_rate_bpm_delta, 6.0)
        self.assertIn("wolniej i drozej", trend.interpretation)

    def test_marks_band_comparison_insufficient_when_sample_is_too_small(self) -> None:
        trends = compare_latest_walking_band_years(
            [
                WalkingBandYear("2025", "6-10 km", 7, 50.0, 850.0, 104.0, 68.0, None),
                WalkingBandYear("2026", "6-10 km", 2, 15.0, 810.0, 100.0, 62.0, None),
            ]
        )

        self.assertEqual(trends[0].confidence, "Insufficient")
        self.assertIn("za mala probka", trends[0].interpretation)


if __name__ == "__main__":
    unittest.main()
