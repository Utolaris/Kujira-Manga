package com.par9uet.jm.dinner

import com.par9uet.jm.dinner.atom.ArticlePromotionVerdict
import com.par9uet.jm.dinner.atom.ArticlePromotionVerdict.*
import com.par9uet.jm.dinner.atom.articlePromotionVerdict
import org.junit.Assert.assertEquals
import org.junit.Test

class ArticlePromotionFilterTest {
    @Test fun `known promotion copy handles HTML whitespace fullwidth and traditional text`() {
        assertVerdicts(BLOCK, listOf(
            "风月 AI", "風月ＡＩ", "风\u200b月 aI", "注册 VIP", "註冊\nＶＩＰ", "注册&nbsp;vip",
            "<p>风<span>月</span> <b>AI</b></p>", "<p>注册</p><p>VIP</p>",
            "▼ cos app下载落地页\n在此立即点击下载", "ＣＯＳ ＡＰＰ 下載落地頁",
            "点我领取：50元 大额无门槛优惠卷（付款直接抵扣）",
            "點我領取：50元大額無門檻優惠券", "点开下面的优惠卷连结",
            "加入会员时记得填写註册码 R18COMIC", "注册碼Ｒ１８ＣＯＭＩＣ",
            "购买连结\n派蒙商品\n心动就赶紧下单吧！", "購買連結\n商品\n心動就趕緊下單吧！",
        ))
    }

    @Test fun `unsigned old articles unknown authors ordinary reviews and official notices remain readable`() {
        assertVerdicts(ALLOW, listOf(
            "", "正常旧文章，没有署名", "作者：gugu", "食堂作者：未知人", "作者：cos，正常漫画推荐",
            "风月同天，AI 工具简介", "VIP 角色的故事", "注册账号帮助", "漫画情节中提到了优惠券和充值",
            "<a href='/album/1245087/'>传送门</a>", "<a href='/blog/2196/'>扩展阅读</a>",
            "<a href='https://bangumi.tv/person/32133'>左藤空气</a>",
            "<a href='https://ppt.cc/frejtx'>参考资料</a>",
            "<a href='https://h18comicorg.pixnet.net/blog/post/305861036'>原博客</a>",
            "<a href='/stray/'>新APP下载</a>", "<a href='/payment?utm_source=platform'>官方赞助渠道</a>",
            "<a href='https://example.com/article?utm_source=18comic'>资料来源</a>",
            "<a href='https://www.dlsite.com/maniax/work/=/product_id/RJ353002.html'>作品资料</a>",
            "故事中的注册验证码是 123456", "漫画中的角色说：心动就赶紧下单吧！",
        ))
    }

    @Test fun `known author and comic portals do not exempt mixed promotional articles`() {
        assertEquals(BLOCK, articlePromotionVerdict("食堂作者：木木火\n风月 AI"))
        assertEquals(BLOCK, articlePromotionVerdict("<a href='/album/1101710/'>传送门</a>" +
            "<a href='https://u.jd.com/crR3dAv'>点我领取优惠卷</a>"))
    }

    @Test fun `confirmed promotional URLs are detected in anchors bare text and game metadata`() {
        val urls = listOf(
            "https://789free.fun/pRW9Ur", "https://cos-sdg.org/?utm_source=18comic",
            "https://fengyue.ai/zh?ref_id=test", "https://u.jd.com/crR3dAv",
            "https://taoquan.taobao.com/coupon/unify_apply.htm?sellerId=1",
            "https://www.dp.toys/products/123-004019/?sl-ref=od102&utm_source=18comic",
            "https://www.hotkrowdplay.site/gr/index.html?ag=18game",
            "https://attdownload-1.com/xproject/?attributionId=126",
            "https://m.xrapk123.com/dl.php?a=126",
            "https://landing.teamhorny.store/orisries/2?ph_source=sin01",
            "https://l.sqzkm.com/s/0Ac1Kc", "https://l.hyenadata.com/s/0Ac1Kc",
            "https://l.tapdb.net/lFCuAMJ9?subc1=blog", "https://url365.club/pzx2Yv",
            "https://url365.club/F3b44f",
        )
        for (url in urls) {
            assertEquals(url, BLOCK, articlePromotionVerdict("<a href='$url'><img src='/image.jpg'></a>"))
            assertEquals(url, BLOCK, articlePromotionVerdict("原文链接：$url"))
            assertEquals(url, BLOCK, articlePromotionVerdict("介绍内容", url))
        }
    }

    @Test fun `URL checks use exact host boundaries and verified short paths rather than substring matches`() {
        assertVerdicts(BLOCK, listOf(
            "<a href='//FENGYUE.AI/'>体验</a>", "<a href='https://sub.fengyue.ai./'>体验</a>",
            "<a href='https://example.com@fengyue.ai/'>体验</a>",
            "<a href='https://url365.club/pzx2Yv/?utm_source=blog'>体验</a>",
        ))
        assertVerdicts(ALLOW, listOf(
            "<a href='https://notfengyue.ai/'>资料</a>", "<a href='https://fengyue.ai.example.com/'>资料</a>",
            "<a href='https://example.com/fengyue.ai'>资料</a>",
            "<a href='https://fengyue.ai@example.com/'>资料</a>",
            "<a href='https://url365.club/another'>资料</a>",
            "<a href='https://url365.club/pzx2Yv-extra'>资料</a>",
            "<a href='https://url365.club/pzx2yv'>资料</a>",
            "<a href='https://l.tapdb.net/other'>资料</a>",
        ))
    }

    @Test fun `hidden code and image source attributes are not promotional text or navigation targets`() {
        assertVerdicts(ALLOW, listOf(
            "<script>注册VIP; https://fengyue.ai</script><p>正文</p>",
            "<style>风月AI</style><p>正文</p>",
            "<noscript><a href='https://789free.fun/test'>推广</a></noscript><p>正文</p>",
            "<img alt='注册VIP' src='https://cos-sdg.org/image.jpg'>",
            "<a href='https://example.com/注册VIP'>正文链接</a>",
        ))
    }

    @Test fun `ambiguous calls to action request a link check without being treated as confirmed ads`() {
        assertVerdicts(CHECK_LINKS, listOf(
            "游戏下载网址", "下载连结：", "立即体验新功能", "官方旗舰店", "购买链接请至这里",
            "点击进入查看详情", "游戏请点此游玩", "領券方法介紹",
        ))
        assertEquals(CHECK_LINKS, articlePromotionVerdict("<a href='/stray/'>下载链接</a>"))
    }

    @Test fun `January author reference remains readable without any author restriction`() {
        val fixture = javaClass.getResourceAsStream("/dinner/author-reference-2102.json")!!.bufferedReader().use { it.readText() }
        val data = com.google.gson.JsonParser.parseString(fixture).asJsonObject
        for (field in listOf("listContent", "detailContent")) {
            assertEquals(ALLOW, articlePromotionVerdict(data.get(field).asString))
        }
    }

    private fun assertVerdicts(expected: ArticlePromotionVerdict, contents: List<String>) {
        for (content in contents) assertEquals(content, expected, articlePromotionVerdict(content))
    }
}
