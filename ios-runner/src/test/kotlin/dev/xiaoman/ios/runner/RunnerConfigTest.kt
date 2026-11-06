package dev.xiaoman.ios.runner

import dev.xiaoman.ios.IosPlatform
import java.io.File
import java.nio.file.Files
import kotlin.test.*

class RunnerConfigTest {
    private val home: File = Files.createTempDirectory("runner-config").toFile()

    @AfterTest fun cleanup() { home.deleteRecursively() }

    @Test fun mergesArgsEnvAndPropertiesWithoutLeakingKeys() {
        File(home, "config.properties").writeText(
            "chat.model=doubao-chat\noperator.model=file-operator\nchat.apiKey=sk-in-file\n", Charsets.UTF_8)
        val env = mapOf(
            "XIAOMAN_HOME" to home.path,
            "XIAOMAN_OPERATOR_MODEL" to "doubao-vision",
            "ARK_API_KEY" to "ark-secret-123",
            "XIAOMAN_WDA_URL" to "http://10.0.0.2:8100",
        )
        val config = RunnerConfig.parse(listOf("task", "打开设置", "--skills", "/repo/skills/ios", "--skill", "open-settings-wifi",
            "--max-steps", "12", "--non-interactive"), env)
        assertEquals("task", config.command)
        assertEquals("打开设置", config.goal)
        assertEquals("open-settings-wifi", config.skill)
        assertEquals(12, config.maxSteps)
        assertTrue(config.nonInteractive)
        assertEquals("http://10.0.0.2:8100", config.wdaUrl)
        assertEquals("doubao-chat", config.chat.model)
        assertEquals("doubao-vision", config.operator.model)
        assertEquals(RunnerConfig.DEFAULT_BASE_URL, config.operator.baseUrl)
        assertEquals("ark-secret-123", config.chat.apiKey)
        assertEquals("ark-secret-123", config.operator.apiKey)
        assertEquals(listOf(File("/repo/skills/ios"), File(home, "skills")), config.skillDirs)
        assertEquals(1, config.warnings.size)
        assertTrue("chat.apiKey" in config.warnings.single())
        assertNull(config.missingModels())
        assertFalse("ark-secret-123" in config.toString())
        assertFalse("sk-in-file" in config.toString())
    }

    @Test fun explicitArgsWinAndSpecificKeysBeatSharedKey() {
        val env = mapOf("XIAOMAN_HOME" to home.path, "XIAOMAN_WDA_URL" to "http://env:8100",
            "ARK_API_KEY" to "shared", "XIAOMAN_CHAT_API_KEY" to "chat-only")
        val config = RunnerConfig.parse(listOf("check", "--wda", "http://arg:8100"), env)
        assertEquals("http://arg:8100", config.wdaUrl)
        assertEquals("chat-only", config.chat.apiKey)
        assertEquals("shared", config.operator.apiKey)
    }

    @Test fun wdaBundleIdFollowsTheScript() {
        val default = RunnerConfig.parse(listOf("check"), mapOf("XIAOMAN_HOME" to home.path))
        assertEquals("com.xiaoman.WebDriverAgentRunner.xctrunner", IosPlatform.runnerBundleId(default.wdaBundleId))
        val custom = RunnerConfig.parse(listOf("check"), mapOf("XIAOMAN_HOME" to home.path, "WDA_BUNDLE_ID" to "com.ryan.wda"))
        assertEquals("com.ryan.wda.xctrunner", IosPlatform.runnerBundleId(custom.wdaBundleId))
    }

    @Test fun missingModelsExplainsWithoutKeys() {
        val config = RunnerConfig.parse(listOf("chat", "--home", home.path), mapOf("XIAOMAN_CHAT_API_KEY" to "only-chat-key"))
        val message = assertNotNull(config.missingModels())
        assertTrue("chat.model" in message && "operator.model" in message && "XIAOMAN_OPERATOR_API_KEY" in message)
        assertFalse("only-chat-key" in message)
    }

    @Test fun rejectsBadUsage() {
        val env = mapOf("XIAOMAN_HOME" to home.path)
        assertFailsWith<UsageException> { RunnerConfig.parse(listOf("fly"), env) }
        assertFailsWith<UsageException> { RunnerConfig.parse(listOf("task"), env) }
        assertFailsWith<UsageException> { RunnerConfig.parse(listOf("dump"), env) }
        assertFailsWith<UsageException> { RunnerConfig.parse(listOf("check", "--max-steps", "0"), env) }
        assertFailsWith<UsageException> { RunnerConfig.parse(listOf("check", "--wda"), env) }
        assertFailsWith<UsageException> { RunnerConfig.parse(listOf("check", "--bogus"), env) }
    }
}
