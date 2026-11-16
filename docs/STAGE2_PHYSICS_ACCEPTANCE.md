# 阶段 2 验收报告：桌球物理引擎

- 验收日期：2026-11-16
- 代码基线：`034061c`（阶段 1 工程骨架验收 + F1 刘海修复合入）
- 前置条件：**阶段 1 已 PASS**（见
  [`STAGE1_ENGINEERING_ACCEPTANCE.md`](STAGE1_ENGINEERING_ACCEPTANCE.md)；
  本轮开始时复跑 `:core:test` 32/32、`:app:testDebugUnitTest` 16/16 全绿确认）
- 验收环境：JDK 21.0.13、Gradle 9.8.2、AGP 9.5.0、Kotlin 2.2.10、
  compileSdk/targetSdk 37、minSdk 26，本机 Debian 13 x86_64
- 本轮性质：按 `docs/audit/PHYSICS_DESIGN.md` 第 8.2 节的补强计划，对既有物理
  引擎做不变量模糊、确定性、能量、袋口和性能的系统验收；**不改动物理手感参数**。

> 编号说明：`docs/STAGE_REPORT.md` 中的“阶段 2（物理引擎）”是历史开发顺序记录；
> 本轮“阶段 2”是工程基线补强序列中的物理引擎验收轮，两者并存，不改写历史数据。

## 1. 验收结论

**通过。** 55 个 JVM 测试全绿（core 39 + app 16），debug/release APK 构建成功，
lint 0 error。发现 1 个真实物理缺陷（开球永不静止/睡眠饥饿）并修复，经确定性
复现验证；其余设计简化（碰撞求解器微量能量注入、袋口角柱近似）均有量化数据
支撑，在文档阈值内。

| 验收项 | 证据 | 结论 |
|---|---|---|
| 阶段 1 基线未回退 | 复跑 core 32 + app 16 全绿后再动工 | 通过 |
| 不变量模糊测试 | 新增 `PhysicsInvariantTest`：64 个种子随机开球至静止 + 16 布局 × 128 随机击球（>60 万步），每采样步断言有限/在界/单位四元数/无重叠超标 | 通过 |
| 确定性 | 5 个固定种子 × 3000 步状态哈希两次运行完全一致 | 通过 |
| 能量单调性 | 无击球时总动能强递减、逐秒桶不增；单步注入有实测上界 | 通过（含文档化容差） |
| 袋口行为回归 | 中袋/角袋扫射：口内必进、口外必回，全程不变量成立 | 通过 |
| 性能基线 | 桌面 JVM 7.8 µs/步（均值）、19.6 µs/步（最差），预算 250 µs/步 | 通过（真机待测） |
| 真实缺陷修复 | 开球永不静止（睡眠饥饿）修复后同场景 3.18 s 静止 | 通过 |
| 构建产物 | debug 1,355,560 B；release unsigned 417,445 B；lint 0 error | 通过 |

## 2. 发现一：开球永不静止（真实缺陷，已修复）

### 2.1 现象与复现

不变量模糊测试（seed=1，7.96 m/s 开球）发现：开球 3 秒后全部球速度已为 0，
但 `isMoving()` 恒为 true，45 秒仍不静止、`Settled` 事件永不触发。

### 2.2 根因

`resolveBallCollisions()` 对任意 `dist < 2r` 的球对无条件 `wake()` 并重置
`lowMotionTime`。球架网格的相邻球心距在浮点运算后比 `2r` 小几个 ULP（理论值
恰好 `2r`），于是未被撞动的球每步都“发生接触”→ 被唤醒 → 睡眠计时器清零 →
**永远无法入睡**。开球后若有球留在原位（轻开球极常见），整局卡死在“球未停”
状态，规则层拿不到 `Settled`，回合无法结束。既有 14 个物理测试最长只步进 8 秒
且多用隔离球，未覆盖此场景。

### 2.3 修复

`core/src/main/kotlin/com/minamikune/billiards/physics/PhysicsWorld.kt`
`resolveBallCollisions()`：重叠量 `<= 1e-9 m` 的接触视为浮点触碰伪影，
跳过位置修正、唤醒与冲量（真实碰撞的最小重叠在毫米量级，阈值有 6 个数量级
余量，不影响任何真实碰撞路径）。

### 2.4 验证

同一 seed 修复前 45 s 不静止、修复后 **3.18 s 全部入睡**；64 种子随机开球
全部在 30 s 内静止；`MatchEngineTest` 13 个规则测试无回退。

## 3. 发现二：碰撞求解器微量能量注入（设计内，量化记录在案）

两遍求解器在密集球堆中，第二遍可能把已被其他球对挤回接触的球对再施加一次
完整恢复系数冲量。64 个种子开球、82,710 步至全静止的实测：

- 能量上升的步数：42 步（0.05%）
- 最大单步上升：**1.04e-3 J**（开球动能 6.9 J 的 1.5e-4）

测试断言（宽于实测 5 倍）：单步上升 < 5e-3 J、累计注入 < 5e-2 J、
上升步数 < 1%、逐秒动能桶不增、终态动能 < 初始 1%。该行为是迭代冲量求解器的
已知特性（`PHYSICS_DESIGN.md` 8.2 第 3 条已预留“碰撞瞬间数值误差”），不改变
任何参数与手感。

## 4. 发现三：袋口过渡带实测（几何验证，非缺陷）

中袋 2 m/s 直射扫射（0.005 m 步进）：路径偏移 ≤ 0.060 m 必落袋
（与颚内表面 `SIDE_MOUTH_HALF − JAW_RADIUS = 0.060 m` 理论值吻合）；
0.065–0.095 m 弹回；≥ 0.10 m 可沿库边弧线灌入角袋（真实球台同样存在的
合法反弹）。测试按 ±0.05/±0.07–0.09 的清晰边界断言，并对每个扫射步执行
全量不变量检查；球从未卡死在袋口、从未飞出桌面（安全网未触发）。

## 5. 新增测试清单

`core/src/test/kotlin/com/minamikune/billiards/physics/PhysicsInvariantTest.kt`
（7 个测试，全部固定种子、失败可复现）：

| 测试 | 覆盖 |
|---|---|
| `randomBreaksNeverBreakTheTable` | 64 种子随机开球至静止；采样步不变量；最大重叠 ≤ 2 mm（设计文档位置修正容差） |
| `randomStrikesOnScatteredLayoutsStayValid` | 16 个随机合法布局 × 128 随机击球 × 0.5 s，逐步全量不变量（491,520 步） |
| `seededRunsProduceIdenticalStateHashes` | 5 种子 × 3000 步（每 500 步随机击球），1e-9 精度状态哈希两次一致 |
| `kineticEnergyNeverIncreasesWithoutAStrike` | 能量强递减 + 单步/累计注入上界（§3 容差） |
| `sweepingAimAcrossSidePocketMouthBehavesSanely` | 中袋口内必进/口外必回 + 不变量 |
| `sweepingAimAcrossCornerPocketMouthBehavesSanely` | 角袋同上 |
| `physicsStepCostStaysFarBelowFrameBudget` | 桌面性能基线：均值 7.8 µs/步，预算 250 µs/步 |

## 6. 测试命令与结果（本机复跑，2026-11-16）

```bash
export GRADLE_USER_HOME="$PWD/.gradle-home"
export ANDROID_USER_HOME="$PWD/.android-home"
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug \
  :app:assembleRelease :app:lintDebug --rerun-tasks
```

| 套件 | 数量 | 结果 |
|---|---:|---|
| `:core:test` | 39（物理 14 + 不变量 7 + 坐标 5 + 规则 13） | 0 失败 |
| `:app:testDebugUnitTest` | 16 | 0 失败 |
| `lintDebug` | — | 0 errors（既有 1 warning：mipmap-anydpi-v26 ObsoleteSdkInt） |
| debug APK | 1,355,560 B | sha256 `5b7d56c5…18bad3` |
| release unsigned APK | 417,445 B | sha256 `2805d506…218504` |

临时诊断探针 `Stage2Probe.kt` 按惯例（同阶段 0 `AuditProbeTest`）已删除，
原始结论数据收录于本报告 §2–§4。

## 7. 已知限制与后续

1. 性能数字为桌面 JVM；真机物理耗时需在阶段后续用 `adb shell dumpsys gfxinfo`
   或 Perfetto 复测（本机无设备/KVM，同阶段 1 结论）。
2. 能量注入上界 5e-3 J/步为测试断言值，实测 worst 1.04e-3 J；若未来改碰撞
   求解器（如增加迭代遍数），需重新标定。
3. 四元数、坐标映射、规则层行为本轮未改动，沿用既有测试。
4. 仪器测试（9 个）本轮未改动，仍由 CI 的 API 35 ATD 任务执行；本机仅编译级
   验证。

## 8. 本阶段变更文件

- 修复：`core/src/main/kotlin/com/minamikune/billiards/physics/PhysicsWorld.kt`
  （`resolveBallCollisions()` 浮点触碰伪影跳过一次小改动，含注释）
- 新增测试：`core/src/test/kotlin/com/minamikune/billiards/physics/PhysicsInvariantTest.kt`
- 文档：本报告

## 9. 准入结论

物理引擎经不变量模糊、确定性、能量、袋口、性能五维验收；唯一真实缺陷
（睡眠饥饿）已修复并经确定性复现验证；55 个 JVM 测试全绿，构建产物正常。
**阶段 2 通过，具备进入下一阶段的条件。**
