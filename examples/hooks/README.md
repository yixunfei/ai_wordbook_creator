# Hook 示例脚本

该目录包含两类脚本：

1. 基础模板（`python/` 与 `javascript/`）
2. 内置常用脚本（`builtin/`，也是高级设置脚本浏览默认目录）

## 目录说明
- `python/pre_request_hook.py`: 请求前 Hook 模板（`PRE_REQUEST`）
- `python/post_response_hook.py`: 响应后 Hook 模板（`POST_RESPONSE`）
- `python/post_parsed_hook.py`: 解析后 Hook 模板（`POST_PARSED`）
- `javascript/hook_template.js`: JavaScript 单文件模板（支持三阶段）

- `builtin/README.md`: 内置脚本总览
- `builtin/python/post_response_json_minify.py`: JSON 压缩
- `builtin/python/post_response_json_transform.py`: JSON 格式转换
- `builtin/python/post_response_regex_cleanup.py`: 换行/正则清理
- `builtin/python/post_parsed_dedup_sort_shuffle.py`: 去重/排序/乱序
- `builtin/python/tools/wordlist_merge_dedup_sort_shuffle.py`: 多源文件增补工具（离线）

## 使用建议
1. 在 UI `高级设置` 中选择对应阶段 Hook 并启用。
2. 脚本语言与文件类型一致（例如 `.py` 选 `PYTHON`）。
3. 脚本路径建议使用绝对路径。
4. 首次接入建议 `timeout=30s`。

## 输入输出约定
- 输入：主程序通过 `stdin` 传入 JSON。
- 输出：脚本通过 `stdout` 返回 JSON。
- 不要向 `stdout` 打印调试日志；调试信息请写入 `stderr`。

