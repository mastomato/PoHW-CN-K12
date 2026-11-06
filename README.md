# 知行答题守护 · PoHW-CN-K12

基于 [jagerzhang/PoHW](https://github.com/jagerzhang/PoHW) 的 Android TV 答题守护应用。守护服务会按家长设置的间隔在其他应用上方显示答题页；题库离线可用，支持小学和初中多个学科。

**当前版本：1.0.3（versionCode 20）** · [下载 APK](https://github.com/mastomato/PoHW-CN-K12/releases/download/v1.0.3-cn.1/PoHW-v1.0.3.apk) · [查看 Release](https://github.com/mastomato/PoHW-CN-K12/releases/tag/v1.0.3-cn.1)

## 1.0.3 的答题方式

- 答题页改用与[知行答题 TV](https://github.com/mastomato/zhixing-tv-quiz)一致的浅色卡片、紫色焦点和竖排选项；应用图标与电视横幅也统一为“书本 + 守护盾牌”。
- 遥控器方向键移动选项，确定键立即提交。答对显示绿色反馈并在短暂提示后自动进入下一题；答错显示正确答案和解析，按确定进入下一题。
- 家长设置新增“每轮题数”，可选 1、3、5、10、20 题，默认 10 题。答错也计入本轮题数，并写入错题库。
- 一轮完成后留在答题页显示成绩。按确定开始下一轮；**只有主动按返回键才退出答题页**。退出后守护倒计时重新开始。
- 答对、答错和一轮完成有不同音效。视频暂停与退出后恢复播放仍按家长设置执行。

## 题库

内置 5,735 道四选一题，覆盖 1–9 年级，含 498 道奥数思维题。题库来源包括原项目的 810 道真实题、[cn-math-taxonomy](https://github.com/giszh86/cn-math-taxonomy) 的 3,314 道题，以及程序生成题。按学期筛选时，未标注学期与全学年题目仍可参与；题量不足时先放宽学期，再保留学科范围抽题。

1.0.3 修正了一道英语题的两个等价干扰项，并更新了离线题库版本戳。详细来源和许可见 [NOTICE.md](NOTICE.md) 与 [docs/QUESTION_BANK.md](docs/QUESTION_BANK.md)。题库中包含 CC BY-NC 4.0 内容，**仅供非商业使用**。

## 安装与升级

需要 Android 5.0+，并授予“显示在其他应用上层”权限。电视开启网络 ADB 后可执行：

```bash
adb connect <电视IP>:5555
adb install -r PoHW-v1.0.3.apk
```

**从 1.0.1 或上游发布的 1.0.2 升级需注意签名变化。**旧版签名证书为 `e5816bdd…212286c`，其私钥不在本仓库或当前构建环境中。1.0.3 使用新的发布证书 `42cbf6a7…07cdb`，Android 不允许直接覆盖安装。必须先卸载旧版；卸载会清除旧版的家长设置、答题统计和错题库。之后如继续使用同一发布密钥，可直接覆盖升级。

装好后在“家长设置”选择年级、学期、出题频率和每轮题数，设置家长密码，并按需开启开机自启。不同电视的厂商自启动权限仍需在系统设置中允许。

## 从源码构建

需要 JDK 17、Android SDK Platform 33/34、Build Tools 34 及 Python 3。执行：

```bash
export JAVA_HOME=/path/to/jdk17
export ANDROID_HOME=/path/to/android-sdk
bash app/build.sh
```

脚本运行静态布局检查、题库校验、JVM 行为检查，再编译、签名和验证 APK。发布签名密钥不入库；使用自己的密钥时设置 `POHW_KEYSTORE` 与 `POHW_KEYSTORE_PASS`。图标和横幅由 `tools/branding/make_icons.py` 生成，生成时需要 Pillow。

## 限制

- 在线题库源不带学期字段，学期筛选仅影响内置离线题库。
- 初中语文和一年级英语题量较少，筛选过窄时会按题库兜底规则放宽范围。
- “暂停视频 / 恢复播放”依靠音频焦点与媒体键请求，不保证所有播放器都响应。

应用代码沿用 MIT 许可；第三方题库有单独许可。参见 [LICENSE](LICENSE) 与 [NOTICE.md](NOTICE.md)。
