package com.movtery.zalithlauncher.utils.string
fun String.isNotEmptyOrBlank(): Boolean = isNotEmpty() && isNotBlank()
fun String.isEmptyOrBlank(): Boolean = isEmpty() || isBlank()
fun Throwable.getMessageOrToString(): String = message?.takeIf { it.isNotBlank() } ?: toString()
