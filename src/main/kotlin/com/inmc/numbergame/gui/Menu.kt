package com.inmc.numbergame.gui

import com.inmc.numbergame.Ng
import net.kyori.adventure.text.Component

/**
 * core 의 [kr.inmc.core.gui.Menu] 에 이 플러그인의 서비스 로케이터를 다시 붙인 얇은 층.
 *
 * core 는 `Ng` 를 알지 못하고 알 필요도 없다. 반대로 이 플러그인의 메뉴들은 `ng` 로
 * 레지스트리·설정에 닿아야 한다. 그 둘을 잇는 것이 이 파일의 전부이며, 덕분에 기존
 * 메뉴 구현들은 한 줄도 바뀌지 않았다.
 */
abstract class Menu(
    protected val ng: Ng,
    size: Int,
    title: Component,
) : kr.inmc.core.gui.Menu(size, title) {

    /** 리로드 때 열린 화면을 닫는 청소가 이 값으로 우리 것을 가려낸다. */
    override val owner: Any get() = ng
}
