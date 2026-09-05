# dex 工具链

写这套工具是因为板上没有 `dexdump`/`baksmali`，而每一个根因都要先精确定位到
「哪个 dex、哪个方法、哪条指令」才能做等长替换。纯 Python，无依赖。

| 脚本 | 用途 |
|---|---|
| `dexcore.py`   | 最小 dex 解析器（string / type / proto / field / method / class_def），被其它脚本 `exec()` 引入 |
| `dexfind.py`   | 按类名定位 `<clinit>` 的 `code_off` |
| `dexdis.py`    | 反汇编指定 `code_off`（带完整指令宽度表） |
| `dexclass.py`  | 列出某类全部方法 / 反汇编指定方法 |
| `findstr.py`   | 按字符串常量反查引用它的方法与 pc（例如定位 `"appName is empty"` 的抛出点） |
| `whichmethod.py` | 由文件偏移反查所属方法（用来确认一处字节差属于哪个方法） |
| `neutralize.py`| 通用：把方法入口改成 `return-void`（仅限 `sget-object changeQuickRedirect` 开头的 hotfix 前导） |

单点补丁脚本（`patch_lancet.py` / `patch_applog.py` / `patch_apk.py` / `add_dex.py` /
`strip_npth.py`）是逐个根因排查时写的，逻辑已全部并入
`patches/patch_base_apk.py`，保留作为推导记录。

`patch_tttext.py` 是唯一仍需单独执行的：它等长改写 `libtttext_lite.so` 的两个
dlopen 名（`libicuuc.so`→`libwlicu.so`、`libicui18n.so`→`libwlic18n.so`），
输入输出路径在脚本头部常量里，按需改。
