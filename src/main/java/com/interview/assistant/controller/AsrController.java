package com.interview.assistant.controller;

import com.interview.assistant.security.TenantContext;
import com.interview.assistant.service.AsrService;
import com.interview.assistant.service.AsrSettingsService;
import com.interview.assistant.service.LlmPurpose;
import com.interview.assistant.service.LlmRouter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 语音识别：设置接口 + 转写接口 + 术语纠错。
 *
 * 音频由前端切成短段（静音处自动切句）后上传，后端同步转发给云厂商。
 * 之所以不做 WebSocket 流式，是因为它需要额外维护长连接，而单段延迟已经在 1 秒内。
 */
@RestController
@CrossOrigin
public class AsrController {

    private static final Logger log = LoggerFactory.getLogger(AsrController.class);
    private static final long MAX_AUDIO_BYTES = 8L * 1024 * 1024;

    private final AsrService asrService;
    private final AsrSettingsService settingsService;
    private final LlmRouter llmRouter;

    public AsrController(AsrService asrService, AsrSettingsService settingsService, LlmRouter llmRouter) {
        this.asrService = asrService;
        this.settingsService = settingsService;
        this.llmRouter = llmRouter;
    }

    @GetMapping("/api/settings/asr")
    public ResponseEntity<?> get() {
        return ResponseEntity.ok(settingsService.describe(TenantContext.requireTenantId()));
    }

    @PutMapping("/api/settings/asr")
    public ResponseEntity<?> save(@RequestBody Map<String, Object> body) {
        try {
            return ResponseEntity.ok(settingsService.save(TenantContext.requireTenantId(), body));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage()));
        }
    }

    @DeleteMapping("/api/settings/asr")
    public ResponseEntity<?> reset() {
        Long tenantId = TenantContext.requireTenantId();
        settingsService.reset(tenantId);
        return ResponseEntity.ok(settingsService.describe(tenantId));
    }

    /**
     * 不落库的连通性测试：用一份临时配置跑一小段音频，
     * 让用户先确认 Key 能用再保存。
     */
    @PostMapping("/api/settings/asr/test")
    public ResponseEntity<?> test(@RequestBody Map<String, Object> body) {
        Long tenantId = TenantContext.requireTenantId();
        AsrSettingsService.ResolvedAsr current = settingsService.resolve(tenantId);

        String provider = str(body.get("provider"), current.provider());
        String baseUrl = str(body.get("baseUrl"), AsrSettingsService.defaultBaseUrl(provider));
        String model = str(body.get("model"), AsrSettingsService.defaultModel(provider));
        String apiKey = str(body.get("apiKey"), null);
        if (apiKey == null || apiKey.isBlank()) {
            apiKey = current.apiKey();
        }
        if (baseUrl == null || !baseUrl.startsWith("http")) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "Base URL 需要以 http(s):// 开头"));
        }
        if (apiKey == null || apiKey.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("ok", false, "error", "请先填写 Key"));
        }

        var cfg = new AsrSettingsService.ResolvedAsr(provider, baseUrl, apiKey, model,
                str(body.get("hotwords"), current.hotwords()));
        try {
            AsrService.Result result = asrService.transcribeWith(cfg, TestAudio.silenceWav());
            Map<String, Object> resp = new LinkedHashMap<>();
            resp.put("ok", true);
            resp.put("message", "连接成功（" + result.provider() + " / " + result.model()
                    + "，耗时 " + result.elapsedMs() + "ms"
                    + (result.text().isBlank() ? "，测试音频无人声，返回空文本属正常" : "）"));
            resp.put("sample", result.text());
            return ResponseEntity.ok(resp);
        } catch (AsrService.AsrException e) {
            // 静音被服务端判定为「没有识别出内容」说明鉴权已经通过，算连通
            String msg = e.getMessage() == null ? "" : e.getMessage();
            if (msg.contains("没有识别出内容") || msg.contains("empty")) {
                return ResponseEntity.ok(Map.of("ok", true, "message", "连接成功（鉴权通过，测试音频无人声）"));
            }
            return ResponseEntity.ok(Map.of("ok", false, "error", msg));
        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (msg.contains("401") || msg.contains("403")) {
                return ResponseEntity.ok(Map.of("ok", false, "error", "Key 无效或余额不足"));
            }
            log.warn("ASR 测试失败", e);
            return ResponseEntity.ok(Map.of("ok", false, "error", "连接失败：" + abbreviate(msg)));
        }
    }

    @PostMapping("/api/asr/transcribe")
    public ResponseEntity<?> transcribe(@RequestParam("audio") MultipartFile audio) {
        if (audio == null || audio.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "没有收到音频数据"));
        }
        if (audio.getSize() > MAX_AUDIO_BYTES) {
            return ResponseEntity.badRequest().body(Map.of("error", "单段音频过大，请缩短说话长度"));
        }
        Long tenantId = TenantContext.requireTenantId();
        try {
            byte[] wav = audio.getBytes();
            AsrService.Result result = asrService.transcribe(tenantId, wav);
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("text", result.text());
            body.put("provider", result.provider());
            body.put("model", result.model());
            body.put("elapsedMs", result.elapsedMs());
            body.put("empty", result.text() == null || result.text().isBlank());
            return ResponseEntity.ok(body);
        } catch (AsrService.AsrException e) {
            return ResponseEntity.ok(Map.of("error", e.getMessage(), "text", ""));
        } catch (Exception e) {
            log.warn("语音识别失败", e);
            String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            return ResponseEntity.ok(Map.of("error", "语音识别失败：" + abbreviate(message), "text", ""));
        }
    }

    /**
     * 术语校对：把识别结果用当前租户的对话模型过一遍，只修技术术语，不改语气。
     * 前端做成开关，默认关闭。
     */
    @PostMapping("/api/asr/proofread")
    public ResponseEntity<?> proofread(@RequestBody Map<String, Object> body) {
        Object raw = body.get("text");
        String text = raw == null ? "" : raw.toString().trim();
        if (text.isEmpty()) {
            return ResponseEntity.ok(Map.of("text", ""));
        }
        if (text.length() > 2000) {
            // 太长说明不是一次口语回答，直接原样返回，避免无谓的模型开销
            return ResponseEntity.ok(Map.of("text", text, "skipped", true));
        }
        String prompt = """
                你是语音识别结果的校对员。下面是一段技术面试中候选人的口述内容，\
                可能因同音字或识别误差出现技术术语错误（例如「对列」应为「队列」、\
                「分布式所」应为「分布式锁」、「瑞迪斯」应为「Redis」、「卡夫卡」应为「Kafka」）。

                规则：
                1. 只修正明显的技术术语、人名、产品名的错别字，其余一律保持原样；
                2. 不要润色语气、不要改口语表达、不要合并或拆分句子、不要增删内容；
                3. 原文已经正确的地方必须原样保留；
                4. 只输出修正后的整段文字，不要解释、不要加引号或任何前后缀。

                原文：
                """ + text;

        try {
            String fixed = llmRouter.chat(LlmPurpose.CHAT).chat(prompt);
            String result = fixed == null ? text : fixed.trim();
            // 模型偶尔会包一层代码块或引号，去掉
            result = result.replaceAll("^```[a-zA-Z]*\\s*", "").replaceAll("\\s*```$", "").trim();
            if (result.length() > 1 && (result.startsWith("\"") && result.endsWith("\""))
                    || (result.startsWith("「") && result.endsWith("」"))) {
                result = result.substring(1, result.length() - 1).trim();
            }
            if (result.isEmpty()) {
                return ResponseEntity.ok(Map.of("text", text));
            }
            return ResponseEntity.ok(Map.of("text", result, "changed", !result.equals(text)));
        } catch (Exception e) {
            log.warn("术语校对失败，返回原文: {}", e.getMessage());
            return ResponseEntity.ok(Map.of("text", text, "error", "校对失败，已保留原始识别结果"));
        }
    }

    private static String abbreviate(String s) {
        return s.length() > 180 ? s.substring(0, 180) + "…" : s;
    }

    private static String str(Object value, String fallback) {
        if (value == null) {
            return fallback;
        }
        String s = value.toString().trim();
        return s.isEmpty() ? fallback : s;
    }

    /**
     * 连通性测试用的合成音频：0.8 秒 16kHz 单声道 WAV，带一点低幅底噪。
     * 纯静音会被部分服务端直接拒绝，加噪声更接近真实输入，也能验证鉴权是否通过。
     */
    private static final class TestAudio {

        static byte[] silenceWav() {
            int rate = 16000;
            int samples = (int) (rate * 0.8);
            byte[] out = new byte[44 + samples * 2];
            ascii(out, 0, "RIFF");
            int32(out, 4, 36 + samples * 2);
            ascii(out, 8, "WAVE");
            ascii(out, 12, "fmt ");
            int32(out, 16, 16);
            int16(out, 20, 1);
            int16(out, 22, 1);
            int32(out, 24, rate);
            int32(out, 28, rate * 2);
            int16(out, 32, 2);
            int16(out, 34, 16);
            ascii(out, 36, "data");
            int32(out, 40, samples * 2);
            java.util.Random random = new java.util.Random(42);
            for (int i = 0; i < samples; i++) {
                int v = random.nextInt(400) - 200;
                out[44 + i * 2] = (byte) (v & 0xff);
                out[45 + i * 2] = (byte) ((v >> 8) & 0xff);
            }
            return out;
        }

        private static void ascii(byte[] b, int off, String s) {
            for (int i = 0; i < s.length(); i++) {
                b[off + i] = (byte) s.charAt(i);
            }
        }

        private static void int32(byte[] b, int off, int v) {
            b[off] = (byte) (v & 0xff);
            b[off + 1] = (byte) ((v >> 8) & 0xff);
            b[off + 2] = (byte) ((v >> 16) & 0xff);
            b[off + 3] = (byte) ((v >> 24) & 0xff);
        }

        private static void int16(byte[] b, int off, int v) {
            b[off] = (byte) (v & 0xff);
            b[off + 1] = (byte) ((v >> 8) & 0xff);
        }
    }
}
