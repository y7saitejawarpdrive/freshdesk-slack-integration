package com.example.demo.controllers;

import com.example.demo.services.FreshdeskService;
import com.example.demo.services.SlackService;
import com.example.demo.services.TicketChannelMap;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/slack")
public class SlackController {

    private final FreshdeskService freshdeskService;
    private final SlackService slackService;
    private final TicketChannelMap map;

    public SlackController(FreshdeskService freshdeskService, SlackService slackService, TicketChannelMap map) {
        this.freshdeskService = freshdeskService;
        this.slackService = slackService;
        this.map = map;
    }

    @PostMapping("/events")
    public ResponseEntity<Map<String, String>> slackEvents(@RequestBody Map<String, Object> body) {
        if ("url_verification".equals(body.get("type"))) {
            return ResponseEntity.ok(Map.of("challenge", body.get("challenge").toString()));
        }
        if (!body.containsKey("event")) return ResponseEntity.ok(Map.of("status", "ignored"));

        Map<String, Object> event = (Map<String, Object>) body.get("event");

        if ("message".equals(event.get("type")) && !event.containsKey("bot_id")) {
            boolean isFileShare = "file_share".equals(event.get("subtype"));
            if (event.containsKey("subtype") && !isFileShare) return ResponseEntity.ok(Map.of("status", "ignored_subtype"));

            Object channelObj = event.get("channel");
            String channelId = (channelObj != null) ? channelObj.toString() : null;
            String ticketId = map.getTicketId(channelId);

            if (ticketId != null) {
                // --- HANDLE FILES ---
                if (event.containsKey("files")) {
                    List<Map<String, Object>> files = (List<Map<String, Object>>) event.get("files");
                    for (Map<String, Object> file : files) {
                        String urlPrivate = (String) file.get("url_private");
                        String name = (String) file.get("name");

                        System.out.println("📥 Starting download for: " + name);
                        byte[] fileData = slackService.downloadFile(urlPrivate);

                        if (fileData != null && fileData.length > 0) {
                            System.out.println("✅ Downloaded " + fileData.length + " bytes. Uploading to FD...");
                            freshdeskService.addNoteWithFile(ticketId, "📎 File from Slack: " + name, name, fileData);
                        } else {
                            System.out.println("❌ Download failed or empty: " + name);
                        }
                    }
                }
                // --- HANDLE TEXT ---
                if (event.containsKey("text")) {
                    String text = event.get("text").toString();
                    if(text != null && !text.isEmpty()) {
                        if (!isFileShare || !text.contains("uploaded a file")) {
                            freshdeskService.addNote(ticketId, "💬 Slack:\n" + text);
                        }
                    }
                }
            }
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}