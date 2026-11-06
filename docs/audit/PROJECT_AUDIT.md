# 项目审计报告（阶段 0）

- 项目：Break Point 桌球（`com.minamikune.billiards`）
- 审计基线：`main` 分支，commit `6664c913ece0969f8a05156ee06b8a69cd25cdf4`（2026-11-06）
- 审计时间：2026-11-06
- 审计范围：仓库、构建配置、物理/规则/渲染/UI/音频/触觉/存档实现、测试与 CI
- 审计方式：只读代码审查 + JVM 最小物理探针 + 构建/测试/lint/Shader 编译验证
- 最终结论：**允许进入阶段 1（条件性通过）**。条件见第 9 节；阶段 1 的第一项必须是真机兼容性与性能门禁，不能先做功能扩张。

> 本文区分三类结论：**已验证**（本机可复现）、**静态验证**（代码/构建/Shader 静态检查通过）、**未验证**（缺少设备或环境）。未验证项不会被写成“通过”。

---

## 1. 审计结论摘要

| 维度 | 结论 | 证据强度 |
|---|---|---|
| 工具链可构建 | 通过 | 本机 `assembleDebug`、`assembleRelease`、unit test、lint 全部成功 |
| 单元测试 | 通过，34/34 | 本机 JUnit XML 复跑 |
| 仪器测试 | CI 通过，4/4 | GitHub Actions run `42154844717` 绿色；本地无设备，未复跑 |
| 物理引擎 | 通过最小验证 | 探针测量 + 14 个物理单测；自研方案可执行 |
| 渲染方案 | Shader/构建通过，帧率未验证 | 8/8 GLSL 通过 `glslangValidator`；无真机 GPU 数据 |
| Android 17 兼容 | 静态通过，真机未验证 | compile/target 37、行为变更逐项审查；CI 仅 API 35 |
| 折叠屏适配 | 基础自适应通过，铰链姿态未适配 | 多尺寸 Canvas 绘制测试；无折叠屏实机 |
| 触觉反馈 | 方案可执行，设备差异未验证 | API 26/31 分支 + 振幅能力探测 + 降级；无实机 |
| 存档/生命周期 | 静态通过 | 版本化 JSON、稳定局面保存、暂停保存；无进程死亡实测 |
| 发布工程 | 部分通过 | debug 可安装；release 未签名，tag 发布的是 debug APK |

**阻塞级问题：0 个。**
**高优先级缺口：3 个**——无 Android 17 真机、无 GPU 帧率数据、CI 未覆盖 API 37。

---

## 2. 仓库状态

```text
branch : main
HEAD   : 6664c91 docs: record green CI and emulator instrumentation run
remote : https://github.com/MinaMiKune/Android-Billiards.git
status : 审计开始时 clean；审计期间新增 docs/audit/，临时探针 AuditProbeTest.kt 已删除
```

审计开始时 `docs/` 只有 `STAGE_REPORT.md`，不存在 `docs/audit/`。本阶段新增 8 份审计文档和 1 份物理探针原始输出。

现有工程不是“待开发骨架”，而是已经完成阶段 1–7 的实现：8 球规则、AI、GLES 3.0 渲染、Canvas UI、音频、触觉、存档和 CI 都已存在。因此本审计的职责是**验证既有实现是否具备进入下一阶段的条件**，而不是从零设计。

---

## 3. 环境与工具链（已验证）

| 项目 | 实测值 | 证据 |
|---|---|---|
| OS | Debian 13（trixie），x86_64 | 本机 |
| JDK | 21.0.13 | `java -version` |
| Gradle | 9.8.2（wrapper） | `gradle/wrapper/gradle-wrapper.properties` |
| Android Gradle Plugin | 9.5.0 | 根 `build.gradle.kts` |
| Kotlin | 2.2.10 | 根 `build.gradle.kts`；`app` 使用 AGP 9 内置 Kotlin |
| compileSdk | 37 | `app/build.gradle.kts:7` |
| targetSdk | 37 | `app/build.gradle.kts:13` |
| minSdk | 26 | `app/build.gradle.kts:12` |
| Build-Tools | 37.0.0 | `app/build.gradle.kts:8` |
| Android SDK | `/opt/android-sdk`，platform `android-37.0` | `local.properties`、SDK 目录 |
| NDK | 未安装，也不需要 | 仓库无 `externalNativeBuild`、无 `.so` |
| 模拟器/KVM | 未安装，`/dev/kvm` 不存在 | 本机检查 |
| adb 设备 | 0 台 | `adb devices` |

`gradle.properties`：`-Xmx2048m`、parallel、build cache、AndroidX、non-transitive R class。没有 version catalog；所有版本直接写在构建脚本中。

---

## 4. 模块与代码规模

```text
:core  纯 Kotlin/JVM，无 Android 依赖
  physics/  Vec2/Vec3/Quat、Ball/TableSpec/Rack、PhysicsWorld、TableSpace
  game/     MatchEngine（8 球规则、练习模式、AI 调度）、BilliardsAi
:app   Android
  gl/       TableRenderer、Mat4、GlUtil/Mesh、BallArt、Meshes
  ui/       GameHudView、MenuView（全部 Canvas 绘制）
  game/     GameLoop（480 Hz 独立模拟线程）
  audio/    SoundBank（SoundPool）
  haptics/  Haptics（VibratorManager/Vibrator）
  data/     Prefs、SaveStore
```

| 文件 | 行数 | 职责 |
|---|---:|---|
| `app/src/main/kotlin/com/minamikune/billiards/MainActivity.kt` | 501 | 单 Activity、窗口/insets/生命周期、游戏流 |
| `app/src/main/kotlin/com/minamikune/billiards/game/GameLoop.kt` | 179 | 固定步长模拟线程、命令队列、快照发布 |
| `app/src/main/kotlin/com/minamikune/billiards/gl/TableRenderer.kt` | 878 | GLES 3.0 渲染、相机、触摸反投影、Shader |
| `app/src/main/kotlin/com/minamikune/billiards/gl/GlUtil.kt` | 273 | 程序编译、纹理、Mesh/VAO |
| `app/src/main/kotlin/com/minamikune/billiards/gl/Mat4.kt` | 186 | 矩阵/四元数变换 |
| `app/src/main/kotlin/com/minamikune/billiards/gl/BallArt.kt` | 202 | 程序化球面/台呢/木纹/阴影/地板纹理 |
| `app/src/main/kotlin/com/minamikune/billiards/ui/GameHudView.kt` | 579 | 力度、杆法、按钮、安全区 |
| `app/src/main/kotlin/com/minamikune/billiards/ui/MenuView.kt` | 913 | 主菜单/设置/帮助/暂停/结算 |
| `app/src/main/kotlin/com/minamikune/billiards/audio/SoundBank.kt` | 77 | 9 个 WAV、音量/音高/冷却 |
| `app/src/main/kotlin/com/minamikune/billiards/haptics/Haptics.kt` | 125 | 事件分级振动、振幅缩放、降级 |
| `app/src/main/kotlin/com/minamikune/billiards/data/SaveStore.kt` | 149 | 版本化 JSON 单局存档 |
| `app/src/main/kotlin/com/minamikune/billiards/data/Prefs.kt` | 33 | 设置与统计 |
| `core/.../physics/PhysicsWorld.kt` | 537 | 固定步长、碰撞、袋口、事件 |
| `core/.../physics/Ball.kt` | 225 | 球体状态、常量、球架、袋口定义 |
| `core/.../physics/TableSpace.kt` | 27 | 桌子坐标 ↔ 世界坐标 |
| `core/.../physics/Vec.kt` | 116 | Vec2/Vec3/Quat |
| `core/.../game/MatchEngine.kt` | 665 | 规则、AI 回合、快照/恢复 |

**分层优点**：`:core` 不依赖 Android，物理与规则可在 JVM 上快速、确定性地测试；`:app` 只负责渲染、输入、平台能力。这个分层是后续所有验证的基础，应保持。

---

## 5. 构建、测试与 CI 证据

### 5.1 本机复跑（已验证）

```bash
export GRADLE_USER_HOME="$PWD/.gradle-home"
export ANDROID_USER_HOME="$PWD/.android-home"
./gradlew :core:cleanTest :app:cleanTestDebugUnitTest \
  :core:test :app:testDebugUnitTest :app:assembleDebug :app:lintDebug
```

结果：`BUILD SUCCESSFUL`。

| 测试类 | 数量 | 结果 |
|---|---:|---|
| `PhysicsWorldTest` | 14 | 0 失败 |
| `TableSpaceTest` | 5 | 0 失败 |
| `MatchEngineTest` | 13 | 0 失败 |
| `GameLoopTest`（app） | 2 | 0 失败 |
| **合计** | **34** | **0 失败 / 0 错误 / 0 跳过** |

### 5.2 产物

| 产物 | 大小 | 说明 |
|---|---:|---|
| `app/build/outputs/apk/debug/app-debug.apk` | 1,367,239 B | 可安装；`aapt2` 确认 target 37、min 26、GLES 3.0 |
| `app/build/outputs/apk/release/app-release-unsigned.apk` | 408,810 B | R8 + 资源压缩；未签名 |
| `docs/audit/evidence/physics_probe_results.txt` | — | 本次物理探针原始输出 |

`aapt2 dump badging` 关键输出：

```text
package: name='com.minamikune.billiards.debug' versionCode='1'
minSdkVersion:'26'
targetSdkVersion:'37'
uses-gl-es: '0x30000'
uses-feature: name='android.hardware.touchscreen'
```

APK 内无 `lib/`、无 `.so`，因此不涉及 16 KB 页对齐原生库问题。

### 5.3 Lint

`app/build/reports/lint-results-debug.xml`：**0 errors，1 warning**。

- `ObsoleteSdkInt`：`res/mipmap-anydpi-v26/ic_launcher.xml`。属于可清理的冗余版本目录，不影响运行。

### 5.4 Shader 编译

从 `TableRenderer.kt` 提取 8 个内嵌 GLSL ES 3.00 源（`BALL_VERT/FRAG`、`TABLE_VERT/FRAG`、`SIMPLE_VERT`、`SHADOW_FRAG`、`LINE_VERT/FRAG`），逐个通过：

```bash
/root/dsh-project/.tools/root/usr/bin/glslangValidator -S vert|frag <shader>
```

结果：**8/8 exit 0**。这证明语法/版本合法，不证明设备驱动上的性能或兼容性。

### 5.5 CI（已验证远端记录）

GitHub Actions run `42154844717`：

- workflow：Android CI
- head：`55b97f4`
- 结论：success
- 时间：2026-11-06T03:09:59Z → 03:26:52Z
- `Instrumentation tests (API 35 ATD emulator)`：success（03:10:02 → 03:26:52Z）
- `Unit tests and APK`：success（03:10:02 → 03:11:13Z）
- artifacts：`billiards-instrumentation-results` 2,193,877 B；`billiards-release-mapping` 271,545 B；`billiards-debug-apk` 1,287,288 B

**证据限制**：未认证下载 artifact 返回 HTTP 401，因此没有重新读取 instrumentation XML；“4/4”由源码中 4 个 `@Test`（`LaunchTest` 1、`MainActivityTest` 1、`MenuRenderTest` 2）加绿色 job 推断，不能等同于逐条断言回读。

---

## 6. Manifest 与平台声明

| 声明 | 值 | 影响 |
|---|---|---|
| 权限 | `android.permission.VIBRATE` | 普通权限，无需运行时申请 |
| 必需特性 | `android.hardware.touchscreen` | 无触摸屏设备不安装 |
| 必需特性 | `android.hardware.opengles.version = 0x00030000` | 无 GLES 3.0 设备不安装 |
| Activity | `.MainActivity`，`exported=true`，`singleTask` | 单 Activity 架构 |
| 配置变化 | `orientation\|screenSize\|screenLayout\|smallestScreenSize\|keyboardHidden\|uiMode\|density` | 旋转/折叠/分屏不重建 Activity |
| 方向 | `fullSensor` | 不锁横竖屏 |
| 可调整大小 | `resizeableActivity=true` | 分屏/自由窗口/折叠屏可缩放 |
| `appCategory` | **未声明** | 不享受 Android 17 的游戏豁免；但应用本身已满足自适应要求 |
| 备份 | `allowBackup=true` | 存档可能随系统备份迁移；无敏感数据 |
| `largeHeap` | `false` | 依赖正常堆与 11 MB 级纹理预算 |

---

## 7. 代码审查发现

### 7.1 做得好的部分

1. **物理与平台解耦**：`PhysicsWorld` 无 Android 引用，固定 480 Hz，确定性可测。
2. **并发边界清楚**：UI 不直接改物理；`GameLoop` 命令队列 + `synchronized(engine)` 快照；渲染线程只读快照。
3. **生命周期有明确策略**：暂停保存、稳定局面自动保存、GL context 重建时清 uniform 缓存。
4. **资源体量小**：程序化纹理与几何，debug APK 1.30 MiB，release 399 KiB。
5. **CI 同时覆盖 JVM 测试、R8 构建和 API 35 仪器测试**，不是只跑编译。
6. **无网络权限、无反射、无 JNI/native 库**，Android 17 的两项高风险变更（`static final`、16 KB）天然规避。

### 7.2 已确认的缺口

| 编号 | 缺口 | 证据 | 影响 |
|---|---|---|---|
| A1 | 无 Android 17 真机/GPU 数据 | 本机无 adb 设备、无 KVM；CI 仅 API 35 软件渲染 | 帧率、发热、触觉、折叠屏无法签收 |
| A2 | CI instrumentation 不是 API 37 | workflow 使用 `google_atd` API 35 | 不能证明 API 37 运行时行为 |
| A3 | 无折叠屏铰链/姿态适配 | 无 `WindowLayoutInfo`/`FoldingFeature` 代码；仅靠窗口 resize + insets | 桌面模式/半折叠可能布局不理想，但不崩溃 |
| A4 | Canvas UI 可访问性弱 | 自绘 View 无内容描述/虚拟节点 | TalkBack 用户难以操作；上架无障碍评级风险 |
| A5 | 文档数值缺少条件 | `docs/STAGE_REPORT.md` 称开球静止需 20–27 s；本次探针 8 m/s 为 5.04 s、12 m/s 为 4.60 s（力度/碰撞次数不同） | 易被误读为固定值；应注明条件或改为区间 |
| A6 | release 分发链路不完整 | release 未签名；tag job 上传 debug APK | 不能作为正式商店/签名包发布 |
| A7 | 仅中文 UI | `resourceConfigurations zh,en` 但只有默认中文文案 | 海外可读性差 |
| A8 | 音频为合成占位 | README/`res/raw` 说明 | 品质风险，不影响功能 |
| A9 | 设置注释与实现不一致 | `Prefs.hapticStrength` 注释写 `0.35..1.0`，实现 `coerceIn(0.2f, 1f)` | 轻微文档瑕疵 |
| A10 | 极端窗口尺寸未覆盖 | 仪器测试覆盖 1080×2400、2400×1080、1600×2560、900×1600；未覆盖分屏 600×800 级 | 小窗口按钮拥挤风险 |

### 7.3 未发现的问题（已核查）

- 未使用 `java.lang.reflect`、`Class.forName`、`setAccessible`、JNI、`System.loadLibrary`。
- 无 `android:screenOrientation` 锁定值（是 `fullSensor`），无 `minAspectRatio`/`maxAspectRatio`。
- 无网络权限、无 WebView、无动态代码加载。
- 无 `externalNativeBuild`，APK 无 `.so`。
- 无 `WorkManager`/后台服务/前台服务，不存在 Android 17 后台音频加固的触发条件。

---

## 8. 物理最小验证（摘要）

完整设计与数据见 [PHYSICS_DESIGN.md](PHYSICS_DESIGN.md)，原始输出见 [evidence/physics_probe_results.txt](evidence/physics_probe_results.txt)。

| 验证项 | 结果 | 判定 |
|---|---|---|
| 双球正碰 | 入射 1.9011 m/s；出射 0.0474 / 1.8492 m/s；动量损失 0.24%；恢复系数 0.9475 | 通过 |
| 库边垂直反弹 | 入射 1.0635 m/s；出射 0.7867 m/s；恢复系数 0.7397 | 通过（设计值 0.74） |
| 滑动→滚动 | 0.3958 s / 1.0175 m；理论 0.397 s | 通过 |
| 高低杆差异 | 0.8 s 时 top 0.8971 m、draw 0.4977 m，差 0.3993 m | 通过 |
| 侧旋 | `ωz = -69.991 rad/s`，与 `5v/(2R)·0.4` 一致 | 通过 |
| 四元数 | `|q| = 1.000000` | 通过 |
| 确定性 | 两个新世界 2400 步最大位置差 0.000000000000 | 通过 |
| 开球 | 8 m/s：5.035 s 静止；12 m/s：4.598 s 静止 | 通过（见下） |
| 性能 | 4800 步 21.5–94.9 ms（3 次），4.5–19.8 µs/步，105–465× 实时 | 通过（桌面 JVM） |

**关于开球时间**：探针在 8 m/s 与 12 m/s 下分别测得 5.035 s 和 4.598 s 静止；`STAGE_REPORT.md` 的 20–27 s 描述的是更高力度、更多库边碰撞的场景，两者条件不同，不能直接判定谁“过期”。正确的表述是“随力度/旋转/碰撞次数变化，典型 5–27 s”，不应给单一固定值。

---

## 9. 阶段 0 验收与准入结论

### 9.1 验收对照

| 验收项 | 状态 | 说明 |
|---|---|---|
| 确实读取项目代码与构建配置 | 通过 | 构建脚本、Manifest、全部核心源文件、测试、CI 均已读取 |
| 技术选型有实际依据 | 通过 | 见 TECH_STACK，含被拒方案与理由 |
| 物理引擎方案可执行 | 通过 | 自研 480 Hz，探针 + 14 单测 |
| 渲染方案可执行 | 条件通过 | Shader/构建通过；帧率未在目标设备验证 |
| 折叠屏适配方案明确 | 条件通过 | 基础自适应明确；铰链姿态在阶段 1 验证，必要时再立项 |
| 触觉反馈方案明确 | 通过 | API 26/31 分支、振幅能力探测、降级、冷却 |
| 识别技术风险 | 通过 | 见 RISK_ASSESSMENT，24 项（R1–R24） |
| 无未解释的关键架构问题 | 通过 | 线程/生命周期/存档/渲染边界均有明确答案 |

### 9.2 结论

**允许进入阶段 1，但属于“条件性 Go”，不是“Android 17 真机认证通过”。**

允许的理由：

1. 构建、34 个单测、8 个 Shader、R8 release、lint 均有本机可复现证据。
2. 物理引擎的核心行为已被量化验证，误差与理论值一致，足以支撑玩法。
3. 架构边界清楚，没有需要推倒重来的设计问题。
4. Android 17 的两项高危变更（`static final` 反射、16 KB native）在本项目中不适用；方向/尺寸变更已被现有自适应实现覆盖。

阶段 1 的硬性前置条件（Gate A，必须先于任何新功能）：

1. 在至少一台 Android 17 真机上安装 debug APK，完成启动、开球、进球、暂停/恢复、存档/继续。
2. 用 `dumpsys gfxinfo`、`Perfetto` 或 GPU 厂商工具记录 60 秒对局帧时间；目标 p95 ≤ 16.6 ms，且无明显热降频。
3. 在折叠屏或可改变窗口大小的设备上验证展开/折叠/分屏不崩溃、不丢状态、按钮可点。
4. 验证触觉在至少一台振幅可控设备和一台仅开关型设备上的差异与降级。
5. 将 CI instrumentation 增加 API 37（或明确记录 API 35 的替代理由）。

未满足 Gate A 之前，不得宣称“Android 17 兼容性完成”或“性能达标”。

---

## 10. 审计产物索引

| 文档 | 内容 |
|---|---|
| [TECH_STACK.md](TECH_STACK.md) | 工具链、依赖、选型理由、被拒方案 |
| [ARCHITECTURE.md](ARCHITECTURE.md) | 模块、线程、生命周期、数据流 |
| [PHYSICS_DESIGN.md](PHYSICS_DESIGN.md) | 物理模型、公式、参数、探针数据、局限 |
| [RENDERING_DESIGN.md](RENDERING_DESIGN.md) | GLES 3.0 管线、Shader、光照、性能预算 |
| [DEVICE_COMPATIBILITY.md](DEVICE_COMPATIBILITY.md) | Android 17 行为变更、方向/折叠/触觉/生命周期 |
| [RISK_ASSESSMENT.md](RISK_ASSESSMENT.md) | 风险登记册、矩阵、缓解与门禁 |
| [DEVELOPMENT_ROADMAP.md](DEVELOPMENT_ROADMAP.md) | 阶段 1–4 路线图与验收标准 |
| [evidence/physics_probe_results.txt](evidence/physics_probe_results.txt) | 探针原始输出 |
