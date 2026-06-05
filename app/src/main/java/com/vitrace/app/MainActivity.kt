package com.vitrace.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.compose.foundation.Canvas
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.vitrace.app.analysis.AnalysisBlock
import com.vitrace.app.analysis.AnalysisConfidence
import com.vitrace.app.analysis.MonthlySportTrend
import com.vitrace.app.analysis.PersonalAnalysisContext
import com.vitrace.app.analysis.SportEfficiencyComparison
import com.vitrace.app.analysis.TestedInsight
import com.vitrace.app.analysis.buildVitaTraceAnalysis
import com.vitrace.app.data.UserProfileEntity
import com.vitrace.app.health.ActivityWindow
import com.vitrace.app.health.BodyDomainSummary
import com.vitrace.app.health.DataQualityItem
import com.vitrace.app.health.DailySyncSummary
import com.vitrace.app.health.DiagnosticQuality
import com.vitrace.app.health.DiagnosticRow
import com.vitrace.app.health.HealthDashboard
import com.vitrace.app.health.HealthConnectDiagnostics
import com.vitrace.app.health.HealthConnectDiagnosticsRepository
import com.vitrace.app.health.HealthConnectSdkStatus
import com.vitrace.app.health.HealthJournalSummary
import com.vitrace.app.health.LongTermActivitySummary
import com.vitrace.app.health.SleepDomainSummary
import com.vitrace.app.health.SportDomainSummary
import com.vitrace.app.health.WorkoutTypeDaySummary
import com.vitrace.app.health.WorkoutTypeSummary
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.roundToLong

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            VitaTraceApp()
        }
    }
}

@Composable
private fun VitaTraceApp() {
    MaterialTheme(
        colorScheme = MaterialTheme.colorScheme.copy(
            primary = Color(0xFF0F766E),
            secondary = Color(0xFF2563EB),
            surface = Color(0xFFF8FAFC),
            background = Color(0xFFF8FAFC),
        )
    ) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background,
        ) {
            HealthConnectScreen()
        }
    }
}

@Composable
private fun HealthConnectScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var diagnostics by remember { mutableStateOf<HealthConnectDiagnostics?>(null) }
    var loading by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract(),
    ) {
        scope.launch {
            loading = true
            diagnostics = HealthConnectDiagnosticsRepository.load(context, syncFromHealthConnect = true)
            loading = false
        }
    }

    fun refresh(syncFromHealthConnect: Boolean) {
        scope.launch {
            loading = true
            diagnostics = HealthConnectDiagnosticsRepository.load(
                context = context,
                syncFromHealthConnect = syncFromHealthConnect,
            )
            loading = false
        }
    }

    LaunchedEffect(Unit) {
        refresh(syncFromHealthConnect = false)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Header()
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            DataRealityScreen(
                diagnostics = diagnostics,
                loading = loading,
                onRequestPermissions = {
                    val openedSettings = openHealthConnectPermissions(context)
                    if (!openedSettings) {
                        permissionLauncher.launch(HealthConnectDiagnosticsRepository.requiredPermissions)
                    }
                },
                onRefresh = { refresh(syncFromHealthConnect = true) },
            )
            Spacer(modifier = Modifier.height(96.dp))
        }
    }
}

private fun openHealthConnectPermissions(context: android.content.Context): Boolean {
    val intent = Intent("android.health.connect.action.MANAGE_HEALTH_PERMISSIONS")
        .putExtra(Intent.EXTRA_PACKAGE_NAME, context.packageName)
        .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    return try {
        context.startActivity(intent)
        true
    } catch (_: ActivityNotFoundException) {
        false
    } catch (_: SecurityException) {
        false
    }
}

@Composable
private fun Header() {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(
            text = "VitaTrace",
            style = MaterialTheme.typography.headlineLarge,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF0F172A),
        )
        Text(
            text = "Osobista analiza ciala",
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFF475569),
        )
    }
}

@Composable
private fun DataRealityScreen(
    diagnostics: HealthConnectDiagnostics?,
    loading: Boolean,
    onRequestPermissions: () -> Unit,
    onRefresh: () -> Unit,
) {
    if (diagnostics == null) {
        AnalysisCard(
            title = if (loading) "Czytam lokalna baze" else "Brak kontekstu",
            lines = listOf(
                "najpierw ladujemy dane z telefonu",
                "ten ekran ma pokazac co da sie z nich uczciwie wyczytac",
            ),
            quality = DiagnosticQuality.Neutral,
        )
        return
    }

    diagnostics.error?.let { error ->
        SyncNotice(error)
    }

    val insights = diagnostics.insights

    RealityHeroCard(
        title = "Feed wnioskow",
        answer = "Najpierw odpowiedz i pewnosc. Dowody, ograniczenia i nastepny test sa pod szczegolami.",
        evidence = listOf(
            "wnioski: ${insights.size}",
            "wysoka pewnosc: ${insights.count { insight -> insight.confidence == AnalysisConfidence.High }}",
            "za mala probka: ${insights.count { insight -> insight.confidence == AnalysisConfidence.Insufficient }}",
        ),
    )

    insights.forEach { insight ->
        InsightCard(insight)
    }

    ActionSection(
        diagnostics = diagnostics,
        loading = loading,
        onRequestPermissions = onRequestPermissions,
        onRefresh = onRefresh,
    )
}

@Composable
private fun RealityHeroCard(
    title: String,
    answer: String,
    evidence: List<String>,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF09090B)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = answer,
                style = MaterialTheme.typography.titleLarge,
                color = Color.White,
                fontWeight = FontWeight.Bold,
            )
            evidence.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFFD4D4D8),
                )
            }
        }
    }
}

@Composable
private fun InsightCard(insight: TestedInsight) {
    var expanded by remember { mutableStateOf(false) }
    val visibleEvidence = if (expanded) insight.evidence else insight.evidence.take(2)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.Top,
            ) {
                Column(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    Text(
                        text = insight.domain,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF64748B),
                    )
                    Text(
                        text = insight.title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color(0xFF0F172A),
                        fontWeight = FontWeight.Bold,
                    )
                }
                Text(
                    text = insight.confidence.label(),
                    modifier = Modifier.padding(start = 12.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = insight.confidence.quality().color(),
                    fontWeight = FontWeight.SemiBold,
                    textAlign = TextAlign.End,
                )
            }

            Text(
                text = insight.answer,
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF0F172A),
                fontWeight = FontWeight.SemiBold,
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                InsightPill(
                    label = "Probka",
                    value = insight.sampleSize.toString(),
                    modifier = Modifier.weight(1f),
                )
                InsightPill(
                    label = "Zakres",
                    value = insight.dateRange,
                    modifier = Modifier.weight(2f),
                )
            }

            visibleEvidence.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF475569),
                )
            }

            if (!expanded && insight.evidence.size > visibleEvidence.size) {
                Text(
                    text = "+${insight.evidence.size - visibleEvidence.size} dowodow w szczegolach",
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF64748B),
                )
            }

            if (expanded) {
                insight.limitations.forEach { limitation ->
                    Text(
                        text = "Ograniczenie: $limitation",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF92400E),
                    )
                }
                Text(
                    text = "Nastepny test: ${insight.nextStep}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF334155),
                    fontWeight = FontWeight.SemiBold,
                )
            }

            OutlinedButton(
                onClick = { expanded = !expanded },
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp),
            ) {
                Text(if (expanded) "Zwin" else "Szczegoly")
            }
        }
    }
}

@Composable
private fun InsightPill(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF64748B),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodySmall,
            color = Color(0xFF0F172A),
            fontWeight = FontWeight.SemiBold,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun AnalysisCard(
    block: AnalysisBlock,
) {
    AnalysisCard(
        title = block.title,
        lines = block.lines,
        quality = block.quality,
    )
}

@Composable
private fun AnalysisCard(
    title: String,
    lines: List<String>,
    quality: DiagnosticQuality,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF0F172A),
                fontWeight = FontWeight.Bold,
            )
            lines.forEach { line ->
                Text(
                    text = line,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (quality == DiagnosticQuality.Warning) Color(0xFF92400E) else Color(0xFF475569),
                )
            }
        }
    }
}

@Composable
private fun CompactMetricRow(
    label: String,
    value: String,
    quality: DiagnosticQuality,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = Color(0xFF475569),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            color = quality.color(),
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun ActionSection(
    diagnostics: HealthConnectDiagnostics?,
    loading: Boolean,
    onRequestPermissions: () -> Unit,
    onRefresh: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Button(
            onClick = onRefresh,
            enabled = !loading,
            modifier = Modifier.weight(1f),
        ) {
            Text("Odswiez")
        }
        Button(
            onClick = onRequestPermissions,
            enabled = !loading && diagnostics?.sdkStatus == HealthConnectSdkStatus.Available,
            modifier = Modifier.weight(1f),
        ) {
            Text("Zgody")
        }
    }
}

@Composable
private fun DailySyncSection(summary: DailySyncSummary?) {
    if (summary == null) {
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SectionTitle("Lokalna baza")
        InfoRow(
            label = "Ostatnia sync",
            value = summary.lastSyncedAt ?: "none",
            quality = if (summary.lastSyncedAt == null) DiagnosticQuality.Warning else DiagnosticQuality.Good,
        )
        InfoRow(
            label = "Dni aktywne",
            value = summary.activityDays.toString(),
            quality = summary.activityDays.qualityForCount(),
        )
        InfoRow(
            label = "Dni z pulsem",
            value = summary.heartDays.toString(),
            quality = summary.heartDays.qualityForCount(),
        )
        InfoRow(
            label = "Dni snu",
            value = summary.sleepDays.toString(),
            quality = summary.sleepDays.qualityForCount(),
        )
        InfoRow(
            label = "Dni treningow",
            value = summary.workoutDays.toString(),
            quality = summary.workoutDays.qualityForCount(),
        )
        InfoRow(
            label = "Dni dodatkowe",
            value = summary.bodyDays.toString(),
            quality = summary.bodyDays.qualityForCount(),
        )
    }
}

@Composable
private fun RowsSection(rows: List<DiagnosticRow>) {
    if (rows.isEmpty()) {
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SectionTitle("Activity summary")
        rows.forEachIndexed { index, row ->
            if (index > 0) {
                HorizontalDivider(color = Color(0xFFE2E8F0))
            }
            InfoRow(label = row.label, value = row.value, quality = row.quality)
        }
    }
}

@Composable
private fun DataQualitySection(items: List<DataQualityItem>) {
    if (items.isEmpty()) {
        return
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        SectionTitle("Jakosc zrodel")
        items.forEachIndexed { index, item ->
            if (index > 0) {
                HorizontalDivider(color = Color(0xFFE2E8F0))
            }
            DataQualityRow(item)
        }
    }
}

@Composable
private fun DataQualityRow(item: DataQualityItem) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            Text(
                text = item.label,
                style = MaterialTheme.typography.bodyLarge,
                color = Color(0xFF334155),
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = "1d ${item.count1d} | 7d ${item.count7d} | 30d ${item.count30d}",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF64748B),
            )
        }
        Text(
            text = item.statusLabel(),
            modifier = Modifier.padding(start = 12.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = item.quality.color(),
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
        )
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleMedium,
        color = Color(0xFF0F172A),
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(top = 8.dp, bottom = 4.dp),
    )
}

@Composable
private fun SyncNotice(message: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFFFFFBEB)),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Sync Health Connect",
                style = MaterialTheme.typography.titleSmall,
                color = Color(0xFF92400E),
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF92400E),
            )
        }
    }
}

@Composable
private fun InfoRow(
    label: String,
    value: String,
    quality: DiagnosticQuality,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFF334155),
        )
        Text(
            text = value,
            modifier = Modifier
                .weight(1f)
                .padding(start = 12.dp),
            style = MaterialTheme.typography.bodyLarge,
            color = quality.color(),
            fontWeight = FontWeight.SemiBold,
            textAlign = TextAlign.End,
        )
    }
}

private fun Int.qualityForCount(): DiagnosticQuality {
    return if (this > 0) DiagnosticQuality.Good else DiagnosticQuality.Warning
}

private fun Long.qualityForCount(): DiagnosticQuality {
    return if (this > 0L) DiagnosticQuality.Good else DiagnosticQuality.Warning
}

private fun Int.toDayLabel(): String {
    return when (this) {
        0 -> "brak dni"
        1 -> "1 dzien"
        else -> "$this dni"
    }
}

private fun Double.qualityForPositive(): DiagnosticQuality {
    return if (this > 0.0) DiagnosticQuality.Good else DiagnosticQuality.Warning
}

private fun Double?.qualityForNullable(): DiagnosticQuality {
    return if (this == null) DiagnosticQuality.Warning else DiagnosticQuality.Good
}

private fun Double.format0(): String = roundToLong().formatWhole()

private fun Double.format1(): String = "%,.1f".format(this)

private fun Long.formatWhole(): String {
    return toString()
        .reversed()
        .chunked(3)
        .joinToString(" ")
        .reversed()
}

private fun Long.formatMinutes(): String {
    val hours = this / 60
    val minutes = this % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

private fun Double.formatMinutes(): String = toLong().formatMinutes()

private fun Double?.phaseText(percent: Double?): String {
    if (this == null) {
        return "brak"
    }
    val percentText = percent?.let { value -> "${value.format0()}%" } ?: "brak %"
    return "${formatMinutes()} | $percentText"
}

private fun Double?.formatSignedPercent(): String {
    return this?.let { value -> "%+.0f%%".format(value) } ?: "brak"
}

private fun Double.formatSignedMinutes(): String {
    return "%+.0f min".format(this)
}

private fun Double?.formatSigned1OrMissing(): String {
    return this?.let { value -> "%+.1f".format(value) } ?: "brak"
}

private fun Double.formatSigned1(): String = "%+.1f".format(this)

private fun Int.formatSigned(): String = "%+d".format(this)

private fun Double?.formatSigned0WithUnit(unit: String): String {
    return this?.let { value -> "%+.0f %s".format(value, unit) } ?: "brak"
}

private fun Double?.formatSigned1WithUnit(unit: String): String {
    return this?.let { value -> "%+.1f %s".format(value, unit) } ?: "brak"
}

private fun Double?.format0OrMissing(): String {
    return this?.format0() ?: "brak"
}

private fun Double?.format1OrMissing(): String {
    return this?.format1() ?: "brak"
}

private fun Double?.formatPaceOrMissing(): String {
    if (this == null || this <= 0.0) {
        return "brak"
    }
    val totalSeconds = toLong()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    return "%d:%02d".format(minutes, seconds)
}

private fun Double?.formatSignedPaceDelta(): String {
    if (this == null) {
        return "brak"
    }
    val totalSeconds = abs(this).roundToLong()
    val minutes = totalSeconds / 60
    val seconds = totalSeconds % 60
    val sign = when {
        this > 0.0 -> "+"
        this < 0.0 -> "-"
        else -> "+/-"
    }
    return "$sign%d:%02d/km".format(minutes, seconds)
}

private data class TrendPoint(
    val period: String,
    val value: Double,
)

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawTrendLine(
    points: List<TrendPoint>,
    left: Float,
    top: Float,
    chartWidth: Float,
    chartHeight: Float,
    color: Color,
) {
    if (points.size < 2) {
        return
    }
    val min = points.minOf { point -> point.value }
    val max = points.maxOf { point -> point.value }
    val range = (max - min).takeIf { value -> value > 0.0 } ?: 1.0
    val offsets = points.mapIndexed { index, point ->
        val x = left + chartWidth * index / (points.size - 1).toFloat()
        val normalized = ((point.value - min) / range).toFloat()
        val y = top + chartHeight * (1f - normalized)
        androidx.compose.ui.geometry.Offset(x, y)
    }
    val path = Path().apply {
        moveTo(offsets.first().x, offsets.first().y)
        offsets.drop(1).forEach { offset -> lineTo(offset.x, offset.y) }
    }
    drawPath(
        path = path,
        color = color,
        style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round),
    )
    offsets.forEach { offset ->
        drawCircle(
            color = color,
            radius = 4.dp.toPx(),
            center = offset,
        )
    }
}

private fun SportEfficiencyComparison.headline(): String {
    val currentTrend = current
    val previousTrend = previous
    val deltaTrend = delta
    val label = workoutType.workoutTypeLabel()
    if (currentTrend == null || previousTrend == null || deltaTrend == null) {
        return "$label: za malo danych"
    }
    val pulse = deltaTrend.avgHeartRateBpm.formatSigned0WithUnit("bpm")
    val pace = deltaTrend.avgPaceSecondsPerKm.formatSignedPaceDelta()
    return "$label: $pulse, $pace"
}

private fun String.workoutTypeLabel(): String {
    return when (this) {
        "walking" -> "Chodzenie"
        "running" -> "Bieganie"
        else -> this
    }
}

private fun AnalysisConfidence.label(): String {
    return when (this) {
        AnalysisConfidence.Insufficient -> "za mala probka"
        AnalysisConfidence.Low -> "niska"
        AnalysisConfidence.Medium -> "srednia"
        AnalysisConfidence.High -> "wysoka"
    }
}

private fun AnalysisConfidence.rank(): Int {
    return when (this) {
        AnalysisConfidence.Insufficient -> 0
        AnalysisConfidence.Low -> 1
        AnalysisConfidence.Medium -> 2
        AnalysisConfidence.High -> 3
    }
}

private fun AnalysisConfidence.quality(): DiagnosticQuality {
    return when (this) {
        AnalysisConfidence.Insufficient -> DiagnosticQuality.Warning
        AnalysisConfidence.Low -> DiagnosticQuality.Neutral
        AnalysisConfidence.Medium,
        AnalysisConfidence.High -> DiagnosticQuality.Good
    }
}

private fun AnalysisConfidence.watchColor(): Color {
    return when (this) {
        AnalysisConfidence.Insufficient -> Color(0xFFFF9F0A)
        AnalysisConfidence.Low -> Color(0xFFFFD60A)
        AnalysisConfidence.Medium -> Color(0xFF30D158)
        AnalysisConfidence.High -> Color(0xFF32D74B)
    }
}

private fun DataQualityItem.statusLabel(): String {
    return when {
        !hasPermission -> "no permission"
        count30d > 0 -> "records found"
        else -> "no records"
    }
}

private fun HealthConnectSdkStatus.label(): String {
    return when (this) {
        HealthConnectSdkStatus.Available -> "available"
        HealthConnectSdkStatus.NotInstalled -> "install/update"
        HealthConnectSdkStatus.NotSupported -> "not supported"
        HealthConnectSdkStatus.Unknown -> "unknown"
    }
}

private fun DiagnosticQuality.color(): Color {
    return when (this) {
        DiagnosticQuality.Good -> Color(0xFF047857)
        DiagnosticQuality.Warning -> Color(0xFFB45309)
        DiagnosticQuality.Neutral -> Color(0xFF334155)
    }
}
