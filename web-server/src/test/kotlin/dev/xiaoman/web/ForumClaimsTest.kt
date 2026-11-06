package dev.xiaoman.web

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.*
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.PrintStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.URL
import java.net.URLDecoder
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.time.ZoneId
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.*

class ForumClaimsTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val upstreamKey = "ark-instructor-secret-claims"
    private val start = Instant.parse("2026-11-06T06:00:00Z").toEpochMilli()
    @Volatile private var now = start
    private val clock = { now }
    private val expires = ClaimConfig.parseTime("2026-11-12 21:00", zone)!!

    /** A Discourse-shaped forum: `/u/{name}.json`, `/t/{id}.json` and `/t/{id}/posts.json?post_ids[]=…`. */
    private inner class FakeForum : Closeable {
        inner class User(val id: Long, val username: String, val createdAt: Long, val trust: Int)
        val users = mutableMapOf<String, User>()
        val posts = CopyOnWriteArrayList<JsonObject>()
        val requests = AtomicInteger()
        val postRequests = AtomicInteger()
        @Volatile var failing = false
        private var nextPost = 1000L
        private val executor = Executors.newCachedThreadPool()
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            executor = this@FakeForum.executor
            createContext("/") { x -> handle(x) }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"

        fun user(id: Long, username: String, createdAt: Long = Instant.parse("2025-01-01T00:00:00Z").toEpochMilli(), trust: Int = 1) =
            User(id, username, createdAt, trust).also { users[username.lowercase()] = it }
        fun reply(user: User, raw: String, cooked: String = "<p>${raw.replace("&", "&amp;").replace("<", "&lt;")}</p>", at: Long = now, type: Int = 1, hidden: Boolean = false) {
            posts += buildJsonObject {
                put("id", nextPost++); put("post_number", posts.size + 1); put("user_id", user.id); put("username", user.username)
                put("raw", raw); put("cooked", cooked); put("post_type", type); put("hidden", hidden); put("deleted_at", JsonNull)
                put("created_at", Instant.ofEpochMilli(at).toString()); put("updated_at", Instant.ofEpochMilli(at).toString()); put("trust_level", user.trust)
            }
        }
        /** Filler replies by an unrelated account, older than any phrase. */
        fun filler(n: Int, at: Long = start - 86_400_000) { val u = users["filler"] ?: user(9999, "filler"); repeat(n) { reply(u, "路过 $it", at = at) } }

        private fun handle(x: HttpExchange) {
            requests.incrementAndGet()
            fun send(code: Int, body: String) {
                val bytes = body.toByteArray(); x.responseHeaders.set("Content-Type", "application/json"); x.sendResponseHeaders(code, bytes.size.toLong()); x.responseBody.write(bytes); x.close()
            }
            if (failing) return send(500, """{"errors":["boom"]}""")
            val path = URLDecoder.decode(x.requestURI.rawPath, "UTF-8")
            val query = x.requestURI.rawQuery.orEmpty().split('&').filter { it.isNotEmpty() }.map { URLDecoder.decode(it.substringBefore('='), "UTF-8") to URLDecoder.decode(it.substringAfter('='), "UTF-8") }
            when {
                path.startsWith("/u/") && path.endsWith(".json") -> {
                    val u = users[path.removePrefix("/u/").removeSuffix(".json").lowercase()] ?: return send(404, """{"errors":["not found"]}""")
                    send(200, buildJsonObject { put("user_badges", JsonArray(emptyList())); put("user", buildJsonObject {
                        put("id", u.id); put("username", u.username); put("name", ""); put("created_at", Instant.ofEpochMilli(u.createdAt).toString())
                        put("trust_level", u.trust); put("suspended_till", JsonNull)
                    }) }.toString())
                }
                path == "/t/42.json" -> send(200, buildJsonObject {
                    put("id", 42); put("posts_count", posts.size); put("chunk_size", 20)
                    put("post_stream", buildJsonObject {
                        put("posts", JsonArray(posts.take(20)))
                        put("stream", JsonArray(posts.map { it["id"]!! }))
                    })
                }.toString())
                path == "/t/42/posts.json" -> {
                    postRequests.incrementAndGet()
                    assertEquals("true", query.firstOrNull { it.first == "include_raw" }?.second)
                    val ids = query.filter { it.first == "post_ids[]" }.map { it.second.toLong() }.toSet()
                    send(200, buildJsonObject { put("id", 42); put("post_stream", buildJsonObject {
                        put("posts", JsonArray(posts.filter { it["id"]!!.jsonPrimitive.long in ids }))
                    }) }.toString())
                }
                else -> send(404, """{"errors":["not found"]}""")
            }
        }
        override fun close() { server.stop(0); executor.shutdownNow() }
    }

    /** OpenAI-compatible upstream for checking that a claimed token works on /v1. */
    private class FakeUpstream : Closeable {
        val server: HttpServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/api/v3/chat/completions") { x ->
                x.requestBody.readBytes()
                val bytes = """{"id":"c1","object":"chat.completion","choices":[{"index":0,"message":{"role":"assistant","content":"你好"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":5,"total_tokens":15}}""".toByteArray()
                x.responseHeaders.set("Content-Type", "application/json"); x.sendResponseHeaders(200, bytes.size.toLong()); x.responseBody.write(bytes); x.close()
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}/api/v3"
        override fun close() = server.stop(0)
    }

    private val dir: Path = Files.createTempDirectory("claims")
    private val file: Path = dir.resolve("class-tokens.json")
    private val logs = CopyOnWriteArrayList<String>()

    private fun claimConfig(forum: String, max: Int = 50, before: Long? = null, trust: Int = 0, perIp: Int = 5) =
        ClaimConfig(42, expires, max, forumUrl = forum, createdBefore = before, minTrust = trust, perIpStartsPerMinute = perIp, forumTimeoutSeconds = 3)
    private fun server(claim: ClaimConfig, upstream: String = "http://127.0.0.1:9/api/v3", accessCode: String? = null) = WebServer(WebServer.Options(
        port = 0, allowPrivateMcp = true, accessCode = accessCode, claimConfig = claim,
        classConfig = ClassConfig(upstreamKey, linkedMapOf("xiaoman-class" to "doubao-real"), upstream, file, flushMillis = 60_000, reloadMillis = 0),
    ), clock = clock, logger = logs::add).start()

    private data class Reply(val code: Int, val text: String, val headers: Map<String?, List<String>>) {
        val json get() = Json.parseToJsonElement(text).jsonObject
        val errorCode get() = json.str("code")
        fun header(name: String) = headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value?.single()
    }
    private fun call(s: WebServer, path: String, body: String? = null, headers: Map<String, String> = emptyMap()): Reply {
        val c = URL("http://127.0.0.1:${s.port}$path").openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 15_000
        headers.forEach { (k, v) -> c.setRequestProperty(k, v) }
        if (body != null) { c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json"); c.outputStream.use { it.write(body.toByteArray()) } }
        try {
            val code = c.responseCode
            return Reply(code, (if (code >= 400) c.errorStream else c.inputStream)?.bufferedReader()?.use { it.readText() }.orEmpty(), c.headerFields)
        } finally { c.disconnect() }
    }
    private fun startClaim(s: WebServer, username: String, ip: String? = null) =
        call(s, "/api/claim/start", buildJsonObject { put("username", username) }.toString(), ip?.let { mapOf("X-Forwarded-For" to it) } ?: emptyMap())
    /** Each check comes a few seconds later than the last, past the server's short topic cache. */
    private fun verify(s: WebServer, challengeId: String): Reply { now += 4_000; return call(s, "/api/claim/verify", buildJsonObject { put("challengeId", challengeId) }.toString()) }
    private fun info(s: WebServer) = call(s, "/api/info").json.obj("claim")
    private fun tokens() = ClassTokenStore(file, zone, clock, reloadMillis = 0).all()
    private fun admin(vararg args: String): Pair<String, String> {
        val out = ByteArrayOutputStream(); val err = ByteArrayOutputStream()
        assertEquals(0, ClassTokenAdmin.run(args.toList() + listOf("--file", file.toString()), PrintStream(out, true, "UTF-8"), PrintStream(err, true, "UTF-8"), env = emptyMap(), clock = clock))
        return out.toString(Charsets.UTF_8) to err.toString(Charsets.UTF_8)
    }
    /** Full flow for [user]; returns the plaintext token. */
    private fun claim(s: WebServer, forum: FakeForum, user: FakeForum.User, typed: String = user.username): String {
        val started = startClaim(s, typed)
        assertEquals(200, started.code, started.text)
        forum.reply(user, "老师好，${started.json.str("phrase")} 谢谢")
        val done = verify(s, started.json.str("challengeId"))
        assertEquals(200, done.code, done.text)
        return done.json.str("token")
    }
    private fun assertNoTokenInLogs(vararg plain: String) = plain.forEach { t -> logs.forEach { assertFalse(t in it, "log leaks token: $it") } }

    @AfterTest fun cleanup() { dir.toFile().deleteRecursively() }

    @Test fun claimIssuesOneTokenWithConfiguredExpiryAndLimitsThatWorksOnV1() {
        FakeForum().use { forum -> FakeUpstream().use { up ->
            val alice = forum.user(101, "Alice"); forum.filler(5)
            server(claimConfig(forum.url), up.url).use { s ->
                assertEquals("open", info(s).str("state")); assertEquals(50, info(s)["remaining"]!!.jsonPrimitive.int)
                assertFalse(call(s, "/api/info").json["classTokens"]!!.jsonPrimitive.boolean, "token file does not exist yet")
                val started = startClaim(s, "@alice")
                assertEquals(200, started.code, started.text)
                val phrase = started.json.str("phrase")
                assertTrue(phrase.matches(Regex("领取小满令牌 [2-9A-Z]{6}")), phrase)
                assertEquals("Alice", started.json.str("username"), "the forum's spelling is used")
                assertEquals("${forum.url}/t/42", started.json.str("topicUrl"))
                assertEquals(600, started.json["expiresInSeconds"]!!.jsonPrimitive.int)
                assertEquals("no-store", started.header("Cache-Control"))

                // Typed on a phone: extra spaces, full-width letters, lower case, inside other text.
                val code = phrase.substringAfter(' ')
                val typed = "领取 小满令牌　" + code.lowercase().map { if (it.isLetter()) (it.code - 'a'.code + 'ａ'.code).toChar() else it }.joinToString("")
                forum.reply(alice, "收到！$typed")
                val done = verify(s, started.json.str("challengeId"))
                assertEquals(200, done.code, done.text)
                val token = done.json.str("token")
                assertTrue(ClassTokenCodec.looksLikeToken(token))
                assertEquals("2026-11-12 21:00", done.json.str("expiresAt"))
                assertEquals("xiaoman-class", done.json.str("model"))

                val t = tokens().single()
                assertEquals("论坛:Alice", t.label); assertEquals(expires, t.expiresAt)
                assertEquals(1_000_000, t.totalLimit); assertEquals(1_000_000, t.dailyLimit)
                assertEquals(ForumClaim(101, "Alice", now), t.claim)
                assertEquals(49, info(s)["remaining"]!!.jsonPrimitive.int)

                // The same challenge cannot be used twice.
                assertEquals("challenge_expired", verify(s, started.json.str("challengeId")).errorCode)

                val c = URL("http://127.0.0.1:${s.port}/v1/chat/completions").openConnection() as HttpURLConnection
                c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Authorization", "Bearer $token"); c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write("""{"model":"xiaoman-class","messages":[{"role":"user","content":"你好"}]}""".toByteArray()) }
                assertEquals(200, c.responseCode); c.inputStream.close()
                assertTrue(call(s, "/api/info").json["classTokens"]!!.jsonPrimitive.boolean)
                assertTrue(logs.any { it == "自助领取 论坛:Alice(${token.take(6)}) 已发放" }, logs.toString())
                // The gateway logs after the response is sent.
                val logged = { logs.any { it.startsWith("POST /v1/chat/completions 200") && "论坛:Alice(${token.take(6)})" in it } }
                repeat(50) { if (!logged()) Thread.sleep(20) }
                assertTrue(logged(), logs.toString())
                assertNoTokenInLogs(token)
            }
            assertEquals(15, tokens().single().usedTotal)
        } }
    }

    @Test fun wrongExpiredOrForeignPhrasesDoNotIssue() {
        FakeForum().use { forum ->
            val alice = forum.user(101, "alice"); val bob = forum.user(102, "bob")
            server(claimConfig(forum.url)).use { s ->
                val started = startClaim(s, "alice"); val id = started.json.str("challengeId")
                assertEquals("reply_not_found", verify(s, id).errorCode)
                forum.reply(alice, "领取小满令牌 AAAAAA")
                assertEquals("reply_not_found", verify(s, id).also { assertEquals(404, it.code) }.errorCode)
                // Bob posts Alice's phrase: found, but by the wrong account.
                forum.reply(bob, started.json.str("phrase"))
                val wrong = verify(s, id)
                assertEquals("reply_wrong_user", wrong.errorCode); assertTrue("bob" in wrong.json.str("error"))
                // Alice quoting Bob's post does not count either: only her own words are checked.
                forum.reply(alice, "[quote=\"bob, post:3\"]\n${started.json.str("phrase")}\n[/quote]\n顶",
                    "<aside class=\"quote no-group\"><blockquote><p>${started.json.str("phrase")}</p></blockquote></aside><p>顶</p>")
                assertEquals("reply_wrong_user", verify(s, id).errorCode)
                // Hidden or moderator posts are ignored.
                forum.reply(alice, started.json.str("phrase"), hidden = true)
                assertEquals("reply_wrong_user", verify(s, id).errorCode)

                now += 11 * 60_000
                forum.reply(alice, started.json.str("phrase"))
                val late = verify(s, id)
                assertEquals(410, late.code); assertEquals("challenge_expired", late.errorCode)
                assertEquals("challenge_expired", verify(s, "missing").errorCode)
                assertFalse(Files.exists(file), "no token was issued")
            }
        }
    }

    @Test fun oneTokenPerForumAccountAndRevokedIsNotReissuedUntilReset() {
        FakeForum().use { forum ->
            val alice = forum.user(101, "Alice")
            server(claimConfig(forum.url, perIp = 20)).use { s ->
                // Two challenges started before the first claim: the second one must not issue another token.
                val first = startClaim(s, "alice"); val second = startClaim(s, "ALICE")
                forum.reply(alice, first.json.str("phrase")); forum.reply(alice, second.json.str("phrase"))
                val token = verify(s, first.json.str("challengeId")).json.str("token")
                val again = verify(s, second.json.str("challengeId"))
                assertEquals(409, again.code); assertEquals("already_claimed", again.errorCode)
                assertEquals(token.take(6), again.json.str("prefix"))
                val restart = startClaim(s, "aLiCe")
                assertEquals("already_claimed", restart.errorCode)
                assertTrue(token.take(6) in restart.json.str("error"), restart.text)
                assertEquals(1, tokens().size)

                admin("revoke", "论坛:Alice")
                val revoked = startClaim(s, "alice")
                assertEquals("already_claimed", revoked.errorCode); assertTrue(revoked.json["revoked"]!!.jsonPrimitive.boolean)
                assertTrue("作废" in revoked.json.str("error"))
                admin("restore", "论坛:Alice")
                assertEquals("already_claimed", startClaim(s, "alice").errorCode)

                val (_, resetMsg) = admin("reset-claim", "alice")
                assertTrue("已重置 论坛:Alice(${token.take(6)})" in resetMsg, resetMsg)
                val newToken = claim(s, forum, alice)
                assertNotEquals(token, newToken)
                val all = tokens()
                assertEquals(2, all.size)
                assertTrue(all.single { it.prefix == token.take(6) }.revoked, "reset revokes the old token")
                assertEquals(1, ClaimService.activeClaims(all).size)
                val (list, _) = admin("claims")
                assertTrue(list.lines().any { it.startsWith("Alice\t101\t${token.take(6)}\t已重置") }, list)
                assertTrue(list.lines().any { it.startsWith("Alice\t101\t${newToken.take(6)}\t可用\t0/1000000\t0/1000000") }, list)
                assertTrue("已领取 1 份（另有 1 条已重置）" in list, list)
                assertNoTokenInLogs(token, newToken)
            }
        }
    }

    @Test fun resetClaimNeedsAnExistingClaim() {
        val err = ByteArrayOutputStream()
        assertEquals(2, ClassTokenAdmin.run(listOf("reset-claim", "nobody", "--file", file.toString()), PrintStream(ByteArrayOutputStream()), PrintStream(err, true, "UTF-8"), env = emptyMap()))
        assertTrue("找不到「nobody」" in err.toString(Charsets.UTF_8))
    }

    @Test fun totalLimitAndExpiryCloseTheClaim() {
        FakeForum().use { forum ->
            val users = (1..3).map { forum.user(200L + it, "user$it") }
            server(claimConfig(forum.url, max = 2)).use { s ->
                // user3 starts while there is room, but the limit is checked again when issuing.
                val late = startClaim(s, "user3"); forum.reply(users[2], late.json.str("phrase"))
                claim(s, forum, users[0]); claim(s, forum, users[1])
                assertEquals("full", info(s).str("state")); assertEquals(0, info(s)["remaining"]!!.jsonPrimitive.int)
                assertEquals("claim_full", startClaim(s, "user3").errorCode)
                assertEquals("claim_full", verify(s, late.json.str("challengeId")).errorCode)
                // Resetting an account frees its slot.
                admin("reset-claim", "user1")
                assertEquals("open", info(s).str("state"))
                assertEquals(200, verify(s, late.json.str("challengeId")).code)
                assertEquals("full", info(s).str("state"))

                now = expires
                assertEquals("expired", info(s).str("state"))
                val r = startClaim(s, "user1")
                assertEquals(410, r.code); assertEquals("claim_expired", r.errorCode); assertTrue("2026-11-12 21:00" in r.json.str("error"))
            }
        }
    }

    @Test fun accountAgeAndTrustLevelAreChecked() {
        FakeForum().use { forum ->
            forum.user(301, "newbie", createdAt = Instant.parse("2026-11-05T00:00:00Z").toEpochMilli(), trust = 1)
            forum.user(302, "visitor", trust = 0)
            forum.user(303, "regular", trust = 2)
            server(claimConfig(forum.url, before = ClaimConfig.parseTime("2026-11-01", zone), trust = 1)).use { s ->
                val young = startClaim(s, "newbie")
                assertEquals(403, young.code); assertEquals("account_too_new", young.errorCode)
                assertTrue("2026-11-01 00:00" in young.json.str("error"), young.text)
                assertEquals("trust_too_low", startClaim(s, "visitor").errorCode)
                assertEquals(200, startClaim(s, "regular").code)
                assertEquals("user_not_found", startClaim(s, "ghost").also { assertEquals(404, it.code) }.errorCode)
            }
        }
    }

    @Test fun invalidUsernamesAreRejectedBeforeAskingTheForum() {
        FakeForum().use { forum ->
            server(claimConfig(forum.url)).use { s ->
                for (bad in listOf("", "a b", "../admin", "x/y", "a".repeat(61), "名字?")) {
                    assertEquals("invalid_username", startClaim(s, bad).errorCode, bad)
                }
                assertEquals(0, forum.requests.get())
                forum.user(401, "汤圆")
                assertEquals(200, startClaim(s, "汤圆").code, "Discourse allows Unicode usernames")
            }
        }
        assertEquals("u.name-1_x", ForumClient.cleanUsername(" @u.name-1_x "))
    }

    @Test fun forumFailuresNeverIssueTokens() {
        FakeForum().use { forum ->
            val alice = forum.user(101, "alice")
            server(claimConfig(forum.url)).use { s ->
                val started = startClaim(s, "alice"); forum.reply(alice, started.json.str("phrase"))
                forum.failing = true
                val down = verify(s, started.json.str("challengeId"))
                assertEquals(503, down.code); assertEquals("forum_unavailable", down.errorCode)
                assertEquals("forum_unavailable", startClaim(s, "alice").errorCode)
                assertFalse(Files.exists(file))
                forum.failing = false
                assertEquals(200, verify(s, started.json.str("challengeId")).code, "the challenge survives a forum outage")
            }
        }
        val closedPort = ServerSocket(0).use { it.localPort }
        server(claimConfig("http://127.0.0.1:$closedPort")).use { s ->
            val r = startClaim(s, "alice")
            assertEquals(503, r.code); assertEquals("forum_unavailable", r.errorCode)
            assertTrue("稍后重试" in r.json.str("error"))
        }
        assertEquals(1, tokens().size)
        assertTrue(logs.any { "论坛请求失败" in it })
    }

    @Test fun startsAreRateLimitedPerClientIp() {
        FakeForum().use { forum ->
            forum.user(101, "alice")
            server(claimConfig(forum.url, perIp = 3)).use { s ->
                repeat(3) { assertEquals(200, startClaim(s, "alice", ip = "203.0.113.5").code) }
                val limited = startClaim(s, "alice", ip = "203.0.113.5")
                assertEquals(429, limited.code); assertEquals("rate_limited", limited.errorCode)
                assertNotNull(limited.header("Retry-After"))
                // Behind Caddy the client is the last X-Forwarded-For entry; a spoofed first entry does not help.
                assertEquals(429, startClaim(s, "alice", ip = "1.2.3.4, 203.0.113.5").code)
                assertEquals(200, startClaim(s, "alice", ip = "198.51.100.7").code)
                now += 61_000
                assertEquals(200, startClaim(s, "alice", ip = "203.0.113.5").code)
            }
        }
    }

    @Test fun longTopicsAreReadNewestFirstInBatches() {
        FakeForum().use { forum ->
            val alice = forum.user(101, "alice")
            forum.filler(400)
            server(claimConfig(forum.url)).use { s ->
                val started = startClaim(s, "alice")
                forum.reply(alice, started.json.str("phrase"))
                forum.filler(120, at = now) // newer replies after hers
                assertEquals(200, verify(s, started.json.str("challengeId")).code)
                assertEquals(3, forum.postRequests.get(), "120 newer posts + hers = 3 batches of 50")

                // A phrase not posted: reading stops once the posts are older than the phrase, not at 300.
                val other = forum.user(102, "bob")
                val second = startClaim(s, "bob")
                forum.postRequests.set(0)
                assertEquals("reply_not_found", verify(s, second.json.str("challengeId")).errorCode)
                assertTrue(forum.postRequests.get() <= 1, "cached recent posts; ${forum.postRequests.get()} requests")
                assertNotNull(other)
            }
        }
    }

    @Test fun claimWorksWithoutTheAccessCodeAndThePageIsServed() {
        FakeForum().use { forum ->
            forum.user(101, "alice")
            server(claimConfig(forum.url), accessCode = "classcode1").use { s ->
                assertEquals(401, call(s, "/api/session", "{}").code)
                assertEquals(200, startClaim(s, "alice").code)
                val page = call(s, "/claim")
                assertEquals(200, page.code); assertTrue("领取课堂令牌" in page.text)
                assertEquals(200, call(s, "/claim.js").code)
            }
        }
    }

    @Test fun infoShowsOffWithoutClaimConfig() {
        WebServer(WebServer.Options(port = 0)).start().use { s ->
            assertEquals("off", info(s).str("state"))
            assertEquals(404, startClaim(s, "alice").code)
        }
    }

    @Test fun configFromEnvironment() {
        assertNull(ClaimConfig.fromEnv(emptyMap(), zone).first)
        val (c, status) = ClaimConfig.fromEnv(mapOf("XIAOMAN_CLAIM_TOPIC_ID" to "https://forum.trae.cn/t/xiaoman-claim/465300/3",
            "XIAOMAN_CLAIM_EXPIRES" to "2026-11-12 21:00", "XIAOMAN_CLAIM_MIN_CREATED_BEFORE" to "2026-11-01", "XIAOMAN_CLAIM_MIN_TRUST" to "1",
            "XIAOMAN_CLAIM_TOTAL_TOKENS" to "2_000_000"), zone, now = start)
        assertEquals("已开启", status)
        assertEquals(465300, c!!.topicId); assertEquals(expires, c.expiresAt); assertEquals(50, c.max)
        assertEquals(2_000_000, c.totalTokens); assertEquals(2_000_000, c.dailyTokens, "daily defaults to the total")
        assertEquals(Instant.parse("2026-10-31T16:00:00Z").toEpochMilli(), c.createdBefore); assertEquals(1, c.minTrust)
        assertEquals("https://forum.trae.cn/t/465300", c.topicUrl)
        assertEquals(465300, ClaimConfig.fromEnv(mapOf("XIAOMAN_CLAIM_TOPIC_ID" to "465300", "XIAOMAN_CLAIM_EXPIRES" to "2026-11-12T21:00:00+08:00"), zone).first!!.topicId)
        assertFailsWith<IllegalArgumentException> { ClaimConfig.fromEnv(mapOf("XIAOMAN_CLAIM_TOPIC_ID" to "465300"), zone) }
        assertFailsWith<IllegalArgumentException> { ClaimConfig.fromEnv(mapOf("XIAOMAN_CLAIM_TOPIC_ID" to "abc", "XIAOMAN_CLAIM_EXPIRES" to "2026-11-12 21:00"), zone) }
        assertFailsWith<IllegalArgumentException> { ClaimConfig.fromEnv(mapOf("XIAOMAN_CLAIM_TOPIC_ID" to "1", "XIAOMAN_CLAIM_EXPIRES" to "明天"), zone) }
        assertFailsWith<IllegalArgumentException> { ClaimConfig.fromEnv(mapOf("XIAOMAN_CLAIM_TOPIC_ID" to "1", "XIAOMAN_CLAIM_EXPIRES" to "2026-11-12 21:00", "XIAOMAN_CLAIM_MIN_TRUST" to "9"), zone) }
    }

    @Test fun claimRecordsSurviveOtherAdminCommands() {
        FakeForum().use { forum ->
            val alice = forum.user(101, "alice")
            server(claimConfig(forum.url)).use { s -> claim(s, forum, alice) }
        }
        admin("generate", "2")
        admin("limit", "--all", "--expires", "2026-11-13 21:00", "--total", "2000000")
        val t = tokens().single { it.claim != null }
        assertEquals(101, t.claim!!.userId); assertEquals("alice", t.claim!!.username); assertFalse(t.claim!!.reset)
        assertEquals(2_000_000, t.totalLimit); assertEquals(ClaimConfig.parseTime("2026-11-13 21:00", zone), t.expiresAt)
        assertEquals(3, tokens().size)
    }

    /** Read-only check of the parser against the real forum; runs only with XIAOMAN_REAL_FORUM_TOPIC set (e.g. 5). */
    @Test fun realForumReadOnly() {
        val topic = System.getenv("XIAOMAN_REAL_FORUM_TOPIC")?.toLongOrNull() ?: return
        val client = ForumClient(System.getenv("XIAOMAN_REAL_FORUM") ?: ClaimConfig.DEFAULT_FORUM, okhttp3.OkHttpClient(), 20)
        val ids = client.stream(topic)
        assertTrue(ids.size > 20, "stream has every post ID, not only the first chunk: ${ids.size}")
        val posts = client.posts(topic, ids.takeLast(50))
        assertTrue(posts.size >= 45, "posts.json returns a batch of 50: ${posts.size}")
        val p = posts.first { it.visible }
        assertTrue(p.userId > 0 && p.username.isNotEmpty() && p.raw.isNotEmpty() && p.createdAt > 0, p.toString())
        val u = client.user(p.username)!!
        assertEquals(p.userId, u.id); assertTrue(u.createdAt != null)
        val upper = client.user(p.username.uppercase())
        assertEquals(u.id, upper?.id, "usernames are case-insensitive")
        assertNull(client.user("no_such_user_xm_${System.nanoTime() % 100000}"))
        println("real forum: topic $topic stream=${ids.size} batch=${posts.size} sample=${p.username}#${p.userId} raw=${p.raw.take(20)} " +
            "user created=${Instant.ofEpochMilli(u.createdAt!!)} trust=${u.trustLevel} visible=${posts.count { it.visible }}/${posts.size}")
    }
}
