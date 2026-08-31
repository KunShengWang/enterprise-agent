package com.agent.platform.procurement.provider;

import com.agent.platform.procurement.model.CatalogItem;
import com.agent.platform.procurement.model.ProcurementCaseState;
import com.agent.platform.procurement.model.SupplierCandidate;
import com.agent.platform.procurement.model.SupplierEvidence;
import com.agent.platform.procurement.model.SupplierOffer;

import java.util.List;

public interface ProcurementDataProvider {
    List<CatalogItem> searchCatalog(ProcurementCaseState state);
    List<SupplierCandidate> searchSuppliers(ProcurementCaseState state);
    List<SupplierOffer> getSupplierOffers(ProcurementCaseState state, List<SupplierCandidate> candidates);
    List<SupplierEvidence> getSupplierEvidence(String supplierId, ProcurementCaseState state);
}
