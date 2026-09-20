# Frida 调查脚本

保留三个观察脚本，RVA、对象字段偏移和文本列号统一读取本地 `app/src/main/assets/runtime.json`，不再内置某个版本的地址。先生成独立脚本，再交给 Frida 加载；Android 端不需要访问 Windows 文件路径。

| 脚本 | 用途 |
| --- | --- |
| `diag_scenario_static_text` | 从 FindScenarioData 返回的 scenario、label、页面和命令读取正文、说话人、选项和跳转 |
| `diag_scenario_branch_graph` | 观察静态分支、延续与合流关系；图模拟是调查辅助，不等同于当前 native 解析器 |
| `diag_characteroff_speaker` | 按 CommandList 顺序比较 CharacterOff 清空/保留说话人的结果；不判断最终画面显示 |

## 生成与加载

从仓库根目录执行，使用 Python 标准库，无额外依赖：

```powershell
python tools/frida/prepare.py diag_scenario_static_text
python tools/frida/prepare.py diag_scenario_branch_graph --scene-filter quest_love_shino
python tools/frida/prepare.py diag_characteroff_speaker
```

输出到 `build/frida/<脚本名>.js`。也可以通过 `--runtime <本地文件路径>` 指定另一份 runtime；相对路径相对于执行命令的目录。分支图不指定 `--scene-filter` 时观察全部 Scene，过滤值同时匹配 Scene 名和请求 label。

确认设备、目标进程及 Frida 环境后，加载生成文件，例如：

```powershell
frida -U -p <游戏PID> -l build/frida/diag_scenario_static_text.js
```

不要直接加载本目录下的源脚本。生成物包含配置快照，runtime 更新后需要重新生成、重新加载。生成失败时旧生成物不会被更新，不能继续将它当作本轮结果使用。

生成阶段检查脚本所需字段，缺失或格式错误即报错，不回退到旧常量。加载阶段检查 arm64 与指针宽度，并打印配置版本和来源。遍历上限、轮询间隔及指针低地址过滤等算法参数保留在脚本中，它们不是 RVA/字段偏移。

runtime 当前不包含目标库哈希或 build ID；版本日志和 ABI 检查不能证明二进制匹配。运行前仍需核对实际游戏版本和目标库身份。脚本会安装 Hook，仍可能影响时序；生成和语法检查不等于设备验证。

CharacterOff 探针只使用在 CommandList 中实际找到的命令位置；找不到时输出 SKIP，不再把 TextData 索引猜作命令索引。旧显示观察把 PageTextChange 的 AdvPage 参数猜作字符串/解析器，已移除该段及其未配置偏移。

## 已移除的历史脚本

| 文件 | 移除原因 |
| --- | --- |
| `hook.js`、`diag_branch.js` | 早期综合 Hook 和页面初始化调查，使用旧地址；当前静态正文和分支图覆盖主要调查入口 |
| `diag_active_text.js`、`diag_call_tree.js` | 包含由旧 InitText 地址推算的 DoCommand 地址，不能作为当前版本地址使用 |
| `diag_labeldata_route.js`、`diag_scenario_start.js` | 旧 label/协程入口调查，依赖本地 runtime 未维护的入口和状态布局 |
| `diag_scenario_bucket.js` | 早期 bucket 布局调查；正文和分支图已有相同 bucket 遍历，额外旧入口及 jumpData 布局未进入 runtime |
| `probe_quest_writeback.js`、`probe_quest_batch_writeback.js`、`probe_selection_live_refresh.js` | 一次性回写原型，包含旧固定样本、LateUpdate 或 runtime 未维护的缓存布局；不同于现有生产回写链 |

生产链路和历史调查出处见 [游戏层开发文档](../../docs/game-layer-development.md)。此次清理不修改应用 runtime 数据，也不执行 Frida 注入或设备操作。
