package com.agent.platform.tool;

import java.util.List;

public interface ToolRunRecorder {

    void record(ToolCallRecord record);

    List<ToolCallRecord> recent(int limit);

    ToolRunStats stats();
}
