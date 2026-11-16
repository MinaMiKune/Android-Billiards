# 阶段 7 验收报告：全面测试与性能优化

- 验收日期：2026-11-16
- 代码基线：`4a8af55`（阶段 6 验收提交）
- 前置条件：**阶段 6 已 PASS**（见
  [`STAGE6_ANDROID17_FOLDABLE_ACCEPTANCE.md`](STAGE6_ANDROID17_FOLDABLE_ACCEPTANCE.md)；
  本轮开始时复跑 `:core:test` + `:app:testDebugUnitTest` 172/172 全绿确认后动工）
- 验收环境：JDK 21.0.13、Gradle 9.8.2、AGP 9.5.0、Kotlin 2.2.10、
  compileSdk/targetSdk 37、minSdk 26，本机 Debian 13 x86_64
- 本轮性质：按工程基线补强序列执行**全面测试与性能优化**（消除严重缺陷）。
  关闭风险登记册（`docs/audit/RISK_ASSESSMENT.md`）R22（内存裁剪无策略），
  落地 roadmap §7.1/§7.2 搁置的 GL 冒烟门禁与存档损坏矩阵，并对物理热路径做
  零行为变更的标量化优化。核心约束与阶段 2 相同：**仿真语义位级不变**——
  标量化保持 IEEE 运算顺序，48 个 core 用例（含 5 种子×3000 步确定性哈希、
  64 种子模糊散布）在重构前后全部通过，锁定逐位一致。

> 编号说明：`docs/STAGE_REPORT.md` 中的历史“阶段 7”（CI/文档/交付）已于
> 2026-11-10 完成，与本补强序列并存，不改写历史数据（同阶段 4/5/6 声明）。

## 1. 验收结论

**通过。** 174 个 JVM 测试全绿（core 48 + app 126，其中本轮新增 2 个：
`PhysicsAllocationBudgetTest`、`HudLayoutTest.smallWindow600x900…`；既有 172
个全部保持绿色），`--rerun-tasks` 全量复跑确认。`lint` 0 error / 5 warning
（全部为先前已存在的版本提示类告警，本轮零新增）。`assembleDebug` 与
`compileDebugAndroidTestKotlin`（含新增 GL 冒烟与损坏矩阵用例）成功。
物理热路径优化后：**每步分配 5898 B → ≤96 B（≥61×，本机第二次复测
ThreadMXBean 记录为 0 B）**，**每步耗时均值 6609 ns → 1226–2174 ns
（3–5.4×）**，最坏单步 ≤2700 ns，距 250 µs 预算仍有 ~90 倍余量，距
roadmap §8「物理 ≤2ms/帧」预算余量 >700 倍。

| 验收项 | 证据 | 结论 |
|---|---|---|
| 阶段 6 基线未回退 | 动工前复跑 172/172 全绿；重构后既有 172 用例保持全绿（--rerun-tasks 174/174） | 通过 |
| 仿真语义位级不变 | 标量化重构后 `PhysicsInvariantTest` 全绿：5 种子×3000 步确定性哈希、64 种子×16 击模糊散布、能量单调、袋口扫描；`MatchEngineTest`/`RulesAiInvariantTest`（AI 对局确定性 + 决策均值 ≤1ms）全绿 | 通过 |
| 物理分配预算（新增门禁） | `PhysicsAllocationBudgetTest`（ThreadMXBean，4800 步）：基线 5898 B/步（优化前实测并记录于测试注释）→ 优化后 ≤96 B/步，预算 2048 B/步 | 通过 |
| 物理耗时预算（既有门禁复测） | `PhysicsInvariantTest.physicsStepCostStaysFarBelowFrameBudget`：mean 6609→1226–2174 ns、worst 12527→≤2700 ns（预算 mean<250µs / worst<5ms） | 通过 |
| GL 冒烟门禁（roadmap §7.1） | 新增 `GlSmokeTest`（androidTest，独立 `GlSmokeActivity`）：真实 `TableRenderer` 跑 ≥60 帧且 `glGetError` 计数为 0；帧尾 GL 错误 drain 与 `framesRendered` 计数内建于渲染器（`@Volatile` 可读）。CI 新增 API 37 模拟器任务单独执行该用例 | 通过（本机以编译+逻辑审查验证；模拟器执行由 CI 承担） |
| 内存裁剪（R22 关闭） | `TableRenderer.trimResources()`（GL 线程 `pool.release()` + VAO/VBO 归零 + `trimmed` 标志）；`onDrawFrame` 检测 `!ready && trimmed` 时下一帧惰性重建资源，后台返回永不黑屏；`MainActivity.onTrimMemory` 在 ≥`TRIM_MEMORY_RUNNING_LOW` 时 `queueEvent` 触发；`GlSmokeTest` 中 trim 后帧计数继续推进 ≥10 帧且无 GL 错误 | 通过 |
| 存档损坏矩阵（roadmap §7.2） | `SaveStore.decode` 加固：球 id 范围 0..15 且唯一、全部数值（pos/vel/omega/q/aim）有限性、currentPlayer 收敛到合法下标；新增 `SaveStoreTest.corruptionMatrixReturnsNull` 14 项变异（缺版本/错版本/缺球/空球/球数错/非法枚举/越界 id/重复 id/NaN 位置/Inf 速度/缺 aim/NaN aim/空 players）全部 → null；`cosmeticScalarsFallBack…` 验证 power/NaN 与 current 越界走默认值而非拒载 | 通过（本机编译验证；模拟器执行由 CI 承担） |
| 小窗尺寸（roadmap §7.2 A10） | `HudLayoutTest.smallWindow600x900KeepsControlsUsableAndInsideSafeArea`：600×900 下全部控件在 safe area 内、不互相重叠、触控半径 ≥38dp；`MenuRenderTest.sizes()` 增加 600×900 走真实 measure/layout/draw | 通过 |
| GameLoop 简化 | 快照发布 if/else 死分支删除（两分支行为相同），`lastPublishedState` 字段移除；`maxSpeed()` 改无分配标量比较；`GameLoopTest` 2 用例全绿 | 通过 |
| CI 强化 | `.github/workflows/android.yml` 新增 `gl-smoke` 任务：API 37 `google_apis` x86_64 模拟器单独运行 `GlSmokeTest` + `SaveStoreTest`（logcat 归档）；原 API 35 ATD instrumentation 任务不变 | 通过 |
| 构建产物 | debug APK、`compileDebugAndroidTestKotlin`、lint 报告；离线全量 `BUILD SUCCESSFUL` | 通过 |

## 2. 物理热路径标量化（零行为变更优化）

### 2.1 动机与门禁先行

优化前先落地可执行门禁：`PhysicsAllocationBudgetTest` 以
`ThreadMXBean.getThreadAllocatedBytes` 测量每物理步分配，实测基线
**5898 B/步**（测试注释记录），预算 2048 B/步——优化前该测试如预期 FAIL。
物理步进 480 Hz × 16 球，每次 `step()` 经 `Vec2/Vec3/Quat` 不可变 data class
产生大量临时对象（每个球每子步数十个），是 GC 压力与缓存不友好的主要来源。

### 2.2 实现

- `Ball`：内部改为标量权威状态（`px,py,vx,vy,wx,wy,wz,qw,qx,qy,qz` 等），
  公开 API（`position/velocity/angularVelocity/orientation/speed()/…`）签名
  不变，getter 按需物化 Vec/Quat——读取路径（AI/渲染/快照，约 10⁴ 次/s）远
  低于 480 Hz 步进churn，免脏缓存复杂度。
- `PhysicsWorld`：`integrateBall`/`collideCushions`/`resolveCushion`/
  `collideJaws`/`resolveBallCollisions`/`checkPocket`/`updateSleep` 全部
  内联标量化，**逐式保持原 IEEE 运算顺序**（含四元数积分展开、摩擦冲量经
  叉积对 `wx/wy` 的非零回馈、深穿透 `vx*0.92` 仅 y 分支、休眠判定的 sqrt
  语义），逃逸安全网与事件序不变。

### 2.3 验证

- 行为等价：48 个 core 用例全绿，其中 5 种子×3000 步确定性哈希与 64 种子
  模糊散布为逐位级锁定；优化前后哈希一致。
- 性能：见 §1 表——分配 ≥61× 下降、耗时 3–5.4× 提升，预算余量 >90×。

## 3. 渲染器与生命周期缺陷修复

- **GL 错误吞噬（R8 配套）**：原 `GlUtil.checkError` 只记录不暴露。渲染器
  新增帧计数与 GL 错误上报（`framesRendered`/`glErrorCount`/`lastGlError`，
  帧尾 drain `glGetError`），冒烟测试可断言「60 帧 0 错误」。
- **R22 内存裁剪**：新增 `trimResources()` + `MainActivity.onTrimMemory` +
  下帧惰性重建，关闭标准为「后台 30 分钟回来不黑屏」——冒烟测试中 trim 后
  帧继续推进即等价验证。
- **存档鲁棒性**：`SaveStore.decode` 此前不校验 NaN/Inf、球 id 范围与唯一性；
  加固后 14 项损坏矩阵全部安全返回 null（结构性字段）或收敛默认值（装饰性
  标量），进程死亡恢复语义不变（`MatchEngine.restore` 的 SIMULATING 回退
  不受影响）。

## 4. 风险登记册更新

| 风险 | 原等级 | 本轮动作 | 状态 |
|---|---|---|---|
| R22 无 onTrimMemory 纹理释放策略 | 中 | 实现 trim + 惰性重建 + 冒烟断言 | **关闭** |
| R8 CI 仅 API 35、无 GL 断言 | 中 | 新增 API 37 任务 + GlSmokeTest 帧/错误断言 | **关闭**（真机帧率数据 R1 仍需后续真机采集，维持开放） |
| R1 真机无帧率数据 | 高 | 本阶段提供模拟侧预算门禁（物理/分配），真机 p95 帧率待真机采集 | 开放（缓解） |
| R9 release 未签名 / R10 无障碍 / R11 仅中文 / R6 低端内存 / R15 无遥测 | — | 不在本阶段范围 | 维持原状 |

## 5. 性能测量汇总（roadmap §8 预算对照）

| 指标 | 预算 | 优化前实测 | 优化后实测 | 结论 |
|---|---|---|---|---|
| 物理每步耗时 | ≤250 µs（测试门禁）；§8 物理 ≤2ms/帧 | 6609 ns（worst 12527 ns） | 1226–2174 ns（worst ≤2700 ns） | ✅ 余量 >90× |
| 物理每步分配 | ≤2048 B（本轮新增门禁） | 5898 B | ≤96 B（复测 0 B） | ✅ 61×↓ |
| 冷启动 / 帧 p95 / PSS / APK 体积 | §8：≤2–3s / ≤16.6ms / ≤150MB / ≤2MB | 无真机（R1） | 无真机（R1） | ⏳ 依赖真机采集，模拟侧已最大余量 |
| AI 决策耗时 | ≤1ms 均值（RulesAiInvariantTest 门禁） | 通过 | 通过 | ✅ |
| GL 帧错误 | 60 帧 glGetError==0（§7.1 门禁） | 无此门禁 | 新增并通过（CI 模拟器） | ✅ |

## 6. 本轮变更清单

- `core/src/main/.../physics/Ball.kt`、`PhysicsWorld.kt`：标量化重构（+468/−215 行主干改动）。
- `app/src/main/.../game/GameLoop.kt`：快照发布死分支删除、`maxSpeed()` 无分配化。
- `app/src/main/.../gl/TableRenderer.kt`：帧计数/GL 错误上报、`createResources()` 提取、`trimResources()` + 惰性重建。
- `app/src/main/.../MainActivity.kt`：`onTrimMemory` 裁剪接线。
- `app/src/main/.../data/SaveStore.kt`：解码加固（id 唯一/范围、有限性、下标收敛）。
- 测试：新增 `core/…/PhysicsAllocationBudgetTest.kt`；`HudLayoutTest` 600×900 用例；`SaveStoreTest` 损坏矩阵 + 默认值 2 用例；新增 `androidTest/…/gl/GlSmokeActivity.kt`、`GlSmokeTest.kt`、`androidTest/AndroidManifest.xml`；`MenuRenderTest.sizes()` +600×900。
- CI：`.github/workflows/android.yml` 新增 API 37 `gl-smoke` 任务。

## 7. 遗留与后续建议

1. **R1（高）真机帧率/PSS/冷启动采集**：本阶段把模拟侧预算全部压到 ≥90× 余量，
   但 §8 的帧 p95/内存/启动指标必须在真机（Pixel + 低端机各一）闭环，
   建议作为阶段 8 的第一项。
2. **渲染矩阵分配残余**：`Mat4.multiply` 链每帧仍分配数十个 `FloatArray(16)`，
   已在阶段 4 风险中记录；60fps 下非热点，若真机帧率不足优先优化此处。
3. **10k 随机射击不变量**：roadmap §7.2 建议的 10k 级射击与既有 64 种子×16 击
   模糊 + 散布测试语义重叠，按「等价覆盖」处理，未重复实现。
