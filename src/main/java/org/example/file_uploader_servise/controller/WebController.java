package org.example.file_uploader_servise.controller;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;

@Slf4j
@Controller
@RequiredArgsConstructor
public class WebController {

    @GetMapping("/")
    public String index(Model model) {
        model.addAttribute("title", "File Uploader - Главная");
        model.addAttribute("apiEndpoint", "/api/v1/files/upload");
        model.addAttribute("maxFileSize", "100MB");
        return "index";
    }

    @GetMapping("/upload")
    public String uploadPage(Model model) {
        model.addAttribute("title", "Загрузить файл");
        return "upload";
    }

    @GetMapping("/status")
    public String statusPage(Model model) {
        model.addAttribute("title", "Проверить статус");
        return "status";
    }

    @GetMapping("/status/{uploadRequestId}")
    public String statusDetailPage(
            @PathVariable String uploadRequestId,
            @RequestParam String clientId,
            Model model) {

        model.addAttribute("title", "Статус загрузки");
        model.addAttribute("uploadRequestId", uploadRequestId);
        model.addAttribute("clientId", clientId);
        return "status-detail";
    }

    @GetMapping("/download")
    public String downloadPage(Model model) {
        model.addAttribute("title", "Скачать файл");
        return "download";
    }

    @GetMapping("/api-docs")
    public String apiDocs(Model model) {
        model.addAttribute("title", "API Документация");
        return "api-docs";
    }
}