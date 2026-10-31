package dev.phonepal.app.settings

import dev.phonepal.api.AgentConfig
import dev.phonepal.api.ModelConfig
import dev.phonepal.api.ModelRole

/** One OpenAI-compatible endpoint as typed into Settings. [toString] never shows [apiKey]. */
data class ModelSettings(
    val baseUrl: String = DEFAULT_BASE_URL,
    val model: String = "",
    val apiKey: String = "",
    val temperature: Double? = null,
) {
    val configured: Boolean get() = baseUrl.isNotBlank() && model.isNotBlank() && apiKey.isNotBlank()

    fun toModelConfig(role: ModelRole) = ModelConfig(
        role = role,
        baseUrl = baseUrl.trim(),
        model = model.trim(),
        apiKey = apiKey.trim(),
        temperature = temperature,
    )

    override fun toString() =
        "ModelSettings(baseUrl=$baseUrl, model=$model, apiKey=${if (apiKey.isEmpty()) "<unset>" else "***"}, temperature=$temperature)"

    companion object {
        /** Volcano Ark. */
        const val DEFAULT_BASE_URL = "https://ark.cn-beijing.volces.com/api/v3"
    }
}

data class AppSettings(
    val chat: ModelSettings = ModelSettings(),
    val operator: ModelSettings = ModelSettings(),
    val maxSteps: Int = AgentConfig().maxSteps,
    val learnFromSuccess: Boolean = AgentConfig().learnFromSuccess,
) {
    val configured: Boolean get() = chat.configured && operator.configured

    /** Values to mask in traces and logs. */
    val secrets: List<String> get() = listOf(chat.apiKey, operator.apiKey).filter { it.isNotBlank() }

    fun agentConfig() = AgentConfig(maxSteps = maxSteps, learnFromSuccess = learnFromSuccess)

    companion object {
        val MAX_STEPS_RANGE = 1..200
    }
}

/**
 * The editable text of the Settings screen. Parsing lives here, not in the Composable.
 */
data class SettingsForm(
    val chat: ModelForm = ModelForm(),
    val operator: ModelForm = ModelForm(),
    val maxSteps: String = AgentConfig().maxSteps.toString(),
    val learnFromSuccess: Boolean = true,
) {
    data class ModelForm(
        val baseUrl: String = ModelSettings.DEFAULT_BASE_URL,
        val model: String = "",
        val apiKey: String = "",
        val temperature: String = "",
    ) {
        val temperatureValid: Boolean get() = parseTemperature(temperature) != null || temperature.isBlank()

        fun toSettings(): ModelSettings? {
            val t = if (temperature.isBlank()) null else parseTemperature(temperature) ?: return null
            return ModelSettings(baseUrl.trim(), model.trim(), apiKey.trim(), t)
        }

        override fun toString() =
            "ModelForm(baseUrl=$baseUrl, model=$model, apiKey=${if (apiKey.isEmpty()) "<unset>" else "***"}, temperature=$temperature)"

        companion object {
            fun of(s: ModelSettings) = ModelForm(s.baseUrl, s.model, s.apiKey, s.temperature?.toString().orEmpty())
        }
    }

    val maxStepsValid: Boolean get() = maxSteps.trim().toIntOrNull()?.let { it in AppSettings.MAX_STEPS_RANGE } == true

    /** Null while a field is invalid. */
    fun toSettings(): AppSettings? {
        val steps = maxSteps.trim().toIntOrNull()?.takeIf { it in AppSettings.MAX_STEPS_RANGE } ?: return null
        return AppSettings(chat.toSettings() ?: return null, operator.toSettings() ?: return null, steps, learnFromSuccess)
    }

    companion object {
        fun of(s: AppSettings) = SettingsForm(ModelForm.of(s.chat), ModelForm.of(s.operator), s.maxSteps.toString(), s.learnFromSuccess)

        fun parseTemperature(text: String): Double? = text.trim().toDoubleOrNull()?.takeIf { it in 0.0..2.0 }
    }
}
