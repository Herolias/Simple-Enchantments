package org.herolias.plugin.lang;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages custom language translations for the mod.
 * This allows forcing a specific language translation regardless of the client's game language.
 */
public class LanguageManager {

    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    
    // Map<LanguageCode, Map<TranslationKey, TranslatedString>>
    private final Map<String, Map<String, String>> translations = new ConcurrentHashMap<>();
    
    public static final String[] AVAILABLE_LANGUAGES = {
        "en-US", "de-DE", "es-ES", "fr-FR", "id-ID", "it-IT", 
        "nl-NL", "pt-BR", "ru-RU", "sv-SE", "uk-UA"
    };

    public LanguageManager() {
        loadTranslations();
    }

    private void loadTranslations() {
        for (String lang : AVAILABLE_LANGUAGES) {
            String path = "/Server/Languages/" + lang + "/server.lang";
            try (InputStream is = getClass().getResourceAsStream(path)) {
                if (is != null) {
                    Map<String, String> langMap = new HashMap<>();
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            line = line.replace("\uFEFF", "").trim();
                            if (line.isEmpty() || line.startsWith("#")) continue;
                            
                            int separatorIndex = line.indexOf('=');
                            if (separatorIndex > 0) {
                                String key = line.substring(0, separatorIndex).trim();
                                String value = line.substring(separatorIndex + 1).trim();
                                langMap.put(key, value);
                            }
                        }
                    }
                    translations.put(lang, langMap);
                    LOGGER.atInfo().log("Loaded translation file for: " + lang + " (" + langMap.size() + " keys)");
                } else {
                    LOGGER.atWarning().log("Could not find translation file for: " + lang + " at " + path);
                }
            } catch (Exception e) {
                LOGGER.atSevere().log("Error loading translation file for " + lang + ": " + e.getMessage());
            }
        }
    }

    /**
     * Gets a raw translated string.
     * If langCode is "default" or not found, applies the clientLangCode.
     * Falls back to English, then finally returns the raw key if no translation exists.
     */
    public String getRawMessage(String key, String langCode, String clientLangCode) {
        String targetLang = (langCode == null || "default".equalsIgnoreCase(langCode)) ? clientLangCode : langCode;
        if (targetLang == null) targetLang = "en-US";

        Map<String, String> langMap = translations.get(targetLang);
        if (langMap != null && langMap.containsKey(key)) {
            return langMap.get(key);
        }

        // Fallback to English
        Map<String, String> enMap = translations.get("en-US");
        if (enMap != null && enMap.containsKey(key)) {
            return enMap.get(key);
        }

        return key; // return key as string if missing
    }

    /**
     * Gets a localized Message object, resolving "default" via the client's language.
     * Ensures we always send Message.raw() so the client doesn't see raw keys, 
     * unless the translation genuinely doesn't exist.
     */
    public Message getMessage(String key, String langCode, String clientLangCode) {
        String raw = getRawMessage(key, langCode, clientLangCode);
        
        // If we only got the key back, defer to client just in case the client has it built-in
        if (raw.equals(key)) {
            return Message.translation(key);
        }

        return Message.raw(raw);
    }

    /**
     * Retrieves the entire translation map for a specific target language, including fallbacks.
     */
    public Map<String, String> getTranslationMap(String langCode, String clientLangCode) {
        String targetLang = (langCode == null || "default".equalsIgnoreCase(langCode)) ? clientLangCode : langCode;
        if (targetLang == null) targetLang = "en-US";
        
        Map<String, String> map = new HashMap<>();
        
        // Base fallback
        Map<String, String> enMap = translations.get("en-US");
        if (enMap != null) map.putAll(enMap);
        
        // Overlay target language
        Map<String, String> targetMap = translations.get(targetLang);
        if (targetMap != null) map.putAll(targetMap);
        
        return map;
    }

    /**
     * Pushes the entire translation map to the client using an UpdateTranslations(Init) packet.
     * This forces the client to use these translations for all static UI, item names, and descriptions.
     */
    public void sendUpdatePacket(com.hypixel.hytale.server.core.universe.PlayerRef playerRef, String customLangCode) {
        if (playerRef == null || !playerRef.isValid()) return;
        
        String clientLocale = playerRef.getLanguage();
        if (clientLocale == null || clientLocale.isEmpty()) {
            clientLocale = "en-US";
        }
        
        Map<String, String> translationsMap = getTranslationMap(customLangCode, clientLocale);
        
        if (!translationsMap.isEmpty()) {
            // Prepend "server." to all keys as the client expects them to have this prefix
            Map<String, String> prefixedTranslationsMap = new HashMap<>();
            for (Map.Entry<String, String> entry : translationsMap.entrySet()) {
                prefixedTranslationsMap.put("server." + entry.getKey(), entry.getValue());
            }

            // Use AddOrUpdate type to merge with the client's internal translation dictionary
            com.hypixel.hytale.protocol.packets.assets.UpdateTranslations packet = 
                new com.hypixel.hytale.protocol.packets.assets.UpdateTranslations(
                    com.hypixel.hytale.protocol.UpdateType.AddOrUpdate, 
                    prefixedTranslationsMap
                );
            if (playerRef.getPacketHandler() != null) {
                playerRef.getPacketHandler().writeNoCache(packet);
            }
        }
    }
}
