import org.graalvm.nativeimage.hosted.Feature;

/**
 * GraalVM Feature that forces deterministic Clojure core initialization
 * before the parallel analysis phase begins.
 *
 * Without this, --initialize-at-build-time=clojure causes native-image to
 * eagerly initialize clojure.* classes in parallel, leading to circular
 * class init deadlocks (RT <-> fn/deftype classes).
 *
 * By forcing RT.init() in beforeAnalysis(), all core namespaces, fn classes,
 * and deftype classes are initialized sequentially on a single thread.
 * When analysis later discovers these classes, they're already initialized.
 */
public class ClojureFeature implements Feature {
    @Override
    public void beforeAnalysis(BeforeAnalysisAccess access) {
        try {
            Class.forName("clojure.lang.RT");
            // Load cream.main to trigger all build-time requires
            Class.forName("cream.main__init");
            // Force clojure.reflect.java__init - clojure.reflect loads it
            // via (load "reflect/java") from source, so the __init class
            // is never class-initialized. When analysis discovers it later,
            // it fails because TypeReference protocol isn't visible in
            // the parallel worker thread context. Force it here.
            Class.forName("clojure.reflect.java__init");
        } catch (ClassNotFoundException e) {
            throw new RuntimeException(e);
        }
    }
}
