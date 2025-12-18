package com.example.demo.controllers;

import com.example.demo.services.SlackService;
import com.example.demo.services.TicketChannelMap;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/freshdesk")
@RequiredArgsConstructor
public class FreshdeskController {

    private final SlackService slackService;
    private final TicketChannelMap map;

    private static final Map<String, List<String>> USER_GROUPS_BY_REGION = Map.of(
            "bengaluru", List.of("S0A1L56DJ3B", "S0A1APMTHD2"),
            "hyderabad", List.of("S0A1B5UBYJ0", "S0A0S5E6MD5")
    );

    // TODO: ENSURE THIS ID IS CORRECT
    private static final String SUPPORT_AGENT_ID = "U085Q6D2E07";

    @PostMapping("/ticket/creation")
    public ResponseEntity<String> onTicketCreate(@RequestBody Map<String, Object> body) {
        if (!body.containsKey("ticket_id")) return ResponseEntity.badRequest().body("Missing ticket_id");

        String ticketId = body.get("ticket_id").toString();
        String orderId = body.getOrDefault("order_id", "NA").toString();
        String issueType = body.getOrDefault("issue_type", "General").toString();
        String priority = body.getOrDefault("priority", "Low").toString();
        String sla = body.getOrDefault("sla", "24 hours").toString();
        String carrier = body.getOrDefault("carrier", "Unknown").toString();
        String lastScan = body.getOrDefault("last_scan", "Unknown").toString();

        String cleanOrderId = orderId.replaceAll("[^a-zA-Z0-9]", "");
        if (cleanOrderId.isEmpty()) cleanOrderId = ticketId;
        String cleanIssue = issueType.toLowerCase().replaceAll("[^a-z0-9]", "-");

        String channelName = ("ticket-" + cleanOrderId + "-" + cleanIssue);
        if (channelName.length() > 80) channelName = channelName.substring(0, 80);

        if (map.getChannelId(ticketId) != null) return ResponseEntity.ok("Exists");

        String channelId = slackService.createChannel(channelName);
        if(channelId != null) {
            map.put(ticketId, channelId);

            String msg = String.format(
                    ":small_blue_diamond: *STEP 1: Ticket Created in Freshdesk*\n" +
                            "Support Agent receives a call\n\n" +
                            "*Ticket details:*\n" +
                            "• *Issue Type:* %s\n" +
                            "• *Priority:* %s\n" +
                            "• *SLA:* %s\n" +
                            "• *Order ID:* %s\n" +
                            "• *Carrier:* %s\n" +
                            "• *Last Scan:* %s\n",
                    issueType, priority, sla, orderId, carrier, lastScan
            );

            slackService.sendMessage(channelId, msg);
            slackService.inviteUserToChannel(channelId, SUPPORT_AGENT_ID);
        }
        return ResponseEntity.ok("Created");
    }

    @PostMapping("/ticket/updates")
    public ResponseEntity<?> onTicketUpdate(@RequestBody Map<String, Object> body) {
        String ticketId = body.get("ticket_id").toString();

        Object senderObj = body.get("sender_name");
        String sender = (senderObj != null && !senderObj.toString().isEmpty()) ? senderObj.toString() : "Freshdesk Agent";

        String rawHtml = "";
        if (body.containsKey("comment_body") && body.get("comment_body") != null) rawHtml = body.get("comment_body").toString();
        if ((rawHtml.isEmpty() || rawHtml.equals("null")) && body.containsKey("public_comment")) {
            Object pc = body.get("public_comment");
            if (pc != null) rawHtml = pc.toString();
        }

        if (rawHtml == null || rawHtml.trim().isEmpty() || rawHtml.equals("null")) return ResponseEntity.ok("Ignored empty");

        // --- STEP 1: CLEANUP FIRST (Fixes the Symbol Issue) ---
        String withLinks = convertHtmlLinks(rawHtml);
        String cleanMsg = withLinks.replaceAll("\\<.*?\\>", "").trim();
        // Remove the Emoji code and common entities
        cleanMsg = cleanMsg.replace("&#128172;", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"");

        // --- STEP 2: IGNORE SYSTEM MESSAGES (Fixes the Button Loop) ---
        // If the note is just our own approval/rejection log, DO NOT send it back to Slack
        if (cleanMsg.contains("✅ Approved by") || cleanMsg.contains("❌ Rejected by") || cleanMsg.contains("✅ Reroute Approved")) {
            System.out.println("🛑 Ignored System Note: " + cleanMsg);
            return ResponseEntity.ok("Ignored system note");
        }

        // --- STEP 3: IGNORE ECHO ---
        // Check if it starts with "Slack:" or contains the emoji we typically send
        if (cleanMsg.contains("Slack:") || cleanMsg.contains("💬")) {
            System.out.println("🛑 Ignored Echo");
            return ResponseEntity.ok("Ignored echo");
        }

        // --- STEP 4: DUPLICATE CHECK ---
        if (map.isDuplicate(ticketId, cleanMsg.trim())) {
            System.out.println("🛑 Ignored duplicate");
            return ResponseEntity.ok("Duplicate");
        }

        String channelId = map.getChannelId(ticketId);
        if (channelId == null) {
            System.out.println("❌ No mapping for Ticket " + ticketId);
            return ResponseEntity.ok("No mapping");
        }

        if (!cleanMsg.isEmpty()) {
            String slackMsg = String.format("👤 *%s*: %s", sender, cleanMsg);
            slackService.sendMessage(channelId, slackMsg);
            System.out.println("✅ Sent to Slack!");
        }

        return ResponseEntity.ok("OK");
    }

    private String convertHtmlLinks(String html) {
        if (html == null) return "";
        try {
            Pattern pattern = Pattern.compile("<a\\s+(?:[^>]*?\\s+)?href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher matcher = pattern.matcher(html);
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                String url = matcher.group(1);
                String text = matcher.group(2).replaceAll("\\<.*?\\>", "").trim();
                matcher.appendReplacement(sb, text + " (" + url + ")");
            }
            matcher.appendTail(sb);
            return sb.toString();
        } catch (Exception e) { return html; }
    }
}