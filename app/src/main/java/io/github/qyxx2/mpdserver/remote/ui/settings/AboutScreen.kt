package io.github.qyxx2.mpdserver.remote.ui.settings

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import io.github.qyxx2.mpdserver.remote.BuildConfig

const val MPD_REMOTE_REPOSITORY_URL = "https://github.com/qyxx2/MPD-Server_remote_for_Android"

/** The single source for the repository action used by the About page and its contract test. */
fun createRepositoryLinkIntent(): Intent =
    Intent(Intent.ACTION_VIEW, Uri.parse(MPD_REMOTE_REPOSITORY_URL))

@Composable
fun AboutScreen(onOpenRepository: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState())
            .padding(horizontal = 20.dp, vertical = 20.dp).testTag("about-settings-screen"),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            BuildConfig.APP_DISPLAY_NAME,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.testTag("about-app-name"),
        )
        Text(
            "面向 MPD-Server 的 Android 远程客户端，用于浏览音乐库、查看播放信息并控制服务端播放。播放队列与播放状态仍以服务端为唯一权威。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("about-introduction"),
        )
        AboutInfoRow("版本", BuildConfig.VERSION_NAME, "about-version")
        AboutInfoRow("作者", "qyxx2", "about-author")
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("开源项目", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
            Text(
                MPD_REMOTE_REPOSITORY_URL,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.testTag("about-repository-url"),
            )
            OutlinedButton(onClick = onOpenRepository, modifier = Modifier.testTag("about-repository-link")) {
                Text("查看 GitHub 仓库")
            }
        }
    }
}

@Composable
private fun AboutInfoRow(label: String, value: String, tag: String) {
    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.testTag(tag))
    }
}
