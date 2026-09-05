package com.sportapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
fun ThemePickerDialog(
    current: GlassTheme,
    isDarkMode: Boolean,
    onSelect: (GlassTheme) -> Unit,
    onDismiss: () -> Unit
) {
    val panel = if (isDarkMode) Color(0xEE1A0B2E) else Color(0xEEFFFFFF)
    val text = if (isDarkMode) Color(0xFFF5EEFF) else Color(0xFF1A0B2E)
    val sub = if (isDarkMode) Color(0xFFB9A3D4) else Color(0xFF6B5A8A)
    val green = Color(0xFF00E5A8)

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(18.dp))
                .background(panel)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("🎨 Téma & üveg", color = green, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Text(
                "Válassz háttérszínt – üveghatású kártyákkal.",
                color = sub,
                fontSize = 12.sp,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp)
            )

            GlassTheme.entries.forEach { theme ->
                val selected = theme == current
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .border(
                            width = if (selected) 2.dp else 0.5.dp,
                            color = if (selected) green else Color(0x33FFFFFF),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .background(
                            Brush.horizontalGradient(
                                listOf(Color(theme.g1), Color(theme.g2), Color(theme.g3))
                            )
                        )
                        .clickable {
                            onSelect(theme)
                            onDismiss()
                        }
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(28.dp)
                            .clip(CircleShape)
                            .background(Color(theme.accent))
                            .border(1.dp, Color.White.copy(alpha = 0.4f), CircleShape)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier = Modifier.weight(1f)) {
                        Text(
                            "${theme.emoji} ${theme.label}",
                            color = Color.White,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp
                        )
                        Text(
                            if (selected) "Aktív" else "Koppints a váltáshoz",
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp
                        )
                    }
                    if (selected) {
                        Text("✓", color = green, fontWeight = FontWeight.Bold, fontSize = 18.sp)
                    }
                }
            }

            Spacer(Modifier.height(12.dp))
            TextButton(onClick = onDismiss, modifier = Modifier.align(Alignment.End)) {
                Text("Bezárás", color = green)
            }
        }
    }
}
