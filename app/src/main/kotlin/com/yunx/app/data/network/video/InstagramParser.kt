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
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Locale
import kotlin.random.Random

/**
 * Instagram 解析器（`/p/` 图片帖、`/reel/` 视频帖）。
 *
 * 设计原则（用户明确要求）：
 * 1. **默认匿名**：不登录也能下图片和普通视频 —— 页面本身对访客可见时，媒体地址就在页面里/播放请求里；
 * 2. **登录只用来过登录墙**：命中「登录并继续 / 有年龄限制」时如实提示需要登录，不自动弹登录、不自动重试；
 * 3. **尽量降低封号风险**：只用页面可视数据 + 媒体请求，**不调私有接口**（不学第三方脚本去请求
 *    `i.instagram.com/api/v1/media/.../info/`）；带登录态时先随机等待、并在脚本里对「翻页」加随机间隔；
 *    命中「操作频繁」立即停并提示，绝不重试。
 *
 * 技术要点（2026-11 实测）：
 * - 图片：`img` 的自然宽度 ≥1000 的是帖子大图；多图轮播靠点「下一步/Next」逐张翻，脚本里按随机间隔点。
 * - 视频：`<video src>` 是 `blob:`，抓 DOM 没用；但 `performance.getEntriesByType('resource')` 能拿到
 *   真实的 `.mp4` 请求地址 —— IG 是 **DASH 分轨**（纯视频 + 纯音频两个文件），所以要用 [VideoMedia.audioUrl]
 *   交给下载器合轨（与 B 站同一套 `DashMuxer`）。这些 CDN 地址**不需要 Cookie**，但带 `oe=` 时效，需现取现用。
 * - 命名信息（作者/作者ID/文案/时间）全部来自 `og:title` / `og:description`，匿名即可得。
 */
object InstagramParser : VideoParser {

    override val platform: VideoPlatform = VideoPlatform.INSTAGRAM

    private val mediaHeaders = mapOf(
        "User-Agent" to VideoHttp.UA_CHROME,
        "Referer" to "https://www.instagram.com/"
    )

    override suspend fun parse(link: DetectedVideoLink, cookie: String): VideoParseResult {
        // 带登录态时先随机等待：把"打开页面"的节奏做得像人（匿名则不需要）
        if (cookie.isNotBlank()) delay(900L + Random.nextLong(0, 1_200))

        val json = HeadlessWebView.loadAndExtract(
            url = link.url,
            userAgent = VideoHttp.UA_CHROME,
            readyCheckJs = "window.__yunxIgDone===true",
            extractJs = "window.__yunxIgPayload||''",
            timeoutMs = 35_000L,
            extraDocumentStartJs = listOf(COLLECT_JS)
        ) ?: throw VideoParseException(
            VideoParseErrorKind.UNKNOWN,
            AppStrings.get(R.string.er_ig_no_data)
        )

        val obj = runCatching { JSONObject(json) }.getOrNull() ?: throw VideoParseException(
            VideoParseErrorKind.UNKNOWN,
            AppStrings.get(R.string.er_ig_no_data)
        )
        when (obj.optString("error")) {
            "LOGIN_REQUIRED" -> throw VideoParseException(
                VideoParseErrorKind.UNSUPPORTED,
                AppStrings.get(R.string.er_ig_login_required)
            )
            "RATE_LIMITED" -> throw VideoParseException(
                VideoParseErrorKind.UNSUPPORTED,
                AppStrings.get(R.string.er_ig_rate_limited)
            )
        }

        val ogTitle = obj.optString("ogTitle").takeUnless { it == "null" }.orEmpty()
        val ogDesc = obj.optString("ogDesc").takeUnless { it == "null" }.orEmpty()
        val ogImage = obj.optString("ogImage").takeUnless { it == "null" }.orEmpty()

        val images = obj.optJSONArray("imgs")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty()
        val mp4s = obj.optJSONArray("mp4")?.let { arr ->
            (0 until arr.length()).mapNotNull { arr.optString(it).takeIf(String::isNotBlank) }
        }.orEmpty()

        // DASH 分轨：音频轨（m78 / efg 里带 audio）与视频轨分开
        val audioUrl = mp4s.firstOrNull { isAudioTrack(it) }.orEmpty()
        val videoUrl = mp4s.firstOrNull { !isAudioTrack(it) }.orEmpty()

        val base = "instagram"
        val medias = mutableListOf<VideoMedia>()
        images.forEachIndexed { i, url ->
            medias += VideoMedia(
                url = url,
                kind = VideoMediaKind.IMAGE,
                fileName = "${base}_${i + 1}.jpg",
                headers = mediaHeaders
            )
        }
        if (videoUrl.isNotBlank()) {
            medias += VideoMedia(
                url = videoUrl,
                kind = VideoMediaKind.VIDEO,
                fileName = "${base}_video.mp4",
                headers = mediaHeaders,
                audioUrl = audioUrl,
                audioHeaders = if (audioUrl.isNotBlank()) mediaHeaders else emptyMap()
            )
        }
        // 视频帖没有可用大图时，把封面当一张图（可下载）
        if (medias.isEmpty() && ogImage.isNotBlank()) {
            medias += VideoMedia(
                url = ogImage,
                kind = VideoMediaKind.IMAGE,
                fileName = "${base}_cover.jpg",
                headers = mediaHeaders
            )
        }
        if (medias.isEmpty()) {
            throw VideoParseException(
                VideoParseErrorKind.UNSUPPORTED,
                AppStrings.get(R.string.er_ig_no_media)
            )
        }

        return VideoParseResult(
            platform = platform,
            title = captionOf(ogTitle),
            author = authorOf(ogTitle),
            authorId = authorIdOf(ogDesc),
            cover = ogImage,
            text = captionOf(ogTitle),
            timestampSeconds = timestampOf(ogDesc),
            url = link.url,
            medias = medias
        )
    }

    /** 音频轨判定：IG 的 DASH 音频走 `/m78/`，或 `efg` 里带 `audio`/`heaac` */
    internal fun isAudioTrack(url: String): Boolean =
        url.contains("/m78/") || url.contains("heaac_audio") || url.contains("dash_ln_heaac")

    // ---------------------------------------------------------------- 元数据（纯函数，可单测）

    /** `og:title` 形如 `Instagram 用户 张三 : "文案…"` 或 `Instagram user X : "..."` → 文案 */
    internal fun captionOf(ogTitle: String): String {
        val afterColon = ogTitle.substringAfter("\"", "").substringBeforeLast("\"", "")
        if (afterColon.isNotBlank()) return afterColon.trim()
        val idx = ogTitle.indexOf(':')
        return if (idx >= 0) ogTitle.substring(idx + 1).trim().trim('"') else ogTitle.trim()
    }

    /** 从 `og:title` 取作者昵称：`Instagram 用户 <昵称> : "..."` 里 `<昵称>` 那段 */
    internal fun authorOf(ogTitle: String): String {
        val before = ogTitle.substringBefore("\"", ogTitle).substringBeforeLast(':').trim()
        val name = before.substringAfter("用户 ", before).substringAfter("user ", before)
        return name.trim()
    }

    /** 从 `og:description` 取用户名：`… - <username>，<日期> : "..."` */
    internal fun authorIdOf(ogDesc: String): String =
        Regex("""-\s*([A-Za-z0-9._]+?)\s*[,，]""").find(ogDesc)?.groupValues?.getOrNull(1).orEmpty()

    /** 从 `og:description` 取发布时间（英文 `November 13, 2026` 或中文 `2026年11月13日`），失败返回 0 */
    internal fun timestampOf(ogDesc: String): Long {
        Regex("""([A-Z][a-z]{2,8} \d{1,2}, \d{4})""").find(ogDesc)?.groupValues?.getOrNull(1)?.let { text ->
            val fmt = SimpleDateFormat("MMMM d, yyyy", Locale.ENGLISH)
            runCatching { fmt.parse(text)?.time?.div(1000L) }.getOrNull()?.let { return it }
        }
        Regex("""(\d{4})年(\d{1,2})月(\d{1,2})日""").find(ogDesc)?.let { m ->
            val fmt = SimpleDateFormat("yyyy-M-d", Locale.US)
            runCatching { fmt.parse("${m.groupValues[1]}-${m.groupValues[2]}-${m.groupValues[3]}")?.time?.div(1000L) }
                .getOrNull()?.let { return it }
        }
        return 0L
    }

    // ---------------------------------------------------------------- 页面内收集脚本

    /**
     * 在页面文档开始时就挂上，等页面渲染后：
     * 1. 记录所有 `naturalWidth>=1000` 的大图（轮播会新增）；
     * 2. 有「下一步/Next」就按 0.9–1.6s 随机间隔逐张翻（最多 12 次），把每张都收进来；
     * 3. 从 `performance` 里收 `.mp4` 请求地址（含 DASH 分轨）；
     * 4. 命中登录墙/频繁提示时提前收工并带上错误码。
     */
    private val COLLECT_JS = """
(function(){
  window.__yunxIgPayload=''; window.__yunxIgDone=false;
  var t0=Date.now(), imgs=[], clicks=0;
  function meta(p){var a=[].slice.call(document.querySelectorAll('meta'));for(var i=0;i<a.length;i++){var n=a[i].getAttribute('property')||a[i].getAttribute('name');if(n===p)return a[i].getAttribute('content')||'';}return '';}
  function addImgs(){var a=[].slice.call(document.querySelectorAll('img'));for(var i=0;i<a.length;i++){var im=a[i];if((im.naturalWidth||0)>=1000){var u=im.currentSrc||im.src||'';if(u&&imgs.indexOf(u)<0)imgs.push(u);}}}
  function mp4s(){try{var r=[].slice.call(performance.getEntriesByType('resource')),o=[];for(var i=0;i<r.length;i++){var n=r[i].name||'';if(n.indexOf('.mp4')>=0){var u=n.split('&bytestart=')[0];if(o.indexOf(u)<0)o.push(u);}}}catch(e){return [];}return o;}
  function bodyText(){return (document.body&&document.body.innerText)||'';}
  function finish(err){
    if(window.__yunxIgDone)return; addImgs();
    var vids=[],v=[].slice.call(document.querySelectorAll('video'));
    for(var i=0;i<v.length;i++){vids.push({d:v[i].duration||0,w:v[i].videoWidth||0,h:v[i].videoHeight||0});}
    window.__yunxIgPayload=JSON.stringify({error:err||'',ogTitle:meta('og:title'),ogDesc:meta('og:description'),ogImage:meta('og:image'),imgs:imgs,mp4:mp4s(),vids:vids,url:location.href});
    window.__yunxIgDone=true;
  }
  function step(){
    if(Date.now()-t0>22000){finish('');return;}
    var bt=bodyText();
    if(/登录并继续|Log in and continue|有年龄限制/.test(bt)){finish('LOGIN_REQUIRED');return;}
    if(/Please wait a few minutes|请稍后再试|操作过于频繁/.test(bt)){finish('RATE_LIMITED');return;}
    addImgs();
    if(!meta('og:title')){setTimeout(step,450);return;}
    var next=[].slice.call(document.querySelectorAll('button,[role=button]')),btn=null;
    for(var i=0;i<next.length;i++){var l=next[i].getAttribute('aria-label')||'';if(l==='下一步'||l==='Next'){btn=next[i];break;}}
    if(btn&&clicks<12){clicks++;try{btn.click();}catch(e){} setTimeout(step,900+Math.random()*700);return;}
    setTimeout(function(){finish('');},900);
  }
  function boot(){setTimeout(step,600);}
  if(document.readyState==='loading')document.addEventListener('DOMContentLoaded',boot);else boot();
})();
    """.trimIndent()
}
