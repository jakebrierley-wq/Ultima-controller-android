/*
 * SPDX-License-Identifier: GPL-2.0-or-later
 */
package com.jakebrierley.ultimacontroller

interface EmulatorInput {
    fun sendKey(keyCode: Int, down: Boolean): Boolean
}
