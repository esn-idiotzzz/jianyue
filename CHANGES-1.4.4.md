# 简阅 1.4.4

- 书籍夹移动/拆夹/最后一本/隐私转移后标记父级 dirty；返回手势和按钮共用已更新的内存目录，手势在返回前隐藏旧 BackPreview 并只构建一次目标，避免旧排列覆盖。父目录排列未变化时保持原路径。
- 条漫：保留黑色章节过渡信息板块，`WebtoonTransitionHolder` 上下 padding 收紧；`WebtoonViewer` 在用户拖动/惯性滚动期间不再立即替换 adapter 章节列表，pending 到滚动 idle 后再应用，避免在黑色过渡处被 DiffUtil 重排截停。
- 保留 1.4.3 的 JM 快速进入/加载文案、仓库会话封面、历史封面隔离、历史多选、菜单/键盘/隐私/返回修复。
- 版本 1.4.4，versionCode 66；本地交付并上传 GitHub。

验证见 `VALIDATION-1.4.4.md`。
