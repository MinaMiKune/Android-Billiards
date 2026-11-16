# 阶段 5 验收报告：触控、音效与触觉

- 验收日期：2026-11-16
- 代码基线：`34da9c5`（阶段 4 渲染验收基线）
- 前置条件：**阶段 4 已 PASS**（见
  [`STAGE4_RENDERING_ACCEPTANCE.md`](STAGE4_RENDERING_ACCEPTANCE.md)；
  本轮开始时复跑 `:core:test` + `:app:testDebugUnitTest` 101/101 全绿确认后动工）
- 验收环境：JDK 21.0.13、Gradle 9.8.2、AGP 9.5.0、Kotlin 2.2.10、
  compileSdk/targetSdk 37、minSdk 26，本机 Debian 13 x86_64
- 本轮性质：按工程基线补强序列，对既有手机交互实现
  （`GameHudView` 触控 / `SoundBank` 音效 / `Haptics` 触觉 / `MenuView` 菜单触控）
  做系统验收；核心工作是把触控手势状态机、HUD 布局、音效曲线、触觉计划与
  节流闸门提取为可在 JVM 上验证的 Android-free 纯 Kotlin（行为不变的重构
  为主），并修复重构过程中暴露的 3 个真实触控缺陷与 2 个次级缺陷；
  **不改变对外回调契约**（`onAimPoint` / `onPlaceCue` / `onShoot` /
  `onPause` / `onPower` / `onSpin` / `onFastForward` / `onUiTap` 签名不变，
  `MainActivity` 接线零改动）。

> 编号说明：`docs/STAGE_REPORT.md` 中的历史“阶段”记录与本补强序列并存，
> 不改写历史数据（同阶段 4 报告声明）。

## 1. 验收结论

**通过。** 155 个 JVM 测试全绿（core 47 + app 108，其中本轮新增 54 个），
`assembleDebug` / `assembleRelease` 成功，`lintDebug` 0 error（2 个既有
informational 提示，均与本轮无关），`compileDebugAndroidTestKotlin` 通过。
本轮修复 3 个真实触控缺陷 + 2 个次级缺陷，全部有确定性回归用例。

| 验收项 | 证据 | 结论 |
|---|---|---|
| 阶段 4 基线未回退 | 动工前复跑 101/101 全绿；重构后 101 个既有用例保持全绿（core 47 + app 54） | 通过 |
| HUD 布局几何（`HudLayout`） | `HudLayoutTest` 10 用例：力度条/暂停/击球/塞杆/加速按钮全部避开安全区且在屏内；`powerTouch` 外扩命中区；击球/塞杆最小触控尺寸钳制（≥38dp）；击球命中 1.15× slop 内外边界；瞄准顶部守卫 y = safeTop + 112dp；2400×1080 竖屏与 1080×2400 横屏两极端下布局成立 | 通过 |
| 触控状态机（`HudTouchController`） | `HudTouchControllerTest` 17 用例：命中顺序（pause→ff→shoot→power→spin→table）、pause 任意位置松手即发、shoot 释放时按最新快照 `canShoot` 判定、自由球落点按释放时状态判定、指针归属（修 复 1）、力度条相对拖动不跳变（修复 2）、击球滑出取消（修复 3）、spin 向量归一化与 [-0.5,0.5] 缩放、null 映射容错 | 通过 |
| 音效曲线（`SoundCues`） | `SoundCuesTest` 9 用例：轻/重球分界 1.35、音量/音高速率曲线与钳制（确认低端钳制界不可达，属原实现死界，行为不变）、库边/落袋/犯规/击球/胜负/UI 各自曲线与冷却 28–900ms | 通过 |
| 触觉计划（`HapticPatterns`） | `HapticPatternsTest` 11 用例：球碰 <0.28 静默、轻/重分界 1.2（7ms/15ms）、库边 <0.35 静默、foul/win/lose 波形逐拍一致、幅值随 `hapticStrength` 缩放并钳制 1–255、`DEFAULT_AMPLITUDE = -1` 契约 | 通过 |
| 节流闸门（`CooldownGate`） | `CooldownGateTest` 7 用例：首次必放行、冷却窗内丢弃、边界恰好放行、各 key 独立节流、失败请求不推迟窗口、时钟回拨不阻塞、`clear()` 复位（修复 4） | 通过 |
| 真实缺陷修复 | 修复 1/2/3 为触控行为缺陷；修复 4（CooldownGate 首事件）与修复 5（spin 负零）为次级缺陷，均有确定性复现 + 回归用例（§3） | 通过 |
| 构建产物 | debug APK 构建成功；release APK 构建成功；lint 0 error（2 个既有提示：Gradle 新版本、mipmap-v26 目录）；androidTest Kotlin 编译通过 | 通过 |

## 2. 行为不变的重构（为可测试性）

触控、音效、触觉原本全部内嵌在 Android View / SoundPool / Vibrator 回调中
（`GameHudView` 584 行里 `onTouchEvent` + `layoutControls` 与全部绘制耦合），
app 单测使用 mockable android.jar、无 Robolectric，无法在 JVM 上验证。本轮做
五处纯提取，逐行保持行为：

1. **`ui/HudLayout.kt` 新增**：`FRect`（`RectF` 的最小纯 Kotlin 等价物）+
   `computeHudLayout(w, h, safeL, safeT, safeR, safeB, density)`，把
   `GameHudView.layoutControls` 的全部常量与公式原样搬出为纯函数。
   `GameHudView.layoutControls` 改为调用它并把结果拷贝进绘制用的
   `RectF`/`PointF` 字段（单一事实来源，绘制与触控共用同一布局）。
2. **`ui/HudTouchController.kt` 新增**：指针级触控状态机。View 把
   pointer-scoped 事件（`onDown/onMove/onUp(pointerId, x, y)` +
   `onCancel()`）喂入，控制器返回 `List<HudEffect>`（
   `PowerChanged` / `SpinChanged` / `AimAt` / `PlaceCue` / `Shoot` / `Pause` /
   `FastForward` / `UiTap`），View 只做效果分发。原有语义全部保留：命中顺序
   pause→ff→shoot→power→spin→table；pause/ff 松手任意位置即发；shoot 按压时
   `UiTap`、释放时按最新快照 `canShoot`；自由球拖动只预览、释放按当时快照落
   点；spin 拖拽立即生效并归一化到单位圆。`GameHudView.setSnapshot` 的引擎
   同步门控改读 `controller.draggingPower/draggingSpin`（语义与旧
   `dragPower/dragSpin` 一致），死字段 `localAimPoint` 删除。
3. **`util/CooldownGate.kt` 新增**：可注入时钟的 per-key 节流闸门，语义 =
   首次放行、冷却窗内丢弃。`SoundBank.playRateLimited` 与 `Haptics.throttled`
   两处重复实现（以 SoundPool id / 事件名为 key）统一委托到它，key 改用
   `SoundKind.name` / 计划 key（一一对应，无行为变化）。
4. **`audio/SoundCues.kt` 新增**：`PhysicsEvent → SoundCue(kind, volume, rate,
   cooldownMs)` 的映射，公式逐行来自 `SoundBank.play`；`SoundBank` 只保留
   SoundPool 加载与 `pool.play`，音量仍经 `coerceIn(0,1)`。
5. **`haptics/HapticPatterns.kt` 新增**：`PhysicsEvent → HapticPlan(key,
   effect, cooldownMs)`（`HapticEffect.OneShot/Waveform`，
   `DEFAULT_AMPLITUDE = -1` 与平台契约一致），幅值公式逐行来自
   `Haptics.amp()`；`Haptics` 只做 `VibrationEffect` 解析与 best-effort 调用，
   `uiTap` 的 `performHapticFeedback(KEYBOARD_TAP)` 路径不变。
6. **`MenuView.onTouchEvent` 最小修复**（见 §3 修复 1 的菜单等价项）：增加
   主指针归属 `activePointerId`，次指按下忽略、主指离开（POINTER_UP）即中止
   手势，次指不再能替主指“完成”按钮点击。

重构验证：提取后全量 JVM 套件（含阶段 1–4 全部 101 个既有用例）保持全绿；
`MainActivity` 与 `GameLoop` 零改动。

## 3. 本轮修复的真实缺陷

按工程基线补强序列的既定模式，每个缺陷均给出确定性复现 → 根因 → 修复 →
回归测试四步。

### 修复 1（主要）：多指抬起互相撕毁手势 —— HUD 与菜单

- **现象**：HUD 中一指瞄准 + 一指调力度的双指操作，任意一指抬起即全部失效；
  菜单中第二根手指抬起会让主指按住的按钮“自动点击”。
- **根因**：`GameHudView.onTouchEvent` 与 `MenuView.onTouchEvent` 只按
  `actionMasked` 分支处理，任何指针的 `ACTION_UP` 都会清掉全部
  `dragAim/dragPower/dragSpin/pressedId`（单标志布尔，无指针归属）。
- **修复**：`HudTouchController` 内 aim/power/spin 拖拽与 pause/ff/shoot 按压
  均按 `pointerId` 归属；`ACTION_POINTER_UP` 只结束对应指针的手势。
  `MenuView` 增加 `activePointerId`：次指按下不建手势、主指 `POINTER_UP`
  中止手势并清除按压态。
- **回归**：`releasingOneFingerDoesNotDestroyTheOtherFingerGesture`、
  `secondaryPointerCannotHijackAnOwnedControl`、
  `cancelResetsAllPointersAndPresses`。

### 修复 2（主要）：力度条抓取跳变

- **现象**：手指按住力度条上远离当前旋钮的位置时，力度瞬间跳到抓取点
  （0.55 → 0.2 一类的突变），手感像“滑条咬手”。
- **根因**：旧 `updatePower` 直接以按下点的 y 反算力度。
- **修复**：按下时记录 `powerOffset = 当前力度 − 抓取点原始力度`，拖动按
  相对位移更新（手势语义与 Android `ScrollView` 类拖动一致），并继续钳制在
  [0.05, 1]。
- **回归**：`grabbingBarAwayFromKnobDoesNotSnapPower`、
  `powerDragClampsToSliderRange`。

### 修复 3（主要）：击球按钮滑出仍触发击球

- **现象**：按住“击球”后手指滑出按钮再抬起，仍会出杆。
- **根因**：旧代码只在 `ACTION_UP` 判断 `pressedShoot && canShoot`，从不检查
  按压期间手指是否已离开命中区。
- **修复**：`onMove` 中该指针一旦滑出 1.15× 命中圈即解除按压（与 Android
  可点击控件的手指追踪语义一致）；滑回圈内会重新武装（命中圈判定天然支持）。
- **回归**：`shootDisarmsWhenFingerSlidesOffButton`、
  `shootStillFiresWhenFingerStaysOnButton`。

### 修复 4（次级）：CooldownGate 首事件被吞

- **现象/根因**：提取闸门时复刻了原实现 `last ?: 0L` 的写法——当注入时钟
  起点接近 0 时，key 无记录的首次请求被误判为“距上次 0ms”而丢弃。真实设备
  的 `nanoTime()/1e6` 极大，原实现不会触发；但该语义是潜伏缺陷（任何换时钟
  或冷启动模拟都会踩中），顺手修复：无记录 ⇒ 放行。
- **回归**：`firstAcquisitionAlwaysSucceeds`、
  `acquisitionsInsideCooldownAreDropped`、`clockAdvancingBackwardsNeverBlocks`。

### 修复 5（次级）：spin 负零泄漏

- **现象/根因**：塞杆圆盘正上/正右拖动时 `-0f` 经 `toDouble() * 0.5` 变成
  `-0.0` 传给引擎；数值无害但无谓污染快照比较。修复：归一化后 `+ 0f`。
- **回归**：`spinDragNormalizesAndScalesToEngineRange`。

## 4. 测试与验证

- 新增 5 个测试套件共 54 个用例：
  - `ui/HudLayoutTest.kt` — 10 用例（布局不变量、slop、极端纵横比）。
  - `ui/HudTouchControllerTest.kt` — 17 用例（命中顺序、指针归属、三处缺陷
    回归、自由球释放语义、spin 归一化）。
  - `audio/SoundCuesTest.kt` — 9 用例（全部事件类型的曲线/冷却契约）。
  - `haptics/HapticPatternsTest.kt` — 11 用例（阈值、波形、幅值钳制）。
  - `util/CooldownGateTest.kt` — 7 用例（节流语义、注入时钟）。
- 全量回归：`:core:test :app:testDebugUnitTest --rerun-tasks`
  → **155 tests, 0 failures, 0 errors, 0 skipped**
  （core 47 + app 108 = 既有 101 + 本轮 54）。
- `:app:assembleDebug` / `:app:assembleRelease` → BUILD SUCCESSFUL。
- `:app:lintDebug` → 0 error；2 个既有 informational（Gradle 9.9.1 可升级、
  `mipmap-anydpi-v26` 目录在 minSdk 26 下冗余），与本轮改动无关，保持基线
  原状。
- `:app:compileDebugAndroidTestKotlin` → 通过。
- 提交：`c1c5b6c feat(stage5): touch, audio & haptics acceptance baseline`
  （14 文件，+1350/−226）。

## 5. 残留风险与后续建议

- 触控/音效/触觉的最终手感（力度曲线斜率、冷却窗口、振动时长）属主观调参，
  本轮以“行为不变 + 缺陷修复”为验收边界；如需调参，`SoundCues` /
  `HapticPatterns` 的纯函数签名保证调参可单测回归。
- 尚未在真机/模拟器做输入注入（`adb shell input` / Espresso）级别的端到端
  验证；`GameHudView` 的 View 壳层（事件拆分、效果分发）目前靠编译期 +
  手动走查保证，androidTest 源集已可编译，后续可补 Espresso 用例。
- `GameHudView` 绘制部分（Canvas/pulse 动画）不在本轮验收范围（与阶段 4
  渲染验收互补）。
