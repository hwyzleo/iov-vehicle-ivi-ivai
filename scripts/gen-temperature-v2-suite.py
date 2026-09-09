#!/usr/bin/env python3
"""Generate ivai-agent-climate-temperature-boundary-regression-v2 (IVI-IVAI-DSN-CR-019).

schemaVersion=2, 200 cases in 8 groups:
  - 当前Catalog对齐L0相对调温 35 (L0, adjust)
  - L1相对调温改写 45 (L1, adjust)
  - 合法绝对目标温度 35 (set)
  - 最大/最小边界Alias 20 (set)
  - 越界绝对温度 15 (REJECTED)
  - 需要追问的歧义请求 20 (NEED_DIALOGUE)
  - 中左/中右拓扑用例 20 (L0/L1 adjust, middle zones)
  - adjust/set及相似Tool边界 10 (mixed)

Totals: Outcome EXECUTE 161 / NEED_DIALOGUE 24 / REJECTED 15
        Target adjust 95 / set 66 / 无执行Target 39
        Tier L0 55 / L1 145
"""
import json, sys

EXECUTE = "EXECUTE"
NEED = "NEED_DIALOGUE"
REJECT = "REJECTED"
L0 = "L0_DETERMINISTIC_TOOL"
L1 = "L1_LOCAL_TOOL_REASONING"

cases = []
def add(tier, outcome, tool, text, args, desc, tags, reason=None):
    c = {
        "caseId": f"TEMP-{len(cases)+1:03d}",
        "description": desc,
        "tags": list(tags),
        "enabled": True,
        "input": text,
        "expectedTier": tier,
        "expectedDomain": "CABIN_COMFORT",
        "expectedCapabilityPack": "cabin.climate",
        "expectedOutcome": outcome,
    }
    if tool is not None:
        c["expectedTarget"] = {"type": "TOOL", "id": tool}
    if args:
        c["expectedArguments"] = args
    if reason:
        c["expectedReasonCode"] = reason
    cases.append(c)

def a(**kw): return {k: v for k, v in kw.items() if v is not None}

# ============ 1) 当前Catalog对齐L0相对调温 35 ============
# adjust up/down with zone, exact phrases hitting L0 rules (vehicle_position_v2).
l0_adjust = [
    ("主驾温度调高一点", {"direction": "increase", "step": 1, "zone": "driver"}),
    ("副驾温度调高一点", {"direction": "increase", "step": 1, "zone": "passenger"}),
    ("主驾温度调高", {"direction": "increase", "zone": "driver"}),
    ("副驾温度调低一点", {"direction": "decrease", "step": 1, "zone": "passenger"}),
    ("主驾温度调低", {"direction": "decrease", "zone": "driver"}),
    ("主驾升温", {"direction": "increase", "zone": "driver"}),
    ("驾驶位升温", {"direction": "increase", "zone": "driver"}),
    ("主驾驶升温", {"direction": "increase", "zone": "driver"}),
    ("副驾升温", {"direction": "increase", "zone": "passenger"}),
    ("副驾驶升温", {"direction": "increase", "zone": "passenger"}),
    ("乘客位升温", {"direction": "increase", "zone": "passenger"}),
    ("主驾降温", {"direction": "decrease", "zone": "driver"}),
    ("驾驶位降温", {"direction": "decrease", "zone": "driver"}),
    ("主驾驶降温", {"direction": "decrease", "zone": "driver"}),
    ("副驾降温", {"direction": "decrease", "zone": "passenger"}),
    ("副驾驶降温", {"direction": "decrease", "zone": "passenger"}),
    ("乘客位降温", {"direction": "decrease", "zone": "passenger"}),
    ("主驾温度调高1度", {"direction": "increase", "step": 1, "zone": "driver"}),
    ("副驾温度调低1度", {"direction": "decrease", "step": 1, "zone": "passenger"}),
    ("主驾温度调高0.5度", {"direction": "increase", "step": 0.5, "zone": "driver"}),
    ("副驾温度调低0.5度", {"direction": "decrease", "step": 0.5, "zone": "passenger"}),
    ("主驾温度调高2度", {"direction": "increase", "step": 2, "zone": "driver"}),
    ("副驾温度调低2度", {"direction": "decrease", "step": 2, "zone": "passenger"}),
    ("主驾温度调高一点", {"direction": "increase", "step": 1, "zone": "driver"}),
    ("副驾温度调低一些", {"direction": "decrease", "step": 1, "zone": "passenger"}),
    ("主驾温度调高一丢丢", {"direction": "increase", "step": 0.5, "zone": "driver"}),
    ("副驾温度调低半度", {"direction": "decrease", "step": 0.5, "zone": "passenger"}),
    ("主驾温度加1度", {"direction": "increase", "step": 1, "zone": "driver"}),
    ("副驾温度减1度", {"direction": "decrease", "step": 1, "zone": "passenger"}),
    ("主驾温度升高1度", {"direction": "increase", "step": 1, "zone": "driver"}),
    ("副驾温度降低1度", {"direction": "decrease", "step": 1, "zone": "passenger"}),
    ("主驾温度增加2度", {"direction": "increase", "step": 2, "zone": "driver"}),
    ("副驾温度减少1度", {"direction": "decrease", "step": 1, "zone": "passenger"}),
    ("主驾温度调高一点", {"direction": "increase", "step": 1, "zone": "driver"}),
    ("前排温度调高一点", {"direction": "increase", "step": 1, "zone": "front"}),
]
for text, args in l0_adjust[:35]:
    add(L0, EXECUTE, "climate.temperature.adjust", text, args,
        f"L0 adjust 相对调温 {text}", ["L0", "adjust", "relative"])

# ============ 2) L1相对调温改写 45 ============
l1_adjust = [
    ("温度调高一点", {"direction": "increase", "step": 1}),
    ("温度调低一点", {"direction": "decrease", "step": 1}),
    ("温度调高", {"direction": "increase"}),
    ("温度调低", {"direction": "decrease"}),
    ("温度升高一点", {"direction": "increase", "step": 1}),
    ("温度降低一点", {"direction": "decrease", "step": 1}),
    ("温度升高2度", {"direction": "increase", "step": 2}),
    ("温度降低2度", {"direction": "decrease", "step": 2}),
    ("温度增加1度", {"direction": "increase", "step": 1}),
    ("温度减少1度", {"direction": "decrease", "step": 1}),
    ("把主驾温度调高一点", {"direction": "increase", "step": 1, "zone": "driver"}),
    ("把副驾温度调低一点", {"direction": "decrease", "step": 1, "zone": "passenger"}),
    ("帮我把主驾温度升高", {"direction": "increase", "zone": "driver"}),
    ("把副驾温度降低一点", {"direction": "decrease", "step": 1, "zone": "passenger"}),
    ("空调温度调高一点", {"direction": "increase", "step": 1}),
    ("空调温度调低一点", {"direction": "decrease", "step": 1}),
    ("温度调高一些", {"direction": "increase", "step": 1}),
    ("温度调低一些", {"direction": "decrease", "step": 1}),
    ("温度调高明显一些", {"direction": "increase", "step": 2}),
    ("温度调低明显一些", {"direction": "decrease", "step": 2}),
    ("温度再调高一点", {"direction": "increase", "step": 1}),
    ("温度再调低一点", {"direction": "decrease", "step": 1}),
    ("主驾温度再调高1度", {"direction": "increase", "step": 1, "zone": "driver"}),
    ("副驾温度再调低1度", {"direction": "decrease", "step": 1, "zone": "passenger"}),
    ("空调温度升高一点", {"direction": "increase", "step": 1}),
    ("空调温度降低一点", {"direction": "decrease", "step": 1}),
    ("把温度调高一点", {"direction": "increase", "step": 1}),
    ("把温度调低一点", {"direction": "decrease", "step": 1}),
    ("温度升高1度", {"direction": "increase", "step": 1}),
    ("温度降低1度", {"direction": "decrease", "step": 1}),
    ("主驾温度升高一点", {"direction": "increase", "step": 1, "zone": "driver"}),
    ("副驾温度降低一点", {"direction": "decrease", "step": 1, "zone": "passenger"}),
    ("温度调高一点", {"direction": "increase", "step": 1}),
    ("主驾空调温度调高一点", {"direction": "increase", "step": 1, "zone": "driver"}),
    ("副驾空调温度调低一点", {"direction": "decrease", "step": 1, "zone": "passenger"}),
    ("温度加一点", {"direction": "increase", "step": 1}),
    ("温度减一点", {"direction": "decrease", "step": 1}),
    ("温度升温一点", {"direction": "increase", "step": 1}),
    ("温度降温一点", {"direction": "decrease", "step": 1}),
    ("温度稍微调高一点", {"direction": "increase", "step": 1}),
    ("温度稍微调低一点", {"direction": "decrease", "step": 1}),
    ("空调温度升高", {"direction": "increase"}),
    ("空调温度降低", {"direction": "decrease"}),
    ("主驾温度再升高1度", {"direction": "increase", "step": 1, "zone": "driver"}),
    ("副驾温度再降低1度", {"direction": "decrease", "step": 1, "zone": "passenger"}),
]
for text, args in l1_adjust[:45]:
    add(L1, EXECUTE, "climate.temperature.adjust", text, args,
        f"L1 adjust 相对调温改写 {text}", ["L1", "adjust", "relative"])

# ============ 3) 合法绝对目标温度 35 (set) ============
set_legal = [
    ("主驾温度调到24度", {"temperature": 24, "zone": "driver"}),
    ("副驾温度调到25度", {"temperature": 25, "zone": "passenger"}),
    ("主驾温度设为26度", {"temperature": 26, "zone": "driver"}),
    ("副驾温度设为24度", {"temperature": 24, "zone": "passenger"}),
    ("主驾温度调到20度", {"temperature": 20, "zone": "driver"}),
    ("副驾温度调到28度", {"temperature": 28, "zone": "passenger"}),
    ("主驾温度设成25度", {"temperature": 25, "zone": "driver"}),
    ("副驾温度设成22度", {"temperature": 22, "zone": "passenger"}),
    ("温度调到24度", {"temperature": 24}),
    ("温度设为26度", {"temperature": 26}),
    ("空调设成25度", {"temperature": 25}),
    ("目标温度24度", {"temperature": 24}),
    ("温度保持在26度", {"temperature": 26}),
    ("设置温度到24度", {"temperature": 24}),
    ("温度调节到25度", {"temperature": 25}),
    ("主驾目标温度24度", {"temperature": 24, "zone": "driver"}),
    ("副驾目标温度26度", {"temperature": 26, "zone": "passenger"}),
    ("温度调到16度", {"temperature": 16}),
    ("温度调到30度", {"temperature": 30}),
    ("主驾温度调到16度", {"temperature": 16, "zone": "driver"}),
    ("副驾温度调到30度", {"temperature": 30, "zone": "passenger"}),
    ("温度设为20度", {"temperature": 20}),
    ("温度设为28度", {"temperature": 28}),
    ("主驾空调温度调到24度", {"temperature": 24, "zone": "driver"}),
    ("副驾空调温度调到25度", {"temperature": 25, "zone": "passenger"}),
    ("温度设置到26度", {"temperature": 26}),
    ("温度设定为24度", {"temperature": 24}),
    ("主驾温度设定为25度", {"temperature": 25, "zone": "driver"}),
    ("副驾温度设定为26度", {"temperature": 26, "zone": "passenger"}),
    ("温度调到18度", {"temperature": 18}),
    ("温度调到29度", {"temperature": 29}),
    ("主驾温度调到18度", {"temperature": 18, "zone": "driver"}),
    ("副驾温度调到29度", {"temperature": 29, "zone": "passenger"}),
    ("把主驾温度调到24度", {"temperature": 24, "zone": "driver"}),
    ("把副驾温度调到25度", {"temperature": 25, "zone": "passenger"}),
]
for text, args in set_legal[:35]:
    add(L1, EXECUTE, "climate.temperature.set", text, args,
        f"set 合法绝对目标 {text}", ["set", "absolute"])

# ============ 4) 最大/最小边界Alias 20 (set, bound) ============
set_bound = [
    ("主驾温度调到最高", {"temperature": 30, "zone": "driver"}),
    ("副驾温度调到最高", {"temperature": 30, "zone": "passenger"}),
    ("主驾温度调到最低", {"temperature": 16, "zone": "driver"}),
    ("副驾温度调到最低", {"temperature": 16, "zone": "passenger"}),
    ("主驾温度设为最高", {"temperature": 30, "zone": "driver"}),
    ("副驾温度设为最低", {"temperature": 16, "zone": "passenger"}),
    ("温度调到最高", {"temperature": 30}),
    ("温度调到最低", {"temperature": 16}),
    ("温度设为最高", {"temperature": 30}),
    ("温度设为最低", {"temperature": 16}),
    ("主驾温度调至最高", {"temperature": 30, "zone": "driver"}),
    ("副驾温度调至最低", {"temperature": 16, "zone": "passenger"}),
    ("温度调最大", {"temperature": 30}),
    ("温度调最小", {"temperature": 16}),
    ("温度调到最大", {"temperature": 30}),
    ("温度调到最小", {"temperature": 16}),
    ("主驾温度调到最热", {"temperature": 30, "zone": "driver"}),
    ("副驾温度调到最热", {"temperature": 30, "zone": "passenger"}),
    ("主驾温度调到最冷", {"temperature": 16, "zone": "driver"}),
    ("副驾温度调到最冷", {"temperature": 16, "zone": "passenger"}),
]
for text, args in set_bound[:20]:
    add(L1, EXECUTE, "climate.temperature.set", text, args,
        f"set 边界Alias {text}", ["set", "bound"])

# ============ 5) 越界绝对温度 15 (REJECTED) ============
out_of_range = [
    ("温度调到35度", REJECT, None),
    ("温度调到5度", REJECT, None),
    ("温度调到40度", REJECT, None),
    ("温度调到0度", REJECT, None),
    ("主驾温度调到35度", REJECT, None),
    ("副驾温度调到38度", REJECT, None),
    ("温度调到100度", REJECT, None),
    ("温度调到-10度", REJECT, None),
    ("主驾温度调到50度", REJECT, None),
    ("温度调到15度", REJECT, None),
    ("温度调到31度", REJECT, None),
    ("副驾温度调到32度", REJECT, None),
    ("温度调到60度", REJECT, None),
    ("温度调到45度", REJECT, None),
    ("主驾温度调到8度", REJECT, None),
]
for text, outcome, tool in out_of_range[:15]:
    add(L1, REJECT, None, text, None,
        f"越界绝对温度 {text}", ["set", "out-of-range"], reason="IVAI-TEMP-RANGE-001")

# ============ 6) 需要追问的歧义请求 20 (NEED_DIALOGUE) ============
ambiguous = [
    ("温度调到", NEED, None),
    ("温度设为", NEED, None),
    ("温度调到5", NEED, None),
    ("温度调到", NEED, None),
    ("调到24度", NEED, None),
    ("24度", NEED, None),
    ("空调温度", NEED, None),
    ("温度", NEED, None),
    ("温度调到多少", NEED, None),
    ("调到多少度", NEED, None),
    ("温度调高到", NEED, None),
    ("温度降低到", NEED, None),
    ("调到", NEED, None),
    ("设定温度", NEED, None),
    ("温度目标", NEED, None),
    ("温度调到多少度", NEED, None),
    ("温度调一丢丢", NEED, None),
    ("温度调一下", NEED, None),
    ("把温度调到", NEED, None),
    ("温度要多少", NEED, None),
]
for text, outcome, tool in ambiguous[:20]:
    add(L1, NEED, None, text, None,
        f"歧义追问 {text}", ["ambiguous"], reason="IVAI-TEMP-SEMANTIC-001")

# ============ 7) 中左/中右拓扑用例 20（全 L0：12 adjust + 8 set，带 zone） ============
topology_adjust = [
    ("中左温度调高一点", {"direction": "increase", "step": 1, "zone": "middle_left"}),
    ("中右温度调高一点", {"direction": "increase", "step": 1, "zone": "middle_right"}),
    ("中左温度调低一点", {"direction": "decrease", "step": 1, "zone": "middle_left"}),
    ("中右温度调低一点", {"direction": "decrease", "step": 1, "zone": "middle_right"}),
    ("2排左温度调高一点", {"direction": "increase", "step": 1, "zone": "middle_left"}),
    ("2排右温度调低一点", {"direction": "decrease", "step": 1, "zone": "middle_right"}),
    ("中排左温度调高一点", {"direction": "increase", "step": 1, "zone": "middle_left"}),
    ("中排右温度调高一点", {"direction": "increase", "step": 1, "zone": "middle_right"}),
    ("第二排左温度调高1度", {"direction": "increase", "step": 1, "zone": "middle_left"}),
    ("第二排右温度调低1度", {"direction": "decrease", "step": 1, "zone": "middle_right"}),
    ("2排左温度调高2度", {"direction": "increase", "step": 2, "zone": "middle_left"}),
    ("2排右温度调低2度", {"direction": "decrease", "step": 2, "zone": "middle_right"}),
]
topology_set = [
    ("中左温度调到24度", {"temperature": 24, "zone": "middle_left"}),
    ("中右温度调到25度", {"temperature": 25, "zone": "middle_right"}),
    ("中左温度设为26度", {"temperature": 26, "zone": "middle_left"}),
    ("中右温度设为24度", {"temperature": 24, "zone": "middle_right"}),
    ("中左温度调到最高", {"temperature": 30, "zone": "middle_left"}),
    ("中右温度调到最低", {"temperature": 16, "zone": "middle_right"}),
    ("2排左温度调到24度", {"temperature": 24, "zone": "middle_left"}),
    ("2排右温度调到25度", {"temperature": 25, "zone": "middle_right"}),
]
for text, args in topology_adjust[:12]:
    add(L0, EXECUTE, "climate.temperature.adjust", text, args,
        f"中左/中右拓扑 adjust {text}", ["zone", "topology", "L0"])
for text, args in topology_set[:8]:
    add(L0, EXECUTE, "climate.temperature.set", text, args,
        f"中左/中右拓扑 set {text}", ["zone", "topology", "L0"])

# ============ 8) adjust/set及相似Tool边界 10（3 set + 3 adjust + 4 NEED） ============
boundary_set = [
    ("主驾温度调高到24度", {"temperature": 24, "zone": "driver"}),  # 相对+到 → 绝对
    ("温度调高到25度", {"temperature": 25}),
    ("主驾温度调至24度", {"temperature": 24, "zone": "driver"}),
]
boundary_adjust = [
    ("温度调高1度", {"direction": "increase", "step": 1}),
    ("空调温度调高", {"direction": "increase"}),
    ("温度升高2度", {"direction": "increase", "step": 2}),
]
boundary_need = [
    ("空调温度", None),  # HVAC对象仅证据，不执行
    ("打开空调温度", None),
    ("温度调到5档", None),  # 档位非温度单位 → 追问
    ("温度调到24", None),  # 无单位 → 追问
]
for text, args in boundary_set[:3]:
    add(L1, EXECUTE, "climate.temperature.set", text, args,
        f"adjust/set 边界 → set {text}", ["boundary", "set"])
for text, args in boundary_adjust[:3]:
    add(L1, EXECUTE, "climate.temperature.adjust", text, args,
        f"adjust/set 边界 → adjust {text}", ["boundary", "adjust"])
for text, args in boundary_need[:4]:
    add(L1, NEED, None, text, None,
        f"adjust/set 边界 → 追问 {text}", ["boundary", "ambiguous"], reason="IVAI-TEMP-SEMANTIC-001")

# ============ verify ============
from collections import Counter
tools = Counter(c.get("expectedTarget", {}).get("id") for c in cases)
tools.pop(None, None)
outcomes = Counter(c["expectedOutcome"] for c in cases)
tiers = Counter(c["expectedTier"] for c in cases)
print("total:", len(cases))
print("by target:", dict(tools))
print("by outcome:", dict(outcomes))
print("by tier:", dict(tiers))
assert len(cases) == 200, len(cases)
assert outcomes[EXECUTE] == 161, outcomes
assert outcomes[NEED] == 24, outcomes
assert outcomes[REJECT] == 15, outcomes
assert tiers[L0] == 55, tiers
assert tiers[L1] == 145, tiers
assert tools["climate.temperature.adjust"] == 95, tools
assert tools["climate.temperature.set"] == 66, tools
ids = [c["caseId"] for c in cases]
assert len(set(ids)) == 200, "caseId unique"

suite = {
    "suiteId": "ivai-agent-climate-temperature-boundary-regression-v2",
    "schemaVersion": 2,
    "governanceVersion": "ivai-governance-v1",
    "cases": cases,
}
out = "app-demo/src/debug/assets/agent-tests/v2/temperature-boundary-v2.json"
with open(out, "w", encoding="utf-8") as f:
    json.dump(suite, f, ensure_ascii=False, indent=1)
    f.write("\n")
print("written:", out)
