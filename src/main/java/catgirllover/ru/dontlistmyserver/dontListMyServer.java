package catgirllover.ru.dontlistmyserver;

import com.comphenix.protocol.PacketType;
import com.comphenix.protocol.ProtocolLibrary;
import com.comphenix.protocol.ProtocolManager;
import com.comphenix.protocol.events.*;
import com.comphenix.protocol.wrappers.WrappedServerPing;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.event.*;
import org.bukkit.event.player.AsyncPlayerPreLoginEvent;
import org.bukkit.event.server.ServerListPingEvent;
import org.bukkit.event.world.*;
import org.bukkit.plugin.java.JavaPlugin;
import org.json.JSONArray;
import org.json.JSONObject;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

public final class dontListMyServer extends JavaPlugin {

    private ProtocolManager protocolManager;
    final ConcurrentMap<String, ConnectionInfo> connectionInfoMap = new ConcurrentHashMap<>();
    private long connectionTtlMs;

    public static final String DEFAULT_COMMENT =
            "Suspicious Minecraft server scan. ip={ip}, port={port}, protocol={protocolVersion}";

    @Override
    public void onEnable() {
        saveDefaultConfig();

        getConfig().addDefault("enabled", true);
        getConfig().addDefault("ABUSEIPDB_API_KEYS", new ArrayList<>());
        getConfig().addDefault("ABUSEIPDB_MODE", "SINGLE_WITH_RETRY");
        getConfig().addDefault("ABUSEIPDB_LIST_CHECK_CATEGORY", "14");
        getConfig().addDefault("BLOCK_USER_ACCESS", true);
        getConfig().addDefault("connection_info_ttl_seconds", 30);
        getConfig().options().copyDefaults(true);
        saveConfig();

        if (!getConfig().getBoolean("enabled", true)) return;

        connectionTtlMs =
                getConfig().getLong("connection_info_ttl_seconds", 30) * 1000L;

        protocolManager = ProtocolLibrary.getProtocolManager();
        protocolManager.addPacketListener(new HandshakeListener());
        protocolManager.addPacketListener(new PingBlocker());

        Bukkit.getPluginManager().registerEvents(
                new EventsWorkerModule(this), this);

        Bukkit.getScheduler().runTaskTimerAsynchronously(
                this,
                this::cleanup,
                20L * 5,
                20L * 10
        );
    }

    private void cleanup() {
        long now = System.currentTimeMillis();
        connectionInfoMap.entrySet()
                .removeIf(e -> now - e.getValue().timestamp > connectionTtlMs);
    }

    // ---------------- PACKETS ----------------

    class HandshakeListener extends PacketAdapter {
        HandshakeListener() {
            super(dontListMyServer.this,
                    ListenerPriority.NORMAL,
                    PacketType.Handshake.Client.SET_PROTOCOL);
        }

        @Override
        public void onPacketReceiving(PacketEvent e) {
            try {
                String ip = e.getPlayer()
                        .getAddress().getAddress().getHostAddress();
                String host = e.getPacket().getStrings().read(0);
                int port = e.getPacket().getIntegers().read(1);
                int proto = e.getPacket().getIntegers().read(0);

                connectionInfoMap.put(ip,
                        new ConnectionInfo(ip, host, port, proto,
                                System.currentTimeMillis()));
            } catch (Exception ignored) {}
        }
    }

    class PingBlocker extends PacketAdapter {
        PingBlocker() {
            super(dontListMyServer.this,
                    ListenerPriority.HIGH,
                    PacketType.Status.Server.SERVER_INFO);
        }

        @Override
        public void onPacketSending(PacketEvent e) {
            WrappedServerPing ping =
                    WrappedServerPing.fromHandle(
                            e.getPacket().getModifier().read(0));

            if (ping == null || ping.getMotD() == null) return;
            if ("blocked".equals(extractPlain(ping.getMotD().getJson())))
                e.setCancelled(true);
        }
    }

    private String extractPlain(String json) {
        try {
            JSONObject o = new JSONObject(json);
            StringBuilder sb = new StringBuilder(o.optString("text", ""));
            JSONArray a = o.optJSONArray("extra");
            if (a != null)
                for (int i = 0; i < a.length(); i++)
                    sb.append(a.getJSONObject(i).optString("text", ""));
            return sb.toString();
        } catch (Exception e) {
            return json;
        }
    }

    // ---------------- DATA ----------------

    static class ConnectionInfo {
        final String ip, host;
        final int port, proto;
        final long timestamp;

        ConnectionInfo(String ip, String host, int port, int proto, long ts) {
            this.ip = ip;
            this.host = host;
            this.port = port;
            this.proto = proto;
            this.timestamp = ts;
        }
    }

    enum AbuseMode {
        SINGLE_WITH_RETRY,
        PARALLEL_ALL_KEYS
    }

    // ---------------- EVENTS ----------------

    static class EventsWorkerModule implements Listener {

        private final dontListMyServer plugin;
        private final List<String> KEYS;
        private final AbuseMode MODE;
        private final String CATEGORY;
        private final String COMMENT;

        private final Map<String, Long> cooldown = new ConcurrentHashMap<>();
        private final ExecutorService executor =
                Executors.newCachedThreadPool();

        private static final long COOLDOWN_MS = 15 * 60 * 1000;

        EventsWorkerModule(dontListMyServer p) {
            plugin = p;
            KEYS = p.getConfig().getStringList("ABUSEIPDB_API_KEYS");
            CATEGORY = p.getConfig()
                    .getString("ABUSEIPDB_LIST_CHECK_CATEGORY", "14");
            COMMENT = p.getConfig()
                    .getString("ABUSEIPDB_COMMENT", DEFAULT_COMMENT);

            AbuseMode m;
            try {
                m = AbuseMode.valueOf(
                        p.getConfig().getString(
                                "ABUSEIPDB_MODE",
                                "SINGLE_WITH_RETRY").toUpperCase());
            } catch (Exception e) {
                m = AbuseMode.SINGLE_WITH_RETRY;
            }
            MODE = m;

            plugin.getLogger().info("AbuseIPDB mode: " + MODE);
            plugin.getLogger().info("AbuseIPDB keys: " + KEYS.size());
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onLogin(AsyncPlayerPreLoginEvent e) {
            if (plugin.getConfig().getBoolean("BLOCK_USER_ACCESS", true))
                e.disallow(
                        AsyncPlayerPreLoginEvent.Result.KICK_OTHER,
                        "Server closed");
        }

        @EventHandler(priority = EventPriority.HIGHEST)
        public void onPing(ServerListPingEvent e) {
            String ip = e.getAddress().getHostAddress();
            ConnectionInfo info = plugin.connectionInfoMap.get(ip);
            if (info == null) return;

            Long last = cooldown.get(ip);
            if (last != null &&
                    System.currentTimeMillis() - last < COOLDOWN_MS)
                return;

            if (MODE == AbuseMode.SINGLE_WITH_RETRY)
                sendSingleRetry(ip, info);
            else
                sendParallel(ip, info);
        }

        // -------- MODES --------

        private void sendSingleRetry(String ip, ConnectionInfo info) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                for (String key : KEYS) {
                    try {
                        if (send(key, ip, info)) {
                            cooldown.put(ip, System.currentTimeMillis());
                            plugin.getLogger().info(
                                    "AbuseIPDB OK (retry) " + ip);
                            return;
                        }
                    } catch (Exception ignored) {}
                }
                plugin.getLogger().warning(
                        "AbuseIPDB ALL KEYS FAILED " + ip);
            });
        }

        private void sendParallel(String ip, ConnectionInfo info) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
                AtomicBoolean success = new AtomicBoolean(false);
                CountDownLatch latch = new CountDownLatch(KEYS.size());

                for (String key : KEYS) {
                    executor.submit(() -> {
                        try {
                            if (send(key, ip, info)) {
                                success.set(true);
                                cooldown.put(ip,
                                        System.currentTimeMillis());
                            }
                        } catch (Exception ignored) {}
                        finally {
                            latch.countDown();
                        }
                    });
                }

                try { latch.await(10, TimeUnit.SECONDS); }
                catch (InterruptedException ignored) {}

                if (success.get())
                    plugin.getLogger().info(
                            "AbuseIPDB OK (parallel) " + ip);
                else
                    plugin.getLogger().warning(
                            "AbuseIPDB PARALLEL FAILED " + ip);
            });
        }

        // -------- HTTP --------

        private boolean send(String key, String ip, ConnectionInfo info)
                throws IOException {

            HttpURLConnection c = (HttpURLConnection)
                    new URL("https://api.abuseipdb.com/api/v2/report")
                            .openConnection();

            c.setRequestMethod("POST");
            c.setDoOutput(true);
            c.setConnectTimeout(5000);
            c.setReadTimeout(5000);

            c.setRequestProperty("Key", key);
            c.setRequestProperty("Accept", "application/json");
            c.setRequestProperty(
                    "Content-Type",
                    "application/x-www-form-urlencoded");

            String body =
                    "ip=" + URLEncoder.encode(ip, "UTF-8") +
                            "&categories=" + CATEGORY +
                            "&comment=" + URLEncoder.encode(
                            COMMENT
                                    .replace("{ip}", ip)
                                    .replace("{port}",
                                            "" + info.port)
                                    .replace("{protocolVersion}",
                                            "" + info.proto),
                            "UTF-8");

            try (OutputStream os = c.getOutputStream()) {
                os.write(body.getBytes(StandardCharsets.UTF_8));
            }

            int code = c.getResponseCode();
            if (code == 200 || code == 201) return true;

            if (code == 401 || code == 403 || code == 429)
                throw new IOException("HTTP " + code);

            return false;
        }
    }
}
