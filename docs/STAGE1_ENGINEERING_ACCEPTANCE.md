# 阶段 1 验收报告：工程骨架与基础架构

- 验收日期：2026-11-06
- 代码基线：`a82e75e`（阶段 0 技术方案与既有游戏主体）
- 本轮性质：在已验收的 Android 17 工程上补齐基础设施、生命周期与资源边界，不重写
  已稳定的物理/规则/渲染实现。
- 验收环境：JDK 21.0.13、Gradle 9.8.2、AGP 9.5.0、Kotlin 2.2.10、
  compileSdk/targetSdk 37、Build-Tools 37.0.0、minSdk 26。

> 编号说明：`docs/STAGE_REPORT.md` 中的“阶段 1–7”是历史开发顺序；本轮“阶段 1”
> 指工程骨架与基础架构验收。两者并存，不合并、不改写历史数据。

## 1. 验收结论

**本地工程/构建/单元测试：通过。设备启动与仪器测试：有条件通过（环境无设备）。**

| 验收项 | 证据 | 结论 |
|---|---|---|
| Gradle 构建成功 | `:app:assembleDebug :app:assembleRelease` 成功 | 通过 |
| APK 成功生成 | debug 1,375,284 B；release unsigned 416,989 B | 通过 |
| 游戏可以启动 | Manifest 注册 `BilliardsApplication` + `MainActivity`；`LaunchTest` 编译；本机无设备，未执行真机启动 | 待设备/CI |
| 主菜单可以正常使用 | 既有 Canvas 主菜单/设置/帮助/暂停/结算；菜单渲染测试已编译 | 代码/构建通过，待设备执行 |
| 生命周期正常 | Application/Activity 重建、暂停、恢复、前后台、实例状态恢复实现 + 重建测试已编译 | 代码/构建通过，待设备执行 |
| 游戏循环正常 | 独立 `billiards-sim` 线程，480 Hz 固定步长，3 个 GameLoop JVM 测试通过 | 通过 |
| 资源加载与释放 | `ResourceManager` 5 个 JVM 测试通过；音频/字体/GL 池明确所有权与释放路径 | 通过 |
| 自动化测试可执行 | core 32 + app 12 JVM 测试全绿；9 个仪器测试编译通过；lint 0 error | 本地 JVM/构建通过 |

设备项没有用“计划”或历史 CI 结果替代本地结果，详见第 8 节。

## 2. 任务一：工程结构

保留阶段 0 已批准的两级 Gradle 结构，并按职责拆分 `app` 内部包。没有为了形式
拆出十个 Gradle 模块，因为那会增加构建与依赖管理成本，却不改变运行时边界；这符合
“可根据实际工程框架调整，但必须保持模块职责清晰”的约束。

```text
:app   Android 应用入口与平台适配
  platform/    Application 容器、运行配置、Activity 级资源
  resources/   统一键值资源注册表、字体集
  gl/          GLES3 渲染、程序/网格/纹理池、网格释放
  game/        固定步长游戏循环、模式与快照类型
  audio/       SoundPool 音效
  haptics/     振动反馈
  data/        设置与存档
  ui/          Splash / Menu / HUD，全部 Canvas 绘制
:core  纯 Kotlin JVM
  physics/     向量、球体、球桌、固定步长物理
  game/        8 球规则、练习规则、AI、MatchSnapshot
```

`core` 不依赖 Android，可直接在 JVM 上测试。渲染只读取快照；UI 通过
`GameLoop.submit` 提交命令，不直接修改物理对象。

## 3. 任务二：Android 生命周期

| 场景 | 实现 |
|---|---|
| Application 初始化 | `BilliardsApplication.onCreate()` 创建进程级 `AppContainer`（Prefs、SaveStore、GameConfig） |
| Activity 生命周期 | 单 Activity；`onCreate` 创建 `ActivityResources`、视图与 GLSurfaceView |
| 游戏启动 | `MenuView.Action.PRACTICE/VS_AI/CONTINUE` → `startGame`；先恢复快照再启动循环 |
| 游戏暂停 | `GameLoop.pause()`；`onPause` 保存稳定快照、弹出暂停菜单、`surface.onPause()` |
| 游戏恢复 | `onResume` 恢复 surface；重建后恢复的对局以暂停态打开，不自动运行 |
| 后台切换 | 循环在后台不推进；音频/触觉由开关控制；切回后由用户继续 |
| 屏幕休眠 | 仅在“前台 + 对局中 + 无菜单 + 启动页结束”时持有 `FLAG_KEEP_SCREEN_ON` |
| 资源释放 | `onDestroy` 停止并 join 模拟线程、移除主线程回调、`ActivityResources.close()` 释放 SoundPool/字体 |
| 状态保存/恢复 | `onSaveInstanceState` 写入版本化 JSON 快照和菜单页；重建时解码并 `startPaused=true` |
| Activity 重建 | Manifest 的 `configChanges` 覆盖旋转/折叠/尺寸变化；进程重建走实例状态，进程死亡回退 SharedPreferences 存档 |
| 返回键 | API 33+ 使用 `OnBackInvokedCallback`；旧版本走 `onBackPressed`；启动页期间返回直接结束启动页 |

关键文件：

- `app/src/main/kotlin/com/minamikune/billiards/BilliardsApplication.kt`
- `app/src/main/kotlin/com/minamikune/billiards/platform/AppContainer.kt`
- `app/src/main/kotlin/com/minamikune/billiards/MainActivity.kt`
- `app/src/main/kotlin/com/minamikune/billiards/data/SaveStore.kt`

## 4. 任务三：游戏主循环

`app/src/main/kotlin/com/minamikune/billiards/game/GameLoop.kt` 使用独立线程，职责
分离如下：

| 阶段 | 机制 |
|---|---|
| 输入更新 | 所有外部操作进入 `ConcurrentLinkedQueue<(MatchEngine) -> Unit>`，在模拟线程串行执行 |
| 物理更新 | 固定 `1/480 s` 步长；累加器 clamp 到 `0.05 s`；单帧最多 140 步，防止后台恢复时“补帧爆炸” |
| 渲染更新 | GLSurfaceView 连续模式，由系统 vsync/Choreographer 调度，不硬编码 60 FPS；渲染线程只读快照 |
| 音频事件 | 每个物理步排空 `PhysicsEvent`，由 UI 线程投递 SoundPool/触觉，避免模拟线程触碰视图 |
| UI 更新 | 每 0.075 s（由 `game_config.properties` 可调，范围 0.033–0.5 s）发布不可变快照 |
| 暂停/恢复 | `pause()`/`resume()` 幂等；`stop()` 中断并 join，带 400 ms 上限 |
| 快进 | `boost()` 用于已静止判断后的尾段加速，不改变物理步长 |

配置值：

```properties
loop.snapshotIntervalSeconds=0.075
```

JVM 测试覆盖快照发布、停止幂等、跨线程命令以及暂停/恢复后线程仍存活。

## 5. 任务四：基础 UI

| 界面 | 实现 |
|---|---|
| 启动界面 | 新增 `SplashView`，品牌标记、标题、进度线；默认最短 850 ms + 220 ms 淡出，可由配置调整 |
| 主菜单 | 练习、AI 对战、继续、设置、帮助 |
| 游戏入口 | 主菜单动作驱动 `startGame`，首次进入与恢复存档共用路径 |
| 设置界面 | 声音、触觉、画质档位；沿用既有 `Prefs` |
| 暂停界面 | 继续、重新开始、回主菜单；后台/重建后自动呈现 |
| 结算界面 | 胜负结果与再来一局/回主菜单 |

启动页是覆盖在 GLSurfaceView 上的普通 `View`，不是第二个 Activity，因此不会引入
额外的窗口切换和生命周期分叉。它结束时通过 `post` 移除自身，避免在
`dispatchDraw` 过程中修改 View 树。

## 6. 任务五：统一资源管理

| 资源 | 所有者 | 加载 | 释放 |
|---|---|---|---|
| 运行配置 | `AppContainer`（进程级） | `assets/game_config.properties`，非法值 clamp/回退默认 | 进程结束 |
| 设置/存档 | `AppContainer`（进程级） | `Prefs`、`SaveStore` | 进程结束 |
| 音效 | `ActivityResources` | `SoundBank` 9 个 WAV | `ResourceManager.close()` → `SoundBank.release()` |
| 字体 | `ActivityResources` | `FontSet` | 随 Activity 资源注册表关闭 |
| GL 程序/网格/纹理 | `TableRenderer` + `GlResourcePool` | 每个 EGL 上下文按 key 懒加载一次 | EGL 上下文销毁；`GlResourcePool.release()` 提供当前上下文内的显式清理 |
| 着色器 | `GlResourcePool.program` | 4 个 GLES3 程序，按 key 缓存 | 同上 |
| 模型 | `GlResourcePool.mesh` | 球/低模球/盒/面/盘/环/杆/quad，按 key 缓存 | `Mesh.release()` 删除 VBO/VAO |
| 动态瞄准线 | `TableRenderer` | 单个动态 VBO/VAO | 随 EGL 上下文销毁 |

`ResourceManager` 的关键约束：

- 同 key 只加载一次；加载器抛异常时不留半成品缓存。
- `release(key)` 只关闭一次；`close()` 逆序关闭且幂等。
- 关闭后继续 `get` 会抛异常，避免使用已释放资源。
- `SoundBank` 的流数量由配置限制在 4–24，防止无界音频资源。

## 7. 任务六：测试与构建

### 7.1 本地已执行

```bash
export GRADLE_USER_HOME="$PWD/.gradle-home"
export ANDROID_USER_HOME="$PWD/.android-home"

./gradlew :core:test :app:testDebugUnitTest --rerun-tasks
./gradlew :app:assembleDebug :app:assembleRelease :app:lintDebug
./gradlew :app:compileDebugAndroidTestKotlin
```

结果：

| 套件 | 数量 | 结果 |
|---|---:|---|
| `:core:test` | 32 | 32 passed / 0 failed |
| `:app:testDebugUnitTest` | 12 | 12 passed / 0 failed |
| `androidTest` 编译 | 9 | `compileDebugAndroidTestKotlin` 成功 |
| `lintDebug` | — | 0 errors / 1 warning（既有 `ObsoleteSdkInt`） |
| debug APK | — | 1,375,284 B |
| release unsigned APK | — | 416,989 B |

JVM 测试分布：

- `core`：物理 14、坐标 5、规则/AI 13。
- `app`：GameLoop 3、GameConfig 4、ResourceManager 5。

新增仪器测试：

- `LaunchTest`：Application 类型、配置版本、音频流与快照间隔边界。
- `MainActivityTest`：启动、Activity 重建、对局重建后必须停在暂停页。
- `SaveStoreTest`：完整快照 JSON 往返、损坏数据返回 null。
- `MenuRenderTest`：新增 Splash 在 4 种尺寸下的真实 Canvas 绘制。

### 7.2 构建产物

```text
app/build/outputs/apk/debug/app-debug.apk
  SHA-256 e87edc9b49953066e9fc779cf8a6d693f893c552ea945ac4b78ddb57006b785a
app/build/outputs/apk/release/app-release-unsigned.apk
  SHA-256 92ca33b43360a40fa64176ec8e973f63f5665f49a02854e28635a902222c33eb
```

APK 静态校验（`aapt2 dump badging`）：

- package `com.minamikune.billiards.debug`，minSdk 26，targetSdk 37，
  platformBuild 37。
- `BilliardsApplication` 已注册；`enableOnBackInvokedCallback=true`。
- `assets/game_config.properties` 已打包。
- OpenGL ES 3.0 与触摸屏为必需特性，MainActivity 可启动。

### 7.3 未在本地执行

本机 `/opt/android-sdk` 没有 emulator、system image 和 `/dev/kvm`，
`adb devices` 为空，因此 `connectedDebugAndroidTest` 无法本地执行。仓库的
GitHub Actions `build` 任务已加入 `:app:lintDebug` 与
`:app:compileDebugAndroidTestKotlin`，`instrumentation` 任务会在 API 35 ATD
x86_64 模拟器上执行 9 个仪器测试；本报告不把历史 CI 结果当作本轮本地结果。

## 8. 风险与后续

1. **设备启动/菜单/生命周期验收未本地执行**：需要在 CI 或有设备环境运行
   `./gradlew :app:connectedDebugAndroidTest`，并做一次真机冷启动、后台切换、
   屏幕休眠/唤醒检查。
2. **Release 未签名**：符合阶段 0 决策（签名密钥不入库），发布前需接入外部密钥。
3. **GL 资源释放依赖 EGL 上下文销毁**：这是 GLSurfaceView 的标准生命周期；
   `GlResourcePool.release()` 可用于未来在 GL 线程内显式清理，但不应在上下文销毁后调用。
4. **仅中文 UI**：`resourceConfigurations` 已预留 zh/en，英文文案不属于本阶段。
5. **Lint 唯一警告**：`mipmap-anydpi-v26` 的 `ObsoleteSdkInt` 是既有资源目录问题；
   尝试合并到 `mipmap-anydpi` 会导致 AAPT2 找不到自适应图标，故保留并记录。

## 9. 本阶段主要变更文件

- 新增：`BilliardsApplication.kt`、`platform/AppContainer.kt`、`platform/GameConfig.kt`、
  `platform/ActivityResources.kt`、`resources/ResourceManager.kt`、`resources/FontSet.kt`、
  `ui/SplashView.kt`、`gl/GlResourcePool.kt`、`assets/game_config.properties`、
  `res/values/ids.xml`。
- 修改：`MainActivity.kt`、`SaveStore.kt`、`GameLoop.kt`、`SoundBank.kt`、
  `TableRenderer.kt`、`GlUtil.kt`、`MenuView.kt`、`GameHudView.kt`、
  `AndroidManifest.xml`。
- 测试：`ResourceManagerTest.kt`、`GameConfigTest.kt`、`GameLoopTest.kt`、
  `LaunchTest.kt`、`MainActivityTest.kt`、`SaveStoreTest.kt`、`MenuRenderTest.kt`。
