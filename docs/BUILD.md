# 构建与校验（BUILD）

`structurereset`（Forge 1.20.1 附加模组）的构建与校验方式。两条路径互相独立：离线校验不需要
Forge classpath，发布 jar 必须由 Gradle 产出。

## 1. 环境要求

| 依赖 | 版本 | 说明 |
| --- | --- | --- |
| JDK | **17** | ForgeGradle 6 / Forge 1.20.1 要求；PATH 上的 `java` 版本不符时先设 `JAVA_HOME` |
| Python | 3.12（仅标准库） | 运行 `tools/check_all.py` |
| 网络 | 仅 Gradle 构建与首次依赖缓存需要 | `repo1.maven.org`（JUnit）、`maven.latvian.dev`（KubeJS）、Forge maven |

## 2. 一条命令跑完全部离线校验

```text
python tools/check_all.py             # 依次执行全部检查，一行一个结论，任一失败即非 0 退出
python tools/check_all.py --list      # 只打印检查项与各自命令，不执行、不编译（只读）
python tools/check_all.py --selftest  # 额外证明每个校验器都能拒绝注入的缺陷（防空转）
python tools/check_all.py --only docs,kubejs   # 只跑其中几项（--skip 用法相同）
```

14 项检查：`privacy`、`skeleton`、`pure-logic`、`api`、`api-surface`、`docs`、`appearance`、
`coverage`、`kubejs`、`syntax`、`resources`、`tests`、`package`、`jar`。其中 `syntax`、`tests`、
`package` 会用 JDK 17 的 javac 真正编译，`package` 产出
`build/dist/structurereset-<mod_version>-dev.jar`，`jar` 再把资源规则在该产物内部重跑一遍。
**该 dev jar 只含资源与不依赖 Forge 的类，发布件必须走第 3 节。**

离线工具链的两条长期约定：

* `tools/lib/` 是工具自己的依赖缓存（JUnit / gson / KubeJS，已在 `.gitignore` 内）。缺失时
  `tools/run_tests.py` 会自动下载 JUnit；KubeJS 编译期 jar 用
  `python tools/validate_checks.py kubejs-fetch` 重新取回（该文件与 `gradle.properties` 的版本必须一致）。
* `com.structurereset.api` 下的类必须**不依赖 Minecraft / Forge**，否则离线 `tests` 无法编译
  （`pure-logic` 检查会失败）。

## 3. 发布构建（Gradle）

```text
set JAVA_HOME=C:\Program Files\Java\jdk-17
gradlew.bat build            # Linux/macOS: ./gradlew build
```

产物为 `build/libs/structurereset-<mod_version>.jar`（`jar.finalizedBy('reobfJar')` 会另产一份重混淆件）。
已知事项：

* ForgeGradle 复用**陈旧 daemon** 时可能报 `Project services has been closed`：加 `--no-daemon`，或先执行 `gradlew --stop`。
* 清空 Gradle 缓存后需要能访问 `maven.minecraftforge.net`；走代理时在 `~/.gradle/gradle.properties` 写
  `systemProp.https.proxyHost` / `systemProp.https.proxyPort`。
* KubeJS 的传递依赖 `dev.architectury:architectury-forge` 不在 Forge / Maven Central 上，
  `build.gradle` 已加入 `https://maven.architectury.dev/`。

## 4. 提交前（贡献者）

```text
git config core.hooksPath .githooks                  # 每个克隆执行一次
python tools/validate_checks.py privacy --staged     # 被跟踪文件不得含私人截图、日志、存档、密钥或本机绝对路径
```

确认无误时可用 `git commit --no-verify` 跳过钩子。设计约束（不使用 Mixin、Cataclysm 与 KubeJS 均非硬依赖）
只维护在 [README](../README.md) 的兼容性一节。
