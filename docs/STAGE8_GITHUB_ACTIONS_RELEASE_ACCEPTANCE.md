# 阶段 8 验收报告：GitHub Actions 与发布

- 验收日期：2026-11-17
- 代码基线：`914d3fa`（阶段 7 验收提交）
- 前置条件：**阶段 7 已 PASS**（见
  [`STAGE7_TESTING_PERFORMANCE_ACCEPTANCE.md`](STAGE7_TESTING_PERFORMANCE_ACCEPTANCE.md)；
  本轮开始时复跑 `:core:test` + `:app:testDebugUnitTest` + `:app:lintDebug`
  全绿、174/174 确认后动工）
- 验收环境：JDK 21.0.13、Gradle 9.8.2、AGP 9.5.0、Kotlin 2.2.10、
  compileSdk/targetSdk 37、minSdk 26，本机 Debian 13 x86_64；
  SDK build-tools 37.0.0（含 apksigner/aapt2）；无 KVM/模拟器/adb
- 本轮性质：按工程基线补强序列执行 **GitHub Actions 与发布——自动构建和
  发布 APK**。核心内容：建立 release 签名体系（关闭风险登记册 R9
  「release 未签名」，评分 16，全册最高）、版本号自动化、CI 签名构建 +
  `v*` tag 自动发布 GitHub Release（APK + AAB + 校验和 + R8 映射表）。

> 编号说明：`docs/STAGE_REPORT.md` 中的历史“阶段 7”（CI/文档/交付）已于
> 2026-11-10 完成，与本补强序列并存，不改写历史数据（同阶段 4/5/6/7 声明）。

## 1. 验收结论

**通过（CI 部分为“本机等价验证通过，远端运行待推送后复验”）。**
174 个 JVM 测试全绿（core 48 + app 126），`lint` 0 error / 4 warning
（全部为先前已存在的版本提示类告警，本轮零新增；注：阶段 7 报告记为
5 warning，本轮复测为 4，差异为同一类版本提示的去重计数，无新增项）。
带签名材料构建 `assembleDebug` + `assembleRelease` + `bundleRelease`
全部成功：release APK（513,700 B）`apksigner verify` V2 通过且证书
SHA-256 与 keystore 逐字节一致；release AAB（696,852 B）`jarsigner -verify`
输出 `jar verified.`。无签名材料（环境变量缺失或显式空串，模拟 fork PR）
时回退未签名构建成功，保持阶段 8 之前的 CI 行为。

| 验收项 | 证据 | 结论 |
|---|---|---|
| 阶段 7 基线未回退 | 动工前复跑 174/174 全绿 + lint 0 error；本轮收尾全量复跑 `:core:test :app:testDebugUnitTest :app:lintDebug :app:assembleDebug :app:assembleRelease :app:bundleRelease` BUILD SUCCESSFUL，测试 174/174、lint 0 error/4 warning | 通过 |
| 签名 keystore 建立 | RSA 4096、PKCS12、别名 `breakpoint`、有效期 2026-11-17→2056-11-09（30 年）；SHA-256 指纹 `51:2A:6F:4E:…:57:45:17:1E`；存于 `.keystores/`（被 `.gitignore` 第 40 行 `/.keystores/` 覆盖，`git check-ignore -v` 双文件确认） | 通过 |
| 签名 APK（R9 关闭标准） | `apksigner verify --print-certs app-release.apk`：V2 Signer 通过，证书 SHA-256 digest `512a6f4e…45171e` 与 keystore 指纹一致 | 通过 |
| 签名 AAB | `jarsigner -verify app-release.aab` 输出 `jar verified.`（自签证书警告属正常） | 通过 |
| 条件签名 / fork PR 兼容 | 四 `RELEASE_*` 变量齐全且 keystore 存在才创建 signingConfig；无 env → `app-release-unsigned.apk`；`env -i` 显式空串 env → 同样正确回退未签名（blank-filter：`.map{trim}.filter{isNotEmpty}`） | 通过 |
| 版本号自动化 | 默认 `versionCode = git rev-list --count HEAD`（当前 22）、`versionName = 1.0.0`，aapt2 badging 实测 `versionCode='22' versionName='1.0.0'`；`-Pbilliards.versionCode=777 -Pbilliards.versionName=9.9.9-test` 覆盖实测生效 | 通过 |
| CI 工作流（本机等价） | `.github/workflows/android.yml` 重写后经 js-yaml 解析校验（jobs = build/instrumentation/gl-smoke/release，on = push branches+tags/PR/dispatch）；build 任务的 gradle 步骤、签名验证、校验和生成均在本机以相同命令复现成功 | 通过（等价验证；远端运行待复验） |
| 校验和与发布完整性 | CI 生成只含文件名的 SHA-256 校验和；本机端到端模拟「生成→下载→`sha256sum -c`」两个产物均 OK（曾发现含目录前缀会导致 release job 校验失败，已修复为 cd 子 shell + `sed 's| \./| |'`） | 通过 |
| tag 自动发布 | release 任务：`refs/tags/v*` 触发、needs 三任务、`contents:write`、缺签名 secrets 时 `::error::` 显式失败、下载 artifact 后 `test -f` + `sha256sum -c` 核对、softprops/action-gh-release@v2 发布 APK/AAB/校验和/mapping.txt | 通过（逻辑与 YAML 校验；真实 tag 发布待远端复验） |
| 文档与登记册 | 新增 `docs/release/SIGNING.md`（指纹/CI secrets 配置/验证/密钥保管，不含密码）；README 构建/产物/兼容性三节更新；`RISK_ASSESSMENT.md` R9 标记关闭、§5 结论表同步 | 通过 |

## 2. 签名体系设计

- **keystore**：RSA 4096 / PKCS12（JKS 已弃用），有效期 30 年；主体
  `CN=Break Point Billiards Release, OU=Development, O=MinaMiKune, C=TW`。
  签名材料永不入库：keystore 与 env 文件（600 权限）放 `.keystores/`，
  `.gitignore` 目录级忽略。
- **条件签名**：`app/build.gradle.kts` 新增 `signingEnv(env, property)`
  帮助函数（env 或 `-Pbilliards.*` gradle 属性，空白视为未提供——防止 CI
  空串 env 误判为“提供了签名”）；四个 `RELEASE_*` 齐全且文件存在才创建
  `signingConfigs.release` 并挂到 release buildType，缺失时报错信息指明
  缺哪个变量。无密钥 fork PR 构建行为与阶段 8 之前完全一致。
- **版本号**：`versionCode` 取提交计数保证单调递增，tag 发布时
  versionName 自动取 `v*` 去前缀；均支持 `-P` 手动覆盖。
- 注意：PKCS12 不支持 store/key 密码不同，两密码实际相同（已在
  SIGNING.md 注明）。

## 3. GitHub Actions 发布流程

`.github/workflows/android.yml` 四任务（instrumentation、gl-smoke 两任务
与阶段 7 相同，未改动）：

1. **build**：单测 + lint + 编译 androidTest → 构建 debug APK、release APK、
   release AAB。经 env 间接引用 secrets 判定可用性（GitHub 不允许 `if:`
   直接引用 secrets）→ base64 解码 keystore 到 `$RUNNER_TEMP` → 签名构建 →
   `apksigner verify --print-certs` + `jarsigner -verify` AAB → 生成
   SHA-256 校验和（文件名无目录前缀）→ 上传 5 个 artifact（debug-apk、
   release-apk、release-aab、checksums、mapping）。checkout `fetch-depth: 0`
   保证版本号提交计数可用。
2. **release**（仅 `refs/tags/v*`，needs 其余三任务）：缺签名 secrets 显式
   失败 → 下载 artifact → `sha256sum -c` 完整性核对 → 创建 GitHub Release，
   附件 `app-release.apk` / `app-release.aab` / `checksums-sha256.txt` /
   `mapping.txt`，中文发布说明链接 SIGNING.md。

## 4. 风险登记册更新

| 风险 | 原等级 | 本轮动作 | 状态 |
|---|---|---|---|
| R9 release 产物未签名 | 高（16） | keystore + 条件 signingConfigs + CI secrets 签名构建 + tag 自动发布；apksigner/jarsigner 验证通过 | **关闭**（“可安装/可升级”待真机/远端复验） |
| R1 真机无帧率数据 | 高 | 不在本阶段范围 | 开放（缓解） |
| R10 无障碍 / R11 仅中文 等 | 中 | 不在本阶段范围 | 维持原状 |

## 5. 本轮变更清单

本轮共 7 个文件（含本报告，+400/−17 行规模）：

- `app/build.gradle.kts`：版本号策略（git 提交计数 + `-P` 覆盖）、
  `signingEnv` 条件签名（+63 行主干改动）。
- `.github/workflows/android.yml`：build 任务签名构建/验证/校验和/artifact
  扩展，新增 release 发布任务（+135 行主干改动）。
- `.gitignore`：新增 `/.keystores/`。
- `docs/release/SIGNING.md`：新建（93 行）。
- `README.md`：构建命令、版本号与签名说明、产物清单、发布流程说明。
- `docs/audit/RISK_ASSESSMENT.md`：R9 关闭标注、§5 结论表同步。
- `docs/STAGE8_GITHUB_ACTIONS_RELEASE_ACCEPTANCE.md`：本报告。

## 6. 遗留与后续建议

1. **远端 CI 首跑复验**：本机无 GitHub Actions 运行环境，工作流经 YAML
   解析校验 + 逐步骤本机等价复现验证；推送后需观察一次 push 构建、并
   配置 4 个 secrets（SIGNING.md §仓库 secrets）后做一次 `v*` tag 试
   发布，确认 release 任务端到端行为。
2. **签名产物“可安装/可升级”真机闭环**：R9 关闭标准含可安装/可升级，
   本机无模拟器/adb，待远端 CI 模拟器或真机复验（安装 → 覆盖安装升级）。
3. **Play 上架准备**：当前发布渠道为 GitHub Release；如需上架 Google
   Play，应启用 Play App Signing 并改用 AAB 上传（keystore 备份策略见
   SIGNING.md）。
4. **actionlint**：本机无 actionlint，工作流静态检查以 js-yaml 解析 +
   逐表达式人工审查替代；后续可在 CI 中加 actionlint 步骤。
