package com.par9uet.jm.dinner.atom

import java.text.Normalizer
import java.util.Locale
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import org.jsoup.Jsoup

internal enum class ArticlePromotionVerdict { ALLOW, BLOCK, CHECK_LINKS }

/** 列表的纯文本与详情 HTML 共用规则；署名、上传者和普通外链均不决定去留。 */
internal fun articlePromotionVerdict(content: String, vararg links: String): ArticlePromotionVerdict {
    val document = Jsoup.parseBodyFragment(content)
    document.select("script,style,noscript").remove()
    val text = document.body().wholeText()
    val normalized = Normalizer.normalize(text, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT).replace(PROMOTION_SPACING, "")
    if (PROMOTION_PHRASES.any(normalized::contains) || COS_DOWNLOAD.containsMatchIn(normalized) ||
        COUPON_CLAIM.containsMatchIn(normalized) || REFERRAL_SIGNUP.containsMatchIn(normalized) ||
        (PURCHASE_LINK.containsMatchIn(normalized) && ORDER_PROMPT.containsMatchIn(normalized))) {
        return ArticlePromotionVerdict.BLOCK
    }

    val urls = document.select("a[href]").eachAttr("href").asSequence() +
        BODY_URL.findAll(text).map { it.value } + links.asSequence()
    if (urls.any(::isPromotionUrl)) return ArticlePromotionVerdict.BLOCK
    return if (DETAIL_HINTS.any(normalized::contains)) ArticlePromotionVerdict.CHECK_LINKS
        else ArticlePromotionVerdict.ALLOW
}

private fun isPromotionUrl(raw: String): Boolean {
    val value = raw.trim().trimEnd('.', ',', ';', ')', '。', '，', '；', '）')
    val url = (if (value.startsWith("//")) "https:$value" else value).toHttpUrlOrNull() ?: return false
    val host = url.host.trimEnd('.')
    if (PROMOTION_HOSTS.any { host == it || host.endsWith(".$it") }) return true
    // 短链服务可能承载正常内容，只拦截实拉确认的推广路径，保留路径大小写。
    return url.encodedPath.trimEnd('/') in PROMOTION_SHORT_PATHS[host].orEmpty()
}

private val PROMOTION_SPACING = Regex("[\\s\\p{Z}\\u200B-\\u200D\\uFEFF]+")
private val PROMOTION_PHRASES = listOf("风月ai", "風月ai", "注册vip", "註冊vip", "註册vip", "注冊vip")
private val COS_DOWNLOAD = Regex("cosapp(?:下载|下載)落地[页頁]")
private val COUPON_CLAIM = Regex(
    "(?:点我|點我|点击|點擊|立即|下方).{0,16}(?:领取|領取|领|領).{0,40}(?:优惠|優惠)[券卷]" +
        "|(?:优惠|優惠)[券卷](?:链接|連結|连结|鏈接)",
)
private val REFERRAL_SIGNUP = Regex("[注註][册冊][码碼]r18comic")
private val PURCHASE_LINK = Regex("[购購][买買](?:链接|連結|连结|鏈接)")
private val ORDER_PROMPT = Regex("心[动動]就[赶趕][紧緊]下[单單]")
private val BODY_URL = Regex("(?:https?:)?//[^\\s<>\"'，。！？；（）【】]+", RegexOption.IGNORE_CASE)
// 2026-10-20 实拉确认：AI/视频推广、京东与淘宝领券、带归因的游戏下载入口。
private val PROMOTION_HOSTS = setOf(
    "fengyue.ai", "cos-sdg.org", "789free.fun", "u.jd.com", "taoquan.taobao.com", "dp.toys",
    "hotkrowdplay.site", "attdownload-1.com", "xrapk123.com", "landing.teamhorny.store",
)
private val PROMOTION_SHORT_PATHS = mapOf(
    "l.sqzkm.com" to setOf("/s/0Ac1Kc"),
    "l.hyenadata.com" to setOf("/s/0Ac1Kc"),
    "l.tapdb.net" to setOf("/lFCuAMJ9"),
    "url365.club" to setOf("/pzx2Yv", "/F3b44f"),
)
private val DETAIL_HINTS = listOf(
    "游戏下载", "遊戲下載", "下载链接", "下载连结", "下載連結", "下載鏈接",
    "购买链接", "购买连结", "購買連結", "購買鏈接", "游戏请点此游玩", "遊戲請點此遊玩",
    "立即体验", "立即體驗", "点击进入", "點擊進入", "领券", "領券", "领卷", "領卷",
    "官方旗舰店", "官方旗艦店",
)
