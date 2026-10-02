package dev.amenhancer.module.hook

import android.view.Menu
import android.view.View
import java.lang.reflect.Method
import java.util.IdentityHashMap

/** All model and action objects stay inside the host adapter. No host Compose ownership crosses it. */
internal class FragmentChromeNavigation(
    private val owner: Any,
    private val view: View,
    private val placement: NavigationPlacement,
    private val contract: FragmentChromeContract,
    private val callbacks: IdentityHashMap<Any, Any>,
) : NavigationPort, AutoCloseable {
    private val listeners = LinkedHashSet<(NavigationSnapshot) -> Unit>()
    private val vm = contract.viewModel.invoke(owner)!!
    private val selected = FragmentChromeContract.method(vm.javaClass, "getSelectedTab")
    private val live = FragmentChromeContract.method(vm.javaClass, "getGetBottomTabsLiveData").invoke(vm)!!
    private val value = FragmentChromeContract.method(live.javaClass, "getValue")
    private val menuMethod: Method? = if (placement == NavigationPlacement.BOTTOM)
        FragmentChromeContract.method(view.javaClass, "getMenu") else null
    private val selectedIdMethod: Method? = if (menuMethod != null)
        FragmentChromeContract.method(view.javaClass, "getSelectedItemId") else null
    private val selectMethod: Method? = if (menuMethod != null)
        FragmentChromeContract.method(view.javaClass, "setSelectedItemId", java.lang.Integer.TYPE) else null
    private var key: List<Any?> = emptyList()
    private var revision = 0L
    private var models = emptyMap<Int, Any>()
    private var last: NavigationSnapshot? = null
    private var iconConfiguration = 0
    private val icons = HashMap<Int, android.graphics.drawable.Drawable?>()
    private var closed = false

    override fun snapshot(): NavigationSnapshot {
        if (closed) return NavigationSnapshot(emptyList(), null, placement, revision, false)
        val menu = menuMethod?.invoke(view) as? Menu
        val items: List<NavigationItem>
        val selectedId: Int?
        val ready: Boolean
        if (menu != null) {
            items = (0 until menu.size()).map(menu::getItem).filter { it.isVisible }.map {
                NavigationItem(it.itemId, it.title?.toString().orEmpty(), it.icon, it.isEnabled)
            }
            selectedId = selectedIdMethod!!.invoke(view) as Int
            ready = true
        } else {
            val configuration = view.resources.configuration.hashCode()
            if (configuration != iconConfiguration) {
                iconConfiguration = configuration
                icons.clear()
            }
            val nativeModels = (value.invoke(live) as? List<*>)?.filterNotNull().orEmpty()
            models = nativeModels.associateBy { contract.kindId.invoke(contract.modelKind.get(it)) as Int }
            items = nativeModels.map { model ->
                val kind = contract.modelKind.get(model)!!
                val iconId = contract.kindIcon.invoke(kind) as Int
                NavigationItem(contract.kindId.invoke(kind) as Int, contract.kindLabel.invoke(kind) as String,
                    if (iconId != 0) icons.getOrPut(iconId) { view.context.getDrawable(iconId) } else null, true)
            }
            selectedId = selected.invoke(vm)?.let { contract.kindId.invoke(it) as Int }
            // The callback may be captured for an equivalent model object from the same live menu.
            ready = nativeModels.isNotEmpty() && nativeModels.all { callbackFor(it) != null }
        }
        val nextKey = items.flatMap { listOf(it.id, it.label, it.enabled, it.icon?.constantState) } +
            listOf(selectedId, ready, view.resources.configuration.hashCode())
        if (key != nextKey) { key = nextKey; revision++ }
        return NavigationSnapshot(items, selectedId, placement, revision, ready)
    }

    private fun callbackFor(model: Any): Any? = callbacks[model] ?: callbacks.entries.firstOrNull {
        contract.modelKind.get(it.key) == contract.modelKind.get(model)
    }?.value

    override fun select(id: Int): NavigationSnapshot {
        val before = snapshot()
        if (before.tabs.none { it.id == id && it.enabled }) return before
        if (selectMethod != null) selectMethod.invoke(view, id)
        else models[id]?.let { model -> callbackFor(model)?.let { contract.invokeCallback.invoke(it, model) } }
        return snapshot().also { publish(it) }
    }

    fun refresh() { if (!closed) publish(snapshot()) }
    private fun publish(snapshot: NavigationSnapshot) {
        if (snapshot == last) return
        last = snapshot
        listeners.toList().forEach { it(snapshot) }
    }
    override fun observe(observer: (NavigationSnapshot) -> Unit): HostSubscription {
        if (closed) return HostSubscription {}
        listeners += observer
        observer(snapshot())
        return HostSubscription { listeners -= observer }
    }
    override fun close() { closed = true; listeners.clear(); models = emptyMap(); icons.clear() }
}
