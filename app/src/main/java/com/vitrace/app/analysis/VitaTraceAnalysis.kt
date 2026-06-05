package com.vitrace.app.analysis

import com.vitrace.app.health.DiagnosticQuality
import com.vitrace.app.health.HealthDashboard

data class AnalysisBlock(
    val title: String,
    val lines: List<String>,
    val quality: DiagnosticQuality,
)

data class VitaTraceAnalysisReport(
    val readiness: AnalysisBlock,
    val currentInsight: AnalysisBlock,
    val historyPlan: AnalysisBlock,
)

fun buildVitaTraceAnalysis(dashboard: HealthDashboard?): VitaTraceAnalysisReport {
    if (dashboard == null) {
        return VitaTraceAnalysisReport(
            readiness = AnalysisBlock(
                title = "Gotowosc do analizy",
                lines = listOf("najpierw potrzebny jest sync albo import historii"),
                quality = DiagnosticQuality.Warning,
            ),
            currentInsight = AnalysisBlock(
                title = "Wniosek teraz",
                lines = listOf("nie analizujemy trendow bez lokalnego dashboardu"),
                quality = DiagnosticQuality.Warning,
            ),
            historyPlan = historyImportBlock(),
        )
    }

    val coverage = dashboard.signalCounts
    val hasEnoughActivityForTrend = dashboard.last30Days.daysWithActivity >= 7
    val hasContextSignals = coverage.heartDays > 0 || coverage.sleepDays > 0 || coverage.workoutDays > 0

    return VitaTraceAnalysisReport(
        readiness = AnalysisBlock(
            title = "Gotowosc do analizy",
            lines = listOf(
                "aktywne dni: ${dashboard.last30Days.daysWithActivity} / 30",
                "puls: ${coverage.heartDays} / 30 dni",
                "sen: ${coverage.sleepDays} / 30 dni",
                "treningi: ${coverage.workoutDays} / 30 dni",
            ),
            quality = if (hasEnoughActivityForTrend && hasContextSignals) {
                DiagnosticQuality.Good
            } else {
                DiagnosticQuality.Warning
            },
        ),
        currentInsight = AnalysisBlock(
            title = "Wniosek teraz",
            lines = currentInsightLines(dashboard),
            quality = if (hasEnoughActivityForTrend) DiagnosticQuality.Neutral else DiagnosticQuality.Warning,
        ),
        historyPlan = historyImportBlock(),
    )
}

private fun currentInsightLines(dashboard: HealthDashboard): List<String> {
    val lines = mutableListOf<String>()

    if (dashboard.last30Days.daysWithActivity < 7) {
        lines += "za malo dni z danymi, zeby liczyc trend"
    }
    if (dashboard.signalCounts.heartDays == 0) {
        lines += "brak pulsu w Health Connect, analiza obciazenia jest wstrzymana"
    }
    if (dashboard.signalCounts.sleepDays == 0) {
        lines += "brak snu w Health Connect, regeneracji nie analizujemy"
    }
    if (dashboard.last30Days.steps > 0) {
        lines += "aktywnosc z Health Connect jest widoczna, ale zakres jest jeszcze krotki"
    }
    if (lines.isEmpty()) {
        lines += "dane sa gotowe do pierwszej analizy trendow"
    }

    return lines
}

private fun historyImportBlock(): AnalysisBlock {
    return AnalysisBlock(
        title = "Po imporcie historii",
        lines = listOf(
            "porownamy ostatnie 7 dni z typowym tygodniem",
            "wykryjemy spadki aktywnosci i regeneracji",
            "polaczymy treningi z pulsem, snem i masa ciala",
        ),
        quality = DiagnosticQuality.Good,
    )
}
