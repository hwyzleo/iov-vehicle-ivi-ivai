package net.hwyz.iov.vehicle.ivi.ivai.retrieval.eval

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType

/**
 * 首期离线评测集（CR-011）。L1 与 L2 分别建立、独立灰度启用；具体 Top-K 与
 * 阈值由评测集调整，不写死在业务逻辑中。
 *
 * 说明：本评测集在接入真实 Embedding 模型（HTTP_COMPATIBLE / LOCAL）后用于
 * 度量 Recall@K / MRR / Top-1/Top-3 / 无效候选率；哈希桩 Embedding 仅保证
 * 管线可运行，不代表真实召回质量。
 */
object SampleEvalSets {

    /** L1 Tool/Intent 评测集：期望命中 canonical Tool ID。 */
    val L1_TOOL: List<RetrievalEvalQuery> = listOf(
        RetrievalEvalQuery("打开空调", setOf("climate.power.set")),
        RetrievalEvalQuery("设置空调温度 26 度", setOf("climate.temperature.set")),
        RetrievalEvalQuery("空调温度调高一点", setOf("climate.temperature.adjust")),
        RetrievalEvalQuery("播放音乐", setOf("media.playback.play")),
        RetrievalEvalQuery("设置导航目的地", setOf("navigation.destination.set")),
        RetrievalEvalQuery("查询空调状态", setOf("climate.status.query")),
        RetrievalEvalQuery(
            "播放音乐", setOf("media.playback.play"),
            domainIds = listOf(BusinessDomainId.MEDIA_ENTERTAINMENT),
            operationTypes = listOf(OperationType.PLAYBACK)
        ),
        RetrievalEvalQuery(
            "设置空调温度 26 度", setOf("climate.temperature.set"),
            domainIds = listOf(BusinessDomainId.CABIN_COMFORT)
        ),
        RetrievalEvalQuery("量子物理讲座", setOf(), expectEmpty = false),
        RetrievalEvalQuery("宇宙起源", setOf(), expectEmpty = true),
        // ---- CR-017：位置表达专项（中左/中右/2排/3排 → climate.fan.speed.set） ----
        RetrievalEvalQuery("中左风量档位调到5档", setOf("climate.fan.speed.set")),
        RetrievalEvalQuery("中右设置风量档位5档", setOf("climate.fan.speed.set")),
        RetrievalEvalQuery("2排风量档位设为5档", setOf("climate.fan.speed.set")),
        RetrievalEvalQuery("3排风量档位调到5档", setOf("climate.fan.speed.set")),
        RetrievalEvalQuery("中排左风量调到5档", setOf("climate.fan.speed.set")),
        RetrievalEvalQuery("第二排右风量设为5档", setOf("climate.fan.speed.set")),
        // ---- CR-018：空调相似 Tool 边界专项（power/vent/fan/airflow/auto 五类） ----
        RetrievalEvalQuery("打开空调", setOf("climate.power.set")),
        RetrievalEvalQuery("启动空调系统", setOf("climate.power.set")),
        RetrievalEvalQuery("接通空调电源", setOf("climate.power.set")),
        RetrievalEvalQuery("打开通风口", setOf("climate.vent.set")),
        RetrievalEvalQuery("打开前排风口", setOf("climate.vent.set")),
        RetrievalEvalQuery("风量调到5档", setOf("climate.fan.speed.set")),
        RetrievalEvalQuery("风量调大一点", setOf("climate.fan.speed.adjust")),
        RetrievalEvalQuery("出风模式吹脸", setOf("climate.airflow.mode.set")),
        RetrievalEvalQuery("开启自动空调", setOf("climate.auto.set"))
    )

    /** L2 Knowledge 评测集：期望命中 sourceId。 */
    val L2_KNOWLEDGE: List<RetrievalEvalQuery> = listOf(
        RetrievalEvalQuery("胎压报警是什么意思", setOf("doc_tire_pressure")),
        RetrievalEvalQuery("空调怎么开启", setOf("doc_climate")),
        RetrievalEvalQuery("座椅加热怎么用", setOf("doc_seat_heating")),
        RetrievalEvalQuery("胎压报警怎么处理", setOf("doc_tire_pressure"), softwareVersion = "0.2.0"),
        RetrievalEvalQuery("胎压报警", setOf("doc_tire_pressure"), softwareVersion = "0.0.5"),
        RetrievalEvalQuery("量子物理与宇宙起源", setOf(), expectEmpty = true)
    )
}
