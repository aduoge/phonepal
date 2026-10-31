package dev.phonepal.skills

import dev.phonepal.api.Skill
import dev.phonepal.api.SkillLoadResult
import dev.phonepal.api.SkillProblem
import dev.phonepal.api.SkillRepository
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

/**
 * Loads skills from folders `<root>/<name>/SKILL.md` (+ optional `flow.yaml`).
 *
 * Later [roots] override earlier ones by skill name, so pass bundled skills first and user skills
 * last. Missing roots are skipped silently (the user folder may not exist yet). A broken skill is
 * reported in [SkillLoadResult.problems] and never stops the others from loading; when a user skill
 * is broken, a bundled skill of the same name stays in place.
 *
 * [list] and [get] load lazily on first use; [reload] rescans. Safe to call from any thread.
 */
class DirectorySkillRepository(
    private val roots: List<File>,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : SkillRepository {
    private val mutex = Mutex()

    @Volatile
    private var state: State? = null

    override suspend fun reload(): SkillLoadResult = mutex.withLock { load() }.result

    override suspend fun list(): List<Skill> = loaded().result.skills

    override suspend fun get(name: String): Skill? = loaded().byName[name]

    private suspend fun loaded(): State = state ?: mutex.withLock { state ?: load() }

    /** Must hold [mutex]. */
    private suspend fun load(): State = withContext(io) {
        val merged = LinkedHashMap<String, Skill>()
        val problems = mutableListOf<SkillProblem>()
        for (root in roots) {
            val (skills, rootProblems) = loadRoot(root)
            problems += rootProblems
            skills.forEach { merged[it.name] = it }
        }
        val skills = merged.values.sortedBy { it.name }
        State(SkillLoadResult(skills, problems), skills.associateBy { it.name })
    }.also { state = it }

    private class State(val result: SkillLoadResult, val byName: Map<String, Skill>)

    companion object {
        /** Loads one root without overrides; duplicate skill names inside it are problems. */
        fun loadRoot(root: File): SkillLoadResult {
            if (!root.exists()) return SkillLoadResult(emptyList(), emptyList())
            val folders = root.listFiles()
                ?: return SkillLoadResult(emptyList(), listOf(SkillProblem(root.path, "not a readable directory")))
            val parsed = mutableListOf<Pair<File, Skill>>()
            val problems = mutableListOf<SkillProblem>()
            for (folder in folders.filter { it.isDirectory && !it.name.startsWith(".") }.sortedBy { it.name }) {
                val skillFile = File(folder, "SKILL.md")
                if (!skillFile.isFile) {
                    problems += SkillProblem(folder.path, "missing SKILL.md")
                    continue
                }
                val flowFile = File(folder, "flow.yaml")
                val (skill, skillProblems) = try {
                    SkillParser.parseFolder(
                        name = folder.name,
                        skillMd = skillFile.readText(),
                        flowYaml = if (flowFile.isFile) flowFile.readText() else null,
                        source = folder.path,
                    )
                } catch (e: IOException) {
                    null to listOf(SkillProblem(folder.path, "cannot read: ${e.message}"))
                }
                problems += skillProblems
                if (skill != null) parsed += folder to skill
            }
            val skills = parsed.groupBy({ it.second.name }) { it }.map { (name, candidates) ->
                // Prefer the folder whose name matches; report the rest.
                val keep = candidates.firstOrNull { it.first.name == name } ?: candidates.first()
                for ((folder, _) in candidates) if (folder != keep.first) {
                    problems += SkillProblem(folder.path, "duplicate skill name '$name' (also in ${keep.first.path}); ignored")
                }
                keep.second
            }
            return SkillLoadResult(skills, problems)
        }
    }
}
