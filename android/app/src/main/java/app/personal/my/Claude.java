package app.personal.my;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import com.anthropic.client.AnthropicClient;
import com.anthropic.client.okhttp.AnthropicOkHttpClient;
import com.anthropic.core.JsonValue;
import com.anthropic.errors.AnthropicServiceException;
import com.anthropic.models.messages.JsonOutputFormat;
import com.anthropic.models.messages.Message;
import com.anthropic.models.messages.MessageCreateParams;
import com.anthropic.models.messages.OutputConfig;
import com.anthropic.models.messages.StopReason;
import com.anthropic.models.messages.WebFetchTool20260209;
import com.anthropic.models.messages.WebSearchTool20260209;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** The API key, encrypted by Android's keystore, and the only code that ever reads it. */
final class Claude {
    private static final String PREFS = "claude", SAVED = "key", ALIAS = "claude_api_key";
    private static AnthropicClient client;
    private static String testBaseUrl;

    private Claude() {}

    private static SecretKey secret() throws Exception {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (ks.containsAlias(ALIAS)) return (SecretKey) ks.getKey(ALIAS, null);
        KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build());
        return g.generateKey();
    }

    static synchronized boolean saveKey(Context c, String key) {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, secret());
            byte[] sealed = cipher.doFinal(key.trim().getBytes(StandardCharsets.UTF_8));
            String value = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP) + ":"
                    + Base64.encodeToString(sealed, Base64.NO_WRAP);
            c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(SAVED, value).commit();
            client = null;
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    static synchronized void clearKey(Context c) {
        c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(SAVED).commit();
        client = null;
    }

    static boolean hasKey(Context c) {
        return readKey(c) != null;
    }

    private static String readKey(Context c) {
        String value = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(SAVED, null);
        if (value == null) return null;
        try {
            String[] parts = value.split(":");
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secret(), new GCMParameterSpec(128, Base64.decode(parts[0], Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(parts[1], Base64.NO_WRAP)), StandardCharsets.UTF_8);
        } catch (Exception e) {
            return null; // e.g. restored to a new phone, where the old keystore doesn't exist
        }
    }

    /** Only reachable from debug builds, for testing against a local stand-in server. */
    static synchronized void setTestBaseUrl(String url) {
        testBaseUrl = url;
        client = null;
    }

    private static synchronized AnthropicClient client(Context c) {
        if (client != null) return client;
        String key = readKey(c);
        if (key == null) return null;
        AnthropicOkHttpClient.Builder b = AnthropicOkHttpClient.builder().apiKey(key)
                .timeout(Duration.ofMinutes(6)); // research reads pages, which can take minutes
        if (testBaseUrl != null) b.baseUrl(testBaseUrl);
        client = b.build();
        return client;
    }

    /**
     * Returns {text, input, output, searches} or {error, status}. With research above zero, Claude
     * gets web search and fetch, run on Anthropic's side, and states its result as text. Otherwise
     * it has no tools and the reply must match the schema.
     */
    static String ask(Context c, String model, String system, String user, String schemaJson, long maxTokens, long research) {
        JSONObject out = new JSONObject();
        try {
            AnthropicClient api = client(c);
            if (api == null) return out.put("error", "No API key saved").put("status", 0).toString();

            MessageCreateParams.Builder params = MessageCreateParams.builder()
                    .model(model)
                    .maxTokens(maxTokens)
                    .system(system)
                    .addUserMessage(user);
            OutputConfig.Builder output = OutputConfig.builder().effort(OutputConfig.Effort.MEDIUM);
            if (research > 0) {
                params.addTool(WebSearchTool20260209.builder().maxUses(research).build());
                params.addTool(WebFetchTool20260209.builder().maxUses(research * 2).build());
            } else {
                JsonOutputFormat.Schema.Builder schema = JsonOutputFormat.Schema.builder();
                JSONObject parsed = new JSONObject(schemaJson);
                for (Iterator<String> keys = parsed.keys(); keys.hasNext(); ) {
                    String k = keys.next();
                    schema.putAdditionalProperty(k, JsonValue.from(plain(parsed.get(k))));
                }
                output.format(JsonOutputFormat.builder().schema(schema.build()).build());
            }
            params.outputConfig(output.build());
            if (!model.startsWith("claude-haiku")) {
                // If a safety check declines the request, let the API retry on a suitable model.
                params.putAdditionalHeader("anthropic-beta", "server-side-fallback-2026-07-01");
                params.putAdditionalBodyProperty("fallbacks", JsonValue.from("default"));
            }
            Message reply = api.messages().create(params.build());
            long input = reply.usage().inputTokens(), output2 = reply.usage().outputTokens();
            // Long research can pause mid-way; sending it back lets Claude carry on.
            for (int i = 0; i < 6 && reply.stopReason().filter(StopReason.PAUSE_TURN::equals).isPresent(); i++) {
                params.addMessage(reply);
                reply = api.messages().create(params.build());
                input += reply.usage().inputTokens();
                output2 += reply.usage().outputTokens();
            }
            if (reply.stopReason().filter(StopReason.REFUSAL::equals).isPresent()) {
                return out.put("error", "Claude declined this batch").put("status", 200).toString();
            }
            String text = reply.content().stream()
                    .flatMap(block -> block.text().stream())
                    .map(t -> t.text())
                    .collect(Collectors.joining());
            return out.put("text", text)
                    .put("input", input)
                    .put("output", output2)
                    .toString();
        } catch (AnthropicServiceException e) {
            return fail(out, e.statusCode(), e.getMessage());
        } catch (Exception e) {
            return fail(out, 0, e.getMessage());
        }
    }

    private static String fail(JSONObject out, int status, String message) {
        try {
            return out.put("error", message == null ? "Request failed" : message).put("status", status).toString();
        } catch (JSONException e) {
            return "{\"error\":\"Request failed\",\"status\":0}";
        }
    }

    private static Object plain(Object v) throws JSONException {
        if (v instanceof JSONObject) {
            Map<String, Object> m = new HashMap<>();
            JSONObject o = (JSONObject) v;
            for (Iterator<String> keys = o.keys(); keys.hasNext(); ) {
                String k = keys.next();
                m.put(k, plain(o.get(k)));
            }
            return m;
        }
        if (v instanceof JSONArray) {
            List<Object> l = new ArrayList<>();
            JSONArray a = (JSONArray) v;
            for (int i = 0; i < a.length(); i++) l.add(plain(a.get(i)));
            return l;
        }
        return v;
    }
}
