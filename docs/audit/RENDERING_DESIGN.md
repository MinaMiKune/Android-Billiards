# 渲染设计（阶段 0）

- 实现：`app/src/main/kotlin/com/minamikune/billiards/gl/TableRenderer.kt`（878 行）
- 工具：`GlUtil.kt`（编译/链接/纹理/错误检查）、`Mat4.kt`、`BallArt.kt`（程序化贴图）
- 结论：**OpenGL ES 3.0 前向渲染方案可执行**；但“目标真机帧率”尚未实测，必须作为阶段 1 的门禁项。
- 范围：只画一张台球桌 + 16 球 + 瞄准辅助 + 球杆；没有 3D 场景、没有后处理、没有阴影贴图。

---

## 1. 技术选型

| 决策 | 选择 | 理由 |
|---|---|---|
| 图形 API | **OpenGL ES 3.0** | `minSdk 26` 已保证；覆盖广；代码量小；CI/模拟器可用 SwiftShader |
| 是否用 Vulkan | 否 | 收益低、初始化/兼容复杂度高；本场景 ~50 draw call |
| 是否用游戏引擎 | 否 | Unity/Unreal 包体与构建链过重；本项目只需要一个渲染器 |
| 是否用 Compose | 否（3D 部分） | `GLSurfaceView` 与 Compose 混排会引入额外生命周期/互操作复杂度 |
| UI | **Canvas 自绘 View** | 覆盖层文字/按钮/圆盘足够；零 XML 布局；横竖屏手动布局可控 |
| 阴影 | **接触阴影贴片** | 台球阴影主要是球底软阴影；比 shadow map 便宜且稳定 |
| 抗锯齿 | 无 MSAA | EGL 配置 `(8,8,8,8,16,0)`；靠高分辨率球网格 + mipmap；后续可加 4×MSAA |

---

## 2. 帧图与 GL 状态

```text
onDrawFrame
  ├─ copyState(dt)                 // 锁内复制快照到原始数组
  ├─ glClear(COLOR | DEPTH)
  ├─ glEnable(DEPTH_TEST); glDepthFunc(LEQUAL); glDepthMask(true)
  ├─ drawFloor()                   // 大平面 + 地板纹理
  ├─ drawTable()                   // 11 个 box：台面/库边/木框/桌腿
  ├─ drawShadows()                 // 每球一个接触阴影 quad，混合
  ├─ drawPockets()                 // 6 个袋口暗盘 + 袋角
  ├─ drawAimGuide()                // 虚线、鬼球、目标线
  ├─ drawBalls()                   // 16 个球（高/低模）
  ├─ drawCue()                     // 3 段圆柱球杆
  └─ GlUtil.checkError("frame")    // debug 构建可查 GL 错误
```

固定状态（`onSurfaceCreated`）：

```text
clearColor = (0.020, 0.024, 0.028, 1)
depth test = LEQUAL, depth mask on
blend = SRC_ALPHA / ONE_MINUS_SRC_ALPHA
cull face = disabled（球/杆/阴影双面可见，避免绕序问题）
```

---

## 3. 相机与投影

### 3.1 参数

| 项 | 值 |
|---|---|
| 投影 | 透视，垂直 FOV **36°** |
| 近/远 | 0.05 / 40 m |
| 视线方向 | `normalize(0, 0.86, 0.51)`，俯视台面 |
| 目标点 | 台面中心 `(0,0,0)` |
| up | `(0,1,0)` |
| 适配边距 | 台面外扩 0.20 m |
| NDC 安全区 | x ≤ 0.90，y ≤ 0.80（给 HUD 留边） |

### 3.2 自动取景

`fitCamera(aspect)` 在距离 `[1.5, 14]` 上做 34 次二分：

```text
for d in binary_search:
    eye = dir * d
    view = lookAt(eye, origin, up)
    for 台面四角 × y∈{0, 0.12}:
        p = project(viewProj, corner)
        if |p.x| > 0.90 or |p.y| > 0.80: 失败
取满足条件的最小距离 × 1.015
```

- 竖屏时先把桌面根节点绕 Y 旋转 +90°，再按“屏幕横向=台宽、纵向=台长”取景。
- `onSurfaceChanged` 重新计算 aspect/viewProj/invViewProj；折叠、分屏、旋转都走这里。
- 相机不做动画/追踪，保证台面始终完整可见。

### 3.3 坐标映射

```text
物理桌面 (x,y)
  └─ TableSpace.tableToWorld(x, y, portrait)
       ├─ 横屏: (x, 0, y)
       └─ 竖屏: (y, 0, -x)
  └─ root * model * viewProj
```

触摸反向：

```text
屏幕 → NDC → invViewProj → 射线与 y=0 求交
     → TableSpace.worldToTable(wx, wz, portrait) → 物理桌面坐标
```

---

## 4. 材质与着色器

共 4 个 program、8 个 shader，全部 `#version 300 es`；已用 `glslangValidator` 逐个编译通过（8/8，exit 0）。

### 4.1 球（`BALL_VERT` / `BALL_FRAG`）

顶点：

```glsl
w = uModel * vec4(aPos,1)
vWorld = w.xyz
vNormal = mat3(uModel) * aNormal
vUv = aUv
gl_Position = uViewProj * w
```

片元：

```glsl
N = normalize(vNormal)
V = normalize(uCam - vWorld)
L = normalize(uLight - vWorld)
H = normalize(L + V)
diff = max(dot(N,L), 0)
spec = pow(max(dot(N,H),0), 110)
base = texture(uTex, vUv).rgb; base *= base
color = base * (0.16 + 0.92*diff)
color += vec3(1.0,0.94,0.82) * spec * 1.55
fill = max(dot(N, normalize(vec3(-0.35,0.55,0.75))), 0)
color += base * fill * 0.16
R = reflect(-V,N)
env = mix(vec3(0.015,0.045,0.035), vec3(0.62,0.46,0.28), clamp(R.y*0.5+0.5,0,1))
fres = pow(1 - clamp(dot(N,V),0,1), 4)
color += env * fres * 1.05
color += vec3(0.018,0.085,0.052) * max(-N.y, 0)   // 台呢绿色反弹
fragColor = vec4(pow(max(color,0), vec3(0.92)), uAlpha)
```

特点：

- **Lambert + Blinn-Phong + Fresnel 环境反射 + 半球补光**；无 PBR/IBL。
- `base = tex * tex` 加深色饱和度，让程序化球面更“实”。
- 高光指数 110，模拟清漆球面；`uAlpha` 用于落袋淡出。
- 球面 UV 是等距柱状（equirectangular），数字/条纹由 `BallArt` 预烘焙到纹理，旋转时随四元数转动。

### 4.2 台面/木框（`TABLE_VERT` / `TABLE_FRAG`）

```glsl
base = texture(uTex, vUv * uUvScale).rgb * uTint
d = distance(vWorld, uLight)
pool = exp(-d*d*1.7)
diff = max(dot(N,L),0)
spec = pow(max(dot(N,H),0),42) * 0.10
color = base * (0.19 + 0.84*diff)
color += vec3(1.0,0.83,0.56) * pool * 0.10
color += vec3(1.0,0.92,0.78) * spec
fragColor = vec4(pow(max(color,0), vec3(0.94)), uAlpha)
```

- 同一 program 通过 `uTint`/`uUvScale` 复用于台呢、木框、桌腿。
- `pool` 是**动态光斑**：随 `uLight` 移动，在台呢上形成缓慢游走的高光区域。
- 台呢纹理 `GL_REPEAT`，木纹也 repeat；UV 缩放控制密度。

### 4.3 接触阴影（`SIMPLE_VERT` / `SHADOW_FRAG`）

```glsl
// vertex
vUv = aPos.xz * 0.5 + 0.5

// fragment
a = texture(uTex, vUv).a
fragColor = vec4(0.0, 0.02, 0.01, a * uAlpha)
```

- 每个活动球一个 quad，放在 `y = 0.0035`，`uAlpha ≈ 0.72`。
- 阴影贴图是 128×128 的径向 alpha 渐变；quad 随球位置移动，不随球旋转。
- 这是**接触阴影/假阴影**，不是 shadow mapping：没有遮挡关系、没有方向性、没有桌腿投影。
- 落袋过程中阴影随球缩小/淡出。

### 4.4 线（`LINE_VERT` / `LINE_FRAG`）

```glsl
gl_Position = uViewProj * uModel * vec4(aPos,1)
fragColor = uColor
```

- 单个动态 VBO（4096 floats），每帧上传瞄准虚线/袋口线。
- `glLineWidth` 在 GLES 上支持有限（很多设备只保证 1 px），所以辅助线视觉粗细不保证；需要更粗时应改为三角形带。

---

## 5. 光照与阴影

### 5.1 动态光

```text
lightPos = (0.30·sin(t·0.22), 1.15, 0.28·cos(t·0.19))
```

- 单点光，缓慢做李萨如运动；球和台面都采样同一个 `uLight`。
- 台面额外的 `exp(-d²·1.7)` 光斑增强“灯罩”感。
- 没有多光源、没有阴影贴图、没有实时反射/折射。

### 5.2 阴影取舍

| 方案 | 成本 | 效果 | 决策 |
|---|---|---|---|
| Shadow map | 额外 pass + FBO + PCF | 方向性阴影，桌腿/球互相投影 | 不做（当前场景收益低） |
| 接触阴影 quad | 16 个额外 draw，极低 | 球底软阴影，稳定 | **采用** |
| 无阴影 | 0 | 球像悬浮 | 不采用 |

风险：球靠近库边时阴影会穿到库边上（quad 不裁剪），视觉上可接受；若追求质量，阶段 1 可加“阴影裁剪到台面内”的 shader 判断。

---

## 6. 纹理、网格与显存

### 6.1 程序化纹理

| 纹理 | 尺寸 | 生成 | 用途 |
|---|---|---|---|
| 16 个球面 | 512×256 ARGB8888 | `BallArt` Canvas 绘制（底色/条纹/号码圈/高光） | 每球一张，避免图集 UV 复杂度 |
| 台呢 | 512×512 | 噪点 + 纤维 | repeat |
| 木纹 | 512×512 | 条纹 + 噪点 | repeat（库边/桌腿） |
| 阴影 | 128×128 alpha | 径向渐变 | 接触阴影 |
| 地板 | 512×512 | 暗色木纹/噪点 | repeat |
| 白 | 2×2 | 纯白 | 纯色部件 |

- 全部 `GLUtils.texImage2D` 上传后 `bitmap.recycle()`，Java 堆不长期占图。
- 默认生成 mipmap（`GL_LINEAR_MIPMAP_LINEAR`），纹理内存约 **15 MB**（基础 11.3 MB × 1.33）；低端机可考虑关闭球面 mipmap 或降到 256×128。
- 没有纹理压缩（ETC2/ASTC）；程序化生成后压缩需要额外工具链，收益有限。

### 6.2 网格

| 网格 | 规格 | 用途 |
|---|---|---|
| 高模球 | 32×48（约 1.5k 三角形） | 高质量模式 |
| 低模球 | 20×30 | 省电/低内存 |
| 极低球 | 12×18 | 远景/低端兜底（已定义） |
| box | 单位立方体 | 台面/库边/桌腿（缩放） |
| plane | 单位平面 | 地板 |
| disc | 圆盘 | 袋口暗盘 |
| ring | 0.86 半径环 | 袋口装饰 |
| cue | 20 段圆柱 | 球杆（3 段缩放） |
| quad | 单位四边形 | 阴影 |

- 台面由 11 个 `part()` box 拼成：台呢面、四边库、四角木框、桌腿等；不是单一复杂 mesh。
- 所有顶点/索引在启动时生成，运行期不重建（除 EGL 上下文丢失）。
- VBO/VAO 常驻；线 VBO 动态。

### 6.3 绘制调用估算

| 批次 | draw calls |
|---|---:|
| 台面/木框/桌腿 | ~11 |
| 球 | 16 |
| 阴影 | 16 |
| 袋口/装饰 | ~7 |
| 瞄准线/鬼球/球杆 | ~5–10 |
| **合计** | **~50–60** |

对 GLES3 手机来说很低；瓶颈更可能是片元着色（球面高光/Fresnel）和填充率，而不是 draw call。

---

## 7. 球面与落袋动画

- 球模型矩阵 = `translate(world) * rotate(quat) * scale(r)`；四元数来自物理快照。
- 球面纹理包含号码；旋转时号码会转到背面，符合真实球。
- 落袋动画：
  - 球 `active=false` 后，渲染侧 `fall[i]` 从 0 增至 1；
  - 位置下沉、缩放缩小、`uAlpha` 降低；
  - 约 0.55 s 完成（`fall += dt / 0.55`），然后不再绘制。
- 白球复位/自由球：`active=true` 时重置 `fall=0`。
- 瞄准辅助：
  - 从白球沿 aim 方向画虚线；
  - 与最近球/库求交得到鬼球位置（半透明球）；
  - 目标球方向线、力度条在 HUD（Canvas）而非 GL。

---

## 8. 与 Canvas UI 的合成

```text
FrameLayout
  ├─ GLSurfaceView   (z=0)  ← 3D 台面
  ├─ GameHudView     (z=1)  ← 力度/杆法/按钮/提示
  └─ MenuView        (z=2)  ← 主菜单/暂停/结算/设置
```

- 两个 Canvas View 都是 `MATCH_PARENT`，背景透明（菜单页有不透明底色）。
- 系统栏/刘海 inset 传给 `setSafeInsets`；GL 仍全屏绘制（台面在安全区内由相机边距保证）。
- 菜单可见时消费触摸；HUD 只在对局时处理触摸。
- 没有 `TextureView`/`SurfaceView` 与 Compose 的互操作问题。

---

## 9. 画质与性能策略

### 9.1 质量档

| 档位 | 触发 | 球网格 |
|---|---|---|
| 高 | 用户选“高”，或自动且 `!isLowRamDevice && memoryClass >= 256` | 32×48 |
| 低 | 用户选“省电”，或自动且低内存设备 | 20×30 |

- 质量在 `MainActivity.highQuality()` 决定，创建 renderer 时传入；切换需要重建 GLSurfaceView（`recreateSurface`）。
- 没有动态分辨率、没有帧率自适应；快进只影响模拟，不影响渲染。

### 9.2 性能预算（估算，非实测）

| 项 | 估算 |
|---|---|
| 三角形 | 球 16×1.5k ≈ 24k；台面/杆/袋 <5k；总计 <35k |
| 片元 | 1080p 全屏台面 + 球；球面高光 pow 110/Fresnel |
| 纹理带宽 | 15 MB 常驻；每帧采样球面/台面 |
| draw call | ~50–60 |
| 目标 | 60 fps，p95 帧时间 ≤16.6 ms |

**未实测**：本地无 adb 设备、无 `/dev/kvm`、无 emulator 包；CI 的 API35 ATD 用 SwiftShader，不能代表手机 GPU。阶段 1 必须在至少一台真实 Android 17 设备上测：

1. `dumpsys gfxinfo` 帧时间分布；
2. GPU 厂商 profiler（Adreno/Mali）看片元/带宽；
3. 连续 10 分钟对局温度/降频；
4. 折叠屏展开/合上时 `onSurfaceChanged` 是否稳定；
5. 4×MSAA 开关的帧率对比。

### 9.3 可优化项（按收益排序）

1. 球面片元降级：低端机去掉 Fresnel/pow 110，改 Blinn-Phong pow 32。
2. 球纹理降到 256×128（省 ~6 MB）。
3. 接触阴影合并为一个 instanced draw（GLES3 支持实例化）。
4. 台面 11 个 box 合并为 1 个静态 VBO。
5. 4×MSAA（若 GPU 有余量）。
6. 动态分辨率（复杂，暂不做）。

---

## 10. 上下文丢失与生命周期

- `GLSurfaceView` 设置 `preserveEGLContextOnPause=true`，但系统仍可能丢上下文。
- `onSurfaceCreated` 每次都：
  1. 清空 `uniforms` 缓存；
  2. 重新编译/链接 4 个 program；
  3. 重新生成所有 mesh/纹理；
  4. 重新绑定 VAO/VBO。
- 不在 `onSurfaceCreated` 之外持有 GL 资源句柄；避免“旧上下文 ID 在新上下文无效”。
- `onPause` 调 `surface.onPause()`；`onResume` 调 `surface.onResume()`；渲染线程不直接触碰 Activity。
- `copyState` 用 `engine` 快照；EGL 重建期间物理继续（若未 pause），恢复后直接画最新状态，不补帧。

---

## 11. 已知渲染风险

| 风险 | 影响 | 缓解 |
|---|---|---|
| 无真机帧率数据 | 可能中低端机 <60 fps | 阶段 1 门禁：真机 p95 ≤16.6 ms；低画质档兜底 |
| 无 MSAA | 球边缘/瞄准线锯齿 | 高分辨率 + mipmap；可按设备加 4×MSAA |
| 无阴影贴图 | 球/桌腿无方向性阴影 | 接触阴影已够用；后续可加 |
| 球面高光过曝 | 部分角度白色 | 已有 gamma 0.92；可按设备调低 spec |
| 纹理内存 15 MB | 低端机压力 | 低画质降尺寸/关 mipmap |
| 线宽 1 px | 辅助线细 | 改为三角形带（阶段 1） |
| Canvas 覆盖层非硬件合成 | 大菜单重绘时掉帧 | 菜单静态，仅交互时 invalidate |
| 无 GPU 错误上报 | 线上黑屏难诊断 | debug 构建保留 `checkError`；考虑加帧计数 |

---

## 12. 结论

| 验收项 | 状态 |
|---|---|
| OpenGL ES 3.0 可行 | 是；manifest 强制、8 个 shader 编译通过、代码只用 ES3 核心特性 |
| 动态光照 | 是；单点光 + 台面光斑 + 球面 Blinn-Phong/Fresnel |
| 阴影 | 是（接触阴影）；不是 shadow mapping，需在文档/产品中明确 |
| 纹理管理 | 是；程序化生成、mipmap、上传后回收 Bitmap、上下文重建可恢复 |
| 球体渲染 | 是；UV 球 + 四元数旋转 + 程序化号码纹理 |
| 帧率 | **未验证**；无真机，CI 仅 SwiftShader |
| 目标设备可行性 | **条件通过**；阶段 1 必须补真机 FPS/发热/折叠屏测试 |

**结论：渲染方案可执行，允许进入阶段 1；但“目标设备帧率”是未关闭的门禁项，不能宣称已完全验证。**
