# 设备与 API 兼容性（阶段 0）

- 基线：`compileSdk 37` / `targetSdk 37` / `minSdk 26`
- 结论：**API 兼容性设计合理；用户真机运行与触觉已通过；折叠屏专项按产品决定搁置；性能尚未量化。**

> 2026-11-06 用户补充：真机安装运行正常、触觉正常；无折叠屏设备，折叠屏专项适配搁置。未提供机型/Android 版本、帧率与发热数据，因此本文不把“真机可运行”写成“Android 17 性能认证”。
- 本文把“已知事实”“代码现状”“待验证项”分开写，避免把推断当成实测。

---

## 1. 版本策略

| 项 | 值 | 说明 |
|---|---|---|
| `compileSdk` | 37 | Android 17 |
| `targetSdk` | 37 | 直接对齐 Android 17 |
| `minSdk` | 26 | Android 8.0 |
| Build Tools | 37.0.0 | |
| AGP | 9.5.0 | 内置 Kotlin 支持，app 模块不再单独应用 kotlin-android |
| Gradle | 9.8.2 | wrapper |
| JDK | 21 | 构建；字节码目标 Java 17 |
| NDK | 无 | 纯 Kotlin/Java，无 native 代码 |

### 1.1 为什么 minSdk 26

- `VibrationEffect` / `Vibrator.hasAmplitudeControl()` 从 API 26 开始；本项目触觉分级依赖它。
- 自适应图标（`mipmap-anydpi-v26`）从 26 开始。
- OpenGL ES 3.0 从 API 18 就可用；26 不是图形限制。
- 26 以下需要额外的触觉降级和图标兼容，收益很小。

### 1.2 为什么 target 37

- 应用商店的 targetSdk 要求逐年提高；直接对齐 37 可避免阶段 1 再迁移。
- Android 17 对大屏方向/可调整大小的限制，本项目**本来就不依赖 opt-out**：
  - `screenOrientation="fullSensor"`：不锁定方向；
  - `resizeableActivity="true"`；
  - 没有 `minAspectRatio` / `maxAspectRatio`；
  - 横竖屏都布局，`onConfigurationChanged` 不重建。
- 代价：target 37 的新行为（内存限制、静态 final、后台音频等）必须逐项确认；见第 3 节。

---

## 2. Android 17 关键行为与影响

### 2.1 大屏方向/可调整大小（最高优先级）

官方博客（2026-02-13）原文：

> "Android 17 (API level 37) removes the developer opt-out for orientation and resizability restrictions on large screen devices (sw > 600 dp). When you target API level 37, your app must be capable of adapting to a variety of display sizes."

在 `sw > 600 dp` 的大屏上，target 37 应用以下设置会被忽略：

- manifest `screenOrientation` 的 `portrait`/`reversePortrait`/`sensorPortrait`/`userPortrait`/`landscape`/`reverseLandscape`/`sensorLandscape`/`userLandscape`；
- `setRequestedOrientation()` 的同上值；
- `resizeableActivity`（所有值）；
- `minAspectRatio` / `maxAspectRatio`（所有值）。

例外：

- 屏幕小于 `sw 600 dp`；
- 应用通过 `android:appCategory` 声明为 **game** 的游戏。

本应用现状：

| 检查项 | 现状 | 判定 |
|---|---|---|
| 是否锁定方向 | 否，`fullSensor` | 合规 |
| 是否固定宽高比 | 否 | 合规 |
| 是否禁用 resize | 否，`resizeableActivity=true` | 合规 |
| 是否处理配置变化 | 是，`configChanges` + `onConfigurationChanged` | 合规 |
| 是否声明 `appCategory` | 否 | **不需要**（因为没有依赖豁免） |
| 大屏是否可用 | 相机自动取景 + Canvas 安全区 | 设计合规，待真机验证 |

结论：**不依赖 game 豁免也能通过 Android 17 大屏要求**。如果未来想锁定手机方向，才需要 `android:appCategory="game"` 并重新评估。

### 2.2 其他 target 37 行为

| 行为 | 本应用影响 | 结论 |
|---|---|---|
| 无锁 `MessageQueue`（反射私有字段可能失效） | 全仓库无 `java.lang.reflect` / `Class.forName` | 无影响 |
| `static final` 字段不可修改（反射/JNI） | 无反射、无 JNI、无 native | 无影响 |
| RemoteViews 位图内存上限 | 无 AppWidget/RemoteViews | 无影响 |
| `ACCESS_LOCAL_NETWORK` 运行时权限 | 无网络权限/无 socket | 无影响 |
| ECH 默认开启 | 无网络 | 无影响 |
| 后台音频加固 | 无 Service、无后台音频、无 MediaSession | 无影响 |
| 应用内存上限（MemoryLimiter） | 纯 Java/Kotlin；峰值主要是启动期 Bitmap + 15 MB GPU 纹理 | 需关注低端机 |
| 16 KB page size | APK 无 `.so`、无 `lib/` | 无影响 |
| 预测性返回 | 已注册 `OnBackInvokedCallback`（API 33+），但 **manifest 未设 `enableOnBackInvokedCallback`** | 建议补 |

### 2.3 预测性返回（建议修复）

现状：

```kotlin
if (Build.VERSION.SDK_INT >= 33) {
    onBackInvokedDispatcher.registerOnBackInvokedCallback(
        OnBackInvokedDispatcher.PRIORITY_DEFAULT,
    ) { handleBack() }
}
```

但 `AndroidManifest.xml` 没有：

```xml
<application android:enableOnBackInvokedCallback="true" ...>
```

影响：

- 行为上仍有 `onBackPressed()` 兜底，返回功能不会坏；
- 但 target 33+ 的系统预测性返回动画/调度可能不会走新回调；
- Android 16/17 对预测性返回的默认策略继续收紧，建议在阶段 1 补上 manifest 标志并回归测试。

### 2.4 边缘到边缘（edge-to-edge）

- Android 15（target 35）起大屏强制 edge-to-edge；Android 17 继续。
- 本应用 `setDecorFitsSystemWindows(false)`（API 30+），并用 `WindowInsets.Type.systemBars() or displayCutout()` 取安全区。
- API 26–29 用 `SYSTEM_UI_FLAG_LAYOUT_*` + `systemWindowInset*` 兜底。
- API 29+ 关闭状态栏/导航栏对比度强制；API 28+ 设置 `LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES`。
- HUD/菜单都接收 `setSafeInsets(l,t,r,b)`；GL 台面通过相机 0.90/0.80 NDC 边距避开边缘。

---

## 3. API 26–37 兼容分层

| 能力 | 26–27 | 28 | 29 | 30–32 | 33–36 | 37 |
|---|---|---|---|---|---|---|
| 沉浸式 | `systemUiVisibility` | 同左 | 同左 | `WindowInsetsController` | 同左 | 同左 |
| 刘海 | — | `shortEdges` | 同左 | `displayCutout()` inset | 同左 | 同左 |
| 系统栏对比度 | — | — | 关闭 | 同左 | 同左 | 同左 |
| 返回 | `onBackPressed` | 同左 | 同左 | 同左 | `OnBackInvokedCallback` | 同左（建议 manifest 开启） |
| 触觉 | `Vibrator` + `VibrationEffect` | 同左 | 同左 | 同左 | `VibratorManager`（31+） | 同左 |
| GLES | 3.0 | 3.0 | 3.0 | 3.0 | 3.0 | 3.0 |
| 大屏方向限制 | 不适用 | 不适用 | 不适用 | 不适用 | 有 opt-out | **无 opt-out** |

### 3.1 API 28–29 刘海风险

API 30 以下只读 `systemWindowInset*`，没有单独读 `displayCutout.safeInset*`：

- 竖屏时状态栏 inset 通常覆盖刘海高度；
- **横屏**时刘海在短边（左/右），而系统栏隐藏后 `systemWindowInsetLeft/Right` 可能为 0；
- HUD 的安全区可能不足，按钮/文字可能贴到刘海。

影响面：Android 9/10 的刘海机型，横屏。当前 `minSdk 26` 覆盖这些设备。

建议（阶段 1）：

```kotlin
if (Build.VERSION.SDK_INT >= 28) {
    val cut = insets.displayCutout
    l = max(l, cut?.safeInsetLeft ?: 0)
    r = max(r, cut?.safeInsetRight ?: 0)
    t = max(t, cut?.safeInsetTop ?: 0)
    b = max(b, cut?.safeInsetBottom ?: 0)
}
```

**已修复（2026-11-11，阶段 1 后续）**：真机实测复现——异形屏顶端记分信息被刘海遮挡。
根因即本节预测：沉浸式隐藏系统栏后刘海侧 inset 为 0，HUD 顶栏按 `safeT=0`
绘制进了刘海区。修复：

- 新增纯 Kotlin `platform/SafeInsets.kt`（`SafeInsets.merge` 分量取最大值），
  API 28+ 两个 SDK 分支统一把 `DisplayCutout.safeInset*` 并入安全区；
- `MainActivity` insets 监听器改用该合并结果喂给 `GameHudView`/`MenuView`；
- 新增 JVM 单测 `SafeInsetsTest` 4 个（null 刘海直通、沉浸式 0 inset 由刘海补齐、
  分量取最大、横屏左侧刘海保留）；
- `:app:testDebugUnitTest` 16/16、`:core:test` 32/32、`lintDebug` 0 error 通过。

待用户同机型复测确认后关闭。

### 3.2 手势导航

- 透明导航栏 + `BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE`；从边缘滑动会临时显示系统栏。
- HUD 底部按钮需要额外避开手势区；当前用 `systemBars` bottom inset，通常足够。
- 三键导航设备底部 inset 更大，布局已按 inset 调整。

---

## 4. 横竖屏

### 4.1 现状

| 项 | 值 |
|---|---|
| manifest | `screenOrientation="fullSensor"` |
| 支持方向 | 竖屏、横屏、反向竖屏、反向横屏（传感器决定） |
| 配置变化 | 不重建 Activity（`configChanges` 包含 orientation/screenSize/screenLayout/smallestScreenSize/density 等） |
| 渲染 | `portrait = height > width`；竖屏桌面绕 Y 旋转 90°；相机重新取景 |
| UI | `MenuView`/`GameHudView` 按 `width > height` 选择布局 |

### 4.2 评估

- **手机**：横竖屏都可玩；竖屏台面纵向铺满，横屏横向铺满。
- **大屏/折叠**：Android 17 会忽略 `fullSensor` 的锁定语义（本来也没锁），窗口可能以任意比例出现；相机二分取景可适配。
- **反向竖屏**：`fullSensor` 允许 180° 翻转；UI 没有反向专用布局，但 Canvas 按宽高布局，理论可用；**未测试**。
- **固定方向需求**：如果产品要求手机只竖屏，需要改为 `portrait`；但在大屏上会被忽略。当前“全方向自适应”是更稳的策略。

### 4.3 建议测试

1. 手机竖屏/横屏/反向竖屏各打一局；
2. 折叠屏展开→合上→再展开，观察是否重建、相机是否跳变；
3. 分屏 50/50、70/30；
4. 自由窗口拖动改变宽高；
5. 刘海机横屏（API 28/29）。

---

## 5. 折叠屏

### 5.1 现状

- 没有使用 `WindowLayoutInfo` / `FoldingFeature` / `Jetpack WindowManager`。
- 没有铰链避让、没有双栏布局、没有桌面模式（tabletop）特殊处理。
- 依赖系统把窗口 resize + `onConfigurationChanged` + `onSurfaceChanged`。

### 5.2 实际表现（设计推演）

| 场景 | 预期 |
|---|---|
| 展开内屏（接近平板） | 应用全屏；相机拉远；台面完整；HUD 按安全区布局 |
| 合上外屏 | 窗口变小；相机拉近；不重建 Activity；不丢局 |
| 半折叠 tabletop | 应用仍占整个窗口（或系统给的单侧窗口）；没有铰链分割；台面可能跨过折痕 |
| 分屏/自由窗口 | 同上，按窗口尺寸重新取景 |

### 5.3 风险

| 风险 | 等级 | 说明 | 缓解 |
|---|---|---|---|
| 折痕穿过球桌 | 低 | 物理折痕，不是显示缺口；不影响触控 | 可接受；如需可把台面偏置到一侧 |
| 铰链导致窗口分割 | 中 | 某些厂商在 tabletop 模式只给半屏 | 相机自适应已覆盖；需真机确认 |
| 展开/合上时 GL 上下文丢失 | 中 | 配置变化可能触发 surface 重建 | `onSurfaceCreated` 已全量重建 |
| HUD 按钮跨折痕 | 低 | 安全区只避系统栏，不避铰链 | 可后续接 `FoldingFeature` |
| 大屏比例导致台面过小 | 低 | 相机按 NDC 0.90/0.80 取景，台面始终最大 | 可接受 |

### 5.4 结论

**产品决定（2026-11-06）：无折叠屏设备，折叠屏专项适配搁置，不作为阶段 1 门禁。** 现有实现仍保留基础自适应（窗口 resize + insets + 相机重取景），但不承诺 tabletop/铰链避让/双栏。若未来恢复该需求，再按 5.2/5.3 验证并引入 `WindowManager`。

---

## 6. 触觉反馈

### 6.1 实现分层

```text
Haptics(context, prefs)
  ├─ API 31+: VibratorManager.defaultVibrator
  ├─ API 26–30: VIBRATOR_SERVICE as Vibrator
  ├─ hasVibrator() / hasAmplitudeControl() 探测（try/catch）
  ├─ 事件 → 振幅/时长/波形 + 每键冷却
  └─ 所有调用 try/catch；无马达/无权限时静默
```

### 6.2 事件映射

| 事件 | 触发条件 | 时长/波形 | 振幅 | 冷却 |
|---|---|---|---|---:|
| `CueStruck` | 击球 | 11 ms | `80 + speed/9×150` | 35 ms |
| `BallHit` 轻 | 0.28–1.2 m/s | 7 ms | `45 + s×30` | 45 ms |
| `BallHit` 重 | >1.2 m/s | 15 ms | `115 + s×28` | 55 ms |
| `CushionHit` | ≥0.35 m/s | 9 ms | `55 + s×22` | 65 ms |
| `Pocketed` | 落袋 | 22 ms | 155 | 120 ms |
| `Foul` | 犯规 | `[0,22,38,30]` | `[0,140,0,95]` | 400 ms |
| `MatchWon` 胜 | 胜利 | `[0,26,55,26,55,70]` | `[0,210,0,170,0,220]` | 1000 ms |
| `MatchWon` 负 | 失败 | `[0,55,60,90]` | `[0,150,0,90]` | 1000 ms |
| UI 点击 | 按钮 | `KEYBOARD_TAP` | 系统默认 | 无 |

振幅再乘 `prefs.hapticStrength`（默认 0.8，范围 0.2–1.0），clamp 到 1–255。

### 6.3 兼容性

| 情况 | 行为 |
|---|---|
| 无马达 | `hasVibrator=false`，全部 no-op |
| 无振幅控制 | 用 `DEFAULT_AMPLITUDE` 的 one-shot；波形仍可用 |
| 系统关闭触觉/勿扰 | 系统可能不振动；应用不报错、不弹窗 |
| 厂商 ROM 异常 | 全部 `try/catch`，不影响游戏 |
| API 31+ | `VibratorManager`，正确处理多马达设备（默认马达） |
| 权限 | `VIBRATE` 是 normal 权限，安装即授予；无需运行时请求 |

### 6.4 真机结果

2026-11-06 用户真机实测：**触觉正常**（轻碰/重碰/落袋可感知，未报告异常）。这关闭了“单机可用性”，但不覆盖多厂商振幅曲线差异；多机型补测降级为 P2。

### 6.5 已知小问题

`Prefs.kt` 注释写“0.35 .. 1.0”，但 setter 和 UI 实际是 **0.2 .. 1.0**，默认 0.8。注释与实现不一致，属于文档/注释缺陷，不影响功能；阶段 1 修正注释。

### 6.6 待多机型验证

- Pixel / Samsung / 小米的振幅曲线差异；
- 连续开球时冷却是否足够（不会“麻手”）；
- 横屏握持时马达位置导致体感偏弱；
- 系统“触觉反馈”关闭时是否完全静默（预期是）。

---

## 7. 生命周期与窗口状态

| 事件 | 处理 | 风险 |
|---|---|---|
| `onCreate` | 建 View/引擎/GL/insets/返回回调 | 低 |
| `onResume` | 恢复 GL、沉浸式；对局中打开暂停菜单 | 低 |
| `onPause` | 停模拟、存档、显示暂停、暂停 GL | 低 |
| `onConfigurationChanged` | 重应用沉浸式/insets/invalidate，不重建 | 低 |
| `onDestroy` | 停线程、释放音频 | 低 |
| 进程死亡 | 仅恢复最近稳定局面 | 中（见 ARCHITECTURE） |
| 多窗口失去焦点 | 系统可能 `onPause`；回来显示暂停菜单 | 低 |
| 分屏 resize | `onConfigurationChanged` + `onSurfaceChanged` | 低 |
| 折叠姿态变化 | 同上 | 中（未真机） |
| 后台被杀 | 无 FGS；不稳定态丢失 | 可接受 |

没有 `onMultiWindowModeChanged` / `onPictureInPictureModeChanged` 处理；当前不需要画中画。

---

## 8. 测试矩阵

### 8.1 已有

| 层级 | 内容 | 结果 |
|---|---|---|
| JVM 单测 | 34 个（core 32 + app 2） | 全绿 |
| Lint | `lintDebug` | 0 error，1 warning（`ObsoleteSdkInt`，mipmap-anydpi-v26） |
| CI 构建 | API 37 SDK，JDK 21，debug/release | 绿 |
| CI 仪器 | API 35 ATD x86_64，4 个测试 | 绿（仅启动/绘制，不测 GL 帧率） |
| Shader | 8 个 GLSL ES 3.00 逐个 `glslangValidator` | 8/8 通过 |

### 8.2 缺口

| 缺口 | 影响 | 阶段 1 动作 |
|---|---|---|
| 真机已运行，但无性能数据 | 帧率/发热未知 | 同一台真机跑 `gfxinfo` + 10 分钟发热 |
| 无 API 37 模拟器 | CI 仍 API 35 | 增加 API 37 system image job（可用时） |
| 无折叠屏 | 铰链/展开未验证 | **产品决定搁置**，不阻塞；保留基础自适应 |
| 无 API 26–29 设备 | 旧兼容层未跑 | 至少 API 28 刘海机或模拟器 |
| 无 GL 帧率断言 | 可能低于 60 fps | 真机 `gfxinfo` + profiler |
| 无触觉多机型数据 | 厂商差异未知 | 单机已通过；P2 补 2–3 台 |

### 8.3 推荐设备矩阵

| 设备 | 用途 |
|---|---|
| 用户现有真机（型号/版本待补） | 已冒烟通过；待补性能数据 |
| Pixel Fold / Pixel Tablet | **搁置**；未来恢复折叠专项时使用 |
| Samsung Galaxy S 系列（One UI） | 厂商触觉/多窗口差异 |
| 低端 Android 8.0（API 26） | minSdk 兜底 |
| Android 9 刘海机（API 28） | 旧 inset 路径 |
| API 35/37 模拟器 | CI 回归（SwiftShader 只做功能） |

---

## 9. 结论

| 验收项 | 状态 |
|---|---|
| Android 17 SDK/API 要求 | 满足：compile/target 37，AGP 9.5/Gradle 9.8.2/JDK 21 |
| minSdk 策略 | 明确：26，理由充分 |
| targetSdk 策略 | 明确：37，且不依赖大屏 opt-out |
| 横竖屏 | 支持，自适应；反向竖屏未测 |
| 折叠屏 | 基础自适应保留；专项适配按产品决定搁置 |
| 触觉 | 分层/降级/冷却完整；用户真机实测正常 |
| 生命周期 | 完整；进程死亡只保证稳定局面 |
| 关键风险 | 无量化帧率、API 28–29 横屏刘海（代码已修，待同机型复测）、预测性返回 manifest、低端机内存 |

**结论：允许进入阶段 1。真机冒烟与触觉已通过；折叠屏专项按产品决定搁置；剩余硬门禁是“真机性能数据 + 签名发布 + API 37 CI”。在此之前不得宣称“Android 17 性能认证完成”。**
