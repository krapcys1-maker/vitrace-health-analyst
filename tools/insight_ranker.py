"""Rank deterministic VitaTrace findings for the main human report."""

from __future__ import annotations

from typing import Any


CONFIDENCE_SCORE = {
    "High": 24.0,
    "Medium": 16.0,
    "Low": 7.0,
    "Insufficient": -40.0,
}


def rank_top_findings(
    engine_facts: dict[str, Any],
    *,
    deterministic_insights: list[dict[str, Any]] | None = None,
    limit: int = 5,
) -> list[dict[str, Any]]:
    """Return the most useful user-facing findings from deterministic facts.

    This is deliberately conservative: weak data can still appear as a
    monitoring candidate, but it cannot outrank medium/high confidence signals.
    """

    candidates: list[dict[str, Any]] = []
    candidates.extend(activity_candidates(engine_facts.get("activityOverTime", {})))
    candidates.extend(walking_candidates(engine_facts.get("walkingFitness", {})))
    candidates.extend(sleep_baseline_candidates(engine_facts.get("sleepBaseline", {})))
    candidates.extend(activity_sleep_candidates(engine_facts.get("activitySleepThresholds", {})))
    candidates.extend(workout_sleep_candidates(engine_facts.get("activityWorkoutSleepLoad", {})))
    candidates.extend(long_walk_sleep_candidates(engine_facts.get("sleepAfterLongWalks", {})))
    candidates.extend(heart_load_candidates(engine_facts.get("heartLoad", {})))
    candidates.extend(persisted_insight_candidates(deterministic_insights or []))

    ranked = sorted(
        deduplicate_candidates(candidates),
        key=lambda item: (-float(item["priorityScore"]), item["id"]),
    )
    return ranked[:limit]


def activity_candidates(facts: dict[str, Any]) -> list[dict[str, Any]]:
    if not facts.get("available"):
        return []
    candidates: list[dict[str, Any]] = []
    best = facts.get("bestRecentMonth")
    if isinstance(best, dict) and best.get("signal") in {"peak", "above_baseline"}:
        ratio = float(best.get("ratioToBaseline") or 1.0)
        candidates.append(
            candidate(
                "activity_peak_month",
                "Aktywnosc",
                "Najwieksza zmiana aktywnosci jest miesieczna, nie dzienna",
                f"{best.get('period')} wybija sie jako {best.get('signal')}: {fmt_int(best.get('steps'))} krokow, {fmt1(best.get('estimatedKm'))} km est.",
                "To pokazuje realny skok poziomu ruchu i chroni przed patrzeniem tylko na dzisiejszy wynik.",
                "Medium",
                76.0 + min(ratio * 5.0, 20.0),
                [
                    f"miesiac: {best.get('period')}",
                    f"kroki: {fmt_int(best.get('steps'))}",
                    f"srednio dziennie: {fmt_int(best.get('avgSteps'))}",
                    f"relacja do baseline: {fmt_ratio(best.get('ratioToBaseline'))}",
                ],
                [
                    "miesiac moze zawierac nietypowy okres urlopu, pracy albo trasy",
                    "same kroki nie mowia jeszcze o regeneracji",
                ],
                "porownac miesiace po sredniej dziennej i sprawdzic, czy peak utrzymal sie w nastepnym miesiacu",
            )
        )
    years = facts.get("years") if isinstance(facts.get("years"), list) else []
    if len(years) >= 2:
        previous, current = years[-2], years[-1]
        delta = pct_delta(current.get("avgSteps"), previous.get("avgSteps"))
        if delta is not None and abs(delta) >= 8:
            direction = "wyzej" if delta > 0 else "nizej"
            candidates.append(
                candidate(
                    "activity_year_shift",
                    "Aktywnosc",
                    "Roczny poziom ruchu zmienil sie wzgledem poprzedniego roku",
                    f"{current.get('period')} jest {direction} o {abs(delta):.1f}% w srednich krokach/dzien vs {previous.get('period')}.",
                    "To jest stabilniejszy sygnal niz pojedyncze dni.",
                    "Medium",
                    62.0 + min(abs(delta), 18.0),
                    [
                        f"{previous.get('period')}: {fmt_int(previous.get('avgSteps'))} krokow/dzien",
                        f"{current.get('period')}: {fmt_int(current.get('avgSteps'))} krokow/dzien",
                        f"km est. w {current.get('period')}: {fmt1(current.get('estimatedKm'))}",
                    ],
                    ["rok biezacy moze byc niepelny, trzeba patrzec na zamkniete dni"],
                    "pokazac projekcje roku tylko jako tempo z zamknietych dni, nie jako pewny wynik",
                )
            )
    return candidates


def walking_candidates(facts: dict[str, Any]) -> list[dict[str, Any]]:
    trends = facts.get("distanceBandTrends")
    if not isinstance(trends, list):
        return []
    candidates: list[dict[str, Any]] = []
    for trend in trends:
        if not isinstance(trend, dict):
            continue
        confidence = str(trend.get("confidence") or "Insufficient")
        if confidence == "Insufficient":
            continue
        deltas = trend.get("deltas") if isinstance(trend.get("deltas"), dict) else {}
        current = trend.get("current") if isinstance(trend.get("current"), dict) else {}
        previous = trend.get("previous") if isinstance(trend.get("previous"), dict) else {}
        pace_delta = as_float(deltas.get("paceSecondsPerKm"))
        hr_delta = as_float(deltas.get("avgHeartRateBpm"))
        kcal_delta = as_float(deltas.get("kcalPerKm"))
        score = 72.0 + CONFIDENCE_SCORE.get(confidence, 0.0)
        score += min(abs(pace_delta or 0.0) / 6.0, 14.0)
        score += min(abs(hr_delta or 0.0) * 1.4, 10.0)
        score += min(abs(kcal_delta or 0.0) / 2.0, 10.0)
        candidates.append(
            candidate(
                f"walking_band_{safe_id(trend.get('distanceBand'))}",
                "Trening",
                f"Chodzenie {trend.get('distanceBand')} mowi wiecej niz suma krokow",
                f"{trend.get('interpretation')}.",
                "Porownujemy podobne dystanse, wiec to jeden z najlepszych sygnalow kondycji.",
                confidence,
                score,
                [
                    f"porownanie: {current.get('period')} vs {previous.get('period')}",
                    f"sesje: {current.get('sessions')} vs {previous.get('sessions')}",
                    f"tempo: {fmt_signed(pace_delta, ' s/km')}",
                    f"puls: {fmt_signed(hr_delta, ' bpm')}",
                    f"kcal/km: {fmt_signed(kcal_delta, '')}",
                ],
                [
                    "nie znamy pogody, przewyzszen, obciazenia dnia i przerw",
                    "trzeba unikac mieszania krotkich spacerow z dlugimi marszami",
                ],
                "zbierac porownywalne marsze w tym samym pasmie dystansu i pozniej porownywac podobne trasy",
            )
        )
    return candidates


def sleep_baseline_candidates(facts: dict[str, Any]) -> list[dict[str, Any]]:
    if not facts.get("available"):
        return []
    comparisons = facts.get("comparisons")
    if not isinstance(comparisons, list):
        return []
    candidates: list[dict[str, Any]] = []
    for item in comparisons:
        if not isinstance(item, dict):
            continue
        confidence = str(item.get("confidence") or "Insufficient")
        if confidence == "Insufficient":
            continue
        deltas = item.get("deltas") if isinstance(item.get("deltas"), dict) else {}
        window = item.get("window") if isinstance(item.get("window"), dict) else {}
        label = str(window.get("label") or "window")
        total = as_float(deltas.get("totalSleepMinutes"))
        rem = as_float(deltas.get("remSleepMinutes"))
        deep = as_float(deltas.get("deepSleepMinutes"))
        score_delta = as_float(deltas.get("sleepScore"))
        effect = max(abs(total or 0.0) / 3.0, abs(rem or 0.0), abs(deep or 0.0), abs(score_delta or 0.0) * 4.0)
        interpretation = str(item.get("interpretation") or "")
        if effect < 10.0 and "blisko baseline" in interpretation:
            continue
        candidates.append(
            candidate(
                f"sleep_baseline_{label}",
                "Sen",
                f"Ostatnie {label.replace('last', '')} zmierzonych nocy vs Twoj baseline",
                f"{interpretation}.",
                "To jest lepsze niz ocena jednej nocy, bo porownuje sen do Twojej normy.",
                confidence,
                66.0 + CONFIDENCE_SCORE.get(confidence, 0.0) + min(effect, 18.0),
                [
                    f"noce w oknie: {window.get('nights')}",
                    f"caly sen: {fmt_signed(total, ' min')}",
                    f"REM: {fmt_signed(rem, ' min')}",
                    f"gleboki: {fmt_signed(deep, ' min')}",
                    f"score: {fmt_signed(score_delta, '')}",
                ],
                ["fazy snu z zegarka sa estymacja", "to sa zmierzone noce, nie zawsze ciagly kalendarz"],
                "pokazac 7/14/30 nocy obok kreski osobistego baseline",
            )
        )
    return candidates


def activity_sleep_candidates(facts: dict[str, Any]) -> list[dict[str, Any]]:
    if not facts.get("available"):
        return []
    result = facts.get("result") if isinstance(facts.get("result"), dict) else {}
    confidence = str(result.get("confidence") or "Insufficient")
    if confidence == "Insufficient":
        return []
    deltas = result.get("highVsTypicalDeltas") if isinstance(result.get("highVsTypicalDeltas"), dict) else {}
    rem = as_float(deltas.get("remSleepMinutes"))
    score_delta = as_float(deltas.get("sleepScore"))
    total = as_float(deltas.get("totalSleepMinutes"))
    if max(abs(rem or 0.0), abs(score_delta or 0.0) * 4.0, abs(total or 0.0) / 3.0) < 8:
        return []
    high = result.get("high") if isinstance(result.get("high"), dict) else {}
    typical = result.get("typical") if isinstance(result.get("typical"), dict) else {}
    return [
        candidate(
            "activity_sleep_threshold",
            "Regeneracja",
            "Wysoka aktywnosc nie wyglada automatycznie jak lepszy sen",
            f"{result.get('interpretation')}.",
            "To odpowiada na glowna hipoteze: czy wiecej ruchu pomaga Twojemu snu.",
            confidence,
            70.0 + CONFIDENCE_SCORE.get(confidence, 0.0) + min(abs(rem or 0.0), 16.0),
            [
                f"pary aktywnosc -> sen: {facts.get('pairs')}",
                f"wysoki ruch: {fmt_int(high.get('avgSteps'))} krokow sr.",
                f"typowy ruch: {fmt_int(typical.get('avgSteps'))} krokow sr.",
                f"sen: {fmt_signed(total, ' min')}",
                f"REM: {fmt_signed(rem, ' min')}",
                f"score: {fmt_signed(score_delta, '')}",
            ],
            ["to obserwacja, nie dowod przyczyny", "nie kontroluje stresu, pracy, choroby i godziny treningu"],
            "rozbijac aktywnosc na dlugie marsze, treningi i same wysokie kroki",
        )
    ]


def workout_sleep_candidates(facts: dict[str, Any]) -> list[dict[str, Any]]:
    if not facts.get("available"):
        return []
    result = facts.get("result") if isinstance(facts.get("result"), dict) else {}
    confidence = str(result.get("confidence") or "Insufficient")
    if confidence == "Insufficient":
        return []
    high_deltas = result.get("highNoLongWalkVsTypicalDeltas") if isinstance(result.get("highNoLongWalkVsTypicalDeltas"), dict) else {}
    long_deltas = result.get("longWalkVsTypicalDeltas") if isinstance(result.get("longWalkVsTypicalDeltas"), dict) else {}
    high_rem = as_float(high_deltas.get("remSleepMinutes"))
    high_score = as_float(high_deltas.get("sleepScore"))
    long_total = as_float(long_deltas.get("totalSleepMinutes"))
    long_score = as_float(long_deltas.get("sleepScore"))
    effect = max(abs(high_rem or 0.0), abs(high_score or 0.0) * 4.0, abs(long_total or 0.0) / 2.0, abs(long_score or 0.0) * 4.0)
    if effect < 8:
        return []
    high = result.get("highNoLongWalk") if isinstance(result.get("highNoLongWalk"), dict) else {}
    long_walk = result.get("longWalk") if isinstance(result.get("longWalk"), dict) else {}
    typical = result.get("typicalNoLongWalk") if isinstance(result.get("typicalNoLongWalk"), dict) else {}
    return [
        candidate(
            "activity_workout_sleep_load",
            "Regeneracja",
            "Regeneracja zalezy od rodzaju obciazenia, nie tylko od liczby krokow",
            f"{result.get('interpretation')}.",
            "Silnik oddziela same wysokie kroki od dlugich marszow, wiec nie wrzuca wszystkiego do jednego worka.",
            confidence,
            82.0 + CONFIDENCE_SCORE.get(confidence, 0.0) + min(effect, 18.0),
            [
                f"typowy dzien: {typical.get('days')} dni, {fmt_int(typical.get('avgSteps'))} krokow sr.",
                f"wysokie kroki bez dlugiego marszu: {high.get('days')} dni, REM {fmt_signed(high_rem, ' min')}, score {fmt_signed(high_score, '')}",
                f"dlugi marsz: {long_walk.get('days')} dni, sen {fmt_signed(long_total, ' min')}, score {fmt_signed(long_score, '')}",
            ],
            ["nie znamy pory dnia, pogody, stresu i trasy", "to nadal porownanie obserwacyjne"],
            "dodac godzine treningu i porownanie podobnych tras, zeby sprawdzic koszt regeneracyjny",
        )
    ]


def long_walk_sleep_candidates(facts: dict[str, Any]) -> list[dict[str, Any]]:
    if not facts.get("available"):
        return []
    delta = facts.get("delta") if isinstance(facts.get("delta"), dict) else {}
    long_walk = facts.get("longWalkSleep") if isinstance(facts.get("longWalkSleep"), dict) else {}
    normal = facts.get("normalSleep") if isinstance(facts.get("normalSleep"), dict) else {}
    days = int(long_walk.get("days") or 0)
    total = as_float(delta.get("totalSleepMinutes"))
    score_delta = as_float(delta.get("sleepScore"))
    if days < 8 or max(abs(total or 0.0) / 2.0, abs(score_delta or 0.0) * 4.0) < 8:
        return []
    confidence = "Medium" if days >= 12 else "Low"
    return [
        candidate(
            "sleep_after_long_walks",
            "Regeneracja",
            "Dlugie marsze maja osobny koszt regeneracyjny do sprawdzenia",
            f"Po marszach >= {fmt1(facts.get('thresholdKm'))} km nastepna noc jest krotsza o {fmt_signed(total, ' min')} vs inne zmierzone noce.",
            "To wskazuje konkretny test: czy bardzo dlugie chodzenie wymaga wiecej regeneracji.",
            confidence,
            74.0 + CONFIDENCE_SCORE.get(confidence, 0.0) + min(abs(total or 0.0) / 2.0, 16.0),
            [
                f"noce po dlugim marszu: {days}",
                f"inne noce: {normal.get('days')}",
                f"sen: {fmt_signed(total, ' min')}",
                f"score: {fmt_signed(score_delta, '')}",
            ],
            ["probka dlugich marszow jest mala", "nie znamy trasy, pogody i obciazenia dnia"],
            "porownac dni po marszach 8+ km z podobnymi dniami bez dlugiego marszu",
        )
    ]


def heart_load_candidates(facts: dict[str, Any]) -> list[dict[str, Any]]:
    if not facts.get("available"):
        return []
    split = facts.get("activitySplit") if isinstance(facts.get("activitySplit"), dict) else {}
    confidence = str(split.get("confidence") or "Insufficient")
    if confidence == "Insufficient":
        return []
    unexplained = split.get("highWithoutActivityVsNormalLowActivity") if isinstance(split.get("highWithoutActivityVsNormalLowActivity"), dict) else {}
    group = split.get("highWithoutActivity") if isinstance(split.get("highWithoutActivity"), dict) else {}
    hr_delta = as_float(unexplained.get("avgBpm"))
    sleep_delta = as_float(unexplained.get("sleepMinutes"))
    if group.get("days", 0) < 5 or (hr_delta or 0.0) < 10:
        return []
    return [
        candidate(
            "heart_load_without_activity",
            "Puls",
            "Czesc wysokiego pulsu warto sledzic poza treningiem",
            f"{split.get('interpretation')}.",
            "To rozdziela normalna reakcje na ruch od dni, ktore moga wymagac sprawdzenia regeneracji.",
            confidence,
            56.0 + CONFIDENCE_SCORE.get(confidence, 0.0) + min((hr_delta or 0.0), 18.0),
            [
                f"dni wysokiego pulsu bez duzej aktywnosci: {group.get('days')}",
                f"puls vs spokojniejsze dni: {fmt_signed(hr_delta, ' bpm')}",
                f"sen vs spokojniejsze dni: {fmt_signed(sleep_delta, ' min')}",
                f"noce ze snem w tej grupie: {group.get('sleepDays')}",
            ],
            ["to sredni dzienny puls, nie resting HR", "slabe pokrycie snem obniza pewnosc"],
            "zbierac live resting/nocny HR i oznaczac dni choroby, stresu albo kofeiny",
        )
    ]


def persisted_insight_candidates(insights: list[dict[str, Any]]) -> list[dict[str, Any]]:
    candidates: list[dict[str, Any]] = []
    for insight in insights:
        if not isinstance(insight, dict):
            continue
        if insight.get("id") == "data_coverage_reality":
            continue
        confidence = str(insight.get("confidence") or "Insufficient")
        if confidence == "Insufficient":
            continue
        # Persisted insights are a fallback, so they do not outrank richer
        # engine facts built above.
        candidates.append(
            candidate(
                f"persisted_{safe_id(insight.get('id'))}",
                str(insight.get("domain") or "Analiza"),
                str(insight.get("title") or "Wniosek z silnika"),
                str(insight.get("answer") or ""),
                "To jest zapisany insight z lokalnego silnika, gotowy do pokazania po uporzadkowaniu.",
                confidence,
                45.0 + CONFIDENCE_SCORE.get(confidence, 0.0),
                keep_strings(insight.get("evidence"))[:4],
                keep_strings(insight.get("limitations"))[:3],
                str(insight.get("nextStep") or "sprawdzic w kolejnym przebiegu silnika"),
            )
        )
    return candidates


def candidate(
    item_id: str,
    domain: str,
    title: str,
    message: str,
    why: str,
    confidence: str,
    priority_score: float,
    evidence: list[str],
    limitations: list[str],
    next_step: str,
) -> dict[str, Any]:
    return {
        "id": item_id,
        "domain": domain,
        "title": title,
        "message": message,
        "whyItMatters": why,
        "confidence": confidence,
        "priorityScore": round(priority_score, 1),
        "evidence": evidence,
        "limitations": limitations,
        "nextStep": next_step,
    }


def deduplicate_candidates(candidates: list[dict[str, Any]]) -> list[dict[str, Any]]:
    by_domain: dict[str, list[dict[str, Any]]] = {}
    for item in candidates:
        by_domain.setdefault(str(item.get("domain")), []).append(item)

    result: list[dict[str, Any]] = []
    recovery_kept = 0
    walking_band_kept = 0
    sleep_baseline_kept = 0
    for item in sorted(candidates, key=lambda value: -float(value["priorityScore"])):
        domain = str(item.get("domain"))
        item_id = str(item.get("id"))
        if item_id.startswith("sleep_baseline_"):
            if sleep_baseline_kept >= 1:
                continue
            sleep_baseline_kept += 1
        if item_id.startswith("walking_band_"):
            if walking_band_kept >= 1:
                continue
            walking_band_kept += 1
        if domain == "Regeneracja":
            if recovery_kept >= 2:
                continue
            recovery_kept += 1
        if item_id.startswith("persisted_") and any(existing["domain"] == domain for existing in result):
            continue
        result.append(item)
    return result


def as_float(value: object) -> float | None:
    if value is None:
        return None
    try:
        return float(value)
    except (TypeError, ValueError):
        return None


def pct_delta(current: object, previous: object) -> float | None:
    current_float = as_float(current)
    previous_float = as_float(previous)
    if current_float is None or previous_float in (None, 0.0):
        return None
    return (current_float - previous_float) / abs(previous_float) * 100.0


def fmt_int(value: object) -> str:
    number = as_float(value)
    return "brak" if number is None else f"{number:,.0f}".replace(",", " ")


def fmt1(value: object) -> str:
    number = as_float(value)
    return "brak" if number is None else f"{number:.1f}"


def fmt_ratio(value: object) -> str:
    number = as_float(value)
    return "brak" if number is None else f"{number:.2f}x"


def fmt_signed(value: object, suffix: str) -> str:
    number = as_float(value)
    return "brak" if number is None else f"{number:+.1f}{suffix}"


def safe_id(value: object) -> str:
    text = str(value or "unknown").lower()
    normalized = "".join(char if char.isalnum() else "_" for char in text).strip("_")
    while "__" in normalized:
        normalized = normalized.replace("__", "_")
    return normalized


def keep_strings(value: object) -> list[str]:
    if not isinstance(value, list):
        return []
    return [item for item in value if isinstance(item, str)]
