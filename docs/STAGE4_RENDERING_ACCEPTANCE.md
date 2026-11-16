# 阶段 4 验收报告：渲染与视觉效果

- 验收日期：2026-11-16
- 代码基线：`fddb4c2`（阶段 3 规则与 AI 验收基线合入）
- 前置条件：**阶段 3 已 PASS**（见
  [`STAGE3_RULES_AI_ACCEPTANCE.md`](STAGE3_RULES_AI_ACCEPTANCE.md)；
  本轮开始时复跑 `:core:test` 47/47、`:app:testDebugUnitTest` 16/16 全绿确认，
  阶段 3 未提交的验收变更已先合入为基线提交 `fddb4c2`）
- 验收环境：JDK 21.0.13、Gradle 9.8.2、AGP 9.5.0、Kotlin 2.2.10、
  compileSdk/targetSdk 37、minSdk 26，本机 Debian 13 x86_64；
  glslangValidator 15.1.0（`.tools/root/usr/bin/glslangValidator`）
- 本轮性质：按工程基线补强序列，对既有渲染实现（`TableRenderer` / `Meshes` /
  `Mat4` / 8 个内嵌 GLSL shader）做系统验收；核心工作是把渲染数学与几何生成
  提取为可在 JVM 上验证的纯函数（行为不变的重构），并新增 4 个测试套件；
  **不改动渲染视觉管线结构**（shader 源码零改动）。

> 编号说明：`docs/STAGE_REPORT.md` 中的“阶段 4（渲染与视觉效果）”是历史开发
> 顺序记录；本轮“阶段 4”是工程基线补强序列中的渲染验收轮，两者并存，不改写
> 历史数据。

## 1. 验收结论

**通过。** 101 个 JVM 测试全绿（core 47 + app 54，其中本轮新增 38 个），
debug/release APK 构建成功，lint 0 error，8 个 GLSL shader 经 glslangValidator
15.1.0 全部编译通过（8/8，无跳过）。本轮发现 1 个真实视觉缺陷（贴库向外瞄准
时幽灵球渲染到球桌外 1 米），已修复并加回归测试。

| 验收项 | 证据 | 结论 |
|---|---|---|
| 阶段 3 基线未回退 | 复跑 core 47 + app 16 全绿后合入基线 `fddb4c2` 再动工 | 通过 |
| 矩阵数学（`Mat4`） | `Mat4Test` 11 个用例：单位元/复合顺序/旋转基向量映射/lookAt 把眼睛映射到原点/投影逆矩阵在渲染器全部纵横比下往返/随机仿射逆/四元数旋转阵正交规范且与 rotateY 一致/奇异阵逆退化为单位阵 | 通过 |
| 网格几何数据（`Meshes`） | `MeshDataTest` 7 个用例：球面（三档精度）单位球位置=法线、极点重合扇剔除后无退化三角形、索引越界检查；box 六面法线轴对齐且绕向一致；plane/quad 单位方面朝 +Y；disc 单位圆扇；ring 内外半径；cueCylinder 沿 −X 单位半径；全部与 `Mesh.build` 的 8 float 步长布局一致 | 通过 |
| 相机拟合（`RenderMath.fitCamera`） | `RenderMathTest`：8 种纵横比 × 横竖屏共 16 组，预松弛距离下 8 个桌角（含 0.12 m 高）全部落入 NDC 安全区（\|x\|≤0.90、\|y\|≤0.80），距离回退 3% 必有桌角越界（证明二分拟合是贴界而非“足够远”），眼睛位于固定方向 ×1.015 松弛 | 通过 |
| 屏幕↔桌面双向映射 | 横竖屏下网格点 table→screen→table 误差 <1 cm；像素级 screen→table→screen 往返 <1.5 px；竖屏映射相对横屏旋转 90°（不对称桌角屏幕位置显著不同）；零尺寸面返回 null | 通过 |
| 瞄准目标几何（`aimTarget`） | 直线击球幽灵球距离 = 球距−2r、近球遮挡远球、身后/不活动球忽略、贴库回归（见 §3）、零界 rail 距离钳制到库面、斜向瞄准、越过库面的球输给库钳制 | 通过 |
| GLSL 源码结构 | `ShaderSourceTest`：8 个 shader 均 `#version 300 es`、括号配平、含 main；顶点属性布局与 Mesh 步长一致（ball/table: loc0 vec3 + loc1 vec3 + loc2 vec2；simple/line: 仅 loc0 vec3）；uniform 集合与渲染器 `loc()` 契约逐字一致（ball/table/shadow/line 四套）；frag 恰好一个输出 | 通过 |
| GLSL 编译 | glslangValidator 15.1.0 对 8 个 shader 全部 exit 0（测试内反射提取常量后落盘编译，skip=0） | 通过（8/8） |
| 真实缺陷修复 | 贴库向外瞄准幽灵球出界，确定性复现 + 修复 + 回归用例（§3） | 通过 |
| 构建产物 | debug APK 1,423,872 B；release unsigned 417,721 B；lint 0 error（2 个既有 warning：Gradle 版本提示、mipmap-v26 目录，均与渲染无关） | 通过 |

## 2. 行为不变的重构（为可测试性）

渲染器原本是 GL 回调与数学的整体（`TableRenderer` 886 行），几何生成直接调
`GLES30` 上传，无法在 JVM 上验证。本轮做了三处纯提取，逐字节保持行为：

1. **`Meshes` 数据/上传分离**（`gl/GlUtil.kt`）：每个几何生成器拆成
   `internal *Data(): Pair<FloatArray, ShortArray>` 纯函数（`sphereData`、
   `boxData`、`planeData`、`discData`、`ringData`、`cueCylinderData`、
   `quadData`），GL 包装层仅一行 `Mesh.build` 委托。生成算法逐行未动。
2. **`gl/RenderMath.kt` 新增**：把 `TableRenderer` 中的相机拟合
   （`fitCamera` 二分搜索）、瞄准目标（`aimTarget`）、屏幕↔桌面映射
   （`screenToTable`/`tableToScreen`，含竖屏旋转语义）提取为无 GL、无 Android
   依赖的纯函数；`TableRenderer` 四处调用点改为薄委托（线程同步与拷贝仍在
   renderer 内）。
3. **`BallRender` 实现 `BallXY` 接口**：`aimTarget` 通过只读接口接收球列表，
   `ballRender` 由 `Array` 改为 `List`（协变），渲染循环零分配。

重构验证：提取后全量 JVM 套件（含阶段 1–3 全部 63 个既有用例）保持全绿；
shader 源码零改动。

## 3. 发现一：贴库向外瞄准，幽灵球渲染到球桌外 1 米（真实缺陷，已修复）

### 3.1 现象与复现

白球置于库边允许位置（如角部 `(maxX, maxZ)`），向桌外方向拖出瞄准线：
`aimTarget` 两个库距离均 ≤ 0（`wall = min(0, 0) = 0`），落入
`if (!best.isFinite() || best <= 0.0) return c + d * 1.0` 兜底分支——幽灵球
被放到白球沿瞄准方向 **1 米** 处，即球桌以外；虚线瞄准线随之穿过库边延伸到
桌外。开局自由球（ball in hand）允许把球放在库边任意位置，该场景可由玩家
稳定触发。复现用例：
`RenderMathTest.aimingIntoTheRailFromTheCushionStaysOnTheTable`。

### 3.2 根因

兜底分支的本意是处理“找不到任何接触”的退化输入，但它把“接触距离合法地为
零”（贴库朝库瞄准）和“完全无处可去”混为一谈，用固定 1 米步长把目标点推出
了桌外，违反了 `aimTarget` 其余所有分支都不越过 `HALF − BALL_RADIUS` 库面的
不变量。

### 3.3 修复

`gl/RenderMath.kt` `RenderMath.aimTarget`：有限接触距离一律
`best.coerceAtLeast(0.0)` 钳制（幽灵球停在白球处，`drawAimGuide` 的
`len < 1e-5` 早退使本帧不画虚线，仅半透明幽灵球与白球重合，视觉无异常）；
仅非有限距离（实际不可达）保留 1 米兜底。回归断言：目标点等于 cue 位置且
`|x| ≤ maxX + ε`、`|y| ≤ maxZ + ε`；另加 `nearZeroRailDistanceClampsToCushionFace`
（贴库 1 cm 朝外瞄准精确落在库面）锁定钳制精度。

## 4. 测试与构建证据

```
:core:test                47/47  通过（阶段 2/3 既有，未改动）
:app:testDebugUnitTest    54/54  通过（16 既有 + 38 本轮新增）
  ├─ gl/Mat4Test                        11
  ├─ gl/MeshDataTest                     7
  ├─ gl/RenderMathTest                  15
  └─ gl/ShaderSourceTest                 5（glslangValidator 可用，skip=0）
:app:assembleDebug        成功，app-debug.apk 1,423,872 B
:app:assembleRelease      成功，app-release-unsigned.apk 417,721 B
:app:lint                 0 error，2 warning（Gradle 版本提示、mipmap-v26，均为既有）
```

新增/改动文件：

- `app/src/main/kotlin/com/minamikune/billiards/gl/RenderMath.kt`（新增，147 行）
- `app/src/main/kotlin/com/minamikune/billiards/gl/GlUtil.kt`（`Meshes` 数据/上传拆分）
- `app/src/main/kotlin/com/minamikune/billiards/gl/TableRenderer.kt`（4 处委托，`BallRender : BallXY`）
- `app/src/test/kotlin/com/minamikune/billiards/gl/{Mat4Test,MeshDataTest,RenderMathTest,ShaderSourceTest}.kt`（新增 38 用例）

## 5. 遗留与后续门禁（不阻塞本轮 PASS）

- **真机视觉/性能验收仍开放**：本轮验收覆盖几何/相机/映射/shader 的正确性；
  帧率、纹理内存（约 15 MB）与视觉效果的最终确认需要真机或带 GPU 的模拟器
  （本机无 KVM，CI 为 API 35 ATD 模拟器），维持 `docs/audit/RENDERING_DESIGN.md`
  既有门禁记录，移交阶段 6 联调/发布前复验。
- **程序化贴图不可 JVM 测试**：`BallArt` 走 `android.graphics`（mockable
  android.jar 下抛 not mocked），球面图案/台布/木纹生成属绘制代码，保持
  设备/截图验证路径。
- **GL 资源生命周期**（`GlResourcePool` 按 EGL 上下文缓存、断连丢弃）与
  逐帧 GL 状态（blend/depth/cull 开关序列）属 GL 上下文行为，仅可由
  instrumentation 覆盖，本轮未新增。
- 既有死代码 2 处（`Mat4.normalMatrix`、`Mesh.drawLines` 无调用方），本轮
  保留未动，避免与渲染行为变更混入同一提交；建议后续清理轮处理。
