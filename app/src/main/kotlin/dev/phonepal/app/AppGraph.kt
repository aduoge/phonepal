package dev.phonepal.app

import android.content.Context
import android.util.Log
import dev.phonepal.agent.PhonePalAgent
import dev.phonepal.api.Agent
import dev.phonepal.api.ModelRole
import dev.phonepal.app.session.AgentSession
import dev.phonepal.app.session.SessionTexts
import dev.phonepal.app.settings.AppSettings
import dev.phonepal.app.settings.SettingsStore
import dev.phonepal.app.trace.RecordingDevice
import dev.phonepal.app.trace.TraceWriter
import dev.phonepal.hands.AccessibilityDevice
import dev.phonepal.hands.MutableStopSignal
import dev.phonepal.hands.PhonePalAccessibilityService
import dev.phonepal.llm.LlmClientFactory
import dev.phonepal.llm.LlmClients
import dev.phonepal.memory.FileMemoryStore
import dev.phonepal.sentinel.PolicySentinel
import dev.phonepal.skills.DirectorySkillRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import java.io.File
import java.io.IOException

/**
 * Manual dependency container. Everything is built lazily; [start] runs once from
 * [PhonePalApp.onCreate].
 *
 * Folders (`<external files>` = `/sdcard/Android/data/dev.phonepal.app/files`):
 * `memory/`, `skills/` (user skills, override bundled ones), `traces/`; bundled skills are
 * extracted to `<files>/bundled-skills/`.
 */
class AppGraph(context: Context, private val llm: LlmClientFactory = LlmClients.default) {
    private val app: Context = context.applicationContext

    /** Process-wide scope; the session runs agent work here. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val externalRoot: File = app.getExternalFilesDir(null) ?: File(app.filesDir, "external")
    val memoryDir = File(externalRoot, "memory")
    val skillsDir = File(externalRoot, "skills")
    val tracesDir = File(externalRoot, "traces")
    val bundledSkillsDir = File(app.filesDir, "bundled-skills")

    val settings: SettingsStore by lazy { SettingsStore.create(app) }

    val memory by lazy { FileMemoryStore(memoryDir) }

    val skills by lazy { DirectorySkillRepository(listOf(bundledSkillsDir, skillsDir)) }

    val notifier by lazy { Notifier(app) }

    val reminderScheduler by lazy { AlarmReminderScheduler(app) }

    val runIds = RunIds()

    val traces by lazy {
        TraceWriter(
            root = tracesDir,
            secrets = { settings.current.secrets },
            log = { Log.i(TAG, it) },
            warn = { Log.w(TAG, it) },
        )
    }

    val accessibilityDevice by lazy { AccessibilityDevice({ PhonePalAccessibilityService.instance.value }, app) }

    private val device by lazy { RecordingDevice(accessibilityDevice, traces::saveScreenshot) }

    val sentinel by lazy { PolicySentinel(approvals = session, audit = traces, selfPackage = app.packageName) }

    val session: AgentSession by lazy {
        AgentSession(
            scope = scope,
            agent = ::agent,
            runIds = runIds,
            stopSignal = MutableStopSignal(),
            trace = traces,
            onTaskEnd = { sentinel.endTask(it) },
            texts = SessionTexts(
                notConfigured = app.getString(R.string.session_not_configured),
                busy = app.getString(R.string.session_busy),
                stopped = app.getString(R.string.session_stopped),
                approvedOnce = { app.getString(R.string.approval_resolved_allowed, it) },
                approvedTask = { app.getString(R.string.approval_resolved_task, it) },
                denied = { app.getString(R.string.approval_resolved_denied, it) },
            ),
            ready = { startup.await() },
        )
    }

    /** Bundled skills extracted and skills loaded once. */
    lateinit var startup: Deferred<Unit>
        private set

    private var cachedAgent: Pair<AppSettings, Agent>? = null

    /** The agent for the current settings, rebuilt when they change; null until both models are configured. */
    @Synchronized
    fun agent(): Agent? {
        val current = settings.current
        if (!current.configured) return null
        cachedAgent?.let { (s, agent) -> if (s == current) return agent }
        val agent = PhonePalAgent(
            chat = llm.create(current.chat.toModelConfig(ModelRole.CHAT)),
            operator = llm.create(current.operator.toModelConfig(ModelRole.OPERATOR)),
            device = device,
            memory = memory,
            skills = skills,
            sentinel = sentinel,
            users = session,
            reminders = reminderScheduler,
            config = current.agentConfig(),
            idFactory = runIds::next,
        )
        cachedAgent = current to agent
        return agent
    }

    fun start() {
        startup = scope.async(Dispatchers.IO) { prepareSkills() }
        notifier.createChannels()
        OverlayController(session, accessibilityDevice).start(scope)
        scope.launch(Dispatchers.Main.immediate) {
            session.busy.collect { busy -> if (busy) AgentService.start(app) }
        }
        scope.launch(Dispatchers.Main.immediate) { session.approval.collect(notifier::showApproval) }
        scope.launch(Dispatchers.Main.immediate) { session.question.collect(notifier::showQuestion) }
    }

    private suspend fun prepareSkills() {
        try {
            val info = app.packageManager.getPackageInfo(app.packageName, 0)
            val version = "${BuildConfig.VERSION_CODE}-${info.lastUpdateTime}"
            if (BundledSkills.extract(app.assets, bundledSkillsDir, version)) Log.i(TAG, "skills: bundled skills extracted ($version)")
            skillsDir.mkdirs()
            memoryDir.mkdirs()
        } catch (e: IOException) {
            Log.w(TAG, "skills: cannot extract bundled skills: ${e.message}")
        }
        val result = skills.reload()
        logSkillProblems(result.skills.size, result.problems)
    }

    fun logSkillProblems(count: Int, problems: List<dev.phonepal.api.SkillProblem>) {
        Log.i(TAG, "reload-skills: $count skills, ${problems.size} problems")
        problems.forEach { p -> Log.w(TAG, "reload-skills: problem ${p.source}${p.line?.let { ":$it" }.orEmpty()} ${p.message}") }
    }

    companion object {
        const val TAG = "PhonePal"
    }
}
