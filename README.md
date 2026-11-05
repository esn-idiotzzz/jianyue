# 简阅 3.4.0

简阅是 Android 漫画阅读应用，提供本地书架、JM 漫画入口、中文图源搜索与浏览、下载和阅读历史。阅读器和图源运行框架基于 Mihon，JM 接口与图片还原参考 JMComic-Crawler-Python，内置扩展来自 Keiyoushi；各项目的来源、用途和许可证见下方说明。

- 项目：[esn-idiotzzz/jianyue](https://github.com/esn-idiotzzz/jianyue)
- 下载：[最新正式版](https://github.com/esn-idiotzzz/jianyue/releases/latest)
- 系统：Android 8.0 及以上，`arm64-v8a`
- 包名：`com.joe.jmlite`

## 3.4.0 更新

- 历史记录加载完成前不显示“暂无浏览记录”，避免有记录时切换页面短暂闪出空状态。
- JM 历史详情中，若相同编号的本地漫画已在书架，下载按钮显示“已存在于书架”并不可点击。
- 全网搜索与封面加载使用更高的有界并发，搜索结果及时分批显示；重复封面请求合并，过期请求取消。
- 书籍拖至另一书籍中央时预览双封面书夹，松手将两本归入默认名为“新建书夹”的新夹。
- 书籍拖至左右边缘时预览连续推挤和插入排序；书籍夹只排序。书籍夹仍位于书籍之前，两类分别保存排序。

继续保留 3.3.0 的 GitHub 更新提示与“关于”项目链接，以及收藏爱心、逐章下载、在线收藏自动分组、下载过渡和先前版本的阅读与图源功能。

## 开源项目介绍与致谢

| 项目 | 在简阅中的用途 | 许可证与说明 |
| --- | --- | --- |
| [Mihon](https://github.com/mihonapp/mihon) | Android 阅读器、分页/条漫、图片缩放与手势、图源 API、网络与本地阅读基础；保留并适配相关源码 | Apache-2.0；保留上游版权，源码位于 `mihon/`，许可证见 [mihon/LICENSE](mihon/LICENSE) |
| [JMComic-Crawler-Python](https://github.com/hect0x7/JMComic-Crawler-Python) | JM API、接口加解密及图片还原方法的 Android/Java 适配参考；应用不运行 Python 爬虫 | MIT；见 [LICENSE-JMComic.txt](app/src/main/assets/LICENSE-JMComic.txt) |
| [Keiyoushi extensions-source](https://github.com/keiyoushi/extensions-source) / [extensions](https://github.com/keiyoushi/extensions) | 内置中文图源扩展及其发布 APK；通过 Mihon 图源 API 加载 | 扩展仓库 Apache-2.0；保留各 APK 内第三方声明，95 个扩展/106 个中文图源的来源、版本及校验值见 [清单](app/src/main/assets/bundled-sources.json) 和 [许可证说明](app/src/main/assets/LICENSES-extensions.txt) |
| [Apache Commons](https://commons.apache.org/)、[XZ for Java](https://tukaani.org/xz/java.html)、[Junrar](https://github.com/junrar/junrar)、[SLF4J](https://github.com/qos-ch/slf4j) | 本地压缩包读取、解压与相关工具支持 | Apache-2.0、0BSD、UnRAR 限制许可、MIT 等分别适用；见 [LICENSE-archives.txt](app/src/main/assets/LICENSE-archives.txt) |

其他网络、图片、界面、数据库、图标和构建依赖的介绍与链接集中在 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)。简阅是独立维护的适配项目，不代表上述上游项目；各组件保持自己的许可证，不能把整包一概视为同一种许可证。

## 构建

需要 JDK 21、Android SDK platform `37.2`、Build Tools `37`，首次构建需要网络下载 Gradle 和 Maven 依赖。

```powershell
.\build-mihon.ps1 -JavaHome C:\path\to\jdk21 -AndroidHome C:\path\to\android-sdk
```

输出位于 `build/`。项目不发布签名私钥、签名密码或本机工具链。构建脚本在没有本地密钥时会生成自己的签名；这种签名不能覆盖安装官方发布包。发布维护者须妥善保留原签名。

应用源码位于 `app/src/main/`，Mihon 适配工程位于 `mihon/`。当前 JVM 逻辑测试位于 `mihon/app/src/test/`；`tests/java/` 和 `build-legacy-tests.ps1` 为旧测试，不作为当前验证结果。每版实际构建、测试和设备验证边界以该版 Release 随附验证说明为准。

## 发布后如何触发更新

1. 提升应用版本名及 `versionCode`，例如 `3.4.0` → `3.4.1`，并用相同发布密钥签名。
2. 在本仓库创建对应 GitHub Release，使用可比较的标签，例如 `v3.4.1`，填写更新说明并附上 APK。
3. 将它发布为最新正式版。草稿和预发布不用于正式版更新提示；Release 正文会显示在应用更新弹窗里。
4. 已安装支持更新检查版本的手机在下次打开应用时检查版本。仅推送源码、创建空标签或上传到其他仓库不会触发更新。

检查更新需要手机能访问 GitHub。无法连接、请求受限或尚无正式 Release 时静默跳过；应用不自动下载或安装 APK。尚未含有此功能的 3.1.0 需要先手动安装 3.3.0 或更高版本。

## 内容来源

本项目不托管漫画内容。在线内容、图源可用性与账号状态取决于对应站点和用户网络；阅读、下载及分享请遵守内容权利人与站点的要求。
