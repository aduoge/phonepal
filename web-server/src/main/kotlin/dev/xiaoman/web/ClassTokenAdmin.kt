package dev.xiaoman.web

import java.io.PrintStream
import java.nio.file.Path
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * `web-server tokens ...`: manages the class-token file on the server itself (there is no network admin API).
 * Run it as the service user (deploy installs `xiaoman-tokens`, which does that); the running server
 * picks up changes within about a second.
 */
object ClassTokenAdmin {
    const val DEFAULT_FILE = "/var/lib/xiaoman-web/class-tokens.json"
    const val DEFAULT_DAILY = 500_000L
    const val DEFAULT_TOTAL = 2_000_000L

    val usage = """课堂令牌管理（只在服务器本机运行；服务运行中修改约 1 秒内生效）
用法：web-server tokens <命令> [选项]
  generate N [--label 学员] [--start 1] [--days 3 | --hours H | --expires "2026-11-08 22:00"]
             [--daily $DEFAULT_DAILY|none] [--total $DEFAULT_TOTAL|none] [--csv] [--base-url URL] [--model 名称]
      生成 N 个令牌，标签为「学员 01」「学员 02」…（编号默认接着已有的）。明文只在这里显示一次；
      --csv 把 CSV（标签,令牌,过期时间,…）输出到标准输出，可重定向到文件发给学员。
  list                         列出全部令牌：状态、今日用量/每日上限、累计/总上限、请求数
  revoke <标签|令牌|前缀>...   作废指定令牌
  restore <标签|令牌|前缀>...  恢复误作废的令牌
  revoke-all                   全部作废（课后执行）
  limit <标签|令牌|前缀|--all> [--daily N|none] [--total N|none] [--days D | --hours H | --expires 时间]
      调整上限或过期时间
通用选项：--file PATH（默认 ${'$'}XIAOMAN_CLASS_TOKENS 或 $DEFAULT_FILE），--timezone Asia/Shanghai
用量单位是模型 token（输入 + 输出），按方舟返回的 usage 记账；运行中的服务每 5 秒写回一次。"""

    private class Usage(message: String) : Exception(message)

    fun run(args: List<String>, out: PrintStream = System.out, err: PrintStream = System.err,
        env: Map<String, String> = System.getenv(), clock: () -> Long = System::currentTimeMillis): Int {
        val rest = args.toMutableList()
        fun option(name: String): String? {
            val i = rest.indexOf(name)
            if (i < 0) return null
            if (i + 1 >= rest.size) throw Usage("$name 需要一个值")
            return rest[i + 1].also { rest.removeAt(i); rest.removeAt(i) }
        }
        fun flag(name: String) = rest.remove(name)
        return try {
            if (rest.isEmpty() || rest[0] in setOf("-h", "--help", "help")) { out.println(usage); return 0 }
            val file = Path.of(option("--file") ?: env["XIAOMAN_CLASS_TOKENS"]?.takeIf { it.isNotBlank() } ?: DEFAULT_FILE)
            val zone = ZoneId.of(option("--timezone") ?: env["XIAOMAN_TZ"]?.takeIf { it.isNotBlank() } ?: "Asia/Shanghai")
            val store = ClassTokenStore(file, zone, clock, reloadMillis = 0)
            val command = rest.removeAt(0)
            /** [Unset] when absent, null for `none` (unlimited), otherwise the positive number. */
            fun limit(name: String): Any? {
                val v = option(name) ?: return Unset
                if (v.lowercase() in NONE) return null
                return v.replace("_", "").replace(",", "").toLongOrNull()?.takeIf { it > 0 } ?: throw Usage("$name 需要正整数或 none")
            }
            when (command) {
                "generate" -> {
                    val label = option("--label") ?: "学员"
                    val start = option("--start")?.let { it.toIntOrNull() ?: throw Usage("--start 需要整数") }
                    val expires = expiry(option("--days"), option("--hours"), option("--expires"), zone, clock())
                        ?: (clock() + 3 * 86_400_000L)
                    val daily = limit("--daily").orDefault(DEFAULT_DAILY)
                    val total = limit("--total").orDefault(DEFAULT_TOTAL)
                    val csv = flag("--csv")
                    val baseUrl = option("--base-url"); val model = option("--model")
                    val n = rest.singleOrNull()?.toIntOrNull()?.takeIf { it in 1..500 } ?: throw Usage("generate 需要数量 N（1-500）")
                    val created = mutableListOf<Pair<ClassToken, String>>()
                    store.update { list ->
                        val pattern = Regex("^" + Regex.escape(label) + " (\\d+)$")
                        val first = start ?: ((list.mapNotNull { pattern.find(it.label)?.groupValues?.get(1)?.toIntOrNull() }.maxOrNull() ?: 0) + 1)
                        val width = maxOf(2, (first + n - 1).toString().length)
                        val now = clock()
                        val known = list.map { it.hash }.toMutableSet()
                        repeat(n) { i ->
                            var plain: String
                            do { plain = ClassTokenCodec.generate() } while (!known.add(ClassTokenCodec.hash(plain)))
                            created += ClassToken(ClassTokenCodec.hash(plain), plain.take(6), "$label ${(first + i).toString().padStart(width, '0')}",
                                now, expires, dailyLimit = daily, totalLimit = total) to plain
                        }
                        list + created.map { it.first }
                    }
                    if (csv) {
                        out.print('﻿')
                        out.println(listOfNotNull("标签", "令牌", "过期时间", "每日上限", "总上限", baseUrl?.let { "Base URL" }, model?.let { "模型名" }).joinToString(","))
                        created.forEach { (t, plain) ->
                            out.println(listOfNotNull(t.label, plain, time(t.expiresAt, zone), amount(t.dailyLimit), amount(t.totalLimit), baseUrl, model)
                                .joinToString(",") { csvField(it) })
                        }
                    } else {
                        created.forEach { (t, plain) -> out.println("${t.label}\t$plain") }
                    }
                    err.println("已生成 ${created.size} 个令牌（${file}），过期时间 ${time(expires, zone)}，每日上限 ${amount(daily)}，总上限 ${amount(total)}。明文只显示这一次，请妥善保存。")
                }
                "list" -> {
                    if (rest.isNotEmpty()) throw Usage("list 不需要参数")
                    val tokens = store.all()
                    if (tokens.isEmpty()) { out.println("（没有令牌：$file）"); return 0 }
                    val today = store.day()
                    out.println(listOf("标签", "前缀", "状态", "今日/每日上限", "累计/总上限", "请求数", "过期时间", "最近使用").joinToString("\t"))
                    tokens.forEach { t ->
                        val state = store.check(t)?.let { d -> when (d.code) { "token_revoked" -> "已作废"; "token_expired" -> "已过期"; "total_quota_exceeded" -> "总额度用完"; else -> "今日额度用完" } } ?: "可用"
                        out.println(listOf(t.label, t.prefix, state, "${t.usedOn(today)}/${amount(t.dailyLimit)}", "${t.usedTotal}/${amount(t.totalLimit)}",
                            t.requests.toString(), time(t.expiresAt, zone), time(t.lastUsedAt, zone)).joinToString("\t"))
                    }
                    val active = tokens.count { store.check(it) == null }
                    out.println("共 ${tokens.size} 个，可用 $active 个，累计用量 ${tokens.sumOf { it.usedTotal }} token，今日 ${tokens.sumOf { it.usedOn(today) }} token")
                }
                "revoke", "restore" -> {
                    if (rest.isEmpty()) throw Usage("$command 需要标签、令牌或前缀")
                    val changed = store.update { list ->
                        val targets = rest.flatMap { select(list, it) }.map { it.hash }.toSet()
                        list.map { if (it.hash in targets) it.copy(revoked = command == "revoke") else it }
                    }.filter { t -> rest.any { sel -> runCatching { select(listOf(t), sel) }.isSuccess } }
                    err.println((if (command == "revoke") "已作废：" else "已恢复：") + changed.joinToString("、") { it.tag })
                }
                "revoke-all" -> {
                    if (rest.isNotEmpty()) throw Usage("revoke-all 不需要参数")
                    val list = store.update { list -> list.map { it.copy(revoked = true) } }
                    err.println("已作废全部 ${list.size} 个令牌。")
                }
                "limit" -> {
                    val all = flag("--all")
                    val expires = expiry(option("--days"), option("--hours"), option("--expires"), zone, clock())
                    val daily = limit("--daily"); val total = limit("--total")
                    if (expires == null && daily === Unset && total === Unset) throw Usage("limit 需要 --daily、--total 或过期时间")
                    if (!all && rest.isEmpty()) throw Usage("limit 需要标签、令牌、前缀或 --all")
                    val changed = mutableListOf<ClassToken>()
                    store.update { list ->
                        val targets = if (all) list.map { it.hash }.toSet() else rest.flatMap { select(list, it) }.map { it.hash }.toSet()
                        list.map { t ->
                            if (t.hash !in targets) t else t.copy(
                                dailyLimit = if (daily === Unset) t.dailyLimit else daily as Long?,
                                totalLimit = if (total === Unset) t.totalLimit else total as Long?,
                                expiresAt = expires ?: t.expiresAt,
                            ).also { changed += it }
                        }
                    }
                    changed.forEach { err.println("${it.tag}：每日上限 ${amount(it.dailyLimit)}，总上限 ${amount(it.totalLimit)}，过期 ${time(it.expiresAt, zone)}") }
                }
                else -> throw Usage("未知命令 $command")
            }
            0
        } catch (e: Usage) {
            err.println("错误：${e.message}\n\n$usage"); 2
        } catch (e: IllegalArgumentException) {
            err.println("错误：${e.message}"); 2
        } catch (e: java.nio.file.AccessDeniedException) {
            err.println("错误：没有权限读写令牌文件 ${e.file}。请用 sudo xiaoman-tokens …（以服务用户运行）"); 1
        } catch (e: java.io.IOException) {
            err.println("错误：读写令牌文件失败（${e.javaClass.simpleName}）"); 1
        }
    }

    /** Marker for "option not given" (distinct from `none`, which means unlimited). */
    private object Unset
    private val NONE = setOf("none", "off", "无", "不限")
    private fun Any?.orDefault(default: Long): Long? = if (this === Unset) default else this as Long?

    private fun select(list: List<ClassToken>, selector: String): List<ClassToken> {
        val s = selector.trim()
        val compact = s.replace(Regex("\\s+"), "")
        val found = when {
            ClassTokenCodec.looksLikeToken(s) -> list.filter { it.hash == ClassTokenCodec.hash(s) }
            else -> list.filter { it.label == s }.ifEmpty { list.filter { it.label.replace(Regex("\\s+"), "") == compact } }
                .ifEmpty { if (s.startsWith(ClassTokenCodec.PREFIX) && s.length >= 4) list.filter { it.prefix.startsWith(s.take(6)) && s.take(6).length <= it.prefix.length } else emptyList() }
        }
        require(found.isNotEmpty()) { "找不到令牌：$s" }
        require(found.size == 1 || found.all { it.label == found[0].label }) { "「$s」对应多个令牌（${found.joinToString("、") { it.tag }}），请用标签或完整令牌" }
        return found
    }

    private fun expiry(days: String?, hours: String?, at: String?, zone: ZoneId, now: Long): Long? {
        if (listOfNotNull(days, hours, at).size > 1) throw Usage("--days、--hours、--expires 只能选一个")
        days?.let { return now + ((it.toDoubleOrNull()?.takeIf { d -> d > 0 } ?: throw Usage("--days 需要正数")) * 86_400_000).toLong() }
        hours?.let { return now + ((it.toDoubleOrNull()?.takeIf { h -> h > 0 } ?: throw Usage("--hours 需要正数")) * 3_600_000).toLong() }
        at?.let { v ->
            val text = v.trim().replace(' ', 'T')
            return runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
                ?: runCatching { LocalDateTime.parse(if (text.length == 16) "$text:00" else text).atZone(zone).toInstant().toEpochMilli() }.getOrNull()
                ?: throw Usage("--expires 格式：2026-11-08 22:00")
        }
        return null
    }
    private val display = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
    private fun time(ms: Long?, zone: ZoneId) = ms?.takeIf { it > 0 }?.let { display.format(Instant.ofEpochMilli(it).atZone(zone)) } ?: "-"
    private fun amount(v: Long?) = v?.toString() ?: "不限"
    private fun csvField(v: String) = if (v.any { it == ',' || it == '"' || it == '\n' }) "\"" + v.replace("\"", "\"\"") + "\"" else v
}
