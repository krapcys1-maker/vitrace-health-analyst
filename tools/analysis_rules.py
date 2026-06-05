"""Shared deterministic rules for offline VitaTrace analysis."""

from __future__ import annotations

from dataclasses import dataclass
from statistics import median


CREDIBLE_WALKING_FILTER_SQL = """
workoutType = 'walking'
and distanceMeters >= 1000
and durationSeconds between 600 and 21600
and avgPaceSecondsPerKm between 480 and 2400
""".strip()

FITNESS_WALKING_FILTER_SQL = """
workoutType = 'walking'
and distanceMeters between 3000 and 15000
and durationSeconds between 600 and 14400
and avgPaceSecondsPerKm between 480 and 1500
""".strip()


@dataclass(frozen=True)
class ActivityMonth:
    period: str
    days: int
    steps: int

    @property
    def avg_steps(self) -> float:
        return self.steps / self.days if self.days > 0 else 0.0


@dataclass(frozen=True)
class ActivityMonthSignal:
    period: str
    label: str
    baseline_avg_steps: float | None
    ratio_to_baseline: float | None
    reason: str


@dataclass(frozen=True)
class WalkingFilterDecision:
    accepted: bool
    distance_band: str | None
    reasons: tuple[str, ...]


def classify_activity_months(
    months: list[ActivityMonth],
    *,
    lookback_months: int = 6,
    min_days: int = 20,
    min_baseline_months: int = 3,
    peak_ratio: float = 1.35,
    slump_ratio: float = 0.65,
) -> dict[str, ActivityMonthSignal]:
    """Classify closed monthly activity against the previous comparable months.

    The comparison uses median average daily steps from the previous eligible
    months, so one extreme month does not become the baseline by itself.
    """

    chronological = sorted(months, key=lambda month: month.period)
    signals: dict[str, ActivityMonthSignal] = {}

    for index, month in enumerate(chronological):
        if month.days < min_days:
            signals[month.period] = ActivityMonthSignal(
                period=month.period,
                label="partial",
                baseline_avg_steps=None,
                ratio_to_baseline=None,
                reason="miesiac ma za malo dni, wiec nie oceniamy go jako trendu",
            )
            continue

        baseline_candidates = [
            candidate
            for candidate in chronological[max(0, index - lookback_months) : index]
            if candidate.days >= min_days
        ]
        if len(baseline_candidates) < min_baseline_months:
            signals[month.period] = ActivityMonthSignal(
                period=month.period,
                label="baseline",
                baseline_avg_steps=None,
                ratio_to_baseline=None,
                reason="budujemy baseline z poprzednich miesiecy",
            )
            continue

        baseline_avg = float(median(candidate.avg_steps for candidate in baseline_candidates))
        ratio = month.avg_steps / baseline_avg if baseline_avg > 0 else None
        if ratio is None:
            label = "unknown"
            reason = "baseline ma zero krokow"
        elif ratio >= peak_ratio:
            label = "peak"
            reason = "wyraznie powyzej mediany poprzednich miesiecy"
        elif ratio <= slump_ratio:
            label = "slump"
            reason = "wyraznie ponizej mediany poprzednich miesiecy"
        elif ratio >= 1.15:
            label = "above_baseline"
            reason = "powyzej poprzedniego baseline"
        elif ratio <= 0.85:
            label = "below_baseline"
            reason = "ponizej poprzedniego baseline"
        else:
            label = "near_baseline"
            reason = "blisko poprzedniego baseline"

        signals[month.period] = ActivityMonthSignal(
            period=month.period,
            label=label,
            baseline_avg_steps=baseline_avg,
            ratio_to_baseline=ratio,
            reason=reason,
        )

    return signals


def evaluate_walking_session(
    *,
    distance_meters: float | int | None,
    duration_seconds: float | int | None,
    pace_seconds_per_km: float | int | None,
    avg_heart_rate_bpm: float | int | None = None,
    fitness_mode: bool = False,
) -> WalkingFilterDecision:
    reasons: list[str] = []

    distance = float(distance_meters or 0)
    duration = float(duration_seconds or 0)
    pace = float(pace_seconds_per_km or 0)
    heart_rate = float(avg_heart_rate_bpm) if avg_heart_rate_bpm is not None else None

    min_distance = 3000.0 if fitness_mode else 1000.0
    max_distance = 15000.0 if fitness_mode else float("inf")
    max_duration = 14400.0 if fitness_mode else 21600.0
    max_pace = 1500.0 if fitness_mode else 2400.0

    if distance < min_distance:
        reasons.append("distance_too_short")
    if distance > max_distance:
        reasons.append("distance_outside_comparable_band")
    if duration < 600:
        reasons.append("duration_too_short")
    if duration > max_duration:
        reasons.append("duration_too_long")
    if pace < 480:
        reasons.append("pace_too_fast_for_walk")
    if pace > max_pace:
        reasons.append("pace_too_slow_or_paused")
    if heart_rate is not None and (heart_rate < 40 or heart_rate > 210):
        reasons.append("heart_rate_not_credible")

    return WalkingFilterDecision(
        accepted=not reasons,
        distance_band=walking_distance_band(distance) if not reasons else None,
        reasons=tuple(reasons),
    )


def walking_distance_band(distance_meters: float) -> str:
    km = distance_meters / 1000.0
    if km < 3:
        return "1-3 km"
    if km < 6:
        return "3-6 km"
    if km < 10:
        return "6-10 km"
    if km < 15:
        return "10-15 km"
    return "15+ km"
