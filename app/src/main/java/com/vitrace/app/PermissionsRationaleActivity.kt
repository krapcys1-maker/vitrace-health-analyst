package com.vitrace.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

class PermissionsRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFFF8FAFC),
                ) {
                    PermissionsRationaleScreen()
                }
            }
        }
    }
}

@Composable
private fun PermissionsRationaleScreen() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = "VitaTrace privacy",
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF0F172A),
        )
        Text(
            text = "VitaTrace reads Health Connect data only on this phone to build personal activity, cardio, recovery, and data quality summaries.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFF334155),
        )
        Text(
            text = "Raw health records stay local. Future AI reports may use only aggregated metrics after explicit consent, never raw Health Connect records.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFF334155),
        )
        Text(
            text = "You can revoke Health Connect permissions at any time in Android settings.",
            style = MaterialTheme.typography.bodyLarge,
            color = Color(0xFF334155),
        )
    }
}
