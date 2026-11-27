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
 * **免登录**，主通道是 X 的公开聚合接口 `cdn.syndication.twimg.com/tweet-result`：
 * - 不需要 Cookie/API Key，只要由帖子 id 推出的 `token`（见 [embedToken]）；
 * - 返回 `photos[]`（图片）与 `mediaDetails[]`（photo / video / animated_gif，含 `video_info.variants`）。
 *
 * **兜底通道**：聚合接口对部分帖子会返回 `{"__typename":"TweetTombstone"}`（敏感内容/地区限制），
 * 这类帖子 x.com 网页是 JS 渲染、HTML 里没有直链，因此在主通道拿不到媒体时改走 `api.fxtwitter.com`
 * （开源第三方镜像，仅在本机请求、不经过 YunX 服务器）。两条通道都失败才报错。
 *
 * 内容口径：
 * - **图片取原图**：`media_url_https` 追加 `?name=orig`；
 * - **视频/动图取最高画质**：变体里优先 `video/mp4`，按 URL 里的 `<宽>x<高>` 取面积最大（同面积看 bit_rate）；
 *   没有 mp4 时退回 m3u8（下载器已支持 HLS 分片合并）；
 * - 同一帖子的图片与视频**都收**（混合媒体），重复 URL 在上层统一去重。
 */
object TwitterParser : VideoParser {

    override val platform: VideoPlatform = VideoPlatform.TWITTER

    private val headers = mapOf(
        "User-Agent" to VideoHttp.UA_CHROME,
        "Referer" to "https://x.com/",
        "Accept" to "application/json, text/plain, */*"
    )

    private val mediaHeaders = mapOf(
        "User-Agent" to VideoHttp.UA_CHROME,
        "Referer" to "https://x.com/"
    )

    override suspend fun parse(link: DetectedVideoLink, cookie: String): VideoParseResult {
        val id = link.id
        val primary = runCatching { parseSyndication(id, link.url) }.getOrNull()
        if (primary != null && primary.medias.isNotEmpty()) return primary
        // 主通道「有帖子但没媒体」或「Tombstone」都交给兜底通道再试一次
        val fallback = runCatching { parseFallback(id, link.url) }.getOrNull()
        if (fallback != null && fallback.medias.isNotEmpty()) return fallback
        throw VideoParseException(
            VideoParseErrorKind.UNSUPPORTED,
            AppStrings.get(R.string.er_x_no_media)
        )
    }

    /** 主通道：X 嵌入接口。帖子不可见（Tombstone）/无媒体时返回 null，交给兜底。 */
    private suspend fun parseSyndication(id: String, linkUrl: String): VideoParseResult? {
        val tokens = listOf(embedToken(id), "", "0").distinct()
        var response = VideoHttp.request(
            "https://cdn.syndication.twimg.com/tweet-result?id=$id&lang=en&token=${tokens.first()}",
            headers = headers
        )
        for (i in 1 until tokens.size) {
            if (response.status in 200..299) break
            response = VideoHttp.request(
                "https://cdn.syndication.twimg.com/tweet-result?id=$id&lang=en&token=${tokens[i]}",
                headers = headers
            )
        }
        when (response.status) {
            404, 410 -> return null
            401, 403, 429 -> return null
        }
        val json = runCatching { JSONObject(response.body) }.getOrNull() ?: return null
        if (json.optString("__typename") == "TweetTombstone") return null
        if (json.optString("id").isBlank() && json.optString("text").isBlank()) return null

        val user = json.optJSONObject("user")
        val author = user?.optString("name").orEmpty()
        val authorId = user?.optString("screen_name").orEmpty()
        val avatar = user?.optString("profile_image_url_https").orEmpty()
        val text = json.optString("text").orEmpty()
        val createdAt = json.optString("created_at").orEmpty()

        val medias = mutableListOf<VideoMedia>()
        val seen = mutableSetOf<String>()
        var cover = ""

        // ① 图片（photos[] 是原图基址）
        val photos = json.optJSONArray("photos")
        if (photos != null) {
            var index = 0
            for (i in 0 until photos.length()) {
                val raw = photos.optJSONObject(i)?.optString("url").orEmpty()
                val original = originalPhoto(raw)
                if (original.isBlank() || !seen.add(original)) continue
                index += 1
                if (cover.isBlank()) cover = original
                medias += VideoMedia(
                    url = original,
                    kind = VideoMediaKind.IMAGE,
                    fileName = "x_${index}.jpg",
                    headers = mediaHeaders,
                    quality = AppStrings.get(R.string.em_quality_original)
                )
            }
        }

        // ② 视频 / 动图
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
                if (!seen.add(best.first)) continue
                videoIndex += 1
                medias += VideoMedia(
                    url = best.first,
                    kind = VideoMediaKind.VIDEO,
                    fileName = "x_video_${videoIndex}.mp4",
                    headers = mediaHeaders,
                    quality = if (type == "animated_gif") {
                        AppStrings.get(R.string.em_quality_motion)
                    } else {
                        best.second
                    }
                )
            }
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
            url = linkUrl,
            medias = medias
        )
    }

    /**
     * 兜底通道：`api.fxtwitter.com`（开源镜像，返回 `tweet.media.all[]`）。
     *
     * 只在主通道拿不到媒体时调用；字段缺失一律跳过，不抛异常。
     */
    private suspend fun parseFallback(id: String, linkUrl: String): VideoParseResult? {
        val response = VideoHttp.request(
            "https://api.fxtwitter.com/i/status/$id",
            headers = mapOf("User-Agent" to VideoHttp.UA_CHROME, "Accept" to "application/json")
        )
        if (response.status !in 200..299) return null
        val root = runCatching { JSONObject(response.body) }.getOrNull() ?: return null
        val tweet = root.optJSONObject("tweet") ?: return null
        val authorObj = tweet.optJSONObject("author")

        val medias = mutableListOf<VideoMedia>()
        val seen = mutableSetOf<String>()
        var cover = ""
        var imageIndex = 0
        var videoIndex = 0
        val all = tweet.optJSONObject("media")?.optJSONArray("all")
        if (all != null) {
            for (i in 0 until all.length()) {
                val item = all.optJSONObject(i) ?: continue
                when (item.optString("type")) {
                    "photo" -> {
                        val url = originalPhoto(item.optString("url").orEmpty())
                        if (url.isBlank() || !seen.add(url)) continue
                        imageIndex += 1
                        if (cover.isBlank()) cover = url
                        medias += VideoMedia(
                            url = url,
                            kind = VideoMediaKind.IMAGE,
                            fileName = "x_${imageIndex}.jpg",
                            headers = mediaHeaders,
                            quality = AppStrings.get(R.string.em_quality_original)
                        )
                    }
                    "video", "gif" -> {
                        val best = bestVideoVariant(item.optJSONArray("variants"))
                            ?: item.optString("url").takeIf { it.isNotBlank() }?.let { it to "" }
                            ?: continue
                        if (!seen.add(best.first)) continue
                        videoIndex += 1
                        medias += VideoMedia(
                            url = best.first,
                            kind = VideoMediaKind.VIDEO,
                            fileName = "x_video_${videoIndex}.mp4",
                            headers = mediaHeaders,
                            quality = if (item.optString("type") == "gif") {
                                AppStrings.get(R.string.em_quality_motion)
                            } else {
                                best.second
                            }
                        )
                    }
                }
            }
        }

        return VideoParseResult(
            platform = platform,
            title = tweet.optString("text").lineSequence().firstOrNull().orEmpty().ifBlank { "x_$id" },
            author = authorObj?.optString("name").orEmpty(),
            authorId = authorObj?.optString("screen_name").orEmpty(),
            avatar = authorObj?.optString("avatar_url").orEmpty(),
            cover = cover,
            text = tweet.optString("text").orEmpty(),
            timestampSeconds = tweet.optLong("created_timestamp", 0L),
            url = linkUrl,
            medias = medias
        )
    }

    /**
     * X 嵌入接口的 `token`：对帖子 id 做「(id / 1e15) × π → 36 进制 → 去掉 `0` 与小数点」。
     * 这是公开嵌入卡片长期使用的算法（不是密钥，也不是签名），换算法只影响这个接口的缓存键。
     */
    internal fun embedToken(id: String): String {
        val numeric = id.toDoubleOrNull() ?: return ""
        return toBase36((numeric / 1e15) * Math.PI).replace(Regex("(0+|\\.)"), "")
    }

    /**
     * 等价于 JS 的 Number.prototype.toString(36)：整数部分按 36 进制，小数部分逐位乘 36 取整
     * （最多 20 位，与 JS 的精度上限一致）。Kotlin/Java 的 toString 只支持十进制，所以自己实现。
     */
    private fun toBase36(value: Double): String {
        if (value.isNaN() || value.isInfinite()) return ""
        val v = kotlin.math.abs(value)
        val intPart = v.toLong()
        var frac = v - intPart
        val sb = StringBuilder()
        if (intPart == 0L) {
            sb.append('0')
        } else {
            val digits = StringBuilder()
            var n = intPart
            while (n > 0) {
                digits.append(BASE36_DIGITS[(n % 36).toInt()])
                n /= 36
            }
            sb.append(digits.reverse())
        }
        if (frac > 0.0) {
            sb.append('.')
            var count = 0
            while (frac > 0.0 && count < 20) {
                frac *= 36
                val d = frac.toInt().coerceIn(0, 35)
                sb.append(BASE36_DIGITS[d])
                frac -= d
                count++
            }
        }
        return sb.toString()
    }

    private const val BASE36_DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz"

    /** 图片取原图：`.../media/xxx.jpg` → `.../media/xxx.jpg?name=orig`（已有参数则替换 name） */
    internal fun originalPhoto(url: String): String {
        if (url.isBlank()) return ""
        val base = url.substringBefore('?')
        return "$base?name=orig"
    }

    /**
     * 从变体里选画质最高的那一路。
     *
     * - 字段兼容两种写法：`type|content_type`、`src|url`（聚合接口用前者，其它镜像用后者）；
     * - 优先 `video/mp4`：按 URL 里的 `/<宽>x<高>/` 算面积取最大，面积相同再看 `bit_rate`；
     * - 没有 mp4 时退回 HLS（m3u8），质量标签用 [HLS_LABEL]。
     *
     * @return `(url, 质量标签)`；完全没有可用变体时返回 null
     */
    internal fun bestVideoVariant(variants: JSONArray?): Pair<String, String>? {
        if (variants == null) return null
        val list = ArrayList<VariantCandidate>(variants.length())
        for (i in 0 until variants.length()) {
            val v = variants.optJSONObject(i) ?: continue
            list += VariantCandidate(
                type = v.optString("type").ifBlank { v.optString("content_type") },
                src = v.optString("src").ifBlank { v.optString("url") },
                bitRate = v.optLong("bit_rate", 0L)
            )
        }
        return chooseVariant(list)
    }

    /** 一条播放地址候选（把 JSON 解析与选优拆开，方便纯函数单测） */
    internal data class VariantCandidate(val type: String, val src: String, val bitRate: Long = 0L)

    /**
     * 纯选优逻辑：优先 mp4（按 URL 里 `/<宽>x<高>/` 面积最大，面积相同看码率），
     * 没有 mp4 时退回 m3u8（标签 [HLS_LABEL]）。
     */
    internal fun chooseVariant(candidates: List<VariantCandidate>): Pair<String, String>? {
        var bestMp4: Pair<String, String>? = null
        var bestArea = -1L
        var bestRate = -1L
        var hls = ""
        candidates.forEach { v ->
            if (v.src.isBlank()) return@forEach
            if (v.type.contains("mpegurl", true) || v.src.contains(".m3u8", true)) {
                if (hls.isBlank()) hls = v.src
                return@forEach
            }
            if (!v.type.contains("mp4", true) && !v.src.contains(".mp4", true)) return@forEach
            val res = RESOLUTION.find(v.src)
            val area = res?.let { it.groupValues[1].toLong() * it.groupValues[2].toLong() } ?: 0L
            val better = area > bestArea || (area == bestArea && v.bitRate > bestRate)
            if (better) {
                bestArea = area
                bestRate = v.bitRate
                val label = if (res != null) "${res.groupValues[1]}×${res.groupValues[2]}" else bitrateLabel(v.bitRate)
                bestMp4 = v.src to label
            }
        }
        if (bestMp4 != null) return bestMp4
        return if (hls.isNotBlank()) hls to HLS_LABEL else null
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

    /** 从 `.../vid/avc1/720x1280/xxx.mp4` 里抠分辨率 */
    private val RESOLUTION = Regex("/(\\d{2,5})x(\\d{2,5})/")

    private const val HLS_LABEL = "HLS"
}
