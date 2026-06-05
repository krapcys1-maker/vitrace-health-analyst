package com.vitrace.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.vitrace.app.health.ActivityWindow
import com.vitrace.app.health.DataQualityItem
import com.vitrace.app.health.DailySyncSummary
import com.vitrace.app.health.DiagnosticQuality
import com.vitrace.app.health.DiagnosticRow
import com.vitrace.app.health.HealthDashboard
import com.vitrace.app.health.HealthConnectDiagnostics
import com.vitrace.app.health.HealthConnectDiagnosticsRepository
import com.vitrace.app.health.HealthConnectSdkStatus
import kotlinx.coroutines.launch

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
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 24.dp),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Header()
        ActionSection(
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
        diagnostics?.error?.let { error ->
            SyncNotice(error)
        }
        DashboardSection(dashboard = diagnostics?.dashboard, loading = loading)
        StatusSection(diagnostics = diagnostics, loading = loading)
        DailySyncSection(summary = diagnostics?.dailySyncSummary)
        DataQualitySection(items = diagnostics?.dataQualityItems.orEmpty())
        RowsSection(rows = diagnostics?.rows.orEmpty())
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
            text = "Dashboard zdrowia",
            style = MaterialTheme.typography.titleMedium,
            color = Color(0xFF475569),
        )
    }
}

@Composable
private fun StatusSection(
    diagnostics: HealthConnectDiagnostics?,
    loading: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        SectionTitle("Health Connect")
        InfoRow(
            label = "SDK",
            value = when {
                loading -> "checking"
                diagnostics == null -> "pending"
                else -> diagnostics.sdkStatus.label()
            },
            quality = when (diagnostics?.sdkStatus) {
                HealthConnectSdkStatus.Available -> DiagnosticQuality.Good
                HealthConnectSdkStatus.NotInstalled,
                HealthConnectSdkStatus.NotSupported -> DiagnosticQuality.Warning
                else -> DiagnosticQuality.Neutral
            },
        )
        InfoRow(
            label = "Permissions",
            value = diagnostics?.let { "${it.grantedPermissionCount}/${it.requiredPermissionCount}" } ?: "pending",
            quality = if (diagnostics?.hasAllPermissions == true) DiagnosticQuality.Good else DiagnosticQuality.Warning,
        )
    }
}

@Composable
private fun DashboardSection(
    dashboard: HealthDashboard?,
    loading: Boolean,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        if (dashboard == null) {
            SectionTitle("Dashboard")
            InfoRow(
                label = "Status",
                value = if (loading) "syncing" else "pending",
                quality = DiagnosticQuality.Neutral,
            )
            return
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SectionTitle("Dzisiaj")
            Text(
                text = dashboard.lastSyncedAt ?: "brak sync",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF64748B),
                textAlign = TextAlign.End,
            )
        }
        TodayTiles(window = dashboard.today)
        SectionTitle("Ostatnie okresy")
        PeriodSummaryCard(title = "7 dni", window = dashboard.last7Days)
        PeriodSummaryCard(title = "30 dni", window = dashboard.last30Days)
        SectionTitle("Pokrycie danych")
        CoverageCard(
            heartDays = dashboard.signalCounts.heartDays,
            sleepDays = dashboard.signalCounts.sleepDays,
            workoutDays = dashboard.signalCounts.workoutDays,
            bodyDays = dashboard.signalCounts.bodyDays,
        )
    }
}

@Composable
private fun TodayTiles(window: ActivityWindow) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                label = "Kroki",
                value = window.steps.toString(),
                quality = window.steps.qualityForCount(),
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "Aktywne kcal",
                value = "${window.activeCaloriesKcal.format0()} kcal",
                quality = window.activeCaloriesKcal.qualityForPositive(),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            MetricTile(
                label = "Dystans",
                value = "${window.distanceKm.format1()} km",
                quality = window.distanceKm.qualityForPositive(),
                modifier = Modifier.weight(1f),
            )
            MetricTile(
                label = "Dni aktywne",
                value = window.daysWithActivity.toString(),
                quality = window.daysWithActivity.qualityForCount(),
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun MetricTile(
    label: String,
    value: String,
    quality: DiagnosticQuality,
    modifier: Modifier = Modifier,
) {
    Card(
        modifier = modifier,
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF64748B),
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineSmall,
                color = quality.color(),
                fontWeight = FontWeight.Bold,
            )
        }
    }
}

@Composable
private fun PeriodSummaryCard(
    title: String,
    window: ActivityWindow,
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
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium,
                    color = Color(0xFF0F172A),
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    text = "${window.daysWithActivity} aktywnych dni",
                    style = MaterialTheme.typography.bodyMedium,
                    color = window.daysWithActivity.qualityForCount().color(),
                    fontWeight = FontWeight.SemiBold,
                )
            }
            CompactMetricRow(label = "Kroki", value = window.steps.toString(), quality = window.steps.qualityForCount())
            CompactMetricRow(label = "Aktywne kcal", value = "${window.activeCaloriesKcal.format0()} kcal", quality = window.activeCaloriesKcal.qualityForPositive())
            CompactMetricRow(label = "Dystans", value = "${window.distanceKm.format1()} km", quality = window.distanceKm.qualityForPositive())
        }
    }
}

@Composable
private fun CoverageCard(
    heartDays: Int,
    sleepDays: Int,
    workoutDays: Int,
    bodyDays: Int,
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
            CompactMetricRow(label = "Puls", value = "$heartDays / 30 dni", quality = heartDays.qualityForCount())
            CompactMetricRow(label = "Sen", value = "$sleepDays / 30 dni", quality = sleepDays.qualityForCount())
            CompactMetricRow(label = "Trening", value = "$workoutDays / 30 dni", quality = workoutDays.qualityForCount())
            CompactMetricRow(label = "Cialo", value = "$bodyDays / 30 dni", quality = bodyDays.qualityForCount())
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
            Text("Refresh")
        }
        Button(
            onClick = onRequestPermissions,
            enabled = !loading && diagnostics?.sdkStatus == HealthConnectSdkStatus.Available,
            modifier = Modifier.weight(1f),
        ) {
            Text("Permissions")
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
            label = "Activity days",
            value = summary.activityDays.toString(),
            quality = summary.activityDays.qualityForCount(),
        )
        InfoRow(
            label = "Heart days",
            value = summary.heartDays.toString(),
            quality = summary.heartDays.qualityForCount(),
        )
        InfoRow(
            label = "Sleep days",
            value = summary.sleepDays.toString(),
            quality = summary.sleepDays.qualityForCount(),
        )
        InfoRow(
            label = "Workout days",
            value = summary.workoutDays.toString(),
            quality = summary.workoutDays.qualityForCount(),
        )
        InfoRow(
            label = "Body days",
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

private fun Double.qualityForPositive(): DiagnosticQuality {
    return if (this > 0.0) DiagnosticQuality.Good else DiagnosticQuality.Warning
}

private fun Double.format0(): String = "%,.0f".format(this)

private fun Double.format1(): String = "%,.1f".format(this)

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
