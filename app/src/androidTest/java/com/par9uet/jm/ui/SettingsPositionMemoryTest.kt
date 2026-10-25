package com.par9uet.jm.ui

import androidx.activity.ComponentActivity
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import com.par9uet.jm.ui.interaction.rememberRestoringListState
import com.par9uet.jm.ui.interaction.rememberRestoringScrollState
import com.par9uet.jm.ui.models.ListPositionKeyLocalSettings
import com.par9uet.jm.ui.models.ListPositionKeySettings
import com.par9uet.jm.ui.models.ListPositionMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class SettingsPositionMemoryTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun settingsPositionsSurviveLeavingCompositionAndStayIndependent() {
        val memory = ListPositionMemory()
        val visible = mutableStateOf(true)
        lateinit var scroll: ScrollState
        lateinit var list: LazyListState
        lateinit var scope: CoroutineScope
        compose.setContent {
            if (visible.value) {
                scroll = rememberRestoringScrollState(ListPositionKeySettings, memory)
                list = rememberRestoringListState(ListPositionKeyLocalSettings, memory)
                scope = rememberCoroutineScope()
                Column {
                    Column(Modifier.height(120.dp).verticalScroll(scroll)) {
                        repeat(80) { Text("设置 $it", Modifier.height(40.dp)) }
                    }
                    LazyColumn(Modifier.height(120.dp), state = list) {
                        items(80) { Text("详细设置 $it", Modifier.height(40.dp)) }
                    }
                }
            } else Text("设置子页面")
        }
        compose.runOnIdle {
            scope.launch { scroll.scrollTo(480) }
            scope.launch { list.scrollToItem(12, 17) }
        }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(480, scroll.value)
            assertEquals(12, list.firstVisibleItemIndex)
            assertEquals(17, list.firstVisibleItemScrollOffset)
            visible.value = false
        }
        compose.waitForIdle()
        compose.runOnIdle { visible.value = true }
        compose.waitForIdle()
        compose.runOnIdle {
            assertEquals(480, scroll.value)
            assertEquals(12, list.firstVisibleItemIndex)
            assertEquals(17, list.firstVisibleItemScrollOffset)
        }
    }
}
