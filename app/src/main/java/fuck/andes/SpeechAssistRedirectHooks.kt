package fuck.andes

import android.content.Intent
import android.os.Binder
import fuck.andes.config.Prefs
import io.github.libxposed.api.XposedModule

/**
 * 平板小白条真实链路：长按导航条最终由 framework 的 ActivityStarter.execute()
 * 启动 com.heytap.speechassist 的 GlobalOpenServiceActivity（小布语音助手）。
 * 与 OplusSpeechHandler / OplusOcrScreenBusiness 无关，故在此直接拦截 activity 启动，
 * 改为触发 Google 一圈即搜。
 *
 * 注意：只拦截小白条长按的专属入口 GlobalOpenServiceActivity，不拦截整个
 * com.heytap.speechassist 包，否则用户在设置里正常打开“小布助手”也会被错误重定向。
 */
internal object SpeechAssistRedirectHooks {
    private const val SPEECH_ASSIST_PACKAGE = "com.heytap.speechassist"

    // 小白条长按（导航条手势）专属入口的类名后缀，并非小布助手主界面。
    private const val GESTURE_BAR_ACTIVITY_SUFFIX = "GlobalOpenServiceActivity"

    fun install(module: XposedModule, logger: ModuleLogger, classLoader: ClassLoader) {
        val starterClass = HookSupport.findClassOrNull(
            classLoader,
            "com.android.server.wm.ActivityStarter"
        )
        val executeMethod = starterClass?.let { HookSupport.findMethod(it, "execute") }
        if (executeMethod == null) {
            logger.warn("未找到 ActivityStarter.execute()")
            return
        }
        HookSupport.hookMethod(module, logger, executeMethod, "ActivityStarter.execute") { chain ->
            val intent = resolveIntent(chain.getThisObject())
            val pkg = intent?.component?.packageName
            val cls = intent?.component?.className
            val isGestureBarEntry = pkg == SPEECH_ASSIST_PACKAGE &&
                cls != null && cls.endsWith(GESTURE_BAR_ACTIVITY_SUFFIX)
            if (!isGestureBarEntry) {
                return@hookMethod chain.proceed()
            }
            if (!Prefs.isEnabled(Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH)) {
                return@hookMethod chain.proceed()
            }
            // ActivityStarter.execute 运行在 speechassist 的 Binder 上下文里，
            // 直接调用 CTS binder 会把 callingUid 带上 10131，触发权限拒绝。
            // 先清空身份切回 system(1000)，让 CTS 权限检查放行。
            val token = Binder.clearCallingIdentity()
            try {
                if (CircleToSearchInvoker.trigger(logger, "System")) {
                    logger.info("已拦截小白条长按 $cls，改为 Google 一圈即搜")
                    return@hookMethod null
                }
            } finally {
                Binder.restoreCallingIdentity(token)
            }
            logger.info("Google 一圈即搜触发失败，回退 $cls")
            chain.proceed()
        }
    }

    private fun resolveIntent(starter: Any): Intent? {
        val request = HookSupport.getFieldValue(starter, "mRequest") ?: return null
        return HookSupport.getFieldValue(request, "intent") as? Intent
    }
}
