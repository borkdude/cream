import java.lang.reflect.Method;
import java.util.Base64;

/**
 * Crema crashes with a NullPointerException while verifying a runtime-loaded
 * class that merges a class type with an interface type.
 *
 * OldMerge below is a class file of major version 49, so it carries no
 * StackMapTable and the verifier has to infer frames. Its one method is:
 *
 *   public static Object m(boolean b) {
 *       Object o;
 *       if (b) { o = new java.util.HashMap(); }
 *       else   { o = java.util.Collections.emptyList(); }
 *       return o;
 *   }
 *
 * Verifying the join after the if merges java/util/HashMap with the interface
 * java/util/List.
 */
public class VerifierRepro {

    private static final String OLD_MERGE = "yv66vgAAADEAFAEACE9sZE1lcmdlBwABAQAQamF2YS9sYW5nL09iamVjdAcAAwEAAW0BABUoWilMamF2YS9sYW5nL09iamVjdDsBABFqYXZhL3V0aWwvSGFzaE1hcAcABwEABjxpbml0PgEAAygpVgwACQAKCgAIAAsBABVqYXZhL3V0aWwvQ29sbGVjdGlvbnMHAA0BAAllbXB0eUxpc3QBABIoKUxqYXZhL3V0aWwvTGlzdDsMAA8AEAoADgARAQAEQ29kZQAhAAIABAAAAAAAAQAJAAUABgABABMAAAAhAAIAAgAAABUamQAOuwAIWbcADEynAAe4ABJMK7AAAAAAAAA=";

    static final class Loader extends ClassLoader {
        Class<?> define(String name, byte[] bytes) {
            return defineClass(name, bytes, 0, bytes.length);
        }
    }

    public static void main(String[] args) throws Exception {
        byte[] bytes = Base64.getDecoder().decode(OLD_MERGE);
        Class<?> c = new Loader().define("OldMerge", bytes);
        Method m = c.getMethod("m", boolean.class);
        System.out.println("m(true) = " + m.invoke(null, true));
        System.out.println("m(false) = " + m.invoke(null, false));
    }
}
