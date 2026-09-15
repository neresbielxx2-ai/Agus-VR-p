package com.lunarvr.interaction

/** Menu/system command ids dispatched by the interaction layer. */
object Commands {
    const val NONE = 0
    const val START = 1          // [ INICIAR ] — close menus, enter the experience
    const val OPEN_APPS = 2
    const val OPEN_SETTINGS = 3
    const val OPEN_SYSTEM = 4
    const val OPEN_ABOUT = 5
    const val OPEN_CLOCK = 6
    const val OPEN_TUTORIAL = 7
    const val CLOSE = 8          // close the current menu (VOLTAR)
    const val CLOSE_FLOATING = 9 // close a floating panel (clock)
    const val QUIT = 10          // exit the app
    const val OPEN_MAIN = 11     // from SystemMenu
    const val CALIBRATE = 12     // [ CALIBRAR VISÃO ]
    const val RESET_ORIENT = 13  // [ RESET ORIENTAÇÃO ]
    const val TOGGLE_SBS = 14
    const val TOGGLE_HT = 15
    const val TOGGLE_RAY = 16
    const val TOGGLE_SOUNDS = 17
    const val HAND_SIDE = 18     // cycle both/right/left
    const val GRAPHICS = 19      // arg 0/1/2
    const val SLIDER_IPD = 20
    const val SLIDER_HEAD_SENS = 21
    const val SLIDER_HEAD_SMOOTH = 22
    const val SLIDER_PINCH = 23
    const val SLIDER_HAND_SMOOTH = 24
    const val SLIDER_VOLUME = 25
}
