package com.jakebrierley.ultimacontroller

data class Command(val label: String, val key: Char)

object Commands {
    val all = listOf(
        Command("ATTACK", 'a'), Command("BOARD", 'b'), Command("CAST", 'c'),
        Command("DROP", 'd'), Command("ENTER", 'e'), Command("FIRE", 'f'),
        Command("GET", 'g'), Command("HYPERJUMP", 'h'), Command("INFORM", 'i'),
        Command("OPEN", 'o'), Command("QUIT/SAVE", 'q'), Command("READY", 'r'),
        Command("STEAL", 's'), Command("TRANSACT", 't'), Command("UNLOCK", 'u'),
        Command("VIEW", 'v'), Command("WAIT", 'w'), Command("X-IT", 'x')
    )
}
