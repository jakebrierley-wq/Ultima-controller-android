package com.jakebrierley.ultimacontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmulatorBridgeTest {
    @Test
    fun sendsAsciiAsBalancedLowercaseKeyEvents() {
        val input = RecordingInput()
        val messages = mutableListOf<String>()
        val bridge = EmulatorBridge(input, messages::add)

        bridge.sendAscii('A')

        assertEquals(
            listOf(KeyEventRecord('a'.code, true), KeyEventRecord('a'.code, false)),
            input.events,
        )
        assertEquals(listOf("DOS key: A"), messages)
    }

    @Test
    fun preservesDirectionPressAndRelease() {
        val input = RecordingInput()
        val bridge = EmulatorBridge(input) {}

        bridge.setDirection("LEFT", true)
        bridge.setDirection("LEFT", false)

        assertEquals(
            listOf(KeyEventRecord(276, true), KeyEventRecord(276, false)),
            input.events,
        )
    }

    @Test
    fun reportsWhenCoreDoesNotAcceptInput() {
        val messages = mutableListOf<String>()
        val bridge = EmulatorBridge(RecordingInput(accept = false), messages::add)

        bridge.sendSpecial("ENTER")

        assertTrue(messages.single().contains("not running"))
    }

    @Test
    fun releasesAllDirectionsForLifecyclePause() {
        val input = RecordingInput()
        val bridge = EmulatorBridge(input) {}

        bridge.releaseDirections()

        assertEquals(
            listOf(273, 274, 276, 275),
            input.events.map(KeyEventRecord::keyCode),
        )
        assertTrue(input.events.none(KeyEventRecord::down))
    }

    private class RecordingInput(
        private val accept: Boolean = true,
    ) : EmulatorInput {
        val events = mutableListOf<KeyEventRecord>()

        override fun sendKey(keyCode: Int, down: Boolean): Boolean {
            events += KeyEventRecord(keyCode, down)
            return accept
        }
    }

    private data class KeyEventRecord(
        val keyCode: Int,
        val down: Boolean,
    )
}
