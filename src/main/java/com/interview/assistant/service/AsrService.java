package com.interview.assistant.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.HexFormat;

/**
 * 语音识别客户端。三家协议差异都在这里吸收掉，上层只看到「WAV 进、文本出」。
 *
 * 不引各家 SDK：腾讯云 SDK 会带进十几 MB 的依赖，而这里只需要一个签名 + 一次 POST。
 */
@Service
public class AsrService {

    private static final Logger log = LoggerFactory.getLogger(AsrService.class);
    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(8))
            .build();

    private final AsrSettingsService settingsService;
    private final ObjectMapper objectMapper;

    public AsrService(AsrSettingsService settingsService, ObjectMapper objectMapper) {
        this.settingsService = settingsService;
        this.objectMapper = objectMapper;
    }

    public record Result(String text, String provider, String model, long elapsedMs) {
    }

    /** 让上层能拿到「这次会走哪家」，用于前端提示 */
    public AsrSettingsService.ResolvedAsr resolve(Long tenantId) {
        return settingsService.resolve(tenantId);
    }

    /**
     * 把一段 16kHz 单声道 WAV 转成文字。
     *
     * @param wav    完整 WAV 字节（含 44 字节头）
     * @param tenantId 租户，null 表示用部署级配置
     */
    public Result transcribe(Long tenantId, byte[] wav) throws Exception {
        return transcribeWith(settingsService.resolve(tenantId), wav);
    }

    /** 用一份显式配置转写，设置页的「测试连接」走这条路，不需要先保存 */
    public Result transcribeWith(AsrSettingsService.ResolvedAsr cfg, byte[] wav) throws Exception {
        if (!cfg.hasKey()) {
            throw new AsrException("尚未配置语音识别 Key，请在「设置 → 语音识别」里填写");
        }
        long start = System.currentTimeMillis();
        String provider = cfg.provider() != null ? cfg.provider() : "siliconflow";
        // 以 Base URL 的形态为准：用户粘贴了完整端点时（比如百炼的专属域名），
        // 即使供应商选的是「自定义」，也要走对应协议，否则会拼出根本不存在的路径。
        String protocol = detectProtocol(provider, cfg.baseUrl());

        String text = switch (protocol) {
            case "tencent" -> transcribeTencent(cfg, wav);
            case "dashscope" -> transcribeDashScope(cfg, wav);
            default -> transcribeOpenAiCompatible(cfg, wav);
        };

        long cost = System.currentTimeMillis() - start;
        log.info("ASR 完成 provider={} protocol={} model={} 音频={}KB 耗时={}ms 文本={}字",
                provider, protocol, cfg.model(), wav.length / 1024, cost, text.length());
        return new Result(text, provider, cfg.model(), cost);
    }

    /** 按 Base URL 判断该用哪种协议，显式端点优先于供应商预设 */
    static String detectProtocol(String provider, String baseUrl) {
        String url = baseUrl == null ? "" : baseUrl.toLowerCase();
        if (url.contains("/multimodal-generation/generation")) {
            return "dashscope";
        }
        if (url.endsWith("/audio/transcriptions")) {
            return "openai";
        }
        if ("tencent".equals(provider)) {
            return "tencent";
        }
        if ("dashscope".equals(provider)) {
            return "dashscope";
        }
        return "openai";
    }

    /** 百炼端点：允许用户填到 /api/v1，也允许直接填完整方法路径 */
    private static String dashScopeEndpoint(String baseUrl) {
        String b = stripTrailingSlash(baseUrl);
        if (b.contains("/multimodal-generation/generation")) {
            return b;
        }
        return b + "/services/aigc/multimodal-generation/generation";
    }

    /** OpenAI 兼容端点：同理，填到 /v1 或直接填完整路径都行 */
    private static String openAiEndpoint(String baseUrl) {
        String b = stripTrailingSlash(baseUrl);
        if (b.endsWith("/audio/transcriptions")) {
            return b;
        }
        return b + "/audio/transcriptions";
    }

    // ────────────────────────── OpenAI 兼容（硅基流动 / 自定义） ──────────────────────────

    private String transcribeOpenAiCompatible(AsrSettingsService.ResolvedAsr cfg, byte[] wav) throws Exception {
        String endpoint = openAiEndpoint(cfg.baseUrl());
        String model = cfg.model() != null && !cfg.model().isBlank() ? cfg.model() : "FunAudioLLM/SenseVoiceSmall";
        String boundary = "----ssp" + Long.toHexString(System.nanoTime());

        byte[] body = buildMultipart(boundary, wav, model);
        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + cfg.apiKey())
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode json = parse(response.body());
        if (response.statusCode() / 100 != 2) {
            throw new AsrException(friendlyOpenAiError(response.statusCode(), json, response.body()));
        }
        String text = json.path("text").asText("");
        // 有的兼容实现会把结果放在 choices 里
        if (text.isBlank() && json.has("choices")) {
            text = json.path("choices").path(0).path("message").path("content").asText("");
        }
        return clean(text);
    }

    private byte[] buildMultipart(String boundary, byte[] wav, String model) throws Exception {
        var out = new java.io.ByteArrayOutputStream();
        String head = "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"model\"\r\n\r\n" + model + "\r\n"
                + "--" + boundary + "\r\n"
                + "Content-Disposition: form-data; name=\"file\"; filename=\"audio.wav\"\r\n"
                + "Content-Type: audio/wav\r\n\r\n";
        out.write(head.getBytes(StandardCharsets.UTF_8));
        out.write(wav);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    // ────────────────────────── 阿里百炼 DashScope ──────────────────────────

    /**
     * 百炼非实时语音识别（qwen-audio-3.1-asr-flash / qwen3-asr-flash）。
     * 请求体是 multimodal-generation 结构，音频按 data URL 放进 input_audio，
     * 热词走 parameters.vocabulary（词 → 权重 1~5）。
     */
    private String transcribeDashScope(AsrSettingsService.ResolvedAsr cfg, byte[] wav) throws Exception {
        String endpoint = dashScopeEndpoint(cfg.baseUrl());
        String model = cfg.model() != null && !cfg.model().isBlank() ? cfg.model() : "qwen-audio-3.1-asr-flash";
        String dataUri = "data:audio/wav;base64," + Base64.getEncoder().encodeToString(wav);

        var payload = objectMapper.createObjectNode();
        payload.put("model", model);

        var messages = payload.putObject("input").putArray("messages");
        var msg = messages.addObject();
        msg.put("role", "user");
        var content = msg.putArray("content");
        var audioPart = content.addObject();
        audioPart.put("type", "input_audio");
        audioPart.putObject("input_audio").put("data", dataUri);

        var params = payload.putObject("parameters");
        params.put("format", "wav");
        params.put("sample_rate", 16000);
        var vocab = dashScopeVocabulary(cfg.hotwords());
        if (vocab != null) {
            params.set("vocabulary", vocab);
        }

        HttpRequest request = HttpRequest.newBuilder(URI.create(endpoint))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", "Bearer " + cfg.apiKey())
                .header("Content-Type", "application/json")
                .header("X-DashScope-SSE", "disable")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload),
                        StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode json = parse(response.body());
        if (response.statusCode() / 100 != 2) {
            throw new AsrException(friendlyDashScopeError(response.statusCode(), json, response.body(), model));
        }

        // 主字段是 output.text；部分模型把结果放在 choices[].message.content 里
        String text = json.path("output").path("text").asText("");
        if (text.isBlank()) {
            JsonNode contentNode = json.path("output").path("choices").path(0).path("message").path("content");
            StringBuilder sb = new StringBuilder();
            if (contentNode.isArray()) {
                for (JsonNode item : contentNode) {
                    if (item.has("text")) {
                        sb.append(item.path("text").asText(""));
                    }
                }
            }
            text = sb.toString();
        }
        return clean(text);
    }

    /** 热词表转成百炼的 vocabulary 结构，普通热词权重取 5（官方推荐 4 起调） */
    private JsonNode dashScopeVocabulary(String hotwords) {
        if (hotwords == null || hotwords.isBlank()) {
            return null;
        }
        var vocab = objectMapper.createObjectNode();
        int count = 0;
        for (String w : hotwords.split(",")) {
            String word = w.trim();
            if (word.isEmpty()) {
                continue;
            }
            vocab.put(word, 5);
            if (++count >= 128) {
                break;
            }
        }
        return vocab.size() == 0 ? null : vocab;
    }

    // ────────────────────────── 腾讯云一句话识别 ──────────────────────────

    private String transcribeTencent(AsrSettingsService.ResolvedAsr cfg, byte[] wav) throws Exception {
        String[] cred = cfg.tencentCredentials();
        if (cred == null) {
            throw new AsrException("腾讯云 Key 需要按 SecretId:SecretKey 的格式填写");
        }
        String baseUrl = stripTrailingSlash(cfg.baseUrl());
        String model = cfg.model() != null && !cfg.model().isBlank() ? cfg.model() : "16k_zh_large";

        var payload = objectMapper.createObjectNode();
        payload.put("EngSerViceType", model);
        payload.put("SourceType", 1);
        payload.put("VoiceFormat", "wav");
        payload.put("Data", Base64.getEncoder().encodeToString(wav));
        payload.put("DataLen", wav.length);
        String hotwords = formatTencentHotwords(cfg.hotwords());
        if (hotwords != null) {
            payload.put("HotwordList", hotwords);
        }
        String body = objectMapper.writeValueAsString(payload);

        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String service = "asr";
        String signedHeaders = "content-type;host";
        String canonicalHeaders = "content-type:application/json; charset=utf-8\n"
                + "host:" + URI.create(baseUrl).getHost() + "\n";
        String canonicalRequest = "POST\n/\n\n" + canonicalHeaders + "\n" + signedHeaders + "\n"
                + sha256Hex(body);
        String date = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC).format(Instant.now());
        String credentialScope = date + "/" + service + "/tc3_request";
        String stringToSign = "TC3-HMAC-SHA256\n" + timestamp + "\n" + credentialScope + "\n"
                + sha256Hex(canonicalRequest);

        byte[] secretDate = hmac("TC3" + cred[1], date);
        byte[] secretService = hmac(secretDate, service);
        byte[] secretSigning = hmac(secretService, "tc3_request");
        String signature = HexFormat.of().formatHex(hmac(secretSigning, stringToSign));

        String authorization = "TC3-HMAC-SHA256 Credential=" + cred[0] + "/" + credentialScope
                + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature;

        HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl))
                .timeout(Duration.ofSeconds(60))
                .header("Authorization", authorization)
                .header("Content-Type", "application/json; charset=utf-8")
                // Host 由 HttpClient 按 URL 自动生成；JDK 不允许手动设置（restricted header）。
                // 但签名必须把它算进 canonical headers，否则腾讯云验签失败。
                .header("X-TC-Action", "SentenceRecognition")
                .header("X-TC-Version", "2019-06-14")
                .header("X-TC-Timestamp", timestamp)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
                .build();

        HttpResponse<String> response = HTTP.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        JsonNode json = parse(response.body());
        JsonNode resp = json.path("Response");
        if (resp.has("Error")) {
            String code = resp.path("Error").path("Code").asText("");
            String message = resp.path("Error").path("Message").asText("");
            throw new AsrException(friendlyTencentError(code, message));
        }
        if (response.statusCode() / 100 != 2) {
            throw new AsrException("腾讯云语音识别请求失败（HTTP " + response.statusCode() + "）");
        }
        return clean(resp.path("Result").asText(""));
    }

    private static String formatTencentHotwords(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (String w : raw.split(",")) {
            String word = w.trim();
            if (word.isEmpty()) {
                continue;
            }
            // 单热词不超过 10 个汉字，超长的截断，避免整表被拒
            if (word.length() > 10) {
                word = word.substring(0, 10);
            }
            if (sb.length() > 0) {
                sb.append(',');
            }
            sb.append(word).append("|5");
            if (++count >= 128) {
                break;
            }
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    // ────────────────────────── 工具方法 ──────────────────────────

    private JsonNode parse(String body) {
        try {
            return body == null || body.isBlank() ? objectMapper.createObjectNode() : objectMapper.readTree(body);
        } catch (Exception e) {
            return objectMapper.createObjectNode();
        }
    }

    /** 去掉模型偶尔带出的富文本标记与多余空白 */
    private static String clean(String text) {
        if (text == null) {
            return "";
        }
        String s = text.replaceAll("<\\|[^|]*\\|>", "")
                .replaceAll("\\[\\[[^\\]]*\\]\\]", "")
                .trim();
        return s;
    }

    private static String stripTrailingSlash(String url) {
        if (url == null) {
            return "";
        }
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }

    private static String sha256Hex(String input) throws Exception {
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        return HexFormat.of().formatHex(md.digest(input.getBytes(StandardCharsets.UTF_8)));
    }

    private static byte[] hmac(String key, String data) throws Exception {
        return hmac(key.getBytes(StandardCharsets.UTF_8), data);
    }

    private static byte[] hmac(byte[] key, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(key, "HmacSHA256"));
        return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
    }

    private static String friendlyOpenAiError(int status, JsonNode json, String raw) {
        String msg = json.path("error").path("message").asText("");
        if (msg.isBlank()) {
            msg = json.path("message").asText("");
        }
        if (status == 401 || status == 403) {
            return "语音识别 Key 无效或余额不足，请到设置页检查";
        }
        if (status == 429) {
            return "语音识别调用过于频繁，请稍后再试";
        }
        if (status == 404) {
            return "语音识别接口地址不对，请检查 Base URL 是否包含 /v1";
        }
        return "语音识别失败（HTTP " + status + "）" + (msg.isBlank() ? "" : "：" + abbreviate(msg));
    }

    private static String friendlyDashScopeError(int status, JsonNode json, String raw, String model) {
        String code = json.path("code").asText("");
        String msg = json.path("message").asText("");
        String lower = (msg == null ? "" : msg).toLowerCase();
        if (status == 401 || status == 403) {
            return "阿里百炼 Key 无效，请到设置页检查";
        }
        if (lower.contains("model not exist") || lower.contains("model not found")) {
            return "模型「" + model + "」在该账号下不存在：请确认模型名，并到百炼控制台确认已开通此模型"
                    + (msg.isBlank() ? "" : "（原始信息：" + abbreviate(msg) + "）");
        }
        if (status == 404) {
            return "接口地址不对：百炼填 https://dashscope.aliyuncs.com/api/v1 即可"
                    + "（专属工作空间填自己的域名，到 /api/v1 为止，后面的路径程序会自动补）";
        }
        if ("Throttling".equalsIgnoreCase(code) || status == 429) {
            return "语音识别调用超出限流，请稍后再试";
        }
        if (msg.isBlank()) {
            msg = raw;
        }
        return "语音识别失败（HTTP " + status + "）" + (msg == null || msg.isBlank() ? "" : "：" + abbreviate(msg));
    }

    private static String friendlyTencentError(String code, String message) {
        return switch (code) {
            case "AuthFailure.SignatureFailure", "AuthFailure.SecretIdNotFound" ->
                    "腾讯云 SecretId / SecretKey 不正确";
            case "AuthFailure.SignatureExpire" -> "请求签名已过期，请检查服务器时间";
            case "FailedOperation.ErrorRecognize" -> "没有识别出内容，请靠近麦克风再说一次";
            case "FailedOperation.AudioDurationExceed" -> "单段音频过长，请缩短后重试";
            case "FailedOperation.ServiceNotOpened" -> "腾讯云语音识别服务未开通，请先到控制台开通";
            case "RequestLimitExceeded" -> "调用频率超限，请稍后再试";
            case "ResourceUnavailable.InArrears" -> "腾讯云账户余额不足";
            default -> "腾讯云语音识别失败" + (code.isBlank() ? "" : "（" + code + "）")
                    + (message == null || message.isBlank() ? "" : "：" + abbreviate(message));
        };
    }

    private static String abbreviate(String s) {
        return s.length() > 200 ? s.substring(0, 200) + "…" : s;
    }

    /** 面向用户的语音识别异常，接口层直接把 message 返回给前端 */
    public static class AsrException extends RuntimeException {
        public AsrException(String message) {
            super(message);
        }
    }
}
