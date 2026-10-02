package dev.amenhancer.module.hook

import android.app.Activity
import android.graphics.Rect
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import dev.amenhancer.module.host.OwnedHostProperty
import java.util.IdentityHashMap

internal class FragmentSurfaceBinding(
    private val owner: Any,
    override val activity: Activity,
    private val root: ViewGroup,
    private val contract: FragmentChromeContract,
    callbacks: IdentityHashMap<Any, Any>,
    private val fail: (Throwable) -> Unit,
) : FragmentPlayerSurfacePort, ViewTreeObserver.OnPreDrawListener {
    private val ids = HashMap<String, Int>()
    private fun id(role: String) = ids.getOrPut(role) {
        root.resources.getIdentifier(contract.resources.getString(role), "id", activity.packageName)
            .also { check(it != 0) { "Missing Fragment chrome resource $role" } }
    }
    private fun find(role: String): View? = root.findViewById(id(role))
    private val top = find("topNavigation")
    private val nav = top ?: checkNotNull(find("bottomNavigation"))
    private val placement = if (top != null) NavigationPlacement.TOP else NavigationPlacement.BOTTOM
    private val source = checkNotNull(find("backdropSource"))
    private val sheet = checkNotNull(find("playerSheet"))
    private val navBlur = find(if (top != null) "topNavigationBlur" else "bottomNavigationBlur")
    private val miniBlur = find("miniBlur")
    private var mini: View? = null
    private var miniTouch: View? = null
    private var playerIdentity: Any? = null
    private var materialParent: ViewGroup? = null
    private var playerContent: View? = null
    private val accentId = root.resources.getIdentifier(contract.names.getString("accentColor"), "color", activity.packageName)
    private val observers = LinkedHashSet<(FragmentPlayerSurfaceSnapshot) -> Unit>()
    override val navigation = FragmentChromeNavigation(owner, nav, placement, contract, callbacks)
    override val pageFamily = HostPageFamily.FRAGMENT_VIEW
    override val viewSessionIdentity: Any get() = root
    private val alphas = IdentityHashMap<View, AlphaLease>()
    private val backgrounds = IdentityHashMap<View, OwnedHostProperty<android.graphics.drawable.Drawable?>>()
    private val clip = OwnedHostProperty({ nav.clipBounds?.let(::Rect) }, { nav.clipBounds = it })
    private val accessibility = OwnedHostProperty({ nav.importantForAccessibility }, { nav.importantForAccessibility = it })
    private var miniReady = false
    private var navReady = false
    private var closed = false
    private var expansion = 0f
    private var writing = false
    private val tree = root.viewTreeObserver
    private val layout = ViewTreeObserver.OnGlobalLayoutListener { resolveMini() }
    private val detach = object : View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(v: View) = Unit
        override fun onViewDetachedFromWindow(v: View) = close()
    }

    init {
        check(root !== sheet && root !== source) { "Glass requires a content-root sibling island" }
        listOfNotNull(nav, navBlur, miniBlur).forEach { alphas[it] = AlphaLease(it) }
        resolveMini()
        tree.addOnPreDrawListener(this)
        tree.addOnGlobalLayoutListener(layout)
        root.addOnAttachStateChangeListener(detach)
    }

    private fun resolveMini() {
        val next = find("miniContent")
        if (next !== mini) {
            mini?.let { backgrounds.remove(it)?.close() }
            mini = next
            miniReady = false
            playerIdentity = contract.playerOf.invoke(owner)
            playerIdentity?.let { player -> contract.playerSlide.get(player)?.let {
                expansion = contract.slideProgress.getFloat(it).coerceIn(0f, 1f)
            } }
        }
        // Android <include android:id="mini_player"> replaces the inflated touch-panel ID.
        miniTouch = (next?.parent as? android.widget.FrameLayout) ?: find("miniTouchPanel")
        materialParent = find("playerRoot") as? ViewGroup
        playerContent = find("playerContent")
    }

    override fun regions() = PlayerRegions(nav, placement, mini, sheet, true)
    override fun snapshot(): FragmentPlayerSurfaceSnapshot {
        val night = root.resources.configuration.uiMode and 0x30 == 0x20
        return FragmentPlayerSurfaceSnapshot(root, source, nav, navBlur, mini, miniTouch, miniBlur, sheet, materialParent, playerContent,
            expansion, alphas.getValue(nav).native, if (accentId != 0) activity.getColor(accentId) else 0xfffa233b.toInt(),
            if (night) android.graphics.Color.WHITE else android.graphics.Color.BLACK)
    }

    fun isPlayer(player: Any?) = player != null && playerIdentity === player
    fun slide(progress: Float) { expansion = progress.takeIf { it.isFinite() }?.coerceIn(0f, 1f) ?: 0f }
    fun isMiniTouchPanel(view: View): Boolean = miniTouch === view
    /** Called before View.setAlpha: remember even host writes equal to our hidden value. */
    fun nativeAlphaWrite(view: View, alpha: Float): Float? {
        if (closed || writing) return null
        val lease = alphas[view] ?: return null
        lease.native = alpha
        return if (lease.hidden) lease.own(0f) else null
    }

    override fun setNavigationGlassReady(ready: Boolean) { navReady = ready; applyOwnership() }
    override fun setMiniGlassReady(ready: Boolean) { miniReady = ready; applyOwnership() }

    private fun applyOwnership() {
        if (closed) return
        writing = true
        try {
            if (placement == NavigationPlacement.TOP) {
                // Keep the original drawer button, native pointer handling, and native drawer action.
                if (navReady) {
                    val width = (48 * root.resources.displayMetrics.density).toInt().coerceAtMost(nav.width)
                    clip.set(if (nav.layoutDirection == View.LAYOUT_DIRECTION_RTL)
                        Rect(nav.width - width, 0, nav.width, nav.height) else Rect(0, 0, width, nav.height))
                } else clip.close()
            } else {
                alphas.getValue(nav).hide(navReady)
                if (navReady) accessibility.set(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS)
                else accessibility.close()
            }
            navBlur?.let { alphas.getValue(it).hide(navReady) }
            miniBlur?.let { alphas.getValue(it).hide(miniReady) }
            mini?.let { view ->
                if (miniReady) backgrounds.getOrPut(view) {
                    OwnedHostProperty({ view.background }, { view.background = it })
                }.set(null)
                else backgrounds.remove(view)?.close()
            }
        } finally { writing = false }
    }

    override fun onPreDraw(): Boolean {
        if (closed) return true
        try {
            alphas.values.forEach { it.observe() }
            navigation.refresh()
            applyOwnership()
            val state = snapshot()
            observers.toList().forEach { it(state) }
        } catch (error: Throwable) { root.post { fail(error) } }
        return true
    }
    override fun observe(observer: (FragmentPlayerSurfaceSnapshot) -> Unit): HostSubscription {
        if (closed) return HostSubscription {}
        observers += observer
        observer(snapshot())
        return HostSubscription { observers -= observer }
    }
    override fun close() {
        if (closed) return
        closed = true
        if (tree.isAlive) { tree.removeOnPreDrawListener(this); tree.removeOnGlobalLayoutListener(layout) }
        root.removeOnAttachStateChangeListener(detach)
        observers.clear()
        navigation.close()
        writing = true
        try {
            alphas.values.forEach { it.hide(false) }
            backgrounds.values.forEach { it.close() }
            clip.close(); accessibility.close()
        } finally { writing = false }
        mini = null; miniTouch = null
    }

    private class AlphaLease(val view: View) {
        var native = view.alpha
        var hidden = false
        private var last: Float? = null
        fun observe() { if (view.alpha != last) native = view.alpha }
        fun own(alpha: Float): Float { last = alpha; return alpha }
        fun hide(value: Boolean) {
            observe()
            if (value) { hidden = true; view.alpha = own(0f) }
            else if (hidden) {
                hidden = false
                if (view.alpha == last) view.alpha = own(native)
                last = null
            }
        }
    }
}
