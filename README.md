# Pentax K-1 Sync v7.5.1

专为 **PENTAX K-1（一代）** 打造的原生 Android 图传、无线遥控与 SpektraLab 81 波长物理暗房客户端（Kotlin + Jetpack Compose + C++20 JNI + Vulkan Compute + LibRaw 0.22.2）。

## v7.0.0 全部新增与暗房深度修复
1. **SpektraLab 暗房 4 项底层参数修复**：
   - 补齐 `filter_shift_scale = 40.0`，彻底修复放大机品红/绿与黄/蓝滤色滑块调节无明显变化的问题（实测 `±40 CC` 立竿见影）；
   - 相纸曝光改为符合直觉的 **相纸亮度 (`-2.5~+2.5 EV`)**；
   - 调整印相层参数时仅下发印相增量 JSON，真正命中底片缓存实现 ~10ms 毫秒级 `spk_reprint`；
   - 单图物理漏光锁定 `leak_rate = 1.0`，DIR 成色剂抑制锁定在 `0..1.6` 安全物理区间。
2. **暗房后期与人像/人文增强**：
   - **黑白物理色镜**：选择黑白胶片时自动浮现 6 款 81 波长柯达 Wratten 物理滤光片（黄 #8 / 深黄 #12 / 橙 #21 / 红 #25 / 黄绿 #11 / 绿 #58）；
   - **后置 6 项光影与人像肤色精修**：高光、阴影、对比度、全局饱和度、橙色肤色明度与肤色饱和度；
   - **发光复古红字日期 (`'26 11 24`)** 与 **IPTC 摄影师/版权元数据** 自动写入导出 EXIF。
3. **风光 / 星空 / 遥控专业工具套件**：
   - **暗夜红光护眼模式 (`暗夜红`)**：650nm 深红滤色保护星空暗适应；
   - **音量键 / 蓝牙快门盲控** + **Live View 社交画幅遮罩 (`3:4` / `4:5` / `2.35:1`)**；
   - **自定义多档包围曝光 (AEB)**：支持 3/5/7 张与 `±0.7 / ±1.0 / ±1.5 / ±2.0 EV` 自动连拍并恢复原 EV；
   - **四合一离线光学计算器**：ND 减光、K-1 NPF 星空防拖尾、全画幅超焦距/景深（支持一键同步机身光圈）、纯离线 NOAA 日出/黄金时刻/蓝调/天文黑夜计算。
4. **组图叙事出片 & 传图增强**：
   - 图库多选新增 **2.35:1 电影多格拼图** 与 **35mm 齿孔编号暗房接触印相表 (Contact Sheet)**；
   - 后台传输队列新增 **实时 MB/s 速率、预计剩余时间 (ETA)** 与 **断流自动 2 次重试**。

## v7.0.1 深度验证与稳定性修复
- **暗夜红光护眼模式重构**：改为离屏 `ColorMatrix` 色彩滤镜层（纯红通道亮度映射、零绿蓝光发射），彻底避免部分安卓 HWUI 机型出现纯红不透明遮挡，同时确保绿色按钮与取景画面清晰可辨。
- **自定义包围曝光 (AEB) 加固**：开拍前按机身实际支持的 `xvList` 自动去重并展示实际档位（绝不拍重复废片）；中途取消或结束时通过 `NonCancellable` 强制恢复机身原始 EV。
- **暗房换卷/换相纸中性滤色隔离**：先前点过「求滤色」后，切换胶片卷、相纸或暗房配方自动重建干净会话，避免残留旧胶片的中性滤色包导致偏色。
- **B 门光学计算器可自由收起**：切入 B 门时自动展开一次，展开/收起按钮随时响应。
- **个人配置备份补全**：JSON 备份与恢复同步覆盖 IPTC 版权声明、复古红字日期开关、音量键快门开关及 K-1 热点密码。

## v7.0.3 遥控实时取景、触屏对焦与快门真机协议修复
- **修复点击屏幕不自动对焦**：`POST /v1/lens/focus` 改用 `addEncoded("pos", "X,Y")` 保持原始逗号不被转义为 `%2C`，并如实校验相机返回的 `errCode` 与 `focused` 字段。
- **修复点击拍照没拍却提示已拍**：`executeLogged()` 新增 Ricoh/Pentax JSON `errCode` 全局校验，`triggerShutter()` 校验 `captured` / `focused` 布尔状态（`af=off` 被拒时自动降级重试默认快门），绝不虚报「快门已释放」。
- **修复 Live View 一直转圈与重连提示取景中断**：新增 `closeActiveLiveViewSocket()` 秒关阻塞旧连接防止占坑、改用 `Connection: keep-alive`、自动探测 `/v1/liveview` 与 `/v1/display` 双接口，并加入 multipart `Content-Length` 极速切帧与容错恢复。

## v7.0.4 真机快门异步回执适配
- **修复快门已释放但误报未成功**：实机抓包确认 K-1 接受快门后会在 ~28ms 内异步返回 `errCode=200, captured=false`（真正拒拍返回 `errCode=412/503`），现调整校验规则仅在 `errCode!=200` 或请求自动对焦却 `focused=false` 时报错。
- **适配 K-1 真实有效对焦区**：按机身返回的 `focusEffectiveArea: [70, 64]`，将触屏对焦坐标自动限定在 `X: 15..85 / Y: 18..82` 有效区域内，边缘点选更稳。
- **日志版本号同步**：通信抓包日志头更新为 `v7.0.4`，并优化正常退出取景时的日志记录。

## v7.0.5 常驻实时图库同步 & 拍完即传 (Tether) 全链路修复
- **全场景图库实时预览**：连接 K-1 Wi-Fi 后自动启动「`/v1/changes` 自动重连 WebSocket + 2.5s 静默增量轮询 + 遥控拍后双探针」三合一同步引擎；无论通过手机遥控拍照，还是直接按 K-1 机身实体快门拍照，新照片都会在 1～3 秒内自动出现在图库顶部并预加载缩略图与预览图，且无闪屏、不丢 EXIF。
- **拍完即传 (Tether) 真正生效**：修复此前真机遥控快门不触发同步、WebSocket 未识别 `storages/remain/captured` 事件及开启顺序依赖问题；新增基准线保护（不误传卡里旧图）与 RAW+JPG 自动去重，并在图库页顶部新增 **`即传`** 快捷开关。

## v7.1.0 PS 级分区混色调色工作台（彻底告别整图蒙色膜）
- **独立「PS混色」工作台 + ~20ms 实时预览缓存**：3D LUT 与 SpektraLab 81 波长胶片均可叠加 PS 混色，拖动滑块复用预渲染画布无需重复跑 Vulkan。
- **PS 三区色彩平衡（阴影 / 中间调 / 高光）**：三区独立 `青↔红 / 洋红↔绿 / 黄↔蓝` 调节，内置 W3C/PS 精确 `Preserve Luminosity (保持明度)` 与纯黑/纸白端点锚定，绝不把暗部染灰、不把高光染脏。
- **PS 混合模式调色层 + 混合颜色带 (Blend-If)**：支持 **柔光 (Soft Light) / 颜色 (Color) / 叠加 (Overlay) / 滤色 (Screen) / 正片叠底 (Multiply)** 5 种精确混合公式，可按 `全图护黑白 / 仅暗部 / 仅中间调 / 仅高光` 定向混色。
- **7 套一键 PS 风格预设 + 自然饱和度 (Vibrance) + 3 域 HSL**：内置青橙电影、日系清透、柯达暖金、富士青绿、王家卫暗调、通透人像等预设；新增肤色保护型自然饱和度与人像橙肤/植被绿/天空蓝局部精修；暗房「存为配方」自动保存并恢复 PS 混色参数；物理放大机光源校正精调至 `±25 CC`。

## v7.2.0 底层流畅度加固 + 专业修图手感 & 老镜头工作流升级
- **底层稳定性三件套**：
  - `PentaxK1ApiClient` 按当前 Wi-Fi 网络复用共享 `OkHttpClient` 连接池与调度器；
  - 图库缩略图引入 `Semaphore(3)` 并发限流与在途去重，内存缩略图（180 张）与预览图（12 张）LRU 上限防 OOM；
  - 动态屏幕常亮锁（`FLAG_KEEP_SCREEN_ON`），在 Live View、B 门、延时、AEB、传图和批量出片期间自动保持亮屏防断流。
- **后期调色手感升级**：
  - 后期顶栏新增 **25 步多级撤销 / 重做（Undo / Redo）**，滑块连续拖动自动合并；
  - 全部暗房与 PS 混色滑块支持 **点击数值单滑块精准归零（↺）**（同时修复 v7.1.0 `DarkroomSliderCard` 参数名对齐）；
  - **Live View 与后期暗房双端实时 RGB 直方图浮窗**；
  - `PS混色` 收尾质感补齐 **清晰度（Clarity）**、**输出亮度锐化（零彩噪）** 与 **光学暗角（Vignette）**；
  - 全屏检视新增 **分屏拉杆前后对比（Before / After Wiper）**。
- **图库与老镜头效率工具**：
  - 图库顶部新增 **`未下`**（仅未下载）一键筛选芯片；
  - 批量出片进度条新增 **`取消`** 按钮随时安全中止；
  - 「镜头参数」新增 **宾得手动老镜头（K/M/A/M42）按焦段自动填 EXIF 镜头名**映射表，并同步接入全量配置备份与恢复。

## v7.3.0 专业曲线/白平衡/去雾 + 图库批量直出 & 架构拆分重构
- **后期调色补齐最后三块专业拼图（`PsColorGradingPanel.kt`）**：
  - **JPG / 全格式通用白平衡（色温 Temp & 色调 Tint）**：支持对所有 JPG、K-1 预览图与暗房后置画面进行保持明度的精准色温/色调校正；
  - **交互式 4 通道 5 点色调曲线（亮度 / R / G / B）**：采用单调保形 Fritsch-Carlson 三次样条（零过冲振铃），支持直接在画布上拖动黑场/阴影/中间调/高光/白场 5 个节点、双击单点归零与 4 套经典曲线预设（线性归零 / 胶片褪色 Matte / 电影 S 反差 / 日系柔亮）；
  - **大气去雾（Dehaze）**：基于暗通道透射率估计，支持向正方向通透去灰、向负方向营造柔和空气雾感（4 核并行、尺度不变）。
- **图库直通暗房批量出片 & 日期快跳（`GalleryScreen.kt`）**：
  - 图库多选悬浮底栏新增 **`批量暗房`** 按钮，直接拉取所选 K-1 照片并自动套用当前胶片 + PS 混色 + 曲线 + 水印直出至相册（带进度条与随时取消）；
  - 双卡包含多个拍摄日期时自动显示顶部 **横向日期快速跳转条**，点击一键平滑滚动至目标日期。
- **遥控实战手感与容错（`HapticFeedbackHelper.kt` & `MainViewModel.kt`）**：
  - 新增差异化触觉震动反馈（合焦轻脆短震、脱焦双震提醒、快门机械震感、B 门/延时/AEB 完成提示震动）；
  - Live View 遭遇偶发 Wi-Fi 波动断流时自动静默退避重连最多 3 次（期间保留最后一帧画面并顶部显示柔和重连提示）。
- **可维护性架构拆分**：将原超大文件拆分为 `PsColorGradingPanel.kt`、`StudioOverlays.kt`、`StudioHistoryManager.kt`、`CameraSyncCoordinator.kt` 与 `HapticFeedbackHelper.kt`，各模块职责清晰、易于持续迭代。

## v7.3.1 编译修复
- 恢复 `WatermarkScreen.kt` 末尾被截断的 4 个私有辅助组件（`WatermarkTemplateCard`、`TemplateWireframeThumbnail`、`StudioField`、`StudioToggleRow`）。
- 补齐 `StudioOverlays.kt` 拆分后遗漏的 `LazyRow` 与 `FilmCategory` 导入。

## v7.4.0 全面体检修复 + 后期直选 K-1 相机图库（20 项核心升级）
- **传输恢复满速 & 彻底根治图库莫名重刷**：
  - 下载升级为 `64KB` 缓冲 + `150ms` 节流回调 + 大文件 `Connection: close` 防复用僵死连接；`keepScreenOn` 与进度条解耦消除高频 UI 重算；
  - 下载、批量出片与 Live View 期间自动暂停后台目录轮询，空闲轮询降频为 `8s`；
  - `listAllPhotos()` 单卡超时自动保留该卡旧列表，绝不再用空列表误刷整库；新增传输失败单条/全部一键 `重试`。
- **后期直连 K-1 相机图库 & 上下无缝切图**：
  - 后期顶栏「选图 / RAW / 批量」在连接 K-1 时直接弹出应用内 **K-1 相机图库选图面板**（秒读缓存缩略图、支持星标筛选与一键切手机相册/本地文件）；
  - 编辑相机照片时预览区新增 `‹ 上一张 / 下一张 ›` 快捷切换。
- **暗房发色精准修正 & 36MP RAW 一致性**：
  - 「求滤色」升级为保持原厂发色的 **「自动曝光」** + 一键 **「原厂色」** 复位，告别灰世界偏色；「场景置位」限定 14-bit RAW 并在关闭时彻底重建干净会话；
  - 切换新照片自动清零上一张的求解曝光与中性滤色包；36MP RAW 导出亮度对齐预览、多带拼接自动关闭跨带漏光防接缝；全屏「分屏」拉杆对比实现 1:1 裁切与无边框像素级对齐；灯光片标注 `灯光片` 提示；预览下方新增「已叠加 PS混色」防呆提醒条。
- **底层生命周期、性能与手感全面加固**：
  - `PS混色` 拆分为 4 个防误触二级分页（`预设/基础`、`色调曲线`、`三区/混色`、`HSL/质感`）；
  - 监听 `ON_STOP / ON_RESUME` 切后台自动停 Live View 与空闲轮询；`WifiNetworkBinder` 加 `AtomicBoolean` 单次回调防重入；`TransferForegroundService` 适配 Android 12+ `stopService`；缩略图磁盘缓存自动上限清理；`CubeLutEngine` 升级 4 核并行；修复 K-1 `WBMode` 大小写、慢门分数显示、EXIF 分数解析、本地原文件名保留、大图/对比页实时星标同步，以及完整多级返回键导航栈。

## v7.4.1 极速传图引擎重构（专治 K-1 无线传输 200KB/s 慢速瓶颈）
- **裸 TCP 极速直连下载器 (`downloadPhotoFastRawTcp`)**：绕过 OkHttp 默认连接池与 Gzip 协商，直接配置 `512KB SO_RCVBUF` 接收窗口 + `TCP_NODELAY` + `128KB` 零拷贝定长直读，并在抓包日志精确打印每张图真实 `MB/s`。
- **Android `WifiLock` 高性能射频锁 + `PARTIAL_WAKE_LOCK`**：下载期间主动锁定 `WIFI_MODE_FULL_LOW_LATENCY / WIFI_MODE_FULL_HIGH_PERF`，防止安卓对无外网相机热点触发 Wi-Fi PSM 休眠降速。
- **传图独占通道防抢占**：下载原图/RAW 或批量暗房期间自动暂停 `/v1/changes` WebSocket（消除 15s 心跳与 4s 重连打断）、排空闲置连接池、暂停图库网络缩略图请求，并默认开启「极速传图 · 独占 Wi-Fi 射频」（下载时临时暂停 5G 保活防天线时间片抢占，传完秒恢复双网并发）。
- **下载与落盘/水印异步流水线**：下载完成后交由后台异步协程写入相册与渲染水印，Wi-Fi 立刻开拉下一张照片不再空等。

## v7.4.2 根治 36.4MP 成片导出闪退（全管线行带流式超低内存重构）
- **`PostDarkroomAdjuster` & `CubeLutEngine` 全面改为 256 行水平条带流式并行**：36.4MP（7360×4912）全分辨率导出时，Java 堆峰值从 **289 MB 骤降至 ≤15 MB**（降幅达 19～36 倍），彻底根治 `OutOfMemoryError` 闪退。
- **`spektra_jni.cpp` 36MP JPG 分带流式直读**：`nativeRenderBitmapFullTiled` 改为逐带转换锁定像素（每带 ≤66 MB），省去 **368 MB** 原生整图浮点缓冲。
- **`MediaStoreSaver` 64KB 零拷贝流式落盘 + `android:largeHeap="true"`**：不再将 30MB JPEG 整文件 `readBytes()` 读回堆内存；水印阴影与发光日期模糊半径针对 36MP 做 Skia 安全限幅。
- **导出/分享全链路异常兜底与内存释放**：导出前主动释放实时预览 Vulkan 会话；全链路加 `try/catch(OutOfMemoryError/Throwable)` 兜底，遇到极端低内存自动降级 4096px 保护导出，绝不闪退。

## v7.4.3 JPG 高光通透度优化 & 负片底片直扫开关
- **JPG 高光区保色相逆色调映射（`spektra_jni.cpp`）**：针对 sRGB JPG 输入，仅在 `sRGB > 195`（线性亮度 `> 0.55`）的高光区域施加 C1 平滑单调高光余量扩展（最高 `+1.68 EV`），暗部、中间调与人像肤色 100% 零改动、RGB 色相比例严格不变；14-bit RAW 完全不经过此转换，保持原始线性传感器数据。
- **新增「底片直扫 (通透)」一键切换（`WatermarkScreen.kt`）**：为彩色与黑白负片开放 `底片直扫` 选项，一键绕过光学相纸 D-min 压白，呈现如哈苏/诺日士扫描仪般明亮通透的高光层次。

## v7.4.4 编译错误修复 & 临时 RAW 缓存自动/手动全清理
- **彻底修复 3 处编译报错**：
  - 修复 `PentaxK1ApiClient.kt` 中 `downloadPhotoFastRawTcp` 请求头字符串意外换行语法错误；
  - 修复 `PostDarkroomAdjuster.kt` 嵌套函数捕获可变 `var smoothDark / lowLuma` 导致 Kotlin Smart-Cast 编译失败；
  - 修复 `MainViewModel.kt` 中 `exportWatermarkedPhoto / shareWatermarkedPhoto` 的 `var finalBitmap` 跨协程 Smart-Cast 编译错误。
- **临时 RAW 缓存全生命周期清理**：
  - 暗房修图拉取的临时 RAW 改为**单槽即时覆盖**（切到下一张 RAW 或切回 JPG 立刻删掉前一张临时 RAW，不再滞留 3 张）；
  - App 启动时与退出时（`init` / `onCleared`）自动清空 `cacheDir/raw_studio` 残留临时 RAW；
  - 「机身」页 **「清理缩略图与临时 RAW 缓存」** 实时统计缩略图 + 临时 RAW + 分享缓存总占用，点击一键全清；同时补齐「极速传图 · 独占 Wi-Fi 射频」开关 UI。

## v7.5.0 手机端全链路性能与传输引擎深度调优
- **Wi-Fi 射频与进程绑定调优（`WifiNetworkBinder.kt`）**：
  - 将 `WifiLock` 从可能关闭 802.11n AMPDU 帧聚合的 `WIFI_MODE_FULL_LOW_LATENCY` 切回专攻大文件吞吐的 **`WIFI_MODE_FULL_HIGH_PERF`**；
  - 开启「极速传图 · 独占 Wi-Fi 射频」时，传输期间仅对当前 App 进程临时执行 `bindProcessToNetwork(wifiNet)`（传完立刻恢复 `null`，不影响微信等其他 App），防止国产 ROM 将无外网 `wlan0` 压进低占空比副网调度；
  - 新增实时无线链路遥测（`2.4GHz · Mbps · dBm`），直接显示在「机身」页连接状态栏。
- **零堆内存流式直写 + 64KB 标准无缩放 Keep-Alive TCP（`PentaxK1ApiClient.kt` + `MediaStoreSaver.kt`）**：
  - 取消可能触发 K-1 2016 年嵌入式 RTOS Window Scaling 兼容降速的 `512KB SO_RCVBUF`，改用干净的 `64KB` 标准接收窗 + 批量 **HTTP/1.1 Keep-Alive 连接复用** + 传输线程 `THREAD_PRIORITY_DISPLAY` 提权；
  - 常规 JPG/RAW 下载与暗房拉取 RAW 全面改为 **64KB 缓冲直接流式落盘至 MediaStore / `raw_studio`**，彻底消除 45MB 大数组分配与下载中途 ART GC 停顿；
  - 「机身」页新增 **「传输引擎：系统 OkHttp 兼容模式」** 一键切换，方便在当前机型上直接对照测速。
- **图库与取景器手机端流畅度提速**：
  - 缩略图与无辅助叠加的 Live View 帧全面改用 **`RGB_565`** 解码（内存减半、GPU 上传翻倍顺滑），图库内存缩略图 LRU 上限扩容至 320 张；
  - 修复传输期间滑动图库读取磁盘缩略图缓存键不匹配的漏命中问题。

## v7.5.1 编译修复
- 修复 `PentaxK1ApiClient.kt` 中 `streamLiveView()` 函数签名遗漏 `preferRgb565` 参数导致的 Kotlin 编译错误。
- 补齐 `SyncScreen.kt` 连接状态栏的实时 Wi-Fi 链路遥测展示（频段 / Mbps / dBm），并让批量暗房与原分辨率导出拉图同步遵循所选传输引擎模式。
