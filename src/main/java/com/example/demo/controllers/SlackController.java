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

    // We need this ID to tag them in the approval request
    // Ensure this ID matches what is in FreshdeskController
    private static final String SUPPORT_AGENT_ID = "U085Q6D2E07";

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
            Object userObj = event.get("user"); // Get the Slack User ID
            String channelId = (channelObj != null) ? channelObj.toString() : null;
            String userId = (userObj != null) ? userObj.toString() : null;

            String ticketId = map.getTicketId(channelId);

            if (ticketId != null && userId != null) {

                // --- FETCH REAL NAME ---
                String senderName = slackService.getUserName(userId);

                // --- FILES ---
                if (event.containsKey("files")) {
                    List<Map<String, Object>> files = (List<Map<String, Object>>) event.get("files");
                    for (Map<String, Object> file : files) {
                        String urlPrivate = (String) file.get("url_private");
                        String name = (String) file.get("name");
                        byte[] fileData = slackService.downloadFile(urlPrivate);
                        if (fileData != null && fileData.length > 0) {
                            freshdeskService.addNoteWithFile(ticketId, "📎 " + senderName + " shared file: " + name, name, fileData);
                        }
                    }
                }
                // --- TEXT ---
                else if (event.containsKey("text")) {
                    String text = event.get("text").toString();
                    if(text != null && !text.isEmpty()) {

                        // 1. Sync Text with Name
                        freshdeskService.addNote(ticketId, "💬 " + senderName + " (Slack):\n" + text);

                        // 2. Logic: Check for ETA
                        if (text.toUpperCase().contains("ETA")) {
                            System.out.println("⏰ ETA update detected: " + text);

                            int etaHours = extractNumber(text);
                            int slaHours = freshdeskService.getTicketSlaHours(ticketId);

                            if (etaHours > slaHours) {
                                // Pass the SUPPORT_AGENT_ID here to tag them!
                                slackService.sendApprovalMessage(channelId, ticketId, text, SUPPORT_AGENT_ID);
                                System.out.println("🚀 ETA > SLA. Approval Triggered.");
                            } else {
                                slackService.sendMessage(channelId, "ℹ️ New ETA (" + etaHours + "h) is within SLA (" + slaHours + "h). No approval needed.");
                            }
                        }
                    }
                }
            }
        }
        return ResponseEntity.ok(Map.of("status", "ok"));
    }

    private int extractNumber(String text) {
        try {
            Pattern p = Pattern.compile("\\d+");
            Matcher m = p.matcher(text);
            if (m.find()) return Integer.parseInt(m.group());
        } catch (Exception e) {}
        return 0;
    }
}