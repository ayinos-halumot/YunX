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
import org.junit.Assert.assertTrue
import org.junit.Test

/** X 解析里不依赖 JSON 的纯函数单测。 */
class TwitterParserPureTest {

    @Test
    fun `embed token 算法稳定`() {
        // 同一 id 必须每次得到同一个 token，且只含 36 进制字符
        val t1 = TwitterParser.embedToken("1234567890123456789")
        val t2 = TwitterParser.embedToken("1234567890123456789")
        assertEquals(t1, t2)
        assertTrue(t1.isNotEmpty())
        assertTrue(t1.all { it in "0123456789abcdefghijklmnopqrstuvwxyz" })
    }

    @Test
    fun `图片地址统一追加 name=orig`() {
        assertEquals(
            "https://pbs.twimg.com/media/ABC.jpg?name=orig",
            TwitterParser.originalPhoto("https://pbs.twimg.com/media/ABC.jpg")
        )
        assertEquals(
            "https://pbs.twimg.com/media/ABC.jpg?name=orig",
            TwitterParser.originalPhoto("https://pbs.twimg.com/media/ABC.jpg?name=small")
        )
        assertEquals("", TwitterParser.originalPhoto(""))
    }

    @Test
    fun `码率标签`() {
        assertEquals("2.2 Mbps", TwitterParser.bitrateLabel(2176000L))
        assertEquals("", TwitterParser.bitrateLabel(0L))
    }

    @Test
    fun `时间解析`() {
        assertEquals(0L, TwitterParser.parseCreatedAt(""))
        assertTrue(TwitterParser.parseCreatedAt("Wed Oct 10 20:19:24 +0000 2018") > 1_500_000_000L)
    }
}
