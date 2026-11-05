# 第三方数据来源与许可

本仓库的 `app/assets/quiz.db` 是多个来源合并而成的题库，**不是单一许可证**。分发或再使用前请逐条确认。

## 1. cn-math-taxonomy（3314 题）

- 来源：https://github.com/giszh86/cn-math-taxonomy
- 许可：**CC BY-NC 4.0**（署名 — 非商业性使用）
- 本仓库使用方式：仅提取 `type == "choice"`、选项数为 4、无插图、答案可解析的题目，剔除依赖课文原文的阅读题、高中题与重复选项题。
- 约束：**不得用于商业用途**。转载/再分发需保留署名与本许可说明。

## 2. PoHW 内置题库（810 题保留）

- 来源：上游 [jagerzhang/PoHW](https://github.com/jagerzhang/PoHW) 的 `app/assets/quiz.db`
- 上游说明：题目由 `tools/quizbank/build_bank.py` 基于 RedSamerai/learning-content 的知识点纲目**参数化生成**，不含第三方真题原文；learning-content 仓库未附 LICENSE。
- 本仓库处理：剔除 4319 道「纲目定位」元数据题，仅保留 810 道可作答的口算/古诗/单词题。

## 3. 参数化生成题（1611 题）

- 来源：本仓库的 `tools/local/build_bank_merge.py` 与 `tools/local/olympiad_gen.py`
- 许可：随本仓库 **MIT**
- 说明：题干由模板生成，答案由程序按公式计算，不含任何外部题目文本，可自由使用。
- 构成：数学口算/单位换算 1002 题、科学常识 111 题、奥数思维 498 题。

## 4. 应用代码

- 上游代码：MIT，Copyright (c) 2026 jagerzhang
- 本仓库改动（学期功能、题库改造、构建脚本）：MIT

## 免责声明

本项目仅供家庭个人学习使用，与上述任何数据来源方、教材出版方均无关联。若权利人认为某部分内容不宜再分发，请开 issue，会立即移除。
