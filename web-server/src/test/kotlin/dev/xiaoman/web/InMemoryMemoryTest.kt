package dev.xiaoman.web

import dev.xiaoman.api.*
import kotlinx.coroutines.runBlocking
import kotlin.test.*

class InMemoryMemoryTest {
    @Test fun searchesTitleBodyAndObservationsIncludingChineseOverlap() = runBlocking {
        val m = InMemoryMemory { 123L }
        m.write(Note("家", "places/home", body = "Beijing 海淀", observations = listOf(Observation("地址", "中关村"))))
        m.write(Note("工作", "places/work", body = "上海"))
        assertEquals("places/home", m.search("BEIJING").single().permalink)
        assertEquals("家", m.search("中关村").first().title)
        assertEquals("家", m.search("海淀地址").first().title)
        assertEquals("家", m.search("家").first().title)
        assertTrue(m.search("").isEmpty())
        assertTrue(m.search("家", 0).isEmpty())
        assertEquals(123L, m.read("家")!!.updatedAtMillis)
    }
    @Test fun supportsReplacementAppendReadDeleteAndFolders() = runBlocking {
        val m = InMemoryMemory { 100L }
        m.write(Note("家", "places/home", body = "旧"))
        m.write(Note("家", "places/home", body = "新"))
        m.appendObservation("家", Observation("地址", "北京"))
        assertEquals("北京", m.read("places/home")!!.observations.single().content)
        m.appendObservation("朋友", Observation("姓名", "小王"))
        assertEquals(1, m.list("places").size)
        assertEquals(2, m.list().size)
        assertTrue(m.delete("家")); assertFalse(m.delete("家"))
        m.clear(); assertTrue(m.list().isEmpty())
    }
}
