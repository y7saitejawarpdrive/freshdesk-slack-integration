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

    // --- CONFIG ---
    private static final Map<String, List<String>> USER_GROUPS_BY_REGION = Map.of(
            "bengaluru", List.of("S0A1L56DJ3B", "S0A1APMTHD2"),
            "hyderabad", List.of("S0A1B5UBYJ0", "S0A0S5E6MD5")
    );

    @PostMapping("/ticket/creation")
    public ResponseEntity<String> onTicketCreate(@RequestBody Map<String, Object> body) {
        if (!body.containsKey("ticket_id") || !body.containsKey("region")) {
            return ResponseEntity.badRequest().body("Missing ticket_id or region");
        }

        String ticketId = body.get("ticket_id").toString();
        String region = body.get("region").toString().toLowerCase();
        String subject = body.getOrDefault("subject", "No Subject").toString();
        String priority = body.getOrDefault("priority", "Low").toString();

        String descriptionHtml = body.getOrDefault("description", "").toString();
        String description = descriptionHtml.replaceAll("\\<.*?\\>", "").trim();
        if (description.length() > 500) description = description.substring(0, 500) + "...";

        String channelName = ("ticket-" + ticketId + "-" + region).replaceAll("[^a-z0-9-]", "");

        if (map.getChannelId(ticketId) != null) return ResponseEntity.ok("Exists");

        try {
            String channelId = slackService.createChannel(channelName);
            if(channelId == null) return ResponseEntity.ok("Failed to create channel");

            map.put(ticketId, channelId);

            List<String> groupIds = USER_GROUPS_BY_REGION.getOrDefault(region, List.of());
            StringBuilder mentions = new StringBuilder();
            for (String gid : groupIds) mentions.append("<!subteam^").append(gid).append("> ");

            String msg = String.format(":ticket: *New Ticket*\n*ID:* %s\n*Subject:* %s\n*Region:* %s\n*Desc:* %s\n\nWelcome %s",
                    ticketId, subject, region, description, mentions);

            slackService.sendMessage(channelId, msg);

            for (String gid : groupIds) {
                slackService.inviteUsersToChannel(channelId, slackService.getUserGroupMembers(gid));
            }
            return ResponseEntity.ok("Created");
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.ok("Error");
        }
    }

    @PostMapping("/ticket/updates")
    public ResponseEntity<?> onTicketUpdate(@RequestBody Map<String, Object> body) {
        String ticketId = body.get("ticket_id").toString();
        String note = "";
        if (body.containsKey("latest_comment")) note = body.get("latest_comment").toString();
        else if (body.containsKey("latest_note")) note = body.get("latest_note").toString();
        else if (body.containsKey("body")) note = body.get("body").toString();

        if (note.contains("Slack:")) return ResponseEntity.ok("Ignored echo");
        if (map.isDuplicate(ticketId, note)) return ResponseEntity.ok("Ignored duplicate");

        String channelId = map.getChannelId(ticketId);
        if (channelId == null) return ResponseEntity.ok("Mapping missing");

        // --- FIX: Extract Links before stripping HTML ---
        String textWithLinks = convertHtmlLinks(note);

        // Now strip remaining tags (like <div>, <p>)
        String cleanNote = textWithLinks.replaceAll("\\<.*?\\>", "").trim();

        if (!cleanNote.isEmpty()) {
            slackService.sendMessage(channelId, "📝 Freshdesk Update:\n" + cleanNote);
        }

        return ResponseEntity.ok("OK");
    }

    // Helper to turn <a href="http...">File.pdf</a> into File.pdf: http...
    private String convertHtmlLinks(String html) {
        if (html == null) return "";

        // Regex to find anchor tags with href
        Pattern pattern = Pattern.compile("<a\\s+(?:[^>]*?\\s+)?href=\"([^\"]*)\"[^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(html);

        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String url = matcher.group(1);
            String text = matcher.group(2);
            // Replace with: Text (URL)
            matcher.appendReplacement(sb, text + " (" + url + ")");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}