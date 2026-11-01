package jp.mc.restapi.api;

import jp.mc.restapi.http.Json;
import jp.mc.restapi.http.Router;
import jp.mc.restapi.http.Scope;
import net.neoforged.fml.ModList;
import net.neoforged.neoforgespi.language.IModInfo;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * 導入済みの Mod と、Bukkit 互換サーバー（Youer など）のプラグイン一覧。
 */
public final class ModApi {
    public void register(Router r) {
        r.get("/mods", Scope.READ, req -> mods());
        r.get("/plugins", Scope.READ, req -> plugins());
    }

    private Object mods() {
        List<Map<String, Object>> list = new ArrayList<>();
        for (IModInfo m : ModList.get().getMods()) {
            list.add(Json.obj(
                    "id", m.getModId(),
                    "name", m.getDisplayName(),
                    "version", m.getVersion().toString(),
                    "description", m.getDescription() == null ? null : m.getDescription().trim()));
        }
        list.sort(Comparator.comparing(m -> (String) m.get("id")));
        return list;
    }

    /** Bukkit API があればリフレクションで呼ぶ（純正 NeoForge では hybrid=false） */
    private Object plugins() {
        Object bukkit = bukkitServer();
        if (bukkit == null) {
            return Json.obj("hybrid", false, "plugins", List.of());
        }
        List<Map<String, Object>> list = new ArrayList<>();
        try {
            Object pm = invoke(bukkit, "getPluginManager");
            Object[] plugins = (Object[]) invoke(pm, "getPlugins");
            for (Object p : plugins) {
                Object desc = call(p, "getDescription");
                list.add(Json.obj(
                        "name", call(p, "getName"),
                        "version", desc == null ? null : call(desc, "getVersion"),
                        "description", desc == null ? null : call(desc, "getDescription"),
                        "authors", desc == null ? null : call(desc, "getAuthors"),
                        "enabled", call(p, "isEnabled")));
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            return Json.obj("hybrid", true, "plugins", list, "error", e.toString());
        }
        return Json.obj("hybrid", true, "bukkit", bukkitInfo(), "plugins", list);
    }

    static Map<String, Object> bukkitInfo() {
        Object bukkit = bukkitServer();
        if (bukkit == null) {
            return null;
        }
        return Json.obj("name", call(bukkit, "getName"), "version", call(bukkit, "getVersion"),
                "bukkitVersion", call(bukkit, "getBukkitVersion"));
    }

    private static Object bukkitServer() {
        try {
            Class<?> c = Class.forName("org.bukkit.Bukkit");
            return c.getMethod("getServer").invoke(null);
        } catch (ReflectiveOperationException | LinkageError e) {
            return null;
        }
    }

    /** 戻り値を JSON にしやすい形（文字列・真偽値・リスト）にして返す。失敗したら null */
    private static Object call(Object target, String method) {
        try {
            Object v = invoke(target, method);
            return v instanceof List<?> l ? List.copyOf(l) : v == null ? null : v instanceof Boolean ? v : v.toString();
        } catch (ReflectiveOperationException | RuntimeException e) {
            return null;
        }
    }

    /**
     * 引数なしのメソッドを呼ぶ。実装クラスが非公開でも呼べるよう、公開インターフェース側の宣言を優先して使う。
     */
    private static Object invoke(Object target, String method) throws ReflectiveOperationException {
        ReflectiveOperationException last = null;
        for (Class<?> c = target.getClass(); c != null; c = c.getSuperclass()) {
            for (Class<?> i : c.getInterfaces()) {
                try {
                    Method m = i.getMethod(method);
                    return m.invoke(target);
                } catch (ReflectiveOperationException e) {
                    last = e;
                }
            }
        }
        try {
            return target.getClass().getMethod(method).invoke(target);
        } catch (ReflectiveOperationException e) {
            throw last != null ? last : e;
        }
    }
}
