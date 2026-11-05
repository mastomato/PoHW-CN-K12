import android.content.Context;
import com.jager.tvquiz.AnswerPolicy;
import com.jager.tvquiz.BootPolicy;
import com.jager.tvquiz.Hashes;
import com.jager.tvquiz.LaunchPolicy;
import com.jager.tvquiz.SettingsStore;
import com.jager.tvquiz.Subjects;

/** v6 regression: wrong-answer swap policy, boot policy, per-subject editions, playback flags. */
public class FeatureV6Test {

    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    public static void main(String[] args) {
        // ---------- AnswerPolicy: 答错 2 次换题 ----------
        ok("A1 correct index accepted", AnswerPolicy.isCorrect(2, 2));
        ok("A2 wrong index rejected", !AnswerPolicy.isCorrect(1, 2));
        ok("A3 out-of-range pick rejected",
                !AnswerPolicy.isCorrect(-1, 2) && !AnswerPolicy.isCorrect(4, 2));
        ok("A4 invalid answerIndex rejected", !AnswerPolicy.isCorrect(2, 9));
        ok("A5 WRONG_LIMIT is 2", AnswerPolicy.WRONG_LIMIT == 2);
        ok("A6 no swap at 0 wrong", !AnswerPolicy.shouldSwap(0));
        ok("A7 no swap at 1 wrong", !AnswerPolicy.shouldSwap(1));
        ok("A8 swap at 2 wrong", AnswerPolicy.shouldSwap(2));
        ok("A9 swap at 3 wrong", AnswerPolicy.shouldSwap(3));
        ok("A10 hint shows one left", AnswerPolicy.wrongHint(1).contains("再错 1 次"));
        ok("A11 swapped hint mentions swap", AnswerPolicy.wrongHint(2).contains("换一题"));

        // ---------- BootPolicy: 开机自启白名单与开关组合 ----------
        ok("B1 BOOT_COMPLETED accepted", BootPolicy.isBootAction(BootPolicy.BOOT));
        ok("B2 QUICKBOOT accepted", BootPolicy.isBootAction(BootPolicy.QUICKBOOT));
        ok("B3 HTC QUICKBOOT accepted", BootPolicy.isBootAction(BootPolicy.HTC_QUICKBOOT));
        ok("B4 arbitrary action rejected", !BootPolicy.isBootAction("com.jager.tvquiz.START"));
        ok("B5 null action rejected", !BootPolicy.isBootAction(null));
        ok("B6 resume previously enabled guard",
                BootPolicy.shouldStartGuard(true, false, true, true, BootPolicy.BOOT));
        ok("B7 autoGuard forces start",
                BootPolicy.shouldStartGuard(true, true, false, true, BootPolicy.BOOT));
        ok("B8 bootAutostart off blocks everything",
                !BootPolicy.shouldStartGuard(false, true, true, true, BootPolicy.BOOT));
        ok("B9 missing overlay permission blocks",
                !BootPolicy.shouldStartGuard(true, true, true, false, BootPolicy.BOOT));
        ok("B10 neither flag set blocks",
                !BootPolicy.shouldStartGuard(true, false, false, true, BootPolicy.BOOT));
        ok("B11 forged action blocks",
                !BootPolicy.shouldStartGuard(true, true, true, true, "android.intent.action.VIEW"));

        // ---------- Subjects ----------
        ok("S1 core subject names",
                "数学".equals(Subjects.name(0)) && "语文".equals(Subjects.name(1))
                        && "英语".equals(Subjects.name(2))
                        && "其他学科".equals(Subjects.name(3)));
        ok("S2 norm clamps out-of-range",
                Subjects.norm(99) == Subjects.OTHER && Subjects.norm(-7) == Subjects.OTHER);
        ok("S3 edition keys are per-subject",
                Subjects.COUNT == 4
                        && !Subjects.editionKey(0).equals(Subjects.editionKey(1))
                        && !Subjects.editionKey(1).equals(Subjects.editionKey(2))
                        && !Subjects.editionKey(2).equals(Subjects.editionKey(3)));

        // ---------- Hashes ----------
        ok("H1 SHA-256(abc) standard vector",
                "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad"
                        .equals(Hashes.sha256Hex("abc")));
        ok("H2 null hash is empty", "".equals(Hashes.sha256Hex((String) null)));
        ok("H3 same input same key", Hashes.sha256Hex("q|A|B|C|D").equals(
                Hashes.sha256Hex("q|A|B|C|D")));
        ok("H4 different input different key", !Hashes.sha256Hex("q|A|B|C|D").equals(
                Hashes.sha256Hex("q|A|B|C|E")));

        // ---------- SettingsStore: 分学科教材版本 ----------
        Context.resetAll();
        SettingsStore st = new SettingsStore(new Context());
        ok("E1 default editions are generic",
                "通用(课标)".equals(st.editionFor(0))
                        && "通用(课标)".equals(st.editionFor(1))
                        && "通用(课标)".equals(st.editionFor(2))
                        && "通用(课标)".equals(st.editionFor(3)));
        st.setEditionIndexFor(Subjects.CHINESE, 7);   // 沪教版
        ok("E2 chinese edition = 沪教版", "沪教版".equals(st.editionFor(Subjects.CHINESE)));
        ok("E3 math edition untouched", "通用(课标)".equals(st.editionFor(Subjects.MATH)));
        st.setEditionIndexFor(Subjects.MATH, 1);      // 人教版
        st.setEditionIndexFor(Subjects.ENGLISH, 5);   // 外研版
        ok("E4 three independent editions",
                "人教版".equals(st.editionFor(Subjects.MATH))
                        && "沪教版".equals(st.editionFor(Subjects.CHINESE))
                        && "外研版".equals(st.editionFor(Subjects.ENGLISH)));
        ok("E5 legacy edition() maps to math",
                st.edition().equals(st.editionFor(Subjects.MATH))
                        && st.editionIndex() == st.editionIndexFor(Subjects.MATH));
        st.setEditionIndexFor(Subjects.OTHER, 99);
        ok("E6 edition index clamped",
                st.editionIndexFor(Subjects.OTHER) == SettingsStore.EDITIONS.length - 1);
        st.setEditionIndexFor(Subjects.OTHER, -5);
        ok("E7 negative index clamped", st.editionIndexFor(Subjects.OTHER) == 0);
        Context.restartProcess();
        SettingsStore st2 = new SettingsStore(new Context());
        ok("E8 editions survive process restart",
                "人教版".equals(st2.editionFor(Subjects.MATH))
                        && "沪教版".equals(st2.editionFor(Subjects.CHINESE))
                        && "外研版".equals(st2.editionFor(Subjects.ENGLISH)));

        // ---------- SettingsStore: 开机 / 播放开关 ----------
        ok("F1 bootAutostart default false (parent opt-in)", !st2.bootAutostart());
        ok("F2 autoGuardOnBoot default true (launch = guard)", st2.autoGuardOnBoot());
        st2.bootAutostart(true);
        st2.autoGuardOnBoot(false);
        ok("F3 boot flag toggles", st2.bootAutostart() && !st2.autoGuardOnBoot());
        Context.restartProcess();
        SettingsStore st3 = new SettingsStore(new Context());
        ok("F4 boot flags survive process restart",
                st3.bootAutostart() && !st3.autoGuardOnBoot());
        ok("P1 pauseOnQuiz default on", st3.pauseOnQuiz());
        ok("P2 resumePlayback default on", st3.resumePlayback());
        st3.pauseOnQuiz(false);
        st3.resumePlayback(false);
        ok("P3 playback flags toggle", !st3.pauseOnQuiz() && !st3.resumePlayback());
        Context.restartProcess();
        SettingsStore st4 = new SettingsStore(new Context());
        ok("P4 playback flags survive process restart",
                !st4.pauseOnQuiz() && !st4.resumePlayback());

        // ---------- LaunchPolicy：APP 启动即守护 ----------
        ok("L1 cold start + configured + permission -> auto guard",
                LaunchPolicy.shouldAutoGuard(true, true, true, false));
        ok("L2 warm resume never re-enables guard",
                !LaunchPolicy.shouldAutoGuard(false, true, true, false));
        ok("L3 parent opt-out respected",
                !LaunchPolicy.shouldAutoGuard(true, false, true, false));
        ok("L4 missing overlay permission blocks auto guard",
                !LaunchPolicy.shouldAutoGuard(true, true, false, false));
        ok("L5 already running is not started twice",
                !LaunchPolicy.shouldAutoGuard(true, true, true, true));
        ok("L6 all four gates combined",
                LaunchPolicy.shouldAutoGuard(true, true, true, false)
                        && !LaunchPolicy.shouldAutoGuard(false, true, true, false));

        System.out.println("----");
        System.out.println("PASS=" + pass + " FAIL=" + fail);
        if (fail == 0) System.out.println("FEATURE_V6_VERIFY=PASS");
        else { System.out.println("FEATURE_V6_VERIFY=FAIL"); System.exit(1); }
    }
}
