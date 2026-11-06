# Break Point 桌球

一款从零实现的 Android 8 球桌球游戏，使用 Kotlin、原生 Android SDK 和
OpenGL ES 3.0。物理、规则、AI、渲染、Canvas UI、音频、触觉和存档均为真实实现，
没有用静态图片或假数据替代玩法。

## 功能

- **练习模式**：自由进球、白球落袋后自动重摆、没有犯规惩罚。
- **AI 对战 8 球**：开球、开放球台、分组、合法首碰、犯规、自由球、8 号球胜负判定。
  几何 AI 会寻找合法进球线路，并带有确定性难度误差。
- **完整操作**：拖动球台瞄准，右侧力度条调节力度，左下圆盘控制高低杆和左右塞，
  点击击球按钮出杆；自由球时直接拖动白球到合法位置。
- **表现**：程序化台呢/木纹/球面号码纹理，动态点光、高光、Fresnel 环境反射、
  接触阴影、瞄准虚线、幽灵球、球杆、落袋下坠动画和 UI 动画。
- **声音与触觉**：SoundPool 合成的击球/碰撞/库边/落袋/犯规/胜负音效；
  VibratorManager / Vibrator 按碰撞强度分级反馈，带开关和强度设置。
- **适配**：横屏、竖屏、折叠屏、平板和不同分辨率；响应式 Canvas 布局，
  刘海/系统栏安全区，配置变化不重建 Activity。
- **存档**：自动保存稳定局面；主菜单继续上一局。设置、统计独立保存。
- **画质降级**：自动/高画质/省电三档；低内存设备自动使用低面数网格和简化阴影。
- **启动与资源边界**：品牌启动页；Application 级配置/存档容器；Activity 级
  音频、字体与 GL 资源注册表；重建/进程死亡均不会丢失进行中的对局。

## 环境与构建

已验证工具链：

- JDK 21
- Android SDK Platform 37、Build-Tools 37.0.0
- Gradle 9.8.2（wrapper）
- AGP 9.5.0，Kotlin 2.2.10（JVM 模块）
- compileSdk / targetSdk 37，minSdk 26

```bash
export GRADLE_USER_HOME="$PWD/.gradle-home"
export ANDROID_USER_HOME="$PWD/.android-home"
./gradlew :core:test :app:testDebugUnitTest
./gradlew :app:assembleDebug :app:assembleRelease
```

运行配置位于 `app/src/main/assets/game_config.properties`：启动页时长、循环快照
间隔、高清内存阈值和音频流上限都在此集中管理，非法值会回退默认值。

APK 输出：

- `app/build/outputs/apk/debug/app-debug.apk`（约 1.31 MiB）
- `app/build/outputs/apk/release/app-release-unsigned.apk`（R8 构建，未签名，约 407 KiB）

`local.properties` 需要指向本机 Android SDK；该文件和构建目录已被 `.gitignore` 忽略。

## 测试

```bash
./gradlew :core:test
./gradlew :app:testDebugUnitTest
./gradlew :app:compileDebugAndroidTestKotlin
# 有设备/模拟器时：
./gradlew :app:connectedDebugAndroidTest
```

- `core`：32 个 JVM 测试，覆盖固定步长物理、球-球/库边/袋口、旋转与杆法、
  确定性、8 球规则（含开球、同杆清组、合法 8 号）、AI 合法性、摆球、存档快照、
  横竖屏坐标映射和球体旋转坐标系映射。
- `app`：12 个 JVM 测试。GameLoop 验证快照发布、停止幂等、跨线程命令和
  暂停/恢复；GameConfig 验证配置解析、裁剪与损坏回退；ResourceManager 验证
  单次加载、失败不留缓存、逆序关闭和关闭后拒绝访问。
- `androidTest`：9 个仪器测试，覆盖 Application 初始化、菜单/HUD/启动页在手机
  竖屏、横屏、平板、小折叠屏尺寸下的 Canvas 绘制、完整 Activity 启动、对局重建后
  停在暂停页，以及存档 JSON 往返和损坏数据回退。
- 本机没有 emulator/system image/KVM，仪器测试只完成编译；GitHub Actions 的
  API 35 ATD x86_64 任务负责执行。验收细节见
  [`docs/STAGE1_ENGINEERING_ACCEPTANCE.md`](docs/STAGE1_ENGINEERING_ACCEPTANCE.md)。

## 架构

```text
core/   纯 Kotlin，无 Android 依赖
  physics/  Vec2/Vec3/Quat、Ball、TableSpec、Rack、PhysicsWorld、TableSpace
  game/     MatchEngine（8 球规则 + 练习）、BilliardsAi
app/    Android
  platform/ Application 容器、GameConfig、Activity 级资源
  resources/ ResourceManager、FontSet
  gl/       TableRenderer、GlResourcePool、Mat4、GlUtil、Meshes、BallArt
  ui/       SplashView、GameHudView、MenuView（全部 Canvas 绘制）
  game/     GameLoop（固定 480 Hz 模拟线程）
  audio/    SoundBank
  haptics/  Haptics
  data/     Prefs、SaveStore
```

物理与渲染完全分离：`PhysicsWorld` 不引用 Android；`GameLoop` 在独立线程按
1/480 s 固定步长推进，渲染线程只读取 `synchronized(engine)` 的快照。击球通过
线程安全命令队列提交，触摸和 UI 不直接修改物理对象。

`BilliardsApplication` 只持有进程级 `AppContainer`；`MainActivity` 通过
`ActivityResources` 管理音频/字体，并在 `onDestroy` 幂等关闭。GL 程序、网格和
纹理由 `GlResourcePool` 按 EGL 上下文缓存；重建时丢弃失效 id，上下文销毁时由
EGL 回收。对局快照同时写入 `onSaveInstanceState` 和 `SaveStore`，Activity 重建
后以暂停态恢复，后台切换不会推进物理。

## 兼容性说明

- 清单声明 OpenGL ES 3.0 与触摸屏为必需特性；不支持 GLES3 的设备不会安装。
- 未申请网络权限，游戏不收集或上传数据。
- 当前只提供中文界面；`resourceConfigurations` 已限制为 zh/en，英文文案待补。
- Release APK 使用仓库外的签名密钥，因此 CI 只发布可安装的 debug APK；
  release 构建用于验证 R8/资源压缩。

## 已知限制

- 尚未在真实 Android 17 设备上运行；兼容性结论基于 compileSdk/targetSdk 37、构建和静态审查，
  不声称已完成真机认证。本机同样没有模拟器，9 个仪器测试只完成编译。
- AI 为几何规划，不含多杆走位搜索；难度通过瞄准误差和球速控制。
- 暂无在线多人、观战或规则自定义。
- 音效为程序化合成占位音色，不是采样录音。
