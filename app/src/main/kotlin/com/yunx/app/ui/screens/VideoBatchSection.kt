/*
 * YunX (云析) - A network drive share-link parser and high-speed downloader for Android.
 * Copyright (C) 2026 CYQawa
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program.  If not, see <https://www.gnu.org/licenses/>.
 */

package com.yunx.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.yunx.app.R
import com.yunx.app.data.network.video.VideoMediaKind
import com.yunx.app.ui.items.logoRes
import com.yunx.app.ui.viewmodel.VideoBatchItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack

/**
 * 多链接解析结果页：一条链接一张卡（可勾选），底部「下载所选」把勾选条目的全部媒体加入下载。
 *
 * 解析仍在进行时条目会先以「解析中…」出现，完成后原地替换成结果或错误。
 */
@Composable
fun VideoBatchSection(
    items: List<VideoBatchItem>,
    skippedDuplicates: Int,
    truncated: Int,
    onToggle: (Int) -> Unit,
    onToggleAll: (Boolean) -> Unit,
    onDownloadSelected: () -> Unit,
    onBack: () -> Unit
) {
    val selectedCount = items.count { it.selected && it.result != null }
    val allSelected = items.filter { it.result != null }.all { it.selected } &&
        items.any { it.result != null }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
            }
            Text(
                text = stringResource(R.string.rs_batch_title),
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold
            )
            Spacer(Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.rs_batch_summary, items.size, selectedCount),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.weight(1f))
            TextButton(onClick = { onToggleAll(!allSelected) }) {
                Text(stringResource(R.string.rs_batch_select_all))
            }
        }

        if (skippedDuplicates > 0) {
            Text(
                text = stringResource(R.string.rs_batch_skipped, skippedDuplicates),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        if (truncated > 0) {
            Text(
                text = stringResource(R.string.rs_batch_truncated, truncated),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(items) { index, item ->
                val result = item.result
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = result != null) { onToggle(index) },
                    colors = CardDefaults.cardColors()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(item.link.platform.logoRes()),
                            contentDescription = null,
                            modifier = Modifier.size(28.dp)
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                text = result?.title?.takeIf { it.isNotBlank() }
                                    ?: item.link.url,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            val subtitle = when {
                                item.error != null -> item.error
                                result != null -> buildString {
                                    if (result.author.isNotBlank()) append(result.author).append(' · ')
                                    append(
                                        stringResource(
                                            R.string.rs_batch_item_media,
                                            result.imageCount,
                                            result.videoCount
                                        )
                                    )
                                }
                                else -> stringResource(R.string.rs_parsing)
                            }
                            Text(
                                text = subtitle,
                                style = MaterialTheme.typography.bodySmall,
                                color = if (item.error != null) {
                                    MaterialTheme.colorScheme.error
                                } else {
                                    MaterialTheme.colorScheme.onSurfaceVariant
                                },
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                        Checkbox(
                            checked = item.selected && result != null,
                            enabled = result != null,
                            onCheckedChange = { onToggle(index) }
                        )
                    }
                }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Button(
                onClick = onDownloadSelected,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                enabled = selectedCount > 0
            ) {
                Text(stringResource(R.string.rs_batch_download_selected, selectedCount))
            }
        }
    }
}
