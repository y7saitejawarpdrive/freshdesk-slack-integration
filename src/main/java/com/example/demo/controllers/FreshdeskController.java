package com.example.demo.controllers;

import com.example.demo.services.SlackService;
import com.example.demo.services.TicketChannelMap;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/freshdesk")
@RequiredArgsConstructor
public class FreshdeskController {

    private final SlackService slackService;
    private final TicketChannelMap map;

    // --- CONFIG: Map Regions to Slack User Group IDs ---
    private static final Map<String, List<String>> USER_GROUPS_BY_REGION = Map.of(
            "bengaluru", List.of("S0A1L56DJ3B", "S0A1APMTHD2"),
            "hyderabad", List.of("S0A1B5UBYJ0", "S0A0S5E6MD5")
    );

    @PostMapping("/ticket/creation")
    public ResponseEntity<String> onTicketCreate(@RequestBody Map<String, Object> body) {
        System.out.println("🔥 Freshdesk ticket creation webhook received: " + body);

        if (!body.containsKey("ticket_id") || !body.containsKey("region")) {
            return ResponseEntity.badRequest().body("Missing ticket_id or region");
        }

        String ticketId = body.get("ticket_id").toString();
        String region = body.get("region").toString().toLowerCase();
        String subject = body.getOrDefault("subject", "No Subject").toString();
        String priority = body.getOrDefault("priority", "Low").toString();
        String descriptionHtml = body.getOrDefault("description", "").toString();

        // Strip HTML from description (simple regex)
        String description = descriptionHtml.replaceAll("\\<.*?\\>", "").trim();
        if (description.length() > 500) description = description.substring(0, 500) + "...";

        // Safe channel name logic
        String channelName = "ticket-" + ticketId + "-" + region;
        channelName = channelName.replaceAll("[^a-z0-9-]", "");

        // Prevent Duplicate Channel Creation
        if (map.getChannelId(ticketId) != null) {
            return ResponseEntity.ok("Channel already exists in map");
        }

        System.out.println("➡ Creating Slack channel: " + channelName);

        try {
            // 1. Create Channel
            String channelId = slackService.createChannel(channelName);
            System.out.println("✅ Slack channel created: " + channelId);
            map.put(ticketId, channelId);

            // 2. Determine User Groups for Mentions & Invites
            List<String> groupIds = USER_GROUPS_BY_REGION.getOrDefault(region, List.of());

            // Build Mentions String (e.g., <!subteam^S123> <!subteam^S456>)
            StringBuilder mentionsBuilder = new StringBuilder();
            for (String gid : groupIds) {
                mentionsBuilder.append("<!subteam^").append(gid).append("> ");
            }

            // 3. Construct the Rich Welcome Message
            String welcomeMsg = String.format(
                    ":ticket: *New Freshdesk Ticket Created*\n" +
                            "*ID:* %s\n" +
                            "*Subject:* %s\n" +
                            "*Priority:* %s\n" +
                            "*Region:* %s\n" +
                            "*Description:*\n%s\n\n" +
                            "Welcome %s to this ticket channel!",
                    ticketId, subject, priority, region, description, mentionsBuilder.toString()
            );

            // 4. Send Message
            slackService.sendMessage(channelId, welcomeMsg);

            // 5. Invite User Group Members to the Channel
            for (String groupId : groupIds) {
                System.out.println("🔍 Fetching members for group: " + groupId);
                List<String> memberIds = slackService.getUserGroupMembers(groupId);

                if (!memberIds.isEmpty()) {
                    System.out.println("➡ Inviting " + memberIds.size() + " users from group " + groupId);
                    slackService.inviteUsersToChannel(channelId, memberIds);
                }
            }

            return ResponseEntity.ok("Channel created and users invited");
        } catch (Exception e) {
            System.out.println("⚠ Error during ticket setup: " + e.getMessage());
            e.printStackTrace();
            return ResponseEntity.ok("Error: " + e.getMessage());
        }
    }

    @PostMapping("/ticket/updates")
    public ResponseEntity<?> onTicketUpdate(@RequestBody Map<String, Object> body) {
        // ... (Keep your existing Update logic exactly as it was in the previous step) ...
        // ... (Copy/Paste the duplicate check and echo check logic here) ...

        // Short version included here so you don't lose context:
        String ticketId = body.get("ticket_id").toString();
        String note = "";
        if (body.containsKey("latest_comment")) note = body.get("latest_comment").toString();
        else if (body.containsKey("latest_note")) note = body.get("latest_note").toString();
        else if (body.containsKey("body")) note = body.get("body").toString();

        if (note.contains("Slack:")) return ResponseEntity.ok("Ignored echo");
        if (map.isDuplicate(ticketId, note)) return ResponseEntity.ok("Ignored duplicate");

        String channelId = map.getChannelId(ticketId);
        if (channelId == null) return ResponseEntity.ok("Mapping missing");

        String cleanNote = note.replaceAll("\\<.*?\\>", "");
        if (!cleanNote.isEmpty()) slackService.sendMessage(channelId, "📝 Freshdesk Update:\n" + cleanNote);

        return ResponseEntity.ok("OK");
    }
}