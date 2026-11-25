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

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Instagram 元数据解析单测（纯函数）。
 *
 * 输入格式取自 2026-11 实测的 `og:title` / `og:description`（已脱敏成占位名）。
 */
class InstagramParserMetaTest {

    private val ogTitleZh = "Instagram 用户 示例作者 : \"第一行文案\n第二行 #标签\""
    private val ogDescZh = "2,307 likes, 23 comments - sample_user，July 22, 2026 : \"第一行文案\n第二行 #标签\". "

    @Test
    fun `从 og title 提取作者昵称`() {
        assertEquals("示例作者", InstagramParser.authorOf(ogTitleZh))
    }

    @Test
    fun `从 og title 提取文案（多行保留）`() {
        val caption = InstagramParser.captionOf(ogTitleZh)
        assertTrue(caption.startsWith("第一行文案"))
        assertTrue(caption.contains("第二行 #标签"))
    }

    @Test
    fun `从 og description 提取用户名`() {
        assertEquals("sample_user", InstagramParser.authorIdOf(ogDescZh))
    }

    @Test
    fun `英文日期能解析成秒级时间戳`() {
        val ts = InstagramParser.timestampOf(ogDescZh)
        assertTrue("ts=$ts", ts > 1_600_000_000L)
    }

    @Test
    fun `中文日期也能解析`() {
        val ts = InstagramParser.timestampOf("123 likes - someone，2026年11月13日 : \"x\"")
        assertTrue("ts=$ts", ts > 1_600_000_000L)
    }

    @Test
    fun `没有日期时返回 0`() {
        assertEquals(0L, InstagramParser.timestampOf("123 likes - someone : \"x\""))
    }

    @Test
    fun `DASH 音频轨判定`() {
        assertTrue(InstagramParser.isAudioTrack("https://scontent.cdninstagram.com/o1/v/t2/f2/m78/AQ.mp4?efg=eyJkYXNoX2xuX2hlYWFjX2F1ZGlvIjo..."))
        assertFalse(InstagramParser.isAudioTrack("https://scontent.cdninstagram.com/o1/v/t2/f2/m86/AQ.mp4?efg=eyJkYXNoX2Jhc2VsaW5l"))
    }
}
