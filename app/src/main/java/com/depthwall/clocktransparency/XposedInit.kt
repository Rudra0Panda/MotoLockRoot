package com.depthwall.clocktransparency

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Paint
import android.graphics.Typeface
import android.os.Build
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage
import java.lang.ref.WeakReference

class XposedInit : IXposedHookLoadPackage {

    companion object {
        private const val TAG = "LockscreenClockTransparency"
        private const val PKG_SYSTEMUI = "com.android.systemui"
        private const val PKG_DEPTH_EFFECT = "com.motorola.deptheffect"
        private const val PKG_PERSONALIZE = "com.motorola.personalize"

        @Volatile
        private var activeMotoClockFaceController: WeakReference<Any>? = null

        @Volatile
        private var activeDepthEffectSession: WeakReference<Any>? = null

        @Volatile
        private var isReceiverRegistered: Boolean = false

        fun log(msg: String) {
            XposedBridge.log("[$TAG] $msg")
            android.util.Log.i(TAG, msg)
        }

        fun applyTypefaceRecursively(view: View, typeface: Typeface, applyToDate: Boolean = false) {
            if (view is TextView) {
                val resName = runCatching {
                    if (view.id != View.NO_ID && view.id != 0) view.resources.getResourceEntryName(view.id) else ""
                }.getOrDefault("")

                val isDate = resName.contains("date", ignoreCase = true)
                if (!isDate || applyToDate) {
                    view.typeface = typeface
                    runCatching {
                        val paint = XposedHelpers.getObjectField(view, "lockscreenPaint") as? Paint
                        paint?.typeface = typeface
                    }
                    view.invalidate()
                }
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    applyTypefaceRecursively(view.getChildAt(i), typeface, applyToDate)
                }
            }
        }

        fun applyAlphaRecursively(view: View?, alpha: Float) {
            if (view == null) return
            if (view is TextView) {
                view.alpha = alpha
            }
            if (view is ViewGroup) {
                for (i in 0 until view.childCount) {
                    applyAlphaRecursively(view.getChildAt(i), alpha)
                }
            }
        }

        fun isClockView(view: View): Boolean {
            val resName = runCatching {
                if (view.id != View.NO_ID && view.id != 0) view.resources.getResourceEntryName(view.id) else ""
            }.getOrDefault("")
            val className = view.javaClass.name

            return resName.contains("clock", ignoreCase = true) ||
                   resName.contains("time", ignoreCase = true) ||
                   resName.contains("hour", ignoreCase = true) ||
                   resName.contains("minute", ignoreCase = true) ||
                   resName.contains("digit", ignoreCase = true) ||
                   resName.contains("num", ignoreCase = true) ||
                   className.contains("clock", ignoreCase = true) ||
                   className.contains("time", ignoreCase = true) ||
                   className.contains("StrokeTextView", ignoreCase = true)
        }

        fun registerSettingsReceiver(context: Context, onSettingsChanged: () -> Unit) {
            if (isReceiverRegistered) return
            runCatching {
                val receiver = object : BroadcastReceiver() {
                    override fun onReceive(c: Context?, intent: Intent?) {
                        log("Broadcast ${intent?.action} received in process")
                        c?.let { PreferencesHelper.reload(it) }
                        onSettingsChanged()
                    }
                }
                val filter = IntentFilter(PreferencesHelper.ACTION_SETTINGS_CHANGED)
                if (Build.VERSION.SDK_INT >= 33) {
                    context.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED)
                } else {
                    context.registerReceiver(receiver, filter)
                }
                isReceiverRegistered = true
                log("Successfully registered settings BroadcastReceiver in ${context.packageName}")
            }.onFailure {
                log("Failed to register settings BroadcastReceiver: ${it.message}")
            }
        }
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        when (lpparam.packageName) {
            PKG_DEPTH_EFFECT -> {
                log("Hooking Motorola Depth Wallpaper: ${lpparam.packageName}")
                initProcessHooks(lpparam)
                hookDepthEffect(lpparam.classLoader)
            }
            PKG_SYSTEMUI -> {
                log("Hooking SystemUI: ${lpparam.packageName}")
                initProcessHooks(lpparam)
                hookSystemUI(lpparam.classLoader)
            }
            PKG_PERSONALIZE -> {
                log("Hooking Motorola Personalize: ${lpparam.packageName}")
                initProcessHooks(lpparam)
                hookPersonalize(lpparam.classLoader)
            }
        }
    }

    private fun initProcessHooks(lpparam: XC_LoadPackage.LoadPackageParam) {
        // 1. Hook Application.onCreate to initialize context & receiver
        runCatching {
            XposedHelpers.findAndHookMethod(
                Application::class.java,
                "onCreate",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val app = param.thisObject as? Application ?: return
                        PreferencesHelper.setContext(app)
                        PreferencesHelper.reload(app)
                        registerSettingsReceiver(app) {
                            refreshActiveViews()
                        }
                    }
                }
            )
        }

        // 2. Intercept Typeface.createFromAsset globally for clock fonts
        runCatching {
            XposedBridge.hookAllMethods(
                Typeface::class.java,
                "createFromAsset",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!PreferencesHelper.isCustomFontEnabled) return
                        val tf = PreferencesHelper.customTypeface ?: return
                        val path = param.args.getOrNull(1) as? String ?: return
                        if (path.contains("font/", ignoreCase = true) ||
                            path.contains("moto", ignoreCase = true) ||
                            path.contains("clock", ignoreCase = true)) {
                            param.result = tf
                            log("createFromAsset($path) -> intercepted, returned custom Typeface")
                        }
                    }
                }
            )
            log("Successfully hooked Typeface.createFromAsset")
        }.onFailure {
            log("Typeface.createFromAsset hook failed: ${it.message}")
        }

        // 3. Intercept TextView.setTypeface to enforce custom font on clock views
        runCatching {
            XposedBridge.hookAllMethods(
                TextView::class.java,
                "setTypeface",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!PreferencesHelper.isCustomFontEnabled) return
                        val tf = PreferencesHelper.customTypeface ?: return
                        val tv = param.thisObject as? TextView ?: return

                        val resName = runCatching {
                            if (tv.id != View.NO_ID && tv.id != 0) viewIdName(tv) else ""
                        }.getOrDefault("")

                        val isClock = isClockView(tv)
                        val isDate = resName.contains("date", ignoreCase = true)

                        if (isClock || (isDate && PreferencesHelper.customFontApplyToDate)) {
                            if (param.args.isNotEmpty() && param.args[0] is Typeface?) {
                                param.args[0] = tf
                            }
                        }
                    }
                }
            )
            log("Successfully hooked TextView.setTypeface")
        }.onFailure {
            log("TextView.setTypeface hook failed: ${it.message}")
        }
    }

    private fun viewIdName(view: View): String {
        return runCatching {
            if (view.id != View.NO_ID && view.id != 0) view.resources.getResourceEntryName(view.id) else ""
        }.getOrDefault("")
    }

    private fun refreshActiveViews() {
        activeMotoClockFaceController?.get()?.let { controller ->
            applySystemUIClockSettings(controller)
        }
        activeDepthEffectSession?.get()?.let { session ->
            applyDepthEffectSettings(session)
        }
    }

    /**
     * Hook Motorola Depth Wallpaper clock face session
     */
    private fun hookDepthEffect(classLoader: ClassLoader) {
        // 1. Hook StrokeTextView.setTypeface directly
        runCatching {
            val strokeTextViewClass = XposedHelpers.findClass(
                "com.motorola.deptheffect.view.StrokeTextView",
                classLoader
            )

            XposedBridge.hookAllMethods(
                strokeTextViewClass,
                "setTypeface",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        val tf = PreferencesHelper.customTypeface ?: return
                        if (PreferencesHelper.isCustomFontEnabled && param.args.isNotEmpty()) {
                            param.args[0] = tf
                            log("StrokeTextView.setTypeface overridden with custom font")
                        }
                    }
                }
            )
            log("Successfully hooked StrokeTextView.setTypeface")
        }.onFailure {
            log("Failed to hook StrokeTextView.setTypeface: ${it.message}")
        }

        // 2. Hook DepthEffectSession methods
        runCatching {
            val sessionClass = XposedHelpers.findClass(
                "com.motorola.deptheffect.clockface.DepthEffectSession",
                classLoader
            )

            val sessionHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val session = param.thisObject
                    activeDepthEffectSession = WeakReference(session)
                    applyDepthEffectSettings(session)
                }
            }

            listOf(
                "onCreate",
                "updateTime",
                "onClockFaceStyleUpdated",
                "onClockFaceTimeTickUpdated",
                "onClockFaceVisibilityChanged"
            ).forEach { methodName ->
                runCatching {
                    XposedBridge.hookAllMethods(sessionClass, methodName, sessionHook)
                }
            }
            log("Successfully attached DepthEffectSession lifecycle hooks")
        }.onFailure {
            log("DepthEffectSession class not found: ${it.message}")
        }

        // 3. Hook ClockFaceSession.setClockFaceContentView (superclass)
        runCatching {
            val clockFaceSessionClass = XposedHelpers.findClass(
                "com.motorola.clockface.sdk.session.ClockFaceSession",
                classLoader
            )
            XposedBridge.hookAllMethods(
                clockFaceSessionClass,
                "setClockFaceContentView",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val view = param.args[0] as? View ?: return
                        PreferencesHelper.setContext(view.context)
                        registerSettingsReceiver(view.context) { refreshActiveViews() }
                        applyDepthEffectClockAlpha(view)
                        applyDepthEffectClockFont(view)
                    }
                }
            )
            log("Successfully hooked ClockFaceSession.setClockFaceContentView")
        }.onFailure {
            log("ClockFaceSession.setClockFaceContentView hook skipped: ${it.message}")
        }
    }

    private fun applyDepthEffectSettings(session: Any) {
        runCatching {
            val binding = XposedHelpers.callMethod(session, "getBinding") ?: return
            val root = XposedHelpers.callMethod(binding, "getRoot") as? View
            root?.context?.let { ctx ->
                PreferencesHelper.setContext(ctx)
                registerSettingsReceiver(ctx) { refreshActiveViews() }
            }
            applyDepthEffectBindingAlpha(binding)
            applyDepthEffectBindingFont(binding)
        }.onFailure {
            log("applyDepthEffectSettings error: ${it.message}")
        }
    }

    private fun applyDepthEffectClockAlpha(rootView: View) {
        if (!PreferencesHelper.isEnabled) return
        val targetAlpha = PreferencesHelper.clockAlpha
        val includeDate = PreferencesHelper.includeDate

        runCatching {
            val res = rootView.resources
            val pkg = rootView.context.packageName

            val firstContainerId = res.getIdentifier("first_time_container", "id", pkg)
            val secondContainerId = res.getIdentifier("second_time_container", "id", pkg)
            val dateContainerId = res.getIdentifier("date_container", "id", pkg)

            if (firstContainerId != 0) {
                rootView.findViewById<View>(firstContainerId)?.alpha = targetAlpha
            }
            if (secondContainerId != 0) {
                rootView.findViewById<View>(secondContainerId)?.alpha = targetAlpha
            }
            if (includeDate && dateContainerId != 0) {
                rootView.findViewById<View>(dateContainerId)?.alpha = targetAlpha
            }
        }
    }

    private fun applyDepthEffectClockFont(rootView: View) {
        if (!PreferencesHelper.isCustomFontEnabled) return
        val tf = PreferencesHelper.customTypeface ?: return
        runCatching {
            val res = rootView.resources
            val pkg = rootView.context.packageName

            val firstContainerId = res.getIdentifier("first_time_container", "id", pkg)
            val secondContainerId = res.getIdentifier("second_time_container", "id", pkg)
            val dateContainerId = res.getIdentifier("date_container", "id", pkg)

            if (firstContainerId != 0) {
                rootView.findViewById<ViewGroup>(firstContainerId)?.let { applyTypefaceRecursively(it, tf, PreferencesHelper.customFontApplyToDate) }
            }
            if (secondContainerId != 0) {
                rootView.findViewById<ViewGroup>(secondContainerId)?.let { applyTypefaceRecursively(it, tf, PreferencesHelper.customFontApplyToDate) }
            }
            if (PreferencesHelper.customFontApplyToDate && dateContainerId != 0) {
                rootView.findViewById<ViewGroup>(dateContainerId)?.let { applyTypefaceRecursively(it, tf, true) }
            }
        }
    }

    private fun applyDepthEffectBindingAlpha(binding: Any) {
        if (!PreferencesHelper.isEnabled) return
        val targetAlpha = PreferencesHelper.clockAlpha
        val includeDate = PreferencesHelper.includeDate

        runCatching {
            (XposedHelpers.getObjectField(binding, "f") as? View)?.alpha = targetAlpha
            (XposedHelpers.getObjectField(binding, "f4543l") as? View)?.alpha = targetAlpha

            if (includeDate) {
                (XposedHelpers.getObjectField(binding, "f4537c") as? View)?.alpha = targetAlpha
            }
        }
    }

    private fun applyDepthEffectBindingFont(binding: Any) {
        if (!PreferencesHelper.isCustomFontEnabled) return
        val tf = PreferencesHelper.customTypeface ?: return
        runCatching {
            (XposedHelpers.getObjectField(binding, "f4541h") as? TextView)?.let {
                it.typeface = tf
                it.invalidate()
            }
            (XposedHelpers.getObjectField(binding, "f4542i") as? TextView)?.let {
                it.typeface = tf
                it.invalidate()
            }
            (XposedHelpers.getObjectField(binding, "j") as? TextView)?.let {
                it.typeface = tf
                it.invalidate()
            }
            (XposedHelpers.getObjectField(binding, "k") as? TextView)?.let {
                it.typeface = tf
                it.invalidate()
            }

            if (PreferencesHelper.customFontApplyToDate) {
                (XposedHelpers.getObjectField(binding, "f4538d") as? TextView)?.let {
                    it.typeface = tf
                    it.invalidate()
                }
                (XposedHelpers.getObjectField(binding, "f4539e") as? TextView)?.let {
                    it.typeface = tf
                    it.invalidate()
                }
            }
        }
    }

    /**
     * Hook Motorola & AOSP SystemUI Lockscreen Clock
     */
    private fun hookSystemUI(classLoader: ClassLoader) {
        // 1. Hook TypefaceCache to return custom typeface for ALL font variants
        runCatching {
            val variantCacheClass = XposedHelpers.findClass(
                "com.android.systemui.customization.clocks.TypefaceCache\$getVariantCache\$1",
                classLoader
            )
            XposedBridge.hookAllMethods(
                variantCacheClass,
                "getTypefaceForVariant",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!PreferencesHelper.isCustomFontEnabled) return
                        val tf = PreferencesHelper.customTypeface ?: return
                        param.result = tf
                    }
                }
            )
            log("Successfully hooked TypefaceCache.getVariantCache.getTypefaceForVariant")
        }.onFailure {
            log("TypefaceCache.getVariantCache hook skipped: ${it.message}")
        }

        // 2. Hook DigitalClockTextView (handles lockscreenPaint and text animation)
        runCatching {
            val digitalClockClass = XposedHelpers.findClass(
                "com.android.systemui.customization.clocks.view.DigitalClockTextView",
                classLoader
            )
            XposedBridge.hookAllMethods(
                digitalClockClass,
                "onTypefaceUpdated",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        if (!PreferencesHelper.isCustomFontEnabled) return
                        val tf = PreferencesHelper.customTypeface ?: return
                        val view = param.thisObject as? View ?: return
                        runCatching {
                            (view as? TextView)?.typeface = tf
                            val lockscreenPaint = XposedHelpers.getObjectField(view, "lockscreenPaint") as? Paint
                            lockscreenPaint?.typeface = tf
                            view.invalidate()
                        }
                    }
                }
            )
            log("Successfully hooked DigitalClockTextView.onTypefaceUpdated")
        }.onFailure {
            log("DigitalClockTextView hook skipped: ${it.message}")
        }

        // 3. Hook FlexClockTextView
        runCatching {
            val flexClockClass = XposedHelpers.findClass(
                "com.android.systemui.shared.clocks.view.FlexClockTextView",
                classLoader
            )
            XposedBridge.hookAllMethods(
                flexClockClass,
                "setTypeface",
                object : XC_MethodHook() {
                    override fun beforeHookedMethod(param: MethodHookParam) {
                        if (!PreferencesHelper.isCustomFontEnabled) return
                        val tf = PreferencesHelper.customTypeface ?: return
                        param.args[0] = tf
                    }
                }
            )
            log("Successfully hooked FlexClockTextView.setTypeface")
        }.onFailure {
            log("FlexClockTextView hook skipped: ${it.message}")
        }

        // 4. Hook Motorola MotoClockFaceController
        runCatching {
            val motoControllerClass = XposedHelpers.findClass(
                "com.motorola.keyguard.clock.MotoClockFaceController",
                classLoader
            )

            val controllerLifecycleHook = object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val controller = param.thisObject
                    activeMotoClockFaceController = WeakReference(controller)

                    val ctx = runCatching {
                        XposedHelpers.getObjectField(controller, "ctx") as? Context
                    }.getOrNull()

                    ctx?.let {
                        PreferencesHelper.setContext(it)
                        PreferencesHelper.reload(it)
                        registerSettingsReceiver(it) { refreshActiveViews() }
                    }

                    applySystemUIClockSettings(controller)
                }
            }

            XposedBridge.hookAllMethods(motoControllerClass, "onPluginConnected", controllerLifecycleHook)
            XposedBridge.hookAllMethods(motoControllerClass, "onClockFontChanged", controllerLifecycleHook)
            XposedBridge.hookAllMethods(motoControllerClass, "initialize", controllerLifecycleHook)
            XposedBridge.hookAllMethods(motoControllerClass, "inLock", controllerLifecycleHook)

            log("Successfully hooked MotoClockFaceController")
        }.onFailure {
            log("MotoClockFaceController hook not found or failed: ${it.message}")
        }

        // 5. Hook MotoClockFaceContainer view hierarchy additions
        runCatching {
            val containerClass = XposedHelpers.findClass(
                "com.android.systemui.shared.clocks.view.MotoClockFaceContainer",
                classLoader
            )

            XposedBridge.hookAllMethods(
                containerClass,
                "onViewAdded",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val childView = param.args[0] as? View ?: return
                        if (PreferencesHelper.isEnabled) {
                            childView.alpha = PreferencesHelper.clockAlpha
                        }
                        if (PreferencesHelper.isCustomFontEnabled) {
                            val tf = PreferencesHelper.customTypeface
                            if (tf != null) {
                                applyTypefaceRecursively(childView, tf, PreferencesHelper.customFontApplyToDate)
                            }
                        }
                        clearCardBackground(childView as? ViewGroup)
                    }
                }
            )
            log("Successfully hooked MotoClockFaceContainer")
        }.onFailure {
            log("MotoClockFaceContainer hook skipped: ${it.message}")
        }
    }

    private fun hookPersonalize(classLoader: ClassLoader) {
        runCatching {
            val modernHolderClass = XposedHelpers.findClass("Q2.s", classLoader)
            XposedBridge.hookAllMethods(
                modernHolderClass,
                "F",
                object : XC_MethodHook() {
                    override fun afterHookedMethod(param: MethodHookParam) {
                        val bg = param.args.getOrNull(0) as? TextView
                        val fore = param.args.getOrNull(1) as? TextView

                        // 1. Transparency
                        if (PreferencesHelper.isEnabled) {
                            val alpha = PreferencesHelper.clockAlpha
                            bg?.alpha = alpha
                            fore?.alpha = alpha
                        }

                        // 2. Custom Font
                        if (PreferencesHelper.isCustomFontEnabled) {
                            val tf = PreferencesHelper.customTypeface
                            if (tf != null) {
                                bg?.typeface = tf
                                fore?.typeface = tf
                            }
                        }

                        // 3. Keep card transparent
                        val bgParent = (bg?.parent as? View)?.parent as? ViewGroup ?: bg?.parent as? ViewGroup
                        val foreParent = (fore?.parent as? View)?.parent as? ViewGroup ?: fore?.parent as? ViewGroup
                        clearCardBackground(bgParent)
                        clearCardBackground(foreParent)

                        bg?.invalidate()
                        fore?.invalidate()
                        log("Hooked Q2.s.F in Personalize -> applied font & transparency")
                    }
                }
            )
            log("Successfully hooked Q2.s.F in Personalize")
        }.onFailure {
            log("Q2.s hook skipped in Personalize: ${it.message}")
        }
    }

    private fun applySystemUIClockSettings(controller: Any) {
        runCatching {
            val largeClockBg = XposedHelpers.getObjectField(controller, "largeClockBg") as? ViewGroup
            val largeClockFg = XposedHelpers.getObjectField(controller, "largeClockFg") as? ViewGroup
            val largeClockView = XposedHelpers.getObjectField(controller, "largeClockView") as? View
            val smallClockView = XposedHelpers.getObjectField(controller, "smallClockView") as? View

            // 1. Transparency
            if (PreferencesHelper.isEnabled) {
                val alpha = PreferencesHelper.clockAlpha
                largeClockBg?.alpha = alpha
                largeClockFg?.alpha = alpha
                smallClockView?.alpha = alpha
                log("Applied alpha $alpha to MotoClockFaceController frames")
            }

            // 2. Custom Font
            if (PreferencesHelper.isCustomFontEnabled) {
                val tf = PreferencesHelper.customTypeface
                if (tf != null) {
                    XposedHelpers.setObjectField(controller, "clockTypeFace", tf)

                    largeClockBg?.let { applyTypefaceRecursively(it, tf, PreferencesHelper.customFontApplyToDate) }
                    largeClockFg?.let { applyTypefaceRecursively(it, tf, PreferencesHelper.customFontApplyToDate) }
                    largeClockView?.let { applyTypefaceRecursively(it, tf, PreferencesHelper.customFontApplyToDate) }
                    smallClockView?.let {
                        applyTypefaceRecursively(it, tf, PreferencesHelper.customFontApplyToDate)
                    }
                    log("Applied custom font to MotoClockFaceController views successfully!")
                } else {
                    log("Custom font enabled but customTypeface is null")
                }
            }

            // 3. Keep card transparent
            clearCardBackground(largeClockBg)
            clearCardBackground(largeClockFg)
        }.onFailure {
            log("Error in applySystemUIClockSettings: ${it.message}")
        }
    }

    private fun findCardView(view: View): View? {
        val idName = runCatching {
            if (view.id != View.NO_ID && view.id != 0) view.resources.getResourceEntryName(view.id) else ""
        }.getOrDefault("")
        if (idName == "depth_frame_card_view" || idName == "config_depth_frame_card_view") {
            return view
        }
        var cls: Class<*>? = view.javaClass
        while (cls != null) {
            val name = cls.name
            if (name.contains("CardView", ignoreCase = true) || name.endsWith("DepthClockFrameView")) {
                return view
            }
            cls = cls.superclass
        }
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                val found = findCardView(view.getChildAt(i))
                if (found != null) return found
            }
        }
        return null
    }

    private fun clearCardBackground(frameLayout: ViewGroup?) {
        if (frameLayout == null) return
        runCatching {
            frameLayout.background = null
            val cardView = findCardView(frameLayout) ?: frameLayout
            cardView.background = null
            cardView.elevation = 0f
            cardView.setPadding(0, 0, 0, 0)
            runCatching { XposedHelpers.callMethod(cardView, "setContentPadding", 0, 0, 0, 0) }
            cardView.invalidate()
        }
    }
}
