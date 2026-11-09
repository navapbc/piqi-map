package com.navapbc.piqi.map.fhir.clinical;

import ca.uhn.fhir.context.FhirVersionEnum;
import com.navapbc.piqi.map.fhir.PiqiBaseMapper;
import com.navapbc.piqi.model.clinical.PiqiDemographic;
import com.navapbc.piqi.model.clinical.PiqiLabResult;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Observation;

import java.util.List;
import java.util.Map;

public class PiqiBaseR4Mapper extends PiqiBaseMapper {

    @Override
    public boolean isFhirVersion(FhirVersionEnum fhirVersion) {
        return fhirVersion == FhirVersionEnum.R4;
    }

    public PiqiDemographic mapDemographics(Bundle bundle) {
        return null;
    }

    public List<PiqiLabResult> mapLabResults(DiagnosticReport diagnosticReport, Map<String, Observation> observations) {
        return null;
    }

    public PiqiLabResult mapLabResult( Observation observation, Map<String, DiagnosticReport> diagnosticReports) {
        return null;
    }

    public List<PiqiLabResult> mapLabResults(Bundle bundle) {
        return null;
    }
}
