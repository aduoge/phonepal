package dev.phonepal.llm

import dev.phonepal.api.LlmClient
import dev.phonepal.api.ModelConfig
import okhttp3.OkHttpClient

/** Builds an [LlmClient] for a configured model. */
fun interface LlmClientFactory {
    fun create(config: ModelConfig): LlmClient
}

object LlmClients {
    /** One connection pool for all clients; per-model timeouts are applied on top of it. */
    val sharedHttp: OkHttpClient by lazy { OkHttpClient() }

    /** Creates [OpenAiCompatibleClient]s that share [http]. */
    fun factory(http: OkHttpClient = sharedHttp, maxRetries: Int = 2): LlmClientFactory =
        LlmClientFactory { OpenAiCompatibleClient(it, http, maxRetries) }

    val default: LlmClientFactory by lazy { factory() }
}
