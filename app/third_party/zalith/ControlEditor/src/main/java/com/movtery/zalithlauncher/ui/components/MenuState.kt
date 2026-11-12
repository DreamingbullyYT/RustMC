package com.movtery.zalithlauncher.ui.components
enum class MenuState {
    NONE { override fun next() = SHOW },
    SHOW { override fun next() = HIDE },
    HIDE { override fun next() = SHOW };
    abstract fun next(): MenuState
}
