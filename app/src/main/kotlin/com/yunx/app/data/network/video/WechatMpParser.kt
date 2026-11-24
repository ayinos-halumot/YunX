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
import com.yunx.app.data.download.DownloadPathPolicy
import com.yunx.app.util.AppStrings
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 微信：**公众号文章**与**媒体直链**。
 *
 * 三条入口（由 [VideoLinkParser] 的 kind 区分）：
 * 1. `article` —— `mp.weixin.qq.com/s/...` 文章页：抓 HTML，取正文图片（`data-src`）、封面（`msg_cdn_url`）与
 *    文章内嵌视频；图片一律升级成 `/0` 原图（**公众号图片本身没有水印**，`/640` 只是缩略规格）。
 * 2. `direct` —— `finder.video.qq.com/.../stodownload?...&sign=...`（视频号签名直链）、`mpvideo.qpic.cn`、
 *    `mmbiz.qpic.cn`：**不需要任何登录态**，带 UA/Referer 直接入队下载（2026-11 实测：不带 Cookie 也 200）。
 * 3. `sph` —— `weixin.qq.com/sph/...` 视频号分享链接：**拿不到视频**。
 *    预览页（`channels.weixin.qq.com/finder-preview/pages/sph`）只对外给封面图，视频直链要靠内部接口 +
 *    `sign` 签名；实测「扫码登录视频号助手」后再调预览接口，返回里仍然只有封面（无 `20302`、无 `sign`、
 *    无 `decodeKey`）。所以这里如实报错，让用户改用直链，而不是假装解析成功。
 */
object WechatMpParser : VideoParser {

    override val platform: VideoPlatform = VideoPlatform.WECHAT_MP

    /** 微信对非浏览器 UA 更严格；移动 Chrome UA 实测能直接拿到文章正文 */
    private const val UA =
        "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"

    private val articleHeaders = mapOf(
        "User-Agent" to UA,
        "Accept-Language" to "zh-CN,zh;q=0.9",
        "Referer" to "https://mp.weixin.qq.com/"
    )

    /** 图片/视频直链要带的头：公众号图床与视频号 CDN 都按 Referer 判来源 */
    private val mpMediaHeaders = mapOf("User-Agent" to UA, "Referer" to "https://mp.weixin.qq.com/")
    private val finderMediaHeaders = mapOf("User-Agent" to UA, "Referer" to "https://channels.weixin.qq.com/")

    override suspend fun parse(link: DetectedVideoLink, cookie: String): VideoParseResult = when (link.kind) {
        "article" -> parseArticle(link)
        "direct" -> parseDirect(link)
        else -> throw VideoParseException(
            VideoParseErrorKind.UNSUPPORTED,
            AppStrings.get(R.string.er_wechat_sph_unsupported)
        )
    }

    // ---------------------------------------------------------------- 公众号文章

    private suspend fun parseArticle(link: DetectedVideoLink): VideoParseResult {
        val html = VideoHttp.getText(link.url, articleHeaders)

        val title = firstGroup(html, TITLE_JS).ifBlank { firstGroup(html, TITLE_H1) }.trim()
        val author = firstGroup(html, AUTHOR_JS).ifBlank { firstGroup(html, AUTHOR_DOM) }.trim()
        val cover = firstGroup(html, COVER_JS).ifBlank { firstGroup(html, COVER_OG) }
        val timestamp = firstGroup(html, CTIME_JS).toLongOrNull() ?: 0L

        val base = DownloadPathPolicy.sanitizeName(title).take(40)
            .ifBlank { "wechat_${link.id}" }

        val medias = mutableListOf<VideoMedia>()

        // ① 正文图片：微信的正文里 img 是懒加载，真地址在 data-src；统一升级成 /0（原图）
        val content = html.substringAfter("id=\"js_content\"", html)
        val imageUrls = LinkedHashSet<String>()
        IMG_DATA_SRC.findAll(content).forEach { imageUrls += it.groupValues[1] }
        if (imageUrls.isEmpty()) {
            // 少数排版直接把地址写在 src 上
            IMG_SRC.findAll(content).forEach { imageUrls += it.groupValues[1] }
        }
        var index = 0
        for (raw in imageUrls) {
            val url = originalImage(raw)
            if (!url.startsWith("http")) continue
            index += 1
            medias += VideoMedia(
                url = url,
                kind = VideoMediaKind.IMAGE,
                fileName = "${base}_$index.${imageExtension(url)}",
                headers = mpMediaHeaders,
                quality = AppStrings.get(R.string.em_quality_original)
            )
        }

        // ② 文章内嵌视频（有的排版是 <video src>，有的是 mpvideo 直链）
        val videoUrls = LinkedHashSet<String>()
        VIDEO_TAG.findAll(html).forEach { videoUrls += it.groupValues[1] }
        MPVIDEO.findAll(html).forEach { videoUrls += it.groupValues[1] }
        var videoIndex = 0
        for (raw in videoUrls) {
            val url = raw.replace("&amp;", "&")
            if (!url.startsWith("http")) continue
            videoIndex += 1
            medias += VideoMedia(
                url = url,
                kind = VideoMediaKind.VIDEO,
                fileName = "${base}_video_$videoIndex.mp4",
                headers = mpMediaHeaders
            )
        }

        if (medias.isEmpty()) {
            throw VideoParseException(
                VideoParseErrorKind.NOT_FOUND,
                AppStrings.get(R.string.er_wechat_no_media)
            )
        }

        return VideoParseResult(
            platform = platform,
            title = title,
            author = author,
            avatar = "",
            cover = cover,
            text = "",
            timestampSeconds = timestamp,
            url = link.url,
            medias = medias
        )
    }

    // ---------------------------------------------------------------- 直链

    private fun parseDirect(link: DetectedVideoLink): VideoParseResult {
        val url = link.url.replace("&amp;", "&")
        val isImage = url.contains("mmbiz.qpic.cn")
        val stamp = SimpleDateFormat("yyyyMMdd-HHmmss", Locale.US).format(Date())
        val headers = if (url.contains("finder.video.qq.com")) finderMediaHeaders else mpMediaHeaders

        val media = if (isImage) {
            VideoMedia(
                url = url,
                kind = VideoMediaKind.IMAGE,
                fileName = "wx_$stamp.${imageExtension(url)}",
                headers = headers,
                quality = AppStrings.get(R.string.em_quality_original)
            )
        } else {
            VideoMedia(
                url = url,
                kind = VideoMediaKind.VIDEO,
                fileName = "wx_$stamp.mp4",
                headers = headers
            )
        }

        return VideoParseResult(
            platform = platform,
            title = if (isImage) "微信图片直链" else "微信视频直链",
            url = url,
            medias = listOf(media)
        )
    }

    // ---------------------------------------------------------------- 工具

    private fun firstGroup(text: String, regex: Regex): String =
        regex.find(text)?.groupValues?.getOrNull(1)?.replace("&amp;", "&")?.replace("&quot;", "\"") ?: ""

    /**
     * 缩略规格 → 原图：`.../640?wx_fmt=png` → `.../0?wx_fmt=png`。
     *
     * ★ 只改「纯数字规格段」，不动 `sz_mmbiz`/`mmbiz` 这类路径前缀；`wx_fmt` 参数原样保留
     * （它决定返回格式，缺了会 404）。
     */
    private fun originalImage(url: String): String =
        url.replace("&amp;", "&").replace(Regex("""/(?:640|750|1080)(\?|$)"""), "/0$1")

    /** 扩展名按 `wx_fmt` 判断；拿不到就按 jpg（微信自己也常把 jpeg 参数配 PNG/WebP 返回，见 §3.49 备注） */
    private fun imageExtension(url: String): String {
        val fmt = Regex("""[?&]wx_fmt=([A-Za-z0-9]+)""").find(url)?.groupValues?.getOrNull(1)?.lowercase()
        return when (fmt) {
            "png" -> "png"
            "gif" -> "gif"
            "webp" -> "webp"
            else -> "jpg"
        }
    }

    private val TITLE_JS = Regex("""var\s+msg_title\s*=\s*['"]([^'"]*)""")
    private val TITLE_H1 = Regex("""class="js_title_inner"[^>]*>([\s\S]{0,200}?)</""")
    private val AUTHOR_JS = Regex("""var\s+nickname\s*=\s*['"]([^'"]*)""")
    private val AUTHOR_DOM = Regex("""id="js_name"[^>]*>([\s\S]{0,80}?)</""")
    private val COVER_JS = Regex("""var\s+msg_cdn_url\s*=\s*['"]([^'"]+)""")
    private val COVER_OG = Regex("""property="og:image"\s+content="([^"]+)"""")
    private val CTIME_JS = Regex("""var\s+ct\s*=\s*['"]?(\d{9,13})""")
    private val IMG_DATA_SRC = Regex("""data-src="([^"]+)"""")
    private val IMG_SRC = Regex("""<img[^>]+src="(https://mmbiz\.qpic\.cn[^"]+)"""")
    private val VIDEO_TAG = Regex("""<video[^>]+src="([^"]+)"""")
    private val MPVIDEO = Regex("""(https?://mpvideo\.qpic\.cn/[^"'\\\s]+)""")
}
