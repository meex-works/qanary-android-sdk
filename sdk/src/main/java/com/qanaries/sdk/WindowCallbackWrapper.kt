package com.qanaries.sdk

import android.view.ActionMode
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.SearchEvent
import android.view.View
import android.view.Window
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/** Delegates every Window.Callback method, so the rage-tap detector can observe touches without changing them. */
internal open class WindowCallbackWrapper(private val d: Window.Callback) : Window.Callback {
    override fun dispatchKeyEvent(event: KeyEvent): Boolean = d.dispatchKeyEvent(event)
    override fun dispatchKeyShortcutEvent(event: KeyEvent): Boolean = d.dispatchKeyShortcutEvent(event)
    override fun dispatchTouchEvent(event: MotionEvent): Boolean = d.dispatchTouchEvent(event)
    override fun dispatchTrackballEvent(event: MotionEvent): Boolean = d.dispatchTrackballEvent(event)
    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean = d.dispatchGenericMotionEvent(event)
    override fun dispatchPopulateAccessibilityEvent(event: AccessibilityEvent): Boolean = d.dispatchPopulateAccessibilityEvent(event)
    override fun onCreatePanelView(featureId: Int): View? = d.onCreatePanelView(featureId)
    override fun onCreatePanelMenu(featureId: Int, menu: Menu): Boolean = d.onCreatePanelMenu(featureId, menu)
    override fun onPreparePanel(featureId: Int, view: View?, menu: Menu): Boolean = d.onPreparePanel(featureId, view, menu)
    override fun onMenuOpened(featureId: Int, menu: Menu): Boolean = d.onMenuOpened(featureId, menu)
    override fun onMenuItemSelected(featureId: Int, item: MenuItem): Boolean = d.onMenuItemSelected(featureId, item)
    override fun onWindowAttributesChanged(attrs: WindowManager.LayoutParams) = d.onWindowAttributesChanged(attrs)
    override fun onContentChanged() = d.onContentChanged()
    override fun onWindowFocusChanged(hasFocus: Boolean) = d.onWindowFocusChanged(hasFocus)
    override fun onAttachedToWindow() = d.onAttachedToWindow()
    override fun onDetachedFromWindow() = d.onDetachedFromWindow()
    override fun onPanelClosed(featureId: Int, menu: Menu) = d.onPanelClosed(featureId, menu)
    override fun onSearchRequested(): Boolean = d.onSearchRequested()
    override fun onSearchRequested(searchEvent: SearchEvent): Boolean = d.onSearchRequested(searchEvent)
    override fun onWindowStartingActionMode(callback: ActionMode.Callback): ActionMode? = d.onWindowStartingActionMode(callback)
    override fun onWindowStartingActionMode(callback: ActionMode.Callback, type: Int): ActionMode? = d.onWindowStartingActionMode(callback, type)
    override fun onActionModeStarted(mode: ActionMode) = d.onActionModeStarted(mode)
    override fun onActionModeFinished(mode: ActionMode) = d.onActionModeFinished(mode)
}
