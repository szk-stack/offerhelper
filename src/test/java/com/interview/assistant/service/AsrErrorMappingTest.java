package com.interview.assistant.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 语音识别错误映射。
 * 用例直接取自三方官方错误码文档：百炼把很多完全不同的错误都返回 400，
 * 所以这里重点验证「同状态码、不同业务码」能区分开，而不是一律提示参数错误。
 */
class AsrErrorMappingTest {

    private static final ObjectMapper M = new ObjectMapper();

    private static JsonNode json(String raw) {
        try {
            return M.readTree(raw);
        } catch (Exception e) {
            return M.createObjectNode();
        }
    }

    // ── 阿里百炼 ──

    @Test
    void dashScopeModelNotExistIsRecognized() {
        // 官方文档：Model not exist. 归在 400 InvalidParameter 下，也常见 404 model_not_found
        var e400 = AsrService.dashScopeError(400,
                json("{\"code\":\"InvalidParameter\",\"message\":\"Model not exist.\"}"),
                "", "qwen-audio-3.1-asr-flash");
        assertTrue(e400.getMessage().contains("不存在"), e400.getMessage());
        assertTrue(e400.getMessage().contains("qwen-audio-3.1-asr-flash"), e400.getMessage());

        var e404 = AsrService.dashScopeError(404,
                json("{\"code\":\"model_not_found\",\"message\":\"model 'xxx' not found or not supported\"}"),
                "", "xxx");
        assertTrue(e404.getMessage().contains("不存在"), e404.getMessage());
    }

    @Test
    void dashScopeInvalidApiKey() {
        var e = AsrService.dashScopeError(401,
                json("{\"code\":\"InvalidApiKey\",\"message\":\"Invalid API-key provided.\"}"), "", "m");
        assertTrue(e.getMessage().contains("Key 无效"), e.getMessage());
        assertEquals("InvalidApiKey", e.getCode());
    }

    @Test
    void dashScopeThrottlingVsQuota() {
        // 限流：降频就能恢复
        var throttled = AsrService.dashScopeError(429,
                json("{\"code\":\"Throttling.RateQuota\",\"message\":\"Requests rate limit exceeded\"}"), "", "m");
        assertTrue(throttled.getMessage().contains("限流"), throttled.getMessage());

        // 额度耗尽：降频没用，得充值或换服务商
        var quota = AsrService.dashScopeError(429,
                json("{\"code\":\"insufficient_quota\",\"message\":\"Your quota has been exhausted.\"}"), "", "m");
        assertTrue(quota.getMessage().contains("额度"), quota.getMessage());
    }

    @Test
    void dashScopeArrearageIsNotParameterError() {
        // 400 但其实是欠费，不能被当成参数错误
        var e = AsrService.dashScopeError(400,
                json("{\"code\":\"Arrearage\",\"message\":\"Access denied, please make sure your account is in good standing.\"}"),
                "", "m");
        assertTrue(e.getMessage().contains("欠费"), e.getMessage());
    }

    @Test
    void dashScopeDataInspectionFailed() {
        var e = AsrService.dashScopeError(400,
                json("{\"code\":\"DataInspectionFailed\",\"message\":\"Input or output data may contain inappropriate content.\"}"),
                "", "m");
        assertTrue(e.getMessage().contains("内容安全"), e.getMessage());
    }

    @Test
    void dashScopeAudioParameterError() {
        var e = AsrService.dashScopeError(400,
                json("{\"code\":\"InvalidParameter\",\"message\":\"Unsupported audio format: mp3\"}"), "", "m");
        assertTrue(e.getMessage().contains("音频参数"), e.getMessage());
    }

    @Test
    void dashScopeAccessDeniedUnpurchased() {
        var e = AsrService.dashScopeError(403,
                json("{\"code\":\"AccessDenied.Unpurchased\",\"message\":\"Access to model denied.\"}"), "", "m");
        assertTrue(e.getMessage().contains("开通"), e.getMessage());
    }

    @Test
    void dashScopeKeepsRequestIdForSupport() {
        var e = AsrService.dashScopeError(500,
                json("{\"code\":\"InternalError\",\"message\":\"oops\",\"request_id\":\"649b2bbc-c541\"}"), "", "m");
        assertEquals("649b2bbc-c541", e.getRequestId());
        assertTrue(e.getMessage().contains("服务端"), e.getMessage());
    }

    // ── 腾讯云 ──

    @Test
    void tencentCredentialErrors() {
        var sig = AsrService.tencentError("AuthFailure.SignatureFailure", "signature error", "rid");
        assertTrue(sig.getMessage().contains("SecretId"), sig.getMessage());
        assertEquals("rid", sig.getRequestId());

        var expired = AsrService.tencentError("AuthFailure.SignatureExpire", "", "");
        assertTrue(expired.getMessage().contains("签名已过期"), expired.getMessage());
    }

    @Test
    void tencentServiceNotOpened() {
        var e = AsrService.tencentError("FailedOperation.ServiceNotOpened", "", "");
        assertTrue(e.getMessage().contains("开通"), e.getMessage());
    }

    @Test
    void tencentNoSpeechIsDistinguishable() {
        // 这个错误码在「测试连接」里被当成鉴权通过，必须保持稳定
        var e = AsrService.tencentError("FailedOperation.ErrorRecognize", "", "");
        assertEquals("FailedOperation.ErrorRecognize", e.getCode());
    }

    // ── OpenAI 兼容 ──

    @Test
    void openAiModelNotFound() {
        var e = AsrService.openAiError(400,
                json("{\"error\":{\"message\":\"Model not exist.\",\"code\":\"model_not_found\"}}"),
                "", "FunAudioLLM/SenseVoiceSmall");
        assertTrue(e.getMessage().contains("不存在"), e.getMessage());
        assertEquals("model_not_found", e.getCode());
    }

    @Test
    void openAiUnauthorizedAndRateLimit() {
        assertTrue(AsrService.openAiError(401, json("{}"), "", "m").getMessage().contains("Key 无效"));
        assertTrue(AsrService.openAiError(429, json("{}"), "", "m").getMessage().contains("限流"));
        assertTrue(AsrService.openAiError(413, json("{}"), "", "m").getMessage().contains("太大"));
    }

    @Test
    void openAiFallbackKeepsServerMessage() {
        var e = AsrService.openAiError(418, json("{\"message\":\"teapot\"}"), "", "m");
        assertTrue(e.getMessage().contains("418"), e.getMessage());
        assertTrue(e.getMessage().contains("teapot"), e.getMessage());
    }
}
