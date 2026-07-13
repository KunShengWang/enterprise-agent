package com.agent.platform.approval;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ConcurrentMap;

@Deprecated(forRemoval = true)
public class InMemoryApprovalStore implements ApprovalStore {

    private static final int MAX_RECORDS = 500;

    private final ConcurrentMap<String, ApprovalRecord> records = new ConcurrentHashMap<>();

    private final ConcurrentLinkedDeque<String> recentIds = new ConcurrentLinkedDeque<>();

    @Override
    public void save(ApprovalRecord record) {
        if (record == null || record.approvalId() == null || record.approvalId().isBlank()) {
            return;
        }
        boolean existed = records.containsKey(record.approvalId());
        records.put(record.approvalId(), record);
        if (!existed) {
            recentIds.addFirst(record.approvalId());
        }
        while (recentIds.size() > MAX_RECORDS) {
            String removed = recentIds.pollLast();
            if (removed != null) {
                records.remove(removed);
            }
        }
    }

    @Override
    public Optional<ApprovalRecord> find(String approvalId) {
        if (approvalId == null || approvalId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(records.get(approvalId.trim()));
    }

    @Override
    public List<ApprovalRecord> recent(int limit) {
        List<ApprovalRecord> result = new ArrayList<>();
        for (String approvalId : recentIds) {
            ApprovalRecord record = records.get(approvalId);
            if (record != null) {
                result.add(record);
            }
            if (result.size() >= Math.max(1, limit)) {
                break;
            }
        }
        return result;
    }
}
