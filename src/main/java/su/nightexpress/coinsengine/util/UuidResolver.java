package su.nightexpress.coinsengine.util;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.UUID;

/**
 * Resolves a player's UUID from a name. Tries official Mojang API first (online-mode),
 * then falls back to an offline-mode UUID (name-based) to support cracked servers.
 * VERY EXPERIMENTAL: Use with caution.
 */
public final class UuidResolver {

    private UuidResolver() {}

    public record ResolvedIdentity(@NotNull UUID uuid, @NotNull String exactName, boolean online) {}

    @Nullable
    public static ResolvedIdentity resolvePreferOnline(@NotNull String playerName) {
        ResolvedIdentity online = resolveOnline(playerName);
        if (online != null) return online;
        return resolveOffline(playerName);
    }

    @Nullable
    public static ResolvedIdentity resolveOnline(@NotNull String playerName) {
        try {
            String url = "https://api.mojang.com/users/profiles/minecraft/" + playerName;
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(3000);
            connection.setReadTimeout(3000);

            int code = connection.getResponseCode();
            if (code != 200) return null;

            try (BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8))) {
                StringBuilder sb = new StringBuilder();
                String line;
                while ((line = reader.readLine()) != null) sb.append(line);
                JsonObject obj = JsonParser.parseString(sb.toString()).getAsJsonObject();
                String id = obj.get("id").getAsString();
                String name = obj.get("name").getAsString();
                UUID uuid = fromMojangId(id);
                if (uuid == null) return null;
                return new ResolvedIdentity(uuid, name, true);
            }
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    public static ResolvedIdentity resolveOffline(@NotNull String playerName) {
        try {
            UUID uuid = offlineUuid(playerName);
            return new ResolvedIdentity(uuid, playerName, false);
        } catch (Exception ignored) {
            return null;
        }
    }

    @Nullable
    private static UUID fromMojangId(@NotNull String idNoDashes) {
        if (idNoDashes.length() != 32) return null;
        String withDashes = idNoDashes.replaceFirst(
            "([0-9a-fA-F]{8})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{4})([0-9a-fA-F]{12})",
            "$1-$2-$3-$4-$5"
        );
        return UUID.fromString(withDashes);
    }

    /**
     * Generates the same offline-mode UUID used by CraftBukkit/Spigot:
     * UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(StandardCharsets.UTF_8))
     */
    @NotNull
    public static UUID offlineUuid(@NotNull String playerName) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + playerName).getBytes(StandardCharsets.UTF_8));
    }
}
