/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.jakebrierley.ultimacontroller

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GameStorageTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun findsRootExecutableCaseInsensitively() {
        val gameDirectory = temporaryFolder.newFolder("game")
        val executable = gameDirectory.resolve("ultima.exe").apply {
            writeBytes(byteArrayOf(1, 2, 3))
        }

        assertEquals(
            executable,
            GameStorage.findRequiredExecutable(gameDirectory),
        )
    }

    @Test
    fun ignoresDirectoryNamedLikeExecutable() {
        val gameDirectory = temporaryFolder.newFolder("game")
        gameDirectory.resolve("ULTIMA.EXE").mkdir()

        assertNull(GameStorage.findRequiredExecutable(gameDirectory))
    }
}
