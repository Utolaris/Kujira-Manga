package com.par9uet.jm.di

import com.par9uet.jm.repository.ComicRepository
import com.par9uet.jm.data.models.Comic
import com.par9uet.jm.data.models.WeekData
import com.par9uet.jm.core.network.NetWorkResult
import com.par9uet.jm.storage.ContentPreferences
import com.par9uet.jm.storage.RecommendationPreferences
import com.par9uet.jm.ui.viewModel.HomeViewModel
import com.par9uet.jm.ui.viewModel.SearchViewModel
import com.par9uet.jm.ui.viewModel.WeekViewModel
import java.lang.reflect.Proxy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.*
import org.junit.Assert.*
import org.junit.Test
import org.koin.dsl.koinApplication
import org.koin.dsl.module

@OptIn(ExperimentalCoroutinesApi::class)
class CatalogViewModelWiringTest {
    @Test fun `production bindings resolve independent home search and week state domains`() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val calls = mutableListOf<String>()
        val repository = Proxy.newProxyInstance(ComicRepository::class.java.classLoader, arrayOf(ComicRepository::class.java)) { _, method, _ ->
            calls += method.name
            when (method.name) {
                "getWeekData" -> NetWorkResult.Success(WeekData(
                    categoryList = listOf("week" to "本周"),
                    typeList = listOf("hot" to "热门"),
                ))
                "getEmbeddedHomeCategory" -> NetWorkResult.Success(emptyList<Comic>())
                // Search/Week Pager 经 stateIn(Eagerly) 在 VM 存活期就会取第一页。
                "getComicList" -> NetWorkResult.Success(
                    com.par9uet.jm.data.models.ComicSearchPage(emptyList(), 0, null),
                )
                else -> error("Unexpected request ${method.name}")
            }
        } as ComicRepository
        val settings = object : ContentPreferences, RecommendationPreferences,
            com.par9uet.jm.storage.BlockedTagTemplatePreferences,
            com.par9uet.jm.storage.MiscSettingsPreferences {
            override val blockedTags = MutableStateFlow(emptyList<String>())
            override val preferenceRecommendEnabled = MutableStateFlow(false)
            override val blockedTagTemplates =
                MutableStateFlow(emptyList<com.par9uet.jm.data.models.BlockedTagTemplate>())
            override val misc = MutableStateFlow(com.par9uet.jm.storage.MiscSettingsState())
        }
        val historySearchManager = com.par9uet.jm.storage.HistorySearchManager(
            object : com.par9uet.jm.storage.HistorySearchStore {
                private var items: List<String>? = emptyList()
                override fun getOrNull(): List<String>? = items
                override fun set(list: List<String>) { items = list }
                override fun remove() { items = emptyList() }
            }
        )
        val app = koinApplication {
            modules(comicModule, module {
                single<ComicRepository> { repository }
                single<ContentPreferences> { settings }
                single<RecommendationPreferences> { settings }
                single<com.par9uet.jm.storage.BlockedTagTemplatePreferences> { settings }
                single<com.par9uet.jm.storage.MiscSettingsPreferences> { settings }
                single { historySearchManager }
            })
        }
        try {
            val home = app.koin.get<HomeViewModel>()
            val search = app.koin.get<SearchViewModel>()
            val week = app.koin.get<WeekViewModel>()
            assertTrue(calls.isEmpty())
            search.changeSearchComicContent("saved query", listOf("excluded"))
            val generation = search.searchViewportState.value.resetGeneration
            search.saveSearchViewport(42, 8, generation)
            home.refreshHome()
            week.getWeekData()
            advanceUntilIdle()
            assertTrue(calls.containsAll(listOf("getEmbeddedHomeCategory", "getWeekData")))
            assertTrue(calls.none { it !in setOf("getEmbeddedHomeCategory", "getWeekData", "getComicList") })
            assertEquals("saved query", search.searchComicFilterState.value.searchContent)
            assertEquals(42, search.searchViewportState.value.firstVisibleItemIndex)
            assertEquals("week", week.weekFilterState.value.categoryId)
            assertEquals("hot", week.weekFilterState.value.typeId)
            week.changeWeekCategoryFilter("another")
            assertEquals("builtin_week_hot", home.homeState.value.selectedCategoryId)
            assertEquals(generation, search.searchViewportState.value.resetGeneration)
        } finally {
            // Eagerly 的 stateIn 在 close 时取消；必须先排空 Main 上的收尾再 resetMain。
            app.close()
            advanceUntilIdle()
            Dispatchers.resetMain()
        }
    }
}
