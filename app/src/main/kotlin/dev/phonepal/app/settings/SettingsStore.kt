package dev.phonepal.app.settings

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.core.content.edit
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Persists [AppSettings]: everything but the API keys in [plain], the keys only in [secret]
 * (EncryptedSharedPreferences in the app). Nothing here logs a key.
 */
class SettingsStore(private val plain: SharedPreferences, internal val secret: SharedPreferences) {

    private val _settings = MutableStateFlow(load())
    val settings: StateFlow<AppSettings> = _settings.asStateFlow()

    val current: AppSettings get() = _settings.value

    @Synchronized
    fun save(settings: AppSettings) {
        plain.edit(commit = true) {
            putModel(CHAT, settings.chat)
            putModel(OPERATOR, settings.operator)
            putInt(MAX_STEPS, settings.maxSteps)
            putBoolean(LEARN, settings.learnFromSuccess)
        }
        secret.edit(commit = true) {
            putString(CHAT + API_KEY, settings.chat.apiKey)
            putString(OPERATOR + API_KEY, settings.operator.apiKey)
        }
        _settings.value = settings
    }

    private fun load(): AppSettings {
        val defaults = AppSettings()
        return AppSettings(
            chat = model(CHAT),
            operator = model(OPERATOR),
            maxSteps = plain.getInt(MAX_STEPS, defaults.maxSteps),
            learnFromSuccess = plain.getBoolean(LEARN, defaults.learnFromSuccess),
        )
    }

    private fun model(prefix: String) = ModelSettings(
        baseUrl = plain.getString(prefix + BASE_URL, null) ?: ModelSettings.DEFAULT_BASE_URL,
        model = plain.getString(prefix + MODEL, null).orEmpty(),
        apiKey = secret.getString(prefix + API_KEY, null).orEmpty(),
        temperature = plain.getString(prefix + TEMPERATURE, null)?.toDoubleOrNull(),
    )

    private fun SharedPreferences.Editor.putModel(prefix: String, m: ModelSettings) {
        putString(prefix + BASE_URL, m.baseUrl)
        putString(prefix + MODEL, m.model)
        putString(prefix + TEMPERATURE, m.temperature?.toString())
    }

    companion object {
        private const val TAG = "PhonePal"
        private const val PLAIN_FILE = "settings"
        private const val SECRET_FILE = "settings-secrets"
        private const val CHAT = "chat."
        private const val OPERATOR = "operator."
        private const val BASE_URL = "baseUrl"
        private const val MODEL = "model"
        private const val API_KEY = "apiKey"
        private const val TEMPERATURE = "temperature"
        private const val MAX_STEPS = "agent.maxSteps"
        private const val LEARN = "agent.learnFromSuccess"

        fun create(context: Context): SettingsStore =
            SettingsStore(context.getSharedPreferences(PLAIN_FILE, Context.MODE_PRIVATE), encrypted(context))

        private fun encrypted(context: Context): SharedPreferences = try {
            openEncrypted(context)
        } catch (e: Exception) {
            // A keystore entry lost after a restore or OS update makes the file undecryptable.
            // The keys cannot be recovered; start over so the user can type them again.
            Log.w(TAG, "settings: encrypted preferences unreadable (${e.javaClass.simpleName}); resetting API keys")
            context.deleteSharedPreferences(SECRET_FILE)
            openEncrypted(context)
        }

        private fun openEncrypted(context: Context): SharedPreferences {
            val key = MasterKey.Builder(context).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            return EncryptedSharedPreferences.create(
                context,
                SECRET_FILE,
                key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
            )
        }
    }
}
