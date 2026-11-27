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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.unit.dp
import com.yunx.app.R
import com.yunx.app.ui.items.logoRes
import com.yunx.app.ui.viewmodel.ParseQueueItem
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete

/**
 * 输入页下方的解析队列：每条链接一行。
 *
 * 行内三态勾选只作**指示**（点行进入内容页挑内容）：
 * - 全选 → √；部分选 → 半选方块；未选 → 空。
 */
@Composable
fun ParseQueueList(
    items: List<ParseQueueItem>,
    onOpen: (Long) -> Unit,
    onRemove: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.rs_queue_title, items.size),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 4.dp, top = 8.dp)
        )
        items.forEach { item ->
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = item.result != null) { onOpen(item.id) },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(item.link?.platform?.logoRes() ?: R.drawable.icon),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp)
                    )
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            text = item.result?.title?.takeIf { it.isNotBlank() } ?: item.url,
                            style = MaterialTheme.typography.bodyMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        val status = when {
                            item.parsing -> stringResource(R.string.rs_queue_parsing)
                            item.error != null -> item.error.orEmpty()
                            item.result != null -> stringResource(
                                R.string.rs_batch_item_media,
                                item.result.imageCount,
                                item.result.videoCount
                            )
                            else -> stringResource(R.string.rs_queue_waiting)
                        }
                        Text(
                            text = status,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (item.error != null) MaterialTheme.colorScheme.error
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                    TriStateCheckbox(
                        state = when {
                            item.result == null -> ToggleableState.Off
                            item.allSelected -> ToggleableState.On
                            item.partiallySelected -> ToggleableState.Indeterminate
                            else -> ToggleableState.Off
                        },
                        onClick = null
                    )
                    IconButton(onClick = { onRemove(item.id) }) {
                        Icon(
                            Icons.Filled.Close,
                            contentDescription = stringResource(R.string.rs_queue_remove),
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }
            }
        }
    }
}
