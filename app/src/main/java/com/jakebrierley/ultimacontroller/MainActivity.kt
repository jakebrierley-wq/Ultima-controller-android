package com.jakebrierley.ultimacontroller

import android.app.Activity
import android.app.AlertDialog
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.text.format.Formatter
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import java.util.concurrent.Executors
import kotlin.math.roundToInt

class MainActivity : Activity() {
    private lateinit var actionText: TextView
    private lateinit var controllerText: TextView
    private lateinit var outputText: TextView
    private lateinit var displayText: TextView
    private lateinit var bridge: EmulatorBridge
    private lateinit var contentView: View
    private val ioExecutor = Executors.newSingleThreadExecutor()
    private var operationDialog: AlertDialog? = null
    private var actionIndex = 0
    private var dialogOpen = false
    private val statusRefresh = object : Runnable {
        override fun run() {
            refreshDisplayStatus()
            if (GameStorage.isOperationInProgress()) {
                contentView.postDelayed(this, STATUS_REFRESH_MILLIS)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        actionIndex = savedInstanceState
            ?.getInt(STATE_ACTION_INDEX, 0)
            ?.coerceIn(0, Commands.all.lastIndex)
            ?: 0

        contentView = buildUi()
        setContentView(contentView)
        bridge = EmulatorBridge { message -> outputText.text = message }
        updateAction()
        contentView.post(statusRefresh)
    }

    override fun onResume() {
        super.onResume()
        if (::contentView.isInitialized) {
            contentView.removeCallbacks(statusRefresh)
            contentView.post(statusRefresh)
        }
    }

    override fun onPause() {
        if (::contentView.isInitialized) {
            contentView.removeCallbacks(statusRefresh)
        }
        super.onPause()
    }

    override fun onDestroy() {
        operationDialog?.dismiss()
        operationDialog = null
        ioExecutor.shutdown()
        super.onDestroy()
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

    private fun refreshDisplayStatus() {
        if (!::contentView.isInitialized || contentView.width == 0) return

        val orientation = when (resources.configuration.orientation) {
            Configuration.ORIENTATION_LANDSCAPE -> "landscape"
            Configuration.ORIENTATION_PORTRAIT -> "portrait"
            else -> "unspecified"
        }
        val importStatus = when {
            GameStorage.isOperationInProgress() -> "Game import: working…"
            else -> {
                val summary = GameStorage.currentSummary(this)
                if (summary == null) {
                    "Game import: none\nStart → Import game ZIP"
                } else {
                    val size = Formatter.formatShortFileSize(this, summary.totalBytes)
                    "Game import: ready (${summary.fileCount} files, $size)"
                }
            }
        }
        displayText.text =
            "CONTROLLER SHELL\n\n" +
                "Window ${contentView.width} × ${contentView.height} ($orientation)\n" +
                "$importStatus\n" +
                "DOS core not connected; no game files are bundled"
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
        val existingImport = GameStorage.currentSummary(this)
        val items = buildList {
            add(SystemMenuItem("Resume") {})
            add(
                SystemMenuItem(
                    if (existingImport == null) {
                        "Import game ZIP"
                    } else {
                        "Replace game ZIP"
                    },
                ) {
                    showImportConfirmation(replacing = existingImport != null)
                },
            )
            if (existingImport != null) {
                add(SystemMenuItem("Remove imported game") { showRemoveConfirmation() })
            }
            add(SystemMenuItem("Send Enter") { bridge.sendSpecial("ENTER") })
            add(SystemMenuItem("Send Escape") { bridge.sendSpecial("ESC") })
            add(SystemMenuItem("Show key picker") { showKeyPicker() })
            add(SystemMenuItem("Exit app") { finish() })
        }
        var pendingAction: (() -> Unit)? = null
        AlertDialog.Builder(this)
            .setTitle("SYSTEM")
            .setItems(items.map { it.label }.toTypedArray()) { _, which ->
                pendingAction = items[which].action
            }
            .setOnDismissListener {
                dialogOpen = false
                pendingAction?.let { action -> contentView.post { action() } }
            }
            .show()
    }

    private fun showImportConfirmation(replacing: Boolean) {
        dialogOpen = true
        var chooseArchive = false
        val message = if (replacing) {
            "Choose a ZIP containing your legally owned game installation. " +
                "ULTIMA.EXE must be at the ZIP root. The current private import " +
                "will be replaced only after the new ZIP passes validation."
        } else {
            "Choose a ZIP containing your legally owned game installation. " +
                "ULTIMA.EXE must be at the ZIP root. Files will be validated and " +
                "copied into this app's private storage."
        }
        AlertDialog.Builder(this)
            .setTitle(if (replacing) "REPLACE GAME FILES" else "IMPORT GAME FILES")
            .setMessage(message)
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Choose ZIP") { _, _ -> chooseArchive = true }
            .setOnDismissListener {
                dialogOpen = false
                if (chooseArchive) contentView.post { launchGameImportPicker() }
            }
            .show()
    }

    @Suppress("DEPRECATION")
    private fun launchGameImportPicker() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "application/zip"
            putExtra(
                Intent.EXTRA_MIME_TYPES,
                arrayOf(
                    "application/zip",
                    "application/x-zip-compressed",
                    "application/octet-stream",
                ),
            )
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivityForResult(intent, REQUEST_IMPORT_GAME_ZIP)
        } catch (_: ActivityNotFoundException) {
            showMessage(
                "IMPORT UNAVAILABLE",
                "Android could not find a document picker for ZIP files.",
            )
        }
    }

    @Deprecated("Uses the platform result API to keep the startup shell AndroidX-free")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT_GAME_ZIP || resultCode != RESULT_OK) return
        val selectedArchive = data?.data ?: return
        beginImport(selectedArchive)
    }

    private fun beginImport(selectedArchive: Uri) {
        if (GameStorage.isOperationInProgress()) {
            showMessage("GAME FILES", "Another game-file operation is already running.")
            return
        }

        showOperationDialog("Importing and validating the selected ZIP…")
        ioExecutor.execute {
            val result = runCatching {
                GameStorage.importArchive(applicationContext, selectedArchive)
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                finishOperationDialog()
                contentView.post(statusRefresh)
                result.fold(
                    onSuccess = { summary ->
                        val size = Formatter.formatShortFileSize(this, summary.totalBytes)
                        showMessage(
                            "IMPORT COMPLETE",
                            "${summary.fileCount} files imported ($size).\n\n" +
                                "Archive SHA-256:\n${summary.archiveSha256}\n\n" +
                                "The DOS core remains disabled.",
                        )
                    },
                    onFailure = { error ->
                        showMessage(
                            "IMPORT FAILED",
                            error.message ?: "The selected ZIP could not be imported.",
                        )
                    },
                )
            }
        }
    }

    private fun showRemoveConfirmation() {
        dialogOpen = true
        var removeImport = false
        AlertDialog.Builder(this)
            .setTitle("REMOVE GAME FILES")
            .setMessage(
                "Delete the imported private copy? The original ZIP will not be changed.",
            )
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Remove") { _, _ -> removeImport = true }
            .setOnDismissListener {
                dialogOpen = false
                if (removeImport) contentView.post { beginRemoveImport() }
            }
            .show()
    }

    private fun beginRemoveImport() {
        if (GameStorage.isOperationInProgress()) {
            showMessage("GAME FILES", "Another game-file operation is already running.")
            return
        }

        showOperationDialog("Removing the private game-file copy…")
        ioExecutor.execute {
            val result = runCatching { GameStorage.removeImport(applicationContext) }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                finishOperationDialog()
                contentView.post(statusRefresh)
                result.fold(
                    onSuccess = { removed ->
                        showMessage(
                            "GAME FILES",
                            if (removed) {
                                "The imported private copy was removed."
                            } else {
                                "No imported game files were present."
                            },
                        )
                    },
                    onFailure = { error ->
                        showMessage(
                            "REMOVE FAILED",
                            error.message ?: "The imported files could not be removed.",
                        )
                    },
                )
            }
        }
    }

    private fun showOperationDialog(message: String) {
        dialogOpen = true
        operationDialog = AlertDialog.Builder(this)
            .setTitle("GAME FILES")
            .setMessage(message)
            .setCancelable(false)
            .create()
            .also { dialog ->
                dialog.setOnDismissListener { dialogOpen = false }
                dialog.show()
            }
    }

    private fun finishOperationDialog() {
        operationDialog?.dismiss()
        operationDialog = null
        dialogOpen = false
    }

    private fun showMessage(title: String, message: String) {
        dialogOpen = true
        AlertDialog.Builder(this)
            .setTitle(title)
            .setMessage(message)
            .setPositiveButton("OK", null)
            .setOnDismissListener { dialogOpen = false }
            .show()
    }

    private data class SystemMenuItem(
        val label: String,
        val action: () -> Unit,
    )

    private companion object {
        const val STATE_ACTION_INDEX = "action_index"
        const val REQUEST_IMPORT_GAME_ZIP = 1001
        const val STATUS_REFRESH_MILLIS = 500L
    }
}
