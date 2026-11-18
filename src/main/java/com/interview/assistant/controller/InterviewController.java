package com.interview.assistant.controller;

import com.interview.assistant.entity.InterviewExperience;
import com.interview.assistant.service.InterviewDataService;
import com.interview.assistant.service.ImageParseService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.Map;

/**
 * 面经库：图片解析录入、按公司/部门检索、面经增删。
 * AI 面试与智能助手已下线，对应的 /questions、/chat、/chat-session、/tool-session 接口一并移除。
 */
@RestController
@RequestMapping("/api/interviews")
@CrossOrigin
public class InterviewController {

    private static final Logger log = LoggerFactory.getLogger(InterviewController.class);
    private final InterviewDataService interviewDataService;
    private final ImageParseService imageParseService;

    public InterviewController(InterviewDataService interviewDataService,
            ImageParseService imageParseService) {
        this.interviewDataService = interviewDataService;
        this.imageParseService = imageParseService;
    }

    /** 图片解析：上传面经截图，大模型提取结构化内容 */
    @PostMapping(value = { "/parse-image", "/parseImage" }, consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<?> parseImage(@RequestParam("image") MultipartFile image) {
        if (image.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "请选择图片"));
        }
        try {
            Map<String, Object> parsed = imageParseService.parseImage(image);
            return ResponseEntity.ok(parsed);
        } catch (Exception e) {
            log.error("parseImage failed", e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage() != null ? e.getMessage() : "图片解析失败"));
        }
    }

    @GetMapping("/companies")
    public ResponseEntity<List<String>> getCompanies() {
        return ResponseEntity.ok(interviewDataService.getAllCompanies());
    }

    @GetMapping("/companies/{company}/departments")
    public ResponseEntity<List<String>> getDepartments(@PathVariable String company) {
        return ResponseEntity.ok(interviewDataService.getDepartmentsByCompany(company));
    }

    @GetMapping("/search")
    public ResponseEntity<List<InterviewExperience>> search(
            @RequestParam String company,
            @RequestParam(required = false) String department) {
        return ResponseEntity.ok(interviewDataService.search(company, department));
    }

    @PostMapping("/experiences")
    public ResponseEntity<?> addExperiences(@RequestBody List<InterviewExperience> experiences) {
        log.info("POST /experiences 收到请求, 条数: {}", experiences == null ? 0 : experiences.size());
        if (experiences == null || experiences.isEmpty()) {
            return ResponseEntity.badRequest().body("面经列表不能为空");
        }
        try {
            List<InterviewExperience> saved = interviewDataService.saveAll(experiences);
            log.info("POST /experiences 保存完成: {} 条", saved.size());
            return ResponseEntity.ok(saved);
        } catch (Exception e) {
            log.error("POST /experiences 失败", e);
            throw e;
        }
    }

    @DeleteMapping("/experiences/{id}")
    public ResponseEntity<?> deleteExperience(@PathVariable Long id) {
        try {
            interviewDataService.deleteById(id);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("DELETE /experiences/{} 失败", id, e);
            throw e;
        }
    }

    @PostMapping("/experiences/delete")
    public ResponseEntity<?> deleteExperiencePost(@RequestBody Map<String, Object> body) {
        Object idObj = body != null ? body.get("id") : null;
        if (idObj == null)
            return ResponseEntity.badRequest().build();
        Long id = idObj instanceof Number ? ((Number) idObj).longValue() : Long.parseLong(String.valueOf(idObj));
        try {
            interviewDataService.deleteById(id);
            return ResponseEntity.ok().build();
        } catch (Exception e) {
            log.error("POST /experiences/delete id={} 失败", id, e);
            throw e;
        }
    }
}
