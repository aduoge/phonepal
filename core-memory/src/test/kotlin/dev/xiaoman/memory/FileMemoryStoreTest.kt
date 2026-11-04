package dev.xiaoman.memory

import dev.xiaoman.api.Note
import dev.xiaoman.api.Observation
import dev.xiaoman.api.Relation
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FileMemoryStoreTest {
    private val root: File = createTempDirectory("memory-test").toFile()
    private var now = 1_700_000_000_000L
    private val store = FileMemoryStore(root) { now }

    @AfterTest
    fun cleanUp() {
        root.deleteRecursively()
    }

    private fun tick() {
        now += 10_000
    }

    @Test
    fun writeAndReadByPermalinkAndTitle() = runTest {
        val stored = store.write(
            Note(title = "家", permalink = "places/家", type = "place", tags = listOf("地址"),
                observations = listOf(Observation("address", "北京市海淀区某某路 1 号", listOf("常用"))),
                relations = listOf(Relation("lives_in", "北京"))),
        )
        assertEquals(now, stored.updatedAtMillis)
        val file = File(root, "places/家.md")
        assertTrue(file.isFile)
        assertTrue(file.readText(Charsets.UTF_8).contains("- [address] 北京市海淀区某某路 1 号 #常用"))

        assertEquals(stored, store.read("places/家"))
        assertEquals(stored, store.read("places/家.md"))
        assertEquals(stored, store.read("家"))
        assertNull(store.read("nowhere"))
    }

    @Test
    fun titleLookupIsCaseInsensitiveForLatin() = runTest {
        store.write(Note(title = "Home Address", permalink = ""))
        assertEquals("home-address", store.read("home address")?.permalink)
        assertEquals("Home Address", store.read("HOME ADDRESS")?.title)
    }

    @Test
    fun generatesPermalinksFromTitles() = runTest {
        assertEquals("家的地址", store.write(Note(title = "家的地址", permalink = "")).permalink)
        assertEquals("taxi-home-v2", store.write(Note(title = "Taxi: Home  v2!", permalink = "")).permalink)
        // Same slug, different title: does not overwrite.
        assertEquals("taxi-home-v2-2", store.write(Note(title = "taxi home v2", permalink = "")).permalink)
        // Same title again: replaces in place.
        assertEquals("taxi-home-v2", store.write(Note(title = "Taxi: Home  v2!", permalink = "")).permalink)
        assertEquals(3, store.list().size)
    }

    @Test
    fun rejectsPathTraversal() = runTest {
        for (bad in listOf("../escape", "a/../../b", "/etc/passwd", "C:/x", "a//b", "a/./b", "a/", ".hidden/x", "..\\x")) {
            assertFailsWith<IllegalArgumentException>(bad) { store.write(Note(title = "x", permalink = bad)) }
        }
        assertFailsWith<IllegalArgumentException> { store.appendObservation("../x", Observation("a", "b")) }
        assertFalse(File(root.parentFile, "escape.md").exists())
        assertNull(store.read("../escape"))
    }

    @Test
    fun appendObservationCreatesNote() = runTest {
        val note = store.appendObservation("偏好/车型", Observation("car", "快车"))
        assertEquals("车型", note.title)
        assertEquals("note", note.type)
        assertEquals("偏好/车型", note.permalink)
        assertEquals(listOf(Observation("car", "快车")), note.observations)
        assertTrue(note.body.startsWith("# 车型\n"))
    }

    @Test
    fun appendObservationKeepsExistingBodyAndFrontmatter() = runTest {
        File(root, "people").mkdirs()
        File(root, "people/alice.md").writeText(
            "---\ntitle: Alice\ntype: person\naliases: [Ally]\n---\n# Alice\n\nMet at work.\n\n## Observations\n- [role] engineer\n",
        )
        val updated = store.appendObservation("Alice", Observation("likes", "tea", listOf("drink")))
        assertEquals("people/alice", updated.permalink)
        assertEquals(mapOf("aliases" to listOf("Ally")), updated.extraFrontmatter)
        assertEquals(listOf(Observation("role", "engineer"), Observation("likes", "tea", listOf("drink"))), updated.observations)
        assertTrue("Met at work." in updated.body)
        assertEquals(updated, store.read("people/alice"))
    }

    @Test
    fun chineseSearchFindsObservation() = runTest {
        store.write(Note(title = "住址", permalink = "places/home", observations = listOf(Observation("fact", "家 地址 北京市海淀区"))))
        store.write(Note(title = "公司", permalink = "places/work", observations = listOf(Observation("fact", "公司 在 上海"))))
        store.write(Note(title = "日记", permalink = "diary", body = "今天的天气很好，我们的心情也好。"))

        val hits = store.search("家的地址")
        assertEquals("places/home", hits.first().permalink)
        assertEquals("[fact] 家 地址 北京市海淀区", hits.first().snippet)
        assertTrue(hits.none { it.permalink == "diary" }, "weak 的 matches are dropped: $hits")

        assertEquals("places/home", store.search("家").single().permalink)
        assertEquals("places/work", store.search("上海").first().permalink)
        assertEquals(emptyList(), store.search("你好"), "single shared characters alone are not a match")
    }

    @Test
    fun titleMatchesAreBoosted() = runTest {
        store.write(Note(title = "Coffee", permalink = "coffee", observations = listOf(Observation("fact", "brewed daily"))))
        store.write(Note(title = "Morning", permalink = "morning", observations = listOf(Observation("fact", "drink coffee then tea"))))
        val hits = store.search("coffee")
        assertEquals(listOf("coffee", "morning"), hits.map { it.permalink })
        assertTrue(hits[0].score > hits[1].score)
        assertEquals(1, store.search("coffee", limit = 1).size)
    }

    @Test
    fun snippetsAreClippedAroundTheMatch() = runTest {
        val long = "填充".repeat(100) + " 目标词 " + "尾巴".repeat(100)
        store.write(Note(title = "长", permalink = "long", observations = listOf(Observation("x", long))))
        val snippet = store.search("目标词").single().snippet
        assertTrue(snippet.length <= Snippets.MAX_LENGTH + 2, snippet)
        assertTrue("目标词" in snippet, snippet)
    }

    @Test
    fun deleteRemovesFileAndSearchEntries() = runTest {
        store.write(Note(title = "秘密", permalink = "secrets/秘密", observations = listOf(Observation("pin", "银行 密码 提示"))))
        assertEquals(1, store.search("密码").size)
        assertTrue(store.delete("秘密"))
        assertFalse(File(root, "secrets/秘密.md").exists())
        assertFalse(File(root, "secrets").exists(), "empty folder is pruned")
        assertTrue(store.search("密码").isEmpty())
        assertNull(store.read("secrets/秘密"))
        assertFalse(store.delete("secrets/秘密"))
    }

    @Test
    fun listFiltersByFolder() = runTest {
        store.write(Note(title = "a", permalink = "howto/a"))
        store.write(Note(title = "b", permalink = "howto/sub/b"))
        store.write(Note(title = "c", permalink = "howtox/c"))
        store.write(Note(title = "d", permalink = "d"))
        assertEquals(listOf("howto/a", "howto/sub/b"), store.list("howto").map { it.permalink })
        assertEquals(listOf("howto/a", "howto/sub/b"), store.list("/howto/").map { it.permalink })
        assertEquals(4, store.list().size)
        assertEquals(4, store.list("").size)
    }

    @Test
    fun externalChangesAreSeen() = runTest {
        store.write(Note(title = "旧", permalink = "old", observations = listOf(Observation("x", "苹果"))))
        assertTrue(store.search("香蕉").isEmpty())

        // Added by adb / Obsidian.
        File(root, "inbox").mkdirs()
        File(root, "inbox/水果.md").writeText("---\ntitle: 水果\n---\n# 水果\n\n- [fruit] 香蕉 很甜\n")
        assertEquals("inbox/水果", store.search("香蕉").single().permalink)

        // Edited externally (different size and mtime).
        File(root, "old.md").writeText("---\ntitle: 旧\n---\n- [x2] 葡萄 酸\n")
        assertEquals("old", store.search("葡萄").single().permalink)
        assertTrue(store.search("苹果").none { it.permalink == "old" }, "old content is gone")

        // Removed externally; hidden folders are ignored.
        File(root, "inbox/水果.md").delete()
        File(root, ".obsidian").mkdirs()
        File(root, ".obsidian/香蕉.md").writeText("- [fruit] 香蕉\n")
        assertTrue(store.search("香蕉").isEmpty())
        assertEquals(listOf("old"), store.list().map { it.permalink })
    }

    @Test
    fun emptyQueryReturnsRecentNotes() = runTest {
        store.write(Note(title = "one", permalink = "one", observations = listOf(Observation("x", "first"))))
        tick()
        store.write(Note(title = "two", permalink = "two"))
        tick()
        store.write(Note(title = "three", permalink = "three"))
        assertEquals(listOf("three", "two", "one"), store.search("").map { it.permalink })
        assertEquals(listOf("three", "two"), store.search("  ", limit = 2).map { it.permalink })
        assertEquals("[x] first", store.search("").last().snippet)
        assertTrue(store.search("！？").isEmpty())
    }

    @Test
    fun concurrentAppendsAreAllKept() = runTest {
        (1..40).map { i -> async { store.appendObservation("log", Observation("entry", "第${i}条")) } }.awaitAll()
        val note = assertNotNull(store.read("log"))
        assertEquals(40, note.observations.size)
        assertTrue(root.walkTopDown().none { it.name.endsWith(".tmp") }, "no temp files left")
    }

    @Test
    fun writeWithBodyReflectsObservations() = runTest {
        val stored = store.write(
            Note(title = "T", permalink = "t", body = "# T\n\nIntro [[Other]].\n", observations = listOf(Observation("fact", "added"))),
        )
        assertEquals(listOf(Observation("fact", "added")), stored.observations)
        assertEquals(listOf(Relation("links_to", "Other")), stored.relations)
        assertTrue(stored.body.startsWith("# T\n\nIntro [[Other]].\n\n## Observations\n- [fact] added"))
    }
}
