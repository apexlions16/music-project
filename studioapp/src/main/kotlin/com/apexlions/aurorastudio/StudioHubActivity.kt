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
import androidx.compose.material.icons.rounded.Code
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Groups
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Settings
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
        fun admin(mode: StudioAdminMode) = Intent(this, StudioAdminActivity::class.java).putExtra("mode", mode.key)
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
                    openOverview = { startActivity(admin(StudioAdminMode.OVERVIEW)) },
                    openPublishing = { startActivity(Intent(this, StudioV2Activity::class.java)) },
                    openLibrary = { startActivity(Intent(this, StudioLibraryActivity::class.java)) },
                    openCompletion = { startActivity(Intent(this, StudioAudioCompletionActivity::class.java)) },
                    openArtists = { startActivity(admin(StudioAdminMode.ARTISTS)) },
                    openCuration = { startActivity(Intent(this, StudioCurationActivity::class.java)) },
                    openJson = { startActivity(admin(StudioAdminMode.JSON)) },
                    openSettings = { startActivity(admin(StudioAdminMode.SETTINGS)) },
                )
            }
        }
    }
}

@Composable
private fun StudioHub(
    openOverview: () -> Unit,
    openPublishing: () -> Unit,
    openLibrary: () -> Unit,
    openCompletion: () -> Unit,
    openArtists: () -> Unit,
    openCuration: () -> Unit,
    openJson: () -> Unit,
    openSettings: () -> Unit,
) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(HubAccent.copy(alpha = .22f), HubBackground, HubBackground)))
            .padding(22.dp),
    ) {
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.Center,
        ) {
            Spacer(Modifier.height(30.dp))
            Surface(shape = RoundedCornerShape(20.dp), color = HubAccent, modifier = Modifier.size(68.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Rounded.AutoAwesome, null, modifier = Modifier.size(38.dp), tint = Color.White) }
            }
            Spacer(Modifier.height(18.dp))
            Text("Aurora Studio", fontSize = 38.sp, fontWeight = FontWeight.ExtraBold)
            Text("Mobile v0.7.0", color = HubAccent, fontWeight = FontWeight.Bold)
            Text(
                "Windows Studio'daki sekiz yönetim bölümüyle eşlenik mobil merkez.",
                color = HubMuted,
                lineHeight = 21.sp,
                modifier = Modifier.padding(top = 7.dp, bottom = 22.dp),
            )
            HubCard("Genel Bakış", "Sanatçı, yayın, şarkı ve Yakında durumlarını tek ekranda gör.", Icons.Rounded.Home, openOverview)
            Spacer(Modifier.height(10.dp))
            HubCard("Yeni Yayın", "Spotify metadata içe aktar, toplu ses eşleştir ve yeni albüm/single yayınla.", Icons.Rounded.Edit, openPublishing)
            Spacer(Modifier.height(10.dp))
            HubCard("Yayın Kütüphanesi", "Yayın ve şarkıları ara, düzenle, LRC yükle veya kaldır.", Icons.Rounded.LibraryMusic, openLibrary)
            Spacer(Modifier.height(10.dp))
            HubCard("Yakında Tamamlama", "Mevcut şarkılara yeni release oluşturmadan ses, TXT veya LRC ekle.", Icons.Rounded.UploadFile, openCompletion)
            Spacer(Modifier.height(10.dp))
            HubCard("Sanatçılar", "Profil, hero, arka plan, video ve biyografi alanlarını yönet.", Icons.Rounded.Groups, openArtists)
            Spacer(Modifier.height(10.dp))
            HubCard("Sunum ve Listeler", "Popülerleri, sanatçı seçkilerini ve ana sayfa raflarını sırala.", Icons.Rounded.LibraryMusic, openCuration)
            Spacer(Modifier.height(10.dp))
            HubCard("Katalog JSON", "Ham katalog verisini doğrulayarak düzenle ve GitHub'a commit et.", Icons.Rounded.Code, openJson)
            Spacer(Modifier.height(10.dp))
            HubCard("Ayarlar", "GitHub, Hugging Face ve Spotify anahtarlarını şifreli kaydet.", Icons.Rounded.Settings, openSettings)
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.CloudDone, null, tint = HubAccent, modifier = Modifier.size(18.dp))
                Text("Bütün bölümler aynı katalog ve şifreli ayar kasasını kullanır.", color = HubMuted, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp))
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
        Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
            Surface(shape = RoundedCornerShape(16.dp), color = HubAccent.copy(alpha = .16f), modifier = Modifier.size(54.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(icon, null, tint = HubAccent, modifier = Modifier.size(29.dp)) }
            }
            Column(Modifier.weight(1f).padding(horizontal = 14.dp)) {
                Text(title, fontSize = 18.sp, fontWeight = FontWeight.Bold)
                Text(description, color = HubMuted, fontSize = 12.sp, lineHeight = 17.sp)
            }
            Icon(Icons.Rounded.ArrowForward, null, tint = HubMuted)
        }
    }
}
