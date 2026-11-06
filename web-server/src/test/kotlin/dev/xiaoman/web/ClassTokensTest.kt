package dev.xiaoman.web

import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong
import kotlin.test.*

class ClassTokensTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val now = AtomicLong(1_762_400_000_000) // 2025-11-06 11:33 +08:00
    private fun tempFile(): Path = Files.createTempDirectory("class-tokens").resolve("class-tokens.json")

    private class Run(val code: Int, val out: String, val err: String)
    private fun admin(file: Path, vararg args: String): Run {
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        val code = ClassTokenAdmin.run(args.toList() + listOf("--file", file.toString()), PrintStream(out, true, "UTF-8"),
            PrintStream(err, true, "UTF-8"), env = emptyMap(), clock = now::get)
        return Run(code, out.toString(Charsets.UTF_8), err.toString(Charsets.UTF_8))
    }
    private fun generated(run: Run) = run.out.lines().filter { it.isNotBlank() }.map { it.substringBefore('\t') to it.substringAfter('\t') }

    @Test fun generateStoresOnlyHashesAndPrintsPlaintextOnce() {
        val file = tempFile()
        val run = admin(file, "generate", "3", "--daily", "1000", "--total", "5000")
        assertEquals(0, run.code, run.err)
        val tokens = generated(run)
        assertEquals(listOf("学员 01", "学员 02", "学员 03"), tokens.map { it.first })
        tokens.forEach { (_, plain) -> assertTrue(plain.matches(Regex("xm-[2-9a-km-np-z]{20}")), plain) }
        assertEquals(3, tokens.map { it.second }.toSet().size)
        val text = Files.readString(file)
        tokens.forEach { (_, plain) ->
            assertFalse(plain in text, "plaintext must not be stored")
            assertTrue(ClassTokenCodec.hash(plain) in text)
        }
        val store = ClassTokenStore(file, zone, now::get, 0)
        val first = assertNotNull(store.find(tokens[0].second))
        assertEquals("学员 01", first.label); assertEquals(tokens[0].second.take(6), first.prefix)
        assertEquals(1000, first.dailyLimit); assertEquals(5000, first.totalLimit)
        assertEquals(now.get() + 3 * 86_400_000L, first.expiresAt)
        assertNull(store.find("xm-" + "a".repeat(20)))
        // Numbering continues after existing labels; the file mode is 0640.
        assertEquals(listOf("学员 04", "学员 05"), generated(admin(file, "generate", "2")).map { it.first })
        assertEquals("rw-r-----", java.nio.file.attribute.PosixFilePermissions.toString(Files.getPosixFilePermissions(file)))
        assertTrue("学员 05" in admin(file, "list").out)
    }

    @Test fun csvOutputCarriesWhatStudentsNeed() {
        val file = tempFile()
        val run = admin(file, "generate", "2", "--csv", "--label", "A班", "--base-url", "https://x.example/v1", "--model", "xiaoman-class")
        val lines = run.out.removePrefix("﻿").trim().lines()
        assertEquals("标签,令牌,过期时间,每日上限,总上限,Base URL,模型名", lines[0])
        assertEquals(3, lines.size)
        assertTrue(lines[1].startsWith("A班 01,xm-")); assertTrue(lines[1].endsWith(",https://x.example/v1,xiaoman-class"))
    }

    @Test fun revokeExpiryAndQuotaAreEnforced() {
        val file = tempFile()
        val tokens = generated(admin(file, "generate", "3", "--daily", "100", "--total", "150", "--hours", "2"))
        val store = ClassTokenStore(file, zone, now::get, 0)
        fun code(i: Int) = store.check(store.find(tokens[i].second)!!)?.code
        assertNull(code(0))
        assertEquals(0, admin(file, "revoke", "学员01").code) // label without the space also matches
        assertEquals("token_revoked", code(0))
        assertEquals(0, admin(file, "restore", tokens[0].second).code) // full token works as selector
        assertNull(code(0))

        store.record(store.find(tokens[1].second)!!.hash, 100)
        assertEquals("daily_quota_exceeded", code(1))
        now.addAndGet(36 * 3_600_000L) // a day and a half later: past the 2-hour expiry, and a new day
        assertEquals("token_expired", code(1))
        assertEquals(0, admin(file, "limit", "学员 02", "--days", "5").code)
        assertNull(code(1), "a new day resets the daily usage")
        store.record(store.find(tokens[1].second)!!.hash, 60)
        assertEquals("total_quota_exceeded", code(1))
        assertEquals(0, admin(file, "limit", "--all", "--total", "none", "--daily", "none").code)
        assertNull(code(1))

        assertEquals(0, admin(file, "revoke-all").code)
        assertTrue(tokens.indices.all { code(it) == "token_revoked" })
        assertEquals(2, admin(file, "revoke", "不存在").code)
        assertEquals(2, admin(file, "generate", "0").code)
    }

    @Test fun usageFlushMergesWithAdminChangesAndSurvivesRestart() {
        val file = tempFile()
        val plain = generated(admin(file, "generate", "1", "--daily", "1000")).single().second
        val server = ClassTokenStore(file, zone, now::get, 0)
        val hash = server.find(plain)!!.hash
        server.record(hash, 40); server.record(hash, 2)
        assertEquals(42, server.find(plain)!!.usedTotal, "pending usage counts before it is written")
        // The admin changes the limit while usage is pending; neither change may be lost.
        assertEquals(0, admin(file, "limit", "学员 01", "--daily", "77").code)
        server.flush()
        val reread = ClassTokenStore(file, zone, now::get, 0).find(plain)!!
        assertEquals(77, reread.dailyLimit); assertEquals(42, reread.usedTotal); assertEquals(42, reread.usedToday); assertEquals(2, reread.requests)
        assertEquals(42, server.find(plain)!!.usedTotal, "no double counting after the flush")
        assertTrue("42/77" in admin(file, "list").out)
    }

    @Test fun missingFileMeansOff() {
        val store = ClassTokenStore(tempFile(), zone, now::get, 0)
        assertFalse(store.exists()); assertNull(store.find("xm-anything"))
    }
}
