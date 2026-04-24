package com.wordbookgen.core.provider;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * provider 轮询路由。
 */
public class ProviderRouter {

    private final List<ProviderClient> clients;
    private final AtomicInteger cursor = new AtomicInteger(0);

    public ProviderRouter(List<ProviderClient> clients) {
        if (clients == null || clients.isEmpty()) {
            throw new IllegalArgumentException("providers cannot be empty");
        }
        this.clients = List.copyOf(clients);
    }

    public List<ProviderClient> orderedClientsForNextBatch() {
        int size = clients.size();
        int start = Math.floorMod(cursor.getAndIncrement(), size);

        List<ProviderClient> ordered = new ArrayList<>(size);
        for (int i = 0; i < size; i++) {
            ordered.add(clients.get((start + i) % size));
        }
        return ordered;
    }
}
