package com.par9uet.jm.ui.navigation

enum class MainTab(
    val index: Int,
    val route: String,
    val navigationLabel: String,
    val topBarTitle: String,
) {
    Home(
        index = 0,
        route = "home",
        navigationLabel = "首页",
        topBarTitle = "首页",
    ),
    Collect(
        index = 1,
        route = "collect",
        navigationLabel = "收藏",
        topBarTitle = "我的收藏",
    ),
    Settings(
        index = 2,
        route = "user",
        navigationLabel = "设置",
        topBarTitle = "设置",
    );

    companion object {
        val ordered: List<MainTab> = entries.sortedBy(MainTab::index)

        fun fromIndex(index: Int): MainTab = ordered[index]

        fun fromRoute(route: String?): MainTab? = entries.firstOrNull { it.route == route }
    }
}
