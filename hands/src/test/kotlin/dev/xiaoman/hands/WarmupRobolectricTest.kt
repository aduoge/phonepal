package dev.xiaoman.hands

import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.test.assertNotNull

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WarmupRobolectricTest {
    @Test fun context() { assertNotNull(RuntimeEnvironment.getApplication()) }
}
