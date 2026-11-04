package dev.xiaoman.agent

import dev.xiaoman.api.Action
import dev.xiaoman.api.Point
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class OperatorReplyParserTest {
    private fun ok(text: String): Action = assertIs<ParsedReply.Ok>(OperatorReplyParser.parse(text)).action

    @Test
    fun documentedFormat() {
        val parsed = OperatorReplyParser.parse("""{"thought": "目的地输入框在顶部", "action": {"type": "tap", "nodeId": 12, "target": "目的地输入框", "reason": "先输入目的地"}}""")
        parsed as ParsedReply.Ok
        assertEquals("目的地输入框在顶部", parsed.thought)
        assertEquals(Action.Tap(nodeId = 12, target = "目的地输入框", reason = "先输入目的地"), parsed.action)
    }

    @Test
    fun fencedJsonWithProseAndExtraKeys() {
        val a = ok("好的，下一步：\n```json\n{\"thought\":\"x {not json}\",\"confidence\":0.9,\"action\":{\"type\":\"back\",\"extra\":1}}\n```\n完毕")
        assertEquals(Action.Back(), a)
    }

    @Test
    fun bareActionAndSynonyms() {
        assertEquals(Action.Tap(nodeId = 3), ok("""{"type": "click", "node_id": 3}"""))
        assertEquals(Action.Tap(nodeId = 4), ok("""{"type": "Tap", "id": "4"}"""))
        assertEquals(Action.TypeText("北京南站", nodeId = 2), ok("""{"action": {"type": "input", "text": "北京南站", "nodeId": 2}}"""))
        assertEquals(Action.LaunchApp("com.android.settings"), ok("""{"action": {"type": "open_app", "package": "com.android.settings"}}"""))
        assertEquals(Action.Finish(true, "完成了"), ok("""{"action": {"type": "done", "summary": "完成了"}}"""))
        assertEquals(Action.AskUser("验证码？", takeover = true), ok("""{"action": {"type": "takeover", "question": "验证码？"}}"""))
    }

    @Test
    fun actionAsStringDiscriminator() {
        assertEquals(Action.Tap(nodeId = 7), ok("""{"thought": "t", "action": "tap", "nodeId": 7}"""))
    }

    @Test
    fun pointFormsAreClamped() {
        assertEquals(Action.Tap(point = Point(100, 200)), ok("""{"action": {"type": "tap", "point": [100, 200]}}"""))
        assertEquals(Action.Tap(point = Point(1000, 0)), ok("""{"action": {"type": "tap", "point": {"x": 1200.4, "y": -5}}}"""))
        assertEquals(Action.Tap(point = Point(300, 400)), ok("""{"action": {"type": "tap", "x": 300, "y": 400}}"""))
        assertEquals(Action.Tap(point = Point(10, 20)), ok("""{"action": {"type": "tap", "coordinate": "(10, 20)"}}"""))
        val swipe = assertIs<Action.Swipe>(ok("""{"action": {"type": "swipe", "from": [500, 800], "to": {"x": 500, "y": 2000}}}"""))
        assertEquals(Point(500, 1000), swipe.to)
        assertIs<Action.Swipe>(ok("""{"action": {"type": "scroll", "direction": "up"}}"""))
    }

    @Test
    fun errors() {
        assertIs<ParsedReply.Error>(OperatorReplyParser.parse("我觉得应该点击设置"))
        assertIs<ParsedReply.Error>(OperatorReplyParser.parse("""{"action": {"type": "teleport"}}"""))
        assertIs<ParsedReply.Error>(OperatorReplyParser.parse("""{"action": {"type": "tap"}}"""))
        assertIs<ParsedReply.Error>(OperatorReplyParser.parse("""{"action": {"type": "tap", "nodeId": 1"""))
        val e = assertIs<ParsedReply.Error>(OperatorReplyParser.parse("""{"action": {"type": "type"}}"""))
        assertTrue(e.message.contains("text"))
    }

    @Test
    fun yesWords() {
        listOf("是", "好", "好的", "确认", "yes", "OK", "ok!", "Yes please").forEach { assertTrue(FlowRunner.isYes(it), it) }
        listOf("不", "不要", "不好", "no", "nope", "取消", "", "okra").forEach { assertTrue(!FlowRunner.isYes(it), it) }
    }
}
