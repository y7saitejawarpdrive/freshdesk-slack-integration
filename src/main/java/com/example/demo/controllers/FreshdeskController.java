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

    @PostMapping("/ticket/creation")
    public ResponseEntity<String> onTicketCreate(@RequestBody Map<String, Object> body) {
        System.out.println("🔥 Creating Ticket: " + body); // DEBUG LOG
        if (!body.containsKey("ticket_id") || !body.containsKey("region")) return ResponseEntity.badRequest().body("Missing data");

        String ticketId = body.get("ticket_id").toString();
        String region = body.get("region").toString().toLowerCase();
        String subject = body.getOrDefault("subject", "No Subject").toString();

        String desc = body.getOrDefault("description", "").toString().replaceAll("\\<.*?\\>", "").trim();
        if (desc.length() > 500) desc = desc.substring(0, 500) + "...";

        String channelName = ("ticket-" + ticketId + "-" + region).replaceAll("[^a-z0-9-]", "");

        if (map.getChannelId(ticketId) != null) return ResponseEntity.ok("Exists");

        String channelId = slackService.createChannel(channelName);
        if(channelId != null) {
            map.put(ticketId, channelId);
            List<String> groupIds = USER_GROUPS_BY_REGION.getOrDefault(region, List.of());

            StringBuilder mentions = new StringBuilder();
            for (String gid : groupIds) mentions.append("<!subteam^").append(gid).append("> ");

            String msg = String.format(":ticket: *New Ticket*\n*ID:* %s\n*Subject:* %s\n*Region:* %s\n*Desc:* %s\n\nWelcome %s",
                    ticketId, subject, region, desc, mentions);

            slackService.sendMessage(channelId, msg);
            for (String gid : groupIds) slackService.inviteUsersToChannel(channelId, slackService.getUserGroupMembers(gid));
        }
        return ResponseEntity.ok("Created");
    }

    @PostMapping("/ticket/updates")
    public ResponseEntity<?> onTicketUpdate(@RequestBody Map<String, Object> body) {
        String ticketId = body.get("ticket_id").toString();
        String rawHtml = "";

        // Priority: latest_comment (from {{comment.body}})
        if (body.containsKey("latest_comment")) rawHtml = body.get("latest_comment").toString();
        else if (body.containsKey("latest_note")) rawHtml = body.get("latest_note").toString();
        else if (body.containsKey("body")) rawHtml = body.get("body").toString();

        // 1. Log what we received
        System.out.println("📨 Update for Ticket " + ticketId + " | Raw Content: " + rawHtml);

        // 2. Validate Content
        if (rawHtml == null || rawHtml.trim().isEmpty() || rawHtml.equals("null")) {
            System.out.println("⚠ Skipped: Content is empty/null");
            return ResponseEntity.ok("Ignored empty");
        }

        // 3. Check Echo
        if (rawHtml.contains("Slack:")) {
            System.out.println("🛑 Skipped: Echo from Slack");
            return ResponseEntity.ok("Ignored echo");
        }

        // 4. Check Duplicate
        if (map.isDuplicate(ticketId, rawHtml.trim())) {
            System.out.println("🛑 Skipped: Duplicate hash detected");
            return ResponseEntity.ok("Duplicate");
        }

        // 5. Check Map (Did app restart?)
        String channelId = map.getChannelId(ticketId);
        if (channelId == null) {
            System.out.println("❌ ERROR: No channel mapping for Ticket " + ticketId + ". (RAM wiped?)");
            return ResponseEntity.ok("No mapping");
        }

        // 6. Clean and Send
        try {
            // First convert links
            String withLinks = convertHtmlLinks(rawHtml);
            // Then strip tags
            String cleanMsg = withLinks.replaceAll("\\<.*?\\>", "").trim();
            // Decode entities
            cleanMsg = cleanMsg.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");

            if (!cleanMsg.isEmpty()) {
                slackService.sendMessage(channelId, "📝 Freshdesk Update:\n" + cleanMsg);
                System.out.println("✅ Sent to Slack Channel " + channelId);
            } else {
                System.out.println("⚠ Message was empty after stripping HTML!");
            }
        } catch (Exception e) {
            System.out.println("❌ Error parsing HTML: " + e.getMessage());
            // Fallback: send raw text stripped
            slackService.sendMessage(channelId, "📝 Freshdesk Update:\n" + rawHtml.replaceAll("\\<.*?\\>", ""));
        }

        return ResponseEntity.ok("OK");
    }

    private String convertHtmlLinks(String html) {
        if (html == null) return "";
        try {
            Pattern pattern = Pattern.compile("<a\\s+(?:[^>]*?\\s+)?href=[\"']([^\"']*)[\"'][^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher matcher = pattern.matcher(html);
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) {
                String url = matcher.group(1);
                String text = matcher.group(2).replaceAll("\\<.*?\\>", "").trim();
                matcher.appendReplacement(sb, text + " (" + url + ")");
            }
            matcher.appendTail(sb);
            return sb.toString();
        } catch (Exception e) {
            return html; // Return original if regex fails
        }
    }
}