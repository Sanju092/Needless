package com.cactus.needle3.agent.service

import android.accessibilityservice.AccessibilityService
import android.os.Build
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Allows Needle 3 to perform full UI automation across any app on the phone:
 * clicking buttons by text, typing into input fields, scrolling, taking screenshots,
 * and pressing Home / Back / Recents / Lock Screen.
 */
class NeedleAccessibilityService : AccessibilityService() {

    companion object {
        var instance: NeedleAccessibilityService? = null
            private set
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        super.onDestroy()
    }

    fun triggerScreenshot(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            performGlobalAction(GLOBAL_ACTION_TAKE_SCREENSHOT)
        } else {
            false
        }
    }

    fun executeUiAction(action: String, targetOrText: String): Boolean {
        return when (action) {
            "press_home" -> performGlobalAction(GLOBAL_ACTION_HOME)
            "press_back" -> performGlobalAction(GLOBAL_ACTION_BACK)
            "press_recents" -> performGlobalAction(GLOBAL_ACTION_RECENTS)
            "open_notifications" -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            "lock_screen" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN) else false
            "click_text" -> clickNodeByText(targetOrText)
            "type_text" -> typeIntoFocusedNode(targetOrText)
            "scroll_down" -> scrollWindow(forward = true)
            "scroll_up" -> scrollWindow(forward = false)
            else -> false
        }
    }

    private fun clickNodeByText(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val nodes = root.findAccessibilityNodeInfosByText(text)
        for (node in nodes) {
            if (node.isClickable) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            var parent = node.parent
            while (parent != null) {
                if (parent.isClickable) {
                    return parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                }
                parent = parent.parent
            }
        }
        return false
    }

    private fun typeIntoFocusedNode(text: String): Boolean {
        val root = rootInActiveWindow ?: return false
        val focus = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return false
        val args = Bundle().apply {
            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
        }
        return focus.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
    }

    private fun scrollWindow(forward: Boolean): Boolean {
        val root = rootInActiveWindow ?: return false
        val action = if (forward) AccessibilityNodeInfo.ACTION_SCROLL_FORWARD else AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD
        return findScrollableNode(root)?.performAction(action) ?: false
    }

    private fun findScrollableNode(node: AccessibilityNodeInfo?): AccessibilityNodeInfo? {
        if (node == null) return null
        if (node.isScrollable) return node
        for (i in 0 until node.childCount) {
            val found = findScrollableNode(node.getChild(i))
            if (found != null) return found
        }
        return null
    }
}
