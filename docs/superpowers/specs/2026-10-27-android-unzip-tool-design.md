# Android 解压缩工具 · 设计文档

- 日期：2026-10-27
- 状态：待作者确认
- 应用名：**Unseal**（中文名：启封，2026-10-27 作者选定）；包名沿用脚手架 `ziyao.huang.unzip`
- 相关 mockup：`.superpowers/brainstorm/18964-1793088406/content/flow.html`（五屏流程）、`visual-style.html`（视觉方向）

## 1. 意图与背景

作者在手机上收到带密码的 RAR 包（RAR5）时，现有 Android 工具要么不支持，要么逼用户开会员、塞满广告。市面上的信任缺口是这个项目的立足点：

- **开源**发布在 GitHub，接受捐赠（README + 关于页入口，不做任何付费墙）
- **无广告、无会员、无埋点、无网络权限**（纯离线本身就是可验证的信任卖点）
- 作者自用是硬需求；成功标准是**质量优先**——解压正确性、边界情况处理、稳定不崩、代码干净可扩展。星标与规模随缘。

## 2. 目标 / 非目标 / 反目标

### v1 目标

1. 解压 **ZIP**：ZipCrypto 与 AES 加密；GBK / UTF-8 文件名编码自动处理（Windows 中文环境打包的包不乱码，支持手动切换编码）
2. 解压 **RAR4 / RAR5**：密码、加密文件头（header 加密）、多卷分卷包
3. 极简三步流：选文件 → 内容清单（默认全选、可勾选部分解压）→ 解压到指定目录
4. 密码弹窗：输错可重试；**仅在会话内存中记住**，不落盘
5. 首页「最近打开」本地历史（仅记录用户打开过的文件，**不扫描全盘**）
6. 完成页：结果汇总、解压位置、「打开目录」
7. 系统入口：注册为 zip / rar 等格式的「用其他应用打开」目标（覆盖微信/QQ 接收文件的场景）
8. 全程 SAF 访问文件，**不申请存储权限**

### 版本节奏（已与作者确认）

- **v1**：上述解压功能
- **v1.1**：ZIP 压缩（新建压缩包）
- **v1.2**：集成 7-Zip 源码，支持 7z 解压/压缩

### 非目标（v1 不做）

- 压缩（见版本节奏）、文件管理器、批量解压工具、云盘集成、压缩包内预览文件内容

### 反目标（任何版本都不做）

- 广告、会员/付费墙、埋点/统计 SDK、网络权限、全盘扫描、常驻通知骚扰

## 3. 约束

- **RAR 压缩法律上不可行**：unrar 许可证明确禁止用其源码开发 RAR 压缩功能（ZArchiver 等所有第三方工具同样受限）。README 与后续商店文案需写明「能解 RAR、不能压 RAR」，避免被误认为功能残缺。
- **unrar 许可**：允许免费分发用于解压，需在分发物中附带许可文本。
- **7-Zip 源码为 LGPL**（v1.2 时注意合规方式）。
- **分发**：以 GitHub 为主，可能上架应用商店（Google Play / 国内商店 / F-Droid 待定）。unrar 许可被 F-Droid 归为非自由 → 模块层面隔离 RAR/JNI、预留「无 RAR」lite flavor；Play 侧注意 NDK 16KB 页对齐、前台服务类型声明与 targetSdk 政策。
- 无网络权限 → 检查更新、捐赠、反馈一律通过关于页外链跳浏览器。

## 4. 技术选型（已定，为设计者的决定）

| 项 | 选择 | 理由 |
|---|---|---|
| 语言 / UI | Kotlin 2.x + Jetpack Compose + Material3，单 Activity + Navigation Compose | 现代默认，极简界面开发快 |
| SDK | minSdk 26（Android 8.0+；脚手架当前为 23，将提升），targetSdk 取最新稳定 | 覆盖绝大多数设备 |
| ZIP 处理 | **zip4j**（纯 Java，Apache-2.0） | 统一处理 ZipCrypto / AES / 字符集；`java.util.zip` 无法覆盖加密与编码 |
| RAR 处理 | **官方 unrar C++ 源码 + NDK/JNI**（CMake 构建） | junrar 只支持 RAR4；RAR5 解码官方只有 C++ 源码 |
| 格式识别 | 文件头魔数（`PK\x03\x04`、`Rar!\x1a\x07`、`7z¼¯'\x1a`），扩展名仅作提示 | 可靠路由到对应 extractor，为扩展格式打基础 |
| 异步模型 | Kotlin 协程；长任务用前台服务（进度通知、可取消） | 防止后台被杀 |
| 文件访问 | SAF：`OpenDocument` 打开、`OpenDocumentTree` 选目标目录、持久化 URI 权限 | 零存储权限 |
| 历史存储 | DataStore（uri、名称、大小、时间、是否加密） | 轻量、无外部依赖 |
| 主题 | 自定义品牌色（示意蓝 `#2563eb`），浅/深双主题手工定义，不用动态取色 | 作者选定「品牌蓝活力风」（视觉方向 C） |
| 语言 | 应用内中英双语：`values/` 中文默认 + `values-en/`，遵循系统语言；README 双语 | 作者确认 |
| 测试 | JUnit + Robolectric；fixture 压缩包生成脚本；GitHub Actions CI | 质量优先 |
| 依赖注入 | Hilt 或手工注入 | 留给 builder（§8） |

## 5. 架构

```
ui/ (Compose screens: Home, ArchiveContents, PasswordDialog, Progress, Done)
    ↓
core/ArchiveSession        ← 打开、列出、解压的用例编排（含密码会话缓存、取消、进度）
    ↓
core/format/ArchiveFormat registry   ← 按魔数识别并路由
    ↓
core/format/ArchiveExtractor 接口    ← 每种格式一个实现
    ├─ ZipExtractor (zip4j)
    ├─ RarExtractor (JNI → unrar C++)
    └─ (v1.2: SevenZipExtractor)
```

- 接口草案：

```kotlin
interface ArchiveExtractor {
    suspend fun listEntries(
        source: ArchiveSource,
        passwordProvider: suspend (firstAttempt: Boolean) -> CharArray?,
    ): List<ArchiveEntry>
    suspend fun extract(
        source: ArchiveSource,
        entries: List<ArchiveEntry>,          // 允许部分解压
        dest: Destination,
        passwordProvider: suspend (retry: Boolean) -> CharArray?,  // null = 用户取消
        onProgress: (Progress) -> Unit,
    )
}

data class ArchiveEntry(
    val path: String, val isDir: Boolean,
    val size: Long, val packedSize: Long,
    val encrypted: Boolean, val mtime: Long?,
)
```

- 流程时序（审查后明确）：**加密文件头（header 加密）的 RAR 在列出清单之前就需要密码**——技术上无法无密码列目，所以打开后即检测并提前弹密码框；普通加密条目维持 mockup 时序（点「解压到…」时弹）。重试循环对两个阶段都适用。
- 未来 v1.1 新增并列的 `ArchiveCreator` 接口，UI 与会话层复用。
- 模块划分（为商店分发预留）：`:archive-core`（纯 Kotlin：接口与模型）+ `:archive-zip`（zip4j 封装）+ `:archive-rar`（JNI/unrar）+ `:app`（UI 与会话编排）。未来若上 F-Droid，以去掉 `:archive-rar` 的 lite flavor 出包，其余不变。
- 穿越路径安全（zip slip）必须在 extractor 层统一防护。
- 密码策略：`passwordProvider` 由 UI 层实现（弹窗）；会话内存缓存；密码错→带重试标记重新弹窗。

## 6. 界面（作者已确认）

五屏流程 + 品牌蓝风格（详见 mockup）：

1. **首页**：标题 + 设置；主按钮「打开压缩包」；「最近打开」列表（空态给引导文案）
2. **内容清单**：文件树/面包屑、默认全选可勾选、底部「解压到…」；显示格式与「已加密」徽标
3. **密码弹窗**：密码输入 +「本次使用期间记住」选项
4. **解压中**：百分比、当前文件、已用/预计、取消（前台服务支撑，进程被杀后可提示续传或重来——细节留给 builder）
5. **完成**：结果汇总、解压位置、「打开目录」（intent 拉起文件管理器，兼容性策略与兜底文案留给 builder）、「完成」

- 分卷 RAR：从「用其他应用打开」进来时（如点开 `.part1.rar`），检测到分卷即引导用户选择所在文件夹（OpenDocumentTree），自动找齐目录内所有卷；首页同时提供次级入口「打开文件夹」。
- 解压落地目录：默认在所选目录下新建「以压缩包名命名」的子目录；若包内只有单一根目录，则直接解到所选目录内。
- 「取消 / 部分失败」语义：已完整解出的文件保留、半截文件删除；完成页按 成功 / 跳过 / 失败 汇总（不只有整齐的成功态）。
- intent-filter 注册 zip / rar / 7z 相关 mime 及 `application/octet-stream` 兜底。

## 7. 质量与验收

- 测试矩阵：格式（ZIP/RAR4/RAR5）×（普通 / 加密 / 加密文件头 / 中文文件名 / 分卷 / 畸形包 / 空包）。
- mtime（修改时间）：SAF 无 API 写回时间戳 → v1 **不保留**解压后文件的时间戳；mtime 仅用于清单内展示，README 记为已知限制；测试矩阵不含时间戳校验。
- 编码策略：ZIP 有 EFS 标志 → UTF-8；无标志 → 先按 UTF-8 校验、失败回退 GBK；UI 可手动切换编码并实时刷新列表。
- zip slip、超长路径、重名覆盖策略（默认询问或自动重命名，builder 定，需有明确行为）。
- 1GB 级压缩包流式解压不 OOM；任意时刻可取消，取消后不留半截文件（或清理半截文件）。
- 无崩溃上报（反目标）→ 依赖测试覆盖与防御式边界处理。

## 8. 留给 builder 的决定

- DI 方式（Hilt vs 手工注入）、前台服务类型与 Android 14+ 限制下的具体策略
- 内容清单的树/面包屑组件实现、错误提示文案、深浅主题色板细化
- 应用图标与 README 结构（技术选型表、隐私声明「无网络权限」、捐赠入口、RAR 压缩限制说明）
- 脚手架修正：`allowBackup="true"` 改为 false（最近记录不得进云备份，与隐私定位一致）

## 9. 开放问题

已由脚手架定型（既成事实）：
- 包名 `ziyao.huang.unzip`；应用显示名暂为 "Unzip"；品牌色脚手架已用 `#2563eb`，与本文档示意蓝一致。

2026-10-27 作者已答：
- 分发：GitHub 为主，可能上架应用商店 → 已按「模块隔离 RAR/JNI」设计（见 §3 / §5）。
- 语言：应用内中英双语 + README 双语。
- 捐赠：暂未开通。方案——国内主用**爱发电**（支持微信/支付宝），国际兜底 PayPal.me / Ko-fi；GitHub Sponsors 需确认账号所在地区是否在支持范围（中国大陆不在其中）。关于页放「请作者喝杯咖啡」入口，README 放对应徽标；链接开通后回填。
- 应用名：已定 **Unseal / 启封**（作者 2026-10-27 选定）。

应用名候选（推荐 ①）：
1. **Unseal / 启封** —— 「拆开封印的卷」，语义贴切、简短独特，中英呼应
2. **Unwrap / 拆包** —— 拆礼物式的轻松感，好记友好
3. **开卷 / Kaijuan** —— 「开卷有益」× 压缩「卷」双关，国内语感最佳，英文稍弱
4. Unzip —— 最直白，但商店内同名泛滥、几乎不可搜索

## 10. 设计者拍板记录（审查后，作者可否决）

1. header 加密的 RAR：密码弹窗提前到「列清单之前」（技术必然，非偏好）
2. mtime 不保留（SAF 限制），仅清单展示，README 记为已知限制
3. 首页增加次级入口「打开文件夹」（服务分卷与成批压缩包场景）
4. 解压默认在所选目录下建「以包名命名」子目录；单根目录包直接解到所选目录
5. 取消/失败语义：保留已解出文件、删除半截文件、完成页汇总 成功/跳过/失败
6. minSdk 统一提升为 26；`allowBackup` 关闭
7. 「设置」页最小化为：关于（版本、开源地址、许可文本、捐赠入口）+ 清空最近记录；编码手动切换放在内容清单页
