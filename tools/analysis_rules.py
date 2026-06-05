"""Shared deterministic rules for offline VitaTrace analysis."""

from __future__ import annotations

from dataclasses import dataclass
from statistics import median


WALKING_DISTANCE_BANDS = ("1-3 km", "3-6 km", "6-10 km", "10-15 km", "15+ km")

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

LONG_WALK_SLEEP_MIN_DISTANCE_KM = 8.0
HEART_MIN_DAILY_SAMPLES = 24

CREDIBLE_DAILY_HEART_FILTER_SQL = f"""
sampleCount >= {HEART_MIN_DAILY_SAMPLES}
and avgBpm is not null
and avgBpm between 35 and 220
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


@dataclass(frozen=True)
class WalkingBandYear:
    period: str
    distance_band: str
    sessions: int
    km: float | None
    pace_seconds_per_km: float | None
    avg_heart_rate_bpm: float | None
    kcal_per_km: float | None
    vo2: float | None = None


@dataclass(frozen=True)
class WalkingBandTrend:
    distance_band: str
    current: WalkingBandYear
    previous: WalkingBandYear | None
    confidence: str
    pace_seconds_per_km_delta: float | None
    avg_heart_rate_bpm_delta: float | None
    kcal_per_km_delta: float | None
    vo2_delta: float | None
    interpretation: str


@dataclass(frozen=True)
class SleepWindow:
    label: str
    nights: int
    total_minutes: float | None
    rem_minutes: float | None
    deep_minutes: float | None
    light_minutes: float | None
    awake_minutes: float | None
    score: float | None


@dataclass(frozen=True)
class SleepWindowComparison:
    window: SleepWindow
    baseline: SleepWindow
    confidence: str
    total_minutes_delta: float | None
    rem_minutes_delta: float | None
    deep_minutes_delta: float | None
    light_minutes_delta: float | None
    awake_minutes_delta: float | None
    score_delta: float | None
    interpretation: str


@dataclass(frozen=True)
class ActivitySleepGroup:
    label: str
    days: int
    min_steps: int | None
    max_steps: int | None
    avg_steps: float | None
    total_minutes: float | None
    rem_minutes: float | None
    deep_minutes: float | None
    score: float | None


@dataclass(frozen=True)
class ActivitySleepThresholdResult:
    low: ActivitySleepGroup
    typical: ActivitySleepGroup
    high: ActivitySleepGroup
    confidence: str
    high_total_minutes_delta: float | None
    high_rem_minutes_delta: float | None
    high_deep_minutes_delta: float | None
    high_score_delta: float | None
    low_total_minutes_delta: float | None
    low_rem_minutes_delta: float | None
    low_deep_minutes_delta: float | None
    low_score_delta: float | None
    interpretation: str


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


def compare_latest_walking_band_years(
    rows: list[object],
    *,
    min_current_sessions: int = 3,
    min_previous_sessions: int = 8,
) -> list[WalkingBandTrend]:
    """Compare latest available year to previous year inside each walking band."""

    by_band: dict[str, list[WalkingBandYear]] = {band: [] for band in WALKING_DISTANCE_BANDS}
    for row in rows:
        band_year = walking_band_year_from_row(row)
        by_band.setdefault(band_year.distance_band, []).append(band_year)

    trends: list[WalkingBandTrend] = []
    for band in WALKING_DISTANCE_BANDS:
        years = sorted(by_band.get(band, []), key=lambda item: item.period)
        if not years:
            continue
        current = years[-1]
        previous = years[-2] if len(years) >= 2 else None
        deltas = walking_band_deltas(current, previous)
        confidence = walking_band_confidence(
            current,
            previous,
            min_current_sessions=min_current_sessions,
            min_previous_sessions=min_previous_sessions,
        )
        trends.append(
            WalkingBandTrend(
                distance_band=band,
                current=current,
                previous=previous,
                confidence=confidence,
                pace_seconds_per_km_delta=deltas["pace_seconds_per_km"],
                avg_heart_rate_bpm_delta=deltas["avg_heart_rate_bpm"],
                kcal_per_km_delta=deltas["kcal_per_km"],
                vo2_delta=deltas["vo2"],
                interpretation=walking_band_interpretation(confidence, deltas),
            )
        )
    return trends


def compare_sleep_window_to_baseline(
    window: SleepWindow,
    baseline: SleepWindow,
    *,
    min_window_nights: int = 5,
    min_baseline_nights: int = 30,
) -> SleepWindowComparison:
    deltas = {
        "total_minutes": numeric_delta(window.total_minutes, baseline.total_minutes),
        "rem_minutes": numeric_delta(window.rem_minutes, baseline.rem_minutes),
        "deep_minutes": numeric_delta(window.deep_minutes, baseline.deep_minutes),
        "light_minutes": numeric_delta(window.light_minutes, baseline.light_minutes),
        "awake_minutes": numeric_delta(window.awake_minutes, baseline.awake_minutes),
        "score": numeric_delta(window.score, baseline.score),
    }
    confidence = sleep_window_confidence(
        window,
        baseline,
        min_window_nights=min_window_nights,
        min_baseline_nights=min_baseline_nights,
    )
    return SleepWindowComparison(
        window=window,
        baseline=baseline,
        confidence=confidence,
        total_minutes_delta=deltas["total_minutes"],
        rem_minutes_delta=deltas["rem_minutes"],
        deep_minutes_delta=deltas["deep_minutes"],
        light_minutes_delta=deltas["light_minutes"],
        awake_minutes_delta=deltas["awake_minutes"],
        score_delta=deltas["score"],
        interpretation=sleep_window_interpretation(confidence, deltas),
    )


def sleep_window_confidence(
    window: SleepWindow,
    baseline: SleepWindow,
    *,
    min_window_nights: int,
    min_baseline_nights: int,
) -> str:
    if window.nights < min_window_nights or baseline.nights < min_baseline_nights:
        return "Insufficient"
    if window.nights >= 14 and baseline.nights >= 60:
        return "High"
    if window.nights >= 7 and baseline.nights >= min_baseline_nights:
        return "Medium"
    return "Low"


def sleep_window_interpretation(
    confidence: str,
    deltas: dict[str, float | None],
) -> str:
    if confidence == "Insufficient":
        return "za mala probka, pokazac bez wniosku"

    total_delta = deltas["total_minutes"]
    rem_delta = deltas["rem_minutes"]
    deep_delta = deltas["deep_minutes"]
    score_delta = deltas["score"]

    if total_delta is None:
        return "brak dlugosci snu, nie da sie ocenic okna"

    weak_parts: list[str] = []
    strong_parts: list[str] = []
    if total_delta <= -30:
        weak_parts.append("sen krotszy o co najmniej 30 min")
    elif total_delta >= 30:
        strong_parts.append("sen dluzszy o co najmniej 30 min")

    if score_delta is not None:
        if score_delta <= -5:
            weak_parts.append("score wyraznie nizej")
        elif score_delta >= 5:
            strong_parts.append("score wyraznie wyzej")

    if deep_delta is not None:
        if deep_delta <= -10:
            weak_parts.append("mniej snu glebokiego")
        elif deep_delta >= 10:
            strong_parts.append("wiecej snu glebokiego")

    if rem_delta is not None:
        if rem_delta <= -10:
            weak_parts.append("mniej REM")
        elif rem_delta >= 10:
            strong_parts.append("wiecej REM")

    if weak_parts and strong_parts:
        return "mieszany sygnal: " + "; ".join(weak_parts + strong_parts)
    if weak_parts:
        return "slabsza regeneracja vs baseline: " + "; ".join(weak_parts)
    if strong_parts:
        return "lepsza regeneracja vs baseline: " + "; ".join(strong_parts)
    return "blisko baseline, bez duzej zmiany"


def compare_activity_sleep_thresholds(
    low: ActivitySleepGroup,
    typical: ActivitySleepGroup,
    high: ActivitySleepGroup,
    *,
    min_group_days: int = 10,
) -> ActivitySleepThresholdResult:
    high_deltas = sleep_group_deltas(high, typical)
    low_deltas = sleep_group_deltas(low, typical)
    confidence = activity_sleep_threshold_confidence(low, typical, high, min_group_days=min_group_days)
    return ActivitySleepThresholdResult(
        low=low,
        typical=typical,
        high=high,
        confidence=confidence,
        high_total_minutes_delta=high_deltas["total_minutes"],
        high_rem_minutes_delta=high_deltas["rem_minutes"],
        high_deep_minutes_delta=high_deltas["deep_minutes"],
        high_score_delta=high_deltas["score"],
        low_total_minutes_delta=low_deltas["total_minutes"],
        low_rem_minutes_delta=low_deltas["rem_minutes"],
        low_deep_minutes_delta=low_deltas["deep_minutes"],
        low_score_delta=low_deltas["score"],
        interpretation=activity_sleep_threshold_interpretation(confidence, high_deltas, low_deltas),
    )


def sleep_group_deltas(
    group: ActivitySleepGroup,
    typical: ActivitySleepGroup,
) -> dict[str, float | None]:
    return {
        "total_minutes": numeric_delta(group.total_minutes, typical.total_minutes),
        "rem_minutes": numeric_delta(group.rem_minutes, typical.rem_minutes),
        "deep_minutes": numeric_delta(group.deep_minutes, typical.deep_minutes),
        "score": numeric_delta(group.score, typical.score),
    }


def activity_sleep_threshold_confidence(
    low: ActivitySleepGroup,
    typical: ActivitySleepGroup,
    high: ActivitySleepGroup,
    *,
    min_group_days: int,
) -> str:
    if min(low.days, typical.days, high.days) < min_group_days:
        return "Insufficient"
    if min(low.days, typical.days, high.days) >= 25:
        return "High"
    if min(low.days, typical.days, high.days) >= 15:
        return "Medium"
    return "Low"


def activity_sleep_threshold_interpretation(
    confidence: str,
    high_deltas: dict[str, float | None],
    low_deltas: dict[str, float | None],
) -> str:
    if confidence == "Insufficient":
        return "za mala probka w jednym z progow aktywnosci"

    high_total = high_deltas["total_minutes"]
    high_rem = high_deltas["rem_minutes"]
    high_deep = high_deltas["deep_minutes"]
    high_score = high_deltas["score"]
    low_total = low_deltas["total_minutes"]
    low_score = low_deltas["score"]

    high_weaker = []
    high_better = []
    if high_total is not None:
        if high_total <= -20:
            high_weaker.append("po wysokim ruchu sen jest krotszy")
        elif high_total >= 20:
            high_better.append("po wysokim ruchu sen jest dluzszy")
    if high_score is not None:
        if high_score <= -3:
            high_weaker.append("score jest nizszy")
        elif high_score >= 3:
            high_better.append("score jest wyzszy")
    if high_rem is not None and high_rem <= -10:
        high_weaker.append("REM jest nizszy")
    if high_deep is not None and high_deep >= 10:
        high_better.append("gleboki sen jest wyzszy")

    if high_weaker and high_better:
        return "wysoki ruch daje mieszany sygnal: " + "; ".join(high_weaker + high_better)
    if high_weaker:
        return "wysoki ruch poprzedniego dnia moze pogarszac regeneracje: " + "; ".join(high_weaker)
    if high_better:
        return "wysoki ruch poprzedniego dnia moze pomagac regeneracji: " + "; ".join(high_better)
    if low_total is not None and low_score is not None and low_total >= 15 and low_score >= 2:
        return "nizszy ruch wyglada lepiej niz typowy dzien, ale bez mocnego efektu wysokiego ruchu"
    return "brak wyraznej roznicy miedzy progami aktywnosci"


def walking_band_year_from_row(row: object) -> WalkingBandYear:
    return WalkingBandYear(
        period=str(row_field(row, "period")),
        distance_band=str(row_field(row, "distance_band")),
        sessions=int(row_field(row, "sessions") or 0),
        km=optional_float(row_field(row, "km")),
        pace_seconds_per_km=optional_float(row_field(row, "pace_seconds_per_km", "paceSecondsPerKm", "pace")),
        avg_heart_rate_bpm=optional_float(row_field(row, "avg_heart_rate_bpm", "avgHeartRateBpm", "hr")),
        kcal_per_km=optional_float(row_field(row, "kcal_per_km", "kcalPerKm", "kcal_km")),
        vo2=optional_float(row_field(row, "vo2")),
    )


def walking_band_deltas(
    current: WalkingBandYear,
    previous: WalkingBandYear | None,
) -> dict[str, float | None]:
    if previous is None:
        return {
            "pace_seconds_per_km": None,
            "avg_heart_rate_bpm": None,
            "kcal_per_km": None,
            "vo2": None,
        }
    return {
        "pace_seconds_per_km": numeric_delta(current.pace_seconds_per_km, previous.pace_seconds_per_km),
        "avg_heart_rate_bpm": numeric_delta(current.avg_heart_rate_bpm, previous.avg_heart_rate_bpm),
        "kcal_per_km": numeric_delta(current.kcal_per_km, previous.kcal_per_km),
        "vo2": numeric_delta(current.vo2, previous.vo2),
    }


def walking_band_confidence(
    current: WalkingBandYear,
    previous: WalkingBandYear | None,
    *,
    min_current_sessions: int,
    min_previous_sessions: int,
) -> str:
    if previous is None:
        return "Insufficient"
    if current.sessions < min_current_sessions or previous.sessions < min_previous_sessions:
        return "Insufficient"
    has_core_metrics = (
        current.pace_seconds_per_km is not None
        and previous.pace_seconds_per_km is not None
        and current.avg_heart_rate_bpm is not None
        and previous.avg_heart_rate_bpm is not None
        and current.kcal_per_km is not None
        and previous.kcal_per_km is not None
    )
    if not has_core_metrics:
        return "Low"
    if current.sessions >= 8 and previous.sessions >= 8:
        return "High"
    if current.sessions >= 4 and previous.sessions >= min_previous_sessions:
        return "Medium"
    return "Low"


def walking_band_interpretation(
    confidence: str,
    deltas: dict[str, float | None],
) -> str:
    if confidence == "Insufficient":
        return "za mala probka, pokazac jako obserwacje bez wniosku"

    pace_delta = deltas["pace_seconds_per_km"]
    heart_delta = deltas["avg_heart_rate_bpm"]
    kcal_delta = deltas["kcal_per_km"]
    if pace_delta is None:
        return "brak tempa, nie da sie uczciwie ocenic wydolnosci"

    heart_delta = heart_delta or 0.0
    kcal_delta = kcal_delta or 0.0
    if pace_delta <= -30 and heart_delta <= 0 and kcal_delta <= 0:
        return "szybciej przy nie wyzszym tetnie i nizszym koszcie - mocny sygnal poprawy"
    if pace_delta <= -30 and (heart_delta > 0 or kcal_delta > 0):
        return "szybciej, ale organizm placi wiecej - tempo poprawione kosztem obciazenia"
    if abs(pace_delta) < 30 and (heart_delta <= -3 or kcal_delta <= -5):
        return "tempo podobne, ale koszt nizszy - sygnal lepszej ekonomii"
    if pace_delta >= 30 and (heart_delta >= 3 or kcal_delta >= 5):
        return "wolniej i drozej dla organizmu - mozliwy spadek formy albo trudniejsze warunki"
    if pace_delta >= 30:
        return "wolniej niz poprzednio, ale bez jednoznacznie wyzszego kosztu"
    return "zmiana mala albo mieszana - obserwowac dalej"


def numeric_delta(current: float | None, previous: float | None) -> float | None:
    if current is None or previous is None:
        return None
    return round(current - previous, 1)


def optional_float(value: object) -> float | None:
    if value is None:
        return None
    return float(value)


def row_field(row: object, *names: str) -> object:
    for name in names:
        if isinstance(row, dict) and name in row:
            return row[name]
        try:
            return row[name]  # type: ignore[index]
        except (KeyError, IndexError, TypeError):
            pass
        if hasattr(row, name):
            return getattr(row, name)
    return None
