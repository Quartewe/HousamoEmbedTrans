# RVA 配置生成

[generate_config.py](generate_config.py) 读取 Il2CppDumper 的 `dump.cs` 或 `script.json`，生成 HET 的运行时配置。使用现有 Python 环境与标准库。

## 预览与写入

从仓库根目录执行，替换输入路径和游戏版本：

```powershell
python tools/rva/generate_config.py --version 5.19.0 --input "D:\你的导出目录\dump.cs" --output app/src/main/assets/runtime.json --stdout
```

确认输出后，去掉 `--stdout` 才会写入 `--output` 指定文件。脚本内保留个人机器的默认路径，其他环境应显式传入参数。

| 参数 | 用途 |
| --- | --- |
| `--version` | 目标游戏版本 |
| `--input` | 实际目标二进制对应的 dump.cs 或 script.json |
| `--output` | 配置路径，也作为保留旧 Layout 的读取来源 |
| `--stdout` | 输出预览，不写文件 |

## 更新边界

已有输出文件时，只替换 `GameVersion` 与 `RuntimeConfigs.RVA`，保留原 `Layout`。输出不存在时会走完整配置生成分支，两种情况不能混为一谈。

生成成功不表示偏移已在设备上验证。应独立核对游戏版本、ABI、目标库身份、函数签名、字段布局与文本列号；原 Layout 被保留也不表示它适用于新版游戏。验证要求见 [游戏层开发文档](../../docs/game-layer-development.md)。
