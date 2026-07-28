package com.jakebrierley.ultimacontroller

import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.WindowInsets
import android.view.WindowInsetsController
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class MainActivity : AppCompatActivity() {
    private lateinit var actionText: TextView
    private lateinit var eventText: TextView
    private lateinit var bridge: EmulatorBridge
    private var actionIndex = 0
    private var dialogOpen = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        hideSystemUi()
        bridge = EmulatorBridge { eventText.text = it }
        setContentView(buildUi())
        updateAction()
    }

    private fun hideSystemUi() {
        window.insetsController?.let {
            it.hide(WindowInsets.Type.statusBars() or WindowInsets.Type.navigationBars())
            it.systemBarsBehavior = WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            setPadding(20, 20, 20, 20)
        }

        val emulatorFrame = FrameLayout(this).apply {
            setBackgroundColor(Color.rgb(12, 12, 12))
            addView(TextView(this@MainActivity).apply {
                text = "MILESTONE 1 — CONTROLLER TEST\n\nDOS core not yet connected\nULTIMA.EXE assets embedded"
                setTextColor(Color.LTGRAY)
                gravity = Gravity.CENTER
                textSize = 22f
            }, FrameLayout.LayoutParams(-1, -1))
        }
        root.addView(emulatorFrame, LinearLayout.LayoutParams(-1, 0, 1f))

        actionText = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 28f
            setPadding(0, 18, 0, 8)
        }
        root.addView(actionText)

        eventText = TextView(this).apply {
            setTextColor(Color.LTGRAY)
            textSize = 18f
            text = "L/R: action   A: execute   X: list   Y: keys"
        }
        root.addView(eventText)
        return root
    }

    private fun updateAction() {
        actionText.text = "ACTION: ${Commands.all[actionIndex].label}"
    }

    private fun cycle(delta: Int) {
        actionIndex = (actionIndex + delta + Commands.all.size) % Commands.all.size
        updateAction()
    }

    private fun executeAction() = bridge.sendAscii(Commands.all[actionIndex].key)

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (dialogOpen) return super.dispatchKeyEvent(event)
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0) return true
        val controller = event.source and (InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK) != 0
        if (!controller) return super.dispatchKeyEvent(event)

        eventText.text = "Android keyCode=${event.keyCode} scanCode=${event.scanCode}"
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
            else -> super.dispatchKeyEvent(event)
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
                    3 -> showKeyPicker()
                    4 -> finish()
                }
            }
            .setOnDismissListener { dialogOpen = false }
            .show()
    }
}
