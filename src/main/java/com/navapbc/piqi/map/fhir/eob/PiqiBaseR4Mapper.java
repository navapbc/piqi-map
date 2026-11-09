package com.navapbc.piqi.map.fhir.eob;

import ca.uhn.fhir.context.FhirVersionEnum;
import com.navapbc.piqi.map.fhir.PiqiBaseMapper;
import com.navapbc.piqi.model.eob.PiqiPatient;
import org.hl7.fhir.r4.model.*;

import java.util.List;
import java.util.function.Function;

public class PiqiBaseR4Mapper extends PiqiBaseMapper {

    @Override
    public boolean isFhirVersion(FhirVersionEnum fhirVersion) {
        return fhirVersion == FhirVersionEnum.R4;
    }

    public PiqiPatient mapPiqiPatientFromFhirClaim(Claim claim, Patient patient, List<Coverage> coverages,
                                               Function<Reference, Resource> resolve) {
        R4ClaimToPiqiPatientMapper mapper = new R4ClaimToPiqiPatientMapper();
        return mapper.map(claim, patient, coverages, resolve);
    }

    public PiqiPatient mapPiqiPatientFromFhirEob(ExplanationOfBenefit eob, Patient patient, List<Coverage> coverages,
                                                 Function<Reference, Resource> resolve) {
        R4EobToPiqiPatientMapper mapper = new R4EobToPiqiPatientMapper();
        return mapper.map(eob, patient, coverages, resolve);
    }
}

