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
 *
 * ## 为什么每条都记来源（[Fact.src]）
 *
 * 抽错了要能一眼看出错在哪句（2026-11-28 真机：把我方说的"淄博"
 * 记成了对方的城市）。只显示结论的话，用户只能猜。
 */
object FactExtractor {

    /** 低于这个值不进提示词 —— 猜错的代价是模型一本正经地报错信息。 */
    const val MIN_CONFIDENCE = 0.7f

    data class Fact(
        val key: String,
        val value: String,
        val confidence: Float,
        /** 抽到它的那句原文（截断），给"会话记忆"页核对用。 */
        val src: String = "",
    )

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

    private val AVOID = Regex("""(讨厌|不喜欢|受不了|别叫|别问|反感)([\u4e00-\u9fa5A-Za-z]{1,6})""")

    /**
     * @param selfIdentity 人设里"我是谁"那段。用来挡一类必然发生的误抽：
     *   方向判错时（气泡在左侧就被当成对方），我方说的「我在淄博做美容」
     *   会被抽成"对方在淄博做美容"。对方的城市/职业正好和我一样的概率很低，
     *   而把这句当成我方说的概率不低 —— 所以命中人设的直接丢掉。
     */
    fun extract(turns: List<Turn>, selfIdentity: String = ""): List<Fact> {
        val mine = selfIdentity.replace("\n", " ")
        val out = LinkedHashMap<String, Fact>()

        fun put(key: String, value: String, conf: Float, src: String) {
            if (value.isBlank() || out.containsKey(key)) return
            out[key] = Fact(key, value, conf, src.take(30))
        }

        // 逐句扫，命中即定 —— 这样每条事实都能带上"从哪句来的"
        for (t in turns) {
            if (!t.fromOther) continue
            val s = t.text
            if (s.isBlank()) continue

            if (!out.containsKey("age")) {
                val age = (AGE.find(s) ?: AGE_ALT.find(s))
                    ?.groupValues?.getOrNull(1)?.toIntOrNull()
                    ?.takeIf { it in 14..60 }
                if (age != null) put("age", "$age 岁", 0.9f, s)
            }

            if (!out.containsKey("city")) {
                CITIES.firstOrNull { s.contains(it) }?.let { put("city", it, 0.8f, s) }
            }

            if (!out.containsKey("job")) {
                JOBS.firstOrNull { s.contains(it) }?.let { put("job", "做$it", 0.7f, s) }
            }

            if (!out.containsKey("marriage")) {
                when {
                    s.contains("离婚") || s.contains("离异") -> put("marriage", "离异", 0.9f, s)
                    s.contains("已婚") || s.contains("结过婚") -> put("marriage", "已婚", 0.9f, s)
                    s.contains("单身") || s.contains("没对象") -> put("marriage", "单身", 0.7f, s)
                }
            }

            if (!out.containsKey("kid")) {
                val kid = listOf("女儿", "儿子", "孩子", "娃").firstOrNull { s.contains(it) }
                if (kid != null) {
                    val kidAge = Regex("""$kid[^。！\n]{0,6}?(\d{1,2})\s*岁""")
                        .find(s)?.groupValues?.getOrNull(1)
                    put("kid", if (kidAge != null) "有$kid $kidAge 岁" else "有$kid", 0.8f, s)
                }
            }

            if (!out.containsKey("want")) {
                when {
                    s.contains("找对象") || s.contains("找老公") || s.contains("找老婆") ||
                        s.contains("结婚") -> put("want", "想找对象", 0.8f, s)
                    s.contains("无聊") || s.contains("寂寞") || s.contains("找人聊") ||
                        s.contains("陪聊") -> put("want", "想找人聊天", 0.7f, s)
                }
            }

            if (!out.containsKey("avoid")) {
                AVOID.find(s)?.let { m -> put("avoid", "不喜欢「${m.groupValues[2]}」", 0.8f, s) }
            }
        }

        // 命中人设的「城市/职业」降成低置信：多半是把我方说的话当成了对方说的。
        //
        // 为什么是"降级"而不是"丢弃"：同城交友平台上，对方真的和我同城
        // （都淄博）一点也不稀奇 —— 直接丢会误杀真事实。
        // 降到 0.5 的效果是：**不进提示词**，但会留在「会话记忆」页并标「不确定」，
        // 用户看一眼来源就知道该确认还是该删。
        val tainted = { v: String ->
            val core = v.removePrefix("做").trim()
            core.isNotEmpty() && mine.contains(core)
        }
        return out.values.map { f ->
            if ((f.key == "city" || f.key == "job") && tainted(f.value)) {
                f.copy(confidence = 0.5f)
            } else {
                f
            }
        }
    }

    /** 事实的固定顺序：稳定的顺序能让上游的前缀缓存更愿意命中。 */
    val ORDER = listOf("age", "city", "job", "marriage", "kid", "want", "avoid")

    /** 键 → 中文名。和 [ORDER] 一起维护，加一个键记得两边都加。 */
    fun labelOf(key: String): String = when (key) {
        "age" -> "年龄"
        "city" -> "城市"
        "job" -> "职业"
        "marriage" -> "婚育"
        "kid" -> "孩子"
        "want" -> "诉求"
        "avoid" -> "雷区"
        else -> key
    }
}
