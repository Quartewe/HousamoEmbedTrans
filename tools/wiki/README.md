# Wiki 与角色词典工具

正式操作步骤以 [WORKFLOW.md](WORKFLOW.md) 为准。使用现有 Python 3.10+ 和标准库，从仓库根目录执行。

## 当前入口

| 脚本 | 输入 | 输出 / 用途 |
| --- | --- | --- |
| [prepare_event_update.py](prepare_event_update.py) | 官方活动 URL、独立输出目录 | 下载官方页并整理阅读资料，供确认 pickup 名单 |
| [prepare_character_update.py](prepare_character_update.py) | 指定角色日文名、独立输出目录 | 下载角色页，生成原文和含字典基线的 manifest |
| [apply_character_update.py](apply_character_update.py) | 已完成的批次与 generated.json | 默认预览差异；加 `--write` 才写入 CharDict |

典型顺序：

```powershell
python tools/wiki/prepare_character_update.py --names "タサブロウ" --out-dir build/wiki_character_update/tasaburo-01
# 按 WORKFLOW.md 阅读原始资料并生成该批次的 generated.json 后：
python tools/wiki/apply_character_update.py --batch build/wiki_character_update/tasaburo-01
# 确认预览后，才执行写入：
python tools/wiki/apply_character_update.py --batch build/wiki_character_update/tasaburo-01 --write
```

每次准备使用新的批次目录，不修改 manifest 绕过基线检查。当前字典路径是 `app/src/main/assets/term/chardict.json`；语言值与别名的保留规则见完整工作流。

## 历史工具与共享依赖

| 脚本 | 定位 | 注意事项 |
| --- | --- | --- |
| [extract_character_wiki.py](extract_character_wiki.py) | 历史全名单抓取入口，同时提供现有入口使用的站点常量和名字映射 | 不能直接删除或移动；旧 CLI 默认字典路径缺少 `term/` |
| [apply_character_info.py](apply_character_info.py) | 历史 Info 批处理 | 使用大写 `Info`，不是当前小写 `info` 更新流程 |
| [make_manual_info_tasks.py](make_manual_info_tasks.py) | 历史人工资料任务生成 | 依赖旧提取目录与产物，不作为新批次入口 |

`prepare_event_update.py` 引用 `prepare_character_update.py` 的页面解析器；后者引用 `extract_character_wiki.py` 的常量。因此保留这些文件在同一目录，仅在说明中区分当前与历史用途。
