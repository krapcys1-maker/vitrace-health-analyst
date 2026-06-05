"""Generate a human-readable body intelligence report from a VitaTrace DB copy."""

from __future__ import annotations

import argparse
import datetime as dt
import math
import sqlite3
import sys
from pathlib import Path

from analysis_rules import (
    CREDIBLE_DAILY_HEART_FILTER_SQL,
    CREDIBLE_WALKING_FILTER_SQL,
    FITNESS_WALKING_FILTER_SQL,
    HEART_MIN_DAILY_SAMPLES,
    LONG_WALK_SLEEP_MIN_DISTANCE_KM,
    ActivityMonth,
    ActivityMonthSignal,
    SleepWindow,
    SleepWindowComparison,
    WalkingBandTrend,
    classify_activity_months,
    compare_sleep_window_to_baseline,
    compare_latest_walking_band_years,
)


DEFAULT_DB = Path("build/phone-db-check/phone-current-vitrace.db")
DEFAULT_OUTPUT = Path("build/body-intelligence-report.md")
DEFAULT_STEPS_PER_KM = 1250


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--db", default=str(DEFAULT_DB), help="Path to a VitaTrace SQLite DB copy.")
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT, help="Markdown output path.")
    parser.add_argument("--cutoff-date", help="Current partial day. Closed days are before this date.")
    args = parser.parse_args()

    db_path = Path(args.db)
    if not db_path.exists():
        print(f"ERROR: DB not found: {db_path}", file=sys.stderr)
        return 2

    with sqlite3.connect(db_path) as con:
        con.row_factory = sqlite3.Row
        cutoff_date = args.cutoff_date or latest_generated_for_date(con) or dt.date.today().isoformat()
        report = build_report(con, db_path, cutoff_date)

    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(report, encoding="utf-8")
    print(f"Wrote {args.output}")
    return 0


def build_report(con: sqlite3.Connection, db_path: Path, cutoff_date: str) -> str:
    steps_per_km = user_steps_per_km(con)
    activity_years = activity_by_year(con, cutoff_date)
    activity_months = activity_by_month(con, cutoff_date)
    sleep_months = sleep_by_month(con, cutoff_date)
    walking_years = credible_walking_by_year(con, cutoff_date)
    fitness_walking_years = fitness_walking_by_year(con, cutoff_date)
    walking_band_trends = compare_latest_walking_band_years(walking_by_distance_band_year(con, cutoff_date))
    running = running_summary(con, cutoff_date)
    intensity = exercise_intensity_summary(con, cutoff_date)
    sleep_activity = sleep_activity_tests(con, cutoff_date)
    long_walk_sleep = sleep_after_long_walks(con, cutoff_date)
    sleep_windows = sleep_debt_windows(con, cutoff_date)
    sleep_window_comparisons = compare_sleep_windows(sleep_windows)
    heart = heart_context(con, cutoff_date)
    coverage = data_coverage(con, cutoff_date)

    lines: list[str] = [
        "# VitaTrace Body Intelligence Report",
        "",
        f"- DB: `{db_path}`",
        f"- Data zamkniecia analizy: `{cutoff_date}`",
        "- Zasada: dzien dzisiejszy jest traktowany jako niepelny i nie wchodzi do trendow.",
        f"- Przelicznik krokow: `{steps_per_km}` krokow/km",
        "",
        "## Co naprawde mowia te dane",
        "",
    ]

    lines.extend(executive_findings(activity_years, activity_months, walking_years, fitness_walking_years, walking_band_trends, running, sleep_activity, long_walk_sleep, sleep_windows, sleep_window_comparisons, heart, steps_per_km))
    lines.extend(coverage_section(coverage))
    lines.extend(activity_section(activity_years, activity_months, steps_per_km))
    lines.extend(walking_section(walking_years, fitness_walking_years, walking_band_trends, intensity))
    lines.extend(running_section(running))
    lines.extend(sleep_section(sleep_months, sleep_windows, sleep_window_comparisons, sleep_activity, long_walk_sleep))
    lines.extend(heart_section(heart))
    lines.extend(product_section())
    lines.extend(reference_section())

    return "\n".join(lines).rstrip() + "\n"


def executive_findings(
    activity_years: list[sqlite3.Row],
    activity_months: list[sqlite3.Row],
    walking_years: list[sqlite3.Row],
    fitness_walking_years: list[sqlite3.Row],
    walking_band_trends: list[WalkingBandTrend],
    running: dict[str, object],
    sleep_activity: dict[str, object],
    long_walk_sleep: dict[str, object],
    sleep_windows: dict[str, object],
    sleep_window_comparisons: list[SleepWindowComparison],
    heart: dict[str, object],
    steps_per_km: int,
) -> list[str]:
    y2024 = row_by(activity_years, "period", "2024")
    y2025 = row_by(activity_years, "period", "2025")
    y2026 = row_by(activity_years, "period", "2026")
    best_month = activity_months[0] if activity_months else None
    last_months = sorted(activity_months, key=lambda row: row["period"])[-6:]
    jan_mar_2026 = [row for row in last_months if row["period"] in {"2026-01", "2026-02", "2026-03"}]
    apr_2026 = row_by(activity_months, "period", "2026-04")
    may_2026 = row_by(activity_months, "period", "2026-05")
    walk2024 = row_by(fitness_walking_years, "period", "2024")
    walk2025 = row_by(fitness_walking_years, "period", "2025")
    walk2026 = row_by(fitness_walking_years, "period", "2026")

    findings: list[str] = []

    if y2024 and y2025:
        step_delta = pct_delta(y2025["steps"], y2024["steps"])
        active_day_delta = pct_delta(y2025["avg_steps"], y2024["avg_steps"])
        findings.append(
            f"1. `2025` byl realnie mocniejszy od `2024`: {fmt_int(y2025['steps'])} krokow vs "
            f"{fmt_int(y2024['steps'])}, czyli {fmt_signed_pct(step_delta)} rocznie. Sredni aktywny dzien wzrosl "
            f"z {fmt_int(y2024['avg_steps'])} do {fmt_int(y2025['avg_steps'])} krokow ({fmt_signed_pct(active_day_delta)})."
        )

    if y2026:
        projected = y2026["steps"] / max(y2026["days"], 1) * 365
        findings.append(
            f"2. `2026` nie wyglada jak cofniecie, tylko jak rok bardzo nierowny: do {y2026['days']} zamknietych dni masz "
            f"{fmt_int(y2026['steps'])} krokow, tempo roczne okolo {fmt_int(projected)} krokow "
            f"({projected / steps_per_km:.0f} km est.), ale wynik mocno ciagnie kwiecien."
        )

    if best_month is not None:
        findings.append(
            f"3. Najwiekszy miesiac to `{best_month['period']}`: {fmt_int(best_month['steps'])} krokow, "
            f"{best_month['steps'] / steps_per_km:.1f} km est., srednio {fmt_int(best_month['avg_steps'])} krokow/dzien. "
            "To jest anomalia/peak do opisania, nie zwykla norma miesieczna."
        )

    if apr_2026 and may_2026:
        findings.append(
            f"4. Po kwietniowym peaku maj nadal byl mocny: {fmt_int(may_2026['steps'])} krokow i "
            f"{fmt_int(may_2026['avg_steps'])}/dzien, ale to juz {fmt_signed_pct(pct_delta(may_2026['avg_steps'], apr_2026['avg_steps']))} "
            "wzgledem kwietnia. Aplikacja powinna pokazywac peak i utrzymanie po peaku osobno."
        )

    if walk2024 and walk2025 and walk2026:
        findings.append(
            f"5. Najciekawszy sygnal kondycji jest w chodzeniu po filtrze wiarygodnych sesji 3-15 km: tempo poprawia sie "
            f"z {pace(walk2024['pace'])} w 2024 do {pace(walk2025['pace'])} w 2025 i {pace(walk2026['pace'])} w 2026. "
            f"Jednoczesnie koszt spada z {fmt1(walk2024['kcal_km'])} do {fmt1(walk2026['kcal_km'])} kcal/km. "
            "To jest bardziej wartosciowe niz sama liczba krokow."
        )

    strong_band = strongest_walking_band_signal(walking_band_trends)
    if strong_band:
        findings.append(
            f"5a. Po rozbiciu chodzenia na podobne dystanse najlepiej widac `{strong_band.distance_band}`: "
            f"{strong_band.current.period} vs {strong_band.previous.period if strong_band.previous else 'brak'} daje "
            f"tempo {fmt_signed_pace(strong_band.pace_seconds_per_km_delta)}, HR "
            f"{fmt_signed_number(strong_band.avg_heart_rate_bpm_delta)} bpm i kcal/km "
            f"{fmt_signed_number(strong_band.kcal_per_km_delta)}. "
            f"Wniosek: {strong_band.interpretation}."
        )

    same = sleep_activity["same_day"]
    prev = sleep_activity["previous_day"]
    findings.append(
        f"6. Hipoteza `wiecej krokow = lepszy sen tej samej nocy` na razie nie przechodzi testu: "
        f"r={fmt_corr(same['corr_total'])} dla dlugosci snu i r={fmt_corr(same['corr_score'])} dla score. "
        "To jest wazny wynik, bo chroni przed ladnie brzmiacym, ale falszywym wnioskiem."
    )

    high_prev = prev["high"]
    low_prev = prev["low"]
    if high_prev and low_prev:
        findings.append(
            f"7. Przy przesunieciu `ruch dzien przed snem` najwyzszy kwartyl krokow ma nizszy sleep score "
            f"({fmt1(high_prev['score'])} vs {fmt1(low_prev['score'])}) i mniej REM "
            f"({fmt1(high_prev['rem'])} vs {fmt1(low_prev['rem'])} min). To nie dowod, ale sensowny trop: "
            "u Ciebie bardzo aktywne dni moga nie pomagac regeneracji automatycznie."
        )

    last30 = sleep_windows["last30"]
    baseline = sleep_windows["baseline"]
    if last30 and baseline:
        last30_comparison = next((item for item in sleep_window_comparisons if item.window.label == "last30"), None)
        suffix = f" Wniosek: {last30_comparison.interpretation}." if last30_comparison else ""
        findings.append(
            f"8. Sen nie wyglada teraz na katastrofe: ostatnie 30 zmierzonych nocy to {minutes_h(last30['total'])}, "
            f"czyli {signed_minutes(last30['total'] - baseline['total'])} wobec baseline. "
            f"Gleboki sen jest {signed_minutes(last30['deep'] - baseline['deep'])}, REM {signed_minutes(last30['rem'] - baseline['rem'])}."
            f"{suffix}"
        )

    if heart["high"] and heart["normal"]:
        findings.append(
            f"9. Dni z wysokim srednim pulsem wygladaja jak dni gorszej regeneracji: sen {minutes_h(heart['high']['sleep'])} "
            f"vs {minutes_h(heart['normal']['sleep'])}. Ale to jest sredni puls dzienny i starsze pokrycie, wiec ma byc "
            "hipoteza do monitorowania live, nie diagnoza."
        )

    long_walk = long_walk_sleep["long_walk"]
    normal_walk = long_walk_sleep["normal"]
    if long_walk and normal_walk:
        findings.append(
            f"10. Po dlugich marszach >= {LONG_WALK_SLEEP_MIN_DISTANCE_KM:.0f} km nastepna noc w danych jest krotsza: "
            f"{minutes_h(long_walk['total'])} vs {minutes_h(normal_walk['total'])}, score "
            f"{fmt1(long_walk['score'])} vs {fmt1(normal_walk['score'])}. Probka dlugich marszow to "
            f"{long_walk['days']} nocy, wiec to hipoteza regeneracji, nie pewny wniosek."
        )

    if running["old"] and running["recent"]:
        findings.append(
            f"11. Bieganie jest za male na trend, ale jako sygnal startowy wyglada ciekawie: 2026 ma srednie tempo "
            f"{pace(running['recent']['pace'])} przy {fmt1(running['recent']['hr'])} bpm, a stare biegi 2023 mialy "
            f"{pace(running['old']['pace'])} przy {fmt1(running['old']['hr'])} bpm. To trzeba zbierac dalej."
        )

    findings.extend(["",])
    return findings


def coverage_section(coverage: dict[str, object]) -> list[str]:
    return [
        "## Jakie dane maja wartosc",
        "",
        "| Obszar | Pokrycie | Wniosek produktowy |",
        "|---|---:|---|",
        f"| Kroki i dystans dzienny | {coverage['activity_days']} dni | najmocniejszy fundament do trendow rocznych/miesiecznych |",
        f"| Chodzenie treningowe | {coverage['walking_sessions']} sesji | najlepsze miejsce do analizy kondycji, tempa, HR i kcal/km |",
        f"| Sen szczegolowy | {coverage['sleep_nights']} nocy | dobry do baseline, ale nie do mocnych tez dzien po dniu bez ostroznosci |",
        f"| Puls dzienny | {coverage['heart_days']} wiarygodnych dni | przydatny jako sygnal obciazenia, ale nie jako odpoczynek/resting HR |",
        f"| Bieganie | {coverage['running_sessions']} sesji | za malo do trendu, wystarczy do listy i obserwacji |",
        f"| Waga/body composition | {coverage['body_days']} dni | za malo, nie budowac jeszcze wnioskow |",
        "",
    ]


def activity_section(years: list[sqlite3.Row], months_by_steps: list[sqlite3.Row], steps_per_km: int) -> list[str]:
    complete_months = sorted([row for row in months_by_steps if row["days"] >= 25], key=lambda row: row["avg_steps"], reverse=True)
    last18 = sorted(months_by_steps, key=lambda row: row["period"])[-18:]
    month_signals = classify_activity_months(
        [
            ActivityMonth(
                period=row["period"],
                days=int(row["days"] or 0),
                steps=int(row["steps"] or 0),
            )
            for row in sorted(months_by_steps, key=lambda item: item["period"])
        ]
    )
    lines = [
        "## Aktywnosc: nie tylko ile krokow, ale kiedy zmienil sie poziom",
        "",
        "| Rok | Dni z aktywnoscia | Kroki | Km est. | Km ze zrodla | Srednio krokow/aktywny dzien |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for row in years:
        lines.append(
            f"| {row['period']} | {row['days']} | {fmt_int(row['steps'])} | "
            f"{row['steps'] / steps_per_km:.1f} | {fmt1(row['source_km'])} | {fmt_int(row['avg_steps'])} |"
        )
    lines.extend([
        "",
        "Najmocniejsze pelne miesiace wedlug sredniej dziennej, a nie tylko sumy:",
        "",
        "| Miesiac | Dni | Kroki | Srednio/dzien | Km est. |",
        "|---|---:|---:|---:|---:|",
    ])
    for row in complete_months[:8]:
        lines.append(f"| {row['period']} | {row['days']} | {fmt_int(row['steps'])} | {fmt_int(row['avg_steps'])} | {row['steps'] / steps_per_km:.1f} |")
    lines.extend([
        "",
        "Ostatnie 18 miesiecy:",
        "",
        "| Miesiac | Kroki | Srednio/dzien | Interpretacja |",
        "|---|---:|---:|---|",
    ])
    for row in last18:
        label = activity_month_label(row, month_signals.get(row["period"]))
        lines.append(f"| {row['period']} | {fmt_int(row['steps'])} | {fmt_int(row['avg_steps'])} | {label} |")
    lines.extend(["",])
    return lines


def walking_section(
    walking_years: list[sqlite3.Row],
    fitness_years: list[sqlite3.Row],
    band_trends: list[WalkingBandTrend],
    intensity: list[sqlite3.Row],
) -> list[str]:
    lines = [
        "## Chodzenie: najlepszy sygnal kondycji",
        "",
        "Najpierw odrzucam ewidentne smieci treningowe: sesje z wielogodzinnym postojem, nielogicznym tempem albo zerowym pulsem. Bez tego minuty treningu klamia.",
        "",
        "Wiarygodne chodzenie szeroko, po filtrze: dystans >=1 km, czas 10 min - 6 h, tempo 8-40 min/km.",
        "",
        "| Rok | Sesje | Km | Srednia sesja | Srednie tempo | Sredni HR | kcal/km | VO2 |",
        "|---|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for row in walking_years:
        lines.append(
            f"| {row['period']} | {row['sessions']} | {fmt1(row['km'])} | {fmt1(row['avg_km'])} km | "
            f"{pace(row['pace'])} | {fmt1(row['hr'])} | {fmt1(row['kcal_km'])} | {fmt1(row['vo2'])} |"
        )
    lines.extend([
        "",
        "Najbardziej porownywalne sesje fitness: 3-15 km, tempo 8-25 min/km.",
        "",
        "| Rok | Sesje | Km | Tempo | HR | kcal/km | Co to znaczy |",
        "|---|---:|---:|---:|---:|---:|---|",
    ])
    previous = None
    for row in fitness_years:
        if previous is None:
            meaning = "punkt odniesienia"
        else:
            pace_improvement = pct_delta(-row["pace"], -previous["pace"])
            meaning = (
                f"tempo {fmt_directional_pct(pace_improvement, 'lepsze', 'gorsze')} wzgledem poprzedniego roku, "
                f"kcal/km {fmt_signed_pct(pct_delta(row['kcal_km'], previous['kcal_km']))}"
            )
        lines.append(
            f"| {row['period']} | {row['sessions']} | {fmt1(row['km'])} | {pace(row['pace'])} | "
            f"{fmt1(row['hr'])} | {fmt1(row['kcal_km'])} | {meaning} |"
        )
        previous = row
    lines.extend([
        "",
        "Porownanie pasm dystansu: najnowszy dostepny rok w danym pasmie kontra poprzedni rok. To jest wazniejsze niz jedna srednia ze wszystkich marszow.",
        "",
        "| Pasmo | Porownanie | Sesje | Tempo | HR | kcal/km | Pewnosc | Wniosek |",
        "|---|---|---:|---:|---:|---:|---|---|",
    ])
    for trend in band_trends:
        previous_period = trend.previous.period if trend.previous else "brak"
        previous_sessions = trend.previous.sessions if trend.previous else 0
        lines.append(
            f"| {trend.distance_band} | {trend.current.period} vs {previous_period} | "
            f"{trend.current.sessions}/{previous_sessions} | {fmt_signed_pace(trend.pace_seconds_per_km_delta)} | "
            f"{fmt_signed_number(trend.avg_heart_rate_bpm_delta)} | {fmt_signed_number(trend.kcal_per_km_delta)} | "
            f"{confidence_pl(trend.confidence)} | {trend.interpretation} |"
        )
    lines.extend([
        "",
        "Wniosek: dla aplikacji to powinien byc osobny modul `wydolnosc chodzenia`: porownuj tylko podobne dystanse, pokazuj tempo + HR + kcal/km, a nie losowe sumy miesieczne.",
        "",
        "Intensywnosc wedlug sredniego HR sesji dla profilu 40 lat, max HR okolo 180 bpm:",
        "",
        "| Typ | Sesje z HR | Niska | Umiarkowana | Wysoka | Sredni HR |",
        "|---|---:|---:|---:|---:|---:|",
    ])
    for row in intensity:
        lines.append(
            f"| {row['workoutType']} | {row['sessions']} | {row['low_sessions']} | "
            f"{row['moderate_sessions']} | {row['vigorous_sessions']} | {fmt1(row['avg_hr'])} |"
        )
    lines.extend([
        "",
        "Uwaga: to jest klasyfikacja po srednim HR calej sesji. Prawdziwe minuty w strefach wymagaja danych punktowych z tetna podczas treningu.",
        "",
    ])
    return lines


def running_section(running: dict[str, object]) -> list[str]:
    lines = [
        "## Bieganie: ciekawy sygnal, ale jeszcze za mala probka",
        "",
        "| Okres | Sesje | Km/sesja | Tempo | HR | Max HR | kcal/km |",
        "|---|---:|---:|---:|---:|---:|---:|",
    ]
    for key in ("old", "recent"):
        row = running[key]
        if not row:
            continue
        label = "2023" if key == "old" else "2026"
        lines.append(
            f"| {label} | {row['sessions']} | {fmt1(row['avg_km'])} | {pace(row['pace'])} | "
            f"{fmt1(row['hr'])} | {fmt1(row['max_hr'])} | {fmt1(row['kcal_km'])} |"
        )
    lines.extend([
        "",
        "Wniosek: jesli bedziesz biegal dalej, po 10-15 nowych biegach da sie zrobic pierwszy sensowny trend: podobny dystans, tempo, HR, max HR, kcal/km, subiektywne samopoczucie z notatek.",
        "",
    ])
    return lines


def sleep_section(
    months: list[sqlite3.Row],
    windows: dict[str, object],
    window_comparisons: list[SleepWindowComparison],
    sleep_activity: dict[str, object],
    long_walk_sleep: dict[str, object],
) -> list[str]:
    lines = [
        "## Sen: baseline jest, ale korelacje trzeba traktowac uczciwie",
        "",
        "Miesiace z minimum 3 nocami:",
        "",
        "| Miesiac | Noce | Sen | REM | Gleboki | Plytki | Awake | Score |",
        "|---|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for row in sorted(months, key=lambda item: item["period"], reverse=True)[:10]:
        lines.append(
            f"| {row['period']} | {row['nights']} | {minutes_h(row['total'])} | {fmt1(row['rem'])} | "
            f"{fmt1(row['deep'])} | {fmt1(row['light'])} | {fmt1(row['awake'])} | {fmt1(row['score'])} |"
        )
    lines.extend([
        "",
        "Ostatnie zmierzone noce kontra poprzedni baseline:",
        "",
        "| Okno | Noce | Sen | Zmiana snu | REM | Gleboki | Score | Pewnosc | Wniosek |",
        "|---|---:|---:|---:|---:|---:|---:|---|---|",
    ])
    for comparison in window_comparisons:
        row = windows[comparison.window.label]
        label = {
            "last7": "ostatnie 7",
            "last14": "ostatnie 14",
            "last30": "ostatnie 30",
        }.get(comparison.window.label, comparison.window.label)
        lines.append(
            f"| {label} | {row['nights']} | {minutes_h(row['total'])} | {signed_minutes(comparison.total_minutes_delta)} | "
            f"{signed_minutes(comparison.rem_minutes_delta)} | {signed_minutes(comparison.deep_minutes_delta)} | "
            f"{fmt1(row['score'])} ({fmt_signed_number(comparison.score_delta)}) | {confidence_pl(comparison.confidence)} | "
            f"{comparison.interpretation} |"
        )
    same = sleep_activity["same_day"]
    previous = sleep_activity["previous_day"]
    lines.extend([
        "",
        "Test hipotezy ruch -> sen:",
        "",
        "| Test | Pary dni | Kroki vs sen | Kroki vs REM | Kroki vs gleboki | Kroki vs score | Wniosek |",
        "|---|---:|---:|---:|---:|---:|---|",
        correlation_row("Ten sam dzien", same),
        correlation_row("Dzien przed snem", previous),
        "",
        "Wniosek: aplikacja nie powinna mowic `ruszales sie wiecej, wiec lepiej spales`. U Ciebie na tych danych zaleznosc jest slaba albo lekko odwrotna dla score/REM.",
        "",
        f"Test regeneracji po dlugim marszu >= {LONG_WALK_SLEEP_MIN_DISTANCE_KM:.0f} km:",
        "",
        "| Grupa | Noce | Marsz poprzedniego dnia | Sen | REM | Gleboki | Score |",
        "|---|---:|---:|---:|---:|---:|---:|",
        sleep_after_walk_row("po dlugim marszu", long_walk_sleep["long_walk"]),
        sleep_after_walk_row("pozostale noce", long_walk_sleep["normal"]),
        "",
        "Wniosek: to test nastepnej nocy po marszu, nie dowod przyczyny. Przy malej probce traktujemy go jako trop regeneracji.",
        "",
    ])
    return lines


def heart_section(heart: dict[str, object]) -> list[str]:
    lines = [
        "## Puls: uzywac jako sygnalu obciazenia, nie jako diagnozy",
        "",
        f"- Wiarygodne dni z pulsem: {heart['days']} / {heart['total_days']} (min. {heart['min_samples']} probki/dzien)",
        f"- Odrzucone dni z za mala liczba probek: {heart['excluded_days']}",
        f"- Sredni dzienny baseline: {fmt1(heart['baseline'])} bpm",
        f"- Prog wysokiego dnia: {fmt1(heart['threshold'])} bpm",
        "",
        "| Grupa | Dni | Puls | Sen | Kroki | Trening min |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for key, label in (("high", "wysoki sredni puls"), ("normal", "pozostale dni")):
        row = heart[key]
        lines.append(f"| {label} | {row['days']} | {fmt1(row['hr'])} | {minutes_h(row['sleep'])} | {fmt_int(row['steps'])} | {fmt1(row['workout'])} |")
    lines.extend([
        "",
        "Wniosek: to jest dobry kandydat na alert `sprawdz regeneracje`, ale dopiero po rozdzieleniu resting HR od sredniego dziennego HR i po lepszym live pokryciu.",
        "",
    ])
    return lines


def product_section() -> list[str]:
    return [
        "## Co z tego powinno trafic do aplikacji",
        "",
        "1. Ekran glowny nie powinien pokazywac danych technicznych. Powinien pokazac 3-5 zdan: `co sie poprawia`, `co spada`, `czego nie wiemy`, `co sprawdzic dalej`.",
        "2. Pierwszy prawdziwy modul: `Aktywnosc w czasie` z rokiem, miesiacem, kilometrami est., kilometrami ze zrodla i wykrywaniem peak/slump.",
        "3. Drugi modul: `Wydolnosc chodzenia`, ale tylko na oczyszczonych sesjach i podobnych dystansach. Metryki: tempo, HR, kcal/km, VO2, dystans.",
        "4. Trzeci modul: `Sen i regeneracja`: baseline miesieczny, ostatnie 7/14/30 zmierzonych nocy, oraz uczciwy test czy ruch pomaga czy przeszkadza.",
        "5. Czwarty modul pozniej: `Puls i obciazenie`, ale najpierw trzeba zbierac lepszy live resting HR albo nocny HR.",
        "6. Waga, tluszcz, miesnie i badania krwi: tylko jako przyszle dane. Nie pokazywac modulu bez realnych rekordow.",
        "",
        "## Co trzeba policzyc nastepne",
        "",
        "1. `peak/slump detector`: miesiac jest nietypowo wysoki/niski wobec poprzednich 6 miesiecy.",
        "2. `walking efficiency trend`: porownanie tylko sesji 3-15 km i osobno dlugich marszow 15+ km.",
        "3. `sleep recovery after long walk`: sen po marszach dlugich vs zwyklych, z przesunieciem daty o noc.",
        "4. `high HR context`: wysokie dni pulsu, ale odrzucic dni z mala liczba probek i rozdzielic trening od spoczynku.",
        "5. `notes layer`: gdy zaczniesz wpisywac samopoczucie, laczyc je z poprzednia noca, krokiem, treningiem i pulsem.",
        "",
    ]


def reference_section() -> list[str]:
    return [
        "## Zasady interpretacji",
        "",
        "- Sen: dorosli zwykle potrzebuja co najmniej 7 godzin snu, ale wearable stage REM/deep/light traktujemy jako estymacje, nie pomiar kliniczny.",
        "- Aktywnosc: progi 150-300 minut tygodniowo sa dobrym kontekstem zdrowotnym, ale w Twoich danych najpierw trzeba odfiltrowac absurdalne czasy treningow.",
        "- Korelacja: `r` blisko zera oznacza, ze apka nie powinna robic ladnego wniosku tylko dlatego, ze dwie rzeczy sa na jednym wykresie.",
        "",
        "Zrodla do zasad ogolnych:",
        "",
        "- CDC: dorosli, sen minimum 7 h: https://www.cdc.gov/sleep/data-research/facts-stats/adults-sleep-facts-and-stats.html",
        "- WHO: aktywnosc doroslych 150-300 min/tydzien: https://www.who.int/initiatives/behealthy/physical-activity",
        "- Review wearable sleep staging: https://pmc.ncbi.nlm.nih.gov/articles/PMC7956647/",
        "",
    ]


def data_coverage(con: sqlite3.Connection, cutoff_date: str) -> dict[str, int]:
    return {
        "activity_days": scalar(con, "select count(*) from daily_activity_summaries where date < ? and (steps > 0 or distanceMeters > 0 or activeCaloriesKcal > 0)", cutoff_date),
        "sleep_nights": scalar(con, "select count(*) from sleep_details where date < ? and totalSleepMinutes > 0", cutoff_date),
        "heart_days": scalar(con, f"select count(*) from daily_heart_summaries where date < ? and {CREDIBLE_DAILY_HEART_FILTER_SQL}", cutoff_date),
        "walking_sessions": scalar(con, "select count(*) from workout_sessions where date < ? and workoutType = 'walking'", cutoff_date),
        "running_sessions": scalar(con, "select count(*) from workout_sessions where date < ? and workoutType = 'running'", cutoff_date),
        "body_days": scalar(con, "select count(*) from daily_body_summaries where date < ? and weightRecordCount > 0", cutoff_date),
    }


def activity_by_year(con: sqlite3.Connection, cutoff_date: str) -> list[sqlite3.Row]:
    return list(con.execute(
        """
        select substr(date, 1, 4) as period, count(*) as days, sum(steps) as steps,
               avg(steps) as avg_steps, sum(distanceMeters) / 1000.0 as source_km,
               sum(activeCaloriesKcal) as active_kcal
        from daily_activity_summaries
        where date < ? and (steps > 0 or distanceMeters > 0 or activeCaloriesKcal > 0)
        group by period
        order by period
        """,
        (cutoff_date,),
    ))


def activity_by_month(con: sqlite3.Connection, cutoff_date: str) -> list[sqlite3.Row]:
    return list(con.execute(
        """
        select substr(date, 1, 7) as period, count(*) as days, sum(steps) as steps,
               avg(steps) as avg_steps, sum(distanceMeters) / 1000.0 as source_km,
               sum(activeCaloriesKcal) as active_kcal
        from daily_activity_summaries
        where date < ? and (steps > 0 or distanceMeters > 0 or activeCaloriesKcal > 0)
        group by period
        order by steps desc
        """,
        (cutoff_date,),
    ))


def credible_walking_by_year(con: sqlite3.Connection, cutoff_date: str) -> list[sqlite3.Row]:
    return list(con.execute(
        f"""
        select substr(date, 1, 4) as period, count(*) as sessions,
               sum(distanceMeters) / 1000.0 as km,
               avg(distanceMeters) / 1000.0 as avg_km,
               avg(avgPaceSecondsPerKm) as pace,
               avg(avgHeartRateBpm) as hr,
               avg(activeCaloriesKcal / nullif(distanceMeters / 1000.0, 0)) as kcal_km,
               avg(vo2Max) as vo2
        from workout_sessions
        where date < ?
          and {CREDIBLE_WALKING_FILTER_SQL}
        group by period
        order by period
        """,
        (cutoff_date,),
    ))


def fitness_walking_by_year(con: sqlite3.Connection, cutoff_date: str) -> list[sqlite3.Row]:
    return list(con.execute(
        f"""
        select substr(date, 1, 4) as period, count(*) as sessions,
               sum(distanceMeters) / 1000.0 as km,
               avg(distanceMeters) / 1000.0 as avg_km,
               avg(avgPaceSecondsPerKm) as pace,
               avg(avgHeartRateBpm) as hr,
               avg(activeCaloriesKcal / nullif(distanceMeters / 1000.0, 0)) as kcal_km,
               avg(vo2Max) as vo2
        from workout_sessions
        where date < ?
          and {FITNESS_WALKING_FILTER_SQL}
        group by period
        order by period
        """,
        (cutoff_date,),
    ))


def walking_by_distance_band_year(con: sqlite3.Connection, cutoff_date: str) -> list[sqlite3.Row]:
    return list(con.execute(
        f"""
        select substr(date, 1, 4) as period,
               case
                 when distanceMeters < 3000 then '1-3 km'
                 when distanceMeters < 6000 then '3-6 km'
                 when distanceMeters < 10000 then '6-10 km'
                 when distanceMeters < 15000 then '10-15 km'
                 else '15+ km'
               end as distance_band,
               count(*) as sessions,
               sum(distanceMeters) / 1000.0 as km,
               avg(avgPaceSecondsPerKm) as pace_seconds_per_km,
               avg(avgHeartRateBpm) as avg_heart_rate_bpm,
               avg(activeCaloriesKcal / nullif(distanceMeters / 1000.0, 0)) as kcal_per_km,
               avg(vo2Max) as vo2
        from workout_sessions
        where date < ?
          and {CREDIBLE_WALKING_FILTER_SQL}
        group by period, distance_band
        order by period, distance_band
        """,
        (cutoff_date,),
    ))


def running_summary(con: sqlite3.Connection, cutoff_date: str) -> dict[str, dict[str, float] | None]:
    def period(where: str) -> dict[str, float] | None:
        row = con.execute(
            f"""
            select count(*) as sessions,
                   avg(distanceMeters) / 1000.0 as avg_km,
                   avg(avgPaceSecondsPerKm) as pace,
                   avg(avgHeartRateBpm) as hr,
                   avg(maxHeartRateBpm) as max_hr,
                   avg(activeCaloriesKcal / nullif(distanceMeters / 1000.0, 0)) as kcal_km
            from workout_sessions
            where date < ? and workoutType = 'running' and {where}
            """,
            (cutoff_date,),
        ).fetchone()
        if not row or row["sessions"] == 0:
            return None
        return dict(row)

    return {
        "old": period("date between '2023-01-01' and '2023-12-31'"),
        "recent": period("date between '2026-01-01' and '2026-12-31'"),
    }


def exercise_intensity_summary(con: sqlite3.Connection, cutoff_date: str) -> list[sqlite3.Row]:
    return list(con.execute(
        """
        select workoutType,
               count(*) as sessions,
               sum(case when avgHeartRateBpm < 90 then 1 else 0 end) as low_sessions,
               sum(case when avgHeartRateBpm >= 90 and avgHeartRateBpm < 126 then 1 else 0 end) as moderate_sessions,
               sum(case when avgHeartRateBpm >= 126 then 1 else 0 end) as vigorous_sessions,
               avg(avgHeartRateBpm) as avg_hr
        from workout_sessions
        where date < ?
          and avgHeartRateBpm is not null
          and avgHeartRateBpm between 40 and 210
          and durationSeconds between 300 and 21600
        group by workoutType
        having sessions >= 3
        order by sessions desc
        """,
        (cutoff_date,),
    ))


def sleep_by_month(con: sqlite3.Connection, cutoff_date: str) -> list[sqlite3.Row]:
    return list(con.execute(
        """
        select substr(date, 1, 7) as period, count(*) as nights,
               avg(totalSleepMinutes) as total,
               avg(remSleepMinutes) as rem,
               avg(deepSleepMinutes) as deep,
               avg(lightSleepMinutes) as light,
               avg(awakeMinutes) as awake,
               avg(sleepScore) as score
        from sleep_details
        where date < ? and totalSleepMinutes > 0
        group by period
        having nights >= 3
        order by period
        """,
        (cutoff_date,),
    ))


def sleep_debt_windows(con: sqlite3.Connection, cutoff_date: str) -> dict[str, dict[str, float]]:
    rows = list(con.execute(
        """
        select date, totalSleepMinutes as total, remSleepMinutes as rem, deepSleepMinutes as deep,
               lightSleepMinutes as light, awakeMinutes as awake, sleepScore as score
        from sleep_details
        where date < ? and totalSleepMinutes > 0
        order by date desc
        """,
        (cutoff_date,),
    ))
    baseline_rows = rows[30:120]
    return {
        "baseline": aggregate_sleep_window(baseline_rows),
        "last7": aggregate_sleep_window(rows[:7]),
        "last14": aggregate_sleep_window(rows[:14]),
        "last30": aggregate_sleep_window(rows[:30]),
    }


def compare_sleep_windows(windows: dict[str, dict[str, float]]) -> list[SleepWindowComparison]:
    baseline = sleep_window_from_dict("baseline", windows["baseline"])
    return [
        compare_sleep_window_to_baseline(sleep_window_from_dict(label, windows[label]), baseline)
        for label in ("last7", "last14", "last30")
    ]


def sleep_window_from_dict(label: str, row: dict[str, float]) -> SleepWindow:
    return SleepWindow(
        label=label,
        nights=int(row["nights"] or 0),
        total_minutes=row.get("total"),
        rem_minutes=row.get("rem"),
        deep_minutes=row.get("deep"),
        light_minutes=row.get("light"),
        awake_minutes=row.get("awake"),
        score=row.get("score"),
    )


def sleep_activity_tests(con: sqlite3.Connection, cutoff_date: str) -> dict[str, dict[str, object]]:
    return {
        "same_day": sleep_activity_test(con, cutoff_date, "a.date = s.date"),
        "previous_day": sleep_activity_test(con, cutoff_date, "date(a.date, '+1 day') = s.date"),
    }


def sleep_activity_test(con: sqlite3.Connection, cutoff_date: str, join_condition: str) -> dict[str, object]:
    rows = list(con.execute(
        f"""
        select a.steps as steps, coalesce(w.totalDurationMinutes, 0) as workout,
               s.totalSleepMinutes as total, s.remSleepMinutes as rem,
               s.deepSleepMinutes as deep, s.sleepScore as score
        from daily_activity_summaries a
        join sleep_details s on {join_condition}
        left join daily_workout_summaries w on w.date = a.date
        where a.date < ? and s.date < ? and a.steps > 0 and s.totalSleepMinutes > 0
        """,
        (cutoff_date, cutoff_date),
    ))
    step_values = sorted([row["steps"] for row in rows])
    low = high = None
    if step_values:
        q1 = step_values[len(step_values) // 4]
        q3 = step_values[(len(step_values) * 3) // 4]
        low_rows = [row for row in rows if row["steps"] <= q1]
        high_rows = [row for row in rows if row["steps"] >= q3]
        low = aggregate_sleep_group(low_rows)
        high = aggregate_sleep_group(high_rows)
    return {
        "pairs": len(rows),
        "corr_total": correlation(rows, "steps", "total"),
        "corr_rem": correlation(rows, "steps", "rem"),
        "corr_deep": correlation(rows, "steps", "deep"),
        "corr_score": correlation(rows, "steps", "score"),
        "low": low,
        "high": high,
    }


def sleep_after_long_walks(con: sqlite3.Connection, cutoff_date: str) -> dict[str, object]:
    rows = list(con.execute(
        f"""
        with walking_days as (
          select date as activityDate,
                 sum(distanceMeters) / 1000.0 as walkingKm,
                 count(*) as walkingSessions
          from workout_sessions
          where date < ?
            and {CREDIBLE_WALKING_FILTER_SQL}
          group by date
        )
        select s.date as sleepDate,
               coalesce(w.walkingKm, 0.0) as prevWalkingKm,
               coalesce(w.walkingSessions, 0) as prevWalkingSessions,
               s.totalSleepMinutes as total,
               s.remSleepMinutes as rem,
               s.deepSleepMinutes as deep,
               s.lightSleepMinutes as light,
               s.sleepScore as score
        from sleep_details s
        left join walking_days w on s.date = date(w.activityDate, '+1 day')
        where s.date < ? and s.totalSleepMinutes > 0
        """,
        (cutoff_date, cutoff_date),
    ))
    long_walk_rows = [row for row in rows if float(row["prevWalkingKm"] or 0) >= LONG_WALK_SLEEP_MIN_DISTANCE_KM]
    normal_rows = [row for row in rows if float(row["prevWalkingKm"] or 0) < LONG_WALK_SLEEP_MIN_DISTANCE_KM]
    return {
        "threshold_km": LONG_WALK_SLEEP_MIN_DISTANCE_KM,
        "pairs": len(rows),
        "long_walk": aggregate_sleep_after_walk_group(long_walk_rows),
        "normal": aggregate_sleep_after_walk_group(normal_rows),
    }


def heart_context(con: sqlite3.Connection, cutoff_date: str) -> dict[str, object]:
    total_days = scalar(
        con,
        "select count(*) from daily_heart_summaries where date < ? and sampleCount > 0 and avgBpm is not null",
        cutoff_date,
    )
    rows = list(con.execute(
        f"""
        select h.date, h.avgBpm as hr, coalesce(a.steps, 0) as steps,
               h.sampleCount as samples, s.totalSleepMinutes as sleep,
               coalesce(w.totalDurationMinutes, 0) as workout
        from daily_heart_summaries h
        left join daily_activity_summaries a on a.date = h.date
        left join sleep_details s on s.date = h.date
        left join daily_workout_summaries w on w.date = h.date
        where h.date < ? and {CREDIBLE_DAILY_HEART_FILTER_SQL}
        """,
        (cutoff_date,),
    ))
    values = [float(row["hr"]) for row in rows]
    baseline = sum(values) / len(values) if values else None
    std = standard_deviation(values)
    threshold = baseline + std if baseline is not None and std is not None else None
    high_rows = [row for row in rows if threshold is not None and row["hr"] >= threshold]
    normal_rows = [row for row in rows if threshold is not None and row["hr"] < threshold]
    return {
        "days": len(rows),
        "total_days": total_days,
        "excluded_days": max(total_days - len(rows), 0),
        "min_samples": HEART_MIN_DAILY_SAMPLES,
        "baseline": baseline,
        "threshold": threshold,
        "high": aggregate_heart_group(high_rows),
        "normal": aggregate_heart_group(normal_rows),
    }


def latest_generated_for_date(con: sqlite3.Connection) -> str | None:
    try:
        row = con.execute("select max(generatedForDate) from analysis_results where isCurrent = 1").fetchone()
    except sqlite3.OperationalError:
        return None
    return row[0] if row and row[0] else None


def user_steps_per_km(con: sqlite3.Connection) -> int:
    try:
        row = con.execute("select stepsPerKm from user_profile order by id limit 1").fetchone()
    except sqlite3.OperationalError:
        return DEFAULT_STEPS_PER_KM
    value = int(row[0]) if row and row[0] else DEFAULT_STEPS_PER_KM
    return value if value > 0 else DEFAULT_STEPS_PER_KM


def scalar(con: sqlite3.Connection, query: str, *params: object) -> int:
    return int(con.execute(query, params).fetchone()[0] or 0)


def row_by(rows: list[sqlite3.Row], key: str, value: str) -> sqlite3.Row | None:
    return next((row for row in rows if row[key] == value), None)


def aggregate_sleep_window(rows: list[sqlite3.Row]) -> dict[str, float]:
    return {
        "nights": len(rows),
        "total": average(rows, "total"),
        "rem": average(rows, "rem"),
        "deep": average(rows, "deep"),
        "light": average(rows, "light"),
        "awake": average(rows, "awake"),
        "score": average(rows, "score"),
    }


def aggregate_sleep_group(rows: list[sqlite3.Row]) -> dict[str, float]:
    return {
        "days": len(rows),
        "total": average(rows, "total"),
        "rem": average(rows, "rem"),
        "deep": average(rows, "deep"),
        "score": average(rows, "score"),
    }


def aggregate_sleep_after_walk_group(rows: list[sqlite3.Row]) -> dict[str, float]:
    return {
        "days": len(rows),
        "prev_walking_km": average(rows, "prevWalkingKm"),
        "total": average(rows, "total"),
        "rem": average(rows, "rem"),
        "deep": average(rows, "deep"),
        "light": average(rows, "light"),
        "score": average(rows, "score"),
    }


def aggregate_heart_group(rows: list[sqlite3.Row]) -> dict[str, float]:
    return {
        "days": len(rows),
        "hr": average(rows, "hr"),
        "sleep": average(rows, "sleep"),
        "steps": average(rows, "steps"),
        "workout": average(rows, "workout"),
    }


def average(rows: list[sqlite3.Row], key: str) -> float | None:
    values = [float(row[key]) for row in rows if row[key] is not None]
    return sum(values) / len(values) if values else None


def correlation(rows: list[sqlite3.Row], x_key: str, y_key: str) -> float | None:
    pairs = [
        (float(row[x_key]), float(row[y_key]))
        for row in rows
        if row[x_key] is not None and row[y_key] is not None
    ]
    if len(pairs) < 10:
        return None
    xs = [pair[0] for pair in pairs]
    ys = [pair[1] for pair in pairs]
    mean_x = sum(xs) / len(xs)
    mean_y = sum(ys) / len(ys)
    var_x = sum((value - mean_x) ** 2 for value in xs)
    var_y = sum((value - mean_y) ** 2 for value in ys)
    if var_x == 0.0 or var_y == 0.0:
        return None
    covariance = sum((x - mean_x) * (y - mean_y) for x, y in pairs)
    return covariance / math.sqrt(var_x * var_y)


def standard_deviation(values: list[float]) -> float | None:
    if len(values) < 2:
        return None
    mean = sum(values) / len(values)
    return math.sqrt(sum((value - mean) ** 2 for value in values) / len(values))


def pct_delta(current: float | None, previous: float | None) -> float | None:
    if current is None or previous in (None, 0):
        return None
    return (float(current) - float(previous)) / abs(float(previous)) * 100.0


def activity_month_label(row: sqlite3.Row, signal: ActivityMonthSignal | None = None) -> str:
    if signal is not None:
        if signal.label == "peak":
            return f"peak vs baseline ({fmt_ratio(signal.ratio_to_baseline)})"
        if signal.label == "slump":
            return f"slump vs baseline ({fmt_ratio(signal.ratio_to_baseline)})"
        if signal.label == "above_baseline":
            return f"powyzej baseline ({fmt_ratio(signal.ratio_to_baseline)})"
        if signal.label == "below_baseline":
            return f"ponizej baseline ({fmt_ratio(signal.ratio_to_baseline)})"
        if signal.label == "near_baseline":
            return f"blisko baseline ({fmt_ratio(signal.ratio_to_baseline)})"
        if signal.label == "partial":
            return "miesiac niepelny"
        if signal.label == "baseline":
            return "budowanie baseline"

    avg_steps = float(row["avg_steps"] or 0)
    if avg_steps >= 15000:
        return "peak, bardzo wysoki poziom"
    if avg_steps >= 10000:
        return "mocny miesiac"
    if avg_steps >= 7000:
        return "solidny poziom"
    if avg_steps >= 5000:
        return "niski/sredni poziom"
    return "slump, malo ruchu"


def fmt_ratio(value: float | None) -> str:
    return "brak" if value is None else f"{value:.2f}x"


def correlation_row(label: str, row: dict[str, object]) -> str:
    conclusion = "brak prostej zaleznosci"
    if row["corr_score"] is not None and row["corr_score"] < -0.2:
        conclusion = "wiecej krokow moze laczyc sie z nizszym score"
    return (
        f"| {label} | {row['pairs']} | {fmt_corr(row['corr_total'])} | "
        f"{fmt_corr(row['corr_rem'])} | {fmt_corr(row['corr_deep'])} | "
        f"{fmt_corr(row['corr_score'])} | {conclusion} |"
    )


def sleep_after_walk_row(label: str, row: dict[str, object] | None) -> str:
    if not row:
        return f"| {label} | 0 | brak | brak | brak | brak | brak |"
    return (
        f"| {label} | {row['days']} | {fmt1(row['prev_walking_km'])} km | "
        f"{minutes_h(row['total'])} | {fmt1(row['rem'])} | {fmt1(row['deep'])} | {fmt1(row['score'])} |"
    )


def strongest_walking_band_signal(trends: list[WalkingBandTrend]) -> WalkingBandTrend | None:
    confidence_weight = {"High": 3.0, "Medium": 2.0, "Low": 1.0, "Insufficient": 0.0}
    scored: list[tuple[float, WalkingBandTrend]] = []
    for trend in trends:
        if trend.previous is None or trend.confidence in {"Insufficient", "Low"}:
            continue
        pace_score = abs(trend.pace_seconds_per_km_delta or 0.0) / 60.0
        heart_score = abs(trend.avg_heart_rate_bpm_delta or 0.0) / 5.0
        kcal_score = abs(trend.kcal_per_km_delta or 0.0) / 5.0
        score = confidence_weight.get(trend.confidence, 0.0) + pace_score + heart_score + kcal_score
        scored.append((score, trend))
    if scored:
        return max(scored, key=lambda item: item[0])[1]
    low_confidence = [
        trend
        for trend in trends
        if trend.previous is not None and trend.confidence == "Low"
    ]
    return low_confidence[0] if low_confidence else None


def fmt_int(value: float | int | None) -> str:
    return "brak" if value is None else f"{float(value):,.0f}".replace(",", " ")


def fmt1(value: float | int | None) -> str:
    return "brak" if value is None else f"{float(value):.1f}"


def fmt_signed_pct(value: float | None) -> str:
    return "brak" if value is None else f"{value:+.1f}%"


def fmt_signed_number(value: float | int | None) -> str:
    return "brak" if value is None else f"{float(value):+.1f}"


def fmt_signed_pace(value: float | int | None) -> str:
    if value is None:
        return "brak"
    return f"{float(value):+.0f} s/km"


def fmt_directional_pct(value: float | None, positive_label: str, negative_label: str) -> str:
    if value is None:
        return "brak"
    label = positive_label if value >= 0 else negative_label
    return f"{label} o {abs(value):.1f}%"


def fmt_corr(value: float | None) -> str:
    return "brak" if value is None else f"{value:+.2f}"


def minutes_h(value: float | int | None) -> str:
    if value is None:
        return "brak"
    return f"{float(value) / 60.0:.2f} h"


def signed_minutes(value: float | int | None) -> str:
    if value is None:
        return "brak"
    return f"{float(value):+.0f} min"


def pace(seconds_per_km: float | int | None) -> str:
    if seconds_per_km is None:
        return "brak"
    seconds = int(round(float(seconds_per_km)))
    return f"{seconds // 60}:{seconds % 60:02d}/km"


def confidence_pl(value: str) -> str:
    return {
        "High": "wysoka",
        "Medium": "srednia",
        "Low": "niska",
        "Insufficient": "za mala probka",
    }.get(value, value)


if __name__ == "__main__":
    raise SystemExit(main())
