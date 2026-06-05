package com.vitrace.app

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.health.connect.client.PermissionController
import com.vitrace.app.analysis.AnalysisBlock
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
    var savingNote by remember { mutableStateOf(false) }
    var selectedTab by remember { mutableStateOf(AppTab.Sleep) }

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

    fun saveHealthNote(text: String) {
        scope.launch {
            savingNote = true
            try {
                HealthConnectDiagnosticsRepository.saveHealthNote(context, text)
                diagnostics = HealthConnectDiagnosticsRepository.load(
                    context = context,
                    syncFromHealthConnect = false,
                )
            } finally {
                savingNote = false
            }
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
        TabSection(
            selectedTab = selectedTab,
            onSelectTab = { tab -> selectedTab = tab },
        )
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            when (selectedTab) {
                AppTab.Sleep -> SleepTab(
                    summary = diagnostics?.sleepSummary,
                )
                AppTab.Sport -> SportTab(summary = diagnostics?.sportSummary)
                AppTab.Weight -> WeightTab(summary = diagnostics?.bodySummary)
                AppTab.Health -> HealthTab(
                    journal = diagnostics?.healthJournal,
                    saving = savingNote,
                    onSaveNote = { text -> saveHealthNote(text) },
                )
                AppTab.Analysis -> AnalysisTab(
                    dashboard = diagnostics?.dashboard,
                    longTermActivity = diagnostics?.longTermActivity,
                    sleepSummary = diagnostics?.sleepSummary,
                    sportSummary = diagnostics?.sportSummary,
                    bodySummary = diagnostics?.bodySummary,
                )
                AppTab.Options -> OptionsTab(
                    diagnostics = diagnostics,
                    loading = loading,
                    profile = diagnostics?.profile,
                    onRequestPermissions = {
                        val openedSettings = openHealthConnectPermissions(context)
                        if (!openedSettings) {
                            permissionLauncher.launch(HealthConnectDiagnosticsRepository.requiredPermissions)
                        }
                    },
                    onRefresh = { refresh(syncFromHealthConnect = true) },
                )
            }
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
private fun TabSection(
    selectedTab: AppTab,
    onSelectTab: (AppTab) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        AppTab.entries.chunked(3).forEach { rowTabs ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                rowTabs.forEach { tab ->
                    if (tab == selectedTab) {
                        Button(
                            onClick = { onSelectTab(tab) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        ) {
                            Text(
                                text = tab.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    } else {
                        OutlinedButton(
                            onClick = { onSelectTab(tab) },
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp),
                            contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                        ) {
                            Text(
                                text = tab.label,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                repeat(3 - rowTabs.size) {
                    Spacer(modifier = Modifier.weight(1f))
                }
            }
        }
    }
}

@Composable
private fun SectionSwitch(
    labels: List<String>,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        labels.forEachIndexed { index, label ->
            val modifier = Modifier
                .weight(1f)
                .height(44.dp)
            if (index == selectedIndex) {
                Button(
                    onClick = { onSelect(index) },
                    modifier = modifier,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                ) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                OutlinedButton(
                    onClick = { onSelect(index) },
                    modifier = modifier,
                    contentPadding = PaddingValues(horizontal = 6.dp, vertical = 0.dp),
                ) {
                    Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

@Composable
private fun DashboardTab(
    diagnostics: HealthConnectDiagnostics?,
    loading: Boolean,
    onRefresh: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Button(
            onClick = onRefresh,
            enabled = !loading,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (loading) "Synchronizuje..." else "Synchronizuj Health Connect")
        }
        diagnostics?.error?.let { error ->
            SyncNotice(error)
        }
        DashboardSection(dashboard = diagnostics?.dashboard, loading = loading)
    }
}

@Composable
private fun SleepTab(summary: SleepDomainSummary?) {
    var section by remember { mutableStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("Sen")
        if (summary == null) {
            AnalysisCard(
                title = "Brak danych snu",
                lines = listOf("czekamy na import albo sync snu"),
                quality = DiagnosticQuality.Warning,
            )
            return
        }
        SectionSwitch(
            labels = listOf("Przeglad", "Historia", "Analiza"),
            selectedIndex = section,
            onSelect = { index -> section = index },
        )
        when (section) {
            0 -> SleepLatestCard(summary)
            1 -> SleepMonthlyCard(summary)
            else -> AnalysisCard(
                title = "Analiza snu",
                lines = listOf(
                    "AI pozniej dostanie agregaty snu, aktywnosci i pulsu",
                    "najpierw liczymy korelacje lokalnie i pokazujemy pewnosc wniosku",
                    "nie bedziemy udawac zaleznosci przy zbyt malej probce",
                ),
                quality = if (summary.last30SleepDays >= 14) DiagnosticQuality.Good else DiagnosticQuality.Warning,
            )
        }
    }
}

@Composable
private fun SleepLatestCard(summary: SleepDomainSummary) {
    val latest = summary.latest
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
                text = "Ostatni sen",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF0F172A),
                fontWeight = FontWeight.Bold,
            )
            CompactMetricRow(
                label = latest?.date ?: "brak daty",
                value = latest?.totalSleepMinutes?.formatMinutes() ?: "brak",
                quality = if (latest == null) DiagnosticQuality.Warning else DiagnosticQuality.Good,
            )
            CompactMetricRow(
                label = "Ostatnie 30 dni",
                value = "${summary.last30SleepDays} dni | ${summary.last30AverageMinutes.formatMinutes()} srednio",
                quality = summary.last30SleepDays.qualityForCount(),
            )
            CompactMetricRow(
                label = "Zrodlo",
                value = latest?.source ?: "none",
                quality = if (latest == null) DiagnosticQuality.Warning else DiagnosticQuality.Neutral,
            )
        }
    }
}

@Composable
private fun SleepMonthlyCard(summary: SleepDomainSummary) {
    PeriodBarsCard(
        title = "Sen miesiecznie",
        rows = summary.recentMonths.map { month ->
            BarRowData(
                label = month.period,
                value = month.averageMinutes,
                text = "${month.averageMinutes.formatMinutes()} srednio | ${month.sleepDays} dni",
            )
        },
        emptyText = "brak miesiecy snu",
    )
}

@Composable
private fun SportTab(summary: SportDomainSummary?) {
    var section by remember { mutableStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("Sport")
        if (summary == null) {
            AnalysisCard(
                title = "Brak danych sportowych",
                lines = listOf("czekamy na kroki albo import treningow"),
                quality = DiagnosticQuality.Warning,
            )
            return
        }
        SectionSwitch(
            labels = listOf("Kroki", "Chodzenie", "Bieganie", "Analiza"),
            selectedIndex = section,
            onSelect = { index -> section = index },
        )
        when (section) {
            0 -> {
                TodayTiles(window = summary.today)
                PeriodSummaryCard(title = "7 dni", window = summary.last7Days)
                PeriodSummaryCard(title = "30 dni", window = summary.last30Days)
                LongTermActivitySection(
                    summary = LongTermActivitySummary(
                        stepsPerKm = summary.stepsPerKm,
                        yearly = summary.yearly,
                        bestMonth = summary.bestMonth,
                        recentMonths = summary.recentMonths,
                    )
                )
            }
            1 -> WorkoutTypeSection(
                title = "Chodzenie z treningow",
                summary = summary.walkingLast30,
                recentDays = summary.recentWalkingDays,
                emptyText = "brak osobnych treningow chodzenia w ostatnich 30 dniach",
            )
            2 -> WorkoutTypeSection(
                title = "Bieganie z treningow",
                summary = summary.runningLast30,
                recentDays = summary.recentRunningDays,
                emptyText = "brak osobnych treningow biegania w ostatnich 30 dniach",
            )
            else -> {
                WorkoutSummaryCard(summary)
                PeriodBarsCard(
                    title = "Kroki miesiecznie",
                    rows = summary.recentMonths.map { month ->
                        BarRowData(
                            label = month.period,
                            value = month.steps.toDouble(),
                            text = "${month.steps.formatWhole()} krokow | ${month.estimatedKm.format1()} km",
                        )
                    },
                    emptyText = "brak miesiecy aktywnosci",
                )
                AnalysisCard(
                    title = "Analiza sportu",
                    lines = listOf(
                        "kroki sa z aktywnosci dziennej",
                        "chodzenie i bieganie sa z treningow, nie z samego licznika krokow",
                        "dla biegania sprawdzimy tempo, puls, dystans i czas",
                        "dla chodzenia sprawdzimy objetosc, tempo i obciazenie",
                    ),
                    quality = if (summary.workoutLast30.sessionCount > 0) DiagnosticQuality.Good else DiagnosticQuality.Neutral,
                )
            }
        }
    }
}

@Composable
private fun WorkoutTypeSection(
    title: String,
    summary: WorkoutTypeSummary?,
    recentDays: List<WorkoutTypeDaySummary>,
    emptyText: String,
) {
    if (summary == null || summary.sessionCount == 0) {
        AnalysisCard(
            title = title,
            lines = listOf(emptyText, "to jest oddzielne od zwyklych krokow"),
            quality = DiagnosticQuality.Warning,
        )
        return
    }

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
                text = "$title - 30 dni",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF0F172A),
                fontWeight = FontWeight.Bold,
            )
            CompactMetricRow("Sesje", summary.sessionCount.toString(), summary.sessionCount.qualityForCount())
            CompactMetricRow("Dni", summary.daysWithWorkouts.toString(), summary.daysWithWorkouts.qualityForCount())
            CompactMetricRow("Czas", summary.totalDurationMinutes.formatMinutes(), summary.totalDurationMinutes.qualityForCount())
            CompactMetricRow("Dystans", "${summary.distanceKm.format1()} km", summary.distanceKm.qualityForPositive())
            CompactMetricRow("Aktywne kcal", "${summary.activeCaloriesKcal.format0()} kcal", summary.activeCaloriesKcal.qualityForPositive())
            CompactMetricRow("Sr. puls", summary.avgHeartRateBpm?.let { "${it.format0()} bpm" } ?: "brak", if (summary.avgHeartRateBpm == null) DiagnosticQuality.Warning else DiagnosticQuality.Good)
        }
    }

    PeriodBarsCard(
        title = "Ostatnie treningowe dni",
        rows = recentDays.map { day ->
            BarRowData(
                label = day.date,
                value = day.distanceKm,
                text = "${day.distanceKm.format1()} km | ${day.totalDurationMinutes.formatMinutes()}",
            )
        },
        emptyText = "brak ostatnich dni",
    )
}

@Composable
private fun WorkoutSummaryCard(summary: SportDomainSummary) {
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
                text = "Treningi 30 dni",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF0F172A),
                fontWeight = FontWeight.Bold,
            )
            CompactMetricRow(
                label = "Sesje",
                value = summary.workoutLast30.sessionCount.toString(),
                quality = summary.workoutLast30.sessionCount.qualityForCount(),
            )
            CompactMetricRow(
                label = "Czas",
                value = summary.workoutLast30.totalDurationMinutes.formatMinutes(),
                quality = summary.workoutLast30.totalDurationMinutes.qualityForCount(),
            )
            CompactMetricRow(
                label = "Dni z treningiem",
                value = summary.workoutLast30.daysWithWorkouts.toString(),
                quality = summary.workoutLast30.daysWithWorkouts.qualityForCount(),
            )
        }
    }
}

@Composable
private fun WeightTab(summary: BodyDomainSummary?) {
    var section by remember { mutableStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("Waga")
        if (summary == null) {
            AnalysisCard(
                title = "Brak danych ciala",
                lines = listOf("waga i sklad ciala wejda z importu albo recznie"),
                quality = DiagnosticQuality.Warning,
            )
            return
        }
        SectionSwitch(
            labels = listOf("Teraz", "Dane", "Analiza"),
            selectedIndex = section,
            onSelect = { index -> section = index },
        )
        when (section) {
            0 -> BodyLatestCard(summary)
            1 -> AnalysisCard(
                title = "Pokrycie danych ciala",
                lines = listOf(
                    "dni z danymi: ${summary.bodyDays}",
                    "waga: ${summary.weightRecords} rekordow",
                    "VO2 max: ${summary.vo2Records} rekordow",
                    "SpO2: ${summary.spo2Records} rekordow",
                ),
                quality = summary.bodyDays.qualityForCount(),
            )
            else -> AnalysisCard(
                title = "Docelowo",
                lines = listOf(
                    "wykres wagi, miesni, tluszczu i nawodnienia",
                    "reczne wpisy albo import ze zdjecia wyniku z wagi",
                    "korelacja: waga, puls, sen i meczliwosc",
                ),
                quality = DiagnosticQuality.Neutral,
            )
        }
    }
}

@Composable
private fun BodyLatestCard(summary: BodyDomainSummary) {
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
                text = "Ostatnie dane",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF0F172A),
                fontWeight = FontWeight.Bold,
            )
            CompactMetricRow(
                label = "Data",
                value = summary.latestDate ?: "brak",
                quality = if (summary.latestDate == null) DiagnosticQuality.Warning else DiagnosticQuality.Good,
            )
            CompactMetricRow(
                label = "Waga",
                value = summary.latestWeightKg?.let { "${it.format1()} kg" } ?: "brak",
                quality = if (summary.latestWeightKg == null) DiagnosticQuality.Warning else DiagnosticQuality.Good,
            )
            CompactMetricRow(
                label = "VO2 max",
                value = summary.latestVo2Max?.format1() ?: "brak",
                quality = if (summary.latestVo2Max == null) DiagnosticQuality.Warning else DiagnosticQuality.Good,
            )
            CompactMetricRow(
                label = "SpO2",
                value = summary.latestSpo2Percent?.let { "${it.format1()}%" } ?: "brak",
                quality = if (summary.latestSpo2Percent == null) DiagnosticQuality.Warning else DiagnosticQuality.Good,
            )
        }
    }
}

@Composable
private fun HealthTab(
    journal: HealthJournalSummary?,
    saving: Boolean,
    onSaveNote: (String) -> Unit,
) {
    var noteText by remember { mutableStateOf("") }
    var section by remember { mutableStateOf(0) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("Zdrowie")
        SectionSwitch(
            labels = listOf("Notatki", "Badania", "Wnioski"),
            selectedIndex = section,
            onSelect = { index -> section = index },
        )
        when (section) {
            0 -> {
                HealthNoteCard(
                    text = noteText,
                    onTextChange = { value -> noteText = value },
                    saving = saving,
                    onSave = {
                        onSaveNote(noteText)
                        noteText = ""
                    },
                )
                HealthJournalCard(journal = journal)
            }
            1 -> {
                AnalysisCard(
                    title = "Analiza wynikow",
                    lines = listOf(
                        "najnowsze wyniki porownamy z Twoja historia",
                        "AI podsumuje zmiany, ale nie bedzie diagnozowac",
                        "wyniki beda laczone z notatkami, snem, sportem, pulsem i waga",
                    ),
                    quality = DiagnosticQuality.Neutral,
                )
                AnalysisCard(
                    title = "Furtka techniczna",
                    lines = listOf(
                        "skany badan trafia do lokalnego magazynu dokumentow",
                        "odczyt OCR musi byc potwierdzony przed analiza",
                        "notatki dzienne beda laczone z pomiarami z tego samego dnia",
                    ),
                    quality = DiagnosticQuality.Neutral,
                )
            }
            else -> AnalysisCard(
                title = "Co mozna z tego wyciagnac",
                lines = listOf(
                    "czy kiepskie samopoczucie wraca po slabym snie",
                    "czy mocny trening obniza energie nastepnego dnia",
                    "czy zmiany w wadze/VO2 ida razem z pulsem i regeneracja",
                    "czy wyniki krwi zmieniaja sie po okresach wiekszej aktywnosci",
                ),
                quality = DiagnosticQuality.Neutral,
            )
        }
    }
}

@Composable
private fun HealthNoteCard(
    text: String,
    onTextChange: (String) -> Unit,
    saving: Boolean,
    onSave: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Notatka dzienna",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF0F172A),
                fontWeight = FontWeight.Bold,
            )
            OutlinedTextField(
                value = text,
                onValueChange = onTextChange,
                modifier = Modifier.fillMaxWidth(),
                minLines = 3,
                label = { Text("Jak sie dzis czujesz?") },
                placeholder = { Text("np. fizycznie slabo, psychicznie napiecie, bol glowy, malo energii") },
            )
            Button(
                onClick = onSave,
                enabled = !saving && text.isNotBlank(),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (saving) "Zapisuje..." else "Zapisz notatke")
            }
        }
    }
}

@Composable
private fun HealthJournalCard(journal: HealthJournalSummary?) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Dziennik zdrowia",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF0F172A),
                fontWeight = FontWeight.Bold,
            )
            CompactMetricRow(
                label = "Liczba notatek",
                value = (journal?.noteCount ?: 0).toString(),
                quality = (journal?.noteCount ?: 0).qualityForCount(),
            )
            val notes = journal?.latestNotes.orEmpty()
            if (notes.isEmpty()) {
                Text(
                    text = "brak notatek",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF64748B),
                )
            } else {
                notes.take(5).forEach { note ->
                    HorizontalDivider(color = Color(0xFFE2E8F0))
                    Text(
                        text = note.date + if (note.tags.isBlank()) "" else " | ${note.tags}",
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF0F766E),
                        fontWeight = FontWeight.SemiBold,
                    )
                    Text(
                        text = note.noteText,
                        style = MaterialTheme.typography.bodyMedium,
                        color = Color(0xFF475569),
                    )
                }
            }
        }
    }
}

@Composable
private fun AnalysisTab(
    dashboard: HealthDashboard?,
    longTermActivity: LongTermActivitySummary?,
    sleepSummary: SleepDomainSummary?,
    sportSummary: SportDomainSummary?,
    bodySummary: BodyDomainSummary?,
) {
    val report = buildVitaTraceAnalysis(dashboard)
    var section by remember { mutableStateOf(0) }

    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("Analiza")
        SectionSwitch(
            labels = listOf("Wnioski", "Gotowosc", "Historia"),
            selectedIndex = section,
            onSelect = { index -> section = index },
        )
        when (section) {
            0 -> {
                AnalysisCard(
                    title = "Co analizujemy",
                    lines = listOf(
                        "sen kontra aktywnosc fizyczna",
                        "treningi kontra puls i zmeczenie",
                        "waga i sklad ciala kontra sen, puls i forma",
                    ),
                    quality = DiagnosticQuality.Good,
                )
                AnalysisCard(report.currentInsight)
                SleepActivityCorrelationCard(sleepSummary = sleepSummary, sportSummary = sportSummary)
            }
            1 -> {
                AnalysisCard(report.readiness)
                AnalysisCard(
                    title = "Gotowosc domen",
                    lines = listOf(
                        "sen: ${sleepSummary?.last30SleepDays ?: 0} dni w ostatnim oknie",
                        "sport: ${sportSummary?.last30Days?.daysWithActivity ?: 0} aktywnych dni",
                        "waga/cialo: ${bodySummary?.bodyDays ?: 0} dni z sygnalem",
                    ),
                    quality = if ((sleepSummary?.last30SleepDays ?: 0) >= 14 && (sportSummary?.last30Days?.daysWithActivity ?: 0) >= 14) {
                        DiagnosticQuality.Good
                    } else {
                        DiagnosticQuality.Warning
                    },
                )
            }
            else -> LongTermActivitySection(summary = longTermActivity)
        }
    }
}

@Composable
private fun SleepActivityCorrelationCard(
    sleepSummary: SleepDomainSummary?,
    sportSummary: SportDomainSummary?,
) {
    val sleepDays = sleepSummary?.last30SleepDays ?: 0
    val activeDays = sportSummary?.last30Days?.daysWithActivity ?: 0
    val ready = sleepDays >= 14 && activeDays >= 14
    AnalysisCard(
        title = "Sen a aktywnosc",
        lines = if (ready) {
            listOf(
                "mamy wystarczajaco dni do pierwszej korelacji",
                "kolejny krok: policzyc zaleznosc krokow i treningow od dlugosci snu",
                "wniosek pokazemy z progiem pewnosci, nie jako zgadywanie",
            )
        } else {
            listOf(
                "potrzeba minimum 14 dni snu i aktywnosci w tym samym oknie",
                "teraz sen: $sleepDays dni, aktywnosc: $activeDays dni",
                "na razie pokazujemy dane, korelacji jeszcze nie udajemy",
            )
        },
        quality = if (ready) DiagnosticQuality.Good else DiagnosticQuality.Warning,
    )
}

@Composable
private fun OptionsTab(
    diagnostics: HealthConnectDiagnostics?,
    loading: Boolean,
    profile: UserProfileEntity?,
    onRequestPermissions: () -> Unit,
    onRefresh: () -> Unit,
) {
    var section by remember { mutableStateOf(0) }
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("Opcje")
        SectionSwitch(
            labels = listOf("Profil", "Sync", "Zrodla"),
            selectedIndex = section,
            onSelect = { index -> section = index },
        )
        when (section) {
            0 -> ProfileCard(profile = profile)
            1 -> {
                DataConnectionCard(diagnostics = diagnostics)
                ActionSection(
                    diagnostics = diagnostics,
                    loading = loading,
                    onRequestPermissions = onRequestPermissions,
                    onRefresh = onRefresh,
                )
                diagnostics?.error?.let { error -> SyncNotice(error) }
            }
            else -> {
                SourceCoverageSection(summary = diagnostics?.dailySyncSummary)
                DataQualitySection(items = diagnostics?.dataQualityItems ?: emptyList())
            }
        }
    }
}

private data class BarRowData(
    val label: String,
    val value: Double,
    val text: String,
)

@Composable
private fun PeriodBarsCard(
    title: String,
    rows: List<BarRowData>,
    emptyText: String,
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF0F172A),
                fontWeight = FontWeight.Bold,
            )
            if (rows.isEmpty()) {
                Text(
                    text = emptyText,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color(0xFF64748B),
                )
            } else {
                val maxValue = rows.maxOf { row -> row.value }.coerceAtLeast(1.0)
                rows.forEach { row ->
                    CompactMetricRow(
                        label = row.label,
                        value = row.text,
                        quality = if (row.value > 0.0) DiagnosticQuality.Good else DiagnosticQuality.Warning,
                    )
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth(fraction = (row.value / maxValue).toFloat().coerceIn(0.05f, 1f))
                            .height(8.dp),
                        color = Color(0xFF0F766E),
                        shape = RoundedCornerShape(8.dp),
                    ) {}
                }
            }
        }
    }
}

@Composable
private fun DataTab(
    diagnostics: HealthConnectDiagnostics?,
    loading: Boolean,
    onRequestPermissions: () -> Unit,
    onRefresh: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("Dane")
        DataConnectionCard(diagnostics = diagnostics)
        ActionSection(
            diagnostics = diagnostics,
            loading = loading,
            onRequestPermissions = onRequestPermissions,
            onRefresh = onRefresh,
        )
        diagnostics?.error?.let { error ->
            SyncNotice(error)
        }
        SourceCoverageSection(summary = diagnostics?.dailySyncSummary)
        DataMeaningCard(summary = diagnostics?.dailySyncSummary)
    }
}

@Composable
private fun ProfileTab(profile: UserProfileEntity?) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        SectionTitle("Profil")
        ProfileCard(profile = profile)
        AnalysisCard(
            title = "Rola aplikacji",
            lines = listOf(
                "VitaTrace analizuje dane lokalnie w bazie telefonu",
                "Health Connect i Mi Fitness export sa zrodlami danych",
                "AI pozniej dostanie tylko agregaty po Twojej zgodzie",
            ),
            quality = DiagnosticQuality.Neutral,
        )
        AnalysisCard(
            title = "Historia z Mi Fitness",
            lines = listOf(
                "surowe eksporty zostaja prywatne",
                "import trafi do tych samych dziennych tabel co Health Connect",
                "GPX i trasy zostana lokalne",
            ),
            quality = DiagnosticQuality.Good,
        )
    }
}

@Composable
private fun ProfileCard(profile: UserProfileEntity?) {
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
                text = "Dane bazowe",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF0F172A),
                fontWeight = FontWeight.Bold,
            )
            CompactMetricRow(
                label = "Plec",
                value = when (profile?.sex) {
                    "male" -> "mezczyzna"
                    "female" -> "kobieta"
                    null -> "brak"
                    else -> profile.sex
                },
                quality = if (profile == null) DiagnosticQuality.Warning else DiagnosticQuality.Good,
            )
            CompactMetricRow(
                label = "Wiek",
                value = profile?.let { "${it.ageYears} lat" } ?: "brak",
                quality = if (profile == null) DiagnosticQuality.Warning else DiagnosticQuality.Good,
            )
            CompactMetricRow(
                label = "Wzrost",
                value = profile?.let { "${it.heightCm} cm" } ?: "brak",
                quality = if (profile == null) DiagnosticQuality.Warning else DiagnosticQuality.Good,
            )
            CompactMetricRow(
                label = "Waga",
                value = profile?.let { "${it.weightKg.format1()} kg" } ?: "brak",
                quality = if (profile == null) DiagnosticQuality.Warning else DiagnosticQuality.Good,
            )
            CompactMetricRow(
                label = "Przelicznik",
                value = profile?.let { "${it.stepsPerKm} krokow/km" } ?: "brak",
                quality = if (profile == null) DiagnosticQuality.Warning else DiagnosticQuality.Good,
            )
        }
    }
}

@Composable
private fun LongTermActivitySection(summary: LongTermActivitySummary?) {
    if (summary == null || summary.yearly.isEmpty()) {
        AnalysisCard(
            title = "Aktywnosc dlugoterminowa",
            lines = listOf(
                "czekamy na import historii Mi Fitness",
                "po imporcie policzymy kroki i km dla kazdego roku",
            ),
            quality = DiagnosticQuality.Warning,
        )
        return
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp),
        colors = CardDefaults.cardColors(containerColor = Color.White),
    ) {
        Column(
            modifier = Modifier.padding(14.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                text = "Aktywnosc dlugoterminowa",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF0F172A),
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "km liczone z ${summary.stepsPerKm} krokow/km",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF64748B),
            )
            summary.yearly.take(6).forEach { year ->
                CompactMetricRow(
                    label = year.period,
                    value = "${year.steps.formatWhole()} krokow | ${year.estimatedKm.format1()} km",
                    quality = DiagnosticQuality.Good,
                )
            }
            summary.bestMonth?.let { best ->
                HorizontalDivider(color = Color(0xFFE2E8F0))
                CompactMetricRow(
                    label = "Najlepszy miesiac",
                    value = "${best.period}: ${best.steps.formatWhole()} krokow",
                    quality = DiagnosticQuality.Good,
                )
            }
        }
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
            label = "Zgody",
            value = when {
                diagnostics == null -> "pending"
                !diagnostics.permissionsChecked -> "niesprawdzone"
                else -> "${diagnostics.grantedPermissionCount}/${diagnostics.requiredPermissionCount}"
            },
            quality = when {
                diagnostics?.permissionsChecked != true -> DiagnosticQuality.Neutral
                diagnostics.hasAllPermissions -> DiagnosticQuality.Good
                else -> DiagnosticQuality.Warning
            },
        )
    }
}

@Composable
private fun DataConnectionCard(
    diagnostics: HealthConnectDiagnostics?,
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
                text = "Polaczenie",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF0F172A),
                fontWeight = FontWeight.Bold,
            )
            CompactMetricRow(
                label = "Health Connect",
                value = when (diagnostics?.sdkStatus) {
                    HealthConnectSdkStatus.Available -> "dostepny"
                    HealthConnectSdkStatus.NotInstalled -> "wymaga instalacji"
                    HealthConnectSdkStatus.NotSupported -> "brak wsparcia"
                    HealthConnectSdkStatus.Unknown, null -> "sprawdzam"
                },
                quality = when (diagnostics?.sdkStatus) {
                    HealthConnectSdkStatus.Available -> DiagnosticQuality.Good
                    HealthConnectSdkStatus.NotInstalled,
                    HealthConnectSdkStatus.NotSupported -> DiagnosticQuality.Warning
                    else -> DiagnosticQuality.Neutral
                },
            )
            CompactMetricRow(
                label = "Zgody",
                value = when {
                    diagnostics == null -> "sprawdzam"
                    !diagnostics.permissionsChecked -> "po odswiezeniu"
                    diagnostics.hasAllPermissions -> "pelne"
                    else -> "${diagnostics.grantedPermissionCount}/${diagnostics.requiredPermissionCount}"
                },
                quality = when {
                    diagnostics?.permissionsChecked != true -> DiagnosticQuality.Neutral
                    diagnostics.hasAllPermissions -> DiagnosticQuality.Good
                    else -> DiagnosticQuality.Warning
                },
            )
            CompactMetricRow(
                label = "Lokalna baza",
                value = diagnostics?.dailySyncSummary?.lastSyncedAt ?: "brak zapisu",
                quality = if (diagnostics?.dailySyncSummary?.lastSyncedAt == null) {
                    DiagnosticQuality.Warning
                } else {
                    DiagnosticQuality.Good
                },
            )
        }
    }
}

@Composable
private fun SourceCoverageSection(summary: DailySyncSummary?) {
    SectionTitle("Co mamy w bazie")
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SourceTile(
                title = "Aktywnosc",
                value = summary?.activityDays?.toDayLabel() ?: "brak danych",
                detail = if ((summary?.activityDays ?: 0) > 0) {
                    "kroki i aktywne kcal sa widoczne"
                } else {
                    "czekamy na kroki z Health Connect"
                },
                quality = (summary?.activityDays ?: 0).qualityForCount(),
                modifier = Modifier.weight(1f),
            )
            SourceTile(
                title = "Serce",
                value = summary?.heartDays?.toDayLabel() ?: "brak danych",
                detail = if ((summary?.heartDays ?: 0) > 0) {
                    "puls gotowy do trendow"
                } else {
                    "brak pulsu w lokalnej bazie"
                },
                quality = (summary?.heartDays ?: 0).qualityForCount(),
                modifier = Modifier.weight(1f),
            )
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SourceTile(
                title = "Sen",
                value = summary?.sleepDays?.toDayLabel() ?: "brak danych",
                detail = if ((summary?.sleepDays ?: 0) > 0) {
                    "regeneracja bedzie analizowana"
                } else {
                    "brak snu w lokalnej bazie"
                },
                quality = (summary?.sleepDays ?: 0).qualityForCount(),
                modifier = Modifier.weight(1f),
            )
            SourceTile(
                title = "Treningi",
                value = summary?.workoutDays?.toDayLabel() ?: "brak danych",
                detail = if ((summary?.workoutDays ?: 0) > 0) {
                    "sesje beda laczone z pulsem"
                } else {
                    "brak treningow z Health Connect"
                },
                quality = (summary?.workoutDays ?: 0).qualityForCount(),
                modifier = Modifier.weight(1f),
            )
        }
        SourceTile(
            title = "Cialo",
            value = summary?.bodyDays?.toDayLabel() ?: "brak danych",
            detail = if ((summary?.bodyDays ?: 0) > 0) {
                "masa i parametry ciala sa zapisane"
            } else {
                "waga i sklad ciala wejda z importu lub recznie"
            },
            quality = (summary?.bodyDays ?: 0).qualityForCount(),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun SourceTile(
    title: String,
    value: String,
    detail: String,
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
                text = title,
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF0F172A),
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = value,
                style = MaterialTheme.typography.titleLarge,
                color = quality.color(),
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = detail,
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFF64748B),
            )
        }
    }
}

@Composable
private fun DataMeaningCard(summary: DailySyncSummary?) {
    val missing = buildList {
        if ((summary?.heartDays ?: 0) == 0) add("puls")
        if ((summary?.sleepDays ?: 0) == 0) add("sen")
        if ((summary?.workoutDays ?: 0) == 0) add("treningi")
        if ((summary?.bodyDays ?: 0) == 0) add("cialo")
    }

    AnalysisCard(
        title = "Co to oznacza",
        lines = if (missing.isEmpty()) {
            listOf(
                "mamy komplet glownych sygnalow do pierwszych porownan",
                "kolejny krok to trend 7/30 dni i import historii",
            )
        } else {
            listOf(
                "dzisiejszy dashboard bazuje glownie na aktywnosci",
                "bez: ${missing.joinToString()} nie liczymy pelnej regeneracji",
                "import historii Mi Fitness uzupelni dlugi baseline",
            )
        },
        quality = if (missing.isEmpty()) DiagnosticQuality.Good else DiagnosticQuality.Warning,
    )
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
            label = "Dni ciala",
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

private fun Double.format0(): String = "%,.0f".format(this)

private fun Double.format1(): String = "%,.1f".format(this)

private fun Long.formatWhole(): String = "%,d".format(this)

private fun Long.formatMinutes(): String {
    val hours = this / 60
    val minutes = this % 60
    return if (hours > 0) "${hours}h ${minutes}m" else "${minutes}m"
}

private fun Double.formatMinutes(): String = toLong().formatMinutes()

private fun DataQualityItem.statusLabel(): String {
    return when {
        !hasPermission -> "no permission"
        count30d > 0 -> "records found"
        else -> "no records"
    }
}

private enum class AppTab(val label: String) {
    Sleep("Sen"),
    Sport("Sport"),
    Weight("Waga"),
    Health("Zdrowie"),
    Analysis("Analiza"),
    Options("Opcje"),
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
