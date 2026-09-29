package com.knightdx.glassestunes

import android.accessibilityservice.AccessibilityService
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Lets you control whatever is on screen by voice: tap buttons by name,
 * scroll, type, go back/home. Android only allows this through an
 * accessibility service that you switch on yourself in Settings.
 * It only acts when you give a command; it doesn't record anything.
 */
class ScreenControlService : AccessibilityService() {

    override fun onServiceConnected() {
        instance = this
    }

    override fun onUnbind(intent: android.content.Intent?): Boolean {
        instance = null
        return super.onUnbind(intent)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    fun back() = performGlobalAction(GLOBAL_ACTION_BACK)
    fun home() = performGlobalAction(GLOBAL_ACTION_HOME)
    fun notifications() = performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
    fun recents() = performGlobalAction(GLOBAL_ACTION_RECENTS)

    /** Taps the on-screen item whose text or description best matches [spoken]. Returns what it tapped. */
    fun tap(spoken: String): String? {
        val candidates = ArrayList<Pair<String, AccessibilityNodeInfo>>()
        for (root in roots()) collect(root, candidates)
        val labels = candidates.map { it.first }
        val best = labels.sortedBy { it.length }.maxByOrNull { LibraryMatcher.score(it, spoken) } ?: return null
        if (LibraryMatcher.score(best, spoken) < 60) return null
        val node = candidates.first { it.first == best }.second
        return if (click(node)) best else null
    }

    fun scroll(down: Boolean): Boolean {
        val action = if (down) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        for (root in roots()) {
            findScrollable(root)?.let { return it.performAction(action) }
        }
        return false
    }

    /** Types into the focused text box (or the first one on screen). */
    fun type(text: String): Boolean {
        val field = rootInActiveWindow?.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            ?: roots().firstNotNullOfOrNull { findEditable(it) }
            ?: return false
        val existing = field.text?.toString()?.takeUnless { field.isShowingHintText }.orEmpty()
        val args = Bundle().apply {
            putCharSequence(
                AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                if (existing.isBlank()) text else "$existing $text",
            )
        }
        return field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    /** Presses WhatsApp's Send button once its chat is open. */
    fun pressWhatsAppSend(): Boolean {
        val root = rootInActiveWindow ?: return false
        if (root.packageName?.toString() != PhoneActions.WHATSAPP) return false
        val send = root.findAccessibilityNodeInfosByViewId("${PhoneActions.WHATSAPP}:id/send").firstOrNull()
            ?: root.findAccessibilityNodeInfosByText("Send").firstOrNull { it.isClickable || it.parent?.isClickable == true }
            ?: return false
        return click(send)
    }

    private fun roots(): List<AccessibilityNodeInfo> =
        windows.mapNotNull { it.root }.ifEmpty { listOfNotNull(rootInActiveWindow) }

    private fun collect(node: AccessibilityNodeInfo, out: MutableList<Pair<String, AccessibilityNodeInfo>>) {
        if (!node.isVisibleToUser) return
        val label = (node.text ?: node.contentDescription)?.toString()?.trim()
        if (!label.isNullOrEmpty() && label.length <= 80) out += label to node
        for (i in 0 until node.childCount) node.getChild(i)?.let { collect(it, out) }
    }

    private fun click(node: AccessibilityNodeInfo): Boolean {
        var n: AccessibilityNodeInfo? = node
        while (n != null) {
            if (n.isClickable) return n.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            n = n.parent
        }
        return false
    }

    private fun findScrollable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isScrollable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { findScrollable(it)?.let { s -> return s } }
        return null
    }

    private fun findEditable(node: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (node.isEditable && node.isVisibleToUser) return node
        for (i in 0 until node.childCount) node.getChild(i)?.let { findEditable(it)?.let { e -> return e } }
        return null
    }

    companion object {
        var instance: ScreenControlService? = null
            private set
    }
}
