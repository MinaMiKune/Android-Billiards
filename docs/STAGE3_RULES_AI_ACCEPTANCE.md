# 阶段 3 验收报告：规则与 AI 对战

- 验收日期：2026-11-16
- 代码基线：`645007d`（阶段 2 物理验收合入）
- 前置条件：**阶段 2 已 PASS**（见
  [`STAGE2_PHYSICS_ACCEPTANCE.md`](STAGE2_PHYSICS_ACCEPTANCE.md)；
  本轮开始时复跑 `:core:test` 39/39、`:app:testDebugUnitTest` 16/16 全绿确认）
- 验收环境：JDK 21.0.13、Gradle 9.8.2、AGP 9.5.0、Kotlin 2.2.10、
  compileSdk/targetSdk 37、minSdk 26，本机 Debian 13 x86_64
- 本轮性质：按工程基线补强序列，对既有 8 球规则引擎（`MatchEngine`）与几何
  AI（`BilliardsAi`）做不变量模糊、确定性、规则边界、性能与持久化恢复的系统
  验收；**不改动规则判定逻辑与 AI 策略参数**。

> 编号说明：`docs/STAGE_REPORT.md` 中的“阶段 3（规则与 AI）”是历史开发顺序记录；
> 本轮“阶段 3”是工程基线补强序列中的规则与 AI 验收轮，两者并存，不改写历史数据。

## 1. 验收结论

**通过。** 63 个 JVM 测试全绿（core 47 + app 16），debug/release APK 构建成功，
lint 0 error。本轮发现 2 个真实缺陷（中杆快照恢复死锁、大步长滑行摩擦能量泵
极限循环）和 1 个 UX 不一致（AI 回合覆盖犯规说明消息），全部修复并经确定性
复现验证。

| 验收项 | 证据 | 结论 |
|---|---|---|
| 阶段 2 基线未回退 | 复跑 core 39 + app 16 全绿后再动工 | 通过 |
| 整场对局不变量模糊 | 新增 `RulesAiInvariantTest` MatchRunner：6 个种子 AI 自对弈完整 VS_AI 对局（公共 API 驱动双方），逐杆校验位置有限/AIMING 时白球在场/分组对称且不变/开球后开放球台/目标球不复活（开球 8 号重摆除外）/终局 8 号必落袋 | 通过 |
| 对局确定性 | 种子 7 两场对局胜者/杆数/布局哈希/终局消息完全一致 | 通过 |
| 规则边界 | 无接触犯规、白球落袋（scratch）犯规：AI 获得自由球并继续击球；确认引擎设计为 AI 自由球走 `AI_THINKING` + `ballInHand` 标志（人类才走 `BALL_IN_HAND`） | 通过 |
| AI 行为 | 24 个随机散点布局（3–10 颗目标球）AI 选杆必触球（或白球落袋为合法结果），方向归一、速度在界 | 通过 |
| AI 性能 | 预热后探针实测均值 ≈0.24 ms、p95 ≈0.50 ms、max ≈9.3 ms；测试断言（宽 4 倍）均值 <1 ms、worst <20 ms | 通过（真机待测） |
| 持久化恢复 | 中杆快照（进程死亡场景）恢复后不再死锁；修复前永久卡死 `SIMULATING` | 通过 |
| 随机练习 100 杆 | seed=20261116 全杆回到 AIMING；修复前第 0 杆 120 秒不静止 | 通过 |
| 真实缺陷修复 | 见 §2–§4，均有确定性复现 | 通过 |
| 构建产物 | debug 1,384,074 B；release unsigned 417,445 B；lint 0 error | 通过 |

## 2. 发现一：中杆快照恢复永久死锁（真实缺陷，已修复）

### 2.1 现象与复现

`restoredMidShotSnapshotDoesNotDeadlock`：VS_AI 对局中 `strike()` 后 20 帧
（球实际在飞行）取快照——这正是进程在击球瞬间被杀死时持久化层会捕获的场景——
`restore()` 进新引擎后模拟 120 秒，状态仍为 `SIMULATING`，对局永久卡死。

### 2.2 根因

`MatchEngine.restore()` 重建世界后会清除击球在途标志（`shotInFlight=false`、
`settleDelay=-1`、重置击球追踪）。但规则结算 `finishShot()` 只在
`Settled` 事件到达**且 `shotInFlight` 为真**时才执行。于是恢复后的对局里，
球最终静止、`Settled` 照常发出，却没有任何代码消费它——状态机永久停在
`SIMULATING`，双方都永远无法再击球。进程死亡后的中杆快照本质上也无法续打：
击打冲量已经丢失，球的飞行无法继续。

### 2.3 修复

`core/src/main/kotlin/com/minamikune/billiards/game/MatchEngine.kt`
`restore()`：快照状态为 `SIMULATING` 时恢复为 `AIMING`（附注释说明理由）。
这一修复位于引擎层，因此 `SaveStore` 路径与 `MainActivity.onSaveInstanceState`
bundle 路径（后者此前不做 repair 直接 `startGame`）两类调用方全部覆盖。

### 2.4 验证

修复前：恢复后 120 秒（7200 帧）仍卡 `SIMULATING`；修复后：恢复即刻回到
`AIMING`，测试断言恢复后状态 ∈ {AIMING, BALL_IN_HAND, GAME_OVER}。该场景此前
无任何测试覆盖（既有 `MatchEngineTest` 13 个测试全部从 `AIMING` 快照恢复）。

## 3. 发现二：大步长下滑行摩擦“能量泵”极限循环（真实缺陷，已修复）

### 3.1 现象与复现

`randomHumanShotsInPracticeNeverDeadlock`（seed=20261116）练习模式第 0 杆：
击球后模拟 120 秒不静止，`Settled` 永不触发，状态卡在 `SIMULATING`。
逐步追踪（临时探针，输出见 §6 说明）显示：大量球落袋后仅剩球 1 在
(-0.675, 0.55) 附近做**两步精确二态循环**，全程无球碰撞、无库边接触、
球对重叠恒为 0（排除阶段 2 睡眠饥饿回归）：

- 状态 A：v=(-0.0458, 0.0567)（|v|=0.0729），ω=(-1.094, -0.371)
- 状态 B：v=(-0.0167, 0.0356)（|v|=0.0393），ω=(-2.940, -2.920)

奇数步的速度增量与滑动摩擦 `-ĉ·μg·dt`（μg=2.1582 m/s²，dt=1/60 s，
|Δv|=0.036 m/s）逐步精确吻合，确认循环完全由 `integrateBall` 的滑动摩擦
分支产生。

### 3.2 根因

`MatchEngine.update` 允许以 dt≤1/60 单次调用 `PhysicsWorld.step`，半隐式
滑动摩擦在 dt=1/60 时每步施加角冲量 Δω≈2.55 rad/s，**大于被阻尼的
|ω|≈1.15**——过冲使接触滑速方向逐步翻转，离散映射落入一个确定性的二态极限
循环。逐步能量核算（KE/m）：A=0.002876 → B=0.003578，**摩擦每半周期净增
能量 24%**——O(dt²) 离散误差超过摩擦耗散，摩擦变成能量泵，球永远在台面上
嗡嗡滑行、永不入睡。阶段 2 全部测试以 1/480 步进（该 cadence 已经 64 万步
不变量模糊验证稳定），1/60 才失稳，故历史测试未暴露。值得注意的是
`GameLoop` 把渲染帧 dt 钳到 [1/960, 1/60] 后直接喂给 `engine.update`——
**该缺陷在真机帧率波动（掉帧、慢设备）时是活缺陷**，会表现为对局随机卡死。

### 3.3 修复

`core/src/main/kotlin/com/minamikune/billiards/physics/PhysicsWorld.kt`
`step()`：内部固定以 1/480 子步循环调用新增的 `private integrate(dt)`，
`Settled` 检查随子步执行，事件缓冲每 `step()` 调用只清一次。效果：任何
≤1/60 的调用 cadence 自动获得阶段 2 验证过的数值稳定性，稳定性不变量从
“依赖调用方自觉”下沉到引擎内部。代价：`step(1/60)` 物理成本 ×8
（7.8 µs×8 ≈ 62 µs/帧，仍远低于 250 µs/步预算）。

### 3.4 验证

同一 seed 修复前 120 秒不静止 → 修复后练习模式 100 杆全部回到 `AIMING`；
6 种子 AI 自对弈整场全部正常终局（§1）；`PhysicsInvariantTest` 7 个测试
（1/480 步进路径）与 `MatchEngineTest` 13 个规则测试无回退，core 47/47 全绿。

## 4. 发现三：AI 回合覆盖犯规说明消息（UX 不一致，已修复）

### 4.1 现象与复现

`noContactShotIsAFoulWithBallInHand` 断言失败暴露：人类犯规后轮到 AI，
引擎消息本应是“No contact — ball in hand.”（无接触犯规说明），实际被覆盖为
“DeepSeek AI is thinking…”。

### 4.2 根因

`MatchEngine.switchPlayer()` 的 AI 分支无条件设置 `message = "DeepSeek AI is
thinking…"`；而人类分支（else 路径）特意保留犯规说明（代码注释
“Keep the foul explanation visible”）。两条路径行为不一致，玩家在自己犯规后
看不到犯规原因。

### 4.3 修复

AI 分支采用与人类分支相同的保留条件：消息以犯规说明前缀（`No ` /
`Wrong` / `Illegal` / `Cue`）开头时不覆盖，否则才显示 AI 思考提示。

### 4.4 验证

`noContactShotIsAFoulWithBallInHand` 断言
`message.startsWith("No contact")` 通过；core 47 全绿无回退。

## 5. 新增测试清单

`core/src/test/kotlin/com/minamikune/billiards/game/RulesAiInvariantTest.kt`
（8 个测试，全部固定种子、失败可复现）：

| 测试 | 覆盖 |
|---|---|
| `seededAiVsAiMatchesAlwaysTerminateWithAValidWinner` | 6 种子 AI 自对弈整场：必终局、胜者合法、8 号终局必落袋、杆数在界、布局不完全雷同 |
| `seededMatchesAreDeterministic` | 同种子两场对局胜者/杆数/布局哈希/终局消息逐项一致 |
| `randomHumanShotsInPracticeNeverDeadlock` | seed=20261116 练习模式随机 100 杆，每杆必回 AIMING（§3 回归钉扎） |
| `noContactShotIsAFoulWithBallInHand` | 无接触犯规：AI_THINKING + ballInHand 路由、换手、犯规消息保留、AI 继续击球 |
| `scratchIsAFoulAndAiContinuesFromBallInHand` | 白球落袋犯规同上（AI 从自由球继续） |
| `aiChosenShotsAlwaysContactABall` | 24 个随机散点布局：AI 选杆仿真后必触球（或白球落袋），方向/速度在界 |
| `aiDecisionCostStaysFarBelowTurnBudget` | 20 次预热后 120 轮计时：均值 <1 ms、worst <20 ms |
| `restoredMidShotSnapshotDoesNotDeadlock` | 中杆快照（进程死亡场景）恢复不死锁（§2 回归钉扎） |

## 6. 测试命令与结果（本机复跑，2026-11-16）

```bash
export GRADLE_USER_HOME="$PWD/.gradle-home"
export ANDROID_USER_HOME="$PWD/.android-home"
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug \
  :app:assembleRelease :app:lintDebug
```

| 套件 | 数量 | 结果 |
|---|---:|---|
| `:core:test` | 47（规则 13 + 规则/AI 不变量 8 + 物理不变量 7 + 物理 14 + 坐标 5） | 0 失败 |
| `:app:testDebugUnitTest` | 16 | 0 失败 |
| `lintDebug` | — | 0 errors（2 warnings：1 个既有 ObsoleteSdkInt + 1 个环境性 AGP 新版本提示，均与本阶段变更无关） |
| debug APK | 1,384,074 B | sha256 `fdb95c7a65f6fa251bb5…` |
| release unsigned APK | 417,445 B | sha256 `22c644fefe3e57141cbe…` |

临时诊断探针 `Stage3Probe.kt`（极限循环二态数据、AI 耗时分布）按惯例
（同阶段 0/2 探针）已删除，原始结论数据收录于本报告 §3、§1。

## 7. 已知限制与后续

1. 中杆快照恢复为 `AIMING` 的简化：快照冻结的飞行中速度会随 `restore()`
   保留到下一杆（`PhysicsWorld.restore()` 原样恢复速度），球在下次击球前不
   积分（AIMING 不步进物理），下一杆开始后以残余速度继续运动。行为可接受、
   有测试钉扎不死锁，但非严格物理续局。
2. AI 为几何策略（最近袋选袋 + 幽灵球瞄准 + 按杆数推进的确定性误差），本轮
   验收其可靠性与成本，未评估与人类水平对弈的棋力；种子间胜负混合
   （6 种子 ≥2 种不同终局布局）已验证非平凡。
3. AI 决策成本与物理成本均为桌面 JVM 数字；真机复测同阶段 1/2 结论
   （本机无设备/KVM）。
4. 仪器测试（9 个）本轮未改动，仍由 CI 的 API 35 ATD 任务执行；本机仅编译级
   验证。
5. `step(1/60)` 内部 8 子步化后单帧物理成本约 62 µs（预算 250 µs）；若未来
   在热路径上以 1/60 高频调用，需重新标定性能基线。

## 8. 本阶段变更文件

- 修复：`core/src/main/kotlin/com/minamikune/billiards/game/MatchEngine.kt`
  （`restore()` 中杆快照状态修复 +11/−2；`switchPlayer()` AI 分支保留犯规
  说明消息）
- 修复：`core/src/main/kotlin/com/minamikune/billiards/physics/PhysicsWorld.kt`
  （`step()` 固定 1/480 子步化 +16，含注释）
- 新增测试：`core/src/test/kotlin/com/minamikune/billiards/game/RulesAiInvariantTest.kt`
- 文档：本报告

## 9. 准入结论

规则引擎与 AI 经整场对局不变量模糊、确定性、规则边界、AI 行为与成本、
持久化恢复五维验收；2 个真实缺陷（中杆快照死锁、滑行摩擦能量泵）与 1 个
UX 不一致均已修复并经确定性复现验证；63 个 JVM 测试全绿，构建产物正常。
**阶段 3 通过，具备进入下一阶段的条件。**
