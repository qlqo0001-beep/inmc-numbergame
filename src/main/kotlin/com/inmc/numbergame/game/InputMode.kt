package com.inmc.numbergame.game

import com.inmc.numbergame.game.settings.ChoiceSetting

/**
 * How a game asks the player for an answer.
 *
 * Dialogs are the default and the point of this plugin, but they have one structural limit: a
 * shown dialog cannot be edited. Every turn therefore closes the screen and reopens it a tick
 * later, which is invisible in a game with a handful of turns and intolerable in one with thirty.
 */
enum class InputMode {

    /** The screen everyone else uses: full body text, typed answer, buttons. */
    DIALOG,

    /**
     * Question on the action bar, answer in chat.
     *
     * The action bar updates **in place**, so a rapid-fire game runs without the screen flashing
     * once per question. It is the reason the original spec said "except where a dialog is
     * awkward" - this is that case.
     */
    FAST;

    companion object {

        fun parse(raw: String?): InputMode =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: DIALOG

        /**
         * The shared setting, so every engine that offers a choice spells it the same way.
         *
         * Engines that only make sense one way simply do not put this in their schema.
         */
        fun setting(default: InputMode): ChoiceSetting = ChoiceSetting(
            key = "input-mode",
            label = "입력 방식",
            help = "빠른 입력은 문제를 액션바에 띄우고 답을 채팅으로 받습니다. 화면이 깜빡이지 않아 " +
                "빠르게 연속으로 푸는 게임에 맞습니다.",
            default = default.name,
            options = listOf(
                DIALOG.name to "다이얼로그 (창)",
                FAST.name to "빠른 입력 (액션바 + 채팅)",
            ),
        )
    }
}
