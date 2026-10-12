# 构建与离线校验（BUILD）

本文件描述 **Universal-Boss-Structure-Reset**（`structurereset`，Forge 1.20.1 附加模组）的两种构建/校验路径：

* **标准 Gradle 构建**：供在有正常网络、且装有 JDK 17 的机器上产出发布用 jar；
* **离线校验工具链**：本工作区**实际执行过**的唯一证据来源（受限网络下无法运行 ForgeGradle）。

---

## 1. 环境要求

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| JDK | **17**（本项目用 `C:\Program Files\Java\jdk-17`，javac 17.0.12 已验证） | ForgeGradle 6 / Forge 1.20.1 需要 17；本机 PATH 上的 `java` 是 JDK 25，**不要**用它跑 Gradle |
| Python | 3.12（标准库即可，无需 pip 包） | 运行 `tools/*.py` 离线工具 |
| 网络 | 仅 `repo1.maven.org` 与 `maven.latvian.dev` 可低速访问 | 只用于缓存 JUnit console-standalone 与 KubeJS jar |

---

## 2. 标准构建（已在本机成功执行）

```
python tools/build.py            # 等价于 gradlew.bat build（内部固定用 JDK 17 启动 wrapper）
python tools/build.py clean
python tools/build.py build --offline
```

或直接：

```
set JAVA_HOME=C:\Program Files\Java\jdk-17
gradlew.bat build
```

产物：`build/libs/structurereset-<mod_version>.jar`（`jar.finalizedBy('reobfJar')` 会再产出重混淆件）。

### 实测结论：本机已经真正跑通 Gradle 构建

启用本机代理后（Clash 系统代理 `127.0.0.1:7897`），ForgeGradle 6 全流程（下载 Minecraft、MCP 反编译、重映射、javac、reobfJar）**已成功执行**：

* `python tools/build.py build --no-daemon` → `BUILD SUCCESSFUL`，产出可加载的 `build/libs/structurereset-<mod_version>.jar`
  （约 116 KB，102 条目 / 46 个 class，含 `META-INF/mods.toml`、`kubejs.plugins.txt`、全部资源与 14 条数据包定义）；
* 依赖已全部进入 Gradle 缓存，之后**即使不挂代理**也能在 13 秒左右重建；
* 若要从零构建（例如清空了 Gradle 缓存），必须能访问 `maven.minecraftforge.net`：
  在 `~/.gradle/gradle.properties` 中加入
  `systemProp.https.proxyHost=127.0.0.1` 与 `systemProp.https.proxyPort=7897`（或换成你自己的代理）即可；
* 已知坑：ForgeGradle 复用**陈旧 daemon** 时可能报 `Project services has been closed`
  （它的 toolchain 在依赖解析阶段访问了已关闭的 project 服务）。解决办法：构建时加 `--no-daemon`，或先跑 `gradlew --stop`；
* KubeJS 的传递依赖 `dev.architectury:architectury-forge` 不在 Forge/Maven Central 上，
  `build.gradle` 已加入 `https://maven.architectury.dev/` 仓库。

第 3 节的离线工具链仍然保留：它在没有 Forge classpath 时也能独立校验资源、数据包与纯逻辑层，比反复跑 Gradle 快得多。

---

## 3. 离线校验工具链（本工作区真正的证据来源）

所有脚本都可重复运行、退出码即结论，且都能自检（`--selftest` 用「正例必须通过、反例必须失败」证明校验器本身没有空转）。

### 3.1 `tools/syntax_check.py` — 纯语法检查

```
python tools/syntax_check.py             # exit 0 = src/main/java 下全部 .java 语法正确
python tools/syntax_check.py --selftest  # 正例通过 / 反例(语法错误)必须失败 / 仅解析错误样例必须被忽略
```

实现：用 **JDK 17 的 javac** 执行
`javac -proc:only -XDrawDiagnostics --release 17 -d <临时目录> <全部 .java>`。
只有解析类诊断键（`compiler.err.expected`、`compiler.err.illegal.start.of.expr`、
`compiler.err.class.public.should.be.in.file`、`compiler.err.unclosed.*`、
`compiler.err.premature.eof`、`compiler.err.not.stmt` 等）判为失败；
`cannot find symbol` / `package does not exist` 等**解析类错误被有意忽略**，因为离线环境没有 Minecraft classpath。

### 3.2 `tools/validate.py` — 数据驱动静态校验

```
python tools/validate.py             # exit 0 = 全部资源规则通过
python tools/validate.py --selftest  # 干净样例通过 + 每个缺陷样例都必须非 0
python tools/validate.py --jar       # 把资源规则在 build/dist 下最新 jar 内部重跑一遍
```

规则（只在相关文件存在时生效，规则失败打印 `FAIL: <rule>: <detail>`）：

| rule | 检查内容 |
| --- | --- |
| `json-parse` | `src/main/resources` 下所有 `*.json` 可解析 |
| `mods-toml` | `META-INF/mods.toml` 存在且含 modId / version / displayName / license 与 forge、minecraft 两条依赖 |
| `mod-items` | 扫描 `ModItems.java` 里的 `register("id"` ：每个物品都要有 item 模型、模型引用的贴图、`item.<modid>.<id>` 语言键、以及至少一个产物指向它的配方；扫描到 0 个物品直接失败 |
| `model-refs` | `models/**`、`blockstates/**` 引用的贴图/父模型在包内存在 |
| `structure-resets` | `data/*/structure_resets/**` 含 `structure` 与 `actions`，`actions` 是数组且每项含 `type` |
| `recipes` | `data/*/recipes/**` 含 `type` 且含 `result` 或 `ingredients` |
| `lang-consistency` | `en_us.json` 与 `zh_cn.json` 键集合完全一致 |

自检覆盖的缺陷样例：缺贴图、缺语言键、缺配方、坏 JSON、坏 structure_resets、坏 actions、
缺父模型、缺 blockstate 模型、ModItems 空注册、坏配方、语言键不一致、缺 mods.toml。

### 3.3 `tools/run_tests.py` — 真跑 JUnit 5

```
python tools/run_tests.py             # 编译并执行测试，末行打印 SUMMARY tests=<n> failures=<n> errors=<n>
python tools/run_tests.py --selftest  # 证明「全通过=0 / 断言失败≠0 / 抛异常≠0 / 无测试源=tests=0 且 0」
```

流程：JDK 17 的 javac 把
`src/main/java/com/structurereset/api/**/*.java` 与 `src/test/java/**/*.java` 编译到 `build/test-classes`，
再用 JUnit Platform console-standalone 执行；计数来自真实的 JUnit XML 报告（而不是 grep 源码），
测试数为 0 时判定为失败（防空转）。

> **给后续任务的约定**：`com.structurereset.api` 下的类必须**不依赖 Minecraft/Forge**，
> 否则离线 `run_tests.py`（classpath 只有 JUnit）无法编译它们。

### 3.4 `tools/package.py` — 真实产出可校验归档

```
python tools/package.py             # 产出 build/dist/structurereset-<mod_version>-dev.jar
python tools/package.py --selftest  # 证明纯逻辑类被编入、Minecraft 类被排除、打包是确定性的、真错编译必须中止
```

流程：把 `src/main/java` 下所有 `.java` 交给 JDK 17 的 javac；**只依赖 `java.*` / `javax.*` /
`com.google.gson.*`** 的文件会编译进 `build/classes`，引用 Forge / Minecraft / KubeJS 的文件
（以及依赖它们的文件）被剔除后重试，直到编译通过；然后 `src/main/resources` 的全部资源 + 已编译 class
被写入 jar。写包使用 `zipfile`（等价于 `java.util.zip`）的确定性方式：条目名排序、时间戳与权限固定，
因此**同一棵树打包两次得到逐字节相同的 jar**。javac 若报出「真正的编译错误」（非缺类型），
脚本直接失败，绝不会静默丢文件。

> 该 dev jar **只用于资源与纯逻辑层校验**，不含需要 Forge classpath 才能编译的类（例如 `@Mod` 主类）。
> 发布用 jar 必须由第 2 节的 Gradle 构建产出。

### 3.5 `tools/lib/` — 离线依赖缓存

```
python tools/run_tests.py
```

会在 `tools/lib/` 缺失时，用**浏览器 User-Agent**从 `repo1.maven.org` 下载
`junit-platform-console-standalone-1.10.2.jar` 并缓存（与 `build.gradle` 里的 junit-jupiter 5.10.2 对应）。
同理缓存 `maven.latvian.dev` 的 `kubejs-forge-2001.6.5-build.26.jar` 供编译期检查使用。
这两个 jar 是**唯一**允许联网下载的文件，且**只放在 `tools/lib/`，绝不放进 `src/`**。

### 3.6 `tools/validate_skeleton.py` — 工程骨架与版本号一致性

```
python tools/validate_skeleton.py            # exit 0 = 骨架齐全且版本号自洽
python tools/validate_skeleton.py --selftest # 注入 18 种缺陷，逐一必须非 0
```

12 条规则：必需文件（含 `gradle-wrapper.jar` 确实是 zip 魔数）、`gradle.properties` 的 7 个键值
（`mod_id` / `mod_name` / `mod_version` / `mod_group_id` / `minecraft_version` /
`forge_version` / `kubejs_version`）、`build.gradle`（ForgeGradle `[6.0,6.2)`、official 1.20.1
mappings、`net.minecraftforge:forge:${minecraft_version}-${forge_version}`、
`compileOnly ... kubejs-forge:${kubejs_version}`、junit-jupiter、`jar.finalizedBy('reobfJar')`）、
`settings.gradle`（pluginManagement + Forge maven + rootProject.name）、wrapper
（`gradle-8.8-bin.zip` + 两个启动脚本）、`mods.toml`（用 `tomllib` **真解析** TOML：modLoader /
loaderVersion / license / 唯一的 `[[mods]]` 的四个键 / forge 与 minecraft 两条 `mandatory=true`）、
`pack.mcmeta`（pack_format 15）、lang（en_us 与 zh_cn 键集合一致且全部带 modid）、主类
（package / `MODID` / `@Mod`）、docs（BUILD.md 记录 `gradlew` 构建**且**声明本工作区未跑 Gradle）、
`.gitignore`（`.gradle/` 与 `build/`），以及 modid 在 `gradle.properties` / `mods.toml` /
主类 / `assets/` 四处的一致性。

它只读文件、不写工程、不跑 Gradle，因此可以随时重跑。

---

## 4. 一键验证顺序

在仓库根目录执行：

```
python tools/syntax_check.py
python tools/syntax_check.py --selftest
python tools/validate.py
python tools/validate.py --selftest
python tools/run_tests.py
python tools/run_tests.py --selftest
python tools/package.py
python tools/validate.py --jar
python tools/validate_skeleton.py
python tools/validate_skeleton.py --selftest
```

全部应以退出码 **0** 结束；`package.py` 之后 `build/dist/` 下会有
`structurereset-<mod_version>-dev.jar`，`validate.py --jar` 会在该 jar 内部重跑资源规则。

---

## 5. 设计约束

* **不使用 Mixin**：所有逻辑走 Forge 事件与数据驱动 API。
* **不硬依赖 Cataclysm**：Cataclysm 只在数据包层面被引用（`cataclysm:*` 结构/实体 id 是字符串）。
* **KubeJS 为软依赖**：`build.gradle` 中只有 `compileOnly`，运行时缺失不影响模组加载。
* **资源与数据包可被整合包作者覆盖**：物品贴图、配方、`data/<ns>/structure_resets/` 全部是普通资源/数据文件。

## 提交前检查（贡献者）

仓库是公开的，因此提交前先启用隐私闸门：`ash
git config core.hooksPath .githooks   # 一次即可python tools/check_privacy.py         # 手动运行：扫描被跟踪的文件``n它会拒绝私人截图、日志、存档、密钥与本机绝对路径；确认无误时可用 git commit --no-verify 跳过。

