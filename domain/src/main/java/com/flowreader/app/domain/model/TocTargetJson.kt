package com.flowreader.app.domain.model

/**
 * `toc_nodes.targetJson` 的解析（主计划 4.3）。
 *
 * 写入侧（`PublicationImportCoordinator.flattenToc`）存的形状是：
 * ```json
 * { "documentKey": "OEBPS/Text/ch1.xhtml", "fragment": "a1", "sectionId": "..." }
 * ```
 * 无链接分组的整体 target 为 SQL NULL，字段本身也可能为 JSON `null`。
 *
 * 为什么解析放在 domain：
 * - data/app 层都需要它，但 **domain 的 [MiniJson] 是 internal**，
 *   在 app 层直接用不了；把它放这里既保持封装，又能被 JVM 单测覆盖；
 * - 解析失败**绝不抛异常**：目录里一条坏记录不该让整本书打不开目录。
 */
object TocTargetJson {

    /**
     * 生成 `targetJson`。
     *
     * 与 [sectionIdOf] 对称，且**不使用 `org.json`**：它在 JVM 单测里是 Android SDK 的
     * 空壳桩（调用抛 "not mocked"），会让「目录目标确实写进去了」这类断言根本无法在桌面运行
     * —— 而那正是 S8b 真机上暴露缺陷时最需要的一条测试。
     *
     * 缺值写成显式 `null`，与历史数据形状一致。
     */
    fun encode(documentKey: String?, fragment: String?, sectionId: String?): String {
        fun value(raw: String?): Any? = raw?.takeIf { it.isNotEmpty() }
        return MiniJson.stringify(
            linkedMapOf(
                "documentKey" to value(documentKey),
                "fragment" to value(fragment),
                "sectionId" to value(sectionId)
            )
        )
    }

    /** 取出目标 section；无链接分组或解析失败都返回 null。 */
    fun sectionIdOf(json: String?): String? = fieldOf(json, "sectionId")

    /** 取出目标文档 key（诊断与将来跳转用）。 */
    fun documentKeyOf(json: String?): String? = fieldOf(json, "documentKey")

    /** 取出 anchor fragment（诊断用）。 */
    fun fragmentOf(json: String?): String? = fieldOf(json, "fragment")

    private fun fieldOf(json: String?, key: String): String? {
        if (json.isNullOrBlank()) return null
        val root = MiniJson.asObject(MiniJson.parse(json)) ?: return null
        // org.json 写出的 JSONObject.NULL 落库后是字面量 null，MiniJson 会解析成 null，
        // 因此 asString 返回 null —— 正是我们要的「无值」语义。
        return MiniJson.asString(root[key])?.takeIf { it.isNotEmpty() }
    }
}
