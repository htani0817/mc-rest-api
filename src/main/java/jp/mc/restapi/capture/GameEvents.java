package jp.mc.restapi.capture;

import jp.mc.restapi.http.Json;
import jp.mc.restapi.util.DailyLog;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.neoforged.bus.api.EventPriority;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.CommandEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.entity.living.LivingDeathEvent;
import net.neoforged.neoforge.event.entity.player.AdvancementEvent;
import net.neoforged.neoforge.event.entity.player.PlayerEvent;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * ゲーム内の出来事を EventHub（SSE）とチャット履歴へ流し、出来事の履歴ファイルにも書く。
 * 他の Mod に取り消されたイベントは流さないよう、優先度 LOWEST で受ける。
 */
public final class GameEvents {
    private final EventHub hub;
    private final ChatHistory chat;
    private final DailyLog file;

    public GameEvents(EventHub hub, ChatHistory chat, DailyLog file) {
        this.hub = hub;
        this.chat = chat;
        this.file = file;
    }

    /** SSE に流し、履歴ファイルに {time, type, ...} の 1 行で書く */
    private void emit(String type, Map<String, Object> data) {
        if (file != null) {
            Map<String, Object> line = new LinkedHashMap<>();
            line.put("time", OffsetDateTime.now().toString());
            line.put("type", type);
            line.putAll(data);
            file.write(Json.GSON.toJson(line));
        }
        hub.publish(type, data);
    }

    public void register() {
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, ServerChatEvent.class, this::onChat);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, PlayerEvent.PlayerLoggedInEvent.class, this::onJoin);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, PlayerEvent.PlayerLoggedOutEvent.class, this::onLeave);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, LivingDeathEvent.class, this::onDeath);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, AdvancementEvent.AdvancementEarnEvent.class, this::onAdvancement);
        NeoForge.EVENT_BUS.addListener(EventPriority.LOWEST, CommandEvent.class, this::onCommand);
    }

    private static Map<String, Object> player(Player p) {
        return Json.obj("name", p.getPlainTextName(), "uuid", p.getStringUUID());
    }

    private void onChat(ServerChatEvent e) {
        if (e.isCanceled()) {
            return;
        }
        chat.add(e.getUsername(), e.getPlayer().getStringUUID(), e.getRawText(), "player");
    }

    private void onJoin(PlayerEvent.PlayerLoggedInEvent e) {
        Map<String, Object> m = player(e.getEntity());
        if (e.getEntity() instanceof ServerPlayer sp) {
            m.put("ip", sp.getIpAddress());
        }
        emit("join", m);
    }

    private void onLeave(PlayerEvent.PlayerLoggedOutEvent e) {
        emit("leave", player(e.getEntity()));
    }

    private void onDeath(LivingDeathEvent e) {
        if (e.isCanceled() || !(e.getEntity() instanceof ServerPlayer sp)) {
            return;
        }
        Map<String, Object> m = player(sp);
        m.put("message", sp.getCombatTracker().getDeathMessage().getString());
        m.put("dimension", sp.level().dimension().identifier().toString());
        m.put("position", Json.obj("x", Math.round(sp.getX()), "y", Math.round(sp.getY()), "z", Math.round(sp.getZ())));
        emit("death", m);
    }

    private void onAdvancement(AdvancementEvent.AdvancementEarnEvent e) {
        var holder = e.getAdvancement();
        // 表示の無い進捗（レシピ解放など）は流さない
        holder.value().display().ifPresent(display -> {
            Map<String, Object> m = player(e.getEntity());
            m.put("advancement", holder.id().toString());
            m.put("title", display.getTitle().getString());
            m.put("type", display.getType().getSerializedName());
            emit("advancement", m);
        });
    }

    private void onCommand(CommandEvent e) {
        if (e.isCanceled()) {
            return;
        }
        var parse = e.getParseResults();
        emit("command", Json.obj(
                "source", parse.getContext().getSource().getTextName(),
                "command", parse.getReader().getString()));
    }

    /** サーバーの起動・停止などを流す */
    public void server(String state) {
        emit("server", Json.obj("state", state));
    }
}
