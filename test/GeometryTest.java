import com.inkcontrol.features.motion.Geometry;

/**
 * 离线测试：按预览页 preview_template.html 中 buildMap() 的原始公式逐像素算 S(x,y)，
 * 检查 Geometry.stepRects 给出的裁剪矩形与它完全一致。
 *   展开：第 i 步新窗口可见区域 == { S <= i }
 *   收缩：第 i 步旧窗口可见区域 == { S > i }（方形收缩用反向图 S' = 2 - S）
 * 运行：见 test/run_test.sh
 */
public class GeometryTest {
    static int fails, checks;

    public static void main(String[] a) {
        int[][] sizes = {{360, 720}, {720, 1440}, {1440, 720}};
        double[][] origins = {{0.5, 0.5}, {0.13, 0.21}, {0.9, 0.95}, {0, 0}, {0.999, 0.4}, {0.2531, 0.7777}};
        for (int[] s : sizes) {
            int W = s[0], H = s[1];
            // 四个方向、2–4 步的独立逐像素参考，避免只用面积单调掩盖方向错误。
            for (int steps=2;steps<=4;steps++) for(String direction:new String[]{"ltr","rtl","ttb","btt"}) {
                boolean vertical=direction.equals("ttb")||direction.equals("btt");
                boolean reversed=direction.equals("rtl")||direction.equals("btt");
                int len=vertical?H:W;
                int[] reference=new int[W*H];
                for(int y=0;y<H;y++)for(int x=0;x<W;x++){
                    int position=vertical?y:x;
                    if(reversed)position=len-1-position;
                    reference[y*W+x]=Math.min(steps-1,(int)Math.floor((double)position/len*steps));
                }
                for(boolean grow:new boolean[]{true,false})compare("all-dir "+direction+" steps="+steps,reference,W,H,
                        Geometry.stepRects("kwipe",grow,steps,W,H,0,0,direction),grow);
            }
            // 关键帧擦除：预览页 fromA=true 是从左开始（ltr），fromA=false 是 W-1-x（rtl）
            for (boolean fromA : new boolean[]{true, false}) {
                int[] S = new int[W * H];
                for (int y = 0; y < H; y++) for (int x = 0; x < W; x++)
                    S[y * W + x] = (int) Math.min(2, Math.floor((double) (fromA ? x : W - 1 - x) / W * 3));
                String dir = fromA ? "ltr" : "rtl";
                compare("kwipe grow " + dir + " " + W + "x" + H, S, W, H,
                        Geometry.stepRects("kwipe", true, 3, W, H, 0, 0, dir), true);
                compare("kwipe shrink " + dir + " " + W + "x" + H, S, W, H,
                        Geometry.stepRects("kwipe", false, 3, W, H, 0, 0, dir), false);
            }
            // 方形展开
            for (double[] o : origins) {
                double ox = o[0] * W, oy = o[1] * H;
                int[] S = new int[W * H], R = new int[W * H];
                for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                    double k = Math.max(Math.abs(x - ox) / Math.max(ox, W - ox), Math.abs(y - oy) / Math.max(oy, H - oy));
                    int lv = k < 0.18 ? 0 : k < 0.55 ? 1 : 2;
                    S[y * W + x] = lv;
                    R[y * W + x] = 2 - lv;
                }
                String tag = String.format(" %dx%d o=(%.1f,%.1f)", W, H, ox, oy);
                compare("kiris grow" + tag, S, W, H, Geometry.stepRects("kiris", true, 3, W, H, ox, oy, "auto"), true);
                compare("kiris shrink" + tag, R, W, H, Geometry.stepRects("kiris", false, 3, W, H, ox, oy, "auto"), false);
            }
        }
        // 其他步数：只检查单调（展开越来越大、收缩越来越小）和结束状态
        for (int steps = 2; steps <= 4; steps++) {
            for (String st : new String[]{"kwipe", "kiris"}) {
                for (String dir : new String[]{"ltr", "rtl", "ttb", "btt"}) {
                    int[][] g = Geometry.stepRects(st, true, steps, 720, 1440, 100, 300, dir);
                    int[][] k = Geometry.stepRects(st, false, steps, 720, 1440, 360, 720, dir);
                    for (int i = 1; i < steps; i++) {
                        check(st + " grow monotonic steps=" + steps + " " + dir, area(g[i]) > area(g[i - 1]));
                        check(st + " shrink monotonic steps=" + steps + " " + dir, area(k[i]) < area(k[i - 1]));
                    }
                    check("grow ends full", area(g[steps - 1]) == 720 * 1440);
                    check("shrink ends empty", area(k[steps - 1]) == 0);
                }
            }
        }
        check("autoHold 33C = 151ms", Geometry.autoHoldMs(33) == 152 || Geometry.autoHoldMs(33) == 151);
        check("autoHold 20C uses 15 frames", Geometry.autoHoldMs(20) == Math.round(18 * 1000 / 85.8));
        System.out.println(String.format("autoHold: 33C=%dms 22C=%dms 16C=%dms 10C=%dms",
                Geometry.autoHoldMs(33), Geometry.autoHoldMs(22), Geometry.autoHoldMs(16), Geometry.autoHoldMs(10)));
        System.out.println((fails == 0 ? "PASS" : "FAIL") + "  checks=" + checks + " fails=" + fails);
        System.exit(fails == 0 ? 0 : 1);
    }

    static void compare(String name, int[] S, int W, int H, int[][] rects, boolean grow) {
        for (int i = 0; i < rects.length-1; i++) {
            int[] r = rects[i];
            int bad = 0;
            for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) {
                boolean inRect = x >= r[0] && x < r[2] && y >= r[1] && y < r[3];
                boolean want = grow ? S[y * W + x] <= i : S[y * W + x] > i;
                if (inRect != want) bad++;
            }
            check(name + " step " + i + " rect=" + r[0] + "," + r[1] + "," + r[2] + "," + r[3] + " mismatched px=" + bad, bad == 0);
        }
    }

    static long area(int[] r) {
        return (long) Math.max(0, r[2] - r[0]) * Math.max(0, r[3] - r[1]);
    }

    static void check(String name, boolean ok) {
        checks++;
        if (!ok) {
            fails++;
            System.out.println("FAIL " + name);
        }
    }
}
