package com.example.demo.controllers;

import com.example.demo.services.SlackService;
import com.example.demo.services.TicketChannelMap;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/freshdesk")
@RequiredArgsConstructor
public class FreshdeskController {

    private final SlackService slackService;
    private final TicketChannelMap map;

    @PostMapping("/ticket/creation")
    public ResponseEntity<String> onTicketCreate(@RequestBody Map<String, Object> body) {
        System.out.println("🔥 Freshdesk ticket creation webhook received");

        if (!body.containsKey("ticket_id") || !body.containsKey("region")) {
            return ResponseEntity.badRequest().body("Missing ticket_id or region");
        }

        String ticketId = body.get("ticket_id").toString();
        String region = body.get("region").toString().toLowerCase();

        // Safe channel name logic
        String channelName = "ticket-" + ticketId + "-" + region;
        channelName = channelName.replaceAll("[^a-z0-9-]", "");

        // Check if map already has it (prevents duplicates in memory)
        if (map.getChannelId(ticketId) != null) {
            return ResponseEntity.ok("Channel already exists in map");
        }

        System.out.println("➡ Creating Slack channel: " + channelName);

        try {
            // Create Channel
            String channelId = slackService.createChannel(channelName);
            System.out.println("✅ Slack channel created: " + channelId);

            // SAVE MAPPING (Critical)
            map.put(ticketId, channelId);

            slackService.sendMessage(channelId,
                    "🎫 *New Freshdesk Ticket*\n*Ticket ID:* " + ticketId + "\n*Region:* " + region);

            return ResponseEntity.ok("Channel created");
        } catch (Exception e) {
            // Handle case where channel exists in Slack but not in App Memory (Render Restart)
            System.out.println("⚠ Error creating channel (might already exist): " + e.getMessage());
            return ResponseEntity.ok("Channel creation skipped/failed");
        }
    }

    @PostMapping("/ticket/updates")
    public ResponseEntity<?> onTicketUpdate(@RequestBody Map<String, Object> body) {
        System.out.println("📨 Received Update Payload: " + body);

        String ticketId = body.get("ticket_id").toString();

        String note = "";
        if (body.containsKey("latest_comment")) {
            note = body.get("latest_comment").toString();
        } else if (body.containsKey("latest_note")) {
            note = body.get("latest_note").toString();
        } else if (body.containsKey("body")) {
            note = body.get("body").toString();
        }

        // --- FIX: STOP THE ECHO ---
        // If the note contains "Slack:", it means WE sent it. Don't send it back.
        if (note.contains("Slack:")) {
            System.out.println("🛑 Ignored update because it originated from Slack.");
            return ResponseEntity.ok("Ignored echo");
        }

        // --- OPTIONAL: Clean HTML tags ---
        // Freshdesk sends <div> tags. Let's strip them for cleaner Slack messages.
        String cleanNote = note.replaceAll("\\<.*?\\>", "");

        String channelId = map.getChannelId(ticketId);

        if (channelId == null) {
            System.out.println("❌ ERROR: No mapping found for Ticket ID " + ticketId);
            return ResponseEntity.ok("Mapping missing");
        }

        if (cleanNote.isEmpty() || cleanNote.equals("null")) {
            return ResponseEntity.ok("Note empty");
        }

        slackService.sendMessage(channelId, "📝 Freshdesk Update:\n" + cleanNote);
        System.out.println("✅ Sent update to Slack Channel: " + channelId);

        return ResponseEntity.ok("OK");
    }
}