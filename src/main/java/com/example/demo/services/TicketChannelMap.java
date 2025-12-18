package com.example.demo.services;

import org.springframework.stereotype.Service;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

@Service
public class TicketChannelMap {

    private final Map<String, String> channelToTicket = new ConcurrentHashMap<>();
    private final Map<String, String> ticketToChannel = new ConcurrentHashMap<>();

    // History to stop Freshdesk Retry Spam
    private final Map<String, List<Integer>> ticketRecentHashes = new ConcurrentHashMap<>();

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

    public boolean isDuplicate(String ticketId, String messageContent) {
        if (messageContent == null) return false;
        int newHash = messageContent.trim().hashCode();
        List<Integer> hashes = ticketRecentHashes.computeIfAbsent(ticketId, k -> new CopyOnWriteArrayList<>());

        if (hashes.contains(newHash)) return true;

        hashes.add(newHash);
        if (hashes.size() > 20) hashes.remove(0);
        return false;
    }

    public void remove(String ticketId) {
        String channelId = ticketToChannel.remove(ticketId);
        if (channelId != null) channelToTicket.remove(channelId);
        ticketRecentHashes.remove(ticketId);
    }
}