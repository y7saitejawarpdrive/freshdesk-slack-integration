package com.example.demo.controllers;

import com.example.demo.services.FreshdeskService;
import com.example.demo.services.SlackService;
import com.example.demo.services.TicketChannelMap;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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
        if ("url_verification".equals(body.get("type"))) return ResponseEntity.ok(Map.of("challenge", body.get("challenge").toString()));
        if (!body.containsKey("event")) return ResponseEntity.ok(Map.of("status", "ignored"));

        Map<String, Object> event = (Map<String, Object>) body.get("event");

        if ("message".equals(event.get("type")) && !event.containsKey("bot_id")) {
            boolean isFileShare = "file_share".equals(event.get("subtype"));
            if (event.containsKey("subtype") && !isFileShare) return ResponseEntity.ok(Map.of("status", "ignored"));

            String channelId = (event.get("channel") != null) ? event.get("channel").toString() : null;
            String userId = (event.get("user") != null) ? event.get("user").toString() : null;
            String ticketId = map.getTicketId(channelId);

            if (ticketId != null && userId != null) {
                String senderName = slackService.getUserName(userId);

                if (event.containsKey("files")) {
                    List<Map<String, Object>> files = (List<Map<String, Object>>) event.get("files");
                    for (Map<String, Object> file : files) {
                        String urlPrivate = (String) file.get("url_private");
                        byte[] fileData = slackService.downloadFile(urlPrivate);
                        if (fileData != null) freshdeskService.addNoteWithFile(ticketId, "📎 " + senderName + " shared file: " + file.get("name"), file.get("name").toString(), fileData);
                    }
                } else if (event.containsKey("text")) {
                    String text = event.get("text").toString();
                    if(text != null && !text.isEmpty()) {
                        freshdeskService.addNote(ticketId, "💬 " + senderName + " (Slack):\n" + text);

                        if (text.toUpperCase().contains("ETA")) {
                            int eta = extractNumber(text);
                            int sla = freshdeskService.getTicketSlaHours(ticketId);
                            if (eta > sla) slackService.sendApprovalMessage(channelId, ticketId, text, userId);
                            else slackService.sendMessage(channelId, "ℹ️ New ETA (" + eta + "h) is within SLA (" + sla + "h). No approval needed.");
                        }
                    }
                }
            }
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    private int extractNumber(String text) {
        try {
            Matcher m = Pattern.compile("\\d+").matcher(text);
            if (m.find()) return Integer.parseInt(m.group());
        } catch (Exception e) {}
        return 0;
    }
}