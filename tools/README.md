# 开发工具索引

这些是开发辅助工具，不随 HET APK 安装，也不是应用启动所需组件。所有示例命令从仓库根目录执行。

| 目录 | 用途 | 入口 | 执行影响 |
| --- | --- | --- | --- |
| `wiki/` | 收集角色资料、维护 CharDict | [工具说明](wiki/README.md)、[完整工作流](wiki/WORKFLOW.md) | 抓取脚本访问网络；应用脚本显式 `--write` 后修改字典 |
| `rva/` | 从 IL2CPP 导出资料生成运行时配置 | [使用说明](rva/README.md) | 默认写配置，`--stdout` 只输出预览 |
| `frida/` | 游戏剧情运行时诊断 | [脚本分类](frida/README.md) | 生成时只读本地 runtime；加载时向目标进程安装 Hook |

## 选择工具

- 更新指定角色：先读 `wiki/WORKFLOW.md`，使用 prepare / apply 两阶段入口。
- 游戏升级后更新 RVA：使用 `rva/generate_config.py`，同时独立核验 Layout。
- 调查剧情捕获、分支或画面：从 Frida 分类表选择单一目的脚本，先用本地 runtime 生成脚本并核对目标版本和库身份。
- 理解生产实现：阅读 [游戏层开发文档](../docs/game-layer-development.md)；探针不等于生产代码。

## 文件与产物约定

- Wiki 的模块引用路径保持不变；Frida 旧脚本清理清单和新入口见其目录说明。
- `__pycache__/`、`*.pyc` 是 Python 自动缓存，不是工具源码；本次整理未删除本地缓存。
- Wiki 批次按现有工作流写入 `build/wiki_character_update/<批次>/`；原始网页、生成结果与基线记录属于同一批次。
- 设备日志、dump 和探针输出放到明确的调查目录，不混入脚本文件。
- Frida 生成脚本写入 `build/frida/`，生成物不放在工具源码目录。

工具整理不包含抓取、Frida 注入或设备回写实验；本地生成和语法检查与设备验证分别记录。
