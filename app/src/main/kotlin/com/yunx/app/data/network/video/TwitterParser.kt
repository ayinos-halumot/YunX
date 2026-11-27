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

package com.yunx.app.data.network.video

import com.yunx.app.R
import com.yunx.app.util.AppStrings
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * X（Twitter）帖子解析：`/<user>/status/<id>`、`/i/status/<id>`、`/i/web/status/<id>`。
 *
 * **免登录**，走 X 的公开聚合接口 `cdn.syndication.twimg.com/tweet-result`（帖子的嵌入卡片数据源）：
 * - 它不需要 Cookie、也不需要 API Key，只要一个由帖子 id 推出的 `token`（见 [embedToken]）；
 * - 返回 `photos[]`（图片）与 `mediaDetails[]`（photo / video / animated_gif，含 `video_info.variants`）。
 *
 * 内容口径（与用户确认一致）：
 * - **图片取原图**：`media_url_https` 追加 `?name=orig`；
 * - **动图 / 视频取最高画质**：`variants` 里筛 `video/mp4`，按 `bit_rate` 取最大（HLS/m3u8 不单独下）；
 * - 帖子内重复出现同一 URL 由上层统一去重（见 `VideoParseRepository`）。
 *
 * 已知边界：仅公开帖子；受保护/已删除/需要登录（敏感内容）的帖子会返回 404/403 —— 如实报错。
 * 本机（中国大陆网络）实测 `cdn.syndication.twimg.com` 不可直连，App 侧需用户自备可用网络。
 */
object TwitterParser : VideoParser {

    override val platform: VideoPlatform = VideoPlatform.TWITTER

    private val headers = mapOf(
        "User-Agent" to VideoHttp.UA_CHROME,
        "Referer" to "https://x.com/",
        "Accept" to "application/json, text/plain, */*"
    )

    override suspend fun parse(link: DetectedVideoLink, cookie: String): VideoParseResult {
        val id = link.id
        val url = "https://cdn.syndication.twimg.com/tweet-result?id=$id&lang=en&token=${embedToken(id)}"
        val response = VideoHttp.request(url, headers = headers)
        when (response.status) {
            404, 410 -> throw VideoParseException(
                VideoParseErrorKind.NOT_FOUND,
                AppStrings.get(R.string.er_x_not_found)
            )
            401, 403, 429 -> throw VideoParseException(
                VideoParseErrorKind.BLOCKED,
                AppStrings.get(R.string.er_x_blocked)
            )
        }
        val json = runCatching { JSONObject(response.body) }.getOrNull() ?: throw VideoParseException(
            VideoParseErrorKind.UNKNOWN,
            AppStrings.get(R.string.er_x_no_data)
        )
        if (json.optString("__typename").isBlank() && json.optString("id").isBlank()) {
            throw VideoParseException(VideoParseErrorKind.NOT_FOUND, AppStrings.get(R.string.er_x_not_found))
        }

        val user = json.optJSONObject("user")
        val author = user?.optString("name").orEmpty()
        val authorId = user?.optString("screen_name").orEmpty()
        val avatar = user?.optString("profile_image_url_https").orEmpty()
        val text = json.optString("text").orEmpty()
        val createdAt = json.optString("created_at").orEmpty()

        val mediaHeaders = mapOf("User-Agent" to VideoHttp.UA_CHROME, "Referer" to "https://x.com/")
        val medias = mutableListOf<VideoMedia>()
        var cover = ""

        // ① 图片：photos[] 里是原图地址（追加 ?name=orig 拿最大尺寸）
        val photos = json.optJSONArray("photos")
        if (photos != null) {
            for (i in 0 until photos.length()) {
                val raw = photos.optJSONObject(i)?.optString("url").orEmpty()
                val original = originalPhoto(raw)
                if (original.isBlank()) continue
                if (cover.isBlank()) cover = original
                medias += VideoMedia(
                    url = original,
                    kind = VideoMediaKind.IMAGE,
                    fileName = "x_${i + 1}.jpg",
                    headers = mediaHeaders,
                    quality = AppStrings.get(R.string.em_quality_original)
                )
            }
        }

        // ② 视频 / 动图：mediaDetails[]，variants 里筛 mp4 取最大码率
        var videoIndex = 0
        val details = json.optJSONArray("mediaDetails")
        if (details != null) {
            for (i in 0 until details.length()) {
                val item = details.optJSONObject(i) ?: continue
                val type = item.optString("type")
                val still = item.optString("media_url_https").orEmpty()
                if (cover.isBlank() && still.isNotBlank()) cover = originalPhoto(still)
                if (type != "video" && type != "animated_gif") continue
                val best = bestVideoVariant(item.optJSONObject("video_info")?.optJSONArray("variants"))
                    ?: continue
                videoIndex += 1
                medias += VideoMedia(
                    url = best.first,
                    kind = VideoMediaKind.VIDEO,
                    fileName = "x_video_$videoIndex.mp4",
                    headers = mediaHeaders,
                    quality = if (type == "animated_gif") {
                        AppStrings.get(R.string.em_quality_motion)
                    } else {
                        best.second
                    }
                )
            }
        }

        if (medias.isEmpty()) {
            throw VideoParseException(
                VideoParseErrorKind.UNSUPPORTED,
                AppStrings.get(R.string.er_x_no_media)
            )
        }

        return VideoParseResult(
            platform = platform,
            title = text.lineSequence().firstOrNull().orEmpty().ifBlank { "x_$id" },
            author = author,
            authorId = authorId,
            avatar = avatar,
            cover = cover,
            text = text,
            timestampSeconds = parseCreatedAt(createdAt),
            url = link.url,
            medias = medias
        )
    }

    /**
     * X 嵌入接口的 `token`：对帖子 id 做「(id / 1e15) × π → 36 进制 → 去掉 `0` 与小数点」。
     * 这是公开嵌入卡片长期使用的算法（不是密钥，也不是签名），换算法只影响这个接口的缓存键。
     */
    internal fun embedToken(id: String): String {
        val numeric = id.toDoubleOrNull() ?: return ""
        return ((numeric / 1e15) * Math.PI).toString(36).replace(Regex("(0+|\\.)"), "")
    }

    /** 图片取原图：`.../media/xxx.jpg` → `.../media/xxx.jpg?name=orig`（已有参数则替换 name） */
    internal fun originalPhoto(url: String): String {
        if (url.isBlank()) return ""
        val base = url.substringBefore('?')
        return "$base?name=orig"
    }

    /**
     * 从 `video_info.variants` 里选最高码率的 `video/mp4`。
     * @return `(url, 质量标签)`；没有 mp4 变体时返回 null
     */
    internal fun bestVideoVariant(variants: JSONArray?): Pair<String, String>? {
        if (variants == null) return null
        var best: Pair<String, String>? = null
        var bestRate = -1L
        for (i in 0 until variants.length()) {
            val v = variants.optJSONObject(i) ?: continue
            val type = v.optString("content_type")
            val url = v.optString("url")
            if (type != "video/mp4" || url.isBlank()) continue
            val rate = v.optLong("bit_rate", 0L)
            if (rate > bestRate) {
                bestRate = rate
                best = url to bitrateLabel(rate)
            }
        }
        return best
    }

    /** 码率 → 用户可读的质量标签（Mbps，一位小数） */
    internal fun bitrateLabel(bitRate: Long): String =
        if (bitRate <= 0) "" else String.format(Locale.US, "%.1f Mbps", bitRate / 1_000_000.0)

    /** X 的时间格式：`Wed Oct 10 20:19:24 +0000 2018` */
    internal fun parseCreatedAt(value: String): Long {
        if (value.isBlank()) return 0L
        val fmt = SimpleDateFormat("EEE MMM dd HH:mm:ss Z yyyy", Locale.US)
        return runCatching { fmt.parse(value)?.time?.div(1000L) }.getOrNull() ?: 0L
    }
}
