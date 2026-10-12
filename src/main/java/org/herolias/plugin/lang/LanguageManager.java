package org.herolias.plugin.lang;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.UpdateType;
import com.hypixel.hytale.protocol.packets.assets.UpdateTranslations;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.io.PacketHandler;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.modules.i18n.parser.LangFileParser;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Thin facade over the server's {@link I18nModule}.
 * <p>
 * The server already loads {@code Server/Languages/<lang>/*.lang} from every
 * registered asset pack (this mod's jar included, {@code IncludesAssetPack}),
 * prefixes the keys with the file name ({@code server.}), applies language
 * fallbacks and sends the client its language on connect. This class therefore
 * does not parse or cache the language files itself; it only adds:
 * <ul>
 * <li>the mod's unprefixed key convention ({@code chat.greeting} ->
 * {@code server.chat.greeting});</li>
 * <li>the per-player <em>forced language</em> feature: when a player picks a
 * language in {@code /enchanting} that differs from their client language, the
 * mod's keys are pushed to that client in the chosen language (and restored
 * when they switch back to "default");</li>
 * <li>dynamic translations registered at runtime by addons via
 * {@link #putTranslation(String, String)} (the I18n module has no public API
 * for that), which are pushed to clients on join.</li>
 * </ul>
 * Static keys are <b>not</b> re-sent on join for players using the default
 * language - the client already has them.
 */
public class LanguageManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    /** Prefix the server gives keys from {@code server.lang}. */
    public static final String KEY_PREFIX = "server.";
    public static final String DEFAULT_LANGUAGE = "en-US";
    public static final String DEFAULT_LANGUAGE_OPTION = "default";

    /** Location of the mod's own English file inside the jar (used only for the key list). */
    private static final String MOD_LANG_RESOURCE = "/Server/Languages/en-US/server.lang";

    public static final String[] AVAILABLE_LANGUAGES = {
            "en-US", "de-DE", "es-ES", "fr-FR", "id-ID", "it-IT",
            "nl-NL", "pt-BR", "ru-RU", "sv-SE", "uk-UA"
    };

    /** Runtime-registered translations (unprefixed key -> English value). */
    private final Map<String, String> dynamicTranslations = new ConcurrentHashMap<>();

    /** Players currently receiving a forced (non-client) language. */
    private final Set<UUID> forcedLanguagePlayers = ConcurrentHashMap.newKeySet();

    /** Unprefixed keys shipped in the mod's own .lang files (lazy). */
    private volatile Set<String> modKeys;

    public LanguageManager() {
        LOGGER.atInfo().log("LanguageManager: delegating to the server I18nModule (%d bundled languages)",
                AVAILABLE_LANGUAGES.length);
    }

    // ───────────────────── Lookups ─────────────────────

    /**
     * Resolves the language to use: the forced language if set, otherwise the
     * client's language, otherwise en-US.
     */
    @Nonnull
    public static String resolveTargetLanguage(@Nullable String langCode, @Nullable String clientLangCode) {
        String target = (langCode == null || langCode.isEmpty() || DEFAULT_LANGUAGE_OPTION.equalsIgnoreCase(langCode))
                ? clientLangCode
                : langCode;
        return (target == null || target.isEmpty()) ? DEFAULT_LANGUAGE : target;
    }

    @Nonnull
    private static String prefixed(@Nonnull String key) {
        return key.startsWith(KEY_PREFIX) ? key : KEY_PREFIX + key;
    }

    @Nonnull
    private static String unprefixed(@Nonnull String key) {
        return key.startsWith(KEY_PREFIX) ? key.substring(KEY_PREFIX.length()) : key;
    }

    @Nullable
    private static String i18n(@Nonnull String language, @Nonnull String prefixedKey) {
        I18nModule i18n = I18nModule.get();
        if (i18n == null) {
            return null;
        }
        try {
            return i18n.getMessage(language, prefixedKey);
        } catch (Exception e) {
            return null;
        }
    }

    /**
     * Gets a raw translated string.
     * If langCode is "default" or not found, applies the clientLangCode.
     * Falls back to English, then to dynamic (addon) translations, then returns
     * the key itself if no translation exists.
     */
    @Nonnull
    public String getRawMessage(@Nonnull String key, @Nullable String langCode, @Nullable String clientLangCode) {
        String target = resolveTargetLanguage(langCode, clientLangCode);
        String full = prefixed(key);

        String value = i18n(target, full);
        if (value == null && !DEFAULT_LANGUAGE.equals(target)) {
            value = i18n(DEFAULT_LANGUAGE, full);
        }
        if (value == null) {
            value = dynamicTranslations.get(unprefixed(key));
        }
        return value != null ? value : key;
    }

    /**
     * Gets a localized Message object.
     * <p>
     * For the default language this is a {@link Message#translation(String)} the
     * client resolves itself. For a forced language the string is resolved
     * server-side so it is correct even before the update packet arrived.
     */
    @Nonnull
    public Message getMessage(@Nonnull String key, @Nullable String langCode, @Nullable String clientLangCode) {
        String full = prefixed(key);
        String target = resolveTargetLanguage(langCode, clientLangCode);
        String client = (clientLangCode == null || clientLangCode.isEmpty()) ? DEFAULT_LANGUAGE : clientLangCode;

        if (!target.equals(client)) {
            String raw = getRawMessage(key, langCode, clientLangCode);
            if (!raw.equals(key)) {
                return Message.raw(raw);
            }
        }
        return Message.translation(full);
    }

    /**
     * Retrieves the mod's translation map (unprefixed keys) for a specific target
     * language, including fallbacks and dynamic translations.
     */
    @Nonnull
    public Map<String, String> getTranslationMap(@Nullable String langCode, @Nullable String clientLangCode) {
        Map<String, String> map = new HashMap<>();
        for (String key : getModKeys()) {
            String value = getRawMessage(key, langCode, clientLangCode);
            if (!value.equals(key)) {
                map.put(key, value);
            }
        }
        for (Map.Entry<String, String> e : dynamicTranslations.entrySet()) {
            map.putIfAbsent(e.getKey(), e.getValue());
        }
        return map;
    }

    // ───────────────────── Client updates ─────────────────────

    /**
     * Pushes translations to a client:
     * <ul>
     * <li>forced language (differs from the client language): all of the mod's
     * keys in that language;</li>
     * <li>switching back to default after a forced language: all of the mod's
     * keys in the client language (restores the client's view);</li>
     * <li>otherwise only the dynamic (addon) translations, if any.</li>
     * </ul>
     */
    public void sendUpdatePacket(@Nullable PlayerRef playerRef, @Nullable String customLangCode) {
        if (playerRef == null || !playerRef.isValid())
            return;

        String clientLocale = playerRef.getLanguage();
        if (clientLocale == null || clientLocale.isEmpty()) {
            clientLocale = DEFAULT_LANGUAGE;
        }

        String target = resolveTargetLanguage(customLangCode, clientLocale);
        boolean forced = !target.equals(clientLocale);
        UUID uuid = playerRef.getUuid();

        Map<String, String> payload = new HashMap<>();
        if (forced) {
            forcedLanguagePlayers.add(uuid);
            for (Map.Entry<String, String> e : getTranslationMap(target, clientLocale).entrySet()) {
                payload.put(KEY_PREFIX + e.getKey(), e.getValue());
            }
        } else if (forcedLanguagePlayers.remove(uuid)) {
            // Back to the client's own language: restore its values
            for (Map.Entry<String, String> e : getTranslationMap(null, clientLocale).entrySet()) {
                payload.put(KEY_PREFIX + e.getKey(), e.getValue());
            }
        } else {
            for (Map.Entry<String, String> e : dynamicTranslations.entrySet()) {
                payload.put(KEY_PREFIX + e.getKey(), e.getValue());
            }
        }

        if (!payload.isEmpty()) {
            send(playerRef, payload);
        }
    }

    private static void send(@Nonnull PlayerRef playerRef, @Nonnull Map<String, String> prefixedTranslations) {
        PacketHandler handler = playerRef.getPacketHandler();
        if (handler == null)
            return;
        // AddOrUpdate merges with the client's translation dictionary
        handler.writeNoCache(new UpdateTranslations(UpdateType.AddOrUpdate, prefixedTranslations));
    }

    /**
     * Dynamically registers a translation entry at runtime (English). Values from
     * the .lang files always take precedence; an existing dynamic entry is not
     * overwritten. Online players receive the new entry immediately.
     *
     * @param key   The translation key (without "server." prefix), e.g.
     *              "items.Scroll_Gold_Digger_I.name"
     * @param value The translated value
     */
    public void putTranslation(@Nonnull String key, @Nullable String value) {
        if (key == null || value == null)
            return;
        String k = unprefixed(key);
        if (dynamicTranslations.putIfAbsent(k, value) != null) {
            return;
        }
        try {
            Universe universe = Universe.get();
            if (universe == null)
                return;
            Map<String, String> single = Collections.singletonMap(KEY_PREFIX + k, value);
            for (PlayerRef playerRef : universe.getPlayers()) {
                if (playerRef != null && playerRef.isValid()) {
                    send(playerRef, single);
                }
            }
        } catch (Exception e) {
            // Universe not initialised yet (setup phase) - players get it on join
        }
    }

    // ───────────────────── Mod key list ─────────────────────

    /**
     * The unprefixed keys of the mod's own language file (from the jar). Used to
     * know which keys to push for forced languages; values always come from the
     * I18n module.
     */
    @Nonnull
    private Set<String> getModKeys() {
        Set<String> keys = modKeys;
        if (keys != null) {
            return keys;
        }
        synchronized (this) {
            if (modKeys != null) {
                return modKeys;
            }
            Set<String> loaded = new LinkedHashSet<>();
            try (InputStream is = LanguageManager.class.getResourceAsStream(MOD_LANG_RESOURCE)) {
                if (is != null) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(is, StandardCharsets.UTF_8))) {
                        loaded.addAll(LangFileParser.parse(MOD_LANG_RESOURCE, reader).keySet());
                    }
                } else {
                    LOGGER.atWarning().log("LanguageManager: bundled %s not found; forced-language updates will only carry dynamic keys",
                            MOD_LANG_RESOURCE);
                }
            } catch (Exception e) {
                LOGGER.atSevere().withCause(e).log("LanguageManager: failed to read bundled %s", MOD_LANG_RESOURCE);
            }
            modKeys = Collections.unmodifiableSet(loaded);
            return modKeys;
        }
    }
}
