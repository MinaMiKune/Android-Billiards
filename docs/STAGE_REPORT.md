# 开发阶段报告

本报告记录每个阶段的实施内容、修改文件、技术决策、测试命令与结果、已知问题、
未完成任务和准入结论。测试结果来自本仓库当前工作区，不以计划代替结果。

## 阶段 1：工程与工具链

- 实施：建立 Gradle 多模块工程（`:core` 纯 Kotlin、`:app` Android），配置
  compileSdk/targetSdk 37、minSdk 26、Java 17、AGP 9.5.0、Kotlin 2.2.10、
  Gradle 9.8.2；加入 VIBRATE/GLES3/触摸屏声明、横竖屏和配置变化处理；建立主题、
  颜色、字符串和自适应图标。
- 修改文件：`settings.gradle.kts`、`build.gradle.kts`、`gradle.properties`、
  `core/build.gradle.kts`、`app/build.gradle.kts`、`app/src/main/AndroidManifest.xml`、
  `app/src/main/res/values/*`、`app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml`、
  `app/src/main/res/drawable/ic_launcher_foreground.xml`。
- 技术决策：不引入 Compose 或游戏引擎；UI 用原生 Canvas，渲染用 OpenGL ES 3.0；
  物理放在无 Android 依赖的 `core`，保证 JVM 可测。
- 测试命令：`./gradlew :app:assembleDebug`。
- 测试结果：成功生成最小 debug APK（857 KB 基线）。
- 已知问题：Activity 当时只有空 View。
- 未完成：全部玩法与界面。
- 准入结论：工具链可用，进入物理阶段。

## 阶段 2：物理引擎

- 实施：固定 480 Hz 步长；球体平移/四元数旋转/滑动与滚动摩擦/角速度衰减；
  球-球弹性碰撞与切向摩擦；库边反弹与旋转摩擦；杆法（高低杆）和左右塞；
  扫掠袋口捕获与袋口 jaw 碰撞；落袋/静止事件；球桌、球架和摆球校验。
- 修改文件：`core/src/main/kotlin/com/minamikune/billiards/physics/Vec.kt`、
  `Ball.kt`、`PhysicsWorld.kt`、`TableSpace.kt`；测试
  `core/src/test/kotlin/.../physics/PhysicsWorldTest.kt`、`TableSpaceTest.kt`。
- 技术决策：固定时间步长与 `previousPosition` 扫掠；速度/角速度同时参与接触点
  速度；不通过位置插值伪造运动；坐标映射集中到 `TableSpace` 并由 JVM 测试。
- 测试命令：`./gradlew :core:test`。
- 测试结果：14 个物理测试 + 5 个坐标/朝向测试全部通过；覆盖碰撞符号、侧旋、杆法、
  袋口、确定性、防穿透、摆球、四元数归一和物理→渲染朝向映射。
- 已知问题：完整开球到全部静止约需 20–27 秒；已在 `GameLoop` 对低速尾段做
  物理步进加速，但不改变碰撞计算。
- 未完成：规则层。
- 准入结论：物理测试稳定，进入规则/AI 阶段。

## 阶段 3：8 球规则与 AI

- 实施：练习/对战两种模式；开球、开放球台、分组、合法首碰、犯规、自由球、
  连续进球、8 号提前落袋判负、清组后 8 号合法落袋获胜；练习模式白球落袋自动
  重摆；几何 AI 选袋、切角、清障、安全球、确定性误差；AI 回合延迟。
- 修改文件：`core/src/main/kotlin/.../game/MatchEngine.kt`；
  `core/src/test/kotlin/.../game/MatchEngineTest.kt`。
- 技术决策：规则与物理分层；`MatchEngine` 只通过 `PhysicsWorld` 公共 API 推进；
  AI 在 `core` 内实现，便于 JVM 测试；快照用于 UI 和存档。
- 测试命令：`./gradlew :core:test`。
- 测试结果：`MatchEngineTest` 9 个测试通过；总计 27 个 core 测试 0 失败。
- 已知问题：AI 仅几何规划，不做多杆走位；难度靠瞄准误差，不含难度选择。
- 未完成：应用层与渲染。
- 准入结论：规则测试通过，进入应用/渲染阶段。

## 阶段 4：OpenGL 渲染与资源

- 实施：OpenGL ES 3.0 渲染器；球桌台呢/木框/库边/桌腿；球体号码/花色纹理；
  Lambert + Blinn-Phong、Fresnel 环境反射、动态点光、接触阴影；球杆三段；
  瞄准虚线、幽灵球、接触圈；落袋下坠缩小动画；横竖屏相机适配；自动画质降级。
- 修改文件：`app/src/main/kotlin/.../gl/Mat4.kt`、`GlUtil.kt`、`Meshes.kt`、
  `BallArt.kt`、`TableRenderer.kt`；`app/src/main/res/raw/*.wav` 由脚本合成。
- 技术决策：纹理全部程序化生成，避免大体积位图；相机按旋转后的桌面半尺寸二分
  适配；触摸通过 `TableSpace` 反投影到物理平面；GL 线程只读引擎快照。
- 测试命令：`./gradlew :app:assembleDebug`；`glslangValidator` 校验 8 个内嵌 shader。
- 测试结果：APK 构建成功（1.3 MB）；8 个 shader 全部通过 GLSL ES 3.0 校验。
- 已知问题：未做真机 GPU 性能剖析；低端设备画质降级未实机验证。
- 未完成：HUD、菜单、生命周期和存档接线。
- 准入结论：渲染可编译且 shader 合法，进入 UI/应用层阶段。

## 阶段 5：UI、生命周期、音频、触觉和存档

- 实施：GameHudView（记分、力度、杆法圆盘、击球/暂停/加速、自由球）；
  MenuView（主菜单、设置、玩法、暂停、结算）；MainActivity（GLSurfaceView +
  HUD + Menu、沉浸式、安全区、返回键/预测性返回、配置变化）；GameLoop 独立线程；
  SoundBank；Haptics；Prefs；SaveStore 自动存档/继续。
- 修改文件：`app/src/main/kotlin/.../ui/GameHudView.kt`、`MenuView.kt`、
  `MainActivity.kt`、`game/GameLoop.kt`、`audio/SoundBank.kt`、
  `haptics/Haptics.kt`、`data/Prefs.kt`、`data/SaveStore.kt`。
- 技术决策：不引入 AppCompat/Compose；用 `Activity` + Canvas 控制包体和启动路径；
  模拟线程通过命令队列接收 UI 操作，HUD 只读快照；稳定局面自动存档；
  后台/暂停时保存；触觉调用放主线程并 try/catch 降级。
- 测试命令：`./gradlew :app:testDebugUnitTest`、
  `./gradlew :app:compileDebugAndroidTestKotlin`。
- 测试结果：2 个 GameLoop 测试通过；仪器测试编译通过；APK 构建成功。
- 已知问题：无设备，未执行 `connectedDebugAndroidTest`；Canvas 布局仅静态审查
  和多分辨率绘制测试（编译级）。
- 未完成：真机验证、CI 和文档。
- 准入结论：应用层可构建，进入加固阶段。

## 阶段 6：加固与测试

- 实施：修复竖屏触摸/瞄准 180° 偏差；修复瞄准线不随球桌旋转和缓冲区越界；
  修复白球纹理由运算符优先级导致的红点错位；修复练习模式白球落点被占后无法
  继续；修复开球 8 号落袋错误清除犯规；AI 自由球改用全桌网格兜底；GameLoop
  线程身份和停止幂等；EGL 上下文重建时清理 uniform 缓存；复用瞄准线缓冲区；
  修复球体四元数从物理坐标 (x,y,z-up) 到渲染坐标 (x,y-up,z) 的轴置换，
  避免滚动时绕错轴自转。
- 修改文件：`core/.../game/MatchEngine.kt`、`core/.../physics/TableSpace.kt`、
  `app/.../gl/TableRenderer.kt`、`app/.../gl/BallArt.kt`、`app/.../game/GameLoop.kt`、
  `core/src/test/.../MatchEngineTest.kt`、`app/src/test/.../GameLoopTest.kt`、
  `app/src/androidTest/.../ui/MenuRenderTest.kt`、`app/src/androidTest/.../MainActivityTest.kt`。
- 技术决策：坐标映射进 core 单测；瞄准线 shader 加 `uModel`；所有大十六进制颜色
  显式 `toInt()`；低内存设备按 `ActivityManager` 选择画质。
- 测试命令：`./gradlew :core:test :app:testDebugUnitTest :app:compileDebugAndroidTestKotlin`。
- 测试结果：core 32/32、app 2/2 通过；androidTest 编译通过。
- 已知问题：仪器测试未在设备执行；无 Android 17 真机兼容性结论。
- 未完成：CI、最终 APK 复核。
- 准入结论：静态与 JVM 测试通过，进入交付阶段。

## 阶段 7：CI、文档与交付

- 实施：GitHub Actions 两阶段流水线（单元测试 + debug/release APK；API 35
  模拟器仪器测试）；tag 发布 debug APK；README 和本报告。
- 修改文件：`.github/workflows/android.yml`、`README.md`、`docs/STAGE_REPORT.md`。
- 技术决策：CI 安装 Android 37 平台和 Build-Tools 37.0.0；仪器测试用 API 35
  x86_64 模拟器；release 构建验证 R8，但不把签名密钥放进仓库，因此 tag 发布
  可安装的 debug APK。
- 测试命令：`./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug
  :app:assembleRelease`。
- 测试结果：core 32/32、app 2/2 通过；debug APK 约 1.30 MiB，release APK 约 398 KiB；
  lint 0 errors、1 warning（`mipmap-anydpi-v26` 的 ObsoleteSdkInt 冗余目录）。
  仪器测试 APK 编译通过，未在设备执行。
- 已知问题：CI 尚未在 GitHub 实际运行；本地无 Android 设备/模拟器。
- 未完成：真实设备验收、性能剖析、在线多人。
- 准入结论：代码、文档和 CI 配置完成；设备验收仍是后续工作。

## 最终验证

最终验证命令（无设备环境）：

```bash
export GRADLE_USER_HOME="$PWD/.gradle-home"
export ANDROID_USER_HOME="$PWD/.android-home"
./gradlew :core:test :app:testDebugUnitTest :app:assembleDebug \
  :app:assembleRelease :app:lintDebug
```

实际结果（2026-11-06，本机 Debian 13）：

- `:core:test`：32 tests，0 failures，0 errors。
- `:app:testDebugUnitTest`：2 tests，0 failures，0 errors。
- `:app:assembleDebug`：成功，`app/build/outputs/apk/debug/app-debug.apk`，1,367,103 bytes。
- `:app:assembleRelease`：成功（R8 + shrinkResources），
  `app/build/outputs/apk/release/app-release-unsigned.apk`，407,794 bytes。
- `:app:lintDebug`：0 errors、1 warning（`mipmap-anydpi-v26` 冗余，见上）。
- 仪器测试仅完成编译；本机没有 adb 设备、KVM 或 system image，因此不声称已执行
  `connectedDebugAndroidTest`，也不声称完成 Android 17 真机认证。
