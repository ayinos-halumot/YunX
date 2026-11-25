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

package com.yunx.app.data.network

import com.yunx.app.data.network.video.VideoLinkParser
import com.yunx.app.data.network.video.VideoPlatform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** Instagram 链接识别单测（纯正则，不联网）。 */
class VideoLinkParserInstagramTest {

    @Test
    fun `图片帖 p 链接`() {
        val link = VideoLinkParser.parse("https://www.instagram.com/p/AbCdEf12345/?utm_source=ig_web_copy_link")
        assertEquals(VideoPlatform.INSTAGRAM, link?.platform)
        assertEquals("post", link?.kind)
        assertEquals("AbCdEf12345", link?.id)
    }

    @Test
    fun `视频帖 reel 链接`() {
        val link = VideoLinkParser.parse("https://www.instagram.com/reel/AbCdEf12345/")
        assertEquals(VideoPlatform.INSTAGRAM, link?.platform)
        assertEquals("AbCdEf12345", link?.id)
    }

    @Test
    fun `reels 与 tv 也识别`() {
        assertEquals(VideoPlatform.INSTAGRAM, VideoLinkParser.parse("https://instagram.com/reels/XyZ987654/")?.platform)
        assertEquals(VideoPlatform.INSTAGRAM, VideoLinkParser.parse("https://www.instagram.com/tv/QwE123456/")?.platform)
    }

    @Test
    fun `带语言前缀的链接`() {
        assertEquals(VideoPlatform.INSTAGRAM, VideoLinkParser.parse("https://www.instagram.com/zh-cn/p/AbCdEf12345/")?.platform)
    }

    @Test
    fun `其它平台不受影响`() {
        assertEquals(VideoPlatform.WECHAT_MP, VideoLinkParser.parse("https://mp.weixin.qq.com/s/AbCdEf123456")?.platform)
        assertEquals(VideoPlatform.BILIBILI, VideoLinkParser.parse("https://www.bilibili.com/video/BV1xx411c7mD")?.platform)
        assertNull(VideoLinkParser.parse("https://www.instagram.com/accounts/login/"))
    }
}
