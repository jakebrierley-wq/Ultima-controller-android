package com.jakebrierley.ultimacontroller

import android.app.Activity
import android.app.AlertDialog
import android.content.res.Configuration
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var actionText: TextView
    private lateinit var controllerText: TextView
    private lateinit var outputText: TextView
    private lateinit var displayText: TextView
    private lateinit var bridge: EmulatorBridge
    private var actionIndex = 0
    private var dialogOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        actionIndex = savedInstanceState
            ?.getInt(STATE_ACTION_INDEX, 0)
            ?.coerceIn(0, Commands.all.lastIndex)
            ?: 0

        val content = buildUi()
        setContentView(content)
        bridge = EmulatorBridge { message -> outputText.text = message }
        updateAction()
        content.post {
            val orientation = when (resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> "landscape"
                Configuration.ORIENTATION_PORTRAIT -> "portrait"
                else -> "unspecified"
            }
            displayText.text =
                "CONTROLLER SHELL\n\n" +
                    "Window ${content.width} × ${content.height} ($orientation)\n" +
                    "No DOS core or game files are bundled"
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(STATE_ACTION_INDEX, actionIndex)
        super.onSaveInstanceState(outState)
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            fitsSystemWindows = true
            setPadding(dp(24), dp(20), dp(24), dp(20))
        }

        val emulatorFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(12, 12, 12))
            displayText = TextView(this@MainActivity).apply {
                text = "CONTROLLER SHELL\n\nChecking available display…"
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
                textSize = 20f
                setPadding(dp(16), dp(16), dp(16), dp(16))
            }
            addView(
                displayText,
                FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                ),
            )
        }
        root.addView(
            emulatorFrame,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f,
            ),
        )

        actionText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 26f
            setPadding(0, dp(16), 0, dp(6))
        }
        root.addView(actionText)

        controllerText = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 16f
            text = "Controller input: waiting"
        }
        root.addView(controllerText)

        outputText = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 16f
            text = "L/R: action   A: execute   X: list   Y: keys   Start: menu"
            setPadding(0, dp(4), 0, 0)
        }
        root.addView(outputText)

        return root
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun updateAction() {
        actionText.text = "ACTION: ${Commands.all[actionIndex].label}"
    }

    private fun cycle(delta: Int) {
        actionIndex = (actionIndex + delta + Commands.all.size) % Commands.all.size
        updateAction()
    }

    private fun executeAction() = bridge.sendAscii(Commands.all[actionIndex].key)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        val controller =
            event.isFromSource(InputDevice.SOURCE_GAMEPAD) ||
                event.isFromSource(InputDevice.SOURCE_JOYSTICK)
        if (!controller || dialogOpen) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true

        controllerText.text =
            "Controller input: keyCode=${event.keyCode} scanCode=${event.scanCode}"
        return when (event.keyCode) {
            KeyEvent.KEYCODE_DPAD_UP -> { bridge.sendDirection("UP"); true }
            KeyEvent.KEYCODE_DPAD_DOWN -> { bridge.sendDirection("DOWN"); true }
            KeyEvent.KEYCODE_DPAD_LEFT -> { bridge.sendDirection("LEFT"); true }
            KeyEvent.KEYCODE_DPAD_RIGHT -> { bridge.sendDirection("RIGHT"); true }
            KeyEvent.KEYCODE_BUTTON_A -> { executeAction(); true }
            KeyEvent.KEYCODE_BUTTON_B -> { bridge.sendSpecial("ESC"); true }
            KeyEvent.KEYCODE_BUTTON_L1 -> { cycle(-1); true }
            KeyEvent.KEYCODE_BUTTON_R1 -> { cycle(1); true }
            KeyEvent.KEYCODE_BUTTON_X -> { showActionList(); true }
            KeyEvent.KEYCODE_BUTTON_Y -> { showKeyPicker(); true }
            KeyEvent.KEYCODE_BUTTON_START -> { showSystemMenu(); true }
            KeyEvent.KEYCODE_BUTTON_SELECT -> { bridge.sendAscii('z'); true }
            else -> true
        }
    }

    private fun showActionList() {
        dialogOpen = true
        val labels = Commands.all.map { it.label }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("SELECT ACTION")
            .setSingleChoiceItems(labels, actionIndex) { dialog, which ->
                actionIndex = which; updateAction(); dialog.dismiss()
            }
            .setOnDismissListener { dialogOpen = false }
            .show()
    }

    private fun showKeyPicker() {
        dialogOpen = true
        val keys = (('A'..'Z').map(Char::toString) + ('0'..'9').map(Char::toString) + listOf("SPACE", "ENTER", "ESC"))
        AlertDialog.Builder(this)
            .setTitle("SEND KEY")
            .setItems(keys.toTypedArray()) { _, which ->
                when (val key = keys[which]) {
                    "SPACE" -> bridge.sendSpecial("SPACE")
                    "ENTER" -> bridge.sendSpecial("ENTER")
                    "ESC" -> bridge.sendSpecial("ESC")
                    else -> bridge.sendAscii(key[0].lowercaseChar())
                }
            }
            .setOnDismissListener { dialogOpen = false }
            .show()
    }

    private fun showSystemMenu() {
        dialogOpen = true
        val items = arrayOf("Resume", "Send Enter", "Send Escape", "Show key picker", "Exit app")
        AlertDialog.Builder(this)
            .setTitle("SYSTEM")
            .setItems(items) { _, which ->
                when (which) {
                    1 -> bridge.sendSpecial("ENTER")
                    2 -> bridge.sendSpecial("ESC")
                    3 -> window.decorView.post { showKeyPicker() }
                    4 -> finish()
                }
            }
            .setOnDismissListener { dialogOpen = false }
            .show()
    }

    private companion object {
        const val STATE_ACTION_INDEX = "action_index"
    }
}
