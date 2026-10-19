import com.inkcontrol.core.*;
import java.util.*;

public final class CoreTest {
    static int checks;
    static void check(boolean value) { checks++; if (!value) throw new AssertionError("check " + checks); }
    interface Action { void run() throws Exception; }
    static void reject(Action action) throws Exception {
        try { action.run(); } catch (IllegalArgumentException | IllegalStateException | SecurityException expected) { checks++; return; }
        throw new AssertionError("expected rejection");
    }
    static final class Store implements ConfigStore {
        Configuration value = new Configuration();
        public Configuration load() { return value; }
        public void save(Configuration value) { this.value = value; }
    }
    static void displayAlgorithms() throws Exception {
        Configuration empty = new Configuration();
        Configuration gpu = empty.change("display.gpu", true, "default");
        check(DisplayAlgorithms.scopeForMode(2).equals("clear"));
        for (int mode : new int[]{3, 4, 5, 515, 516, 517}) check(DisplayAlgorithms.scopeForMode(mode).equals("reading"));
        check(DisplayAlgorithms.scopeForMode(518).equals("fast"));
        check(DisplayAlgorithms.scopeForMode(521).equals("extreme"));
        check(DisplayAlgorithms.scopeForMode(7).isEmpty());
        reject(() -> DisplayAlgorithms.hysteresisProperty("reading"));
        reject(() -> DisplayAlgorithms.algorithmProperty("unknown"));
        for (String scope : DisplayAlgorithms.SCOPES) {
            String id = "display.alg." + scope;
            FeatureDefinition f = FeatureCatalog.get(id);
            check(f.role.equals("backend") && f.mode == FeatureDefinition.ApplyMode.HOT && f.dependency.equals("display.gpu"));
            check(f.defaultValue().equals("default"));
            check(DisplayAlgorithms.algorithmFeature(id) && DisplayAlgorithms.scope(id).equals(scope));
            check(DisplayAlgorithms.algorithmProperty(scope).equals("debug.eink.alg." + scope));
            List<String> allowed = DisplayAlgorithms.gray(scope) ? Arrays.asList("default", "stock", "nearest", "bayer16")
                    : scope.equals("video") ? Arrays.asList("default", "blue", "bayer", "stock", "threshold") : Arrays.asList("default", "bayer", "blue", "threshold");
            check(f.values.equals(allowed));
            for (String value : Arrays.asList("default", "stock", "nearest", "bayer16", "bayer", "blue", "threshold", "on"))
                if (!allowed.contains(value)) reject(() -> gpu.change(id, true, value));
            Store store = new Store(); int[] applied = {0};
            FeatureService service = new FeatureService(store, (before, after) -> applied[0]++, feature -> null);
            reject(() -> service.change(id, true, allowed.get(1)));
            check(store.value.revision == 0 && applied[0] == 0);
            store.value = gpu;
            service.change(id, true, allowed.get(1));
            check(store.value.enabled(id) && applied[0] == 1);
            check(service.status(id).equals("待重启"));
            Map<String, StatusSource.Evidence> receipts = new HashMap<>();
            receipts.put("display.gpu", new StatusSource.Evidence(true, "default", "", store.value.revision));
            FeatureService observed = new FeatureService(store, (before, after) -> {}, feature -> receipts.get(feature.id));
            check(observed.status(id).equals("等待运行确认"));
            receipts.put(id, new StatusSource.Evidence(true, allowed.get(1), "", store.value.revision));
            check(observed.status(id).equals("已生效"));
            receipts.put(id, new StatusSource.Evidence(true, "default", "", store.value.revision));
            check(!observed.status(id).equals("已生效"));
            for (String value : allowed) {
                Configuration selected = gpu.change(id, true, value);
                check(DisplayAlgorithms.algorithmOverride(Configuration.parse(selected.text()), scope).equals(value));
                check(DisplayAlgorithms.algorithmOverride(selected.change(id, false, value), scope).equals("default"));
                check(DisplayAlgorithms.algorithmOverride(selected.change("display.gpu", false, "default"), scope).equals("default"));
            }
            if (DisplayAlgorithms.gray(scope)) {
                reject(() -> FeatureCatalog.get("display.hyst." + scope));
                check(!DisplayAlgorithms.supportsHysteresis(gpu, scope));
                continue;
            }
            String hyst = "display.hyst." + scope;
            FeatureDefinition h = FeatureCatalog.get(hyst);
            check(h.values.equals(Arrays.asList("default", "on", "off")));
            check(h.mode == FeatureDefinition.ApplyMode.HOT && h.role.equals("backend") && h.dependency.equals("display.gpu"));
            check(DisplayAlgorithms.scope(hyst).equals(scope));
            check(DisplayAlgorithms.hysteresisProperty(scope).equals("debug.eink.hyst." + scope));
            check(DisplayAlgorithms.hysteresis(gpu, scope) == scope.equals("video"));
            reject(() -> service.change(hyst, true, "blue"));
            store.value = empty;
            reject(() -> service.change(hyst, true, "on"));
            store.value = gpu;
            service.change(hyst, true, "on");
            check(store.value.enabled(hyst));
            for (String alg : allowed) for (String policy : h.values) {
                Configuration selected = gpu.change(id, true, alg).change(hyst, true, policy);
                check(Configuration.parse(selected.text()).text().equals(selected.text()));
                boolean supports = !alg.equals("stock");
                check(DisplayAlgorithms.supportsHysteresis(selected, scope) == supports);
                check(DisplayAlgorithms.hysteresis(selected, scope) == (supports && (policy.equals("on") || policy.equals("default") && scope.equals("video"))));
            }
        }
        Configuration legacy = Configuration.parse("version=1\nrevision=27\ndisplay.gpu=1\nexperiment.video=1\nexperiment.video.value=stock\nlock.dither=1\nlock.dither.value=nearest\n");
        for (String scope : DisplayAlgorithms.SCOPES) check(!legacy.requested("display.alg." + scope));
        check(legacy.value("display.alg.video").equals("default"));
        check(DisplayAlgorithms.algorithm(legacy, "video").equals("stock"));
        check(!DisplayAlgorithms.supportsHysteresis(legacy, "video"));
        Configuration video = legacy.change("display.alg.video", true, "bayer").change("display.hyst.video", true, "off");
        check(DisplayAlgorithms.algorithm(video, "video").equals("bayer") && !DisplayAlgorithms.hysteresis(video, "video"));
        check(video.value("experiment.video").equals("stock") && video.enabled("lock.dither"));
        Configuration back = video.change("display.alg.video", true, "default");
        check(DisplayAlgorithms.algorithm(back, "video").equals("stock") && back.enabled("display.hyst.video"));
        Configuration stock = video.change("display.alg.video", true, "stock").change("display.hyst.video", true, "on");
        check(!DisplayAlgorithms.hysteresis(stock, "video"));
        check(DisplayAlgorithms.hysteresis(stock.change("display.alg.video", true, "blue"), "video"));
        check(stock.requested("display.hyst.video") && stock.value("display.hyst.video").equals("on"));
        Configuration separate = gpu.change("display.alg.fast", true, "blue").change("display.hyst.fast", true, "on");
        check(DisplayAlgorithms.algorithm(separate, "extreme").equals("bayer"));
        check(!DisplayAlgorithms.hysteresis(separate, "extreme"));
        check(DisplayAlgorithms.algorithm(separate, "video").equals("blue"));
        check(DisplayAlgorithms.hysteresis(separate, "video"));
        check(DisplayAlgorithms.algorithm(separate.disableAll(), "fast").equals("bayer"));
        check(separate.disableAll().value("display.alg.fast").equals("blue"));
        for (String scope : Arrays.asList("clear", "reading")) {
            Configuration gray = legacy.change("display.alg." + scope, true, "default");
            check(gray.enabled("lock.dither") && gray.value("lock.dither").equals("nearest"));
            check(DisplayAlgorithms.algorithm(gray, scope).equals("default"));
        }
        reject(() -> Configuration.parse(gpu.text().replace("display.alg.fast.value=default", "display.alg.fast.value=stock")));
        reject(() -> Configuration.parse(gpu.text() + "display.alg.fast.value=blue\n"));
        reject(() -> Configuration.parse(gpu.text() + "display.hyst.clear=1\n"));
    }
    public static void main(String[] args) throws Exception {
        displayAlgorithms();
        Configuration config = new Configuration(); Set<String> ids = new HashSet<>();
        for (FeatureDefinition feature : FeatureCatalog.all()) {
            check(ids.add(feature.id)); check(!config.enabled(feature.id)); check(!config.requested(feature.id));
            for (String value : feature.values) { if (value.equals(FeatureDefinition.CURVE_TOKEN) || value.equals(FeatureDefinition.NUMBER_TOKEN)) { reject(() -> feature.validate(value)); continue; } feature.validate(value); check(Configuration.parse(config.change(feature.id, true, value).text()).value(feature.id).equals(value)); }
            reject(() -> feature.validate("$(id)"));
        }
        check(Configuration.parse(config.text()).text().equals(config.text()));
        FeatureDefinition hold = FeatureCatalog.get("motion.hold");
        String curve = FeatureDefinition.curveValue(new int[]{420, 400, 360, 320, 280, 230, 210, 175, 150});
        hold.validate(curve); check(Configuration.parse(config.change("motion.hold", true, curve).text()).value("motion.hold").equals(curve));
        check(FeatureDefinition.curve(curve)[8] == 150); check(FeatureDefinition.band(2) == 0); check(FeatureDefinition.band(23.9) == 7); check(FeatureDefinition.band(24) == 8);
        for (String bad : new String[]{"curve:", "curve:1,2,3", "curve:1,2,3,4,5,6,7,8,9,10", "curve:1,2,3,4,5,6,7,8,2001", "curve:1,2,3,4,5,6,7,8,-1", "curve:1,2,3,4,5,6,7,8,9;id", "curve:1,2,3,4,5,6,7,8,9\n", "curve:1, 2,3,4,5,6,7,8,9"})
            reject(() -> hold.validate(bad));
        reject(() -> FeatureCatalog.get("motion.steps").validate(curve));
        FeatureDefinition interval = FeatureCatalog.get("refresh.interval");
        check(interval.acceptsNumber() && !interval.acceptsCurve() && FeatureDefinition.number("3") == 3);
        interval.validate("3"); check(Configuration.parse(config.change("refresh.interval", true, "3").text()).value("refresh.interval").equals("3"));
        for (String bad : new String[]{"0", "1000", "abc", "-1", "@number", "", "3.5", " 3", "@curve"}) reject(() -> interval.validate(bad));
        reject(() -> FeatureCatalog.get("motion.hold").validate("3"));
        reject(() -> Configuration.parse(config.text() + "alien=1\n"));
        reject(() -> Configuration.parse(config.text() + "version=1\n"));
        reject(() -> Configuration.parse(config.text().replace("revision=0", "revision=-1")));
        reject(() -> Configuration.parse(config.text().replace("display.gpu=0", "display.gpu=x")));
        reject(() -> Configuration.parse(config.text().replace("version=1", "version=2")));
        reject(() -> Configuration.parse(config.text().replace("\n", "\r\n")));
        Store store = new Store(); final int[] applied = {0};
        final StatusSource.Evidence[] evidence = {null};
        FeatureService service = new FeatureService(store, (before, after) -> { check(after.revision == before.revision + 1 || after == before); applied[0]++; }, feature -> evidence[0]);
        reject(() -> service.change("desktop.icons", true, "default")); check(store.value.revision == 0);
        reject(() -> service.change("motion.lock", true, "scan"));
        service.change("desktop.theme", true, "paper"); check(store.value.revision == 1);
        service.change("desktop.icons", true, "default"); service.change("desktop.theme", false, "ink");
        check(store.value.requested("desktop.icons")); check(!store.value.enabled("desktop.icons")); check(service.status("desktop.icons").equals("依赖未满足"));
        check(store.value.value("desktop.theme").equals("ink"));
        service.change("desktop.theme", true, "ink"); check(store.value.enabled("desktop.icons"));
        service.change("display.gpu", true, "default"); check(service.status("display.gpu").equals("待重启"));
        check(service.status("desktop.theme").equals("等待运行确认"));
        evidence[0] = new StatusSource.Evidence(true, "ink", "", store.value.revision); check(service.status("desktop.theme").equals("已生效"));
        evidence[0] = new StatusSource.Evidence(true, "ink", "", store.value.revision - 1); check(!service.status("desktop.theme").equals("已生效"));
        evidence[0] = new StatusSource.Evidence(true, "paper", "", store.value.revision); check(!service.status("desktop.theme").equals("已生效"));
        evidence[0] = new StatusSource.Evidence(true, "ink", "测试异常", store.value.revision); check(service.status("desktop.theme").startsWith("异常"));
        evidence[0] = null; service.disableAll();
        evidence[0] = new StatusSource.Evidence(true, "ink", "", store.value.revision - 1); check(service.status("desktop.theme").equals("等待停用确认")); evidence[0] = null;
        for (FeatureDefinition feature : FeatureCatalog.all()) check(!store.value.enabled(feature.id));
        check(store.value.value("desktop.theme").equals("ink")); check(applied[0] == 6);
        FeatureService failing = new FeatureService(store, (before, after) -> { throw new java.io.IOException("Root拒绝"); }, f -> null);
        try { failing.change("lock.style", true, "8"); throw new AssertionError(); } catch (java.io.IOException expected) { checks++; }
        check(store.value.enabled("lock.style")); check(failing.status("lock.style").equals("等待运行确认"));
        check(RuntimeIdentity.matches(123, "456", "rc1", 4, "123", "456", "rc1", 4));
        check(!RuntimeIdentity.matches(123, "455", "rc1", 4, "123", "456", "rc1", 4));
        check(!RuntimeIdentity.matches(122, "456", "rc1", 4, "123", "456", "rc1", 4));
        check(!RuntimeIdentity.matches(123, "456", "rc0", 4, "123", "456", "rc1", 4));
        check(!RuntimeIdentity.matches(123, "456", "rc1", 3, "123", "456", "rc1", 4));
        check(!RuntimeIdentity.matches(123, "", "rc1", 4, "123", "", "rc1", 4));
        check(ConditionalRestore.owns("8", "8")); check(!ConditionalRestore.owns("2", "8")); check(!ConditionalRestore.owns("8", null));
        for (int style = -2; style <= 10; style++) check(ConditionalRestore.stockLockStyle(style) == (style >= 0 && style <= 4 ? style : 0));
        check(CallerPolicy.read(10100, 10100, 10139, 10063)); check(CallerPolicy.read(1000, 10100, 10139, 10063));
        check(CallerPolicy.read(10139, 10100, 10139, 10063)); check(CallerPolicy.read(10063, 10100, 10139, 10063));
        check(!CallerPolicy.read(10199, 10100, 10139, 10063)); check(!CallerPolicy.read(0, 10100, -1, -1));
        check(CallerPolicy.report("system", 1000, 10139, 10063)); check(!CallerPolicy.report("system", 10100, 10139, 10063));
        check(CallerPolicy.report("launcher", 10063, 10139, 10063)); check(!CallerPolicy.report("launcher", 10139, 10139, 10063));
        check(!CallerPolicy.report("evil", 1000, 10139, 10063)); check(!CallerPolicy.report("systemui", 10139, -1, 10063));
        String sha = "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad";
        Integrity.verifySha256(new java.io.ByteArrayInputStream(new byte[]{97,98,99}), sha); checks++;
        reject(() -> Integrity.verifySha256(new java.io.ByteArrayInputStream(new byte[]{97,98,100}), sha));
        reject(() -> Integrity.verifySha256(new java.io.ByteArrayInputStream(new byte[0]), "invalid"));
        System.out.println("core checks passed: " + checks);
    }
}
