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

    // Ensure this is the correct Agent ID
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

            // Check if sender is a Bot/Workflow
            boolean isBot = event.containsKey("bot_id");

            String channelId = (event.get("channel") != null) ? event.get("channel").toString() : null;
            String ticketId = map.getTicketId(channelId);

            if (ticketId != null) {

                // --- FILES (Strictly ignore Bots to prevent loops) ---
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
                // --- TEXT ---
                else if (event.containsKey("text")) {
                    String text = event.get("text").toString();

                    if (text != null && !text.isEmpty()) {

                        // --- THE FIX IS HERE ---
                        // We normally ignore bots (!isBot).
                        // BUT, if the text contains "ETA", we allow it (because it's your Workflow).
                        boolean isWorkflowUpdate = isBot && text.toUpperCase().contains("ETA");

                        // Also, filter out our OWN Bot's messages (Freshdesk Agent updates) to prevent loops
                        boolean isMyOwnBot = text.contains("Freshdesk Agent") || text.contains("New Ticket") || text.contains("Breach Expected");

                        if ((!isBot || isWorkflowUpdate) && !isMyOwnBot) {

                            // Determine Sender Name
                            String userId = (event.get("user") != null) ? event.get("user").toString() : null;
                            String senderName = "Ops Workflow";

                            // If it's a real user, get their name. If it's a workflow (userId is null), keep "Ops Workflow"
                            if (userId != null) {
                                senderName = slackService.getUserName(userId);
                            }

                            // 1. Sync Text with Name
                            freshdeskService.addNote(ticketId, "💬 " + senderName + " (Slack):\n" + text);

                            // 2. Logic: Check for ETA
                            if (text.toUpperCase().contains("ETA")) {
                                System.out.println("⏰ ETA update detected: " + text);

                                int etaHours = extractNumber(text);
                                int slaHours = freshdeskService.getTicketSlaHours(ticketId);

                                if (etaHours > slaHours) {
                                    // Trigger Approval
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