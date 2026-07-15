package com.apexlions.aurorastudio

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.CloudDone
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val HubBackground = Color(0xFF08090D)
private val HubSurface = Color(0xFF171922)
private val HubAccent = Color(0xFF9C5CFF)
private val HubMuted = Color(0xFFA7A8B3)

class StudioHubActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = HubAccent,
                    background = HubBackground,
                    surface = HubSurface,
                    onBackground = Color.White,
                    onSurface = Color.White,
                ),
            ) {
                StudioHub(
                    openPublishing = { startActivity(Intent(this, StudioV2Activity::class.java)) },
                    openLibrary = { startActivity(Intent(this, StudioLibraryActivity::class.java)) },
                    openCompletion = { startActivity(Intent(this, StudioAudioCompletionActivity::class.java)) },
                    openCuration = { startActivity(Intent(this, StudioCurationActivity::class.java)) },
                )
            }
        }
    }
}

@Composable
private fun StudioHub(
    openPublishing: () -> Unit,
    openLibrary: () -> Unit,
    openCompletion: () -> Unit,
    openCuration: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(HubAccent.copy(alpha = .22f), HubBackground, HubBackground),
                ),
            )
            .padding(22.dp),
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(30.dp))
            Surface(
                shape = RoundedCornerShape(20.dp),
                color = HubAccent,
                modifier = Modifier.size(68.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(38.dp), tint = Color.White)
                }
            }
            Spacer(Modifier.height(18.dp))
            Text("Aurora Studio", fontSize = 38.sp, fontWeight = FontWeight.ExtraBold)
            Text("Mobile v0.6.0", color = HubAccent, fontWeight = FontWeight.Bold)
            Text(
                "Yeni yayın oluşturma ile yayınlanmış içerik yönetimini birbirinden ayıran sade merkez.",
                color = HubMuted,
                lineHeight = 21.sp,
                modifier = Modifier.padding(top = 7.dp, bottom = 22.dp),
            )
            HubCard(
                title = "Yeni Yayın Oluştur",
                description = "Spotify metadata içe aktar, sesleri seç ve yeni albüm/single yayınla. Kapak otomatik Hugging Face'e taşınır.",
                icon = Icons.Rounded.Edit,
                onClick = openPublishing,
            )
            Spacer(Modifier.height(12.dp))
            HubCard(
                title = "Yayın Kütüphanesi",
                description = "Yayınlanmış albüm ve şarkıları tek yerde düzenle, albümden çıkar veya tamamen sil.",
                icon = Icons.Rounded.LibraryMusic,
                onClick = openLibrary,
            )
            Spacer(Modifier.height(12.dp))
            HubCard(
                title = "Yakında Ses Tamamlama",
                description = "Yeni release oluşturmadan bekleyen şarkılara toplu ses veya TXT/LRC dosyası eşleştir.",
                icon = Icons.Rounded.UploadFile,
                onClick = openCompletion,
            )
            Spacer(Modifier.height(12.dp))
            HubCard(
                title = "Sunum ve Listeler",
                description = "Sanatçı popülerlerini, seçkileri ve dinamik ana sayfa bölümlerini sırala.",
                icon = Icons.Rounded.LibraryMusic,
                onClick = openCuration,
            )
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CloudDone, null, tint = HubAccent, modifier = Modifier.size(18.dp))
                Text(
                    "GitHub, Hugging Face ve metadata tokenları ortak şifreli kasada korunur.",
                    color = HubMuted,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
            Spacer(Modifier.height(30.dp))
        }
    }
}

@Composable
private fun HubCard(
    title: String,
    description: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onClick: () -> Unit,
) {
    Card(
        colors = CardDefaults.cardColors(containerColor = HubSurface),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxWidth().padding(18.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Surface(
                shape = RoundedCornerShape(16.dp),
                color = HubAccent.copy(alpha = .16f),
                modifier = Modifier.size(54.dp),
            ) {
                Box(contentAlignment = Alignment.Center) {
                    Icon(icon, null, tint = HubAccent, modifier = Modifier.size(29.dp))
                }
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(description, color = HubMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Icon(Icons.Rounded.ArrowForward, null, tint = HubMuted)
        }
    }
}
