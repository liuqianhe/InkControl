import com.inkcontrol.core.*;
import java.lang.reflect.*;
import java.util.*;

/** 编译时只依赖核心；运行时另将 Guide.java 编入类路径，无需 Android 环境。 */
public final class GuideTest {
    static int checks;
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("guide check " + checks); }
    static Object field(Object object, String name) throws Exception {
        Field f = object.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(object);
    }
    static Object call(Class<?> type, String name, String id) throws Exception {
        Method m = type.getDeclaredMethod(name, String.class); m.setAccessible(true); return m.invoke(null, id);
    }
    @SuppressWarnings("unchecked")
    public static void main(String[] args) throws Exception {
        Class<?> type = Class.forName("com.inkcontrol.ui.Guide");
        Field categoriesField = type.getDeclaredField("CATEGORIES"); categoriesField.setAccessible(true);
        Map<?, ?> categories = (Map<?, ?>) categoriesField.get(null);
        Set<String> navigation = new HashSet<>();
        for (Object category : categories.values()) for (String[] section : (String[][]) field(category, "sections")) {
            for (int i = 1; i < section.length; i++) {
                check(navigation.add(section[i])); FeatureCatalog.get(section[i]);
            }
        }
        for (FeatureDefinition feature : FeatureCatalog.all()) {
            Object entry = call(type, "get", feature.id);
            check(!field(entry, "summary").toString().isEmpty());
            check(!field(entry, "what").toString().isEmpty());
            Map<?, ?> options = (Map<?, ?>) field(entry, "options");
            if (!options.keySet().equals(new HashSet<>(feature.values)))
                throw new AssertionError(feature.id + " guide options " + options.keySet() + " != " + feature.values);
            checks++;
            for (Object option : options.values()) check(!field(option, "name").toString().isEmpty());
            for (String[] term : (List<String[]>) field(entry, "terms")) check(term[1] != null && !term[1].isEmpty());
            String destination = (String) call(type, "destination", feature.id);
            check(navigation.contains(destination));
        }
        check(!navigation.contains("experiment.video"));
        for (String scope : DisplayAlgorithms.SCOPES) {
            check(navigation.contains("display.alg." + scope));
            check(!navigation.contains("display.hyst." + scope));
        }
        check(call(type, "destination", "experiment.video").equals("display.alg.video"));
        check(call(type, "destination", "display.hyst.fast").equals("display.alg.fast"));
        Object video = call(type, "get", "display.alg.video");
        Object stock = ((Map<?, ?>) field(video, "options")).get("stock");
        check(field(stock, "detail").toString().contains("不支持独立迟滞"));
        Method status = type.getDeclaredMethod("selectionStatus", boolean.class, String.class); status.setAccessible(true);
        check(status.invoke(null, false, "关闭").equals("沿用当前默认"));
        check(status.invoke(null, false, "已生效").equals("沿用当前默认"));
        for (String receipt : Arrays.asList("等待运行确认", "等待停用确认", "待重启", "异常：测试"))
            check(status.invoke(null, false, receipt).equals(receipt));
        check(status.invoke(null, true, "关闭").equals("关闭"));
        System.out.println("guide checks passed: " + checks);
    }
}
