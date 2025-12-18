package com.example.demo.controllers;

import com.example.demo.services.SlackService;
import com.example.demo.services.TicketChannelMap;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@RestController
@RequestMapping("/freshdesk")
@RequiredArgsConstructor
public class FreshdeskController {

    private final SlackService slackService;
    private final TicketChannelMap map;

    @PostMapping("/ticket/creation")
    public ResponseEntity<String> onTicketCreate(@RequestBody Map<String, Object> body) {
        if (!body.containsKey("ticket_id")) return ResponseEntity.badRequest().body("Missing ticket_id");

        String ticketId = body.get("ticket_id").toString();
        String issueType = body.getOrDefault("issue_type", "General").toString();
        String priority = body.getOrDefault("priority", "Low").toString();
        String desc = body.getOrDefault("description", "").toString().replaceAll("\\<.*?\\>", "").trim();
        if (desc.length() > 200) desc = desc.substring(0, 200) + "...";

        // POST TO TRIAGE ONLY
        slackService.sendTriageMessage(ticketId, issueType, priority, desc);
        return ResponseEntity.ok("Posted to Triage");
    }

    @PostMapping("/ticket/updates")
    public ResponseEntity<?> onTicketUpdate(@RequestBody Map<String, Object> body) {
        String ticketId = body.get("ticket_id").toString();
        Object senderObj = body.get("sender_name");
        String sender = (senderObj != null && !senderObj.toString().isEmpty()) ? senderObj.toString() : "Freshdesk Agent";

        String rawHtml = "";
        if (body.containsKey("comment_body") && body.get("comment_body") != null) rawHtml = body.get("comment_body").toString();
        if ((rawHtml.isEmpty() || rawHtml.equals("null")) && body.containsKey("public_comment")) {
            Object pc = body.get("public_comment");
            if (pc != null) rawHtml = pc.toString();
        }

        if (rawHtml == null || rawHtml.trim().isEmpty() || rawHtml.equals("null")) return ResponseEntity.ok("Ignored empty");

        String withLinks = convertHtmlLinks(rawHtml);
        String cleanMsg = withLinks.replaceAll("\\<.*?\\>", "").trim();
        cleanMsg = cleanMsg.replace("&#128172;", "").replace("&#128206;", "").replace("&nbsp;", " ")
                .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"");

        // FILTERS
        if (cleanMsg.contains("(Slack):") || cleanMsg.contains("Slack:")) return ResponseEntity.ok("Ignored echo");
        if (cleanMsg.contains("📎") || cleanMsg.contains("shared file:")) return ResponseEntity.ok("Ignored file echo");
        if (cleanMsg.contains("✅") || cleanMsg.contains("❌") || cleanMsg.contains("Approved by") || cleanMsg.contains("Rejected by")) return ResponseEntity.ok("Ignored system");
        if (map.isDuplicate(ticketId, cleanMsg.trim())) return ResponseEntity.ok("Duplicate");

        String channelId = map.getChannelId(ticketId);
        if (channelId != null && !cleanMsg.isEmpty()) {
            slackService.sendMessage(channelId, "👤 *" + sender + "*: " + cleanMsg);
        }
        return ResponseEntity.ok("OK");
    }

    private String convertHtmlLinks(String html) {
        if (html == null) return "";
        try {
            Pattern pattern = Pattern.compile("<a\\s+(?:[^>]*?\\s+)?href=[\"']([^\"']+)[\"'][^>]*>(.*?)</a>", Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
            Matcher matcher = pattern.matcher(html);
            StringBuilder sb = new StringBuilder();
            while (matcher.find()) matcher.appendReplacement(sb, matcher.group(2).replaceAll("\\<.*?\\>", "").trim() + " (" + matcher.group(1) + ")");
            matcher.appendTail(sb);
            return sb.toString();
        } catch (Exception e) { return html; }
    }
}