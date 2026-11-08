# 简阅 1.4.0

- 键盘弹出时胶囊导航使用系统栏安全区锚定，不再使用 IME 高度/位移；允许键盘遮挡，键盘关闭后同一位置恢复。移除 GlobalLayout 对 IME 状态的重复覆盖，统一以 Insets 为准。
- 搜索框统一 `Ui.bindCursorToIme()`：点击/展开才显示光标，键盘关闭立即隐藏；JM 归属书输入框移除自动全选和平板聚焦滚动；弹窗/列表不重建。
- 弹窗内部四周增加 16dp 等宽留白，选项描边不再贴外壳；宽度按最长选项计算并限制屏幕安全宽度。
- 书籍夹拖出：“移出书籍夹”胶囊移动使用 `Library.moveWithFavorite()` 一次事务更新目录与收藏标记，不再二次 move 到根目录；内存中立即更新书籍 folder/收藏并重建封面索引，父目录首帧使用新状态。
- JM 在线阅读与历史统一走禁漫天堂图源 Manga/Chapter + HttpPageLoader/ChapterCache；历史续读支持 `start_over=false`，不再另造全网记录。会话内图片/页列表复用，冷启动清理临时缓存，历史/进度保留。
- HttpPageLoader 从真实 `lastPageRead` 锚点开始，中心向 p±1、p±2 扩散，当前页不被第 0 页阻塞。
- 历史/详情 HTTPS 封面改走 RemoteCovers（带磁盘缓存），不再交给只能读 ContentResolver 的 CoverLoader；JM 历史封面冷启动可复用磁盘编码文件。
- 保留 1.3.3 的浏览图标、输入/仓库菜单、隐私/返回稳定性修复。
- 版本 1.4.0，versionCode 62；本地交付并上传 GitHub。

验证见 `VALIDATION-1.4.0.md`。
