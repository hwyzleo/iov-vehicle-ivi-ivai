package net.hwyz.iov.vehicle.ivi.ivai.retrieval.knowledge

import net.hwyz.iov.vehicle.ivi.ivai.retrieval.KnowledgeChunk

/**
 * Small built-in sample manual for first-phase Knowledge RAG validation
 * (CR-005): the real knowledge base ships as an index package built offline on
 * the desktop and deployed via OTA; these samples only prove the pipeline.
 *
 * Slicing preserves warning + steps together — never split a safety warning
 * from its corresponding operation steps.
 */
object SampleKnowledgeDocs {

    val chunks: List<KnowledgeChunk> = listOf(
        KnowledgeChunk(
            chunkId = "doc_tire_pressure.1",
            documentId = "doc_tire_pressure",
            title = "胎压报警说明",
            sectionPath = listOf("故障", "胎压报警"),
            content = "胎压报警是什么意思：当车辆检测到某个轮胎气压明显低于标准值时，仪表盘会点亮胎压报警灯。\n" +
                "【警告】报警灯亮起时请勿继续高速行驶，应立即安全靠边停车检查轮胎。\n" +
                "处理步骤：1. 安全停车后检查四轮外观是否有明显漏气或异物扎胎；2. 使用随车气泵补气至标准胎压（参见驾驶侧门框标签）；3. 若补气后报警灯仍不熄灭，请前往授权维修店检查。",
            vehicleModels = listOf("*"),
            softwareRange = ">=0.1.0",
            documentVersion = "1.0"
        ),
        KnowledgeChunk(
            chunkId = "doc_climate.1",
            documentId = "doc_climate",
            title = "空调开启与温度设置",
            sectionPath = listOf("空调", "使用"),
            content = "开启空调：按下中控台 AC 按键或通过语音说出“打开空调”。温度可在 16℃ 到 32℃ 之间调节，主驾和副驾可分别设置。\n" +
                "除霜除雾：雨天或冬季挡风玻璃起雾时，选择除霜模式并开启外循环，可快速清除雾气。",
            vehicleModels = listOf("*"),
            softwareRange = ">=0.1.0",
            documentVersion = "1.0"
        ),
        KnowledgeChunk(
            chunkId = "doc_seat_heating.1",
            documentId = "doc_seat_heating",
            title = "座椅加热使用",
            sectionPath = listOf("舒适", "座椅加热"),
            content = "座椅加热：通过中控屏或语音开启主驾/副驾座椅加热，共三档（低/中/高）。\n" +
                "【警告】请勿在座椅上放置毯子或厚坐垫长时间使用高档位加热，以免过热。",
            vehicleModels = listOf("*"),
            softwareRange = ">=0.1.0",
            documentVersion = "1.0"
        )
    )
}
