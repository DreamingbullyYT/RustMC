package com.movtery.zalithlauncher.setting.unit
fun IntRange.toFloatRange(): ClosedFloatingPointRange<Float> = first.toFloat()..last.toFloat()
fun ClosedFloatingPointRange<Int>.toFloatRange(): ClosedFloatingPointRange<Float> = start.toFloat()..endInclusive.toFloat()
