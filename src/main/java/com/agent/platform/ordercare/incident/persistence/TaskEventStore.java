package com.agent.platform.ordercare.incident.persistence;

import com.agent.platform.ordercare.incident.model.TaskEventRecord;

import java.util.List;

public interface TaskEventStore {

    TaskEventRecord appendEvent(TaskEventRecord event);

    List<TaskEventRecord> loadEventsAfter(String incidentId, long afterSequence, int limit);
}
