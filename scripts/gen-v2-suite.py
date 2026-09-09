#!/usr/bin/env python3
"""Generate ivai-agent-climate-power-boundary-regression-v2 (CR-018).

200 cases: power 80 / vent 50 / fan 40 / airflow 20 / auto 10.
59 L0 (current catalog approved phrases) + 141 L1 (semantically clear rewrites).
Verifies: counts, no L1 case matches any L0 exactPhrase of any tool.
"""
import json, re, sys

# ---- L0 approved exact phrases per tool (from l0-governance-catalog.json) ----
L0_PHRASES = {
    "climate.power.set": ["打开空调","开启空调","把空调打开","开空调","空调打开",
                          "关闭空调","关空调","把空调关掉","关掉空调","空调关闭"],
    "climate.vent.set": ["打开主驾通风口","打开副驾通风口","打开驾驶位通风口","打开副驾驶通风口","打开后排通风口","打开全车通风口",
                         "开启主驾通风口","开启副驾通风口","开启驾驶位通风口","开启副驾驶通风口","开启后排通风口","开启全车通风口",
                         "关闭主驾通风口","关闭副驾通风口","关闭驾驶位通风口","关闭副驾驶通风口","关闭后排通风口","关闭全车通风口",
                         "关掉主驾通风口","关掉副驾通风口","关掉驾驶位通风口","关掉副驾驶通风口","关掉后排通风口","关掉全车通风口"],
    "climate.fan.speed.set": ["风量档位调到","设置风量档位","风量档位设为"],
    "climate.fan.speed.adjust": ["风量调高","调高风量","增加风量","风量调低","调低风量","降低风量"],
    "climate.airflow.mode.set": ["出风模式吹脸","设置出风模式为吹脸","出风模式吹脚","设置出风模式为吹脚",
                                 "出风模式除霜","设置出风模式为除霜","出风模式混合","设置出风模式为混合"],
    "climate.auto.set": ["打开自动空调","开启自动空调","关闭自动空调","关掉自动空调"],
}
ALL_L0 = [p for ps in L0_PHRASES.values() for p in ps]

def hits_l0(text: str) -> str | None:
    for p in sorted(ALL_L0, key=len, reverse=True):
        if p in text:
            return p
    return None


cases = []
i = 0
def add(tool, tier, text, args, desc, tags):
    global i
    i += 1
    cases.append({
        "caseId": f"POWER-{i:03d}",
        "description": desc,
        "tags": list(tags),
        "enabled": True,
        "input": text,
        "expectedTier": tier,
        "expectedDomain": "CABIN_COMFORT",
        "expectedCapabilityPack": "cabin.climate",
        "expectedTarget": {"type": "TOOL", "id": tool},
        "expectedArguments": args,
    })

def a(**kw): return {k: v for k, v in kw.items() if v is not None}

T, F = True, False
L0 = "L0_DETERMINISTIC_TOOL"
L1 = "L1_LOCAL_TOOL_REASONING"

# ================= L0 =================
# power 10
for ph in ["打开空调","开启空调","把空调打开","开空调","空调打开"]:
    add("climate.power.set", L0, ph, a(enabled=T), f"L0 power.on 批准短语 {ph}", ["L0","power","on"])
for ph in ["关闭空调","关空调","把空调关掉","关掉空调","空调关闭"]:
    add("climate.power.set", L0, ph, a(enabled=F), f"L0 power.off 批准短语 {ph}", ["L0","power","off"])
# vent 24
vent_on = [("打开主驾通风口","driver"),("打开副驾通风口","passenger"),("打开驾驶位通风口","driver"),
           ("打开副驾驶通风口","passenger"),("打开后排通风口","rear"),("打开全车通风口","all"),
           ("开启主驾通风口","driver"),("开启副驾通风口","passenger"),("开启驾驶位通风口","driver"),
           ("开启副驾驶通风口","passenger"),("开启后排通风口","rear"),("开启全车通风口","all")]
for ph, z in vent_on:
    add("climate.vent.set", L0, ph, a(zone=z, enabled=T), f"L0 vent.on 批准短语 {ph}", ["L0","vent","on"])
vent_off = [("关闭主驾通风口","driver"),("关闭副驾通风口","passenger"),("关闭驾驶位通风口","driver"),
            ("关闭副驾驶通风口","passenger"),("关闭后排通风口","rear"),("关闭全车通风口","all"),
            ("关掉主驾通风口","driver"),("关掉副驾通风口","passenger"),("关掉驾驶位通风口","driver"),
            ("关掉副驾驶通风口","passenger"),("关掉后排通风口","rear"),("关掉全车通风口","all")]
for ph, z in vent_off:
    add("climate.vent.set", L0, ph, a(zone=z, enabled=F), f"L0 vent.off 批准短语 {ph}", ["L0","vent","off"])
# fan.speed.set 7（当前 Catalog 批准短语；CR-018 已为 temperature.set 增加
# 风扇/风口/风向负向词，避免“风量档位调到/设为”被温度规则劫持。）
add("climate.fan.speed.set", L0, "风量档位调到5档", a(zone="all", level=5), "L0 fan.set 绝对档位", ["L0","fan","set"])
add("climate.fan.speed.set", L0, "设置风量档位2档", a(zone="all", level=2), "L0 fan.set 绝对档位", ["L0","fan","set"])
add("climate.fan.speed.set", L0, "风量档位设为3档", a(zone="all", level=3), "L0 fan.set 绝对档位", ["L0","fan","set"])
for text, z in [("中左风量档位调到5档","middle_left"),("中右风量档位调到2档","middle_right"),
                ("2排风量档位设为5档","second_row"),("3排风量档位调到5档","third_row")]:
    add("climate.fan.speed.set", L0, text, a(zone=z, level=5 if z!="middle_right" else 2), f"L0 fan.set 分区正例 {text}", ["L0","fan","set","zone"])
# fan.speed.adjust 6
for text, d in [("风量调高2档","increase"),("调高风量2档","increase"),("增加风量2档","increase"),
                ("风量调低2档","decrease"),("调低风量2档","decrease"),("降低风量2档","decrease")]:
    add("climate.fan.speed.adjust", L0, text, a(direction=d, step=2), f"L0 fan.adjust 批准短语 {text}", ["L0","fan","adjust"])
# airflow.mode.set 8
for text, m in [("出风模式吹脸","face"),("设置出风模式为吹脸","face"),("出风模式吹脚","feet"),
                ("设置出风模式为吹脚","feet"),("出风模式除霜","defrost"),("设置出风模式为除霜","defrost"),
                ("出风模式混合","mixed"),("设置出风模式为混合","mixed")]:
    add("climate.airflow.mode.set", L0, text, a(mode=m), f"L0 airflow 批准短语 {text}", ["L0","airflow","mode"])
# auto.set 4
for ph in ["打开自动空调","开启自动空调"]:
    add("climate.auto.set", L0, ph, a(enabled=T), f"L0 auto.on 批准短语 {ph}", ["L0","auto","on"])
for ph in ["关闭自动空调","关掉自动空调"]:
    add("climate.auto.set", L0, ph, a(enabled=F), f"L0 auto.off 批准短语 {ph}", ["L0","auto","off"])

# ================= L1 =================
# power L1 (70): explicit power expressions that do NOT hit any L0 phrase.
power_l1 = [
    ("启动空调", a(enabled=T)), ("启动空调系统", a(enabled=T)), ("接通空调电源", a(enabled=T)),
    ("启用空调系统", a(enabled=T)), ("让空调系统开始运行", a(enabled=T)), ("把空调电源打开", a(enabled=T)),
    ("空调电源开启", a(enabled=T)), ("空调系统打开", a(enabled=T)), ("空调电源打开", a(enabled=T)),
    ("空调系统开启", a(enabled=T)), ("启动空调电源", a(enabled=T)), ("打开整个空调系统", a(enabled=T)),
    ("空调系统启动", a(enabled=T)), ("让空调系统运行", a(enabled=T)), ("空调电源启动", a(enabled=T)),
    ("空调电源接通", a(enabled=T)), ("启动整个空调系统", a(enabled=T)), ("把空调系统打开", a(enabled=T)),
    ("打开主驾空调", a(zone="driver", enabled=T)), ("打开副驾空调", a(zone="passenger", enabled=T)),
    ("打开主驾空调电源", a(zone="driver", enabled=T)), ("启动主驾空调", a(zone="driver", enabled=T)),
    ("启动副驾空调系统", a(zone="passenger", enabled=T)), ("打开前排空调", a(zone="front", enabled=T)),
    ("打开后排空调", a(zone="rear", enabled=T)), ("打开全车空调", a(zone="all", enabled=T)),
    ("启动后排空调", a(zone="rear", enabled=T)), ("打开中左空调", a(zone="middle_left", enabled=T)),
    ("打开中右空调", a(zone="middle_right", enabled=T)), ("打开2排空调", a(zone="second_row", enabled=T)),
    ("打开3排空调", a(zone="third_row", enabled=T)), ("打开前排空调电源", a(zone="front", enabled=T)),
    ("把主驾空调启动", a(zone="driver", enabled=T)), ("把副驾空调启动", a(zone="passenger", enabled=T)),
    ("主驾空调开启", a(zone="driver", enabled=T)), ("副驾空调开启", a(zone="passenger", enabled=T)),
    ("接通主驾空调电源", a(zone="driver", enabled=T)), ("启用全车空调系统", a(zone="all", enabled=T)),
    ("后排空调开启", a(zone="rear", enabled=T)), ("2排空调启动", a(zone="second_row", enabled=T)),
    ("3排空调开启", a(zone="third_row", enabled=T)), ("中左空调启动", a(zone="middle_left", enabled=T)),
    ("中右空调启动", a(zone="middle_right", enabled=T)), ("主驾空调启动", a(zone="driver", enabled=T)),
    ("副驾空调启动", a(zone="passenger", enabled=T)), ("启动2排空调", a(zone="second_row", enabled=T)),
    ("启动3排空调", a(zone="third_row", enabled=T)), ("启动中左空调", a(zone="middle_left", enabled=T)),
    ("启动中右空调", a(zone="middle_right", enabled=T)), ("把后排空调启动", a(zone="rear", enabled=T)),
    ("把前排空调启动", a(zone="front", enabled=T)), ("把全车空调启动", a(zone="all", enabled=T)),
    ("主驾空调电源打开", a(zone="driver", enabled=T)), ("副驾空调电源打开", a(zone="passenger", enabled=T)),
    ("中左空调电源打开", a(zone="middle_left", enabled=T)), ("2排空调电源打开", a(zone="second_row", enabled=T)),
    ("空调系统关闭", a(enabled=F)), ("空调电源断开", a(enabled=F)), ("空调电源关闭", a(enabled=F)),
    ("空调电源关掉", a(enabled=F)), ("主驾空调断电", a(zone="driver", enabled=F)),
    ("副驾空调断电", a(zone="passenger", enabled=F)), ("把主驾空调关掉", a(zone="driver", enabled=F)),
    ("把副驾空调关掉", a(zone="passenger", enabled=F)), ("主驾空调电源关闭", a(zone="driver", enabled=F)),
    ("副驾空调电源关闭", a(zone="passenger", enabled=F)), ("关闭整个空调系统", a(enabled=F)),
    ("空调系统关掉", a(enabled=F)), ("断开主驾空调电源", a(zone="driver", enabled=F)),
    ("停止空调系统", a(enabled=F)), ("空调系统停止", a(enabled=F)), ("停掉空调系统", a(enabled=F)),
    ("空调电源断电", a(enabled=F)), ("全车空调电源关闭", a(zone="all", enabled=F)),
    ("前排空调电源关闭", a(zone="front", enabled=F)), ("后排空调电源关闭", a(zone="rear", enabled=F)),
]
power_l1 = power_l1[:70]

# vent L1 (26)
vent_l1 = [
    ("打开前排通风口", a(zone="front", enabled=T)), ("打开前排风口", a(zone="front", enabled=T)),
    ("关闭前排通风口", a(zone="front", enabled=F)), ("关闭后排风口", a(zone="rear", enabled=F)),
    ("开启前排风口", a(zone="front", enabled=T)), ("打开主驾风口", a(zone="driver", enabled=T)),
    ("关闭副驾风口", a(zone="passenger", enabled=F)), ("打开主驾出风口", a(zone="driver", enabled=T)),
    ("开启副驾出风口", a(zone="passenger", enabled=T)), ("关闭后排出风口", a(zone="rear", enabled=F)),
    ("打开全车风口", a(zone="all", enabled=T)), ("关闭全车风口", a(zone="all", enabled=F)),
    ("把主驾风口打开", a(zone="driver", enabled=T)), ("把副驾风口关掉", a(zone="passenger", enabled=F)),
    ("主驾风口打开", a(zone="driver", enabled=T)), ("副驾风口关掉", a(zone="passenger", enabled=F)),
    ("打开中左风口", a(zone="middle_left", enabled=T)), ("打开中右风口", a(zone="middle_right", enabled=T)),
    ("打开2排风口", a(zone="second_row", enabled=T)), ("打开3排风口", a(zone="third_row", enabled=T)),
    ("关闭中左风口", a(zone="middle_left", enabled=F)), ("开启后排风口", a(zone="rear", enabled=T)),
    ("关掉全车风口", a(zone="all", enabled=F)), ("打开前排出风口", a(zone="front", enabled=T)),
    ("关闭前排出风口", a(zone="front", enabled=F)), ("把后排风口打开", a(zone="rear", enabled=T)),
]

# fan L1 (27): set 17 + adjust 10
fan_set_l1 = [
    ("风量调到5档", a(zone="all", level=5)), ("风速设成3档", a(zone="all", level=3)),
    ("风量设为2档", a(zone="all", level=2)), ("风速调到7档", a(zone="all", level=7)),
    ("中左风量调到5档", a(zone="middle_left", level=5)), ("2排风速设成3档", a(zone="second_row", level=3)),
    ("3排风量调到4档", a(zone="third_row", level=4)), ("中右风量设为5档", a(zone="middle_right", level=5)),
    ("副驾风量调到3档", a(zone="passenger", level=3)), ("主驾风量设成5档", a(zone="driver", level=5)),
    ("风量调整到6档", a(zone="all", level=6)), ("风速调整到4档", a(zone="all", level=4)),
    ("风量开到5档", a(zone="all", level=5)), ("风量调成2档", a(zone="all", level=2)),
    ("前排风量调到3档", a(zone="front", level=3)), ("后排风量设成4档", a(zone="rear", level=4)),
    ("全车风量调到5档", a(zone="all", level=5)),
]
fan_adjust_l1 = [
    ("风量调大一点", a(direction="increase", step=1)), ("风量调小一点", a(direction="decrease", step=1)),
    ("风量再大一点", a(direction="increase", step=1)), ("风量再小一点", a(direction="decrease", step=1)),
    ("风速调大一些", a(direction="increase", step=1)), ("风速调小一些", a(direction="decrease", step=1)),
    ("风量调大2档", a(direction="increase", step=2)), ("风量调小1档", a(direction="decrease", step=1)),
    ("风量加大一档", a(direction="increase", step=1)), ("风量减小一档", a(direction="decrease", step=1)),
]

# airflow L1 (12)
airflow_l1 = [
    ("吹脸", a(mode="face")), ("吹脚", a(mode="feet")), ("除霜", a(mode="defrost")),
    ("混合出风", a(mode="mixed")), ("改成吹脚", a(mode="feet")), ("设置出风方向为吹脸", a(mode="face")),
    ("风向调成吹脚", a(mode="feet")), ("出风模式改成除霜", a(mode="defrost")), ("改成混合出风", a(mode="mixed")),
    ("出风方向吹脚", a(mode="feet")), ("出风改为吹脸", a(mode="face")), ("设置风向为吹脚", a(mode="feet")),
]

# auto L1 (6)
auto_l1 = [
    ("打开AUTO模式", a(enabled=T)), ("开启自动模式", a(enabled=T)), ("自动空调启动", a(enabled=T)),
    ("关闭AUTO模式", a(enabled=F)), ("自动空调开启", a(enabled=T)), ("自动空调停止", a(enabled=F)),
]

def emit_l1(tool, pairs, tag, desc_prefix):
    for text, args in pairs:
        hit = hits_l0(text)
        if hit:
            print(f"  !! L1 {tool} 命中 L0 短语 '{hit}': {text}")
            sys.exit(1)
        add(tool, L1, text, args, f"{desc_prefix} {text}", ["L1", tag, "rewrite"])

emit_l1("climate.power.set", power_l1, "power", "L1 power 明确电源表达")
emit_l1("climate.vent.set", vent_l1, "vent", "L1 vent 风口开关")
emit_l1("climate.fan.speed.set", fan_set_l1, "fan", "L1 fan.set 绝对档位")
emit_l1("climate.fan.speed.adjust", fan_adjust_l1, "fan", "L1 fan.adjust 相对增减")
emit_l1("climate.airflow.mode.set", airflow_l1, "airflow", "L1 airflow 风向模式")
emit_l1("climate.auto.set", auto_l1, "auto", "L1 auto 自动空调")

# ================= verify =================
from collections import Counter
tools = Counter(c["expectedTarget"]["id"] for c in cases)
tiers = Counter(c["expectedTier"] for c in cases)
print("total:", len(cases))
print("by tool:", dict(tools))
print("by tier:", dict(tiers))
assert len(cases) == 200, len(cases)
assert tiers[L0] == 59, tiers
assert tiers[L1] == 141, tiers
assert tools["climate.power.set"] == 80, tools
assert tools["climate.vent.set"] == 50, tools
assert tools["climate.fan.speed.set"] + tools["climate.fan.speed.adjust"] == 40, tools
assert tools["climate.airflow.mode.set"] == 20, tools
assert tools["climate.auto.set"] == 10, tools
ids = [c["caseId"] for c in cases]
assert len(set(ids)) == 200, "caseId unique"

suite = {
    "suiteId": "ivai-agent-climate-power-boundary-regression-v2",
    "schemaVersion": 1,
    "governanceVersion": "ivai-governance-v1",
    "cases": cases,
}
out = "app-demo/src/debug/assets/agent-tests/v2/agent-regression.json"
with open(out, "w", encoding="utf-8") as f:
    json.dump(suite, f, ensure_ascii=False, indent=1)
    f.write("\n")
print("written:", out)
