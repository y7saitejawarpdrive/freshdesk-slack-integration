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

    // TODO: DOUBLE CHECK THIS ID. It must look like U0123ABC (Not a name)
    private static final String SUPPORT_AGENT_ID = "U085Q6D2E07";

    private static final Map<String, List<String>> USER_GROUPS_BY_REGION = Map.of(
            "bengaluru", List.of("S0A1L56DJ3B", "S0A1APMTHD2"),
            "hyderabad", List.of("S0A1B5UBYJ0", "S0A0S5E6MD5")
    );

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

            // Invite the Support Agent
            System.out.println("👉 Attempting to invite Support Agent: " + SUPPORT_AGENT_ID);
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

        // --- FILTERING LOGIC ---

        // 1. Clean HTML first
        String withLinks = convertHtmlLinks(rawHtml);
        String cleanMsg = withLinks.replaceAll("\\<.*?\\>", "").trim();

        // 2. Remove weird entities (like the paperclip &#128206; and speech bubble)
        cleanMsg = cleanMsg.replace("&#128172;", "")
                .replace("&#128206;", "")
                .replace("&nbsp;", " ")
                .replace("&amp;", "&")
                .replace("&lt;", "<")
                .replace("&gt;", ">")
                .replace("&quot;", "\"");

        // 3. STOP FILE ECHO: If it contains the attachment emoji or "shared file" text we generated
        if (cleanMsg.contains("📎") || cleanMsg.contains("shared file:")) {
            System.out.println("🛑 Ignored File Echo: " + cleanMsg);
            return ResponseEntity.ok("Ignored file echo");
        }

        // 4. STOP APPROVAL ECHO: If it contains our approval/rejection checkmarks
        if (cleanMsg.contains("✅") || cleanMsg.contains("❌") || cleanMsg.contains("Approved by") || cleanMsg.contains("Rejected by")) {
            System.out.println("🛑 Ignored System Status: " + cleanMsg);
            return ResponseEntity.ok("Ignored system status");
        }

        // 5. STOP SLACK ECHO
        if (cleanMsg.contains("Slack:")) {
            return ResponseEntity.ok("Ignored echo");
        }

        // 6. Duplicate Check
        if (map.isDuplicate(ticketId, cleanMsg.trim())) {
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