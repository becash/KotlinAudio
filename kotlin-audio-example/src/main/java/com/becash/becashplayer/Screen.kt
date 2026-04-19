package com.becash.becashplayer

sealed class Screen {
    object Main : Screen()
    object Settings : Screen()
}
