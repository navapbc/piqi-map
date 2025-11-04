package com.navapbc.piqi.map.fhir;

import ca.uhn.fhir.context.FhirVersionEnum;
import com.navapbc.piqi.model.PiqiDemographics;
import com.navapbc.piqi.model.PiqiLabResult;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Observation;
import org.hl7.fhir.r4.model.Patient;

import java.util.List;
import java.util.Map;

public class PiqiBaseR4Mapper extends PiqiBaseMapper {

    @Override
    public boolean isFhirVersion(FhirVersionEnum fhirVersion) {
        return fhirVersion == FhirVersionEnum.R4;
    }

    public PiqiDemographics mapDemographics(Bundle bundle) {
        return null;
    }

    public List<PiqiLabResult> mapLabResults(DiagnosticReport diagnosticReport, Map<String, Observation> observations) {
        return null;
    }

    public List<PiqiLabResult> mapLabResults(Bundle bundle) {
        return null;
    }
}
