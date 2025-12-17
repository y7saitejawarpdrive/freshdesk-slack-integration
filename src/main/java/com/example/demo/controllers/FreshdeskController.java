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

        // --- 1. THE CATCH-ALL LOGIC ---
        String rawHtml = "";

        // First, try the HTML version (Best for links)
        if (body.containsKey("comment_body") && body.get("comment_body") != null) {
            rawHtml = body.get("comment_body").toString();
        }

        // If empty, fallback to the text version (Backup)
        if ((rawHtml.isEmpty() || rawHtml.equals("null")) && body.containsKey("public_comment")) {
            Object pc = body.get("public_comment");
            if (pc != null) rawHtml = pc.toString();
        }

        // Legacy fallback
        if ((rawHtml.isEmpty() || rawHtml.equals("null")) && body.containsKey("latest_comment")) {
            Object lc = body.get("latest_comment");
            if (lc != null) rawHtml = lc.toString();
        }

        System.out.println("📨 Update Ticket " + ticketId + " | Final Content: " + rawHtml);

        // --- 2. VALIDATION ---
        if (rawHtml == null || rawHtml.trim().isEmpty() || rawHtml.equals("null")) {
            // If it's STILL empty, it's just a status update (not a note)
            return ResponseEntity.ok("Ignored empty");
        }

        if (rawHtml.contains("Slack:")) return ResponseEntity.ok("Ignored echo");

        if (map.isDuplicate(ticketId, rawHtml.trim())) {
            System.out.println("🛑 Ignored duplicate");
            return ResponseEntity.ok("Duplicate");
        }

        String channelId = map.getChannelId(ticketId);
        if (channelId == null) {
            System.out.println("❌ No mapping for Ticket " + ticketId);
            return ResponseEntity.ok("No mapping");
        }

        // --- 3. CLEANING ---
        try {
            // Extract links first
            String withLinks = convertHtmlLinks(rawHtml);
            // Remove tags
            String cleanMsg = withLinks.replaceAll("\\<.*?\\>", "").trim();
            // Decode entities
            cleanMsg = cleanMsg.replace("&nbsp;", " ").replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">");

            if (!cleanMsg.isEmpty()) {
                slackService.sendMessage(channelId, "📝 Freshdesk Update:\n" + cleanMsg);
                System.out.println("✅ Sent to Slack!");
            }
        } catch (Exception e) {
            // Absolute fallback: Send raw text if processing fails
            slackService.sendMessage(channelId, "📝 Freshdesk Update:\n" + rawHtml);
        }

        return ResponseEntity.ok("OK");
    }

    private String convertHtmlLinks(String html) {
        if (html == null) return "";
        try {
            // Finds <a href="...">Text</a> -> Text (URL)
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