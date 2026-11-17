# Release 签名与发布流程

本文说明 Break Point 桌球 release 产物的签名方式、CI 发布流程和验证方法。
签名材料（keystore 与密码）**永不进入 git 仓库**；本文只包含公开信息（证书指纹）。

## 签名证书

- 别名：`breakpoint`
- 算法：RSA 4096，PKCS12（`.jks` 扩展名，JKS 容器已弃用）
- 有效期：2026-11-17 至 2056-11-09（30 年）
- 主体：`CN=Break Point Billiards Release, OU=Development, O=MinaMiKune, C=TW`
- SHA-256 指纹：`51:2A:6F:4E:29:5E:F1:F7:FB:59:01:12:EB:CA:CF:01:EA:D4:27:98:DA:07:5C:A3:E5:1E:ED:C2:57:45:17:1E`
- SHA-1 指纹：`34:6E:0B:CC:2B:5B:E0:EF:F8:94:B1:92:17:04:81:4F:9D:7A:BD:CE`

注意：PKCS12 不支持 store 密码与 key 密码不同，两个密码实际相同。

## 本地构建（有签名材料时）

维护者在本地持有 keystore（建议放在仓库外的安全目录，仓库内约定用
`.keystores/`，已被 `.gitignore` 忽略）。四个变量齐全且 keystore 文件存在时，
`release` buildType 自动挂载签名配置：

| 变量 | 说明 | 对应 gradle 属性 |
| --- | --- | --- |
| `RELEASE_KEYSTORE_PATH` | keystore 文件路径 | `billiards.releaseKeystorePath` |
| `RELEASE_STORE_PASSWORD` | store 密码 | `billiards.releaseStorePassword` |
| `RELEASE_KEY_ALIAS` | 密钥别名 | `billiards.releaseKeyAlias` |
| `RELEASE_KEY_PASSWORD` | key 密码 | `billiards.releaseKeyPassword` |

```bash
set -a; . ./.keystores/breakpoint-release.env; set +a
./gradlew :app:assembleRelease :app:bundleRelease
# 产物：app-release.apk（APK Signature Scheme v2）与 app-release.aab
```

任一变量缺失、为空或 keystore 文件不存在时，release 构建保持**未签名**
（`app-release-unsigned.apk`），与阶段 8 之前的 CI 行为一致——这保证了
fork 仓库/外部 PR 在没有签名材料时也能构建成功。

## 版本号

- `versionCode` 默认取 `git rev-list --count HEAD`，随每次提交自动递增；
- `versionName` 默认 `1.0.0`；
- CI 打 tag（`v*`）时用 tag 名去前缀覆盖 versionName（如 tag `v1.2.0` →
  `1.2.0`），可用 `-Pbilliards.versionCode=N` / `-Pbilliards.versionName=X` 手动覆盖。

## GitHub Actions 发布流程

工作流：[`.github/workflows/android.yml`](../../.github/workflows/android.yml)

1. **build 任务**（push / PR / 手动触发）：单测 + lint + 编译 androidTest，
   然后构建 debug APK、release APK、release AAB。检测到签名 secrets 时
   解码 keystore 并签名，否则保持未签名；有签名时执行
   `apksigner verify --print-certs` 与 `jarsigner -verify` 验证，并生成
   release 产物的 SHA-256 校验和，连同 `mapping.txt` 一起作为 artifact 上传。
2. **release 任务**（仅 `refs/tags/v*`，依赖其余三个任务全部通过）：
   校验签名 secret 存在 → 下载 artifact → `sha256sum -c` 核对校验和 →
   创建 GitHub Release，附件为 `app-release.apk`、`app-release.aab`、
   `checksums-sha256.txt`、`mapping.txt`。

### 配置仓库 secrets

仓库 **Settings → Secrets and variables → Actions** 中设置：

| Secret | 内容 |
| --- | --- |
| `SIGNING_KEYSTORE_BASE64` | keystore 文件的 base64（`base64 -w0 breakpoint-release.jks`） |
| `RELEASE_STORE_PASSWORD` | store 密码 |
| `RELEASE_KEY_ALIAS` | `breakpoint` |
| `RELEASE_KEY_PASSWORD` | key 密码（与 store 密码相同） |

缺少 secrets 时不影响 push/PR 构建（产物未签名）；tag 发布会因
`Require signing secrets` 步骤显式失败，避免把未签名产物当作正式版发出。

## 验证签名产物

```bash
# APK：V2 签名 + 证书指纹
"$ANDROID_SDK_ROOT/build-tools/37.0.0/apksigner" verify --print-certs app-release.apk

# AAB：JAR 签名（自签证书会有警告，属正常）
jarsigner -verify app-release.aab

# 校验和
sha256sum -c checksums-sha256.txt
```

## 密钥保管与轮换

- keystore 原件由维护者离线备份（至少两份，异地）；丢失即失去与应用
  市场/已安装设备升级链的连续性，无法恢复，只能换新证书重新发布。
- 轮换证书 = 生成新 keystore 并更新上述 secrets 与本文指纹；Google Play
  侧如需密钥升级请使用其 Play App Signing 流程，本项目当前不走 Play。
