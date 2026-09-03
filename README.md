# iov-vehicle-ivi-ivai

车机车载智能 AI 平台（IVI-IVAI）v0.1 初版技术骨架。对应设计变更 **IVI-IVAI-DSN-CR-001** / 需求 **IVI-IVAI-REQ-CR-001**。

拓扑：**Android/IVI → agent-core → Mac Ollama(qwen3.5:4b) → Mock 车辆工具**。

## 环境要求

| 项 | 版本 |
|---|---|
| Gradle Wrapper | 8.9 |
| Kotlin | 2.0.21 |
| AGP | 8.5.2 |
| JDK | 17（编译 toolchain）/ 21（Gradle daemon 运行，规避 JDK 17.0.1 类加载器问题） |
| Android SDK | compileSdk 34 / minSdk 26 / targetSdk 34 |
| Ollama | `qwen3.5:4b`（Mac 侧） |

> Gradle daemon 需用 JDK 21 运行：`export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.0.8.jdk/Contents/Home`（JDK 17.0.1 运行 Gradle 8.9 会报 `PlatformClassLoader cannot be cast to URLClassLoader`）。

## 工程结构

```
iov-vehicle-ivi-ivai
├── app-demo                Android app：输入/确认/展示原始响应、结构化结果、校验、执行结果
├── service-ai-agent        Android library：生命周期、协程作用域、网络状态、IPC（AgentService）
├── agent-core              纯 Kotlin/JVM：Session｜Workflow｜Router｜Prompt｜Policy｜状态机
├── model-client            纯 JVM：ModelProvider + OllamaModelProvider + Cloud/AndroidLocal 预留
├── tool-registry           纯 JVM：ToolDefinition、JSON Schema、正反例、执行映射（6 空调工具）
├── tool-runtime            纯 JVM：ToolValidator、ToolPolicyEngine、ToolExecutor、幂等、生命周期
├── adapter-vehicle-mock    纯 JVM：MockClimateToolAdapter（内存车辆状态）
├── retrieval               预留（Tool/Intent RAG、Knowledge RAG 空模块）
└── observability           纯 JVM：requestId、耗时、路由、工具状态、错误记录
```

根包：`net.hwyz.iov.vehicle.ivi.ivai`。`agent-core` 不依赖 Android API，可在 JVM 独立测试。

## 运行与测试

### 1. JVM 单元 / 契约测试（无需 Ollama）

```bash
export JAVA_HOME=/Library/Java/JavaVirtualMachines/jdk-21.0.8.jdk/Contents/Home
./gradlew test
```

### 2. JVM 集成测试（需要 Mac Ollama 运行）

```bash
./gradlew :agent-core:test --tests "*AgentOllamaIntegrationTest*"
```

验证 agent-core → OllamaModelProvider → `http://localhost:11434` → Mock adapter 全链路。
Ollama 不可达时自动跳过（`assumeTrue`）。

### 3. Android 模拟器联调

```bash
# 启动模拟器并安装
emulator -avd Pixel_3a_API_33_arm64-v8a -no-window -no-audio &
adb install -r app-demo/build/outputs/apk/debug/app-demo-debug.apk
adb shell am start -n net.hwyz.iov.vehicle.ivi.ivai.demo/.MainActivity
```

- 模拟器通过 `http://10.0.2.2:11434` 访问宿主 Mac Ollama（已在 network security config 放行明文）。
- 中文输入：安装并启用 ADBKeyboard，用 `am broadcast -a ADB_INPUT_TEXT --es msg '打开空调'` 注入。
- 完整调试台：输入测试语句，展示状态机、路由、原始模型输出、校验、执行结果与 Mock 车辆状态。

## 首批工具（6 个）

| Tool ID | Function-ID | 说明 |
|---|---|---|
| climate.power_on | AC_Control_1 | 打开空调 |
| climate.power_off | AC_Control_2 | 关闭空调 |
| climate.temperature_increase | AC_Temperature_2 | 升温（step 默认 1℃） |
| climate.temperature_decrease | AC_Temperature_3 | 降温（step 默认 1℃） |
| climate.temperature_set | AC_Temperature_1 | 设定温度（temperature 必填） |
| climate.status_query | 待映射 | 查询空调状态 |

## 错误码

| 码 | 含义 |
|---|---|
| IVAI-MODEL-001 | Ollama 不可达或超时 |
| IVAI-MODEL-002 | 模型响应无法解析 |
| IVAI-SCHEMA-001 | 顶层输出不符合 Schema |
| IVAI-TOOL-001 | 未知 Tool ID |
| IVAI-TOOL-002 | 工具参数缺失或越界 |
| IVAI-POLICY-001 | 工具未授权或前置条件不满足 |
| IVAI-EXEC-001 | Mock/车辆工具执行失败 |
| IVAI-ROUTE-001 | 无法确定安全路由 |

## 后续演进

1. 固定工具候选稳定后接入 Tool/Intent RAG。
2. 说明书就绪后接入 Knowledge RAG。
3. 接入 CloudModelProvider 处理复杂与开放域请求。
4. 目标硬件就绪后实现 AndroidLocalModelProvider。
5. 真实车辆服务就绪后新增 VehicleServiceAdapter，并保留端侧安全校验。
