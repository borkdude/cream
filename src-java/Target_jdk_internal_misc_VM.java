import com.oracle.svm.core.annotate.Substitute;
import com.oracle.svm.core.annotate.TargetClass;

@TargetClass(className = "jdk.internal.misc.VM")
public final class Target_jdk_internal_misc_VM {
    @Substitute
    static void initialize() {
    }
}

@TargetClass(className = "jdk.internal.jrtfs.SystemImage")
final class Target_jdk_internal_jrtfs_SystemImage {
    @Substitute
    static String findHome() {
        String home = System.getenv("JAVA_HOME");
        if (home == null) {
            home = System.getProperty("java.home");
        }
        return home;
    }
}
