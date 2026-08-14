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
 */
internal object SpeechAssistRedirectHooks {
    private const val SPEECH_ASSIST_PACKAGE = "com.heytap.speechassist"
    private const val ASSISTANT_SCREEN_PACKAGE = "com.coloros.assistantscreen"

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
            if (pkg != SPEECH_ASSIST_PACKAGE && pkg != ASSISTANT_SCREEN_PACKAGE) {
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
                    logger.info("已拦截 $pkg 启动，改为 Google 一圈即搜")
                    return@hookMethod null
                }
            } finally {
                Binder.restoreCallingIdentity(token)
            }
            logger.info("Google 一圈即搜触发失败，回退 $pkg")
            chain.proceed()
        }
    }

    private fun resolveIntent(starter: Any): Intent? {
        val request = HookSupport.getFieldValue(starter, "mRequest") ?: return null
        return HookSupport.getFieldValue(request, "intent") as? Intent
    }
}
