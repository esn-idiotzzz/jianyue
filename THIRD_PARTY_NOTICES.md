# 第三方项目与开源声明

本文件说明简阅源码及构建配置引用的第三方项目。版权归各原作者所有；简阅的界面、业务入口和打包适配不改变上游组件的许可证。依赖声明并不表示每个组件的全部功能都在简阅界面开放，Release 经收缩后也可能移除未使用代码。

## 主要适配来源

### Mihon / Tachiyomi

- 上游：[mihonapp/mihon](https://github.com/mihonapp/mihon)。项目内原始说明记录的基线为 [`8794e89a866a3de578c02cefac1e609bcff5f092`](https://github.com/mihonapp/mihon/tree/8794e89a866a3de578c02cefac1e609bcff5f092)。
- 用途：阅读 Activity/ViewModel、PageLoader 协议、分页与条漫视图、缩放手势、阅读设置、图源 API、网络与存储基础。适配源码在 `mihon/`。
- 适配：简阅启动页和书架；JM 与在线图源适配；`jmlite://` 阅读路由；下载、收藏和历史；阅读器返回行为；移除独立 Mihon 主界面及部分外围服务。原有修改说明保留于 [NOTICE-JM.txt](app/src/main/assets/NOTICE-JM.txt)。3.3.0 另增加简阅自己的 GitHub 更新检查和“关于”项目链接。
- 许可证：Apache License 2.0，全文保留于 [mihon/LICENSE](mihon/LICENSE) 和 APK 资产 [LICENSE-Mihon.txt](app/src/main/assets/LICENSE-Mihon.txt)。上游保留 Javier Tomás 与 Mihon Open Source Project 等原有版权声明。

### JMComic-Crawler-Python

- 上游：[hect0x7/JMComic-Crawler-Python](https://github.com/hect0x7/JMComic-Crawler-Python)。
- 用途：参考并适配 JM 移动端 API、请求/响应处理和图片分块还原方法，相关 Android 实现在 `app/src/main/java/com/joe/jmlite/`。没有把 Python 运行时打包进应用。
- 许可证：MIT，版权声明及全文保留于 [LICENSE-JMComic.txt](app/src/main/assets/LICENSE-JMComic.txt)。

### Keiyoushi 图源扩展

- 源码：[keiyoushi/extensions-source](https://github.com/keiyoushi/extensions-source)；发布仓库：[keiyoushi/extensions](https://github.com/keiyoushi/extensions)。
- 用途：`app/src/main/assets/exts/` 内置 95 个未经本项目改签或改动的扩展 APK，提供清单中的 106 个中文图源。多语言扩展整包保留，简阅选择其中中文图源。
- 许可证：所参考扩展源码仓库为 Apache-2.0；已保留[对应源码快照的许可证](https://github.com/keiyoushi/extensions-source/blob/de743aed846da53e00c45ab26ee0e77f7bb4355b/LICENSE)及各 APK 内原有声明。扩展的第三方组件仍适用自己的许可证。
- 完整包名、版本、官方 APK 下载 URL 与来源说明见 [LICENSES-extensions.txt](app/src/main/assets/LICENSES-extensions.txt)；SHA-256、源 ID 与元数据见 [bundled-sources.json](app/src/main/assets/bundled-sources.json)。该清单为随包快照，不代表上游最新扩展，也不保证所有站点实时可用。

## 压缩包处理依赖

以下版本来自本项目构建声明，完整原许可与 NOTICE 保留在 [LICENSE-archives.txt](app/src/main/assets/LICENSE-archives.txt)。

| 项目 | 版本 | 用途 | 许可 |
| --- | --- | --- | --- |
| [Apache Commons Compress](https://commons.apache.org/proper/commons-compress/) | 1.28.0 | 归档格式读取与解压 | Apache-2.0 |
| [Apache Commons IO](https://commons.apache.org/proper/commons-io/) | 2.20.0 | 流与文件操作 | Apache-2.0 |
| [Apache Commons Codec](https://commons.apache.org/proper/commons-codec/) | 1.19.0 | 编码与摘要工具 | Apache-2.0 |
| [Apache Commons Lang](https://commons.apache.org/proper/commons-lang/) | 3.18.0 | Java 通用工具 | Apache-2.0 |
| [XZ for Java](https://github.com/tukaani-project/xz-java) | 1.10 | XZ/LZMA 压缩流支持 | 0BSD（1.10 起；不是旧版的 public domain 声明） |
| [Junrar](https://github.com/junrar/junrar) | 8.1.2 | RAR 解压读取 | UnRAR 源码使用与分发许可；不可用于开发兼容 RAR/WinRAR 的压缩器 |
| [SLF4J](https://github.com/qos-ch/slf4j) | 2.0.17 | 归档依赖使用的日志接口 | MIT |

Junrar 的 UnRAR 限制许可不等同于 MIT 或 Apache-2.0；这里只用于解压读取。Apache Commons 的多来源版权与 NOTICE 继续随原许可文本保留。

## Mihon 框架引用的运行时项目

下表按用途列出项目来源。精确 Maven 坐标、版本与传递关系由 [libs.versions.toml](mihon/gradle/libs.versions.toml) 和各模块的 `build.gradle.kts` 定义；许可证以所用版本的上游 LICENSE、NOTICE 与 Maven POM 为准。这些链接也便于查阅各组件的完整许可，不能用本表替代它们。

| 项目 | 在框架中的作用与来源 |
| --- | --- |
| [AndroidX / Jetpack](https://android.googlesource.com/platform/frameworks/support/) | Activity、AppCompat、Compose、Lifecycle、Paging、WorkManager、Preference、RecyclerView、ViewPager、WebKit、SQLite 等 Android 界面与运行基础 |
| [Kotlin](https://github.com/JetBrains/kotlin)、[Coroutines](https://github.com/Kotlin/kotlinx.coroutines)、[Serialization](https://github.com/Kotlin/kotlinx.serialization)、[Datetime](https://github.com/Kotlin/kotlinx-datetime) | Kotlin 语言运行库、异步任务、JSON/Protobuf 和日期时间 |
| [OkHttp](https://github.com/square/okhttp)、[Okio](https://github.com/square/okio) | HTTP 请求、拦截器、DNS-over-HTTPS、压缩与流处理 |
| [Conscrypt](https://github.com/google/conscrypt) | 较旧 Android 上的 TLS 支持 |
| [jsoup](https://github.com/jhy/jsoup) | HTML 解析与图源页面处理 |
| [Coil](https://github.com/coil-kt/coil) | 图片加载、缓存、GIF 与 OkHttp 集成 |
| [subsampling-scale-image-view（Mihon 分支）](https://github.com/mihonapp/subsampling-scale-image-view) | 大图分块显示、平移及缩放 |
| [PhotoView](https://github.com/Baseflow/PhotoView) | 图片缩放与手势视图，依赖坐标沿用 `com.github.chrisbanes:PhotoView` |
| [DirectionalViewPager](https://github.com/tachiyomiorg/DirectionalViewPager) | 阅读分页方向支持 |
| [imagedecoder](https://github.com/mpreg-ca/imagedecoder) | 原生图片解码；上游主项目 MIT，所含解码器仍保留各自许可 |
| [WebGPUViewer](https://github.com/mpreg-ca/webgpuviewer) | WebGPU 阅读视图与图片处理；上游主项目 MIT |
| [libarchive-android](https://github.com/zhanghai/libarchive-android) / [libarchive](https://github.com/libarchive/libarchive) | Android 原生归档读取；Android 封装 Apache-2.0，libarchive 及其压缩库分别适用各自许可 |
| [UniFile（Mihon 分支）](https://github.com/mihon/unifile) | Android 文档与文件抽象，源自 Tachiyomi/UniFile 与 DocumentFile |
| [QuickJS Java](https://github.com/zhanghai/quickjs-java) / [QuickJS](https://bellard.org/quickjs/) | 图源需要的 JavaScript 执行引擎与 Android/JVM 封装 |
| [RxJava](https://github.com/ReactiveX/RxJava) | 图源 API 的响应式接口 |
| [Injekt](https://github.com/kohesive/injekt)、[Metro](https://github.com/ZacSweers/metro) | 依赖注入及 ViewModel 集成 |
| [SQLDelight](https://github.com/sqldelight/sqldelight)、[SQLDelight AndroidX driver](https://github.com/eygraber/sqldelight-androidx-driver) | 本地数据库、协程查询和 Paging 适配 |
| [Apollo Kotlin](https://github.com/apollographql/apollo-kotlin)、[Apollo adapters](https://github.com/apollographql/apollo-kotlin-adapters) | 框架 GraphQL 类型与运行库 |
| [xmlutil](https://github.com/pdvrieze/xmlutil) | XML 序列化与解析 |
| [Material Components Android](https://github.com/material-components/material-components-android) | Material 控件与样式 |
| [Voyager](https://github.com/adrielcafe/voyager) | Compose 页面导航和过渡 |
| [Compose Rich Editor](https://github.com/MohamedRejeb/compose-rich-editor) | Compose 富文本组件 |
| [AboutLibraries](https://github.com/mikepenz/AboutLibraries) | 依赖许可元数据及 Compose 展示组件 |
| [Compose Material Motion](https://github.com/fornewid/material-motion-compose) | Material 页面动画 |
| [Swipe](https://github.com/saket/swipe) | 滑动操作组件 |
| [Compose WebView](https://github.com/KevinnZou/compose-webview) | Compose WebView 封装 |
| [Compose Grid](https://github.com/cheonjaeung/compose-grid) | 网格布局 |
| [Reorderable](https://github.com/Calvin-LL/Reorderable) | 列表拖动排序 |
| [Multiplatform Markdown Renderer](https://github.com/mikepenz/multiplatform-markdown-renderer) | Markdown 文本渲染 |
| [MaterialKolor](https://github.com/jordond/MaterialKolor) | Material 配色 |
| [moko-resources](https://github.com/icerockdev/moko-resources) | 多语言资源 |
| [Logcat](https://github.com/square/logcat)、[LeakCanary / Plumber](https://github.com/square/leakcanary) | 日志接口和 Android 已知内存泄漏修补 |
| [string-similarity-kotlin](https://github.com/aallam/string-similarity-kotlin)、[java-nat-sort](https://github.com/gpanther/java-nat-sort) | 标题相似度和自然顺序比较 |

原生依赖还可能包含 libz、libbz2、liblzma、liblz4、libzstd、mbedTLS 等代码；它们由相应上游原生组件构建并分发，不能用外层封装项目的许可证替代其自身许可。

## 图标与构建/测试工具

- [lingeringsound/adblock_auto](https://github.com/lingeringsound/adblock_auto)：内置登录窗口的去广告规则来源，随包文件为 `app/src/main/assets/browser-adblock.txt`，取自用户指定的 `base/其他.prop`，保留原始规则头和作者来源信息。

- [Material Symbols / Material Design Icons](https://github.com/google/material-design-icons)：界面图标，Apache-2.0。
- [Simple Icons](https://github.com/simple-icons/simple-icons)：品牌 SVG 图标，CC0-1.0；品牌标识的商标权仍归对应权利人。源码 SVG 中原作者注释保留。
- [Gradle](https://github.com/gradle/gradle)、[Android Gradle Plugin](https://android.googlesource.com/platform/tools/base/)、[Kotlin](https://github.com/JetBrains/kotlin)：构建工具链。
- [Valkyrie](https://github.com/ComposeGears/Valkyrie)：将 SVG 图标生成为 Compose 向量资源。
- [Spotless](https://github.com/diffplug/spotless)、[ktlint](https://github.com/pinterest/ktlint)、[TAP-MOC](https://github.com/GradleUp/tapmoc)：代码格式与构建配置工具。
- [JUnit](https://github.com/junit-team/junit-framework)、[Kotest](https://github.com/kotest/kotest)、[MockK](https://github.com/mockk/mockk)、[AndroidX Test](https://github.com/android/android-test)：JVM 及 Android 测试依赖。
- [JSON-java](https://github.com/stleary/JSON-java)：`org.json:json:20240303` 仅用于 JVM 测试，验证下载记录迁移、删除隔离及搜索，不打入 APK；所用 Maven POM 标注 Public Domain，详见该版本原许可。

框架版本目录还保留了 Firebase、Google Services 和 Shizuku 等可选条目；简阅当前发布脚本传入 `include-telemetry=false`，原生 Mihon 更新器传入 `enable-updater=false`。简阅的版本检查直接面向本项目 GitHub Releases，不使用上游 Mihon 的更新仓库。目录中保留条目不表示启用了对应服务。

## 随包保留的原文

- [Mihon 许可证](app/src/main/assets/LICENSE-Mihon.txt)
- [JMComic 许可证](app/src/main/assets/LICENSE-JMComic.txt)
- [图源扩展许可证、发布来源与完整包列表](app/src/main/assets/LICENSES-extensions.txt)
- [压缩包依赖许可证与 NOTICE](app/src/main/assets/LICENSE-archives.txt)
- [历史适配声明](app/src/main/assets/NOTICE-JM.txt)

以上文件随源码和 APK 资产一起保留。本文件是项目介绍与来源索引，不替换原许可文本，也不对第三方代码重新许可。
