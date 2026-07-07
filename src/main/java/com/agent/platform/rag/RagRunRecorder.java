package com.agent.platform.rag;

import java.util.List;

public interface RagRunRecorder {

    void record(RagResult result);

    List<RagRunRecord> recent(int limit);

    RagRunStats stats(int limit);

    void clear();
}
