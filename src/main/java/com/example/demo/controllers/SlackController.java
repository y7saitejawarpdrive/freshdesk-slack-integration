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

    public SlackController(FreshdeskService freshdeskService,
                           SlackService slackService,
                           TicketChannelMap map) {
        this.freshdeskService = freshdeskService;
        this.slackService = slackService;
        this.map = map;
    }

    @PostMapping("/events")
    public ResponseEntity<Map<String, String>> slackEvents(@RequestBody Map<String, Object> body) {

        if ("url_verification".equals(body.get("type"))) {
            return ResponseEntity.ok(Map.of("challenge", body.get("challenge").toString()));
        }

        if (!body.containsKey("event")) {
            return ResponseEntity.ok(Map.of("status", "ignored_no_event"));
        }

        Map<String, Object> event = (Map<String, Object>) body.get("event");

        if ("message".equals(event.get("type")) && !event.containsKey("bot_id")) {

            // Ignore system events (like joins) unless it is a FILE SHARE
            boolean isFileShare = "file_share".equals(event.get("subtype"));

            if (event.containsKey("subtype") && !isFileShare) {
                return ResponseEntity.ok(Map.of("status", "ignored_subtype"));
            }

            Object channelObj = event.get("channel");
            String channelId = (channelObj != null) ? channelObj.toString() : null;
            String ticketId = map.getTicketId(channelId);

            if (ticketId != null) {

                // --- CASE 1: File Upload ---
                if (event.containsKey("files")) {
                    List<Map<String, Object>> files = (List<Map<String, Object>>) event.get("files");

                    for (Map<String, Object> file : files) {
                        String urlPrivate = (String) file.get("url_private");
                        String name = (String) file.get("name");

                        System.out.println("📥 Downloading file from Slack: " + name);

                        // Download from Slack
                        byte[] fileData = slackService.downloadFile(urlPrivate);

                        // Upload to Freshdesk
                        if (fileData != null) {
                            freshdeskService.addNoteWithFile(ticketId, "📎 File from Slack: " + name, name, fileData);
                        }
                    }
                }

                // --- CASE 2: Text Message ---
                if (event.containsKey("text")) {
                    String text = event.get("text").toString();
                    if(text != null && !text.isEmpty()) {
                        // Only add text note if it's not just a file upload message
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
