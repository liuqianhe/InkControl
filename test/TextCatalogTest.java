import com.inkcontrol.i18n.TextCatalog;
import com.inkcontrol.core.Configuration;
import com.inkcontrol.core.DisplayAlgorithms;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.io.StringReader;

/** Exercise composed UI strings while keeping persisted feature values language independent. */
public final class TextCatalogTest {
    private static int checks;
    private static void check(boolean value) { checks++; if (!value) throw new AssertionError("translation check " + checks); }
    private static String decode(String value) { return value.replace("\\n", "\n").replace("\\t", "\t").replace("\\\\", "\\"); }
    public static void main(String[] args) throws Exception {
        TextCatalog catalog;
        try (java.io.Reader reader = Files.newBufferedReader(Paths.get("assets/i18n/en.tsv"), StandardCharsets.UTF_8)) { catalog = new TextCatalog(reader); }
        for (String row : Files.readAllLines(Paths.get("assets/i18n/en.tsv"), StandardCharsets.UTF_8)) {
            if (row.startsWith("#") || row.isEmpty()) continue;
            String[] pair = row.split("\t", -1);
            String zh = decode(pair[0]), en = decode(pair[1]);
            check(catalog.translate(zh, true).equals(en));
            check(catalog.translate(zh, false).equals(zh));
            check(!en.matches("(?s).*[\\p{IsHan}].*"));
        }
        check(catalog.translate("● 已生效 · 快速模式", true).equals("● Active · Fast mode"));
        check(catalog.translate("当前电池温度 -2.5°C → 每张中间画面停留 500 毫秒", true).equals("Battery -2.5°C → hold each intermediate frame for 500 ms"));
        check(catalog.translate("gpu_errors=0; display.alg.fast=blue", true).equals("gpu_errors=0; display.alg.fast=blue"));
        check(catalog.translate("自选歌曲名", true).equals("自选歌曲名"));
        check(catalog.translate(null, true) == null);
        check(catalog.translate("", true).isEmpty());
        String config = new Configuration().text();
        catalog.translate("快速模式", true);
        check(new Configuration().text().equals(config));
        check(DisplayAlgorithms.name("fast").equals("快速模式"));
        try { new TextCatalog(new StringReader("A\tB\nA\tC\n")); throw new AssertionError("duplicate accepted"); }
        catch (java.io.IOException expected) { checks++; }
        System.out.println("TextCatalogTest: " + checks + " checks passed");
    }
}
