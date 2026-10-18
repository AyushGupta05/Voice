package sc.fidelis.testkit;

import java.util.Arrays;
import java.util.Comparator;
import java.util.List;

import org.junit.jupiter.api.ClassOrderer;
import org.junit.jupiter.api.ClassOrdererContext;

/**
 * Runs test classes in the order named by the configuration parameter {@code fidelis.test.classOrder} (comma-separated
 * simple or fully qualified class names; unlisted classes keep their relative order after the listed ones). Used by
 * {@code tools/test.py --class-order A,B} to reproduce an order dependence a seeded random run exposed.
 */
public final class ExplicitClassOrder implements ClassOrderer {
    @Override
    public void orderClasses(ClassOrdererContext context) {
        List<String> order = Arrays.stream(context.getConfigurationParameter("fidelis.test.classOrder").orElse("").split(","))
                .map(String::trim).filter(s -> !s.isEmpty()).toList();
        if (order.isEmpty()) return;
        context.getClassDescriptors().sort(Comparator.comparingInt(d -> {
            String fqcn = d.getTestClass().getName();
            String simple = d.getTestClass().getSimpleName();
            int i = order.indexOf(fqcn);
            if (i < 0) i = order.indexOf(simple);
            return i < 0 ? order.size() : i;
        }));
    }
}
