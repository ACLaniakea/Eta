package fuck.andes

import android.content.Context
import android.content.res.Resources
import android.os.SystemClock
import android.view.MotionEvent
import fuck.andes.config.Prefs
import io.github.libxposed.api.XposedModule
import java.lang.reflect.Method

/**
 * OnePlus/ColorOS tablets route the desktop taskbar handle through Launcher
 * Quickstep's CUI consumer, rather than SystemUI's OCR long-press business.
 *
 * Keep this in a separate launcher-only hook group so phones continue to use
 * the original SystemUI path without an additional Quickstep dependency.
 */
internal object LauncherTaskbarHooks {
    private const val CENTER_HANDLE_FRACTION = 0.20f
    private const val TRIGGER_DEDUP_WINDOW_MS = 1_000L

    @Volatile
    private var lastTriggerUptime = 0L

    fun install(module: XposedModule, logger: ModuleLogger, classLoader: ClassLoader) {
        val companionClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.CUI_COMPANION_CLASS)
        val listenerClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.CUI_GESTURE_LISTENER_CLASS)
        val deviceStateClass = HookSupport.findClassOrNull(classLoader, ModuleConfig.CUI_DEVICE_STATE_CLASS)

        if (companionClass == null || listenerClass == null || deviceStateClass == null) {
            logger.warn("Launcher: 未找到平板 Taskbar CUI 类，保留原有小白条逻辑")
            return
        }

        hookCuiEligibility(module, logger, companionClass, deviceStateClass)
        hookCuiStartService(module, logger, companionClass)
        hookCuiLongPress(module, logger, listenerClass)
        hookCuiPressOverlay(module, logger, listenerClass)
    }

    private fun hookCuiEligibility(
        module: XposedModule,
        logger: ModuleLogger,
        companionClass: Class<*>,
        deviceStateClass: Class<*>
    ) {
        val method = HookSupport.findMethod(
            companionClass,
            "canTriggerCui",
            MotionEvent::class.java,
            Int::class.javaPrimitiveType!!,
            Int::class.javaPrimitiveType!!,
            deviceStateClass
        ) ?: run {
            logger.warn("Launcher: 未找到 OplusCuiInputConsumer.Companion.canTriggerCui()")
            return
        }

        HookSupport.hookMethod(module, logger, method, "Launcher CUI canTriggerCui") { chain ->
            val original = chain.proceed()
            if (original == true || !Prefs.isEnabled(Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH)) {
                return@hookMethod original
            }
            val event = chain.getArgs().getOrNull(0) as? MotionEvent ?: return@hookMethod original
            if (event.actionMasked != MotionEvent.ACTION_DOWN) return@hookMethod original

            val width = Resources.getSystem().displayMetrics.widthPixels
            val inCenterHandle = width > 0 &&
                kotlin.math.abs(event.x - width / 2f) <= width * CENTER_HANDLE_FRACTION
            if (inCenterHandle) true else original
        }
    }

    private fun hookCuiStartService(
        module: XposedModule,
        logger: ModuleLogger,
        companionClass: Class<*>
    ) {
        val method = HookSupport.findMethod(
            companionClass,
            "startService",
            Context::class.java,
            Int::class.javaPrimitiveType!!
        ) ?: run {
            logger.warn("Launcher: 未找到 OplusCuiInputConsumer.Companion.startService()")
            return
        }

        HookSupport.hookMethod(module, logger, method, "Launcher CUI startService") { chain ->
            if (!Prefs.isEnabled(Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH)) {
                return@hookMethod chain.proceed()
            }
            val args = chain.getArgs()
            val context = args.getOrNull(0) as? Context
            val type = args.getOrNull(1) as? Int
            if (type == ModuleConfig.CUI_SERVICE_TYPE && dispatchCircleToSearch(context, logger)) {
                null
            } else {
                chain.proceed()
            }
        }
    }

    private fun hookCuiLongPress(module: XposedModule, logger: ModuleLogger, listenerClass: Class<*>) {
        val method = HookSupport.findMethod(listenerClass, "onLongPress", MotionEvent::class.java)
            ?: run {
                logger.warn("Launcher: 未找到 CUI gesture listener.onLongPress()")
                return
            }

        HookSupport.hookMethod(module, logger, method, "Launcher CUI onLongPress") { chain ->
            val consumer = HookSupport.getFieldValue(chain.getThisObject(), "this\$0")
            val context = consumer?.let(::resolveContext)
            if (Prefs.isEnabled(Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH) &&
                dispatchCircleToSearch(context, logger)
            ) {
                null
            } else {
                chain.proceed()
            }
        }
    }

    private fun hookCuiPressOverlay(module: XposedModule, logger: ModuleLogger, listenerClass: Class<*>) {
        val method = HookSupport.findMethod(listenerClass, "onShowPress", MotionEvent::class.java)
            ?: return

        HookSupport.hookMethod(module, logger, method, "Launcher CUI onShowPress") { chain ->
            val consumer = HookSupport.getFieldValue(chain.getThisObject(), "this\$0")
            val context = consumer?.let(::resolveContext)
            if (Prefs.isEnabled(Prefs.Keys.GESTURE_BAR_CIRCLE_TO_SEARCH) &&
                isCircleToSearchReady(context, logger)
            ) {
                // Do not show ColorOS's full-screen CUI/OCR animation before Eta starts CTS.
                null
            } else {
                chain.proceed()
            }
        }
    }

    private fun resolveContext(target: Any): Context? =
        HookSupport.getFieldValue(target, "context") as? Context
            ?: HookSupport.getFieldValue(target, "mContext") as? Context
            ?: HookSupport.invokeNoArgs(target, "getContext") as? Context

    private fun isCircleToSearchReady(context: Context?, logger: ModuleLogger): Boolean =
        context != null && CircleToSearchInvoker.isAvailable(
            context,
            logger,
            "Launcher",
            "回退原始 Taskbar 长按"
        )

    private fun dispatchCircleToSearch(context: Context?, logger: ModuleLogger): Boolean {
        if (!isCircleToSearchReady(context, logger) || !tryConsumeTriggerWindow()) return false
        return CircleToSearchInvoker.trigger(logger, "Launcher Taskbar")
    }

    private fun tryConsumeTriggerWindow(): Boolean {
        val now = SystemClock.uptimeMillis()
        if (now - lastTriggerUptime < TRIGGER_DEDUP_WINDOW_MS) return false
        lastTriggerUptime = now
        return true
    }
}
