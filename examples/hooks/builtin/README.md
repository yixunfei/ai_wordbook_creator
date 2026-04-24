# 内置常用脚本

该目录是高级设置中“脚本浏览”的默认打开目录，提供常用脚本模板。

## Hook 脚本（可直接挂到高级设置）
- `python/post_response_json_minify.py`
  - JSON 压缩（去空格、去换行）
  - 适合 `POST_RESPONSE`
- `python/post_response_json_transform.py`
  - JSON 格式转换（对象转数组、数组转按“单词”索引对象）
  - 适合 `POST_RESPONSE`
- `python/post_response_regex_cleanup.py`
  - 换行/空白/正则清理
  - 适合 `POST_RESPONSE`
- `python/post_parsed_dedup_sort_shuffle.py`
  - 结果条目去重、排序、乱序
  - 适合 `POST_PARSED`

## 工具脚本（离线预处理）
- `python/tools/wordlist_merge_dedup_sort_shuffle.py`
  - 多源文件增补、去重、排序、乱序输出
  - 适合在任务启动前预处理输入词表

## 注意
- Hook 脚本统一：`stdin` 输入 JSON，`stdout` 输出 JSON。
- 不要向 `stdout` 打印调试日志；调试请打印到 `stderr`。

