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
    public ResponseEntity<Map<String, String>> slackEvents(
            @RequestBody Map<String, Object> body) {

        // URL verification
        if ("url_verification".equals(body.get("type"))) {
            return ResponseEntity.ok(
                    Map.of("challenge", body.get("challenge").toString())
            );
        }

        Map<String, Object> event =
                (Map<String, Object>) body.get("event");

        // Ignore bot messages
        if ("message".equals(event.get("type"))
                && !event.containsKey("bot_id")) {

            String channelId = event.get("channel").toString();
            String text = event.get("text").toString();

            String ticketId = map.getTicketId(channelId);
            if (ticketId != null) {
                freshdeskService.addNote(
                        ticketId,
                        "💬 Slack:\n" + text
                );
            }
        }

        return ResponseEntity.ok(Map.of("status", "ok"));
    }
}




