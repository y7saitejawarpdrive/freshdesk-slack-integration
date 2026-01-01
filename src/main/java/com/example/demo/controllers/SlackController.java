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

    private static final String SUPPORT_AGENT_ID = "U09S0DD7M16";

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

        if ("message".equals(event.get("type"))) {

            boolean isBot = event.containsKey("bot_id");
            String channelId = (event.get("channel") != null) ? event.get("channel").toString() : null;
            String ticketId = map.getTicketId(channelId);

            if (ticketId != null) {

                if (event.containsKey("files") && !isBot) {
                    List<Map<String, Object>> files = (List<Map<String, Object>>) event.get("files");
                    String userId = (event.get("user") != null) ? event.get("user").toString() : null;
                    String senderName = (userId != null) ? slackService.getUserName(userId) : "User";

                    for (Map<String, Object> file : files) {
                        String urlPrivate = (String) file.get("url_private");
                        String name = (String) file.get("name");
                        byte[] fileData = slackService.downloadFile(urlPrivate);
                        if (fileData != null && fileData.length > 0) {
                            freshdeskService.addNoteWithFile(ticketId, "📎 " + senderName + " shared file: " + name, name, fileData);
                        }
                    }
                }
                else if (event.containsKey("text")) {
                    String text = event.get("text").toString();

                    if (text != null && !text.isEmpty()) {

                        if (text.contains("within SLA") ||
                                text.contains("No approval needed") ||
                                text.contains("SLA Breach Expected") ||
                                text.contains("Ticket details") ||
                                text.startsWith("ℹ️") ||
                                text.startsWith("⚠️")) {
                            return ResponseEntity.ok(Map.of("status", "ignored_system_msg"));
                        }

                        boolean isWorkflowUpdate = isBot && text.toUpperCase().contains("ETA");

                        if (!isBot || isWorkflowUpdate) {

                            String userId = (event.get("user") != null) ? event.get("user").toString() : null;
                            String senderName = "Ops Workflow";
                            if (userId != null) senderName = slackService.getUserName(userId);

                            freshdeskService.addNote(ticketId, "💬 " + senderName + " (Slack):\n" + text);

                            if (text.toUpperCase().contains("ETA")) {
                                int etaHours = extractNumber(text);

                                // --- FIX: GET SLA FROM MEMORY ---
                                int slaHours = map.getSla(ticketId);

                                System.out.println("DEBUG: Comparing ETA " + etaHours + " vs SLA " + slaHours);

                                if (etaHours > 0 && slaHours > 0) { // Only compare if we have valid numbers
                                    if (etaHours > slaHours) {
                                        slackService.sendApprovalMessage(channelId, ticketId, text, SUPPORT_AGENT_ID);
                                    } else {
                                        slackService.sendMessage(channelId, "ℹ️ New ETA (" + etaHours + "h) is within SLA (" + slaHours + "h). No approval needed.");
                                    }
                                } else if (slaHours == 0 && etaHours > 0) {
                                    System.out.println("⚠ SLA missing for comparison. Skipping approval.");
                                }
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
            Matcher m = Pattern.compile("\\d+").matcher(text);
            if (m.find()) return Integer.parseInt(m.group());
        } catch (Exception e) {}
        return 0;
    }
}