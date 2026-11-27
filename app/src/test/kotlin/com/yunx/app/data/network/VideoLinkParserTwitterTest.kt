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

/** X（Twitter）链接识别单测。 */
class VideoLinkParserTwitterTest {

    @Test
    fun `带用户名的 status 链接`() {
        val l = VideoLinkParser.parse("https://x.com/someone/status/1234567890123456789?s=20")
        assertEquals(VideoPlatform.TWITTER, l?.platform)
        assertEquals("tweet", l?.kind)
        assertEquals("1234567890123456789", l?.id)
    }

    @Test
    fun `twitter_com 旧域名与 i_web 形态`() {
        assertEquals(VideoPlatform.TWITTER, VideoLinkParser.parse("https://twitter.com/a/status/987654321098765432")?.platform)
        assertEquals("987654321098765432", VideoLinkParser.parse("https://x.com/i/web/status/987654321098765432")?.id)
    }

    @Test
    fun `个人主页不算帖子`() {
        assertNull(VideoLinkParser.parse("https://x.com/someone"))
        assertNull(VideoLinkParser.parse("https://x.com/someone/photo"))
    }
}
