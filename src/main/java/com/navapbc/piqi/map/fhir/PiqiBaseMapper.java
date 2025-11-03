package com.navapbc.piqi.map.fhir;

import ca.uhn.fhir.context.FhirVersionEnum;

public class PiqiBaseMapper {

    public boolean isFhirVersion(FhirVersionEnum fhirVersion) {
        return false;
    }

    public boolean isMappingClassFor(Class<?> clazz) {
        return false;
    }

}
