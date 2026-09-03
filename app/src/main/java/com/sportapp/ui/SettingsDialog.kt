package com.sportapp.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog

@Composable
fun SettingsDialog(
    isDarkMode: Boolean,
    onDismiss: () -> Unit
) {
    val ctx = LocalContext.current
    val card = if (isDarkMode) Color(0xFF152238) else Color.White
    val text = if (isDarkMode) Color(0xFFF0F6FF) else Color(0xFF0D1B2A)
    val sub = if (isDarkMode) Color(0xFF9BB0C9) else Color(0xFF5A6F8A)
    val green = Color(0xFF00E5A8)

    var quiet by remember { mutableStateOf(NotifPrefs.quietHours(ctx)) }
    var allowFav by remember { mutableStateOf(NotifPrefs.allowFavoriteDuringQuiet(ctx)) }
    val typeKeys = remember { NotifPrefs.allTypeKeys() }
    var typeEnabled by remember {
        mutableStateOf(typeKeys.associate { it.first to NotifPrefs.isTypeEnabled(ctx, it.first) })
    }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(card)
                .padding(16.dp)
                .verticalScroll(rememberScrollState())
        ) {
            Text("⚙️ Beállítások", color = green, fontWeight = FontWeight.Bold, fontSize = 18.sp)
            Spacer(Modifier.height(12.dp))

            Text("Értesítés típusok", color = text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Spacer(Modifier.height(6.dp))
            typeKeys.forEach { (key, label) ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            val next = !(typeEnabled[key] ?: true)
                            typeEnabled = typeEnabled + (key to next)
                            NotifPrefs.setTypeEnabled(ctx, key, next)
                        }
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(label, color = text, modifier = Modifier.weight(1f), fontSize = 13.sp)
                    Switch(
                        checked = typeEnabled[key] ?: true,
                        onCheckedChange = {
                            typeEnabled = typeEnabled + (key to it)
                            NotifPrefs.setTypeEnabled(ctx, key, it)
                        }
                    )
                }
            }

            Spacer(Modifier.height(14.dp))
            Text("Csendes órák", color = text, fontWeight = FontWeight.SemiBold, fontSize = 14.sp)
            Text(
                "Értesítések szünetelnek ebben az idősávban (kivéve ha engedélyezed a kedvenceket).",
                color = sub,
                fontSize = 11.sp
            )
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Kezdet: ${quiet.first}:00", color = text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    val n = (quiet.first + 23) % 24
                    quiet = n to quiet.second
                    NotifPrefs.setQuietHours(ctx, n, quiet.second)
                }) { Text("−", color = green, fontSize = 18.sp) }
                IconButton(onClick = {
                    val n = (quiet.first + 1) % 24
                    quiet = n to quiet.second
                    NotifPrefs.setQuietHours(ctx, n, quiet.second)
                }) { Text("+", color = green, fontSize = 18.sp) }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Vége: ${quiet.second}:00", color = text, fontSize = 13.sp, modifier = Modifier.weight(1f))
                IconButton(onClick = {
                    val n = (quiet.second + 23) % 24
                    quiet = quiet.first to n
                    NotifPrefs.setQuietHours(ctx, quiet.first, n)
                }) { Text("−", color = green, fontSize = 18.sp) }
                IconButton(onClick = {
                    val n = (quiet.second + 1) % 24
                    quiet = quiet.first to n
                    NotifPrefs.setQuietHours(ctx, quiet.first, n)
                }) { Text("+", color = green, fontSize = 18.sp) }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable {
                        allowFav = !allowFav
                        NotifPrefs.setAllowFavoriteDuringQuiet(ctx, allowFav)
                    }
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Kedvencek a csendes órákban is", color = text, modifier = Modifier.weight(1f), fontSize = 13.sp)
                Switch(checked = allowFav, onCheckedChange = {
                    allowFav = it
                    NotifPrefs.setAllowFavoriteDuringQuiet(ctx, it)
                })
            }

            Spacer(Modifier.height(12.dp))
            Button(
                onClick = onDismiss,
                colors = ButtonDefaults.buttonColors(containerColor = green),
                modifier = Modifier.align(Alignment.End)
            ) {
                Text("Kész", color = Color.Black)
            }
        }
    }
}
