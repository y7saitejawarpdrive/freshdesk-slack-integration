package com.example.demo.controllers;

import com.example.demo.services.FreshdeskService;
import com.example.demo.services.TicketChannelMap;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/slack")
public class SlackController {

    private final FreshdeskService freshdeskService;
    private final TicketChannelMap map;

    public SlackController(FreshdeskService freshdeskService,
                           TicketChannelMap map) {
        this.freshdeskService = freshdeskService;
        this.map = map;
    }

    @PostMapping("/events")
    public ResponseEntity<Map<String, String>> slackEvents(@RequestBody Map<String, Object> body) {

        if ("url_verification".equals(body.get("type"))) {
            return ResponseEntity.ok(Map.of("challenge", body.get("challenge").toString()));
        }

        if (!body.containsKey("event")) {
            return ResponseEntity.ok(Map.of("status", "ignored_no_event"));
        }

        Map<String, Object> event = (Map<String, Object>) body.get("event");

        if ("message".equals(event.get("type")) && !event.containsKey("bot_id")) {

            // --- FIX: IGNORE SYSTEM MESSAGES (Like joining channel) ---
            if (event.containsKey("subtype")) {
                System.out.println("⚠ Ignored subtype event: " + event.get("subtype"));
                return ResponseEntity.ok(Map.of("status", "ignored_subtype"));
            }

            Object channelObj = event.get("channel");
            Object textObj = event.get("text");

            if (channelObj != null && textObj != null) {
                String channelId = channelObj.toString();
                String text = textObj.toString();

                String ticketId = map.getTicketId(channelId);
                if (ticketId != null) {
                    freshdeskService.addNote(ticketId, "💬 Slack:\n" + text);
                    System.out.println("✅ Forwarded to Freshdesk Ticket " + ticketId);
                }
            }
        }

        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}




