package dev.xiaoman.app

import android.content.res.AssetManager
import java.io.File

/** Copies the APK's `assets/skills/<name>/...` (from `skills/examples/`) to a folder the skill repository reads. */
object BundledSkills {
    const val ASSET_DIR = "skills"
    private const val MARKER = ".version"

    /** Re-extracts into [target] when [version] differs from the last extraction. Returns true when it copied. */
    fun extract(assets: AssetManager, target: File, version: String): Boolean {
        val marker = File(target, MARKER)
        if (marker.isFile && marker.readText() == version) return false
        target.deleteRecursively()
        target.mkdirs()
        for (name in assets.list(ASSET_DIR).orEmpty()) copy(assets, "$ASSET_DIR/$name", File(target, name))
        marker.writeText(version)
        return true
    }

    private fun copy(assets: AssetManager, path: String, dest: File) {
        val children = assets.list(path).orEmpty()
        if (children.isEmpty()) {
            dest.parentFile?.mkdirs()
            assets.open(path).use { input -> dest.outputStream().use { input.copyTo(it) } }
        } else {
            dest.mkdirs()
            for (child in children) copy(assets, "$path/$child", File(dest, child))
        }
    }
}
