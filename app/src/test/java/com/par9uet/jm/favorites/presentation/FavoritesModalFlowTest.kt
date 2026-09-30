package com.par9uet.jm.favorites.presentation

import com.par9uet.jm.favorites.model.FavoritesIntent
import com.par9uet.jm.favorites.model.FavoritesModal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class FavoritesModalFlowTest {
    @Test
    fun `folder management can reach every folder action modal and return to management`() {
        var modal: FavoritesModal? = reduceFavoritesModal(
            current = null,
            intent = FavoritesIntent.FolderManagementOpened,
        )
        assertEquals(FavoritesModal.FolderManagement, modal)

        modal = reduceFavoritesModal(modal, FavoritesIntent.CreateFolderOpened)
        assertEquals(FavoritesModal.CreateFolder, modal)
        modal = reduceFavoritesModal(modal, FavoritesIntent.FolderActionDismissed)
        assertEquals(FavoritesModal.FolderManagement, modal)

        modal = reduceFavoritesModal(
            modal,
            FavoritesIntent.RenameFolderOpened(folderId = 7, folderName = "Old"),
        )
        assertEquals(FavoritesModal.RenameFolder(7, "Old"), modal)
        modal = reduceFavoritesModal(modal, FavoritesIntent.FolderActionDismissed)
        assertEquals(FavoritesModal.FolderManagement, modal)

        modal = reduceFavoritesModal(
            modal,
            FavoritesIntent.DeleteFolderOpened(folderId = 7, folderName = "Old"),
        )
        assertEquals(FavoritesModal.DeleteFolder(7, "Old"), modal)
        modal = reduceFavoritesModal(modal, FavoritesIntent.FolderActionDismissed)
        assertEquals(FavoritesModal.FolderManagement, modal)

        assertEquals(null, reduceFavoritesModal(modal, FavoritesIntent.FolderManagementDismissed))
    }

    @Test
    fun `selection actions keep the current modal instead of opening an invisible one`() {
        // current 必须非 null，否则 reduceFavoritesModal 里的 `else current` 与 `else null`
        // 无法区分——原来的写法用 current = null，把这条契约洗成了恒真。
        assertEquals(
            FavoritesModal.Filter,
            reduceFavoritesModal(FavoritesModal.Filter, FavoritesIntent.MoveSelected, hasSelection = false),
        )
        assertEquals(
            FavoritesModal.Filter,
            reduceFavoritesModal(FavoritesModal.Filter, FavoritesIntent.UncollectSelected, hasSelection = false),
        )
        // 真的选中之后，两个入口才允许开弹窗。
        assertEquals(
            FavoritesModal.Move,
            reduceFavoritesModal(FavoritesModal.Filter, FavoritesIntent.MoveSelected, hasSelection = true),
        )
        assertEquals(
            FavoritesModal.Uncollect,
            reduceFavoritesModal(FavoritesModal.Filter, FavoritesIntent.UncollectSelected, hasSelection = true),
        )
        // 与弹窗无关的 intent 既不能开弹窗，也不能关掉当前弹窗。
        assertEquals(
            FavoritesModal.Filter,
            reduceFavoritesModal(FavoritesModal.Filter, FavoritesIntent.SearchChanged("q")),
        )
        assertEquals(
            null,
            reduceFavoritesModal(null, FavoritesIntent.SearchChanged("q")),
        )
    }

    @Test
    fun `batch toast copy switches template on failure instead of interpolating the action`() {
        assertEquals("已移动 3 部漫画", favoriteBatchMessage(succeeded = 3, failed = 0, action = "移动"))
        assertEquals("已取消收藏 1 部漫画", favoriteBatchMessage(succeeded = 1, failed = 0, action = "取消收藏"))

        // 只要有失败就换成「成功/失败」模板：action 不再出现在文案里。
        val mixed = favoriteBatchMessage(succeeded = 2, failed = 1, action = "移动")
        assertEquals("成功 2 部，失败 1 部", mixed)
        assertFalse("失败分支不得再拼 action：$mixed", mixed.contains("移动"))
        assertEquals("成功 0 部，失败 2 部", favoriteBatchMessage(succeeded = 0, failed = 2, action = "移动"))
    }
}
