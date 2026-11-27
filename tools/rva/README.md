# RVA 配置生成

[generate_config.py](generate_config.py) 读取 Il2CppDumper 的 `dump.cs` 或 `script.json`，生成本 LAH 分支的运行时配置。使用现有 Python 环境与标准库。

## 预览与写入

从仓库根目录执行，替换输入路径和游戏版本：

```powershell
python tools/rva/generate_config.py --version 4.17.2 --input "D:\你的导出目录\dump.cs" --choice-back "D:\你的导出目录\choice-back.json" --output app/src/main/assets/runtime.json --stdout
```

确认输出后，去掉 `--stdout` 才会写入 `--output` 指定文件。`--version` 和 `--input` 必须显式传入。默认输出为当前仓库的 `app/src/main/assets/runtime.json`。使用 `script.json` 时，同目录必须有对应包体的 `dump.cs`，用于读取字段布局。

| 参数 | 用途 |
| --- | --- |
| `--version` | 目标游戏版本 |
| `--input` | 实际目标二进制对应的 dump.cs 或 script.json |
| `--output` | 配置路径，也作为保留旧 Layout 的读取来源 |
| `--choice-back` | 已核验的 `ChoiceBack` 对象 JSON，不含外层 `RuntimeConfigs` |
| `--stdout` | 输出预览，不写文件 |

## 更新边界

已有输出文件时，替换 `GameVersion`、`RuntimeConfigs.RVA` 和 `RuntimeConfigs.ChoiceBack`，用 dump 中的游戏字段更新 `Layout`，保留手动维护的 IL2CPP 容器布局、列号提示和其他自定义字段。每次生成均须通过 `--choice-back` 提供已针对本次 arm64 包体核验的对象，包括 Build ID 和续写地址。相同版本号的重新打包也可能生成不同地址。

`ChoiceBack.ContinuationRVA` 包含 Tap、Text、AddRead、AddHistory、Character、CharacterOff、Background、BackgroundOff、LoadEnd 和 MovePage。显示选项共用 `RVA_ShowSelection`。核对完整方法签名，特别是 Text 使用接收 `AdvCommandText` 的重载，MovePage 使用 `CoStartPage` 协程的 `MoveNext`。核对实际 ELF 的 Build ID、ABI 和入口所属的可执行段后再写入。

`ChoiceBack` 的结构见仓库 `runtime.json`：`BuildId` 是目标 arm64 `libil2cpp.so` 的 GNU build ID；`RVA` 包含 11 个 hook 入口；`SavePageCaller` 是 `CoStartPage.MoveNext` 中页首自动存档调用之后的返回地址；`PageStatus` 只包含回退判断实际使用的四个状态值。函数入口需按完整签名核对，尤其是 `Select` 的重载。调用点必须结合反汇编确认存档时机，不能仅依据函数入口或版本间地址差值生成。

这些数据由维护者核验后提供，脚本不会从 dump 猜测库 build ID 或函数内部调用点。HET 写入资源时检查配置格式；游戏启动后复制配置，已初始化的游戏需重启才会使用新值。字段名、方法调用协议或存档流程变化仍需修改 HET 代码。

生成成功不表示偏移已在设备上验证。应独立核对游戏版本、ABI、目标库身份、函数签名、字段布局与文本列号；保留的手动布局值也需要针对新版游戏核验。验证要求见 [游戏层开发文档](../../docs/game-layer-development.md)。
