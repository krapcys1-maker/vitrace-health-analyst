from __future__ import annotations

import unittest

from tools.insight_ranker import rank_top_findings


class InsightRankerTest(unittest.TestCase):
    def test_prioritizes_recovery_and_comparable_walking_over_low_confidence_heart(self) -> None:
        ranked = rank_top_findings(
            {
                "activityOverTime": {
                    "available": True,
                    "bestRecentMonth": {
                        "period": "2026-04",
                        "signal": "peak",
                        "steps": 559_104,
                        "avgSteps": 18_637,
                        "estimatedKm": 447.3,
                        "ratioToBaseline": 3.6,
                    },
                    "years": [],
                },
                "walkingFitness": {
                    "distanceBandTrends": [
                        {
                            "distanceBand": "15+ km",
                            "confidence": "Medium",
                            "current": {"period": "2026", "sessions": 5},
                            "previous": {"period": "2025", "sessions": 12},
                            "deltas": {
                                "paceSecondsPerKm": 54.0,
                                "avgHeartRateBpm": 4.2,
                                "kcalPerKm": 12.0,
                            },
                            "interpretation": "wolniej i drozej dla organizmu",
                        }
                    ]
                },
                "activityWorkoutSleepLoad": {
                    "available": True,
                    "pairs": 132,
                    "result": {
                        "confidence": "Medium",
                        "interpretation": "dlugi marsz wyglada jak wieksze obciazenie regeneracji",
                        "typicalNoLongWalk": {"days": 63, "avgSteps": 10078},
                        "highNoLongWalk": {"days": 22, "avgSteps": 26064},
                        "longWalk": {"days": 13, "avgSteps": 26019},
                        "highNoLongWalkVsTypicalDeltas": {
                            "remSleepMinutes": -16.9,
                            "sleepScore": -2.6,
                        },
                        "longWalkVsTypicalDeltas": {
                            "totalSleepMinutes": -25.7,
                            "sleepScore": -4.8,
                        },
                    },
                },
                "heartLoad": {
                    "available": True,
                    "activitySplit": {
                        "confidence": "Low",
                        "interpretation": "monitorowac dalej",
                        "highWithoutActivity": {"days": 11, "sleepDays": 1},
                        "highWithoutActivityVsNormalLowActivity": {
                            "avgBpm": 29.1,
                            "sleepMinutes": -15.8,
                        },
                    },
                },
            },
            limit=4,
        )

        ids = [item["id"] for item in ranked]
        self.assertEqual(ids[0], "activity_workout_sleep_load")
        self.assertIn("walking_band_15_km", ids[:3])
        self.assertIn("heart_load_without_activity", ids)
        self.assertGreater(
            ranked[ids.index("walking_band_15_km")]["priorityScore"],
            ranked[ids.index("heart_load_without_activity")]["priorityScore"],
        )

    def test_uses_persisted_insight_only_as_fallback(self) -> None:
        ranked = rank_top_findings(
            {"activityOverTime": {"available": False}},
            deterministic_insights=[
                {
                    "id": "long_term_steps_km",
                    "domain": "Aktywnosc",
                    "title": "Roczne kroki",
                    "answer": "To jest najmocniejszy zapisany insight.",
                    "confidence": "High",
                    "evidence": ["2025: 3 mln krokow"],
                    "limitations": ["fixture"],
                    "nextStep": "pokazac lata",
                }
            ],
        )

        self.assertEqual(ranked[0]["id"], "persisted_long_term_steps_km")
        self.assertEqual(ranked[0]["confidence"], "High")


if __name__ == "__main__":
    unittest.main()
