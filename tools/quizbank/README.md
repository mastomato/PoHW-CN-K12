# quizbank —— 内置题库工具

两个零第三方依赖的 Python 脚本，负责把开源 K12 纲目变成 App 能直接读的 SQLite。

## build_bank.py

```bash
python3 tools/quizbank/build_bank.py \
    --lc /path/to/learning-content \
    --out app/assets/quiz.db \
    --stats docs/BANK_STATS.md \
    --seed 20261104 \
    --per-cell 30
```

| 参数 | 说明 |
| --- | --- |
| `--lc` | `RedSamerai/learning-content` 仓库目录（必需） |
| `--out` | 输出 SQLite 路径，默认 `app/assets/quiz.db` |
| `--stats` | 可选，写出统计 Markdown |
| `--seed` | 随机种子，固定后结果完全可复现 |
| `--per-cell` | 每个年级每科生成的程序题数，默认 30 |

产物：`quiz.db`、`quiz.db.version`（版本戳，App 用来判断是否升级）。
脚本内置 `verify_db()`，任一题不合法即 `BUILD_BANK = FAIL` 并以非零码退出。

## verify.py

```bash
python3 tools/quizbank/verify.py app/assets/quiz.db
```

独立复现 App 端的题目合法性检查、随机抽题压测、算式答案重算、
知识点覆盖度与篡改检测。详见 [`docs/QUESTION_BANK.md`](../../docs/QUESTION_BANK.md)。

## 为什么不用在线 API

早期版本试过把题库做成 HTTP 服务，结论是：上游本身没有题目，在线化只是把
「本地生成」搬到服务器上，还额外引入网络、配额、密钥与缓存一致性问题。
现在题库随 APK 预置、App 本地随机抽题，零网络即可工作。
在线题库源仍作为**可选能力**保留在 App 设置里，由使用者自行部署。
