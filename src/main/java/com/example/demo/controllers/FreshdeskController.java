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
    public ResponseEntity<String> onTicketCreate(
            @RequestBody Map<String, Object> body) {

        System.out.println("🔥 Freshdesk ticket creation webhook received");
        System.out.println(body);

        // ---- SAFETY CHECKS ----
        if (!body.containsKey("ticket_id") || !body.containsKey("region")) {
            System.out.println("❌ Missing required fields");
            return ResponseEntity.badRequest().body("Missing ticket_id or region");
        }

        String ticketId = body.get("ticket_id").toString();
        String region = body.get("region").toString().toLowerCase();

        // ---- SLACK SAFE CHANNEL NAME ----
        String channelName = "ticket-" + ticketId + "-" + region;
        channelName = channelName.replaceAll("[^a-z0-9-]", "");

        // ---- PREVENT DUPLICATE CHANNEL CREATION ----
        if (map.getChannelId(ticketId) != null) {
            System.out.println("⚠ Channel already exists for ticket " + ticketId);
            return ResponseEntity.ok("Channel already exists");
        }

        System.out.println("➡ Creating Slack channel: " + channelName);

        // ---- CREATE CHANNEL ----
        String channelId = slackService.createChannel(channelName);

        System.out.println("✅ Slack channel created: " + channelId);

        // ---- SAVE MAPPING ----
        map.put(ticketId, channelId);

        // ---- POST INITIAL MESSAGE ----
        slackService.sendMessage(
                channelId,
                "🎫 *New Freshdesk Ticket*\n" +
                        "*Ticket ID:* " + ticketId + "\n" +
                        "*Region:* " + region
        );

        return ResponseEntity.ok("Channel created");
    }

    @PostMapping("/ticket/updates")
    public ResponseEntity<?> onTicketUpdate(@RequestBody Map<String, Object> body) {

        String ticketId = body.get("ticket_id").toString();

        // Extract note safely
        String note = "";
        if (body.containsKey("latest_note")) {
            note = body.get("latest_note").toString();
        } else if (body.containsKey("note")) {
            note = body.get("note").toString();
        }

        String channelId = map.getChannelId(ticketId);
        if (channelId != null && !note.isEmpty()) {
            slackService.sendMessage(channelId, "📝 Freshdesk Update:\n" + note);
        } else {
            System.out.println("⚠ No channel mapping found for ticket " + ticketId + " or note is empty");
        }

        return ResponseEntity.ok("OK");
    }

}
