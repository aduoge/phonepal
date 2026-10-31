package dev.phonepal.skills

import dev.phonepal.api.SkillChannel
import dev.phonepal.api.SkillProblem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class SkillParserTest {
    private val valid = """
        ---
        name: taxi-home
        description: 打车回家
        apps: [com.autonavi.minimap]
        channel: deeplink-then-gui
        confirm_before: [确认呼叫, 支付]
        memory: [places/家, 偏好/车型]
        deeplinks:
          search: "amapuri://route/plan/?dname={destination}"
        ---
        # 打车回家

        先打开高德。
    """.trimIndent()

    private fun parse(md: String) = SkillParser.parse(md, "/skills/x")

    /** Parses [md] expecting exactly one problem containing [fragment]; returns it. */
    private fun problem(md: String, fragment: String): SkillProblem {
        val (skill, problems) = parse(md)
        assertNull(skill, "skill should be rejected")
        val match = problems.singleOrNull { fragment in it.message } ?: fail("expected one '$fragment' problem in $problems")
        assertEquals("/skills/x/SKILL.md", match.source)
        return match
    }

    private fun frontmatter(vararg lines: String) = "---\n" + lines.joinToString("\n") + "\n---\nbody"

    @Test
    fun parsesValidSkill() {
        val (skill, problems) = parse(valid)
        assertEquals(emptyList(), problems)
        assertNotNull(skill)
        assertEquals("taxi-home", skill.name)
        assertEquals("打车回家", skill.description)
        assertEquals(listOf("com.autonavi.minimap"), skill.apps)
        assertEquals(SkillChannel.DEEPLINK_THEN_GUI, skill.channel)
        assertEquals(listOf("确认呼叫", "支付"), skill.confirmBefore)
        assertEquals(listOf("places/家", "偏好/车型"), skill.memoryRefs)
        assertEquals(mapOf("search" to "amapuri://route/plan/?dname={destination}"), skill.deeplinks)
        assertEquals("# 打车回家\n\n先打开高德。", skill.instructions)
        assertEquals("/skills/x", skill.source)
        assertNull(skill.flow)
    }

    @Test
    fun appliesDefaultsAndAcceptsSingleStrings() {
        val (skill, problems) = parse(frontmatter("name: a", "description: d", "confirm_before: 下单", "memory: places/家"))
        assertEquals(emptyList(), problems)
        assertNotNull(skill)
        assertEquals(SkillChannel.GUI, skill.channel)
        assertEquals(emptyList(), skill.apps)
        assertEquals(listOf("下单"), skill.confirmBefore)
        assertEquals(listOf("places/家"), skill.memoryRefs)
    }

    @Test
    fun acceptsBom() {
        assertNotNull(parse("\uFEFF" + valid).first)
    }

    @Test
    fun missingFrontmatter() {
        assertEquals(1, problem("# just markdown", "missing frontmatter").line)
        problem("---\nname: a\ndescription: d\n", "missing frontmatter")
    }

    @Test
    fun invalidYamlReportsLine() {
        val p = problem(frontmatter("name: a", "description: [unclosed"), "invalid YAML")
        assertNotNull(p.line)
        assertTrue(p.line!! >= 3, "line ${p.line}")
    }

    @Test
    fun nameRequired() {
        problem(frontmatter("description: d"), "missing required key 'name'")
    }

    @Test
    fun nameFormatWithLine() {
        val p = problem(frontmatter("description: d", "name: Taxi_Home"), "must match [a-z0-9-]+")
        assertEquals(3, p.line)
    }

    @Test
    fun descriptionRequired() {
        problem(frontmatter("name: a"), "missing required key 'description'")
    }

    @Test
    fun descriptionSingleLine() {
        val p = problem(frontmatter("name: a", "description: |", "  第一行", "  第二行"), "single line")
        assertEquals(3, p.line)
    }

    @Test
    fun appsMustBePackageNames() {
        val p = problem(frontmatter("name: a", "description: d", "apps: [com.ok.app, 高德]"), "'高德' is not a package name")
        assertEquals(4, p.line)
        problem(frontmatter("name: a", "description: d", "apps: {a: b}"), "must be a list of strings")
    }

    @Test
    fun unknownChannel() {
        problem(frontmatter("name: a", "description: d", "channel: telepathy"), "unknown channel 'telepathy'")
    }

    @Test
    fun confirmBeforeAndMemoryMustBeStrings() {
        problem(frontmatter("name: a", "description: d", "confirm_before: [[支付]]"), "'confirm_before' items must be strings")
        problem(frontmatter("name: a", "description: d", "memory: {a: b}"), "'memory' must be a list of strings")
    }

    @Test
    fun deeplinksMustBeUris() {
        val p = problem(frontmatter("name: a", "description: d", "deeplinks:", "  ok: \"a://b\"", "  bad: not-a-uri"), "'bad'")
        assertEquals(6, p.line)
        problem(frontmatter("name: a", "description: d", "deeplinks: [a://b]"), "'deeplinks' must be a mapping")
    }

    @Test
    fun unknownAndDuplicateKeys() {
        problem(frontmatter("name: a", "description: d", "confirm-before: [支付]"), "unknown key 'confirm-before'")
        problem(frontmatter("name: a", "name: b", "description: d"), "duplicate key 'name'")
    }

    @Test
    fun folderNameMismatchIsReportedButLoads() {
        val (skill, problems) = SkillParser.parseFolder("other", valid, null, "/skills/other")
        assertNotNull(skill)
        val p = problems.single()
        assertTrue("does not match folder name" in p.message)
        assertEquals(2, p.line)
    }

    @Test
    fun parseFolderAttachesFlow() {
        val flow = "steps:\n  - deeplink: search\n  - tap: 确认呼叫\n"
        val (skill, problems) = SkillParser.parseFolder("taxi-home", valid, flow, "/skills/taxi-home")
        assertEquals(emptyList(), problems)
        assertEquals(2, skill?.flow?.steps?.size)
    }

    @Test
    fun badFlowRejectsSkill() {
        val (skill, problems) = SkillParser.parseFolder("taxi-home", valid, "steps:\n  - fly: away\n", "/skills/taxi-home")
        assertNull(skill)
        assertEquals("/skills/taxi-home/flow.yaml", problems.single().source)
    }
}
