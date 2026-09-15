package com.lunarvr.ui

import android.graphics.RectF
import com.lunarvr.interaction.Commands

/**
 * An interactive element inside a spatial panel (texture-space rectangle +
 * behavior). Kinds:
 *  - BUTTON : single click (PINCH)
 *  - TOGGLE : ON/OFF
 *  - SLIDER : PINCH to set / hold to drag
 *  - VALUE  : cycles through [options]
 *  - LABEL  : non-interactive text
 *  - SECTION: section header
 *  - CLOCK  : live time display
 */
class UIItem {

    var kind: Int = KIND_LABEL
    var label: String = ""
    var icon: Int = Icons.NONE
    var rect = RectF()

    var command: Int = Commands.NONE
    var commandArg: Int = 0
    var enabled: Boolean = true

    // TOGGLE
    var boolValue: Boolean = false

    // SLIDER
    var floatValue: Float = 0f
    var floatMin: Float = 0f
    var floatMax: Float = 1f
    var valueText: String = ""
    var decimals: Int = 1
    var unit: String = ""

    // VALUE (cyclic)
    var options: List<String> = emptyList()
    var optionIndex: Int = 0

    var hovered: Boolean = false

    // LABEL extras
    var fontSize: Float = 26f
    var centered: Boolean = false

    fun setSlider(min: Float, max: Float, value: Float, decimals: Int = 1, unit: String = "") {
        kind = KIND_SLIDER
        floatMin = min; floatMax = max; floatValue = value
        this.decimals = decimals; this.unit = unit
        valueText = formatValue()
    }

    fun formatValue(): String {
        val v = if (decimals == 0) Math.round(floatValue).toString()
        else String.format("%.${decimals}f", floatValue)
        return if (unit.isEmpty()) v else "$v $unit"
    }

    fun cycleValue(): String {
        if (options.isEmpty()) return ""
        optionIndex = (optionIndex + 1) % options.size
        return options[optionIndex]
    }

    fun currentOption(): String =
        if (options.isEmpty()) "" else options.getOrElse(optionIndex) { "" }

    companion object {
        const val KIND_BUTTON = 0
        const val KIND_TOGGLE = 1
        const val KIND_SLIDER = 2
        const val KIND_VALUE = 3
        const val KIND_LABEL = 4
        const val KIND_SECTION = 5
        const val KIND_CLOCK = 6

        fun isInteractive(kind: Int): Boolean =
            kind == KIND_BUTTON || kind == KIND_TOGGLE || kind == KIND_SLIDER || kind == KIND_VALUE
    }
}
