package dev.xiaoman.ios

import dev.xiaoman.api.AppInfo

/** iPhone app identifiers and guidance visible to the operator. */
object IosPlatform {
    /** Default `WDA_BUNDLE_ID` of scripts/ios/wda.sh; Xcode installs the runner as `<id>.xctrunner`. */
    const val DEFAULT_WDA_BUNDLE_ID = "com.xiaoman.WebDriverAgentRunner"
    const val WDA_BUNDLE_ID = "$DEFAULT_WDA_BUNDLE_ID.xctrunner"

    /** Bundle id of the installed runner for a custom `WDA_BUNDLE_ID`. */
    fun runnerBundleId(wdaBundleId: String?): String =
        (wdaBundleId?.trim()?.takeIf { it.isNotEmpty() } ?: DEFAULT_WDA_BUNDLE_ID) + ".xctrunner"
    val LAUNCHER_BUNDLES = setOf("com.apple.springboard")
    val DEFAULT_APPS = listOf(
        AppInfo("com.apple.Preferences", "设置"),
        AppInfo("com.apple.mobileslideshow", "照片"),
        AppInfo("com.apple.reminders", "提醒事项"),
        AppInfo("com.apple.mobilenotes", "备忘录"),
        AppInfo("com.apple.mobilesafari", "Safari 浏览器"),
        AppInfo("com.apple.mobiletimer", "时钟"),
        AppInfo("com.apple.mobilecal", "日历"),
        AppInfo("com.taobao.taobao4iphone", "淘宝"),
        AppInfo("com.tencent.xin", "微信"),
        AppInfo("com.alipay.iphoneclient", "支付宝"),
        AppInfo("com.autonavi.amap", "高德地图"),
    )
    val OPERATOR_NOTES = """
        这是 iPhone。没有系统返回键，back 会点击左上角返回按钮或从左边缘向右滑动。
        home 回到主屏幕。launch 的 packageName 填 iOS bundleId：
        ${DEFAULT_APPS.joinToString("、") { "${it.label}=${it.packageName}" }}。
        节点 bounds 和屏幕尺寸的单位是 iOS 点；动作 point 仍使用 0..1000 归一化坐标。
        系统权限弹窗可能不在节点列表，需要看截图并用 point 点击。
        不要尝试打开 WebDriverAgent（WebDriverAgentRunner）。
    """.trimIndent()
}
