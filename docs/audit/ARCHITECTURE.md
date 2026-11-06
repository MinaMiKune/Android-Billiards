# 应用架构设计（阶段 0）

- 基线：`6664c91`
- 目标：说明现有实现的实际架构、线程边界、生命周期策略和可扩展点；不是重新设计。
- 结论：**单 Activity + 三线程 + 纯 Kotlin 规则核心**的架构成立，无需推倒重来。

---

## 1. 总览

```text
┌─────────────────────────────────────────────────────────────────────┐
│ MainActivity（主线程 / UI）                                          │
│  ├─ FrameLayout                                                      │
│  │   ├─ GLSurfaceView (index 0) ── GL 线程 ──> TableRenderer         │
│  │   ├─ GameHudView (Canvas, 覆盖层)                                  │
│  │   └─ MenuView   (Canvas, 覆盖层)                                  │
│  ├─ Prefs / SoundBank / Haptics / SaveStore                          │
│  └─ 输入、菜单动作、生命周期、insets、沉浸式                          │
└───────────────┬─────────────────────────────────────────────────────┘
                │ 命令队列（线程安全）
                ▼
┌─────────────────────────────────────────────────────────────────────┐
│ GameLoop（"billiards-sim" 线程，NORM+1）                              │
│  ├─ 固定 1/480 s 累加器                                               │
│  ├─ MatchEngine.update(STEP)  ──> PhysicsWorld                       │
│  ├─ 每 0.075 s 发布 MatchSnapshot                                    │
│  └─ 反馈事件 ──runOnUiThread──> SoundBank / Haptics                  │
└───────────────┬─────────────────────────────────────────────────────┘
                │ synchronized(engine) 快照
                ▼
┌─────────────────────────────────────────────────────────────────────┐
│ TableRenderer（GL 线程）                                              │
│  ├─ copyState：只读快照 → 原始数组                                    │
│  ├─ 相机/视口/光照/绘制                                               │
│  └─ screenToTable：触摸反投影到物理平面                                │
└─────────────────────────────────────────────────────────────────────┘
```

`core` 不依赖 Android；`app` 只做平台适配。物理状态是唯一事实来源，渲染和 UI 都是只读消费者。

---

## 2. 模块职责

### 2.1 `:core`

| 类/对象 | 职责 | 关键约束 |
|---|---|---|
| `Vec2` / `Vec3` / `Quat` | 数学基元 | 纯 Kotlin，无分配优化以外的平台依赖 |
| `Ball` / `TableSpec` / `Rack` | 球状态、台面常量、确定性球架 | `TableSpec` 是唯一参数来源 |
| `PhysicsWorld` | 固定步长积分、碰撞、袋口、睡眠、事件、快照 | `step(dt)` 要求 `0 < dt <= 1/60` |
| `TableSpace` | 桌子坐标 ↔ 世界坐标、朝向映射 | 横竖屏唯一坐标真源 |
| `MatchEngine` | 8 球规则、练习模式、AI 调度、快照/恢复 | 只通过 `PhysicsWorld` 公共 API 推进 |
| `BilliardsAi` | 几何选球/选袋/瞄准误差 | 无 Android 依赖，确定性种子 |

### 2.2 `:app`

| 类 | 职责 |
|---|---|
| `MainActivity` | 单 Activity；窗口、insets、沉浸式、生命周期；把 UI 动作翻译成 `GameLoop` 命令 |
| `GameLoop` | 独立模拟线程；固定步长；命令队列；快照发布；反馈转发 |
| `TableRenderer` | GLES 3.0 渲染；相机；纹理/Mesh；触摸反投影 |
| `GameHudView` | 力度、杆法圆盘、击球/暂停/快进/自由球；Canvas 绘制 |
| `MenuView` | 主菜单、设置、帮助、暂停、结算；Canvas 绘制 |
| `SoundBank` | `SoundPool` 9 个音效；事件映射、音量/音高、冷却 |
| `Haptics` | 事件分级振动；振幅能力探测；强度缩放；冷却 |
| `Prefs` | 设置与统计（`SharedPreferences`） |
| `SaveStore` | 单局版本化 JSON 存档 |

---

## 3. 线程模型

| 线程 | 拥有者 | 允许做的事 | 禁止做的事 |
|---|---|---|---|
| 主线程 | Android | View/Window、输入、菜单、`SoundPool`/`Vibrator` 调用、`runOnUiThread` 回调 | 直接改 `Ball`/`PhysicsWorld` |
| `billiards-sim` | `GameLoop` | `MatchEngine.update`、命令执行、快照、反馈收集 | 触碰 View/GL |
| GL 线程 | `GLSurfaceView` | 读快照、绘制、相机、`screenToTable` | 改物理、改 UI View |

### 3.1 同步规则

1. 所有物理读写都在 `synchronized(engine)` 内；UI 只提交命令，不直接调用 `MatchEngine` 的变更方法。
2. `GameLoop.commands` 是 `ConcurrentLinkedQueue<(MatchEngine) -> Unit>`；命令在模拟线程的步进循环内、持锁执行。
3. 快照每 0.075 s 生成一次；`TableRenderer.copyState` 在锁内复制到原始数组，绘制期间不再持锁。
4. 反馈事件每步 `drainFeedback()`；通过 `runOnUiThread` 批量送到主线程，带 `generation` 代际保护，避免旧局回调污染新局。
5. `GameLoop.stop()` 先清运行标志、`interrupt()`，再 `join(400)`；`loop(self)` 用线程身份检查防止旧线程复活。

### 3.2 为什么不用协程/Handler 驱动模拟

- 物理需要**固定步长**与**稳定优先级**；独立线程 + 累加器比主线程 `Choreographer` 更容易保证确定性。
- 模拟线程不依赖 UI 帧率；即使渲染掉帧，物理仍按 480 Hz 推进（受 140 步/帧上限保护）。
- 代价是线程生命周期和锁边界必须严格；当前实现已用命令队列/快照隔离。

### 3.3 帧预算

| 项 | 数值 |
|---|---|
| 物理步长 | `1/480 s` |
| 每渲染帧最多物理步数 | 140 |
| 每帧最大推进 | `140/480 = 0.2917 s` |
| 快照周期 | 0.075 s |
| 加速 | 用户快进 4×；低速尾段自动 ≥2.6× |
| 桌面 JVM 实测 | 4.5–19.8 µs/步（4800 步 21.5–94.9 ms，3 次） |

按 60 fps、每帧 8 步估算，物理 CPU 约 36–158 µs/帧；相对 16.6 ms 预算很小。真机需重新测量。

---

## 4. 游戏状态机

```text
                 ┌──────────────┐
     新局/重开 ──>│   AIMING     │<──────────────┐
                 └──────┬───────┘               │
                        │ strike()              │ 合法击球结束
                        ▼                       │
                 ┌──────────────┐               │
                 │  SIMULATING  │───────────────┤
                 └──────┬───────┘               │
                        │ 犯规/白球落袋          │
                        ▼                       │
                 ┌──────────────┐   placeCue   │
                 │ BALL_IN_HAND │──────────────┘
                 └──────┬───────┘
                        │ 轮到 AI
                        ▼
                 ┌──────────────┐
                 │ AI_THINKING  │──runAiTurn──> SIMULATING
                 └──────┬───────┘
                        │ 8 号合法落袋 / 判负
                        ▼
                 ┌──────────────┐
                 │  GAME_OVER   │  (终态)
                 └──────────────┘
```

实现要点：

- `MatchEngine.state` 是唯一状态源；`GameLoop` 只读它决定是否加速。
- `AIMING`/`BALL_IN_HAND` 是**稳定态**，可自动存档；`SIMULATING`/`AI_THINKING` 不是。
- `MatchEngine.update(dt)` 在 `GAME_OVER` 直接返回；`SIMULATING` 才推进物理。
- AI 回合有 0.9 s 延迟，给 UI 与玩家可读性；AI 击球也走 `PhysicsWorld.strikeCue`，不绕过物理。

---

## 5. 生命周期

### 5.1 创建

```text
onCreate
  ├─ Prefs / SoundBank / Haptics / SaveStore
  ├─ 黑色窗口背景 + FLAG_KEEP_SCREEN_ON
  ├─ FrameLayout + GameHudView + MenuView
  ├─ demoEngine = MatchEngine(PRACTICE, startsWithBreak=false)
  ├─ installSurface(demoEngine)
  ├─ insets listener（API 30+ systemBars+displayCutout；旧版 systemWindowInset*）
  ├─ API 33+ 注册 OnBackInvokedCallback
  └─ 主菜单（有存档时显示“继续”）
```

### 5.2 暂停/恢复

```text
onPause
  ├─ loop.pause()                 // 模拟线程停止推进，lastNanos 归零
  ├─ saveBeforeLeaving()          // 稳定态直接存；否则存最近稳定快照
  ├─ 若在对局：显示暂停菜单
  └─ surface.onPause()            // GL 线程暂停；preserveEGLContextOnPause=true

onResume
  ├─ surface.onResume()
  ├─ applyImmersive()
  └─ 若对局中且菜单不可见：打开暂停菜单
```

设计取舍：

- 不在 `onPause` 里继续模拟；避免后台耗电与状态不确定。
- 中途退出只保证“最近稳定局面”，不保证逐帧精确恢复；这是可接受的存档语义。
- `preserveEGLContextOnPause` 只是尽力而为；`onSurfaceCreated` 必须能处理上下文丢失（已清 uniform 缓存并重建程序/纹理）。

### 5.3 配置变化

Manifest 声明了 `orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden|uiMode|density`，因此旋转/折叠/分屏**不重建 Activity**：

```text
onConfigurationChanged
  ├─ applyImmersive()
  ├─ root.requestApplyInsets()
  └─ hud.invalidate() / menu.invalidate()
```

`GLSurfaceView` 尺寸变化会触发 `onSurfaceChanged`，重设 viewport、宽高比、相机（二分适配），横竖屏通过 `TableSpace` 旋转桌面。

### 5.4 销毁

```text
onDestroy
  ├─ 移除所有主线程回调
  ├─ loop.stop()（interrupt + join 400 ms）
  ├─ loopGeneration++（使旧回调失效）
  ├─ sound.release()
  └─ super.onDestroy()
```

### 5.5 进程死亡

没有 `onSaveInstanceState` 的游戏状态恢复；依赖：

1. 稳定局面自动存档（`AIMING`/`BALL_IN_HAND`）。
2. 主菜单“继续”读取 `SaveStore`。
3. 若读到非稳定状态，`CONTINUE` 会把它修复为 `AIMING` 再恢复。

风险：进程在击球过程中被杀，会回到击球前的稳定局面，而不是恢复球在运动中的精确状态。这是产品取舍，不是缺陷；文档需明确。

---

## 6. 渲染与 UI 的边界

### 6.1 渲染只读

`TableRenderer` 持有 `@Volatile var engine`，但每帧只做：

```text
copyState(dt)
  └─ synchronized(engine) { engine.snapshot() }
     └─ 复制球位置/四元数/active/pocket、瞄准、力度、状态
```

渲染线程不调用任何 `MatchEngine` 变更方法。落袋动画（下坠/缩小/透明）是渲染侧视觉状态，不回写物理。

### 6.2 输入到物理

```text
触摸 (HUD)
  └─ screenToTable(px, py)
       ├─ NDC → invViewProj → 与 y=0 平面求交
       └─ TableSpace.worldToTable(wx, wz, portrait)
  └─ loop.submit { engine.setAim(...) / placeCueBall(...) / strike() }
```

HUD 只发命令；物理线程决定是否接受（例如非 `AIMING` 时 `strike()` 返回 false）。

### 6.3 Canvas 覆盖层

- `GameHudView` 与 `MenuView` 都是 `View` 子类，`onDraw` 全量绘制。
- `setSafeInsets(l,t,r,b)` 接收系统栏/刘海 inset；按钮和文字避开安全区。
- `MenuView` 按 `width > height` 选择横/竖布局；`onSizeChanged` 重新计算按钮矩形。
- 两个 View 都以 `MATCH_PARENT` 覆盖在 `GLSurfaceView` 之上；菜单可见时消费触摸。

---

## 7. 音频与触觉

### 7.1 音频

```text
PhysicsEvent (BallHit/CushionHit/Pocketed/CueStruck/Foul/MatchWon)
  └─ GameLoop 收集
     └─ runOnUiThread
        └─ SoundBank.play(event)
           ├─ SoundPool.play(soundId, volume, volume, 1, 0, rate)
           └─ 每个音效独立冷却（28–900 ms）
```

- `SoundPool` 10 路并发，`USAGE_GAME`/`SONIFICATION`。
- 无背景音乐、无后台播放；Android 17 后台音频加固不触发。
- 9 个 WAV 是程序化合成占位，不是采样录音。

### 7.2 触觉

```text
PhysicsEvent
  └─ Haptics.play(event)
     ├─ 开关 / hasVibrator 检查
     ├─ 事件分级：轻碰/重碰/库边/落袋/犯规/胜负
     ├─ 振幅 = f(impactSpeed) × prefs.hapticStrength，clamp 1..255
     ├─ 有振幅控制：createOneShot(ms, amp) / createWaveform(...)
     └─ 无振幅控制：DEFAULT_AMPLITUDE
```

- API 31+ `VibratorManager.defaultVibrator`；API 26–30 `VIBRATOR_SERVICE`。
- 所有调用 `try/catch`，厂商异常不影响游戏。
- 每个事件键独立冷却，防止开球/连续碰撞振动轰炸。

---

## 8. 存档

### 8.1 分层

| 存储 | 内容 | 生命周期 |
|---|---|---|
| `billiards` SharedPreferences | 声音/触觉/强度/画质/局数/胜场 | 长期 |
| `billiards_save` SharedPreferences | 单局 `match_v1` JSON | 单局；结束后清除 |

### 8.2 Schema（version 1）

```json
{
  "version": 1,
  "mode": "PRACTICE|VS_AI",
  "state": "AIMING|BALL_IN_HAND|SIMULATING|AI_THINKING|GAME_OVER",
  "current": 0,
  "inHand": false,
  "msg": "...",
  "winner": null,
  "shots": 3,
  "aim": [1.0, 0.0],
  "power": 0.55,
  "side": 0.0,
  "vertical": 0.0,
  "lastPocketed": null,
  "players": [{"name":"You","group":"SOLIDS","pocketed":2,"fouls":0}],
  "balls": [
    {"id":0,"pos":[x,y],"vel":[vx,vy],"omega":[wx,wy,wz],
     "q":[w,x,y,z],"active":true,"asleep":false,"pocket":-1}
  ]
}
```

校验：

- `version != 1` → 丢弃。
- `balls.length != 16` → 丢弃。
- 非法 enum / 缺字段 → `runCatching` 丢弃，回主菜单。
- 读档后非稳定状态 → 修复为 `AIMING`。

### 8.3 保存时机

| 时机 | 行为 |
|---|---|
| 进入 `AIMING`/`BALL_IN_HAND` | 自动保存；用 `state|player|shots|inHand` 去重 |
| 暂停/离开 | `saveBeforeLeaving()`；非稳定则保存最近稳定快照 |
| 对局结束 | 清除存档，更新局数/胜场 |
| 返回主菜单 | 保存后停止模拟线程 |

没有数据库、没有文件 IO、没有后台任务；存档失败被吞掉，不影响游戏。

---

## 9. 可扩展点

| 需求 | 扩展方式 | 影响面 |
|---|---|---|
| 更多规则（9 球/斯诺克） | 新增 `MatchEngine` 变体或策略接口；物理不变 | `core/game` |
| 回放/观战 | 记录 `Strike` + 初始快照；用同一 `PhysicsWorld` 重放 | `core`，无需渲染改动 |
| 难度选择 | `BilliardsAi` 的误差/搜索深度参数化 | `core/game` |
| 联机 | 需要网络权限、状态同步/确定性校验；当前无网络层 | 大，另行架构 |
| 无障碍 | 为 Canvas View 建 `AccessibilityNodeProvider` 或叠加不可见原生控件 | `ui` |
| 折叠屏姿态 | `WindowLayoutInfo`/`FoldingFeature` 驱动 HUD/菜单分区 | `MainActivity` + `ui` |
| 手柄/键盘 | `onKeyDown`/`onGenericMotionEvent` 映射到同一命令队列 | `MainActivity` |

---

## 10. 架构结论

| 问题 | 结论 |
|---|---|
| 是否需要多 Activity？ | 不需要。单 Activity + 覆盖层足够，避免 GL/状态在 Activity 间迁移 |
| 是否需要 ViewModel？ | 不需要。`GameLoop` + `MatchSnapshot` 已承担状态宿主；引入 Lifecycle 只会增加依赖 |
| 是否需要协程？ | 当前不需要。固定步长模拟线程更直接；未来异步 IO 可局部引入 |
| 线程边界是否清楚？ | 是。命令队列/快照/代际保护三件套齐全 |
| 最大架构风险 | 平台窗口形态（折叠/分屏）与无障碍，不是核心分层 |
