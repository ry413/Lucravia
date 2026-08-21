# Architecture

## Runtime flow

`Flutter UI -> location + MediaProjection service -> region crop -> StableFrameGate -> LAN JPEG + GPS -> DashScope extraction -> Amap POI resolution + two driving routes -> effective hourly income -> screen-coordinate overlay`

## Flutter layer

- `AnalyzerHomeScreen` 显示局域网服务地址、权限、持久化扫描区域、启停分析和最近一次结果，不接触 DashScope Key。
- `ScreenAnalyzerPlatform` 封装 MethodChannel 和 EventChannel，避免 UI 了解 Android Intent 或 Service。

## Android layer

- `MainActivity` 请求悬浮窗权限和 MediaProjection 单次会话授权。
- `MainActivity` 在启动捕获前取得两分钟内的位置；`ScreenCaptureService` 以 `mediaProjection|location` 前台服务类型继续监听 GPS/网络位置，每次新画面使用当时的最新 WGS84 坐标。
- `MainActivity` 先显示只占一小块屏幕的可触摸校准启动窗，屏幕其余位置仍可操作；用户打开目标 App 并点击“开始校准”后，才切换为全屏可触摸浮层来拖动上下绿线和保存/取消。`ScanRegionPreferences` 以屏幕归一化比例持久化选择，未配置时为完整屏幕。
- `ScreenCaptureService` 是 `mediaProjection` 类型前台服务。它约每 450 ms 取一帧，先裁出保存的全宽纵向区域，再缩放到最大 720 px 宽，并确保每台设备同时最多一个 VLM 请求。
- `FrameChangeDetector` 为实际裁剪区域生成 64×64 亮度指纹；屏幕顶部 18% 和底部 8% 与选区相交的部分仍被排除，以忽略目标 App 倒计时和底部导航。
- `StableFrameGate` 要求连续两帧稳定，只允许与上次提交明显不同的画面进入 VLM；失败画面最早 3 秒后重试。
- `VlmServerClient` 以质量 72 编码 JPEG，将原始字节 POST 到编译期配置的 `VLM_SERVER_URL/v1/analyze`，并通过请求头报告图片尺寸、扫描比例、手机编码耗时和客户端请求 ID。`RequestSigner` 以编译期 `SHARED_SECRET` 对方法、路径、时间戳、请求 ID 和 SHA-256 正文摘要做 HMAC-SHA256 签名。开发协议仍允许明文 HTTP。
- 云端分析在途时，捕获线程仍以相同指纹监测订单区域。检测到变化便取消当前 `HttpURLConnection`、释放客户端分析槽位，并异步调用 `/v1/cancel`；双工作线程允许新稳定页面不必排在正在退出的旧连接后面。只有仍持有 active call 身份的请求可以更新 UI。
- `VlmOrderResponseParser` 校验订单数值范围、归一化卡片框、`is_fully_visible` 和空的 `occluded_fields`。完整订单还必须同时具有起终点可见原文；地址规范化不得产生过大的长度变化。残缺卡片保留用于诊断，但不会参与排序和提醒。
- 原生悬浮窗由 Service 直接更新，因此切换到司机端后不依赖 Flutter Activity 保持在前台。
- `AnalyzerStatusOverlay` 是固定小尺寸、可拖动的运行状态胶囊，仅自身窗口消费触摸。它报告等待稳定、VLM 请求、结果与错误阶段；`OverlaySignatureMask` 将其当前物理屏幕矩形换算到裁剪后的 64×64 指纹并整块屏蔽，避免文字更新自触发。主动拖动后允许稳定闸门重新确认画面。
- 最多三张完整订单有可靠卡片框时，`OrderHighlightOverlay` 将 VLM 选区内的归一化坐标映射回物理屏幕，以绿 / 橙 / 蓝窄框描边，并在各自顶部附上不可触摸的完整中文评价窗。`OrderHighlightLabelFormatter` 分行解释排行、预计毛时薪、三段耗时、三项里程、两段红绿灯、拥堵/严重拥堵和道路收费，不使用“空/载/灯/堵/严/费”式缩写；同时触发短提示音和振动。`HighlightFrameGuard` 按 360×124dp 最大评价窗精确屏蔽标签及框线像素并监测其余页面变化；滚动或刷新后先撤框、重置稳定帧。
- 运行时不显示大型结果悬浮窗；仅保留可拖动的小状态胶囊和卡片评价框。所有窗口都不使用 `FLAG_SECURE`，因此不会阻止用户截取当前屏幕。

## Data and safety boundaries

- Python `ThreadingHTTPServer` 提供公开且无云端成本的 `GET /health`，以及必须通过 `SharedSecretAuthenticator` 的 `POST /v1/analyze` 和 `POST /v1/cancel`。服务端先验证正文摘要、±5 分钟时间窗和请求 ID 重放，通过后才把 JPEG 转为 Base64，以 JSON Mode 调用 DashScope，并将视觉输入限制为最多 1,310,720 像素（1280 视觉 Token）。取消状态按请求 ID 跨 handler 共享；已发往 DashScope 的非流式推理不保证能在云端立刻终止，但返回后会立即丢弃，且不会继续调用高德或回传旧结果，新请求可由另一服务端线程并行开始。
- `AmapOrderEnricher` 将 WGS84 GPS 转为高德坐标，优先直接地理编码 VLM 地址，失败时才取附近 POI 首个结果。Mock 阶段不依据平台公里数消歧或拒绝路线。对每张完整订单调用两次路径规划 2.0（策略 32、`show_fields=cost,tmcs`），分别保留道路里程、耗时、畅通/缓行/拥堵/严重拥堵分段、红绿灯、收费金额、收费里程和主要收费道路。不同订单可并行推进，但 `AmapClient` 按服务类别将真实请求间隔限制为至少 380 ms，以适配个人认证默认 3 QPS；10021 另做最多两次退避重试。
- 服务端把高德接驾秒数、固定 3 分钟等客、高德载客秒数相加，以原始秒数计算有效时薪；UI 分钟数单独向上取整。高德故障只令订单 `route_status=route_failed`，不丢弃 VLM 结构。
- 服务端日志输出终端与 5 MB 滚动文件，记录请求 ID、客户端 IP、字节数、DashScope 与高德分段耗时、成功算路数、Token、DashScope 请求 ID 与完整订单 JSON；不记录图片、精确 GPS 或 API Key。
- 屏幕 Bitmap/JPEG 只在内存中存活到单次请求完成；客户端与服务端均不保存图片。
- Android Gradle 把根目录 `.env` 的 `VLM_SERVER_URL` 和测试期 `SHARED_SECRET` 编译进 APK。DashScope 与高德 Key 仅由 Python 服务运行时读取。签名阻止机会性未授权调用，但不提供传输加密，也无法防止从 APK 提取共享密钥。
- 只读取屏幕和显示结果；没有 Accessibility Service、输入注入、Hook 或网约车私有 API。
- Android 14+ 的 MediaProjection Intent 不会被缓存或复用，每次会话都经过系统授权。
- 只有 `route_status=ok` 的高德路线结果才能显示有效时薪；不得把地点或路线失败的订单回退为平均速度估算。
