package dev.xiaoman.web

import kotlinx.serialization.json.*
import java.nio.channels.FileChannel
import java.nio.file.*
import java.nio.file.attribute.BasicFileAttributes
import java.nio.file.attribute.PosixFileAttributeView
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Instant
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.ZoneId
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * One class token ("课堂令牌") as stored on disk. Only the SHA-256 [hash] of the plaintext is kept;
 * [prefix] is its first 6 characters, for logs and the admin list. Limits are in model tokens
 * (prompt + completion as reported by the upstream); null means unlimited.
 */
data class ClassToken(
    val hash: String, val prefix: String, val label: String,
    val createdAt: Long, val expiresAt: Long?, val revoked: Boolean = false,
    val dailyLimit: Long?, val totalLimit: Long?,
    val usedTotal: Long = 0, val usedToday: Long = 0, val usageDay: String = "",
    val requests: Long = 0, val lastUsedAt: Long? = null,
) {
    fun usedOn(day: String) = if (usageDay == day) usedToday else 0
    val tag get() = "$label($prefix)"
}

/** Why a token may not be used right now, as an OpenAI-style error. */
class ClassDenied(val status: Int, val code: String, val message: String, val retryAfterSeconds: Long? = null)

object ClassTokenCodec {
    const val PREFIX = "xm-"
    /** No 0/1/l/o, so tokens read aloud or copied from a slide are unambiguous. */
    private const val ALPHABET = "23456789abcdefghijkmnpqrstuvwxyz"
    private val random = SecureRandom()

    /** "xm-" + 20 characters from a 32-letter alphabet: 100 random bits. */
    fun generate(): String = PREFIX + (1..20).map { ALPHABET[random.nextInt(ALPHABET.length)] }.joinToString("")
    fun hash(token: String): String = MessageDigest.getInstance("SHA-256").digest(token.trim().toByteArray())
        .joinToString("") { "%02x".format(it) }
    fun looksLikeToken(value: String) = value.length == PREFIX.length + 20 && value.startsWith(PREFIX)

    fun encode(tokens: List<ClassToken>, zone: ZoneId): String {
        fun time(ms: Long?) = ms?.let { JsonPrimitive(OffsetDateTime.ofInstant(Instant.ofEpochMilli(it), zone).toString()) } ?: JsonNull
        val root = buildJsonObject {
            put("version", 1)
            put("note", "小满课堂令牌。只存 SHA-256 哈希；用 web-server tokens 管理，不要手改。")
            put("tokens", JsonArray(tokens.map { t -> buildJsonObject {
                put("label", t.label); put("prefix", t.prefix); put("sha256", t.hash)
                put("createdAt", time(t.createdAt)); put("expiresAt", time(t.expiresAt)); put("revoked", t.revoked)
                put("dailyLimit", t.dailyLimit); put("totalLimit", t.totalLimit)
                put("usedToday", t.usedToday); put("usageDay", t.usageDay); put("usedTotal", t.usedTotal)
                put("requests", t.requests); put("lastUsedAt", time(t.lastUsedAt))
            } }))
        }
        return prettyJson.encodeToString(JsonObject.serializer(), root) + "\n"
    }
    fun decode(text: String): List<ClassToken> {
        val root = Json.parseToJsonElement(text).jsonObject
        fun JsonObject.long(key: String) = (this[key] as? JsonPrimitive)?.takeUnless { it is JsonNull }?.longOrNull
        fun JsonObject.time(key: String) = str(key).takeIf { it.isNotBlank() }?.let { OffsetDateTime.parse(it).toInstant().toEpochMilli() }
        return root.arr("tokens").map { item ->
            val o = item.jsonObject
            val hash = o.str("sha256")
            require(hash.matches(Regex("[0-9a-f]{64}"))) { "令牌文件里有无效的哈希" }
            ClassToken(hash, o.str("prefix"), o.str("label"), o.time("createdAt") ?: 0, o.time("expiresAt"),
                (o["revoked"] as? JsonPrimitive)?.booleanOrNull == true, o.long("dailyLimit"), o.long("totalLimit"),
                o.long("usedTotal") ?: 0, o.long("usedToday") ?: 0, o.str("usageDay"), o.long("requests") ?: 0, o.time("lastUsedAt"))
        }
    }
    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    private val prettyJson = Json { prettyPrint = true; prettyPrintIndent = "  " }
}

/**
 * The token file shared by the running server (usage) and the admin command (everything else).
 *
 * Writers take an exclusive lock on `<file>.lock`, re-read the file, change it and atomically replace it,
 * so the server's usage updates and the admin's revocations never overwrite each other. The server notices
 * a replaced file within [reloadMillis] and keeps its not-yet-written usage in memory on top of it.
 */
class ClassTokenStore(
    val path: Path, private val zone: ZoneId,
    private val clock: () -> Long = System::currentTimeMillis,
    private val reloadMillis: Long = 1000,
) {
    private data class Key(val hash: String, val day: String)
    private class Delta(var tokens: Long = 0, var requests: Long = 0, var last: Long = 0)
    private var cached: Map<String, ClassToken> = emptyMap()
    private var stamp: Any? = null
    private var checkedAt = Long.MIN_VALUE
    private val pending = HashMap<Key, Delta>()
    private val inflight = HashMap<Key, Delta>()

    fun day(now: Long = clock()): String = LocalDate.ofInstant(Instant.ofEpochMilli(now), zone).toString()

    /** Whether the file exists (the class-token feature is off until it does). */
    fun exists(): Boolean = synchronized(this) { refresh(); stamp != null }

    /** Current state of the token whose plaintext is [token], including usage not yet written to disk. */
    fun find(token: String): ClassToken? = synchronized(this) {
        refresh()
        cached[ClassTokenCodec.hash(token)]?.let(::withPending)
    }
    fun all(): List<ClassToken> = synchronized(this) { refresh(); cached.values.map(::withPending) }

    fun check(t: ClassToken, now: Long = clock()): ClassDenied? = when {
        t.revoked -> ClassDenied(401, "token_revoked", "课堂令牌已作废")
        t.expiresAt != null && now >= t.expiresAt -> ClassDenied(401, "token_expired", "课堂令牌已过期")
        t.totalLimit != null && t.usedTotal >= t.totalLimit -> ClassDenied(429, "total_quota_exceeded", "课堂令牌的总额度已用完，请联系讲师")
        t.dailyLimit != null && t.usedOn(day(now)) >= t.dailyLimit -> ClassDenied(429, "daily_quota_exceeded", "课堂令牌今天的额度已用完，明天再试或联系讲师")
        else -> null
    }

    /** Adds one finished request's model tokens; written to disk by [flush]. */
    fun record(hash: String, tokens: Long) = synchronized(this) {
        val now = clock()
        pending.getOrPut(Key(hash, day(now))) { Delta() }.apply { this.tokens += tokens.coerceAtLeast(0); requests++; last = now }
        Unit
    }

    /** Writes pending usage. On failure the usage stays pending and the exception is rethrown. */
    fun flush() {
        val batch = synchronized(this) {
            if (pending.isEmpty()) return
            pending.forEach { (k, d) -> inflight.getOrPut(k) { Delta() }.apply { tokens += d.tokens; requests += d.requests; last = maxOf(last, d.last) } }
            pending.clear()
            inflight.map { (k, d) -> k to Delta(d.tokens, d.requests, d.last) }
        }
        try {
            update { list ->
                list.map { t ->
                    batch.filter { it.first.hash == t.hash }.sortedBy { it.first.day }.fold(t) { acc, (k, d) ->
                        val today = when {
                            acc.usageDay == k.day -> acc.usedToday + d.tokens
                            acc.usageDay < k.day -> d.tokens
                            else -> acc.usedToday
                        }
                        acc.copy(usedTotal = acc.usedTotal + d.tokens, usedToday = today, usageDay = maxOf(acc.usageDay, k.day),
                            requests = acc.requests + d.requests, lastUsedAt = maxOf(acc.lastUsedAt ?: 0, d.last))
                    }
                }
            }
            synchronized(this) { inflight.clear() }
        } catch (e: Exception) {
            synchronized(this) {
                inflight.forEach { (k, d) -> pending.getOrPut(k) { Delta() }.apply { tokens += d.tokens; requests += d.requests; last = maxOf(last, d.last) } }
                inflight.clear()
            }
            throw e
        }
    }

    /** Locked read-modify-write of the whole file; creates it when missing. Returns the written list. */
    fun update(change: (List<ClassToken>) -> List<ClassToken>): List<ClassToken> = processLock.withLock {
        Files.createDirectories(path.toAbsolutePath().parent)
        val lockFile = Path.of("$path.lock")
        val newLock = !Files.exists(lockFile)
        FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE).use { channel ->
            if (newLock) matchOwner(lockFile)
            channel.lock().use {
                val current = if (Files.exists(path)) ClassTokenCodec.decode(Files.readString(path)) else emptyList()
                val next = change(current)
                val tmp = path.resolveSibling(path.fileName.toString() + ".tmp-" + System.nanoTime())
                try {
                    Files.writeString(tmp, ClassTokenCodec.encode(next, zone))
                    runCatching { Files.setPosixFilePermissions(tmp, PosixFilePermissions.fromString("rw-r-----")) }
                    matchOwner(tmp)
                    Files.move(tmp, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
                } finally { Files.deleteIfExists(tmp) }
                synchronized(this) { checkedAt = Long.MIN_VALUE }
                next
            }
        }
    }

    private fun withPending(t: ClassToken): ClassToken {
        val today = day()
        val extra = (inflight.entries + pending.entries).filter { it.key.hash == t.hash }
        if (extra.isEmpty()) return t
        return t.copy(usedTotal = t.usedTotal + extra.sumOf { it.value.tokens },
            usedToday = t.usedOn(today) + extra.filter { it.key.day == today }.sumOf { it.value.tokens }, usageDay = today,
            requests = t.requests + extra.sumOf { it.value.requests },
            lastUsedAt = maxOf(t.lastUsedAt ?: 0, extra.maxOf { it.value.last }))
    }

    private fun refresh() {
        val now = System.nanoTime() / 1_000_000
        if (checkedAt != Long.MIN_VALUE && now - checkedAt < reloadMillis) return
        checkedAt = now
        val attrs = try { Files.readAttributes(path, BasicFileAttributes::class.java) } catch (_: NoSuchFileException) {
            cached = emptyMap(); stamp = null; return
        }
        val next = listOf(attrs.fileKey(), attrs.lastModifiedTime(), attrs.size())
        if (next == stamp) return
        // A file that cannot be parsed keeps the previous tokens; the admin command reports the error.
        val tokens = runCatching { ClassTokenCodec.decode(Files.readString(path)) }.getOrNull() ?: return
        cached = tokens.associateBy { it.hash }
        stamp = next
    }

    /** When root runs the admin command, new files get the owner of the data directory (the service user). */
    private fun matchOwner(file: Path) {
        runCatching {
            val dir = Files.getFileAttributeView(path.toAbsolutePath().parent, PosixFileAttributeView::class.java).readAttributes()
            val view = Files.getFileAttributeView(file, PosixFileAttributeView::class.java)
            val attrs = view.readAttributes()
            if (attrs.owner() != dir.owner()) view.owner = dir.owner()
            if (attrs.group() != dir.group()) view.setGroup(dir.group())
        }
    }

    private companion object { val processLock = ReentrantLock() }
}
