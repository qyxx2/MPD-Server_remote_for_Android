package io.github.qyxx2.mpdserver.remote.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Root Library directory. It contains only real routes, not placeholder counts or recommendations. */
@Composable
fun LibraryScreen(
    onOpenCategory: (LibraryCategory) -> Unit,
    modifier: Modifier = Modifier,
    interactionEnabled: Boolean = true,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .testTag("library-root-screen"),
    ) {
        Text(
            text = "音乐库",
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 12.dp)
                .testTag("library-root-title"),
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground,
        )
        LazyColumn(
            modifier = Modifier.fillMaxWidth().testTag("library-category-list"),
            verticalArrangement = Arrangement.Top,
        ) {
            items(LibraryProjection.categories, key = { it.pathSegment }) { category ->
                Column {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 56.dp)
                            .then(
                                if (interactionEnabled) Modifier.clickable { onOpenCategory(category) }
                                else Modifier,
                            )
                            .padding(horizontal = 20.dp)
                            .testTag("library-category-${category.pathSegment}"),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            text = category.glyph,
                            modifier = Modifier.size(28.dp)
                                .semantics { contentDescription = category.label },
                            color = MaterialTheme.colorScheme.primary,
                            fontSize = 24.sp,
                        )
                        Text(
                            text = category.label,
                            modifier = Modifier.weight(1f).padding(start = 16.dp),
                            fontSize = 18.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                        Text(
                            text = "›",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            fontSize = 26.sp,
                        )
                    }
                    HorizontalDivider(
                        modifier = Modifier.padding(start = 64.dp, end = 20.dp),
                        thickness = 0.5.dp,
                        color = MaterialTheme.colorScheme.outlineVariant,
                    )
                }
            }
        }
    }
}
