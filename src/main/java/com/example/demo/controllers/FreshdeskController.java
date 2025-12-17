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
        String priority = body.getOrDefault("priority", "Low").toString();

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
        String note = "";

        // Priority to 'latest_comment' which should now be {{comment.body}}
        if (body.containsKey("latest_comment")) note = body.get("latest_comment").toString();
        else if (body.containsKey("latest_note")) note = body.get("latest_note").toString();
        else if (body.containsKey("body")) note = body.get("body").toString();

        // 1. Check for Echo
        if (note.contains("Slack:")) return ResponseEntity.ok("Ignored echo");

        // 2. Check for Duplicates (Repeating Bug)
        // We log the hash code to see why it's failing if it does
        if (map.isDuplicate(ticketId, note)) {
            System.out.println("🛑 Blocked duplicate message for Ticket " + ticketId + " (Hash: " + note.hashCode() + ")");
            return ResponseEntity.ok("Ignored duplicate");
        }

        String channelId = map.getChannelId(ticketId);
        if (channelId == null) return ResponseEntity.ok("Mapping missing");

        // 3. Extract Links
        String textWithLinks = convertHtmlLinks(note);
        String cleanNote = textWithLinks.replaceAll("\\<.*?\\>", "").trim();

        if (!cleanNote.isEmpty()) {
            slackService.sendMessage(channelId, "📝 Freshdesk Update:\n" + cleanNote);
            System.out.println("✅ Sent update for Ticket " + ticketId);
        }

        return ResponseEntity.ok("OK");
    }

    // UPDATED REGEX: Handles newlines and different quote types
    private String convertHtmlLinks(String html) {
        if (html == null) return "";
        // DOTALL mode allows . to match newlines
        Pattern pattern = Pattern.compile("<a[^>]+href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
        Matcher matcher = pattern.matcher(html);
        StringBuilder sb = new StringBuilder();
        while (matcher.find()) {
            String url = matcher.group(1);
            String text = matcher.group(2).trim();
            matcher.appendReplacement(sb, text + " (" + url + ")");
        }
        matcher.appendTail(sb);
        return sb.toString();
    }
}