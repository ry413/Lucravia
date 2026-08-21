# 跑单助手 Demo

这是一个 Android Flutter 网约车订单分析工具。Android 客户端在用户明确授权后读取司机端订单页和当前位置，将稳定的新画面发送到同一局域网的 Python 服务；服务端调用 `qwen3.7-flash` 提取一屏订单，再用高德 Web 服务完成地点解析与两段驾车路径规划，把有时间依据的订单评价返回客户端。

工具不会自动点击、模拟触摸或接单，也不调用网约车平台的私有接口。

## 当前可运行链路

1. 将 `.env.example` 复制为工程根目录 `.env`，填写服务端使用的 `DASHSCOPE_API_KEY` 与高德 Web 服务类型 `AMAP_API_KEY`，并把 `VLM_SERVER_URL` 改成服务端地址。再生成一个至少 32 字符的 `SHARED_SECRET`；Android 构建与 Python 服务必须使用同一个值。
2. 运行 `python3 server/server.py`，确认手机能访问 `http://服务端IP:8765/health`。
3. 重新构建并安装 APK，授予“显示在其他应用上层”和定位权限；定位仅用于当前位置到上车点的接驾路线，分析前台服务运行时会持续更新。
4. 可选但推荐先校准识图区域：在助手点击“校准识图区域”后会出现一个小型“开始校准”悬浮按钮；此时屏幕其他位置仍可操作。打开目标订单页并点该按钮，再拖动上下绿线保存或取消。宽度固定为整屏，区域最小高度为屏幕 25%。
5. 点击“开始屏幕分析”，确认 Android 的屏幕捕获对话框，然后切换到订单页并停止滑动。
6. 客户端约每 450 ms 为选定区域生成低分辨率画面指纹；连续两帧稳定且与上次提交不同，才裁出该区域、缩至最大 720 px 宽并以 JPEG 质量 72 发送到局域网服务。未校准时保持完整屏幕。
7. Python 服务将 JPEG 转为 Base64 后调用 DashScope；模型固定为 `qwen3.7-flash`，关闭思考并启用 JSON Mode。
8. VLM 返回画面内从上到下的订单数组、每张卡片的归一化边界框、显式可见性审计和遮挡字段列表。任一关键数字不完整，或起终点存在无法可靠辨认的字符，客户端都不会将其视为完整订单；仅裁到地址字形少量边缘但仍可可靠辨认时允许通过。
9. 服务端将手机 GPS 转为高德坐标，以已知起点为中心搜索地址 POI，并参考平台显示的接驾/行程里程选择候选；若完整地址查不到，会再尝试去掉道路后缀的 POI 名。随后以高德路径规划 2.0 默认推荐策略计算“当前位置→上车点”和“上车点→目的地”两段驾车路线，并读取道路里程、实时路况分段、红绿灯、收费金额、收费里程和主要收费道路。每段高德路线还会与平台里程做宽松的数量级审计；明显冲突时标记“地图地点匹配异常”，不计算时薪、不参与推荐，也不会因为订单跨城本身而拒绝。不同订单并行处理。
   服务端会按高德服务类别把请求间隔限制为至少 380 ms，并对 10021 QPS 超限做最多两次退避重试，以适配个人认证账号默认 3 QPS。
10. 有效时薪按 `价格 ÷（高德接驾秒数 + 3 分钟等客 + 高德载客秒数）× 3600` 计算。展示分钟数向上取整，但时薪使用原始秒数；若地点找不到或 API 失败，该单明确显示“待算路”，不会退回平均速度猜测。
11. 分析期间会显示一个可拖动的小型状态胶囊，反馈“等待稳定 / VLM + 高德识别中 / 识别结果 / 请求失败”等阶段；其所在区域会从画面指纹中动态屏蔽。它会进入截图，因此若遮住订单字段，应拖到页面空白处。
12. 本屏存在多张完整订单时，客户端在最多三张可定位的卡片上分别显示排行、预计毛时薪、接驾/等客/载客耗时、接驾/载客/总行驶里程、两段红绿灯数量、拥堵/严重拥堵里程及预计道路收费，并以绿 / 橙 / 蓝细框区分；评价窗使用完整中文字段和单位，不要求司机理解缩写。同时播放提示音和振动，Flutter 详情页保留两段拆分和主要收费道路名称。
13. 推荐框显示期间只在本地监测页面变化，不重复调用 VLM；用户滚动或订单刷新后先撤框，再等待新画面稳定。
14. VLM / 高德处理期间仍持续做本地画面指纹检测；订单区域一旦发生实质变化，客户端立即断开旧 HTTP 请求并释放分析槽位，服务端收到请求 ID 对应的取消信号后丢弃旧结果且不再开始高德算路。新页面稳定后无需等待旧请求返回即可发起新分析。
15. 客户端启动后会通过同一共享密钥检查服务端更新。发现更高 `versionCode` 时，首页显示版本说明和安装包大小；用户点击后由 App 流式下载 APK、校验大小与 SHA-256，再交给 Android 系统安装器。普通应用不能静默安装，首次需允许“安装未知应用”，之后仍由用户确认覆盖更新。标记为必要更新的版本会阻止开始新的订单分析。

网络失败时，同一稳定画面最早 3 秒后重试。Android 14 及更高版本要求每次新的屏幕捕获会话都由用户重新确认。

屏幕帧不会由客户端或服务端写入图片文件，但稳定的新订单截图会经过服务上传给 DashScope；当前位置与订单地址会由服务端发送给高德用于地点与路线查询。两个 API Key 都只由服务端从 `.env` 读取，不进入 APK。Android 使用同一份 `SHARED_SECRET` 对请求方法、路径、时间戳、请求 ID 和图片摘要做 HMAC-SHA256 签名；服务端在调用云 API 前验签，并拒绝过期或重放请求。

## 重要限制

VLM 提示词只针对当前截图中这一版司机端 UI。模型输出仍可能遗漏或误读，结果只能作为辅助信息。共享密钥能阻止不知密钥的公网扫描器刷 DashScope/高德，但不是账号体系：有心人仍可从 APK 中提取密钥。HTTP 签名也不加密截图和定位，因此公网测试至少应使用 HTTPS；正式部署再补账号/设备鉴权、限流和持久化指标。

同名 POI 仍可能存在无法仅靠名称和里程解决的歧义；服务端会保留高德实际匹配名称用于排查。路线审计目前使用宽松容差，只拦截明显的数量级冲突，不保证地图匹配绝对正确。当前固定等客时间为 3 分钟，尚未开放用户配置。

地址同时保留画面原文和一个保守规范化结果。规范化只允许修正非常明确的错别字、重复字和异常空格，不猜测被遮挡文字；客户端发现长度变化过大时会退回原文。

## 服务端日志

服务端日志同时输出到终端和 `server/logs/vlm_server.log`，单文件 5 MB，保留 3 个历史文件。每次调用包含：

- 服务端请求 ID、手机局域网 IP、JPEG 字节数；
- 客户端实际图片尺寸、JPEG 质量和手机端编码耗时；
- 成功或失败，以及 `total_ms / lan_receive_ms / payload_ms / dashscope_http_ms / response_parse_ms / amap_ms` 分段耗时与成功算路订单数；
- DashScope 请求 ID、输入 Token、输出 Token。
- `orders_result` 会打印模型返回的完整订单 JSON，包含地点信息，开发完成后应关闭或脱敏。

## 构建与测试

```bash
cp .env.example .env
# 编辑 DASHSCOPE_API_KEY、AMAP_API_KEY、VLM_SERVER_URL 和 SHARED_SECRET
python3 server/server.py
python3 -m unittest server.test_server -v

flutter pub get
flutter analyze
flutter test
flutter build apk --debug

cd android
./gradlew testDebugUnitTest
```

Debug APK 生成在 `build/app/outputs/flutter-apk/app-debug.apk`。

## Android 正式签名与应用内更新

正式包名为 `com.lucravia.xiaozhuiot`，版本从 `1.1.0+2` 开始使用永久 Release 签名。当前工作区已经生成以下两个本地机密文件，它们均被 Git 忽略：

- `android/app/lucravia-release.jks`
- `android/key.properties`

必须立即把二者一起离线备份。以后换电脑或 CI 构建时应恢复原文件，绝不能生成新签名；签名丢失后，已经安装的客户端无法再覆盖更新。当前签名证书 SHA-256 为 `0e35c5c488de1ba6de6978e1149614c4a0f2f4f0aa4640d556dc897cb390ada7`，发布脚本会同时核验包名、版本与该证书，防止误发 Debug 或错误签名的 APK。

原先 `com.cheatcat.cheat_cat` 的 Debug 安装与正式签名/包名不兼容，测试手机需要最后卸载一次旧版，再安装首个正式 APK。之后发布更新：

```bash
# 1. 先在 pubspec.yaml 增加 versionName 和 versionCode，例如 1.1.1+3
flutter build apk --release

# 2. 把签名 APK 和原子更新清单发布到本机 server/updates/
python3 server/publish_update.py \
  build/app/outputs/flutter-apk/app-release.apk \
  --version-code 3 \
  --version-name 1.1.1 \
  --notes "本次更新说明"

# 紧急且必须安装的版本可额外传 --required
```

`server/updates/latest.json` 和 APK 含构建产物与 APK 内的测试共享密钥，因此不会进入 Git。把本机 `server/updates/` 同步到 VPS 仓库的同名目录即可；首次部署服务端更新接口需要 `git pull` 并重启 Python 服务，后续只替换发布文件无需重启：

```bash
rsync -av server/updates/ <VPS>:<项目目录>/server/updates/
```

服务端提供鉴权的 `GET /v1/update/latest` 与 `GET /v1/update/apk`，未持有 APK 内共享密钥的请求无法列出或下载更新。发布脚本不会删除旧 APK，可在确认用户不再需要回退后手工清理。

## Git 与 VPS 同步

仓库使用 `main` 分支。`.env`、服务端日志、Flutter/Gradle 构建缓存、APK、IDE 配置、定位数据和签名文件都不会进入 Git；`.env.example`、依赖锁文件和 Gradle Wrapper 会被提交，以便新机器复现环境。

首次关联你自己的远程仓库：

```bash
git remote add origin <你的 Git 仓库地址>
git push -u origin main
```

VPS 首次部署与后续同步：

```bash
git clone <你的 Git 仓库地址> cheat_cat
cd cheat_cat
cp .env.example .env
# 在 VPS 单独填写 .env，密钥不会由 Git 同步
# SHARED_SECRET 必须与构建测试 APK 时的值一致

# 后续只拉取快进更新
git pull --ff-only
```

当前 Python 服务已对两个 POST 分析端点和两个 GET 更新端点做共享密钥 HMAC 验签；未签名请求会在触发 DashScope/高德费用或下载 APK 前返回 401。`GET /health` 保持公开且不调用云 API。若将 8765 端口暴露公网，仍建议在反向代理上启用 HTTPS，防止截图、定位与更新流量被窃听。

## 重要代码

- `lib/ui/analyzer_home_screen.dart`：授权和分析控制台。
- `lib/platform/screen_analyzer.dart`：Flutter 与 Android 之间的通信边界。
- `android/app/src/main/kotlin/com/cheatcat/cheat_cat/ScreenCaptureService.kt`：屏幕捕获、稳定画面调度、悬浮窗和前台服务。
- `android/app/src/main/kotlin/com/cheatcat/cheat_cat/VlmServerClient.kt`：JPEG 压缩和局域网服务请求。
- `android/app/src/main/kotlin/com/cheatcat/cheat_cat/RequestSigner.kt`：与 Python 服务一致的 HMAC-SHA256 请求签名。
- `android/app/src/main/kotlin/com/cheatcat/cheat_cat/VlmOrderResponseParser.kt`：结构化订单 JSON 校验与解析。
- `android/app/src/main/kotlin/com/cheatcat/cheat_cat/StableFrameGate.kt`：滚动静止检测、画面去重和失败退避。
- `android/app/src/main/kotlin/com/cheatcat/cheat_cat/OrderHighlightOverlay.kt`：前三张订单的安全窄边框和评价标签。
- `android/app/src/main/kotlin/com/cheatcat/cheat_cat/AnalyzerStatusOverlay.kt`：可拖动的小型运行阶段浮窗。
- `android/app/src/main/kotlin/com/cheatcat/cheat_cat/OverlaySignatureMask.kt`：根据浮窗实时位置屏蔽画面指纹。
- `android/app/src/main/kotlin/com/cheatcat/cheat_cat/HighlightFrameGuard.kt`：提醒显示期间的页面变化检测与防反馈。
- `android/app/src/main/kotlin/com/cheatcat/cheat_cat/ScanRegionCalibrationOverlay.kt`：可拖动上下边界的全宽校准浮层。
- `android/app/src/main/kotlin/com/cheatcat/cheat_cat/ScanRegionCalibrationController.kt`：允许先切换目标 App 的小型校准启动窗。
- `android/app/src/main/kotlin/com/cheatcat/cheat_cat/ScanRegion.kt`：裁剪、指纹和屏幕坐标的统一换算。
- `server/server.py`：局域网 HTTP、DashScope 调用和滚动日志。
- `server/amap.py`：GPS 坐标转换、POI 消歧、驾车路径规划和有效时薪计算。
- `server/app_updates.py`：更新清单验证与 APK 仓库。
- `server/publish_update.py`：把正式签名 APK 发布到 `server/updates/`。
- `android/app/src/main/kotlin/com/cheatcat/cheat_cat/AppUpdateManager.kt`：鉴权检查、流式下载、摘要校验与系统安装器。
