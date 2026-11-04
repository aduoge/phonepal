package dev.xiaoman.memory

import dev.xiaoman.api.Note
import dev.xiaoman.api.Observation
import dev.xiaoman.api.Relation
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BasicMemoryFormatTest {
    private val example = """
        ---
        title: 家
        type: place
        permalink: places/家
        tags: [地址]
        ---
        # 家

        ## Observations
        - [address] 北京市海淀区某某路 1 号 #常用 (用户 2026-10-30 告知)

        ## Relations
        - lives_in [[北京]]
    """.trimIndent() + "\n"

    @Test
    fun parsesDocumentedExample() {
        val note = BasicMemoryFormat.parse(example, "places/家", 42)
        assertEquals("家", note.title)
        assertEquals("place", note.type)
        assertEquals("places/家", note.permalink)
        assertEquals(listOf("地址"), note.tags)
        assertEquals(emptyMap(), note.extraFrontmatter)
        assertEquals(42, note.updatedAtMillis)
        assertEquals(listOf(Observation("address", "北京市海淀区某某路 1 号", listOf("常用"), "用户 2026-10-30 告知")), note.observations)
        assertEquals(listOf(Relation("lives_in", "北京")), note.relations)
        assertTrue(note.body.startsWith("# 家\n"))
    }

    @Test
    fun roundTripsGeneratedNoteWithCjk() {
        val note = Note(
            title = "偏好/车型".substringAfterLast('/'),
            permalink = "偏好/车型",
            type = "preference",
            tags = listOf("打车", "taxi"),
            observations = listOf(
                Observation("car", "优先 快车，其次 专车", listOf("常用", "taxi")),
                Observation("note", "不要 拼车", context = "用户 说过 (两次)"),
            ),
            relations = listOf(Relation("used_by", "taxi-home", "skill")),
        )
        val text = BasicMemoryFormat.render(note)
        val parsed = BasicMemoryFormat.parse(text, note.permalink, 7)
        assertEquals(note.title, parsed.title)
        assertEquals(note.type, parsed.type)
        assertEquals(note.tags, parsed.tags)
        assertEquals(note.observations, parsed.observations)
        assertEquals(note.relations, parsed.relations)
        assertTrue("## Observations\n- [car] 优先 快车，其次 专车 #常用 #taxi\n" in parsed.body, parsed.body)
        assertTrue("- [note] 不要 拼车 (用户 说过 (两次))" in parsed.body, parsed.body)
        assertTrue("## Relations\n- used_by [[taxi-home]] (skill)\n" in parsed.body, parsed.body)
        // Rendering the parsed note again is stable.
        assertEquals(text, BasicMemoryFormat.render(parsed))
    }

    @Test
    fun preservesExtraFrontmatter() {
        val text = """
            ---
            title: Alice
            type: person
            permalink: people/alice
            tags: friend, work
            created: 2026-10-30
            aliases: [Ally, 爱丽丝]
            meta:
              source: obsidian
              rank: 3
            ---
            Free text mentioning [[Bob|her colleague]] and [[Carol#Work]].
        """.trimIndent() + "\n"
        val note = BasicMemoryFormat.parse(text, "people/alice", 0)
        assertEquals(listOf("friend", "work"), note.tags)
        assertEquals(setOf("created", "aliases", "meta"), note.extraFrontmatter.keys)
        assertEquals(listOf("Ally", "爱丽丝"), note.extraFrontmatter["aliases"])
        assertEquals(mapOf("source" to "obsidian", "rank" to 3), note.extraFrontmatter["meta"])
        assertEquals(listOf(Relation("links_to", "Bob"), Relation("links_to", "Carol")), note.relations)

        val reparsed = BasicMemoryFormat.parse(BasicMemoryFormat.render(note), "people/alice", 0)
        assertEquals(note.extraFrontmatter, reparsed.extraFrontmatter)
        assertEquals(note.body, reparsed.body)
        assertEquals(note.relations, reparsed.relations)
    }

    @Test
    fun ignoresCheckboxesLinksAndCode() {
        val body = """
            # T
            - [ ] todo item
            - [x] done item
            - [docs](https://example.com)
            ```
            - [fake] inside code [[Nope]]
            ```
            - [fact] real one
            Inline `[[NotALink]]` text.
        """.trimIndent()
        val (observations, relations) = BasicMemoryFormat.parseBody(body)
        assertEquals(listOf(Observation("fact", "real one")), observations)
        assertEquals(emptyList(), relations)
    }

    @Test
    fun givenBodyGetsMissingObservationsAndRelations() {
        val body = "# 家\n\n前言文字。\n\n## Observations\n- [address] 旧地址\n\n## Notes\n其它\n"
        val note = Note(
            title = "家",
            permalink = "家",
            body = body,
            observations = listOf(Observation("address", "旧地址"), Observation("phone", "123", listOf("常用"))),
            relations = listOf(Relation("lives_in", "北京")),
        )
        val parsed = BasicMemoryFormat.parse(BasicMemoryFormat.render(note), "家", 0)
        assertEquals(note.observations, parsed.observations)
        assertEquals(note.relations, parsed.relations)
        assertTrue("## Observations\n- [address] 旧地址\n- [phone] 123 #常用\n\n## Notes" in parsed.body, parsed.body)
        assertTrue(parsed.body.trimEnd().endsWith("## Relations\n- lives_in [[北京]]"), parsed.body)
        assertTrue(parsed.body.startsWith("# 家\n\n前言文字。"))
    }

    @Test
    fun observationsSectionCreatedBeforeRelations() {
        val note = Note(
            title = "X",
            permalink = "x",
            body = "# X\n\n## Relations\n- knows [[Y]]\n",
            observations = listOf(Observation("fact", "new")),
        )
        val body = BasicMemoryFormat.parse(BasicMemoryFormat.render(note), "x", 0).body
        assertEquals("# X\n\n## Observations\n- [fact] new\n\n## Relations\n- knows [[Y]]\n", body)
    }

    @Test
    fun fallsBackToHeadingAndPermalinkForTitle() {
        assertEquals("Heading", BasicMemoryFormat.parse("# Heading\ntext\n", "a/b", 0).title)
        val bare = BasicMemoryFormat.parse("just text\n", "a/b", 0)
        assertEquals("b", bare.title)
        assertEquals("note", bare.type)
        assertEquals("just text\n", bare.body)
    }

    @Test
    fun parsesObservationParts() {
        val observation = BasicMemoryFormat.parseObservation("- [tech] Uses f(x) for #math and #ml (from paper)")!!
        assertEquals(Observation("tech", "Uses f(x) for and", listOf("math", "ml"), "from paper"), observation)
        assertNull(BasicMemoryFormat.parseObservation("plain line"))
        assertEquals(Relation("depends on", "库", "v2"), BasicMemoryFormat.parseRelation("- depends on [[库]] (v2)"))
    }
}
