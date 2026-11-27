package com.autoreply.app.core.memory

import com.autoreply.app.core.llm.Turn

/**
 * 从对方说过的话里抽「值得长期记住」的事实。
 *
 * ## 为什么不调模型
 *
 * 抽事实每轮都要做一次。调模型意味着每轮多一次请求：慢、可能失败、
 * 还要在主链路上处理异常 —— 而这里要的东西其实很低级：
 * 年龄、城市、职业、婚育、诉求、雷区，全是可以用正则和关键词命中的。
 *
 * 真抽不到就不记（宁缺勿错）。模型自己会从最近的对话里读到细节；
 * 这里只兜住"跨会话、跨重启之后还能想起来的骨架"。
 *
 * ## 置信度
 *
 * 正则硬命中（"28岁"）给 0.9；关键词沾边（说过"美容"就当职业）给 0.7。
 * 只有 ≥ [MIN_CONFIDENCE] 的才会进提示词，其余留在库里等人看。
 */
object FactExtractor {

    /** 低于这个值不进提示词 —— 猜错的代价是模型一本正经地报错信息。 */
    const val MIN_CONFIDENCE = 0.7f

    data class Fact(val key: String, val value: String, val confidence: Float)

    /**
     * 城市表。不做省市区识别，只认常见市名 —— 认错了比认不出更糟。
     * 加城市时直接往这里追加，别改逻辑。
     */
    private val CITIES = listOf(
        "淄博", "济南", "青岛", "潍坊", "烟台", "临沂", "济宁", "泰安", "威海", "日照",
        "东营", "滨州", "德州", "聊城", "菏泽", "枣庄",
        "北京", "上海", "广州", "深圳", "杭州", "南京", "苏州", "成都", "重庆", "武汉",
        "西安", "郑州", "天津", "石家庄", "长沙", "昆明", "曲靖", "合肥", "福州", "厦门",
        "南昌", "太原", "哈尔滨", "长春", "沈阳", "大连", "兰州", "贵阳", "南宁", "海口",
    )

    /** 职业词表：命中即记，不试图从句子里切出完整职位（切错比记"做美容"更糟）。 */
    private val JOBS = listOf(
        "美容", "美甲", "护士", "护工", "医生", "老师", "幼师", "销售", "客服", "会计",
        "出纳", "服务员", "工厂", "快递", "外卖", "电商", "主播", "保险", "房产", "银行",
        "餐饮", "前台", "文员", "司机", "保洁", "月嫂", "理疗", "药房", "超市", "服装",
    )

    private val AGE = Regex("""(\d{1,2})\s*岁""")

    /** "今年 28" 这种也算，但只在没有"28岁"时兜底。 */
    private val AGE_ALT = Regex("""今年\s*(\d{1,2})""")

    fun extract(turns: List<Turn>): List<Fact> {
        val said = turns.filter { it.fromOther }
            .joinToString("\n") { it.text }
        if (said.isBlank()) return emptyList()

        val out = LinkedHashMap<String, Fact>()
        fun put(key: String, value: String, conf: Float) {
            if (value.isNotBlank()) out[key] = Fact(key, value, conf)
        }

        // 年龄：14~60 之外一律当误命中（"3 岁"多是孩子）
        val age = (AGE.find(said) ?: AGE_ALT.find(said))
            ?.groupValues?.getOrNull(1)?.toIntOrNull()
            ?.takeIf { it in 14..60 }
        if (age != null) put("age", "$age 岁", 0.9f)

        CITIES.firstOrNull { said.contains(it) }?.let { put("city", it, 0.8f) }

        JOBS.firstOrNull { said.contains(it) }?.let { put("job", "做$it", 0.7f) }

        // 婚育：顺序敏感 —— "离婚"里含"婚"，先判离/结
        when {
            said.contains("离婚") || said.contains("离异") -> put("marriage", "离异", 0.9f)
            said.contains("已婚") || said.contains("结过婚") -> put("marriage", "已婚", 0.9f)
            said.contains("单身") || said.contains("没对象") -> put("marriage", "单身", 0.7f)
        }
        val kid = listOf("女儿", "儿子", "孩子", "娃").firstOrNull { said.contains(it) }
        if (kid != null) {
            // 孩子年龄：同一句里找最近的"数字岁"
            val kidAge = Regex("""$kid[^。！\n]{0,6}?(\d{1,2})\s*岁""")
                .find(said)?.groupValues?.getOrNull(1)
            put("kid", if (kidAge != null) "有$kid $kidAge 岁" else "有$kid", 0.8f)
        }

        // 诉求：她要什么，比她的资料更影响"该怎么聊"
        when {
            said.contains("找对象") || said.contains("找老公") || said.contains("找老婆") ||
                said.contains("结婚") -> put("want", "想找对象", 0.8f)
            said.contains("无聊") || said.contains("寂寞") || said.contains("找人聊") ||
                said.contains("陪聊") -> put("want", "想找人聊天", 0.7f)
        }

        // 雷区：只取"讨厌/不喜欢/别叫/别问"后面几个字，够模型避开就够了
        Regex("""(讨厌|不喜欢|受不了|别叫|别问|反感)([\u4e00-\u9fa5A-Za-z]{1,6})""")
            .find(said)?.let { m ->
                put("avoid", "不喜欢「${m.groupValues[2]}」", 0.8f)
            }

        return out.values.toList()
    }

    /** 给提示词看的一行：key 转成人话，只留高置信。 */
    fun describe(f: Fact): String = f.value

    /** 事实的固定顺序：稳定的顺序能让上游的前缀缓存更愿意命中。 */
    val ORDER = listOf("age", "city", "job", "marriage", "kid", "want", "avoid")
}
