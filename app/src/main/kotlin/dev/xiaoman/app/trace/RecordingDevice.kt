package dev.xiaoman.app.trace

import dev.xiaoman.api.Action
import dev.xiaoman.api.ActionResult
import dev.xiaoman.api.AppInfo
import dev.xiaoman.api.Device
import dev.xiaoman.api.ScreenState
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A [Device] that hands every observed screenshot to [record] (the trace's `step-<n>.png`). */
class RecordingDevice(
    private val delegate: Device,
    private val record: (ByteArray) -> Unit,
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : Device {
    override suspend fun observe(): ScreenState {
        val screen = delegate.observe()
        screen.screenshotPng?.let { png -> withContext(io) { record(png) } }
        return screen
    }

    override suspend fun perform(action: Action): ActionResult = delegate.perform(action)

    override suspend fun installedApps(): List<AppInfo> = delegate.installedApps()
}
