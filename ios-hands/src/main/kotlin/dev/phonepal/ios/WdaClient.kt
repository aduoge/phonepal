package dev.phonepal.ios

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import java.io.IOException
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration

/** WDA failure without request bodies or potentially sensitive response messages. */
class WdaException(
    val endpoint: String,
    val statusCode: Int,
    val error: String? = null,
    cause: Throwable? = null,
) : IOException("WDA $endpoint: HTTP $statusCode" + (error?.let { " ($it)" } ?: ""), cause)

/** HTTP transport with a lazy session and one retry after an invalid session. */
class WdaClient(
    baseUrl: String = "http://127.0.0.1:8100",
    timeoutMillis: Long = 30_000,
) {
    private val base = baseUrl.trimEnd('/')
    private val timeout = Duration.ofMillis(timeoutMillis.also { require(it > 0) })
    private val http = HttpClient.newBuilder()
        .version(HttpClient.Version.HTTP_1_1)
        .connectTimeout(timeout)
        .build()
    private val mutex = Mutex()
    private var sessionId: String? = null

    suspend fun status(): JsonElement = request("GET", { "/status" })
    suspend fun windowSize(): JsonObject = request("GET", { "/session/$it/window/size" }, scoped = true).jsonObject
    suspend fun screenshot(): String = request("GET", { "/screenshot" }).jsonPrimitive.content
    suspend fun source(): String = request("GET", { "/source?format=xml" }).jsonPrimitive.content
    suspend fun activeAppInfo(): JsonObject = request("GET", { "/wda/activeAppInfo" }).jsonObject
    suspend fun actions(body: JsonObject) { request("POST", { "/session/$it/actions" }, body, true) }
    suspend fun keys(value: List<String>) {
        request("POST", { "/session/$it/wda/keys" }, buildJsonObject {
            put("value", JsonArray(value.map(::JsonPrimitive)))
        }, true)
    }
    suspend fun focusedElement(): String? {
        val elements = request("POST", { "/session/$it/elements" }, buildJsonObject {
            put("using", "predicate string")
            put("value", "hasKeyboardFocus == 1")
        }, true) as? JsonArray
        val element = elements?.firstOrNull() as? JsonObject ?: return null
        return sequenceOf("ELEMENT", "element-6066-11e4-a52e-4f735466cecf")
            .mapNotNull { (element[it] as? JsonPrimitive)?.contentOrNull }
            .firstOrNull { it.isNotBlank() }
    }
    suspend fun clear(elementId: String) {
        request("POST", { "/session/$it/element/$elementId/clear" }, buildJsonObject {}, true)
    }
    suspend fun setValue(elementId: String, text: String) {
        // Preserve supplementary Unicode characters as individual keys.
        val characters = text.codePoints().toArray().map { JsonPrimitive(String(Character.toChars(it))) }
        request("POST", { "/session/$it/element/$elementId/value" }, buildJsonObject {
            put("value", JsonArray(characters))
        }, true)
    }
    suspend fun home() { request("POST", { "/wda/homescreen" }, buildJsonObject {}) }
    suspend fun launchApp(bundleId: String) {
        request("POST", { "/session/$it/wda/apps/launch" }, buildJsonObject { put("bundleId", bundleId) }, true)
    }
    suspend fun openDeeplink(url: String) {
        request("POST", { "/session/$it/url" }, buildJsonObject { put("url", url) }, true)
    }

    private suspend fun request(
        method: String,
        path: (String?) -> String,
        body: JsonObject? = null,
        scoped: Boolean = false,
    ): JsonElement = withContext(Dispatchers.IO) {
        mutex.withLock {
            var retried = false
            while (true) {
                val sid = if (scoped) session() else sessionId
                try {
                    return@withLock send(method, path(sid), body)["value"] ?: JsonNull
                } catch (e: WdaException) {
                    if (retried || e.statusCode != 404 || e.error != "invalid session id") throw e
                    sessionId = null
                    session()
                    retried = true
                }
            }
            @Suppress("UNREACHABLE_CODE")
            JsonNull
        }
    }

    private fun session(): String {
        sessionId?.let { return it }
        val response = send("POST", "/session", buildJsonObject {
            put("capabilities", buildJsonObject { put("alwaysMatch", buildJsonObject {}) })
        })
        val id = (response["sessionId"] as? JsonPrimitive)?.contentOrNull
            ?: (response["value"] as? JsonObject)?.get("sessionId")?.jsonPrimitive?.contentOrNull
            ?: throw WdaException("/session", 200, "missing session id")
        if (id.isBlank()) throw WdaException("/session", 200, "empty session id")
        sessionId = id
        return id
    }

    private fun send(method: String, endpoint: String, body: JsonObject?): JsonObject {
        val response = try {
            val builder = HttpRequest.newBuilder(URI.create(base + endpoint)).timeout(timeout)
                .header("Accept", "application/json")
            if (method == "POST") builder.header("Content-Type", "application/json")
            builder.method(method, body?.let { HttpRequest.BodyPublishers.ofString(it.toString()) }
                ?: HttpRequest.BodyPublishers.noBody())
            http.send(builder.build(), HttpResponse.BodyHandlers.ofString())
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw WdaException(endpoint, 0, "interrupted", e)
        } catch (e: IOException) {
            throw WdaException(endpoint, 0, "transport failure", e)
        }
        val json = try {
            Json.parseToJsonElement(response.body()).jsonObject
        } catch (e: IllegalArgumentException) {
            throw WdaException(endpoint, response.statusCode(), "invalid JSON", e)
        }
        val error = ((json["value"] as? JsonObject)?.get("error") as? JsonPrimitive)?.contentOrNull
        if (response.statusCode() !in 200..299 || error != null) {
            throw WdaException(endpoint, response.statusCode(), error)
        }
        return json
    }
}
