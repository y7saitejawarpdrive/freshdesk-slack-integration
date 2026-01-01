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

    // YOUR AGENT IDs
    private static final String SUPPORT_AGENT_ID = "U09S0DD7M16";
    private static final String SUPPORT_AGENT_ID1 = "U09BZKZNZ3K";
    private static final String SUPPORT_AGENT_ID2 = "U096MGHD9UZ";
    private static final String WORKFLOW_BOT_ID = "U01234567";

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
        String sla = body.getOrDefault("sla", "0").toString();
        String carrier = body.getOrDefault("carrier", "Unknown").toString();
        String lastScan = body.getOrDefault("last_scan", "Unknown").toString();

        String desc = body.getOrDefault("description", "").toString().replaceAll("\\<.*?\\>", "").trim();
        if (desc.length() > 500) desc = desc.substring(0, 500) + "...";

        String cleanOrderId = orderId.replaceAll("[^a-zA-Z0-9]", "");
        if (cleanOrderId.isEmpty()) cleanOrderId = ticketId;
        String cleanIssue = issueType.toLowerCase().replaceAll("[^a-z0-9]", "-");

        String channelName = ("ticket-" + cleanOrderId + "-" + cleanIssue);
        if (channelName.length() > 80) channelName = channelName.substring(0, 80);

        if (map.getChannelId(ticketId) != null) return ResponseEntity.ok("Exists");

        String channelId = slackService.createChannel(channelName);
        if(channelId != null) {
            map.put(ticketId, channelId);

            // Save SLA
            try {
                String slaNum = sla.replaceAll("[^0-9]", "");
                int slaInt = slaNum.isEmpty() ? 0 : Integer.parseInt(slaNum);
                map.putSla(ticketId, slaInt);
            } catch (Exception e) {}

            String msg = String.format(
                    "*Ticket details:*\n" +
                            "• *Issue Type:* %s\n" +
                            "• *Priority:* %s\n" +
                            "• *SLA:* %s\n" +
                            "• *Order ID:* %s\n" +
                            "• *Carrier:* %s\n" +
                            "• *Last Scan:* %s\n" +
                            "• *Description:* %s\n",
                    issueType, priority, sla, orderId, carrier, lastScan, desc
            );

            slackService.sendMessage(channelId, msg);
            slackService.inviteUserToChannel(channelId, SUPPORT_AGENT_ID);
            slackService.inviteUserToChannel(channelId, SUPPORT_AGENT_ID1);
            slackService.inviteUserToChannel(channelId, SUPPORT_AGENT_ID2);
            slackService.inviteUserToChannel(channelId, WORKFLOW_BOT_ID);
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

        // --- NEW HTML PROCESSING ---
        String cleanMsg = convertHtmlToSlack(rawHtml);

        // Filters
        if (cleanMsg.contains("(Slack):") || cleanMsg.contains("Slack:")) return ResponseEntity.ok("Ignored echo");
        if (cleanMsg.contains("📎") || cleanMsg.contains("shared file:")) return ResponseEntity.ok("Ignored file echo");
        if (cleanMsg.contains("✅") || cleanMsg.contains("❌") || cleanMsg.contains("Approved by") || cleanMsg.contains("Rejected by")) return ResponseEntity.ok("Ignored system");

        if (map.isDuplicate(ticketId, cleanMsg.trim())) return ResponseEntity.ok("Duplicate");

        String channelId = map.getChannelId(ticketId);
        if (channelId == null) {
            System.out.println("❌ Memory Empty. No channel found for Ticket " + ticketId);
            return ResponseEntity.ok("No mapping");
        }

        if (!cleanMsg.isEmpty()) {
            slackService.sendMessage(channelId, "👤 *" + sender + "*: " + cleanMsg);
        }
        return ResponseEntity.ok("OK");
    }

    // --- IMPROVED HTML CLEANER ---
    private String convertHtmlToSlack(String html) {
        if (html == null) return "";
        String processed = html;

        try {
            // 1. Replace <br> and <div...> with newlines
            processed = processed.replaceAll("(?i)<br\\s*/?>", "\n");
            processed = processed.replaceAll("(?i)</div>", "\n");

            // 2. Extract Links: <a href="URL">Text</a> -> Text (URL)
            Pattern linkPattern = Pattern.compile("<a\\s+(?:[^>]*?\\s+)?href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher linkMatcher = linkPattern.matcher(processed);
            StringBuilder sb = new StringBuilder();
            while (linkMatcher.find()) {
                String url = linkMatcher.group(1);
                String text = linkMatcher.group(2).replaceAll("\\<.*?\\>", "").trim();
                if (text.isEmpty()) text = "Link";
                linkMatcher.appendReplacement(sb, text + " (" + url + ")");
            }
            linkMatcher.appendTail(sb);
            processed = sb.toString();

            // 3. Extract Images: <img src="URL"> -> [Image: URL]
            Pattern imgPattern = Pattern.compile("<img\\s+(?:[^>]*?\\s+)?src=[\"']([^\"']+)[\"'][^>]*>", Pattern.CASE_INSENSITIVE);
            Matcher imgMatcher = imgPattern.matcher(processed);
            sb = new StringBuilder();
            while (imgMatcher.find()) {
                String url = imgMatcher.group(1);
                imgMatcher.appendReplacement(sb, " [Image: " + url + "] ");
            }
            imgMatcher.appendTail(sb);
            processed = sb.toString();

            // 4. Strip remaining tags
            processed = processed.replaceAll("\\<.*?\\>", "").trim();

            // 5. Decode entities
            processed = processed.replace("&nbsp;", " ")
                    .replace("&amp;", "&")
                    .replace("&lt;", "<")
                    .replace("&gt;", ">")
                    .replace("&quot;", "\"")
                    .replace("&#128172;", ""); // Remove the speech bubble emoji

            return processed.trim();
        } catch (Exception e) {
            // Fallback: just strip tags if regex fails
            return html.replaceAll("\\<.*?\\>", "");
        }
    }
}