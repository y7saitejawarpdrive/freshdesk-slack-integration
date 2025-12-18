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

    // We removed the SUPPORT_AGENT_ID constant because the claiming agent will be dynamic now.

    @PostMapping("/ticket/creation")
    public ResponseEntity<String> onTicketCreate(@RequestBody Map<String, Object> body) {
        if (!body.containsKey("ticket_id")) return ResponseEntity.badRequest().body("Missing ticket_id");

        String ticketId = body.get("ticket_id").toString();
        String issueType = body.getOrDefault("issue_type", "General").toString();
        String priority = body.getOrDefault("priority", "Low").toString();

        String desc = body.getOrDefault("description", "").toString().replaceAll("\\<.*?\\>", "").trim();
        if (desc.length() > 200) desc = desc.substring(0, 200) + "...";

        // --- OLD LOGIC: Created Channel Immediately ---
        // --- NEW LOGIC: Post to Triage Channel Only ---

        System.out.println("🔥 Posting Triage Card for Ticket " + ticketId);
        slackService.sendTriageMessage(ticketId, issueType, priority, desc);

        return ResponseEntity.ok("Posted to Triage");
    }

    // ... (Keep onTicketUpdate EXACTLY AS IT WAS - NO CHANGES NEEDED) ...
    @PostMapping("/ticket/updates")
    public ResponseEntity<?> onTicketUpdate(@RequestBody Map<String, Object> body) {
        // ... paste your existing update logic ...
        return ResponseEntity.ok("OK");
    }

    // ... (Keep helper methods like convertHtmlLinks) ...
}