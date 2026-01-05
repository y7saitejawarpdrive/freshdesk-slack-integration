package com.example.demo.controllers;

import com.example.demo.services.FreshdeskService;
import com.example.demo.services.ReminderService;
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

    // Inject the new Reminder Service
    private final ReminderService reminderService;

    private static final String SUPPORT_AGENT_ID = "U09S0DD7M16";

    public SlackController(FreshdeskService freshdeskService, SlackService slackService, TicketChannelMap map, ReminderService reminderService) {
        this.freshdeskService = freshdeskService;
        this.slackService = slackService;
        this.map = map;
        this.reminderService = reminderService;
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

                // --- FILES ---
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

                        // ============================================
                        // NEW LOGIC: WATCH FOR WORKFLOW MESSAGES
                        // ============================================

                        // 1. START TIMER: "Ticket Acceptance request was sent to :- @User"
                        if (text.contains("Ticket Acceptance request was sent to")) {
                            // Extract the user tag (e.g., <@U12345>)
                            Matcher m = Pattern.compile("(<@U[A-Z0-9]+>)").matcher(text);
                            if (m.find()) {
                                String userTag = m.group(1);
                                reminderService.startTracking(channelId, userTag);
                            }
                        }

                        // 2. STOP TIMER: "@User has accepted the ticket"
                        if (text.contains("has accepted the ticket")) {
                            reminderService.stopTracking(channelId);
                        }
                        // ============================================

                        // IGNORE SYSTEM MESSAGES
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
                                int slaHours = freshdeskService.getTicketSlaHours(ticketId);

                                if (etaHours > 0 && slaHours > 0) {
                                    if (etaHours > slaHours) {
                                        slackService.sendApprovalMessage(channelId, ticketId, text, SUPPORT_AGENT_ID);
                                    } else {
                                        slackService.sendMessage(channelId, "ℹ️ New ETA (" + etaHours + "h) is within SLA (" + slaHours + "h). No approval needed.");
                                    }
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