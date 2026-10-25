package io.personal.filemanager.core

import kotlin.math.ceil

/** Раскладка плиток главной: число колонок по ширине экрана и настройке пользователя. */
object TileLayout {
    /** Минимальная ширина плитки, при которой название помещается крупным шрифтом. */
    const val MIN_TILE_DP = 84
    /** Ширина, к которой стремится авто-режим (как в утверждённом макете на телефоне — 3 в ряд). */
    const val TARGET_TILE_DP = 104
    const val SPACING_DP = 10
    const val SIDE_PADDING_DP = 16

    private fun fit(widthDp: Int, tileDp: Int): Int {
        val available = widthDp - 2 * SIDE_PADDING_DP
        return ((available + SPACING_DP) / (tileDp + SPACING_DP)).coerceAtLeast(1)
    }

    /**
     * [setting] 0 — авто: не меньше 3 колонок, столько, сколько помещается плиток ~[TARGET_TILE_DP],
     * и при равном числе рядов — меньше колонок (ряды заполнены ровнее). Явное число уменьшается,
     * только если плитки стали бы уже [MIN_TILE_DP].
     */
    fun columns(setting: Int, widthDp: Int, tileCount: Int): Int {
        val maxFit = fit(widthDp, MIN_TILE_DP).coerceAtLeast(2)
        if (setting > 0) return setting.coerceAtMost(maxFit)
        val maxAuto = fit(widthDp, TARGET_TILE_DP).coerceIn(3, maxOf(3, maxFit))
        if (tileCount <= 0) return maxAuto
        val minRows = ceil(tileCount / maxAuto.toDouble()).toInt()
        return (3..maxAuto).first { ceil(tileCount / it.toDouble()).toInt() == minRows }
    }
}
