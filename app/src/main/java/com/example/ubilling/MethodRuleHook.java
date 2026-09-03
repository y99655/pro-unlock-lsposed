package com.example.ubilling;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.StringTokenizer;

import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;

/**
 * 【E】配置化精确返回值 Hook（RuleMethodHook）—— 支持“按 类.方法 -> 指定返回值”
 * 的方式强制改写任意 App 里某个具体方法的返回结果。
 *
 * ============================================================================
 * 为什么需要它（与 UVip/UBilling/NetLabHook 等的分工）：
 *   前面几通道分别是“SP 关键词盲扫 / Billing 回灌 / 网络与 JS”。
 *   但真实世界很多 App（如“我的”/cn.ms 一类）的会员判定是【具名业务方法】——
 *   例如 cn.ms.util.CommonUtil.getLingPaiZuanShi() 返回“是否已购灵派钻石”。
 *   这类判定不经 SharedPreferences、不走 Billing，也不带会员类构造器位，
 *   上述通道都够不着。要覆盖它就必须【按调用方逆向出的“类.方法”精确 hook】。
 *
 *   本通道提供一个通用、可配置的能力：给定一条规则
 *      类名  -> 方法名(可按参数类型限定) -> 目标返回值(字符串表达)
 *   就在目标 App 进程里 hook 该方法并在每次调用后强制返回该值
 *   （返回值会自动按方法真实返回类型做类型转换：boolean/整数/长整/小数/字符串）。
 *
 * ============================================================================
 * 使用场景与边界（必须守住）：
 *   本能力是【中性】的反射级“按名 hook 并改返回值”，与 UVip 等一样，只应在
 *   - 你自己开发/拥有的 App，或
 *   - 明确获授权做安全评估的 App
 *   上做防御自测（验证“你的业务方法若被按名强制改写，能否真的改到放行逻辑”，
 *   据此把关键放行下沉到服务端/加签名等）。请勿配置并用于破解他人收费服务。
 *
 * ============================================================================
 * 配置：
 *   RULES 里每条 = {包.类名, 方法名, 返回值字符串, 参数类型可选}
 *   - 返回值字符串按方法真实返回类型自动转换：
 *       "true"/"false"                 -> boolean
 *       "123" / "-1" / "20250101"      -> 若返回 int/long 则转数字（否则按字符串）
 *       "2099-12-31" / "premium" ...   -> 返回 String 时原样返回
 *   - 若不填参数类型，hook 该类的同名【第一个能转成目标类型】的方法；
 *     若方法有重载需精确定位，用 paramTypes 逗号分隔(如 "java.lang.String"、
 *     "int", 原始类型写 int/long/boolean/double/float 小写即可)。
 *
 *   日志统一 [URule]，含包名 + 命中方法 + 注入值，便于对照哪条规则生效。
 * ============================================================================
 */
public class MethodRuleHook {

    private static final String TAG = "[URule]";

    /**
     * 规则表：{类名, 方法名, 返回值, 参数类型(可空字符串)}。
     * 空 paramTypes 表示“不限定参数（命中首个可转换方法）”。
     * 示例（用户自有 App cn.ms 会员判定——占位，请换成你自己的类.方法）：
     *   {"cn.ms.util.CommonUtil", "getLingPaiZuanShi", "true", ""}
     *   {"cn.ms.util.CommonUtil", "getLingPaiHuangJin", "true", ""}
     *   {"cn.ms.common.vo.SysUserVo", "getLingPai", "2099-12-31", ""}
     */
    public static final String[][] RULES = {
        // {"cn.ms.util.CommonUtil", "getLingPaiZuanShi", "true", ""},
        // {"cn.ms.util.CommonUtil", "getLingPaiHuangJin", "true", ""},
        // {"cn.ms.common.vo.SysUserVo", "getLingPai", "2099-12-31", ""},
    };

    /** 每进程只初始化一次。 */
    private static final Set<String> donePkg = java.util.Collections.synchronizedSet(new HashSet<String>());

    /** 已成功 hook 的方法签名（避免重复挂钩）。 */
    private static final Set<String> hooked = java.util.Collections.synchronizedSet(new HashSet<String>());

    private static volatile int hookedCount = 0;

    /** 入口：目标 App 进程内调用一次（Main.handleLoadPackage 对每个勾选进程调用）。 */
    public static void hook(final ClassLoader cl, final String pkg) {
        if (cl == null || pkg == null) return;
        if (!donePkg.add(pkg)) return;
        if (RULES == null || RULES.length == 0) {
            XposedBridge.log(TAG + " 无配置规则(RULES 为空)，本通道跳过 @ " + pkg);
            return;
        }
        XposedBridge.log(TAG + " 配置化返回值 Hook 已挂载 @ " + pkg + " (规则 " + RULES.length + " 条)");
        for (String[] rule : RULES) {
            if (rule == null || rule.length < 3) continue;
            String clsName = rule[0];
            String mtdName = rule[1];
            String want = rule[2];
            String paramTypes = rule.length >= 4 ? rule[3] : "";
            try {
                applyRule(cl, pkg, clsName, mtdName, want, paramTypes);
            } catch (Throwable t) {
                XposedBridge.log(TAG + " 规则 " + clsName + "." + mtdName + " 失败: " + t);
            }
        }
        XposedBridge.log(TAG + " 配置化 Hook 完成：命中挂钩 " + hookedCount + " 个方法 @ " + pkg);
    }

    private static void applyRule(final ClassLoader cl, final String pkg,
                                  String clsName, String mtdName, final String want, String paramTypes)
            throws Throwable {
        Class<?> c = Class.forName(clsName, false, cl);
        if (c == null) {
            XposedBridge.log(TAG + " 类不存在(可能未加载/被混淆改名): " + clsName + " @ " + pkg);
            return;
        }

        // 目标参数类型（原始类型小写 -> Class）
        Class<?>[] wantParams = parseParamTypes(paramTypes);
        final String wantSig = clsName + "." + mtdName + "(" + paramTypes + ")";
        if (hooked.contains(wantSig)) return;

        List<Method> targets = new ArrayList<>();
        for (Method m : c.getDeclaredMethods()) {
            if (!m.getName().equals(mtdName)) continue;
            if (wantParams == null) {
                targets.add(m);            // 不限参数 -> 全部同名方法都纳入（下面按可转换过滤）
                continue;
            }
            if (matchParams(m.getParameterTypes(), wantParams)) targets.add(m);
        }
        if (targets.isEmpty()) {
            XposedBridge.log(TAG + " 未找到方法(可能不存在/被混淆): " + wantSig + " @ " + pkg);
            return;
        }

        // 逐个尝试挂；按“返回值能否转换成 want”过滤
        int ok = 0;
        for (final Method m : targets) {
            final Class<?> ret = m.getReturnType();
            if (!coercible(ret, want)) continue;
            try {
                final String sig = pkg + "#" + wantSig;
                XposedBridge.hookMethod(m, new XC_MethodHook() {
                    @Override protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                        param.setResult(coerce(ret, want));
                    }
                });
                hooked.add(wantSig);
                hookedCount++;
                ok++;
                XposedBridge.log(TAG + " 命中并挂钩: " + m.getName() + "() : " + ret.getSimpleName()
                        + " -> " + want + " @ " + pkg);
            } catch (Throwable ignore) {
            }
        }
        if (ok == 0) {
            XposedBridge.log(TAG + " 方法存在但返回类型无法匹配目标值 " + want + " : " + wantSig + " @ " + pkg);
        }
    }

    // ------------------------------------------------------------------
    // 参数类型解析 / 匹配
    // ------------------------------------------------------------------
    /** 解析 "java.lang.String,int" -> {String.class,int.class}；空串返回 null(不限)。 */
    private static Class<?>[] parseParamTypes(String s) {
        if (s == null || s.trim().isEmpty()) return null;
        List<Class<?>> out = new ArrayList<>();
        StringTokenizer st = new StringTokenizer(s, ",");
        while (st.hasMoreTokens()) {
            String t = st.nextToken().trim();
            Class<?> c = primitive(t);
            if (c == null) {
                try { c = Class.forName(t); } catch (Throwable ignore) { c = null; }
            }
            if (c != null) out.add(c);
        }
        return out.isEmpty() ? null : out.toArray(new Class<?>[0]);
    }

    private static Class<?> primitive(String t) {
        if (t.equals("int")) return int.class;
        if (t.equals("long")) return long.class;
        if (t.equals("boolean")) return boolean.class;
        if (t.equals("double")) return double.class;
        if (t.equals("float")) return float.class;
        if (t.equals("short")) return short.class;
        if (t.equals("byte")) return byte.class;
        if (t.equals("char")) return char.class;
        return null;
    }

    private static boolean matchParams(Class<?>[] actual, Class<?>[] want) {
        if (actual.length != want.length) return false;
        for (int i = 0; i < actual.length; i++) {
            if (actual[i] != want[i]) return false;
        }
        return true;
    }

    // ------------------------------------------------------------------
    // 返回值类型判断 / 转换
    // ------------------------------------------------------------------
    private static boolean coercible(Class<?> ret, String want) {
        if (ret == boolean.class || ret == Boolean.class) {
            return want.equalsIgnoreCase("true") || want.equalsIgnoreCase("false")
                    || want.equals("1") || want.equals("0");
        }
        if (ret == int.class || ret == Integer.class
                || ret == long.class || ret == Long.class
                || ret == short.class || ret == Short.class
                || ret == byte.class || ret == Byte.class
                || ret == float.class || ret == Float.class
                || ret == double.class || ret == Double.class) {
            return isNumeric(want);
        }
        if (ret == String.class) return true;
        if (ret == char.class || ret == Character.class) {
            return want != null && !want.isEmpty();
        }
        // 其它引用类型：尽量返回原 want 字符串（对象方法少见返回非基础类型的，这里保守跳过）
        return false;
    }

    private static boolean isNumeric(String s) {
        if (s == null) return false;
        try { Double.parseDouble(s.trim()); return true; } catch (NumberFormatException e) { return false; }
    }

    private static Object coerce(Class<?> ret, String want) {
        if (ret == boolean.class || ret == Boolean.class) {
            return want.equalsIgnoreCase("true") || want.equals("1");
        }
        String w = want == null ? "" : want.trim();
        if (ret == int.class) return (int) Math.round(Double.parseDouble(w));
        if (ret == Integer.class) return Integer.valueOf((int) Math.round(Double.parseDouble(w)));
        if (ret == long.class) return (long) Double.parseDouble(w);
        if (ret == Long.class) return Long.valueOf((long) Double.parseDouble(w));
        if (ret == short.class) return (short) Math.round(Double.parseDouble(w));
        if (ret == Short.class) return Short.valueOf((short) Math.round(Double.parseDouble(w)));
        if (ret == byte.class) return (byte) Math.round(Double.parseDouble(w));
        if (ret == Byte.class) return Byte.valueOf((byte) Math.round(Double.parseDouble(w)));
        if (ret == float.class) return (float) Double.parseDouble(w);
        if (ret == Float.class) return Float.valueOf((float) Double.parseDouble(w));
        if (ret == double.class) return Double.parseDouble(w);
        if (ret == Double.class) return Double.valueOf(w);
        if (ret == char.class) return want.charAt(0);
        if (ret == Character.class) return Character.valueOf(want.charAt(0));
        // 默认按字符串返回（方法返回 Object / 其它引用时原样）
        return want;
    }

    /** 辅助：对返回 Object 的方法强制按 want 字符串返回（需在规则里确保对象语义）。 */
    public static void logSkippedForObject(String cls, String m, String reason) {
        XposedBridge.log(TAG + " 跳过 " + cls + "." + m + " (返回 Object/其它引用，无法安全转类型): " + reason);
    }
}
