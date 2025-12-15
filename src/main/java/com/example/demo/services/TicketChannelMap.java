package com.example.demo.services;

import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class TicketChannelMap {

    private final Map<String, String> channelToTicket = new ConcurrentHashMap<>();
    private final Map<String, String> ticketToChannel = new ConcurrentHashMap<>();

    // NEW: Store the "Hash" (fingerprint) of the last message to prevent duplicates
    private final Map<String, Integer> ticketLastMessageHash = new ConcurrentHashMap<>();

    // Save mapping
    public void put(String ticketId, String channelId) {
        ticketToChannel.put(ticketId, channelId);
        channelToTicket.put(channelId, ticketId);
    }

    public String getChannelId(String ticketId) {
        return ticketToChannel.get(ticketId);
    }

    public String getTicketId(String channelId) {
        return channelToTicket.get(channelId);
    }

    // NEW: Check if this message is a duplicate
    public boolean isDuplicate(String ticketId, String messageContent) {
        if (messageContent == null) return false;

        int newHash = messageContent.hashCode();
        Integer lastHash = ticketLastMessageHash.get(ticketId);

        if (lastHash != null && lastHash == newHash) {
            return true; // It is a duplicate!
        }

        // Not a duplicate, so save this new hash
        ticketLastMessageHash.put(ticketId, newHash);
        return false;
    }

    public void remove(String ticketId) {
        String channelId = ticketToChannel.remove(ticketId);
        if (channelId != null) {
            channelToTicket.remove(channelId);
        }
        ticketLastMessageHash.remove(ticketId);
    }
}