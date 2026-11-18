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

        String text;
        try {
            text = switch (protocol) {
                case "tencent" -> transcribeTencent(cfg, wav);
                case "dashscope" -> transcribeDashScope(cfg, wav);
                default -> transcribeOpenAiCompatible(cfg, wav);
            };
        } catch (AsrException e) {
            throw e;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new AsrException("语音识别请求超时（超过 60 秒）。检查服务器能否正常访问外网，"
                    + "或者这段音频太长，缩短一点再试。", "Timeout", null);
        } catch (java.net.UnknownHostException e) {
            throw new AsrException("域名解析失败：" + abbreviate(e.getMessage())
                    + "。检查 Base URL 是否拼错，或该域名是否只能在特定网络下访问。", "UnknownHost", null);
        } catch (java.net.ConnectException e) {
            throw new AsrException("连不上语音识别服务器。确认 Base URL 域名正确、"
                    + "且运行本服务的机器能访问外网（专属域名可能只对特定网络开放）。", "ConnectFailed", null);
        } catch (java.io.IOException e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            throw new AsrException("网络异常：" + abbreviate(msg), "NetworkError", null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AsrException("识别请求被中断，请重试。", "Interrupted", null);
        }

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
            throw openAiError(response.statusCode(), json, response.body(), model);
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
            throw dashScopeError(response.statusCode(), json, response.body(), model);
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
        String requestId = resp.path("RequestId").asText("");
        if (resp.has("Error")) {
            String code = resp.path("Error").path("Code").asText("");
            String message = resp.path("Error").path("Message").asText("");
            throw tencentError(code, message, requestId);
        }
        if (response.statusCode() / 100 != 2) {
            throw new AsrException("腾讯云语音识别请求失败（HTTP " + response.statusCode() + "）",
                    String.valueOf(response.statusCode()), requestId);
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

    /**
     * OpenAI 兼容服务（硅基流动 / 自建）的错误映射。
     * 这类服务的 code 字段位置不统一：硅基流动放顶层，OpenAI 标准放 error.code。
     */
    static AsrException openAiError(int status, JsonNode json, String raw, String model) {
        String code = firstNonBlank(json.path("error").path("code").asText(""),
                json.path("code").asText(""));
        String msg = firstNonBlank(json.path("error").path("message").asText(""),
                json.path("message").asText(""));
        String lowerMsg = (msg == null ? "" : msg).toLowerCase();

        if (lowerMsg.contains("model") && (lowerMsg.contains("not exist") || lowerMsg.contains("not found"))) {
            return new AsrException("模型「" + model + "」不存在，核对服务商文档里的模型 ID（注意大小写和斜杠）。",
                    code, null);
        }
        if (status == 401 || status == 403) {
            return new AsrException("API Key 无效或没有权限，请到设置页检查。", code, null);
        }
        if (status == 404) {
            return new AsrException("接口地址不对。OpenAI 兼容服务一般填到 /v1 即可，"
                    + "程序会自动补 /audio/transcriptions；粘贴完整端点也会原样使用。", code, null);
        }
        if (status == 429) {
            return new AsrException("调用太频繁被限流了。免费额度的并发很低，说慢一点或等几秒再试。", code, null);
        }
        if (status == 413) {
            return new AsrException("这段音频太大被服务端拒收，说话短一点再试。", code, null);
        }
        if (status >= 500) {
            return new AsrException("服务端临时故障（HTTP " + status + "），稍等再试。", code, null);
        }
        return new AsrException(fallbackMessage("HTTP " + status, msg, raw), code, null);
    }

    /**
     * 百炼错误映射，严格按官方错误码表区分。
     * 百炼把好几类完全不同的错误都塞在 400 下（参数错误、欠费、内容审核、模型不存在），
     * 所以必须先看业务码和 message，只看 HTTP 状态会全部提示成「参数错误」。
     */
    static AsrException dashScopeError(int status, JsonNode json, String raw, String model) {
        JsonNode output = json.path("output");
        String code = firstNonBlank(json.path("code").asText(""), output.path("code").asText(""));
        String msg = firstNonBlank(json.path("message").asText(""), output.path("message").asText(""));
        String requestId = firstNonBlank(json.path("request_id").asText(""),
                output.path("request_id").asText(""));
        String lowerCode = code.toLowerCase();
        String lowerMsg = (msg == null ? "" : msg).toLowerCase();

        // 模型不存在 / 未开通：官方同时在 400 InvalidParameter 和 404 ModelNotFound 出现
        if (lowerCode.contains("modelnotfound") || lowerCode.contains("model_not_found")
                || lowerMsg.contains("model not exist") || lowerMsg.contains("not found or not supported")
                || lowerMsg.contains("model is not supported") || lowerMsg.contains("is not activated")) {
            return new AsrException("模型「" + model + "」不存在或未开通。核对名字是否区分大小写、"
                    + "是否属于当前地域，并到百炼控制台的模型广场确认已开通。", code, requestId);
        }

        // 工作空间不对
        if (lowerCode.contains("workspacenotfound") || lowerMsg.contains("workspace not found")) {
            return new AsrException("工作空间 ID 不对。Base URL 里的 {WorkspaceId} 要换成你自己的，"
                    + "并在控制台确认该空间存在。", code, requestId);
        }

        // 401 Key 无效
        if (status == 401 || lowerCode.contains("invalidapikey") || lowerCode.contains("invalid_api_key")
                || lowerMsg.contains("invalid access token") || lowerMsg.contains("incorrect api key")
                || lowerMsg.contains("no api-key provided")) {
            return new AsrException("API Key 无效。确认 Key 完整无空格，且与 Base URL 属于同一地域；"
                    + "专属工作空间要用该空间下创建的 Key。", code, requestId);
        }

        // 403 权限 / 未开通
        if (status == 403 || lowerCode.contains("accessdenied") || lowerCode.contains("access_denied")) {
            // AccessDenied.Unpurchased 的 "unpurchased" 在 code 里，不在 message 里
            if (lowerCode.contains("unpurchased") || lowerMsg.contains("unpurchased")
                    || lowerMsg.contains("not activated") || lowerMsg.contains("not purchase")) {
                return new AsrException("账号还没开通百炼服务，先到阿里云百炼控制台完成开通。", code, requestId);
            }
            return new AsrException("没有调用该模型的权限。在百炼控制台的模型调用授权里给当前业务空间授权，"
                    + "或改用主账号创建的 Key。", code, requestId);
        }

        // 429 限流 / 额度耗尽
        if (status == 429 || lowerCode.contains("throttling") || lowerMsg.contains("insufficient_quota")
                || lowerMsg.contains("rate limit") || lowerMsg.contains("quota exceeded")) {
            if (lowerMsg.contains("quota") || lowerCode.contains("allocationquota")
                    || lowerCode.contains("freetieronly")) {
                return new AsrException("额度用完了（免费额度或套餐额度）。到百炼控制台看用量，"
                        + "或者直接换一家服务商继续用。", code, requestId);
            }
            return new AsrException("请求太频繁被限流了，等几秒再试。", code, requestId);
        }

        // 欠费
        if (lowerCode.contains("arrearage") || lowerMsg.contains("arrearage")
                || lowerMsg.contains("account is in good standing") || lowerMsg.contains("out_of_service")) {
            return new AsrException("阿里云账号欠费，服务已暂停。充值后等几分钟再试。", code, requestId);
        }

        // 内容安全拦截
        if (lowerCode.contains("datainspection") || lowerMsg.contains("inappropriate content")) {
            return new AsrException("这段音频被内容安全检测拦下了，换个说法再试。", code, requestId);
        }

        // 400 参数错误（含音频格式问题）
        if (status == 400 || lowerCode.contains("invalidparameter") || lowerCode.contains("invalid_parameter")) {
            if (lowerMsg.contains("audio") || lowerMsg.contains("format")
                    || lowerMsg.contains("sample") || lowerMsg.contains("voice")) {
                return new AsrException("音频参数不被接受：" + abbreviate(msg)
                        + "。程序固定上传 16kHz 单声道 WAV，若该模型要求别的采样率或格式，"
                        + "请换支持 WAV 的模型（如 qwen-audio-3.1-asr-flash）。", code, requestId);
            }
            return new AsrException("请求参数有误：" + abbreviate(msg), code, requestId);
        }

        // 5xx 服务端
        if (status >= 500) {
            return new AsrException("百炼服务端临时故障（HTTP " + status + "），稍等再试。", code, requestId);
        }

        return new AsrException(fallbackMessage("HTTP " + status, msg, raw), code, requestId);
    }

    /**
     * 腾讯云错误映射，按官方错误码文档分类。
     * 一句话识别的错误码是 "ErrorCode.ErrorReason" 两级结构，所以用精确匹配 + 前缀兜底。
     */
    static AsrException tencentError(String code, String message, String requestId) {
        String msg = switch (code) {
            case "AuthFailure.SecretIdNotFound", "AuthFailure.SignatureFailure" ->
                    "腾讯云 SecretId / SecretKey 不正确。填写格式是「SecretId:SecretKey」，中间用英文冒号。";
            case "AuthFailure.InvalidSecretId" ->
                    "SecretId 格式不对，确认没有把 SecretKey 填到冒号前面。";
            case "AuthFailure.SignatureExpire" -> "请求签名已过期，检查服务器时间是否准确。";
            case "AuthFailure.UnauthorizedOperation" ->
                    "这个 Key 没有语音识别的权限，去 CAM 控制台给子账号授权 QcloudASRFullAccess。";
            case "FailedOperation.ServiceNotOpened" -> "腾讯云语音识别服务还没开通，先到控制台开通。";
            case "FailedOperation.ErrorRecognize" -> "这段音频没识别出内容，靠近麦克风、说清楚一点再试。";
            case "FailedOperation.SilentAudio", "FailedOperation.NoSpeechDetected" ->
                    "音频里没有检测到人声，检查麦克风是否被静音了。";
            case "FailedOperation.AudioDecodeFailed" -> "音频解码失败，确认格式和采样率是 16kHz 单声道 WAV。";
            case "FailedOperation.AudioDurationExceed" -> "单段音频太长，控制在 60 秒以内。";
            case "FailedOperation.AudioTooLarge" -> "音频文件太大，说短一点。";
            case "InvalidParameterValue.AudioFormatError", "InvalidParameterValue.ErrorVoiveFormat" ->
                    "音频格式不被接受。程序上传的是 16kHz 单声道 WAV，确认中间没有被转码。";
            case "InvalidParameterValue", "InvalidParameter" ->
                    "请求参数有误：" + abbreviate(message);
            case "RequestLimitExceeded" -> "调用频率超出限制，等一会儿再试。";
            case "ResourceUnavailable.InArrears" -> "腾讯云账号欠费，充值后重试。";
            case "ResourceUnavailable.NotExist" -> "服务资源不存在，确认语音识别服务已开通。";
            case "ResourceUnavailable.StopUsing" -> "服务已停用，检查账号状态。";
            default -> "腾讯云语音识别失败"
                    + (code == null || code.isBlank() ? "" : "（" + code + "）")
                    + (message == null || message.isBlank() ? "" : "：" + abbreviate(message));
        };
        return new AsrException(msg, code, requestId);
    }

    private static String fallbackMessage(String statusLabel, String msg, String raw) {
        String detail = (msg != null && !msg.isBlank()) ? msg : raw;
        return "语音识别失败（" + statusLabel + "）"
                + (detail == null || detail.isBlank() ? "" : "：" + abbreviate(detail));
    }

    private static String firstNonBlank(String a, String b) {
        return (a != null && !a.isBlank()) ? a : b;
    }

    private static String abbreviate(String s) {
        if (s == null) {
            return "";
        }
        // 错误信息要能一眼看完，太长的服务端堆栈截断
        return s.length() > 120 ? s.substring(0, 120) + "…" : s;
    }

    /** 面向用户的语音识别异常，接口层把 message / code / requestId 一并返回给前端 */
    public static class AsrException extends RuntimeException {
        private final String code;
        private final String requestId;

        public AsrException(String message) {
            this(message, null, null);
        }

        public AsrException(String message, String code, String requestId) {
            super(message);
            this.code = code;
            this.requestId = requestId;
        }

        public String getCode() {
            return code;
        }

        public String getRequestId() {
            return requestId;
        }
    }
}
