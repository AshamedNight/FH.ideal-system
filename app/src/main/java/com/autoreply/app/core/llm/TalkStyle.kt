package com.autoreply.app.core.llm

/**
 * 「这一阵子用哪种语气说话」。
 *
 * ## 为什么不做成给样例打标签
 *
 * 更"准"的做法是给 `persona.json` 里每条样例加一个 `style` 字段，按风格筛样例。
 * 没这么做的原因有两个，都是实际成本：
 *   - 现有 28 条样例得逐条标注，以后**每加一条都要标**，漏标就静默失效
 *   - 筛选样例会让 system 提示词的内容随风格整段变化，
 *     等于每换一次风格就丢一次前缀缓存（见 [ReplyEngine.buildSystem] 的注释）
 *
 * 现在这种做法是把风格做成**一段附加指令**：样例不动，只在 system 里多几行。
 * 风格切换时缓存失效一次，之后稳定。
 *
 * ## 一个必须说清的边界
 *
 * **风格只对真模型有效。** 演练模式（[com.autoreply.app.core.AppConfig.drillMode]）
 * 走的是本机假模型，按样例表和场景池拼句子，它不读提示词 ——
 * 在演练模式里点风格会发现"没有变化"，那不是坏了，是假模型的边界。
 * 输入法面板上会直接把这句话显示出来，免得以为是 bug。
 */
object TalkStyle {

    /**
     * @param id 存进 SharedPreferences 的值，**改了就是数据迁移**，别改
     * @param label 面板上显示的两个字，超过三个字会挤
     * @param hint 追加到 system 提示词的一段。**空串表示不加任何指令**
     */
    data class Item(
        val id: String,
        val label: String,
        val hint: String,
    )

    /**
     * 顺序就是面板上的顺序。
     *
     * 「自然」放第一位且 hint 为空：它是基线，不加指令就是现在的说话方式 ——
     * 老用户升级上来看到的就是它，提示词一个字都没变，缓存也不用重建。
     */
    val ALL = listOf(
        Item("natural", "自然", ""),
        Item(
            "soft", "温柔",
            "语气软一点：多用「呀、呢、吧」这类轻声词，先接住对方的情绪再往下说，不急着给结论。",
        ),
        Item(
            "playful", "俏皮",
            "带点玩笑感：可以自嘲、可以打岔，偶尔一个梗。不油腻、不硬撩、不用网络烂词。",
        ),
        Item(
            "cool", "高冷",
            "话少，句子短。不主动讨好，不连发问句。对方问什么就答什么，答完就停。",
        ),
        Item(
            "direct", "直接",
            "有话直说：想问就问，想提就提，不要绕一大圈铺垫。但直接不等于失礼。",
        ),
    )

    /** 默认风格。和 [com.autoreply.app.core.AppConfig.talkStyle] 的默认值必须一致。 */
    const val DEFAULT_ID = "natural"

    /** 认不出来的 id 一律回落到默认，绝不返回 null —— 掉进默认总比崩掉好。 */
    fun byId(id: String): Item = ALL.firstOrNull { it.id == id } ?: ALL[0]

    fun hintOf(id: String): String = byId(id).hint
}
