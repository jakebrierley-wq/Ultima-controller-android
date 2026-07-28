package com.jakebrierley.ultimacontroller

/**
 * Converts the shell's controller commands into libretro keyboard events.
 */
class EmulatorBridge(
    private val emulator: EmulatorInput,
    private val onSent: (String) -> Unit,
) {
    fun sendAscii(char: Char) {
        sendMomentary(char.lowercaseChar().code, char.uppercaseChar().toString())
    }

    fun setDirection(name: String, down: Boolean) {
        val keyCode = when (name) {
            "UP" -> RetroKey.UP
            "DOWN" -> RetroKey.DOWN
            "LEFT" -> RetroKey.LEFT
            "RIGHT" -> RetroKey.RIGHT
            else -> return
        }
        val accepted = emulator.sendKey(keyCode, down)
        if (down) {
            onSent(
                if (accepted) {
                    "DOS direction: $name"
                } else {
                    "DOS core is not running"
                },
            )
        }
    }

    fun sendSpecial(name: String) {
        val keyCode = when (name) {
            "SPACE" -> RetroKey.SPACE
            "ENTER" -> RetroKey.ENTER
            "ESC" -> RetroKey.ESCAPE
            else -> return
        }
        sendMomentary(keyCode, name)
    }

    fun releaseDirections() {
        emulator.sendKey(RetroKey.UP, false)
        emulator.sendKey(RetroKey.DOWN, false)
        emulator.sendKey(RetroKey.LEFT, false)
        emulator.sendKey(RetroKey.RIGHT, false)
    }

    private fun sendMomentary(keyCode: Int, label: String) {
        val accepted = emulator.sendKey(keyCode, true)
        emulator.sendKey(keyCode, false)
        onSent(
            if (accepted) {
                "DOS key: $label"
            } else {
                "DOS core is not running"
            },
        )
    }

    private object RetroKey {
        const val ENTER = 13
        const val ESCAPE = 27
        const val SPACE = 32
        const val UP = 273
        const val DOWN = 274
        const val RIGHT = 275
        const val LEFT = 276
    }
}
