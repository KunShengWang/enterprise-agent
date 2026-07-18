package com.agent.platform.ordercare.incident.persistence;

import com.agent.platform.ordercare.incident.model.EvidenceRecord;

import java.util.List;

public interface EvidenceStore {

    List<EvidenceRecord> listEvidence(String incidentId);
}
