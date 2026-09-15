package com.par9uet.jm.data.models

data class Comment(
    val userId: Int,
    val comicId: Int,
    val id: Int,
    val time: String,
    val content: String,
    val username: String,
    val nickname: String,
    val avatar: String,
    val parentId: Int,
    val spoiler: Boolean,
    val replyCommentList: List<Comment>,
    val sourceComicName: String = "",
    val sourceChapterId: String = "",
    val sourceBlogId: String = "",
) {
    /**
     * 评论在列表与分页里的唯一身份。详情评论区、我的评论、两边的去重器共用这一个定义，
     * 避免多处各自拼 key 后漂移。
     *
     * [id] 是上游的 CID。mapper 在 CID 缺失时写 0（`toIntOrZero`），此时退回
     * 「漫画 + 章节 + 时间 + 内容」组合：既不会把一批 id=0 的评论误判成同一条，
     * 也不会像只认 [id] 那样直接丢掉它们。
     *
     * 注意：这个值会作为 LazyList/LazyGrid 的 key（等同 SubcomposeLayout 的 slotId），
     * 同一屏内出现两次会直接抛 IllegalArgumentException。
     */
    val identityKey: Any
        get() = if (id != 0) {
            id
        } else {
            "$comicId:$sourceChapterId:$time:${content.hashCode()}"
        }
}
