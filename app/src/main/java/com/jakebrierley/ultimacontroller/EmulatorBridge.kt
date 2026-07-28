package com.jakebrierley.ultimacontroller

/**
 * Milestone-1 test bridge. Replace sendAscii/sendSpecial with calls into the
 * embedded DOSBox keyboard queue in milestone 2.
 */
class EmulatorBridge(private val onSent: (String) -> Unit) {
    fun sendAscii(char: Char) = onSent("DOS key: ${char.uppercaseChar()}")
    fun sendDirection(name: String) = onSent("DOS direction: $name")
    fun sendSpecial(name: String) = onSent("DOS special: $name")
}
