# 技术栈与选型（阶段 0）

- 基线：`6664c91`（2026-11-06）
- 结论：**当前技术栈足以支撑 Android 17 的 8 球游戏**；无需引入游戏引擎、Compose 或 native 物理库。
- 本文的版本号均来自仓库文件或本机命令，不来自记忆。

---

## 1. 版本清单

| 层 | 组件 | 版本 | 证据 |
|---|---|---|---|
| 语言 | Kotlin | 2.2.10 | 根 `build.gradle.kts` |
| 运行时 | JDK | 21.0.13 | `java -version` |
| 构建 | Gradle | 9.8.2 | `gradle/wrapper/gradle-wrapper.properties` |
| 构建 | Android Gradle Plugin | 9.5.0 | 根 `build.gradle.kts` |
| 构建 | Build-Tools | 37.0.0 | `app/build.gradle.kts:8` |
| 平台 | compileSdk | 37 | `app/build.gradle.kts:7` |
| 平台 | targetSdk | 37 | `app/build.gradle.kts:13` |
| 平台 | minSdk | 26 | `app/build.gradle.kts:12` |
| 图形 | OpenGL ES | 3.0（`0x00030000`） | Manifest `uses-feature` |
| 测试 | JUnit | 4.13.2 | `core`、`app` 测试依赖 |
| 测试 | AndroidX Test Runner | 1.7.0 | `app/build.gradle.kts` |
| 测试 | AndroidX Test Ext JUnit | 1.3.0 | `app/build.gradle.kts` |
| 测试 | androidx.core:core-ktx | 1.7.0 | 仅仪器测试依赖 |
| CI | GitHub Actions | `ubuntu-latest` + Temurin JDK 21 | `.github/workflows/android.yml` |

补充事实：

- `app` 模块**没有** `org.jetbrains.kotlin.android` 插件，使用 AGP 9.5 的内置 Kotlin 支持；`core` 模块使用 `org.jetbrains.kotlin.jvm`。
- `core` 用 `jvmToolchain(21)`，但 `jvmTarget = 17`；`app` 的 `compileOptions` 也是 Java 17。产物字节码面向 Android 可接受的 Java 17，构建 JDK 用 21。
- 没有 version catalog（`gradle/libs.versions.toml` 不存在），版本集中在两个模块的构建脚本和根脚本。
- 没有 NDK、没有 CMake、没有 native 依赖。

---

## 2. 模块与构建配置

### 2.1 `:core`（纯 Kotlin/JVM）

```kotlin
plugins { kotlin("jvm") }
kotlin { jvmToolchain(21) }
tasks.withType<KotlinCompile> { compilerOptions.jvmTarget.set(JvmTarget.JVM_17) }
dependencies {
    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
}
```

价值：

- 物理与规则零 Android 依赖，单测在 JVM 上毫秒级完成。
- 同一套代码可被未来的回放工具、规则校验器或桌面调试器复用。
- 没有 Robolectric，不需要模拟 Android framework。

### 2.2 `:app`（Android）

关键配置：

| 配置 | 值 | 目的 |
|---|---|---|
| `namespace` / `applicationId` | `com.minamikune.billiards` | 包名 |
| `buildFeatures.buildConfig` | `true` | 构建信息 |
| `release` | `isMinifyEnabled=true`、`shrinkResources=true` | R8 与资源压缩 |
| ProGuard | `proguard-android-optimize.txt` + `proguard-rules.pro` | 默认优化 + 项目规则 |
| `debug` | `applicationIdSuffix=".debug"` | 与 release 可共存 |
| `unitTests.isIncludeAndroidResources` | `false` | 单测不加载 Android 资源 |
| `resourceConfigurations` | `zh`, `en` | 限制语言资源 |
| `packaging` | 排除 `META-INF/AL2.0`、`LGPL2.1`、`*.kotlin_module` | 避免重复/无用元数据 |

依赖只有：

```text
implementation(project(":core"))
testImplementation(junit 4.13.2, kotlin-test)
androidTestImplementation(androidx.test:runner 1.7.0,
                          androidx.test.ext:junit 1.3.0,
                          androidx.core:core-ktx 1.7.0)
```

**没有** AppCompat、Material Components、Compose、Navigation、Lifecycle、Room、Hilt、Retrofit、OkHttp、Coil/Glide、Firebase、Play Services、游戏引擎、物理库。这是刻意的：APK 小、启动路径短、无供应链面。

### 2.3 根构建与仓库

- `pluginManagement`：`google()`、`mavenCentral()`、`gradlePluginPortal()`。
- `dependencyResolutionManagement`：`FAIL_ON_PROJECT_REPOS`，仓库集中管理。
- 根插件：AGP 9.5.0、Kotlin JVM 2.2.10，均 `apply false`。
- `gradle.properties`：2 GB 堆、parallel、build cache、AndroidX、non-transitive R。

---

## 3. 选型理由

### 3.1 为什么自研物理，而不是 Box2D/Bullet/PhysX

| 方案 | 优势 | 不选的原因 |
|---|---|---|
| **自研 2.5D 台球物理（当前）** | 完全可控；滚动/滑动/角速度/杆法/袋口都可针对台球建模；无 native 依赖；JVM 可测；确定性 | 需要自己处理穿透、袋口、数值稳定性；已用 480 Hz + 扫掠袋口 + 2 次求解迭代覆盖 |
| Box2D | 成熟、稳定 | 2D 刚体不直接表达“球在台面上滚动 + 自转 + 滑动摩擦”；需要额外的台球专用层；引入 native/C++ 或 Java 端口；APK 与 16 KB 对齐面增加 |
| Bullet | 3D 完整 | 对 16 个球严重过度设计；native 体积、构建复杂度、确定性调参成本高；台球手感仍需大量定制 |
| PhysX | 主机级 | Android 集成与包体不可接受 |

**决策**：保留自研。阶段 0 的探针已经验证双球、库边、滑动→滚动、高低杆、侧旋、确定性和开球，足以证明“可执行”，不是理论判断。未来若要做复杂跳球/球台形变，再评估混合方案。

### 3.2 为什么 Canvas UI，而不是 Compose

- 游戏 UI 是强定制的仪表盘/圆盘/力度条，绘制路径与命中区域需要和 GL 场景精确对齐。
- Compose 与 `GLSurfaceView` 的互操作需要额外封装，且会引入大量依赖与启动成本。
- 当前 `GameHudView`/`MenuView` 直接 `onDraw`，布局逻辑按 `width > height` 分支，已在 4 种尺寸上做真实 Canvas 绘制测试。
- 代价：无障碍语义弱（见 RISK_ASSESSMENT R10），需要在阶段 2 补虚拟节点/内容描述。

### 3.3 为什么 GLES 3.0，而不是 Vulkan

- 目标设备覆盖优先：GLES 3.0 在 Android 4.3+ 可用，minSdk 26 的设备几乎全部支持。
- 当前渲染负载很轻（约 50 个 draw call、16 个球、程序化纹理、单 pass），不需要 Vulkan 的显式管线。
- GLES 3.0 的 `#version 300 es`、VAO、`highp`、mipmap 已足够实现 Lambert/Blinn-Phong/Fresnel/接触阴影。
- 代价：驱动差异、GL 状态查询、上下文丢失需要处理；已在 `onSurfaceCreated` 清理 uniform 缓存并在 `onPause` 保留 EGL context。

### 3.4 为什么不用游戏引擎（Unity/Unreal/libGDX）

- 玩法规模小，2D 桌面 + 16 球；引擎带来的包体、启动、构建链路和许可成本大于收益。
- 现有 APK 1.30 MiB（debug）/399 KiB（release），引擎方案通常从数十 MiB 起步。
- 纯 Kotlin 让物理/规则可在 JVM 单测中确定性复现；引擎 MonoBehaviour/Scene 体系反而降低可测性。

### 3.5 为什么保留 `:core` 纯 JVM

- 物理与规则是最高风险、最需要回归测试的部分；纯 JVM 测试快且稳定。
- 渲染与平台能力变化不影响规则测试。
- 未来做回放/观战/服务端校验时，`:core` 可直接复用。

---

## 4. 工具链约束与可复现性

| 约束 | 现状 | 风险/对策 |
|---|---|---|
| Gradle wrapper | 9.8.2，`validateDistributionUrl=true` | 版本固定；CI 使用 `gradle/actions/setup-gradle` |
| JDK | 21（CI Temurin 21） | 与本机一致；`jvmTarget 17` 保证 Android 兼容 |
| SDK 平台 | `platforms;android-37.0` + `build-tools;37.0.0` | CI 显式安装；`local.properties` 不入库 |
| 仓库 | google + mavenCentral | 无第三方私有仓库 |
| 签名 | release 未签名 | 发布需外部 keystore；当前 tag 发 debug |
| 版本目录 | 无 | 升级需改脚本；阶段 2 可引入 catalog，非必需 |

**升级策略**：

1. 每次升级 AGP/Gradle/Kotlin 只动一个变量，跑 `:core:test :app:testDebugUnitTest :app:lintDebug :app:assembleRelease`。
2. 升级后必须重跑 8 个 Shader 的 `glslangValidator`（GLSL 编译器随驱动/工具链变化）。
3. targetSdk 升级必须重读 Android 行为变更，不因“编译通过”而签收。
4. 不在阶段 1 引入新依赖；如必须引入，先写 ADR 并评估 APK 体积与 16 KB 影响。

---

## 5. 安全与隐私

- 无 `INTERNET` 权限，无网络代码，无遥测，无广告 SDK。
- 只有 `VIBRATE` 普通权限；没有存储/位置/相机/麦克风权限。
- 存档在 `SharedPreferences`（应用私有），`allowBackup=true`；不含账号、位置或设备标识。
- 无动态代码加载、无反射、无 JNI；Android 17 的 `static final` 限制与 16 KB 页问题不适用。
- Release 开启 R8；`proguard-rules.pro` 目前无需保留业务类（无反射）。

---

## 6. 结论

| 问题 | 结论 |
|---|---|
| 技术栈是否过时？ | 否。AGP 9.5 / Gradle 9.8.2 / Kotlin 2.2.10 / SDK 37 是当前基线 |
| 是否缺少关键依赖？ | 否。游戏所需能力均由平台 API + 自研代码覆盖 |
| 是否有 native/16 KB 风险？ | 否。APK 无 `.so` |
| 是否有供应链风险？ | 很低。依赖数量极少，无网络 SDK |
| 是否需要换引擎？ | 不需要。现有实现已通过构建、单测和物理探针 |
| 最大技术不确定性 | 真机 GPU 帧率与发热，不在技术栈选择，而在验证环境 |
