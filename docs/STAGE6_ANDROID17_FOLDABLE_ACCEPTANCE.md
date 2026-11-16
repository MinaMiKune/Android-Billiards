# 阶段 6 验收报告：Android 17 与折叠屏

- 验收日期：2026-11-16
- 代码基线：`9aeda9b`（阶段 5 验收报告提交）
- 前置条件：**阶段 5 已 PASS**（见
  [`STAGE5_TOUCH_AUDIO_HAPTICS_ACCEPTANCE.md`](STAGE5_TOUCH_AUDIO_HAPTICS_ACCEPTANCE.md)；
  本轮开始时复跑 `:core:test` + `:app:testDebugUnitTest` 155/155 全绿确认后动工）
- 验收环境：JDK 21.0.13、Gradle 9.8.2、AGP 9.5.0、Kotlin 2.2.10、
  compileSdk/targetSdk 37、minSdk 26，本机 Debian 13 x86_64
- 本轮性质：按工程基线补强序列处理**不同设备形态**——把 2026-11-06 产品决定
  搁置的折叠屏专项（roadmap P1.2）落地为可交付实现，并关闭 Android 17 兼容
  审计（`docs/audit/DEVICE_COMPATIBILITY.md`）残留项。核心约束：**手机基线
  行为逐字不变**——所有新增能力均以「可选参数 / 可空 guide / 默认全窗视口」
  形式接入，`FLAT`（无折痕/未半开）姿态下几何公式与阶段 5 浮点恒等。

> 编号说明：`docs/STAGE_REPORT.md` 中的历史“阶段”记录与本补强序列并存，
> 不改写历史数据（同阶段 4/5 报告声明）。

## 1. 验收结论

**通过。** 172 个 JVM 测试全绿（core 47 + app 125，其中本轮新增 17 个），
`assembleDebug` / `assembleRelease` 成功，`lint` 0 error / 0 warning，
`assembleDebugAndroidTest`（androidTest APK 编译）成功，联网补齐依赖后
**离线全量复跑成功**（构建可复现）。折叠姿态（桌面/tabletop、书本/book）
有确定性 JVM 几何用例 + androidTest 真实绘制路径用例；手机基线有
「FLAT guide 与无 guide 布局逐字段相等」回归用例锁定。

| 验收项 | 证据 | 结论 |
|---|---|---|
| 阶段 5 基线未回退 | 动工前复跑 155/155 全绿；重构后既有 155 用例保持全绿 | 通过 |
| 折叠布局几何（`computeFoldLayout`） | `FoldLayoutTest` 8 用例：无铰链/全平/退化窗口→FLAT 全窗；横向半开→TABLETOP（上台面区/下控制区/折痕 keep-clear 带，数值精确断言）；竖向半开→BOOK（全窗 + 竖直 keep-clear 带）；任一侧 < 35% 可用高度/宽度时回退 FLAT | 通过 |
| HUD 折叠布局（`computeHudLayout` 第 8 参） | `HudLayoutTest` 新增 6 用例：FLAT guide 与 `null` 布局逐字段相等（基线锁定）；TABLETOP 全部控件落在控制区内且避开折痕带、触控半径 ≥38dp 下限保持；BOOK 所有控件被推离折痕带至较宽侧且不出屏 | 通过 |
| 渲染投影视口（`RenderMath`） | `RenderMathTest` 新增 3 用例：默认视口 ≡ 显式全窗视口（双向映射逐位一致，行为不变锁定）；受限视口（1600×752）内网格往返投影/反投影收敛；视口外/退化视口触点返回 `null` 而非 NaN | 通过 |
| 渲染器/菜单/Activity 接线 | `compileDebugKotlin` + 全量构建通过；`MenuRenderTest` 新增 tabletop 姿态真实绘制用例（HUD + MAIN/PAUSE/RESULT 菜单在 1600×1600 半开表面走完整 measure/layout/draw 路径） | 通过 |
| 设备形态渲染矩阵 | `MenuRenderTest.sizes()` 覆盖 1080×2400（手机）、2400×1080（横屏手机）、1600×2560（平板）、900×1600（小折叠内屏）、**1812×2176（大折叠内屏，新增）**、**1280×720（桌面自由窗口，新增）** | 通过 |
| Android 17 合规关闭项 | F2 `enableOnBackInvokedCallback="true"` 在 manifest 中已存在（本轮核对确认）；全工程 `grep` 无反射 / `setAccessible` / `getDeclaredField` / 隐藏 API 使用（唯一命中为 `Haptics.kt` 正常 `SDK_INT` 版本门）；API 37 大屏强制项（方向/opt-out 删除）此前审计已确认合规 | 通过 |
| 依赖卫生 | 新增 `androidx.window:window:1.3.0`（折叠姿态）+ `kotlinx-coroutines-android:1.8.1`（BOM 对齐 androidx.test 带入的 1.8.1 约束，消除双版本解析）；release R8 + shrinkResources 构建通过 | 通过 |
| 构建产物 | debug/release APK、lint（0 error/0 warning）、androidTest APK；联网补齐缓存后离线全量 `BUILD SUCCESSFUL` | 通过 |

## 2. Android 17 残留项关闭

`docs/audit/DEVICE_COMPATIBILITY.md` 记录 F1–F5。本轮核对结论：

- **F1（API 28–29 刘海安全区）**：阶段 4 前已由
  `platform/SafeInsets.merge(systemBars, cutout)` 修复，本轮回归覆盖（155 基线
  用例中含 cutout 合并用例）。
- **F2（manifest `enableOnBackInvokedCallback`）**：本轮 grep 确认
  `AndroidManifest.xml` 第 21 行已声明 `"true"`，**关闭**。
- **隐藏 API / 反射改写**：对 `app/src/main` 全量 grep
  `java.lang.reflect|setAccessible|getDeclaredField|hiddenApi`，零命中；
  无任何 static-final 字段改写或非 SDK 接口调用，Android 17 严格模式无触发点。
- **大屏强制行为（API 37，sw>600dp 设备删除方向与可调整大小 opt-out）**：
  manifest 为 `fullSensor` + `resizeableActivity="true"` +
  `configChanges=orientation|screenSize|screenLayout|smallestScreenSize|
  keyboardHidden|uiMode|density`，无宽高比锁定，此前审计已判合规，本轮不变。

## 3. 折叠屏实现

### 3.1 姿态模型（纯 Kotlin，JVM 可测）

- `platform/Geometry.kt`：**`FRect`** 自 `ui/HudLayout.kt` 迁入（消除
  platform→ui 反向依赖，`platform` 包保持 Android-free）。原定义无其他直接
  引用方，迁移零行为变化。
- `platform/FoldLayout.kt`：
  - `FoldOrientation { HORIZONTAL, VERTICAL }`、`FoldPosture { FLAT, TABLETOP,
    BOOK }`；
  - `FoldHinge(orientation, center, thickness, halfOpened)`；
  - `FoldLayoutGuide(posture, tableViewport, controlZone, avoidZones)` +
    `companion flat(w,h)`；
  - `computeFoldLayout(windowW, windowH, hinge, marginPx)`：无铰链/未半开/
    非正窗口 → FLAT；横向半开 → TABLETOP（`tableViewport` = 折痕带上侧
    减 margin，`controlZone` = 下侧加 margin，`avoidZones` = 折痕带本身）；
    竖向半开 → BOOK（视口/控制区全窗，竖直折痕带 keep-clear）。任一侧可用
    尺寸 < 35% 窗口（`MIN_USABLE_FRACTION`，覆盖铰链贴边/几乎合拢的退化
    姿态）回退 FLAT，宁可全窗也不产生不可用碎片。
- `platform/FoldPostureTracker.kt`：`WindowInfoTracker.windowLayoutInfo`
  收集首个 `FoldingFeature`，`HALF_OPENED` 时按方向构造 `FoldHinge`，与
  窗口尺寸（经 `OnLayoutChangeListener` 主动上报，自由窗口/多窗口尺寸
  变化即重算）合成 guide 回调。`SupervisorJob + Dispatchers.Main.immediate`
  作用域，`stop()` 幂等；guide 存放 `@Volatile currentGuide` 供随时取用。
  runCatching 包裹收集器——不支持 WindowManager 扩展的旧设备上协程静默
  结束，姿态保持 FLAT，功能无损降级。

### 3.2 接线（UI 线程 → GL 线程单向）

- `MainActivity`：`FoldPostureTracker` 在 onCreate 建立；回调
  `applyFoldGuide` 扇出 `hud.setFoldLayout` / `menu.setFoldLayout`（UI 线程），
  并经 `surface.queueEvent { renderer.onFoldLayoutChanged() }` 切到 GL 线程
  更新相机——渲染器内部状态（proj/view/逆矩阵）只在 GL 线程写，与
  `onSurfaceChanged` 的锁序一致。`installSurface()` 重建 GLSurfaceView 时
  重推当前 guide（**修复渲染器重建后丢失折叠姿态的接线隐患**）。
  onDestroy 取消监听器并 `stop()` tracker。
- `TableRenderer`：相机段从 `onSurfaceChanged` 提取为 `updateCamera()`
  （锁内重算），TABLETOP 时用上台面区宽高比取景；`onDrawFrame` 先全幅
  clear 再 `glViewport + glScissor` 限幅绘制、绘制后恢复，全屏清屏无残影；
  `screenToTable/tableToScreen` 视口感知——控制区内的触控不再映射到球台。
- `GameHudView.setFoldLayout` / `MenuView.setFoldLayout`：存 guide 后重算
  布局并重绘。TABLETOP 时：力度条锚定控制区顶右，击球/塞杆分居控制区底
  右/左，快进底部居中，折痕带两侧无任何控件跨越；菜单主屏与暂停/结算/
  设置/帮助面板整体上移到上台面区（`contentArea()`），返回按钮保持窗相对。
  BOOK 时：全部控件按外接矩形推离竖直折痕带至较宽一侧，尺寸不变。
- `RenderMath.screenToTable/tableToScreen` 增加可选视口四参（默认
  `0,0,width,height`，旧调用点编译与行为不变）。

### 3.3 触控一致性核对

`HudTouchController.classify` 命中顺序 pause→ff→shoot→power→spin→table，
其中 ff 仅 `SIMULATING` 命中、shoot 仅 `AIMING` 命中——两者状态互斥，
TABLETOP 下 ff（底中）与 shoot（底右）即使几何相邻也无冲突；power 与
shoot 在控制区上下分层，无重叠。单指针归属、力度相对拖拽、滑出取消等
阶段 5 语义不受布局来源影响（触控只读 `hudLayout` 终值）。

## 4. 设计决策记录

1. **取消独立 WindowFormFactor 尺寸分类器**：原计划按 Material 窗口尺寸档
   （COMPACT/MEDIUM/EXPANDED）建分类器。精读确认阶段 1–5 布局/相机全部
   连续分辨率自适应（`computeHudLayout`、`fitCamera`、`MenuView` 均按 px
   连续公式），无尺寸档分支可挂；运行时行为完全由折叠 guide 驱动。设备
   形态覆盖改为用 androidTest 渲染矩阵（§1 表格）强制，不引入无消费者的
   分类代码。
2. **FLAT 恒等性作为硬约束**：`computeHudLayout` 保留原公式于
   `baselineHudLayout` 逐字不变，FLAT/null 直返；`HudLayoutTest.
   flatGuideReproducesBaselineLayoutExactly` 逐字段断言相等，防止后续维护
   漂移。
3. **35% 可用回退**：半开姿态若把窗口切成不可用碎片（铰链贴边、接近
   合拢），回退全窗 FLAT 布局——与阶段 1–5 手机/平板行为一致，优于展示
   半个按钮的坏布局。
4. **依赖版本对齐**：androidx.test 传递的 coroutines BOM 约束 1.8.1 与初版
   显式声明 1.7.3 冲突，导致 androidTest 编译路径解析 1.8.1 失败（debug
   路径因缓存 1.7.3 而掩盖）。显式声明对齐 1.8.1，单版本全路径一致。

## 5. 测试与验证

- **JVM（offline）**：`./gradlew :core:test :app:testDebugUnitTest --offline`
  → **172 tests, 0 failures, 0 errors**（core 47 + app 125）。新增 17：
  `platform/FoldLayoutTest` 8、`ui/HudLayoutTest` +6、`gl/RenderMathTest` +3。
  过程中 2 个新用例首跑失败，均为**测试断言方向错误**（BOOK 折痕带 margin
  应加在铰链带外侧；TABLETOP 控件位于折痕下方应断言 `top ≥ crease.bottom`），
  实现经复核正确，修正断言后全绿——未为迁就测试改实现。
- **构建（offline 复跑）**：`:app:assembleDebug :app:assembleRelease
  :app:lint :app:assembleDebugAndroidTest --offline` → BUILD SUCCESSFUL；
  lint 0 error / 0 warning。release 走 R8 + shrinkResources，折叠新代码
  无反射、无 keep 需求。
- **androidTest**：`assembleDebugAndroidTest` 编译通过；CI
  （`.github/workflows/android.yml`）在 API 35 模拟器执行全部
  instrumentation，尺寸矩阵含新增 1812×2176 与 1280×720。
- **渲染冒烟**：本地无模拟器，折叠姿态绘制由 `MenuRenderTest.
  hudAndMenusDrawInTabletopFoldPosture`（真实 Canvas 路径，非快照）+ JVM
  几何用例覆盖；GL 限幅绘制路径经代码审查（clear 全幅→scissor 限幅→恢复）
  与既有 `TableRendererTest` 不变量保证。

## 6. 残留风险与后续建议

1. **真机验证**：FoldingFeature 姿态流转（展开→半开→合拢）与
   `windowLayoutInfo` 时序建议在 Pixel Fold / 华为 Mate X 系列真机各过一遍；
   模拟器无铰链硬件，JVM 用例覆盖几何而非系统回调时序。
2. **桌面/自由窗口**：1280×720 已入渲染矩阵；多窗口拖拽缩放时相机按
   连续宽高比重取景，无需额外分支，但建议 ChromeOS 设备抽查一次。
3. **window-core**：`androidx.window:window:1.3.0` 附带
   `window-extensions-core` 运行时探测，R8 已验证无 keep 需求；若未来
   升级 window 版本需复跑 release 构建确认。
4. **P1.2 状态**：roadmap 中折叠屏搁置项由本轮实现关闭，建议同步更新
   `docs/audit/DEVELOPMENT_ROADMAP.md`（本轮未改动该审计文档，保持审计
   原文可追溯）。
