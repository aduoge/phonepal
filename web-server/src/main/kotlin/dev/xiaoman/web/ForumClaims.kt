package dev.xiaoman.web

import kotlinx.serialization.json.*
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.security.SecureRandom
import java.text.Normalizer
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Base64
import java.util.concurrent.Semaphore
import java.util.concurrent.TimeUnit

/**
 * Self-service class tokens ("自助领取"): a student proves they own a forum account by replying a one-time phrase
 * in the instructor's forum topic, and gets one token per forum account. Off unless [topicId] is configured.
 */
data class ClaimConfig(
    val topicId: Long,
    /** Every claimed token expires at this moment (the end of the last class). */
    val expiresAt: Long,
    val max: Int = 50,
    val totalTokens: Long = 1_000_000,
    val dailyTokens: Long = totalTokens,
    val forumUrl: String = DEFAULT_FORUM,
    /** Only accounts registered before this moment may claim; null allows any. */
    val createdBefore: Long? = null,
    val minTrust: Int = 0,
    val challengeMillis: Long = 10 * 60_000,
    val perIpStartsPerMinute: Int = 5,
    val perIpChecksPerMinute: Int = 20,
    val globalStartsPerMinute: Int = 120,
    val globalChecksPerMinute: Int = 120,
    val maxPending: Int = 5_000,
    /** How many of the topic's newest posts a check reads at most. */
    val scanPosts: Int = 300,
    val forumTimeoutSeconds: Long = 10,
    val forumConcurrency: Int = 4,
) {
    init {
        require(topicId > 0 && max > 0 && totalTokens > 0 && dailyTokens > 0 && minTrust in 0..4)
        require(challengeMillis > 0 && perIpStartsPerMinute > 0 && perIpChecksPerMinute > 0 && globalStartsPerMinute > 0 && globalChecksPerMinute > 0)
        require(maxPending > 0 && scanPosts > 0 && forumTimeoutSeconds > 0 && forumConcurrency > 0)
        require(forumUrl.toHttpUrlOrNull() != null) { "论坛地址无效" }
    }
    val topicUrl: String get() = forumUrl.trimEnd('/') + "/t/" + topicId

    companion object {
        const val DEFAULT_FORUM = "https://forum.trae.cn"

        /** Reads XIAOMAN_CLAIM_*; null (off) with the reason when no topic is configured. Invalid values throw. */
        fun fromEnv(env: Map<String, String>, zone: ZoneId, now: Long = System.currentTimeMillis()): Pair<ClaimConfig?, String> {
            fun v(name: String) = env[name]?.trim()?.takeIf { it.isNotEmpty() }
            fun long(name: String, default: Long) = v(name)?.replace("_", "")?.replace(",", "")
                ?.let { it.toLongOrNull()?.takeIf { n -> n > 0 } ?: throw IllegalArgumentException("$name 需要正整数") } ?: default
            val topic = v("XIAOMAN_CLAIM_TOPIC_ID") ?: return null to "未配置领取帖（XIAOMAN_CLAIM_TOPIC_ID）"
            val topicId = parseTopic(topic) ?: throw IllegalArgumentException("XIAOMAN_CLAIM_TOPIC_ID 需要帖子编号或帖子链接")
            val expires = parseTime(v("XIAOMAN_CLAIM_EXPIRES") ?: throw IllegalArgumentException("开启自助领取需要 XIAOMAN_CLAIM_EXPIRES（如 2026-11-12 21:00）"), zone)
                ?: throw IllegalArgumentException("XIAOMAN_CLAIM_EXPIRES 格式：2026-11-12 21:00")
            val total = long("XIAOMAN_CLAIM_TOTAL_TOKENS", 1_000_000)
            val forum = (v("XIAOMAN_CLAIM_FORUM") ?: DEFAULT_FORUM).trimEnd('/')
            require(forum.startsWith("https://") || forum.startsWith("http://")) { "XIAOMAN_CLAIM_FORUM 无效" }
            val before = v("XIAOMAN_CLAIM_MIN_CREATED_BEFORE")?.let {
                parseTime(it, zone) ?: throw IllegalArgumentException("XIAOMAN_CLAIM_MIN_CREATED_BEFORE 格式：2026-11-01 或 2026-11-01 12:00")
            }
            val trust = v("XIAOMAN_CLAIM_MIN_TRUST")?.let { it.toIntOrNull()?.takeIf { n -> n in 0..4 } ?: throw IllegalArgumentException("XIAOMAN_CLAIM_MIN_TRUST 需要 0-4") } ?: 0
            val config = ClaimConfig(topicId, expires, long("XIAOMAN_CLAIM_MAX", 50).toInt(), total, long("XIAOMAN_CLAIM_DAILY_TOKENS", total),
                forum, before, trust)
            return config to if (now >= expires) "已过期" else "已开启"
        }

        /** `465300`, or a topic link such as `https://forum.trae.cn/t/some-slug/465300/12`. */
        fun parseTopic(text: String): Long? {
            text.trim().toLongOrNull()?.takeIf { it > 0 }?.let { return it }
            val segments = text.trim().toHttpUrlOrNull()?.pathSegments ?: return null
            val t = segments.indexOf("t").takeIf { it >= 0 } ?: return null
            return segments.drop(t + 1).take(2).mapNotNull { it.toLongOrNull() }.firstOrNull()?.takeIf { it > 0 }
        }
        /** `2026-11-12 21:00`, `2026-11-12T21:00:00+08:00` or a bare date (its start) in [zone]. */
        fun parseTime(text: String, zone: ZoneId): Long? {
            val t = text.trim().replace(' ', 'T')
            return runCatching { OffsetDateTime.parse(t).toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching { LocalDateTime.parse(if (t.length == 16) "$t:00" else t).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching { LocalDate.parse(t).atStartOfDay(zone).toInstant().toEpochMilli() }.getOrNull()
        }
    }
}

/**
 * Read-only client for the public JSON of a Discourse forum: `/u/{name}.json`, `/t/{id}.json` (the post-ID stream)
 * and `/t/{id}/posts.json?post_ids[]=…&include_raw=true`. Errors other than "not found" throw [ForumUnavailable].
 */
class ForumClient(baseUrl: String, http: OkHttpClient, timeoutSeconds: Long = 10) {
    data class User(val id: Long, val username: String, val createdAt: Long?, val trustLevel: Int, val suspended: Boolean)
    data class Post(val id: Long, val userId: Long, val username: String, val raw: String, val cooked: String,
        val createdAt: Long, val updatedAt: Long, val visible: Boolean)
    class ForumUnavailable(message: String) : Exception(message)
    class TopicMissing : Exception("领取帖不存在或不公开")

    private val base = requireNotNull(baseUrl.trimEnd('/').toHttpUrlOrNull()) { "论坛地址无效" }
    private val http = http.newBuilder().connectTimeout(timeoutSeconds, TimeUnit.SECONDS).readTimeout(timeoutSeconds, TimeUnit.SECONDS)
        .callTimeout(timeoutSeconds + 5, TimeUnit.SECONDS).followRedirects(true).build()

    /** The account, or null when the forum has no such username (Discourse matches it case-insensitively). */
    fun user(username: String): User? {
        val root = get(base.newBuilder().addPathSegment("u").addPathSegment("$username.json").build()) ?: return null
        val u = root["user"] as? JsonObject ?: throw ForumUnavailable("论坛返回的用户信息格式不对")
        val id = (u["id"] as? JsonPrimitive)?.longOrNull ?: throw ForumUnavailable("论坛返回的用户信息格式不对")
        val suspendedTill = time(u.str("suspended_till"))
        return User(id, u.str("username").ifEmpty { username }, time(u.str("created_at")),
            (u["trust_level"] as? JsonPrimitive)?.intOrNull ?: 0, suspendedTill != null && suspendedTill > System.currentTimeMillis())
    }

    /** IDs of every post in the topic, oldest first. */
    fun stream(topicId: Long): List<Long> {
        // Discourse may serve anonymous GETs from a short-lived cache; a changing query keeps new replies visible.
        val root = get(base.newBuilder().addPathSegment("t").addPathSegment("$topicId.json")
            .addQueryParameter("_", (System.currentTimeMillis() / 1000).toString()).build()) ?: throw TopicMissing()
        val stream = (root["post_stream"] as? JsonObject)?.get("stream") as? JsonArray ?: throw ForumUnavailable("论坛返回的帖子格式不对")
        return stream.mapNotNull { (it as? JsonPrimitive)?.longOrNull }
    }

    fun posts(topicId: Long, ids: List<Long>): List<Post> {
        if (ids.isEmpty()) return emptyList()
        val url = base.newBuilder().addPathSegment("t").addPathSegment(topicId.toString()).addPathSegment("posts.json")
            .apply { ids.forEach { addQueryParameter("post_ids[]", it.toString()) } }
            .addQueryParameter("include_raw", "true").build()
        val root = get(url) ?: throw TopicMissing()
        val posts = (root["post_stream"] as? JsonObject)?.get("posts") as? JsonArray ?: throw ForumUnavailable("论坛返回的帖子格式不对")
        return posts.mapNotNull { item ->
            val p = item as? JsonObject ?: return@mapNotNull null
            val id = (p["id"] as? JsonPrimitive)?.longOrNull ?: return@mapNotNull null
            val created = time(p.str("created_at")) ?: 0
            Post(id, (p["user_id"] as? JsonPrimitive)?.longOrNull ?: -1, p.str("username"), p.str("raw"), p.str("cooked"),
                created, time(p.str("updated_at")) ?: created,
                // Regular, not hidden, not deleted (post_type 1 is a normal reply; 2 moderator notes, 3 small actions).
                ((p["post_type"] as? JsonPrimitive)?.intOrNull ?: 1) == 1 && (p["hidden"] as? JsonPrimitive)?.booleanOrNull != true &&
                    p["deleted_at"].let { it == null || it is JsonNull } && (p["user_deleted"] as? JsonPrimitive)?.booleanOrNull != true)
        }
    }

    /** Parsed JSON, null for 404; anything else unexpected is [ForumUnavailable]. */
    private fun get(url: HttpUrl): JsonObject? {
        val request = Request.Builder().url(url).header("Accept", "application/json").header("User-Agent", "xiaoman-claim/1.0").build()
        try {
            http.newCall(request).execute().use { r ->
                if (r.code == 404) return null
                if (r.code == 429) throw ForumUnavailable("论坛繁忙（429）")
                if (!r.isSuccessful) throw ForumUnavailable("论坛返回 ${r.code}")
                val text = r.body?.string().orEmpty()
                return runCatching { Json.parseToJsonElement(text) as JsonObject }.getOrNull() ?: throw ForumUnavailable("论坛返回的不是 JSON")
            }
        } catch (e: IOException) { throw ForumUnavailable("连不上论坛（${e.javaClass.simpleName}）") }
    }

    private fun time(text: String): Long? = text.takeIf { it.isNotBlank() }?.let { runCatching { Instant.parse(it).toEpochMilli() }.getOrNull()
        ?: runCatching { OffsetDateTime.parse(it).toInstant().toEpochMilli() }.getOrNull() }

    companion object {
        /** Discourse usernames: letters (any script), digits, `_`, `.`, `-`; we allow 1-60 characters. */
        private val USERNAME = Regex("^[\\p{L}\\p{M}\\p{N}_.\\-]{1,60}$")
        fun cleanUsername(input: String): String? = Normalizer.normalize(input.trim().removePrefix("@"), Normalizer.Form.NFC).takeIf { it.matches(USERNAME) }

        private val QUOTE_RAW = Regex("\\[quote[^\\]]*][\\s\\S]*?\\[/quote]", RegexOption.IGNORE_CASE)
        private val QUOTE_HTML = Regex("<aside[^>]*class=\"[^\"]*quote[^\"]*\"[^>]*>[\\s\\S]*?</aside>", RegexOption.IGNORE_CASE)
        private val TAG = Regex("<[^>]*>")
        /** The post's own words (quotes of other posts removed), raw or cooked with HTML stripped. */
        fun texts(post: Post): List<String> = listOf(
            post.raw.replace(QUOTE_RAW, " "),
            post.cooked.replace(QUOTE_HTML, " ").replace(TAG, " ")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&"),
        )
        /** Whitespace, width and case differences are ignored, so a phrase typed on a phone still matches. */
        fun normalize(text: String) = Normalizer.normalize(text, Normalizer.Form.NFKC).replace(Regex("\\s+"), "").uppercase()
        fun contains(post: Post, phrase: String): Boolean = normalize(phrase).let { p -> texts(post).any { normalize(it).contains(p) } }
    }
}

/** Sliding one-minute windows per key. */
class RateWindows(private val perMinute: Int, private val clock: () -> Long) {
    private val windows = HashMap<String, ArrayDeque<Long>>()
    /** Records a hit, or returns the seconds to wait when [key] is over the limit. */
    @Synchronized fun hit(key: String): Long? {
        val now = clock()
        if (windows.size > 10_000) windows.entries.removeIf { (_, w) -> w.isEmpty() || now - w.last() >= 60_000 }
        val w = windows.getOrPut(key) { ArrayDeque() }
        while (w.isNotEmpty() && now - w.first() >= 60_000) w.removeFirst()
        if (w.size >= perMinute) return ((w.first() + 60_000 - now + 999) / 1000).coerceAtLeast(1)
        w.addLast(now)
        return null
    }
}

/**
 * The claim flow behind `/claim`: [start] checks the forum account and hands out a one-time phrase;
 * [verify] looks for the account's reply containing it and then writes a new token into the class-token file.
 * Plaintext tokens are returned once and never logged; logs carry `论坛:用户名(xm-xxx)` only.
 */
class ClaimService(
    val config: ClaimConfig,
    private val store: ClassTokenStore,
    private val zone: ZoneId,
    http: OkHttpClient,
    private val clock: () -> Long = System::currentTimeMillis,
    private val logger: (String) -> Unit = ::println,
    /** Model name students type in the Android app (the class whitelist name). */
    private val model: String = "xiaoman-class",
) {
    class Reply(val status: Int, val body: JsonObject, val retryAfter: Long? = null)
    private class Challenge(val id: String, val userId: Long, val username: String, val phrase: String, val createdAt: Long, val expiresAt: Long)

    private val forum = ForumClient(config.forumUrl, http, config.forumTimeoutSeconds)
    private val challenges = LinkedHashMap<String, Challenge>()
    private val ipStarts = RateWindows(config.perIpStartsPerMinute, clock)
    private val ipChecks = RateWindows(config.perIpChecksPerMinute, clock)
    private val allStarts = RateWindows(config.globalStartsPerMinute, clock)
    private val allChecks = RateWindows(config.globalChecksPerMinute, clock)
    private val forumSlots = Semaphore(config.forumConcurrency)
    private val random = SecureRandom()

    // Many students check within the same minute: the topic stream is shared for a few seconds, fetched posts a bit longer.
    private var streamCache: Pair<Long, List<Long>>? = null
    private val postCache = HashMap<Long, Pair<Long, ForumClient.Post>>()

    /** open / full / expired, as shown by /api/info and the claim page. */
    fun state(now: Long = clock()): String = when {
        now >= config.expiresAt -> "expired"
        activeClaims(store.all()).size >= config.max -> "full"
        else -> "open"
    }

    fun info(): JsonObject {
        val claimed = activeClaims(store.all()).size
        return buildJsonObject {
            put("state", state()); put("topicUrl", config.topicUrl); put("expiresAt", display(config.expiresAt))
            put("remaining", (config.max - claimed).coerceAtLeast(0)); put("max", config.max)
            put("totalTokens", config.totalTokens); put("dailyTokens", config.dailyTokens); put("model", model)
        }
    }

    fun start(ip: String, body: JsonObject): Reply {
        closed()?.let { return it }
        val username = ForumClient.cleanUsername(body.str("username"))
            ?: return fail(400, "invalid_username", "论坛用户名格式不对：只能是字母、汉字、数字和 _ . -，不带空格（在论坛点头像→个人资料可以看到）")
        limited(ipStarts.hit(ip) ?: allStarts.hit("all"))?.let { return it }
        val user = try { withForum { forum.user(username) } } catch (e: ForumClient.ForumUnavailable) { return forumDown(e) }
            ?: return fail(404, "user_not_found", "论坛上找不到用户「$username」。请填用户名（个人主页地址 /u/ 后面那段），不是昵称")
        eligibility(user)?.let { return it }
        existing(user.id)?.let { return it }
        val now = clock()
        val challenge = synchronized(challenges) {
            purge(now)
            while (challenges.size >= config.maxPending) challenges.remove(challenges.keys.first())
            val id = Base64.getUrlEncoder().withoutPadding().encodeToString(ByteArray(18).also(random::nextBytes))
            val code = (1..6).map { CODE[random.nextInt(CODE.length)] }.joinToString("")
            Challenge(id, user.id, user.username, "$PHRASE $code", now, now + config.challengeMillis).also { challenges[id] = it }
        }
        return Reply(200, buildJsonObject {
            put("ok", true); put("challengeId", challenge.id); put("phrase", challenge.phrase); put("username", challenge.username)
            put("topicUrl", config.topicUrl); put("expiresInSeconds", config.challengeMillis / 1000)
        })
    }

    fun verify(ip: String, body: JsonObject): Reply {
        closed()?.let { return it }
        val challenge = synchronized(challenges) { purge(clock()); challenges[body.str("challengeId")] }
            ?: return fail(410, "challenge_expired", "验证短语已过期（10 分钟有效）或无效，请重新填写用户名获取新的短语")
        limited(ipChecks.hit(ip) ?: allChecks.hit("all"))?.let { return it }
        existing(challenge.userId)?.let { return it }
        val found = try { withForum { findReply(challenge) } } catch (e: ForumClient.ForumUnavailable) { return forumDown(e)
        } catch (_: ForumClient.TopicMissing) { return fail(503, "topic_missing", "领取帖打不开（可能已删除或设为不公开），请联系讲师") }
        when {
            found == null -> return fail(404, "reply_not_found", "还没在领取帖里找到「${challenge.username}」发的这句话。刚回复的话等几秒再点；" +
                "如果论坛提示回复需要审核，审核通过后再点")
            found.userId != challenge.userId -> return fail(403, "reply_wrong_user", "找到了这句话，但发帖账号是「${found.username}」，不是你填的「${challenge.username}」。" +
                "请用自己的账号回复，或者返回重新填写用户名")
        }
        // The account's own reply is there: issue the token under the file lock, where the one-per-account and total limits are checked again.
        var plain: String? = null
        var outcome: Reply? = null
        val now = clock()
        store.update { list ->
            val active = activeClaims(list)
            val mine = active.firstOrNull { it.claim!!.userId == challenge.userId }
            when {
                mine != null -> { outcome = alreadyClaimed(mine); list }
                active.size >= config.max -> { outcome = fail(409, "claim_full", "这期的 ${config.max} 份令牌已经领完了，请联系讲师"); list }
                else -> {
                    val known = list.map { it.hash }.toSet()
                    var p: String
                    do { p = ClassTokenCodec.generate() } while (ClassTokenCodec.hash(p) in known)
                    plain = p
                    list + ClassToken(ClassTokenCodec.hash(p), p.take(6), "$LABEL${challenge.username}", now, config.expiresAt,
                        dailyLimit = config.dailyTokens, totalLimit = config.totalTokens,
                        claim = ForumClaim(challenge.userId, challenge.username, now))
                }
            }
        }
        synchronized(challenges) { challenges.remove(challenge.id) }
        outcome?.let { return it }
        val token = plain!!
        logger("自助领取 $LABEL${challenge.username}(${token.take(6)}) 已发放")
        return Reply(200, buildJsonObject {
            put("ok", true); put("token", token); put("prefix", token.take(6)); put("username", challenge.username)
            put("expiresAt", display(config.expiresAt)); put("totalTokens", config.totalTokens); put("dailyTokens", config.dailyTokens)
            put("model", model)
        })
    }

    /** The newest matching reply by anyone, preferring the claimant's own; null when none in the scanned range. */
    private fun findReply(c: Challenge): ForumClient.Post? {
        val ids = cachedStream().takeLast(config.scanPosts).reversed()
        var other: ForumClient.Post? = null
        // Posts written well before the phrase existed cannot contain it.
        val oldest = c.createdAt - 5 * 60_000
        for (batch in ids.chunked(BATCH)) {
            val posts = cachedPosts(batch)
            for (p in posts.sortedByDescending { it.id }) {
                if (!p.visible || !ForumClient.contains(p, c.phrase)) continue
                if (p.userId == c.userId) return p
                if (other == null) other = p
            }
            if (posts.isNotEmpty() && posts.all { maxOf(it.createdAt, it.updatedAt) < oldest }) break
        }
        return other
    }

    private fun cachedStream(): List<Long> {
        val now = clock()
        synchronized(this) { streamCache?.let { (at, ids) -> if (now - at < STREAM_CACHE_MS) return ids } }
        val ids = forum.stream(config.topicId)
        synchronized(this) { streamCache = now to ids }
        return ids
    }
    private fun cachedPosts(ids: List<Long>): List<ForumClient.Post> {
        val now = clock()
        val (hit, miss) = synchronized(this) {
            postCache.entries.removeIf { now - it.value.first >= POST_CACHE_MS }
            ids.partition { it in postCache }
        }
        val fetched = if (miss.isEmpty()) emptyList() else forum.posts(config.topicId, miss)
        return synchronized(this) {
            fetched.forEach { postCache[it.id] = now to it }
            hit.mapNotNull { postCache[it]?.second } + fetched
        }
    }

    private fun eligibility(user: ForumClient.User): Reply? = when {
        user.suspended -> fail(403, "account_suspended", "这个论坛账号目前被封禁，不能领取")
        config.createdBefore != null && (user.createdAt == null || user.createdAt >= config.createdBefore) ->
            fail(403, "account_too_new", "这个论坛账号注册于 ${user.createdAt?.let(::display) ?: "未知时间"}，本次只限 ${display(config.createdBefore)} 之前注册的账号领取")
        user.trustLevel < config.minTrust -> fail(403, "trust_too_low", "这个论坛账号的信任等级是 ${user.trustLevel}，本次需要 ${config.minTrust} 级以上（多在论坛浏览、回复就会升级）")
        else -> null
    }
    private fun existing(userId: Long): Reply? = activeClaims(store.all()).firstOrNull { it.claim!!.userId == userId }?.let(::alreadyClaimed)
    private fun alreadyClaimed(t: ClassToken): Reply {
        val state = when (store.check(t)?.code) {
            "token_revoked" -> "，已被讲师作废，不会再补发；有问题请联系讲师"
            "token_expired" -> "，已经过期"
            else -> "。令牌只在领取时显示一次，找不到了请联系讲师"
        }
        return fail(409, "already_claimed", "论坛账号「${t.claim!!.username}」已经领过令牌（${t.prefix}…）$state", extra = buildJsonObject {
            put("prefix", t.prefix); put("revoked", t.revoked)
        })
    }
    private fun closed(): Reply? = when (state()) {
        "expired" -> fail(410, "claim_expired", "领取已结束（课堂令牌在 ${display(config.expiresAt)} 过期）")
        "full" -> fail(409, "claim_full", "这期的 ${config.max} 份令牌已经领完了，请联系讲师")
        else -> null
    }
    private fun limited(wait: Long?) = wait?.let { fail(429, "rate_limited", "操作太频繁，请 $it 秒后再试", retryAfter = it) }
    private fun forumDown(e: ForumClient.ForumUnavailable): Reply {
        logger("自助领取 论坛请求失败：${e.message}")
        return fail(503, "forum_unavailable", "暂时连不上论坛，请稍后重试（这次没有发放令牌）", retryAfter = 10)
    }
    private fun <T> withForum(block: () -> T): T {
        if (!forumSlots.tryAcquire(config.forumTimeoutSeconds, TimeUnit.SECONDS)) throw ForumClient.ForumUnavailable("排队超时")
        try { return block() } finally { forumSlots.release() }
    }
    private fun purge(now: Long) { challenges.values.removeIf { now >= it.expiresAt } }
    private fun fail(status: Int, code: String, message: String, retryAfter: Long? = null, extra: JsonObject? = null) =
        Reply(status, JsonObject(buildJsonObject { put("ok", false); put("code", code); put("error", message) } + (extra ?: emptyMap())), retryAfter)
    private fun display(ms: Long) = DISPLAY.format(Instant.ofEpochMilli(ms).atZone(zone))

    companion object {
        const val PHRASE = "领取小满令牌"
        const val LABEL = "论坛:"
        private const val CODE = "23456789ABCDEFGHJKMNPQRSTUVWXYZ"
        private const val BATCH = 50
        private const val STREAM_CACHE_MS = 3_000L
        private const val POST_CACHE_MS = 30_000L
        private val DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
        fun activeClaims(list: List<ClassToken>) = list.filter { it.claim != null && !it.claim.reset }
    }
}
