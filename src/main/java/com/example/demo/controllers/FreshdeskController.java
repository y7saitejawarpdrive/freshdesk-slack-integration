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
        System.out.println("🔥 Creating Ticket: " + body);
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

        // 1. Get Content
        if (body.containsKey("latest_comment")) rawHtml = body.get("latest_comment").toString();
        else if (body.containsKey("latest_note")) rawHtml = body.get("latest_note").toString();
        else if (body.containsKey("body")) rawHtml = body.get("body").toString();

        // 2. DEBUG: Log the exact content from Freshdesk
        System.out.println("📨 Update Ticket " + ticketId + " | Content: " + rawHtml);

        // 3. Validation
        if (rawHtml == null || rawHtml.trim().isEmpty() || rawHtml.equals("null")) {
            System.out.println("⚠ Skipped: Empty content.");
            return ResponseEntity.ok("Ignored empty");
        }

        if (rawHtml.contains("Slack:")) return ResponseEntity.ok("Ignored echo");

        if (map.isDuplicate(ticketId, rawHtml.trim())) {
            System.out.println("🛑 Skipped: Duplicate");
            return ResponseEntity.ok("Duplicate");
        }

        String channelId = map.getChannelId(ticketId);
        if (channelId == null) {
            System.out.println("❌ ERROR: No channel mapping (App Restarted?)");
            return ResponseEntity.ok("No mapping");
        }

        // 4. Convert HTML Links and Clean Tags
        try {
            String withLinks = convertHtmlLinks(rawHtml);
            String cleanMsg = withLinks.replaceAll("\\<.*?\\>", "").trim();
            // Decode HTML entities
            cleanMsg = cleanMsg.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");

            if (!cleanMsg.isEmpty()) {
                slackService.sendMessage(channelId, "📝 Freshdesk Update:\n" + cleanMsg);
                System.out.println("✅ Sent to Slack!");
            } else {
                System.out.println("⚠ Msg empty after cleaning. Original was: " + rawHtml);
            }
        } catch (Exception e) {
            System.out.println("❌ Error processing text: " + e.getMessage());
        }

        return ResponseEntity.ok("OK");
    }

    private String convertHtmlLinks(String html) {
        if (html == null) return "";
        try {
            // Extracts <a href="http...">File Name</a> -> File Name (http...)
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