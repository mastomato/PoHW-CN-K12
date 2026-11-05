import android.content.Context;
import com.jager.tvquiz.SettingsStore;

/** Grade range, grade labels and the all-subjects switch of the full K12 catalog. */
public class GradeScopeTest {

    static int pass = 0, fail = 0;

    static void ok(String name, boolean cond) {
        if (cond) { pass++; System.out.println("[PASS] " + name); }
        else { fail++; System.out.println("[FAIL] " + name); }
    }

    public static void main(String[] args) {
        Context.resetAll();
        SettingsStore st = new SettingsStore(new Context());

        ok("G1 default grade is 3", st.grade() == 3);
        ok("G2 default label is primary grade 3", "小学 3 年级".equals(st.gradeLabel()));
        ok("G3 MIN_GRADE=1 MAX_GRADE=9",
                SettingsStore.MIN_GRADE == 1 && SettingsStore.MAX_GRADE == 9);

        st.grade(9);
        ok("G4 grade 9 accepted", st.grade() == 9);
        ok("G5 grade 9 label is junior high 3", "初中 3 年级".equals(st.gradeLabel()));
        st.grade(7);
        ok("G6 grade 7 label is junior high 1", "初中 1 年级".equals(st.gradeLabel()));

        st.grade(99);
        ok("G7 upper bound clamped", st.grade() == 9);
        st.grade(0);
        ok("G8 lower bound clamped", st.grade() == 1);
        ok("G9 grade 1 label is primary 1", "小学 1 年级".equals(st.gradeLabel()));

        ok("G10 default is three core subjects only", !st.allSubjects());
        st.allSubjects(true);
        ok("G11 all-subjects switch turns on", st.allSubjects());
        st.allSubjects(false);
        ok("G12 all-subjects switch turns off", !st.allSubjects());

        st.grade(8);
        st.allSubjects(true);
        Context.restartProcess();
        SettingsStore st2 = new SettingsStore(new Context());
        ok("G13 grade survives process restart", st2.grade() == 8);
        ok("G14 all-subjects survives process restart", st2.allSubjects());

        System.out.println("----");
        System.out.println("PASS=" + pass + " FAIL=" + fail);
        if (fail == 0) System.out.println("GRADE_SCOPE_VERIFY=PASS");
        else { System.out.println("GRADE_SCOPE_VERIFY=FAIL"); System.exit(1); }
    }
}
