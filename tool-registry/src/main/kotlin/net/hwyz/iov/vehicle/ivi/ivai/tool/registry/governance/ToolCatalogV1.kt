package net.hwyz.iov.vehicle.ivi.ivai.tool.registry.governance

import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.BusinessDomainId
import net.hwyz.iov.vehicle.ivi.ivai.tool.registry.domain.OperationType

/**
 * IVAI Tool Catalog v1（IVI-IVAI-DSN-CR-009 规范性附录）。
 *
 * 固化 160 个具体 Tool。每个 Tool 已明确：稳定 Tool ID 和中文名称、Domain、
 * Capability Pack 与 OperationType、参数 Schema 和基本参数边界、Policy/确认、
 * Binding 契约和 Alias 规则、P0～P3 实施优先级与治理状态（默认 DRAFT）。
 *
 * 开发 AI 必须按该目录生成 ToolDefinition、JSON Schema、Registry 项、
 * Executor/Adapter 接口和契约测试，**不得从数量配额推测新的 Tool**。
 */
object ToolCatalogV1 {

    private val BD01 = BusinessDomainId.CABIN_COMFORT
    private val BD02 = BusinessDomainId.BODY_CONTROL
    private val BD03 = BusinessDomainId.VEHICLE_DRIVING_CONFIG
    private val BD04 = BusinessDomainId.ENERGY
    private val BD05 = BusinessDomainId.IMAGING_RECORDING
    private val BD06 = BusinessDomainId.NAVIGATION_TRAVEL
    private val BD07 = BusinessDomainId.COMMUNICATION
    private val BD08 = BusinessDomainId.MEDIA_ENTERTAINMENT
    private val BD09 = BusinessDomainId.APP_SYSTEM
    private val BD10 = BusinessDomainId.INFORMATION_SERVICE
    private val CONTROL = OperationType.CONTROL
    private val CONFIGURE = OperationType.CONFIGURE
    private val QUERY = OperationType.QUERY
    private val NAVIGATE_UI = OperationType.NAVIGATE_UI
    private val SEARCH = OperationType.SEARCH
    private val PLAYBACK = OperationType.PLAYBACK
    private val P0 = ImplementationPriority.P0
    private val P1 = ImplementationPriority.P1
    private val P2 = ImplementationPriority.P2
    private val P3 = ImplementationPriority.P3

    val ALL: List<ToolGovernanceSpec> = listOf(
        // ============ BD01 座舱舒适 ============
        t("climate.power.set", "设置空调电源", BD01, "cabin.climate", CONTROL, "{enabled:boolean, zone?:enum}", "LOW；关闭前检查除霜依赖", P0),
        t("climate.temperature.set", "设置目标温度", BD01, "cabin.climate", CONTROL, "{zone:enum, temperature:number[16..30], unit:C}", "LOW；越界拒绝", P0),
        t("climate.temperature.adjust", "调节温度", BD01, "cabin.climate", CONTROL, "{zone:enum, direction:increase|decrease, step:number[0.5..5]}", "LOW；按当前值归一化", P0),
        t("climate.fan.speed.set", "设置风量档位", BD01, "cabin.climate", CONTROL, "{zone?:enum, level:int[0..10]}", "LOW；默认免确认", P0),
        t("climate.fan.speed.adjust", "调节风量", BD01, "cabin.climate", CONTROL, "{direction:increase|decrease, step:int[1..3]}", "LOW；默认免确认", P0),
        t("climate.airflow.mode.set", "设置出风模式", BD01, "cabin.climate", CONTROL, "{zone?:enum, mode:face|feet|defrost|mixed}", "LOW；默认免确认", P0),
        t("climate.auto.set", "设置自动空调", BD01, "cabin.climate", CONTROL, "{enabled:boolean, zone?:enum}", "LOW；默认免确认", P0),
        t("climate.circulation.set", "设置内外循环", BD01, "cabin.climate", CONTROL, "{mode:auto|internal|external}", "LOW；默认免确认", P0),
        t("climate.defrost.set", "设置除霜除雾", BD01, "cabin.climate", CONTROL, "{target:front|rear|all, enabled:boolean}", "LOW；默认免确认", P0),
        t("climate.vent.set", "设置通风口", BD01, "cabin.climate", CONTROL, "{zone:enum, enabled:boolean}", "LOW；默认免确认", P0),
        t("climate.status.query", "查询空调状态", BD01, "cabin.climate", QUERY, "{items?:array, zone?:enum}", "LOW；默认免确认", P0),
        t("climate.air_quality.query", "查询空气质量", BD01, "cabin.climate", QUERY, "{item?:pm25|co2|aqi|filter_life}", "LOW；默认免确认", P1),
        t("climate.fragrance.set", "设置香氛", BD01, "cabin.climate", CONTROL, "{enabled:boolean, fragrance?:enum, level?:int}", "LOW；默认免确认", P1),
        t("seat.heating.set", "设置座椅加热", BD01, "cabin.seat_comfort", CONTROL, "{position:enum, enabled?:boolean, level?:int[0..3]}", "LOW；默认免确认", P0),
        t("seat.ventilation.set", "设置座椅通风", BD01, "cabin.seat_comfort", CONTROL, "{position:enum, enabled?:boolean, level?:int[0..3]}", "LOW；默认免确认", P0),
        t("seat.massage.set", "设置座椅按摩", BD01, "cabin.seat_comfort", CONTROL, "{position:enum, enabled:boolean, level?:int}", "LOW；默认免确认", P0),
        t("seat.massage.mode.set", "设置按摩模式", BD01, "cabin.seat_comfort", CONTROL, "{position:enum, mode:enum}", "LOW；默认免确认", P0),
        t("seat.comfort.status.query", "查询座椅舒适状态", BD01, "cabin.seat_comfort", QUERY, "{position?:enum, items?:array}", "LOW；默认免确认", P1),
        t("steering.heating.set", "设置方向盘加热", BD01, "cabin.seat_comfort", CONTROL, "{enabled:boolean, level?:int}", "LOW；默认免确认", P1),
        t("refrigerator.temperature.set", "设置冰箱温度", BD01, "cabin.refrigerator", CONTROL, "{temperature:number, unit:C}", "LOW；默认免确认", P1),
        t("refrigerator.mode.set", "设置冰箱模式", BD01, "cabin.refrigerator", CONTROL, "{mode:cool|freeze|warm_milk|fresh}", "LOW；默认免确认", P1),
        t("refrigerator.status.query", "查询冰箱状态", BD01, "cabin.refrigerator", QUERY, "{items?:array}", "LOW；默认免确认", P1),

        // ============ BD02 车身控制 ============
        t("body.window.set", "设置车窗", BD02, "body.window_roof", CONTROL, "{positions:array,target:open|close|vent,percentage?:0..100}", "LOW；默认免确认", P0),
        t("body.window.adjust", "调节车窗", BD02, "body.window_roof", CONTROL, "{positions:array,direction,stepPercent}", "LOW；默认免确认", P0),
        t("body.window.status.query", "查询车窗状态", BD02, "body.window_roof", QUERY, "{positions?:array}", "LOW；默认免确认", P0),
        t("body.sunroof.set", "设置天窗", BD02, "body.window_roof", CONTROL, "{target:open|close|vent,percentage?:0..100}", "LOW；默认免确认", P0),
        t("body.sunroof.adjust", "调节天窗", BD02, "body.window_roof", CONTROL, "{direction,stepPercent}", "LOW；默认免确认", P0),
        t("body.sunshade.set", "设置遮阳帘", BD02, "body.window_roof", CONTROL, "{target,percentage?:0..100}", "LOW；默认免确认", P0),
        t("body.roof.status.query", "查询天窗遮阳帘状态", BD02, "body.window_roof", QUERY, "{items?:array}", "LOW；默认免确认", P0),
        t("body.door.open", "打开车门", BD02, "body.door_lock", CONTROL, "{positions:array}", "MEDIUM；执行前确认", P0),
        t("body.door.close", "关闭车门", BD02, "body.door_lock", CONTROL, "{positions:array}", "MEDIUM；防夹检查", P0),
        t("body.door.status.query", "查询车门状态", BD02, "body.door_lock", QUERY, "{positions?:array}", "LOW；默认免确认", P0),
        t("body.lock.set", "设置门锁", BD02, "body.door_lock", CONTROL, "{scope:all|position,locked:boolean}", "MEDIUM；解锁需确认", P0),
        t("body.tailgate.set", "设置尾门", BD02, "body.door_lock", CONTROL, "{target:open|close|stop,height?:number}", "MEDIUM；开启/关闭需防夹", P0),
        t("body.tailgate.status.query", "查询尾门状态", BD02, "body.door_lock", QUERY, "{}", "LOW；默认免确认", P0),
        t("body.child_lock.set", "设置儿童锁", BD02, "body.door_lock", CONTROL, "{position:left|right|all,enabled:boolean}", "MEDIUM；关闭需确认", P0),
        t("body.exterior_light.set", "设置外部灯光", BD02, "body.lighting_wiper", CONTROL, "{light:enum,enabled:boolean}", "LOW；默认免确认", P0),
        t("body.exterior_light.mode.set", "设置外灯模式", BD02, "body.lighting_wiper", CONFIGURE, "{light:enum,mode:enum}", "LOW；默认免确认", P0),
        t("body.ambient_light.set", "设置氛围灯", BD02, "body.lighting_wiper", CONTROL, "{zone?:enum,enabled?:boolean,color?:string,brightness?:int,mode?:enum}", "LOW；默认免确认", P0),
        t("body.reading_light.set", "设置阅读灯", BD02, "body.lighting_wiper", CONTROL, "{position:enum,enabled:boolean,brightness?:int}", "LOW；默认免确认", P0),
        t("body.light.status.query", "查询灯光状态", BD02, "body.lighting_wiper", QUERY, "{lights?:array}", "LOW；默认免确认", P0),
        t("body.wiper.set", "设置雨刮", BD02, "body.lighting_wiper", CONTROL, "{target:front|rear,mode:off|auto|intermittent|continuous,level?:int}", "LOW；默认免确认", P0),
        t("body.wiper.single_sweep", "雨刮单次刮刷", BD02, "body.lighting_wiper", CONTROL, "{target:front|rear}", "LOW；默认免确认", P0),
        t("body.washer.activate", "启动洗涤", BD02, "body.lighting_wiper", CONTROL, "{target:front|rear,durationMs?:int}", "LOW；默认免确认", P0),
        t("body.mirror.fold.set", "设置后视镜折叠", BD02, "body.lighting_wiper", CONTROL, "{target:left|right|all,folded:boolean}", "LOW；默认免确认", P0),
        t("body.mirror.adjust", "调节后视镜", BD02, "body.lighting_wiper", CONTROL, "{target:left|right,direction,step}", "LOW；默认免确认", P0),

        // ============ BD03 车辆设置与驾驶 ============
        t("vehicle.drive_mode.set", "设置驾驶模式", BD03, "vehicle.driving_config", CONFIGURE, "{mode:enum}", "LOW；默认免确认", P1),
        t("vehicle.mode.query", "查询车辆模式", BD03, "vehicle.driving_config", QUERY, "{modeType:enum}", "LOW；默认免确认", P1),
        t("vehicle.energy_mode.set", "设置能源模式", BD03, "vehicle.driving_config", CONFIGURE, "{mode:enum}", "LOW；默认免确认", P1),
        t("vehicle.one_pedal.set", "设置单踏板模式", BD03, "vehicle.driving_config", CONFIGURE, "{enabled:boolean}", "LOW；默认免确认", P1),
        t("vehicle.brake_regen.set", "设置能量回收", BD03, "vehicle.driving_config", CONFIGURE, "{level:enum}", "LOW；默认免确认", P1),
        t("vehicle.steering_mode.set", "设置转向模式", BD03, "vehicle.driving_config", CONFIGURE, "{mode:enum}", "LOW；默认免确认", P1),
        t("vehicle.suspension.mode.set", "设置悬架模式", BD03, "vehicle.driving_config", CONFIGURE, "{mode:enum}", "LOW；默认免确认", P1),
        t("vehicle.suspension.height.set", "设置悬架高度", BD03, "vehicle.driving_config", CONTROL, "{level:enum}", "LOW；默认免确认", P1),
        t("vehicle.acceleration.mode.set", "设置加速响应", BD03, "vehicle.driving_config", CONFIGURE, "{mode:enum}", "LOW；默认免确认", P1),
        t("vehicle.hill_descent.set", "设置陡坡缓降", BD03, "vehicle.driving_config", CONTROL, "{enabled:boolean}", "HIGH；检查车速", P1),
        t("vehicle.offroad.mode.set", "设置越野模式", BD03, "vehicle.driving_config", CONFIGURE, "{mode:enum,enabled?:boolean}", "HIGH；确认并检查车速", P1),
        t("vehicle.offroad_cruise.set", "设置越野巡航", BD03, "vehicle.driving_config", CONTROL, "{enabled:boolean,speed?:number}", "HIGH；确认", P1),
        t("vehicle.trailer_mode.set", "设置牵引模式", BD03, "vehicle.driving_config", CONFIGURE, "{enabled:boolean}", "HIGH；确认", P1),
        t("vehicle.welcome_mode.set", "设置迎宾模式", BD03, "vehicle.driving_config", CONFIGURE, "{enabled:boolean}", "LOW；默认免确认", P1),
        t("vehicle.personalization.profile.set", "切换个性化配置", BD03, "vehicle.driving_config", CONFIGURE, "{profileId:string}", "LOW；默认免确认", P1),
        t("vehicle.speed_limit_alert.set", "设置限速提醒", BD03, "vehicle.driving_config", CONFIGURE, "{enabled:boolean,threshold?:number}", "LOW；默认免确认", P1),
        t("vehicle.maintenance_mode.set", "设置维修模式", BD03, "vehicle.driving_config", CONFIGURE, "{system:enum,enabled:boolean}", "HIGH；强确认", P1),
        t("vehicle.setting.query", "查询车辆设置", BD03, "vehicle.driving_config", QUERY, "{items:array}", "LOW；默认免确认", P1),
        t("adas.feature.set", "设置 ADAS 功能", BD03, "vehicle.adas", CONFIGURE, "{feature:enum,enabled:boolean}", "HIGH；关闭安全能力需确认", P1),
        t("adas.mode.set", "设置 ADAS 模式", BD03, "vehicle.adas", CONFIGURE, "{feature:enum,mode:enum}", "HIGH；确认", P1),
        t("adas.sensitivity.set", "设置 ADAS 灵敏度", BD03, "vehicle.adas", CONFIGURE, "{feature:enum,level:enum}", "MEDIUM", P1),
        t("adas.speed.set", "设置辅助驾驶速度", BD03, "vehicle.adas", CONTROL, "{speed:number,unit:kmh}", "HIGH；边界校验", P1),
        t("adas.distance.set", "设置跟车距离", BD03, "vehicle.adas", CONFIGURE, "{level:int}", "MEDIUM", P1),
        t("adas.lane_centering.set", "设置车道居中", BD03, "vehicle.adas", CONFIGURE, "{enabled:boolean}", "HIGH；确认", P1),
        t("adas.lane_change.set", "设置自动变道", BD03, "vehicle.adas", CONFIGURE, "{enabled:boolean,style?:enum}", "HIGH；确认", P1),
        t("adas.parking_assist.set", "设置泊车辅助", BD03, "vehicle.adas", CONTROL, "{enabled:boolean,mode?:enum}", "HIGH；确认", P1),
        t("adas.collision_warning.set", "设置碰撞预警", BD03, "vehicle.adas", CONFIGURE, "{direction:front|rear,mode:enum}", "HIGH；确认", P1),
        t("adas.status.query", "查询 ADAS 状态", BD03, "vehicle.adas", QUERY, "{features?:array}", "LOW；默认免确认", P1),

        // ============ BD04 能源与补能 ============
        t("energy.charge.start", "开始充电", BD04, "energy.management", CONTROL, "{port?:enum}", "HIGH；检查插枪与状态", P3),
        t("energy.charge.stop", "停止充电", BD04, "energy.management", CONTROL, "{}", "MEDIUM；确认", P3),
        t("energy.charge.status.query", "查询充电状态", BD04, "energy.management", QUERY, "{items?:array}", "LOW；默认免确认", P3),
        t("energy.charge.limit.set", "设置充电上限", BD04, "energy.management", CONFIGURE, "{percent:int[50..100]}", "LOW；默认免确认", P3),
        t("energy.charge.schedule.set", "设置预约充电", BD04, "energy.management", CONFIGURE, "{startTime,endTime?,repeat?}", "LOW；默认免确认", P3),
        t("energy.discharge.start", "开始对外放电", BD04, "energy.management", CONTROL, "{type:v2l|v2v,powerLimit?:number}", "HIGH；强确认", P3),
        t("energy.discharge.stop", "停止对外放电", BD04, "energy.management", CONTROL, "{}", "MEDIUM", P3),
        t("energy.battery.status.query", "查询电池状态", BD04, "energy.management", QUERY, "{items?:soc|range|health|temperature}", "LOW；默认免确认", P3),
        t("energy.consumption.query", "查询能耗", BD04, "energy.management", QUERY, "{period?:enum}", "LOW；默认免确认", P3),
        t("energy.regeneration.set", "设置发电/增程策略", BD04, "energy.management", CONFIGURE, "{mode:enum,targetSoc?:int}", "MEDIUM", P3),

        // ============ BD05 影像与记录 ============
        t("imaging.avm.open", "打开环视影像", BD05, "imaging.recording", CONTROL, "{view?:enum}", "LOW；默认免确认", P3),
        t("imaging.avm.close", "关闭环视影像", BD05, "imaging.recording", CONTROL, "{}", "LOW；默认免确认", P3),
        t("imaging.camera.view.set", "切换摄像头视角", BD05, "imaging.recording", CONTROL, "{view:front|rear|left|right|top}", "LOW；默认免确认", P3),
        t("imaging.transparent_chassis.set", "设置透明底盘", BD05, "imaging.recording", CONTROL, "{enabled:boolean}", "LOW；默认免确认", P3),
        t("imaging.dvr.record.set", "设置行车记录", BD05, "imaging.recording", CONTROL, "{recording:boolean}", "LOW；默认免确认", P3),
        t("imaging.dvr.capture", "行车记录抓拍", BD05, "imaging.recording", CONTROL, "{camera?:enum}", "LOW；默认免确认", P3),
        t("imaging.dvr.playback.open", "打开记录回放", BD05, "imaging.recording", NAVIGATE_UI, "{timeRange?:string,eventType?:enum}", "LOW；默认免确认", P3),
        t("imaging.status.query", "查询影像记录状态", BD05, "imaging.recording", QUERY, "{items?:array}", "LOW；默认免确认", P3),

        // ============ BD06 导航与出行 ============
        t("navigation.destination.set", "设置导航目的地", BD06, "navigation.route", CONTROL, "{poiId?:string,address?:string,coordinate?:object}", "LOW；默认免确认", P2),
        t("navigation.route.start", "开始导航", BD06, "navigation.route", CONTROL, "{routeId?:string}", "LOW；默认免确认", P2),
        t("navigation.route.stop", "结束导航", BD06, "navigation.route", CONTROL, "{}", "LOW；默认免确认", P2),
        t("navigation.route.pause", "暂停导航", BD06, "navigation.route", CONTROL, "{}", "LOW；默认免确认", P2),
        t("navigation.route.resume", "继续导航", BD06, "navigation.route", CONTROL, "{}", "LOW；默认免确认", P2),
        t("navigation.route.select", "选择备选路线", BD06, "navigation.route", CONTROL, "{routeId:string}", "LOW；默认免确认", P2),
        t("navigation.route.option.set", "设置路线偏好", BD06, "navigation.route", CONFIGURE, "{avoidTolls?,avoidHighway?,preferFastest?}", "LOW；默认免确认", P2),
        t("navigation.route.query", "查询路线信息", BD06, "navigation.route", QUERY, "{item:eta|distance|traffic|next_turn}", "LOW；默认免确认", P2),
        t("navigation.waypoint.add", "添加途经点", BD06, "navigation.route", CONTROL, "{poiId:string,index?:int}", "LOW；默认免确认", P2),
        t("navigation.waypoint.remove", "删除途经点", BD06, "navigation.route", CONTROL, "{waypointId:string}", "LOW；默认免确认", P2),
        t("navigation.waypoint.reorder", "调整途经点顺序", BD06, "navigation.route", CONTROL, "{orderedIds:array}", "LOW；默认免确认", P2),
        t("navigation.home.start", "导航回家", BD06, "navigation.route", CONTROL, "{}", "LOW；默认免确认", P2),
        t("navigation.work.start", "导航去公司", BD06, "navigation.route", CONTROL, "{}", "LOW；默认免确认", P2),
        t("navigation.poi.search", "搜索 POI", BD06, "navigation.poi_map", SEARCH, "{query:string,nearby?:boolean,category?:enum}", "LOW；默认免确认", P2),
        t("navigation.poi.detail.query", "查询 POI 详情", BD06, "navigation.poi_map", QUERY, "{poiId:string}", "LOW；默认免确认", P2),
        t("navigation.nearby.search", "搜索附近地点", BD06, "navigation.poi_map", SEARCH, "{category:enum,radius?:number}", "LOW；默认免确认", P2),
        t("navigation.favorite.set", "管理地点收藏", BD06, "navigation.poi_map", CONFIGURE, "{action:add|remove,poiId:string,label?:string}", "LOW；默认免确认", P2),
        t("navigation.map.view.set", "设置地图视图", BD06, "navigation.poi_map", CONTROL, "{mode:2d|3d,northUp?:boolean,zoom?:number}", "LOW；默认免确认", P2),
        t("navigation.traffic.set", "设置路况显示", BD06, "navigation.poi_map", CONTROL, "{enabled:boolean}", "LOW；默认免确认", P2),
        t("navigation.location.query", "查询当前位置", BD06, "navigation.poi_map", QUERY, "{}", "LOW；默认免确认", P2),

        // ============ BD07 通讯 ============
        t("communication.contact.search", "搜索联系人", BD07, "communication.call_message", SEARCH, "{query:string}", "LOW；默认免确认", P2),
        t("communication.call.start", "发起通话", BD07, "communication.call_message", CONTROL, "{contactId?:string,phoneNumber?:string}", "MEDIUM；歧义时确认", P2),
        t("communication.call.end", "结束通话", BD07, "communication.call_message", CONTROL, "{}", "LOW；默认免确认", P2),
        t("communication.call.answer", "接听来电", BD07, "communication.call_message", CONTROL, "{}", "LOW；默认免确认", P2),
        t("communication.call.reject", "拒接来电", BD07, "communication.call_message", CONTROL, "{}", "LOW；默认免确认", P2),
        t("communication.call.control", "控制通话", BD07, "communication.call_message", CONTROL, "{action:mute|unmute|speaker_on|speaker_off|hold|resume}", "LOW；默认免确认", P2),
        t("communication.message.send", "发送消息", BD07, "communication.call_message", CONTROL, "{contactId:string,content:string}", "HIGH；发送前确认", P2),
        t("communication.message.read", "读取消息", BD07, "communication.call_message", QUERY, "{conversationId?:string,unreadOnly?:boolean}", "MEDIUM；隐私检查", P2),

        // ============ BD08 媒体娱乐 ============
        t("media.playback.play", "播放媒体", BD08, "media.audio", PLAYBACK, "{mediaType:audio|video,contentId?:string,query?:string,source?:enum}", "LOW；默认免确认", P2),
        t("media.playback.pause", "暂停播放", BD08, "media.audio", PLAYBACK, "{}", "LOW；默认免确认", P2),
        t("media.playback.resume", "继续播放", BD08, "media.audio", PLAYBACK, "{}", "LOW；默认免确认", P2),
        t("media.playback.stop", "停止播放", BD08, "media.audio", PLAYBACK, "{}", "LOW；默认免确认", P2),
        t("media.playback.next", "播放下一个", BD08, "media.audio", PLAYBACK, "{mediaType?:enum}", "LOW；默认免确认", P2),
        t("media.playback.previous", "播放上一个", BD08, "media.audio", PLAYBACK, "{mediaType?:enum}", "LOW；默认免确认", P2),
        t("media.playback.seek", "跳转播放位置", BD08, "media.audio", PLAYBACK, "{positionSec?:int,offsetSec?:int}", "LOW；默认免确认", P2),
        t("media.playback.mode.set", "设置播放模式", BD08, "media.audio", CONFIGURE, "{mode:sequence|repeat_one|repeat_all|shuffle}", "LOW；默认免确认", P2),
        t("media.volume.set", "设置媒体音量", BD08, "media.audio", CONTROL, "{zone?:enum,value:int[0..100]}", "LOW；默认免确认", P2),
        t("media.volume.adjust", "调节媒体音量", BD08, "media.audio", CONTROL, "{zone?:enum,direction,step:int}", "LOW；默认免确认", P2),
        t("media.audio.search", "搜索音乐音频", BD08, "media.audio", SEARCH, "{query:string,artist?:string,album?:string,genre?:string}", "LOW；默认免确认", P2),
        t("media.audio.play_by_filter", "按条件播放音频", BD08, "media.audio", PLAYBACK, "{artist?,album?,genre?,scene?,audience?}", "LOW；默认免确认", P2),
        t("media.audio.status.query", "查询音频播放状态", BD08, "media.audio", QUERY, "{items?:array}", "LOW；默认免确认", P2),
        t("media.radio.tune", "播放电台", BD08, "media.audio", PLAYBACK, "{stationId?:string,frequency?:number}", "LOW；默认免确认", P2),
        t("media.radio.search", "搜索电台", BD08, "media.audio", SEARCH, "{query:string,band?:enum}", "LOW；默认免确认", P2),
        t("media.video.play", "播放视频", BD08, "media.video", PLAYBACK, "{contentId:string,episode?:int}", "LOW；默认免确认", P2),
        t("media.video.search", "搜索视频", BD08, "media.video", SEARCH, "{query:string,actor?:string,year?:int,genre?:string}", "LOW；默认免确认", P2),
        t("media.video.episode.set", "选择视频集数", BD08, "media.video", PLAYBACK, "{episode:int}", "LOW；默认免确认", P2),
        t("media.video.cast.set", "设置视频投屏", BD08, "media.video", CONTROL, "{target:front|rear|all,enabled:boolean}", "MEDIUM；驾驶限制", P2),
        t("media.video.fullscreen.set", "设置视频全屏", BD08, "media.video", CONTROL, "{enabled:boolean}", "LOW；默认免确认", P2),
        t("media.video.quality.set", "设置视频清晰度", BD08, "media.video", CONFIGURE, "{quality:enum}", "LOW；默认免确认", P2),
        t("media.subtitle.set", "设置字幕", BD08, "media.video", CONFIGURE, "{enabled:boolean,language?:string}", "LOW；默认免确认", P2),
        t("media.audio_track.set", "设置音轨", BD08, "media.video", CONFIGURE, "{language?:string,trackId?:string}", "LOW；默认免确认", P2),
        t("media.video.status.query", "查询视频状态", BD08, "media.video", QUERY, "{items?:array}", "LOW；默认免确认", P2),

        // ============ BD09 应用与系统 ============
        t("system.app.open", "打开应用", BD09, "system.app_desktop", NAVIGATE_UI, "{appId:string}", "LOW；默认免确认", P1),
        t("system.app.close", "关闭应用", BD09, "system.app_desktop", CONTROL, "{appId:string}", "LOW；默认免确认", P1),
        t("system.app.search", "搜索应用", BD09, "system.app_desktop", SEARCH, "{query:string}", "LOW；默认免确认", P1),
        t("system.ui.navigate", "页面定位", BD09, "system.app_desktop", NAVIGATE_UI, "{domainId:string,pageTarget:string,subPageTarget?:string}", "LOW；默认免确认", P1),
        t("system.ui.back", "页面返回", BD09, "system.app_desktop", NAVIGATE_UI, "{}", "LOW；默认免确认", P1),
        t("system.display.set", "设置屏幕", BD09, "system.app_desktop", CONFIGURE, "{target?:enum,brightness?:int,theme?:enum,enabled?:boolean}", "LOW；默认免确认", P1),
        t("system.network.set", "设置网络", BD09, "system.app_desktop", CONFIGURE, "{type:wifi|bluetooth|hotspot,enabled:boolean}", "LOW；默认免确认", P1),
        t("system.bluetooth.device.connect", "连接蓝牙设备", BD09, "system.app_desktop", CONTROL, "{deviceId:string}", "MEDIUM；确认", P1),
        t("system.voice.set", "设置语音系统", BD09, "system.app_desktop", CONFIGURE, "{item:enum,value:any}", "LOW；默认免确认", P1),
        t("system.status.query", "查询系统状态", BD09, "system.app_desktop", QUERY, "{items?:array}", "LOW；默认免确认", P1),

        // ============ BD10 信息服务 ============
        t("information.weather.query", "查询天气", BD10, "information.weather_calendar", QUERY, "{location?:string,date?:string,items?:array}", "LOW；默认免确认", P3),
        t("information.calendar.query", "查询日历", BD10, "information.weather_calendar", QUERY, "{dateRange?:object,keyword?:string}", "LOW；默认免确认", P3),
        t("information.calendar.event.query", "查询日程详情", BD10, "information.weather_calendar", QUERY, "{eventId:string}", "LOW；默认免确认", P3),
        t("information.reminder.create", "创建提醒", BD10, "information.reminder", CONFIGURE, "{title:string,time:string,repeat?:string}", "MEDIUM；确认时间", P3),
        t("information.reminder.query", "查询提醒", BD10, "information.reminder", QUERY, "{dateRange?:object,status?:enum}", "LOW；默认免确认", P3),
        t("information.reminder.manage", "管理提醒", BD10, "information.reminder", CONFIGURE, "{action:update|delete|complete,reminderId:string}", "MEDIUM；删除需确认", P3)
    )

    private fun t(
        toolId: String,
        name: String,
        domainId: BusinessDomainId,
        capabilityPackId: String,
        operationType: OperationType,
        parameterSchema: String,
        policySummary: String,
        priority: ImplementationPriority
    ) = ToolGovernanceSpec(
        toolId = toolId,
        name = name,
        domainId = domainId,
        capabilityPackId = capabilityPackId,
        operationType = operationType,
        parameterSchema = parameterSchema,
        policySummary = policySummary,
        priority = priority
    )

    fun get(toolId: String): ToolGovernanceSpec? =
        ALL.firstOrNull { it.toolId == toolId }

    /** Tool ID 集合（用于 Manifest / 校验）。 */
    val toolIds: Set<String> = ALL.map { it.toolId }.toSet()

    /** 按 Domain 分组计数。 */
    fun countByDomain(): Map<BusinessDomainId, Int> =
        ALL.groupingBy { it.domainId }.eachCount()

    /** 按 Capability Pack 分组计数。 */
    fun countByPack(): Map<String, Int> =
        ALL.groupingBy { it.capabilityPackId }.eachCount()
}
