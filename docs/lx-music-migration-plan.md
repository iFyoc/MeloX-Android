# LX Music 音源接入规划

[English](../README.md)

对比对象：`lx-music-mobile`（`/项目/lx-music-mobile`）与 MeloX 现有 `core/provider/lxuser`。

## 现状对比

| 能力 | lx-music-mobile | MeloX 现状 |
| --- | --- | --- |
| 运行时 | 原生 JS 沙箱（`loadScript`），受限 `global.lx` API | QuickJS（`LxUserRuntime`），受限环境 |
| 脚本元数据 | 强校验 `userApi.name/description/version/author/homepage/sources` | `LxUserScriptMetadata` 基本解析，`sources` 未强校验 |
| 音源声明 | `sources` 显式声明每个源（kw/kg/tx/wy/mg/xm）支持的 `type` | 不读取声明，逐源盲试 |
| 质量协商 | 按脚本声明的 `type` 请求，`qualityList` 全局暴露 | `lxQualityFallbacks` 盲降级（flac24bit→flac→320k→128k） |
| 请求能力 | `lx.request` 支持 method/headers/body/form/timeout/二进制 | QuickJS 桥接的 `lx.request`，能力需核对 |
| 脚本过期 | `expirationTime` 提示 + 更新提醒开关 | 无 |
| 失败切换 | `allowToggleSource` 自动换源/换脚本重试 | 单脚本失败即失败 |
| 请求去重 | 同请求合并为单个 promise（`reqPromise`） | 无 |
| 日志 | `lx.log.*` 开关式日志流 | `Log.w` 分散、无开关 |
| 网易兜底 | 官方 musicSdk 各源独立 | `resolveViaNeteaseMatch` 模糊匹配（可能错版本） |

## 主要缺陷

1. **音源/质量未按脚本声明协商**：MeloX 对每个源盲试，脚本不支持时浪费请求、也可能拿到错误音质。
2. **无失败自动换源**：单个脚本失败后不再尝试其它脚本或其它源（官方会 toggle source）。
3. **网易兜底匹配过宽**：仅按标题+时长匹配，可能选中错误版本（live/翻唱）。
4. **脚本过期无提醒**：`expirationTime` 被忽略。
5. **无请求去重**：同一首歌并发请求会重复调用脚本。
6. **日志不可控**：无法在设置中开启/关闭脚本日志。

## 修复规划（按优先级）

1. **读取 `sources` 声明**（高）：导入/加载脚本时解析 `userApi.sources`，播放时只请求声明支持的源与 type；未声明则维持现状兜底。
2. **失败自动换源**（高）：`LxUserPlaybackResolver.resolve` 改为遍历「声明支持该源的脚本」×「质量降级表」，失败继续下一个组合，而不是单脚本单次。
3. **请求去重**（中）：同一 `(script, source, quality, id)` 合并为一次调用。
4. **网易兜底收紧**（中）：匹配时优先 artist 精确 + 时长 ±3s + 排除非 live/cover 标题。
5. **脚本过期提醒**（低）：读取 `expirationTime`，过期时在音乐服务页提示。
6. **日志开关**（低）：设置页增加 LX 脚本日志开关，统一经 `lx.log` 收集。

## 2026-10-28 逐项对照审计与修复

对照对象：`lx-music-mobile v1.9.1`（`user-api-preload.js` + `QuickJS.java` + `core/init/userApi/request.js` + `toOldMusicInfo`）与官方 `lx-music-source` 脚本（2024-06-12 最后提交）。

已修复（`LxUserRuntime.kt` / 新增 `LxUserScriptContract.kt`）：

1. **musicInfo 扁平契约**：官方宿主在脚本边界使用 `toOldMusicInfo()` 的扁平格式——`songmid = meta.songId`，且 tx `strMediaMid`/`albumMid`/数值 `songId`、kg `hash`/`albumId`、`img`、`types`/`_types`、`typeUrl` 全部在**顶层**。MeloX 修复前把 per-source 字段放进 `meta` 嵌套，导致官方 QQ 源拼出 `M500undefined.mp3` 必然失败。现由 `buildLxMusicInfo()` 按契约构建，`meta` 作为附加超集保留。
2. **响应头形状**：`toMultimap()` 的 `Map<String, List<String>>` 改为小写键 + 逗号拼接字符串（fetch `Headers.map` 形状），修复脚本 `resp.headers.cookie` 拿到数组、回传带方括号的问题。
3. **请求错误对象**：回调首参由字符串改为 JS `Error`，`err.message` 可用。
4. **响应体解析**：对齐 `JSON.parse` 尽力解析（裸数字/布尔/字符串/null 也解析）；`data` 展开兼容改为只补缺失键、绝不覆盖真实顶层键。
5. **send/on 语义**：`inited`/`updateAlert` 一次性守卫、`log` 必填与 `updateUrl` 校验、未知事件 reject（原先静默 resolve）；`updateAlert` 内容记录进日志并可经 `sentEvents()` 读取。
6. **AES 模式**：仅接受 `aes-128-cbc`/`aes-128-ecb`，未知 mode 抛错（原先静默按 ECB）。
7. **inited 过滤**：按宿主白名单取交集（仅 kw/kg/tx/wy/mg/local、`type: 'music'`、qualitys ∩ 128k/320k/flac/flac24bit）；actions 允许 musicUrl/lyric/pic（官方仅 local 支持 lyric/pic，MeloX 保留该超集）。
8. **setTimeout**：移除 60 秒上限（官方无上限）。
9. **console**：脚本输出经 URL/token 脱敏后进入 logcat（`MeloXLxScript`），随日志导出可见。
10. **Function.prototype.toString 伪装**：对齐官方，防止脚本指纹识别桥接函数。

有意保留的差异（超集，不修）：musicUrl 结果额外接受 `{url}`/`{data.url}` 对象返回；`meta`/`id`/`title`/`duration` 等附加键；`formData` 修复了官方的实现缺陷；全局对象不做递归冻结（会锁死 MeloX 为 CJS 社区脚本提供的 `module`/`exports` 垫片）。

已验证一致、无需修改：md5（官方原生先 `URLDecoder.decode` 再摘要，与 MeloX round-trip 等价）、aes/rsa/buffer 全部函数、默认 UA、默认 `Accept: application/json`、POST Content-Type 默认、13 秒默认超时与 60 秒上限、响应 `statusCode/statusMessage/url/ok` 字段、取消函数返回值。
