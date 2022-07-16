package de.maxhenkel.voicechat.voice.server;

import de.maxhenkel.voicechat.Voicechat;
import de.maxhenkel.voicechat.intercompatibility.CommonCompatibilityManager;
import de.maxhenkel.voicechat.net.NetManager;
import de.maxhenkel.voicechat.net.PlayerStatePacket;
import de.maxhenkel.voicechat.voice.common.ClientGroup;
import de.maxhenkel.voicechat.voice.common.PlayerState;
import net.minecraft.entity.player.ServerPlayerEntity;
import net.minecraft.server.MinecraftServer;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class PlayerStateManager {

    private final ConcurrentHashMap<UUID, PlayerState> states;
    private final Server voicechatServer;

    public PlayerStateManager(Server voicechatServer) {
        this.voicechatServer = voicechatServer;
        this.states = new ConcurrentHashMap<>();
        CommonCompatibilityManager.INSTANCE.onPlayerLoggedIn(this::onPlayerLoggedIn);
        CommonCompatibilityManager.INSTANCE.onPlayerLoggedOut(this::onPlayerLoggedOut);
        CommonCompatibilityManager.INSTANCE.onServerVoiceChatConnected(this::onPlayerVoicechatConnect);
        CommonCompatibilityManager.INSTANCE.onServerVoiceChatDisconnected(this::onPlayerVoicechatDisconnect);

        CommonCompatibilityManager.INSTANCE.getNetManager().updateStateChannel.setServerListener((server, player, handler, packet) -> {
            PlayerState state = states.get(player.getUUID());

            if (state == null) {
                state = defaultDisconnectedState(player);
            }

            state.setDisconnected(voicechatServer.getConnection(player.getUUID()) == null);
            state.setDisabled(packet.isDisabled());

            states.put(player.getUUID(), state);

            broadcastState(state);
            Voicechat.logDebug("Got state of {}: {}", player.getDisplayName().getString(), state);
        });
    }

    private void broadcastState(PlayerState state) {
        PlayerStatePacket packet = new PlayerStatePacket(state);
        server.getPlayerList().getPlayers().forEach(p -> NetManager.sendToClient(p, packet));
    }

    public void onPlayerCompatibilityCheckSucceeded(ServerPlayerEntity player) {
        PlayerState state = states.getOrDefault(player.getUUID(), defaultDisconnectedState(player));
        states.put(player.getUUID(), state);
        PlayerStatesPacket packet = new PlayerStatesPacket(states);
        NetManager.sendToClient(player, packet);
        Voicechat.logDebug("Setting initial state of {}: {}", player.getDisplayName().getString(), state);
        voicechatServer.getServer().getPlayerList().getPlayers().forEach(p -> NetManager.sendToClient(p, packet));
    }

    private void onPlayerLoggedIn(ServerPlayerEntity player) {
        PlayerState state = defaultDisconnectedState(player);
        states.put(player.getUUID(), state);
        broadcastState(state);
        Voicechat.logDebug("Setting default state of {}: {}", player.getDisplayName().getString(), state);
    }

    private void onPlayerLoggedOut(ServerPlayerEntity player) {
        states.remove(player.getUUID());
        broadcastState(new PlayerState(player.getUUID(), player.getGameProfile().getName(), false, true));
        Voicechat.logDebug("Removing state of {}", player.getDisplayName().getString());
    }

    private void onPlayerVoicechatDisconnect(UUID uuid) {
        PlayerState state = states.get(uuid);
        if (state == null) {
            return;
        }

        state.setDisconnected(true);

        broadcastState(state);
        Voicechat.logDebug("Set state of {} to disconnected: {}", uuid, state);
    }

    private void onPlayerVoicechatConnect(ServerPlayer player) {
        PlayerState state = states.get(player.getUUID());

        if (state == null) {
            return;
        }

        state.setDisconnected(false);

        states.put(player.getUUID(), state);

        broadcastState(state);
        Voicechat.logDebug("Set state of {} to connected: {}", player.getDisplayName().getString(), state);
    }

    @Nullable
    public PlayerState getState(UUID playerUUID) {
        return states.get(playerUUID);
    }

    public static PlayerState defaultDisconnectedState(ServerPlayerEntity player) {
        return new PlayerState(player.getUUID(), player.getGameProfile().getName(), false, true);
    }

    public void setGroup(ServerPlayerEntity player, @Nullable ClientGroup group) {
        PlayerState state = states.get(player.getUUID());
        if (state == null) {
            state = PlayerStateManager.defaultDisconnectedState(player);
            Voicechat.logDebug("Defaulting to default state for {}: {}", player.getDisplayName().getString(), state);
        }
        state.setGroup(group);
        states.put(player.getUUID(), state);
        broadcastState(state);
        Voicechat.logDebug("Setting group of {}: {}", player.getDisplayName().getString(), state);
    }

    public Collection<PlayerState> getStates() {
        return states.values();
    }

}
