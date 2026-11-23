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

package com.yunx.app.data.download

import com.yunx.app.data.network.video.VideoMedia
import com.yunx.app.data.network.video.VideoMediaKind
import com.yunx.app.data.network.video.VideoParseResult
import com.yunx.app.data.network.video.VideoPlatform

/**
 * 「详细命名」：`平台_作者(@ID)_标题_类型_序号.扩展名`
 *
 * 例：`xhs_0442(@26545950442)_xxxxx_photo_1.jpg`
 *
 * 设计约束（都是踩过的坑）：
 * - 分段里的内容一律过 [DownloadPathPolicy.sanitizeName]：标题/昵称里带 `/`、`:` 会直接毁掉保存路径；
 * - 标题截 40 字：文件名上限 120 字符，不截会把扩展名挤掉（sanitizeName 的截断逻辑优先保留扩展名）；
 * - 序号按**总张数**补零：13 张 → `01..13`，104 张 → `001..104`；单张不补零（`1`）；
 * - 作者或 ID 缺失时整段省略，不留 `_(@)_` 这种空洞；
 * - 只改 `fileName`，不动 url / headers / kind 等任何下载相关字段。
 */
object MediaNaming {

    /** 平台简称（用户指定：xhs / dy / ks / wb / acfun / bili / pixiv） */
    fun platformTag(platform: VideoPlatform): String = when (platform) {
        VideoPlatform.XHS -> "xhs"
        VideoPlatform.DOUYIN -> "dy"
        VideoPlatform.KUAISHOU -> "ks"
        VideoPlatform.WEIBO -> "wb"
        VideoPlatform.ACFUN -> "acfun"
        VideoPlatform.BILIBILI -> "bili"
        VideoPlatform.PIXIV -> "pixiv"
    }

    /** 类型段（英文）：动图单独算 motion，其余按媒体类型 */
    fun kindTag(media: VideoMedia): String = when {
        media.motionUrl.isNotBlank() -> "motion"
        media.kind == VideoMediaKind.IMAGE -> "photo"
        media.kind == VideoMediaKind.AUDIO -> "audio"
        else -> "video"
    }

    /** 生成详细命名后的结果（不修改入参，返回副本） */
    fun detailed(result: VideoParseResult): VideoParseResult {
        val total = result.medias.size
        if (total == 0) return result
        val width = total.toString().length

        val authorPart = buildString {
            val name = DownloadPathPolicy.sanitizeName(result.author.trim()).take(20)
            if (name.isNotBlank()) append(name)
            val id = result.authorId.trim()
            if (id.isNotBlank()) append("(@").append(DownloadPathPolicy.sanitizeName(id)).append(")")
        }
        val title = DownloadPathPolicy.sanitizeName(result.title.trim()).take(40)
        val platform = platformTag(result.platform)

        return result.copy(
            medias = result.medias.mapIndexed { index, media ->
                val seq = (index + 1).toString().padStart(width, '0')
                val ext = extensionOf(media)
                val name = buildString {
                    append(platform)
                    append('_')
                    if (authorPart.isNotBlank()) {
                        append(authorPart)
                        append('_')
                    }
                    if (title.isNotBlank()) {
                        append(title)
                        append('_')
                    }
                    append(kindTag(media))
                    append('_')
                    append(seq)
                    append('.')
                    append(ext)
                }
                media.copy(fileName = name)
            }
        )
    }

    /** 沿用解析器给的原扩展名（`.mp4`/`.jpg`…）；拿不到时按类型兜底 */
    private fun extensionOf(media: VideoMedia): String {
        val raw = media.fileName.substringAfterLast('.', "")
        if (raw.isNotBlank() && raw.length <= 5 && raw.none { !it.isLetterOrDigit() }) return raw.lowercase()
        return when (media.kind) {
            VideoMediaKind.IMAGE -> "jpg"
            VideoMediaKind.AUDIO -> "m4a"
            else -> "mp4"
        }
    }
}
