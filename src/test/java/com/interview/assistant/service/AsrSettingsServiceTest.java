package com.interview.assistant.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 语音识别配置的纯逻辑部分：热词清洗与腾讯云凭据拆分。
 * 这两块出错会直接导致整表被服务端拒绝，或者签名失败，值得单独守住。
 */
class AsrSettingsServiceTest {

    @Test
    void trimHotwordsAcceptsMixedSeparators() {
        String raw = "Java，Redis;Kafka\n分布式锁, , 熔断";
        assertEquals("Java,Redis,Kafka,分布式锁,熔断", AsrSettingsService.trimHotwords(raw));
    }

    @Test
    void trimHotwordsDropsBlankInput() {
        assertNull(AsrSettingsService.trimHotwords(""));
        assertNull(AsrSettingsService.trimHotwords("   "));
        assertNull(AsrSettingsService.trimHotwords(",，;；\n"));
    }

    @Test
    void trimHotwordsCapsAt128() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            if (i > 0) sb.append(',');
            sb.append("word").append(i);
        }
        String result = AsrSettingsService.trimHotwords(sb.toString());
        assertNotNull(result);
        // 腾讯云一句话识别的热词表上限是 128 个
        assertEquals(128, result.split(",").length);
    }

    @Test
    void tencentCredentialsSplitByColon() {
        var cfg = new AsrSettingsService.ResolvedAsr("tencent", "https://asr.tencentcloudapi.com",
                "AKIDxxx:secretYYY", "16k_zh_large", null);
        String[] cred = cfg.tencentCredentials();
        assertNotNull(cred);
        assertEquals("AKIDxxx", cred[0]);
        assertEquals("secretYYY", cred[1]);
    }

    @Test
    void tencentCredentialsRejectsMalformed() {
        var noColon = new AsrSettingsService.ResolvedAsr("tencent", "", "onlysecretid", "", null);
        assertNull(noColon.tencentCredentials());

        var trailingColon = new AsrSettingsService.ResolvedAsr("tencent", "", "id:", "", null);
        assertNull(trailingColon.tencentCredentials());

        var empty = new AsrSettingsService.ResolvedAsr("tencent", "", "", "", null);
        assertNull(empty.tencentCredentials());
        assertFalse(empty.hasKey());
    }

    @Test
    void presetsCoverEverySupportedProvider() {
        for (String provider : AsrSettingsService.supportedProviders()) {
            String baseUrl = AsrSettingsService.defaultBaseUrl(provider);
            String model = AsrSettingsService.defaultModel(provider);
            assertNotNull(baseUrl, provider);
            assertNotNull(model, provider);
            // custom 允许为空，其余必须有可用的默认值
            if (!"custom".equals(provider)) {
                assertTrue(baseUrl.startsWith("https://"), provider);
                assertFalse(model.isBlank(), provider);
            }
        }
    }
}
