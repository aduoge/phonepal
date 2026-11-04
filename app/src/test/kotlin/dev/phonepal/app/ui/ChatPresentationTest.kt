package dev.phonepal.app.ui

import dev.phonepal.app.session.ChatItem
import org.junit.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ChatPresentationTest {
    @Test fun foldingDiagnosticsKeepsQuestionErrorAndFinalAnswerVisible() {
        val original = listOf(ChatItem.User(1, "选本笔记本"), ChatItem.Progress(2, "开始"),
            ChatItem.Progress(3, "1. 搜索", true), ChatItem.Question(4, "选哪种？", false),
            ChatItem.Progress(5, "已确认", true), ChatItem.Error(6, "断网"), ChatItem.Assistant(7, "尚未购买"))
        val rows = chatTimeline(original)
        assertEquals(6, rows.size)
        assertEquals(listOf(2L, 3L), assertIs<TimelineRow.Process>(rows[1]).entries.map { it.id })
        assertIs<ChatItem.Question>(assertIs<TimelineRow.Message>(rows[2]).item)
        assertIs<ChatItem.Error>(assertIs<TimelineRow.Message>(rows[4]).item)
        val restored = rows.flatMap { when (it) {
            is TimelineRow.Message -> listOf(it.item)
            is TimelineRow.Process -> it.entries
        } }
        assertEquals(original, restored)
    }

    @Test fun newProgressKeepsCardIdentityStable() {
        val first = ChatItem.Progress(12, "搜索")
        assertEquals(chatTimeline(listOf(first)).single().id,
            chatTimeline(listOf(first, ChatItem.Progress(13, "进入详情"))).single().id)
        assertTrue(chatTimeline(emptyList()).isEmpty())
    }

    @Test fun boldPriceAndListsRenderWithoutChangingTheirMeaning() {
        val styled = chatRichText("- **合计**：**2.40元**\n尚未付款")
        assertEquals("• 合计：2.40元\n尚未付款", styled.text)
        assertEquals(2, styled.spanStyles.size)
    }

    @Test fun unmatchedMarkersAndLiteralMathStayIntact() {
        assertEquals("2 * 3 = 6，**未结束", chatRichText("2 * 3 = 6，**未结束").text)
    }
}
