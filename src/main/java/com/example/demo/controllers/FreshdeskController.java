package com.example.demo.controllers;

import com.example.demo.services.SlackService;
import com.example.demo.services.TicketChannelMap;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/freshdesk")
@RequiredArgsConstructor
public class FreshdeskController {

    private final SlackService slackService;
    private final TicketChannelMap map;

    // TODO: REPLACE THIS WITH THE ACTUAL SLACK MEMBER ID (e.g., U0123ABC)
    private static final String SUPPORT_AGENT_ID = "U09S0DD7M16";

    @PostMapping("/ticket/creation")
    public ResponseEntity<String> onTicketCreate(@RequestBody Map<String, Object> body) {
        System.out.println("🔥 Creating Ticket Payload: " + body);

        if (!body.containsKey("ticket_id")) return ResponseEntity.badRequest().body("Missing ticket_id");

        String ticketId = body.get("ticket_id").toString();

        // --- 1. Extract New Fields ---
        String orderId = body.getOrDefault("order_id", "NA").toString();
        String issueType = body.getOrDefault("issue_type", "General").toString();
        String priority = body.getOrDefault("priority", "Low").toString();
        String sla = body.getOrDefault("sla", "24 hours").toString();
        String carrier = body.getOrDefault("carrier", "Unknown").toString();
        String lastScan = body.getOrDefault("last_scan", "Unknown").toString();

        // --- 2. Generate Channel Name (#ticket-458923-delayed-delivery) ---
        // Clean Order ID (remove special chars)
        String cleanOrderId = orderId.replaceAll("[^a-zA-Z0-9]", "");
        if (cleanOrderId.isEmpty()) cleanOrderId = ticketId; // Fallback to Ticket ID if Order ID missing

        // Clean Issue Type (spaces to hyphens)
        String cleanIssue = issueType.toLowerCase().replaceAll("[^a-z0-9]", "-");

        String channelName = ("ticket-" + cleanOrderId + "-" + cleanIssue);
        // Slack limit is 80 chars, truncate if needed
        if (channelName.length() > 80) channelName = channelName.substring(0, 80);

        if (map.getChannelId(ticketId) != null) return ResponseEntity.ok("Exists");

        // --- 3. Create Channel ---
        String channelId = slackService.createChannel(channelName);
        if(channelId != null) {
            map.put(ticketId, channelId);

            // --- 4. Formatted Welcome Message ---
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

            // --- 5. Invite the specific Support Agent ---
            slackService.inviteUserToChannel(channelId, SUPPORT_AGENT_ID);
        }
        return ResponseEntity.ok("Created");
    }

    @PostMapping("/ticket/updates")
    public ResponseEntity<?> onTicketUpdate(@RequestBody Map<String, Object> body) {
        String ticketId = body.get("ticket_id").toString();

        // Sender Name
        Object senderObj = body.get("sender_name");
        String sender = (senderObj != null && !senderObj.toString().isEmpty()) ? senderObj.toString() : "Freshdesk Agent";

        // Content
        String rawHtml = "";
        if (body.containsKey("comment_body") && body.get("comment_body") != null) rawHtml = body.get("comment_body").toString();
        if ((rawHtml.isEmpty() || rawHtml.equals("null")) && body.containsKey("public_comment")) {
            Object pc = body.get("public_comment");
            if (pc != null) rawHtml = pc.toString();
        }

        System.out.println("📨 Update from " + sender + " | Content: " + rawHtml);

        if (rawHtml == null || rawHtml.trim().isEmpty() || rawHtml.equals("null")) return ResponseEntity.ok("Ignored empty");
        if (rawHtml.contains("Slack:")) return ResponseEntity.ok("Ignored echo");

        // Duplicate Check
        if (map.isDuplicate(ticketId, rawHtml.trim())) {
            System.out.println("🛑 Ignored duplicate");
            return ResponseEntity.ok("Duplicate");
        }

        String channelId = map.getChannelId(ticketId);
        if (channelId == null) {
            System.out.println("❌ No mapping for Ticket " + ticketId);
            return ResponseEntity.ok("No mapping");
        }

        try {
            String withLinks = convertHtmlLinks(rawHtml);
            String cleanMsg = withLinks.replaceAll("\\<.*?\\>", "").trim();
            cleanMsg = cleanMsg.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");

            if (!cleanMsg.isEmpty()) {
                String slackMsg = String.format("👤 *%s*: %s", sender, cleanMsg);
                slackService.sendMessage(channelId, slackMsg);
            }
        } catch (Exception e) {
            slackService.sendMessage(channelId, "📝 Freshdesk Update:\n" + rawHtml);
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