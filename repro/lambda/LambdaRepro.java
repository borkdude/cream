import java.util.Objects;

// Repro: LambdaConversionException "is not a subtype of itself" in Crema.
// Mimics commonmark-java's Parser$Builder.getInlineParserFactory() pattern.
// Works on JVM, crashes in Crema: ./cream LambdaRepro.java
public class LambdaRepro {

    interface Context {
        String name();
    }

    interface Factory {
        String create(Context context);
    }

    static class Impl {
        final String value;
        Impl(Context ctx) { this.value = ctx.name().toUpperCase(); }
        public String toString() { return value; }
    }

    static class Builder {
        private Factory factory;

        Factory getFactory() {
            // This is the pattern that triggers the bug:
            // Objects.requireNonNullElseGet with a lambda returning a method ref
            // whose parameter is a custom interface (Context).
            return Objects.requireNonNullElseGet(factory, () -> (context) -> new Impl(context).toString());
        }
    }

    public static void main(String[] args) {
        Factory f = new Builder().getFactory();
        System.out.println(f.create(() -> "hello"));
    }
}
