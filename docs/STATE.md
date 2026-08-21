# Current State

## Current goal

构建一个 Android Flutter 客户端与局域网 Python 服务：客户端读取司机端订单页和当前位置，服务端以 DashScope VLM 提取订单、用高德两段路线计算有效时薪，客户端在悬浮层显示有依据的价值分析，且绝不代替司机操作。

## Current system state

- Flutter 主界面是权限和分析控制台，不再模仿网约车订单大厅。
- 用户授权悬浮窗并确认系统屏幕捕获后，原生前台服务持续获取屏幕帧。
- 用户授予前台定位权限；启动前取得较新位置，分析服务运行时持续更新 GPS/网络位置。
- Android Gradle 从根目录 `.env` 读取 `VLM_SERVER_URL` 和测试期 `SHARED_SECRET` 并编译进 `BuildConfig`；DashScope/高德 Key 不进入 APK。
- 前台服务约每 450 ms 生成一次 64×64 亮度指纹，排除顶部 18% 与底部 8%；连续两帧稳定且与上次提交不同才发送截图。
- 校准先显示小型启动浮窗，让用户可正常打开目标 App；点击后才进入全屏拖动模式，并可在目标页面保存或取消。区域以归一化比例持久化；未校准时默认完整屏幕。
- 截图先裁出保存区域，再缩放到最大 720 px 宽并以质量 72 压成 JPEG，以原始字节 POST 到局域网 Python 服务 `/v1/analyze`；请求头记录实际尺寸、扫描比例和手机编码耗时。
- Python 服务从根目录 `.env` 读取 DashScope Key，将 JPEG 转 Base64，关闭思考并以 JSON Mode 调用 `qwen3.7-flash`。
- 服务端将 `max_pixels` 限为 1,310,720（1280 视觉 Token），避免后续设备尺寸变化意外增加视觉输入。
- 服务端提供公开无成本的 `/health`；两个 POST 端点必须通过 HMAC-SHA256 共享密钥签名、±5 分钟时间窗和重放检查，未授权请求在调用 DashScope/高德前返回 401。终端和滚动文件记录请求 ID、客户端 IP、图片大小、局域网接收/构造请求/DashScope 往返/JSON 解析分段耗时、Token、DashScope 请求 ID和完整订单 JSON。
- 同一设备逻辑上最多一个有效请求；静态页面不重复调用，失败画面最早 3 秒后重试。在途期间仍在本地监测订单区，变化后立即取消客户端连接、释放槽位并等待新页面稳定，不再等旧 VLM 返回才发现过期。
- 运行时不再显示固定大型结果窗；保留一个可拖动的小状态胶囊，显示等待稳定、识别中、结果和错误阶段。浮窗实时位置从画面指纹中屏蔽，且不使用 `FLAG_SECURE`，用户可以正常系统截屏。
- 服务端把 GPS 转为高德坐标，Mock 阶段直接地理编码地址、失败才取附近首个 POI，不使用 Mock 平台里程消歧；再用路径规划 2.0 策略 32 计算当前位置→上车点与上车点→目的地。有效时薪按两段原始秒数 + 固定 3 分钟等客计算；`cost,tmcs` 同时提供两段道路里程、路况分段、红绿灯和收费信息。
- 本屏多个完整订单按高德有效时薪排序；最多三张有可靠坐标的订单直接以绿 / 橙 / 蓝框定位。360×124dp 最大评价窗以完整中文逐行显示排行、预计毛时薪、接驾/等客/载客耗时、接驾/载客/总行驶里程、两段红绿灯、拥堵/严重拥堵及预计道路收费；Flutter 主界面还显示主要收费道路。
- 多个评价框和标签显示期间暂停新 VLM 提交，指纹守卫同时屏蔽全部框，只监测真实页面变化；滚动或刷新会撤框、重置稳定帧，再分析新画面。
- 通知栏提供明确的停止分析操作。

## Recently completed

- 删除了误解需求后实现的假订单大厅和 Demo 数据链路。
- 实现 Flutter/Android 双向通信、MediaProjection 授权、前台捕获服务、ImageReader 帧处理和原生悬浮窗。
- 删除了运行中的 ML Kit 与固定坐标 OCR 解析链路，改用 `qwen3.7-flash` 整屏理解。
- 实现零依赖 Python 局域网服务、DashScope JPEG Base64 请求、JSON Mode 输出和可观察调用日志。
- Android 从直连 DashScope 改为上传原始 JPEG 到局域网服务，API Key 不再编译进客户端。
- 实现稳定画面闸门：滚动中不调用、静态页面去重、网络失败退避。
- `.env` 同时提供服务端 DashScope Key和客户端构建用局域网 URL；Flutter 只显示服务地址是否已编译配置。
- VLM 提示词要求禁止跨卡片拼接，不清晰或残缺字段必须返回 `null`。
- 拆分 VLM 调用耗时日志，并补充完整 `orders_result` JSON；Android/Flutter 不再丢弃最高分之外的订单。
- 增加卡片坐标协议、坐标校验、屏幕序号提示、订单描边及提醒期间的防反馈状态。
- 根据真机重复请求日志修正浮层自反馈：排除目标页动态顶部区域，并让订单框使用普通半透明像素后由指纹守卫扩大屏蔽。
- 将 VLM 输入由最大 1080 px/JPEG 86 调整为 720 px/JPEG 72，并补充图片尺寸与客户端编码耗时日志，用于比较视觉 Token、准确率和延迟。
- 将收益排序行从容易与屏幕卡片位置混淆的“第 N 张”改为“推荐 / 备选 / 第三名”；原始屏幕序号仍保留在结构化数据中供定位使用。
- 增加严格完整性协议：关键数字任一位被挡、起终点存在无法可靠辨认的字符都会进入 `occluded_fields`，客户端要求显式可可靠读取、遮挡列表为空且六个字段齐全才参与提醒。
- 放宽地址边缘裁切判定：轻微裁到字形下沿但全部字符仍可可靠辨认时允许通过，字符缺失或含义不确定时仍视为遮挡；数字规则不放宽。
- 所有运行态提示均不使用 `FLAG_SECURE`，保持系统截屏能力。
- 增加扫描区域校准，并改为两阶段交互：小型启动窗不吞掉屏幕其余点击，进入目标 App 后再开始全屏拖动与保存/取消；真实裁图、稳定指纹和订单框坐标均使用同一 `ScanRegion`。
- 删除运行时大型状态悬浮窗；将最多三张订单的排行和时薪贴到各自绿 / 橙 / 蓝卡片框，并扩展防反馈守卫同时屏蔽全部评价框。
- 增加可拖动的小型运行状态胶囊，并按其实时屏幕位置动态屏蔽 64×64 指纹；状态文字更新不会额外调用 VLM。
- 接入高德 Web 服务：GPS 坐标转换、附近 POI/长途地理编码、基于平台里程的候选选择、两段驾车路线、里程一致性审计和有效时薪；高德失败时保留 VLM 订单并明确显示“待算路”。
- 根据真机日志修复高德 10021：多订单并行曾瞬间超过个人认证默认 3 QPS；现按服务类别至少间隔 380 ms，并对 QPS 错误做两次退避重试。
- 新增 Android 前台定位权限与持续位置更新；精确位置仅随局域网请求发送，Key 仍只在服务端。
- 增加地址原文与保守 normalized 结果；客户端禁止规范化结果大幅改变长度，异常时回退原文。
- 增加端到端过期请求取消：Android 为每次分析分配请求 ID，页面变化时立即断开旧连接并通过 `/v1/cancel` 通知服务端；服务端跳过已取消请求的高德阶段和结果回传，新稳定页面可并发开始分析，旧结果也不能覆盖新 UI。
- 扩展高德路线价值信息：真实 API 已验证能返回路线红绿灯、收费和 `tmcs` 路况分段；服务端分别聚合接驾/载客段的拥堵、严重拥堵和缓行里程，Android 评价窗与 Flutter 详情页同步展示，并扩大防反馈掩码覆盖评价标签。
- 将卡片评价从开发式缩写改为新用户可直接理解的完整中文六行文案，新增独立纯 Kotlin 格式化器与精确文本回归测试，并同步扩大评价窗及其防反馈区域。
- 初始化 `main` Git 仓库；首次提交排除 `.env`、服务端日志、构建缓存、APK、IDE 配置和签名文件，同时纳入 Gradle Wrapper 以支持新机器复现构建。README 记录远程仓库与 VPS clone/pull 流程及公网部署安全边界。
- 增加测试期共享密钥请求签名：Android 和 Python 使用同一 canonical request 与 HMAC-SHA256，正文、路径和请求 ID 均受保护；过期、伪造和重放请求被拒绝。Kotlin/Python 固定向量回归测试确保两端格式一致。

## In progress / next work

1. 配置真实局域网 IP 与 DashScope Key，在 Android 真机验证端到端延迟、日志和调用频率。
2. 用真实司机端地址验证同名 POI 消歧和平台/高德里程一致性阈值，观察 `amap_ms` 与调用配额。
3. 将固定 3 分钟等客和价值阈值开放为用户配置。
4. 根据真机日志校准稳定画面阈值、JPEG 分辨率和失败退避。
5. 真机检查 `qwen3.7-flash` 的卡片框偏差，必要时再增加固定 UI 的边缘吸附校正。

## Known issues

- Mock 地址可能被地理编码到同名错误地点；当前刻意不做严格消歧，Flutter 展示实际匹配名称供人工核对。恢复真实业务数据后需要重新启用候选审计。
- 等客时间当前固定 3 分钟，尚未按城市、时段或用户习惯配置。
- VLM 提示词刻意只支持目标司机端当前 UI；不承诺支持其他平台或后续 UI 版本。
- 稳定的新订单截图会经过局域网服务上传至 DashScope；服务端不保存图片。
- 当前 HTTP 服务已有测试期共享密钥验签，可拦截不知密钥的公网扫描与云 API 刷请求；但仍没有 TLS、限流或可撤销的设备身份。密钥可从 APK 提取，而且明文 HTTP 会暴露截图和定位；公网测试仍应配 HTTPS。
- 自动化环境没有连接 Android 真机或模拟器；真实 DashScope、高德接口及 MediaProjection/悬浮窗行为依赖用户真机回归。
- VLM 卡片框是粗定位；坐标无效的订单只保留在 Flutter 结构化结果中，不在司机端页面绘制评价框。
- 小状态胶囊仍会出现在发送给 VLM 的截图中；服务端提示词要求忽略它，但若物理遮住订单关键字段仍可能导致该订单不完整，应将胶囊拖到空白处。
- 当前还没有可配置的“好单”阈值；为便于验证，Demo 会对每个新稳定画面中高德有效时薪最高且坐标有效的完整订单提醒一次。
- 实测服务端局域网接收约 15–101 ms、请求构造 0–6 ms、响应解析 0–1 ms；非空订单调用的主要耗时是 DashScope HTTP 往返，约 2.7–6.5 秒且典型约 4 秒。
- 对同一 DashScope API 地址的无模型冷连接实测约 158 ms（TLS 完成约 110 ms）；这只能用于估计网络握手量级，无法把正式请求中的云端排队、图像预处理和模型推理解耦。
- 720 px 压缩版首批 9 次非空订单实测：输入 Token 固定 1331（旧版 2723），平均总延迟 3242 ms（旧版同口径 4396 ms），中位数约 3018 ms，局域网接收平均 19 ms；Token 下降 51%，总延迟下降约 26%，仍存在一次 5879 ms 的云端波动。
- `flutter doctor` 仍报告 Android SDK `cmdline-tools` 和 license 未配置，但 Debug APK 能成功构建。

## Verification

- `flutter analyze`：通过。
- `flutter test`：Flutter 控制台 Widget 测试通过。
- `python3 -m unittest server.test_server -v`：9 个 `.env`、DashScope payload、JSON、取消协议、HMAC 鉴权/重放、高德与 HTTP 端点测试通过。
- `./gradlew testDebugUnitTest`：Android 画面指纹、稳定帧去重、滚动停止、失败退避、多订单与坐标 JSON 解析、提醒框防反馈测试通过。
- `flutter build apk --debug`：通过。

## Important files

- `.env.example`
- `server/server.py`
- `server/amap.py`
- `server/test_server.py`
- `lib/platform/screen_analyzer.dart`
- `lib/ui/analyzer_home_screen.dart`
- `android/app/src/main/kotlin/com/cheatcat/cheat_cat/MainActivity.kt`
- `android/app/src/main/kotlin/com/cheatcat/cheat_cat/ScreenCaptureService.kt`
- `android/app/src/main/kotlin/com/cheatcat/cheat_cat/VlmServerClient.kt`
- `android/app/src/main/kotlin/com/cheatcat/cheat_cat/RequestSigner.kt`
- `android/app/src/main/kotlin/com/cheatcat/cheat_cat/VlmOrderResponseParser.kt`
- `android/app/src/main/kotlin/com/cheatcat/cheat_cat/StableFrameGate.kt`
- `android/app/src/main/kotlin/com/cheatcat/cheat_cat/OrderModels.kt`
- `android/app/src/main/kotlin/com/cheatcat/cheat_cat/FrameChangeDetector.kt`
- `android/app/src/main/kotlin/com/cheatcat/cheat_cat/OrderHighlightOverlay.kt`
- `android/app/src/main/kotlin/com/cheatcat/cheat_cat/AnalyzerStatusOverlay.kt`
- `android/app/src/main/kotlin/com/cheatcat/cheat_cat/OverlaySignatureMask.kt`
- `android/app/src/main/kotlin/com/cheatcat/cheat_cat/HighlightFrameGuard.kt`
- `android/app/src/main/kotlin/com/cheatcat/cheat_cat/ScanRegion.kt`
- `android/app/src/main/kotlin/com/cheatcat/cheat_cat/ScanRegionPreferences.kt`
- `android/app/src/main/kotlin/com/cheatcat/cheat_cat/ScanRegionCalibrationOverlay.kt`
- `android/app/src/main/kotlin/com/cheatcat/cheat_cat/ScanRegionCalibrationController.kt`
