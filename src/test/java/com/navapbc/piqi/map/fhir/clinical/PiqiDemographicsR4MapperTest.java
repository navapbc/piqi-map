package com.navapbc.piqi.map.fhir.clinical;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.parser.IParser;
import com.navapbc.piqi.model.clinical.PiqiDemographic;
import com.navapbc.piqi.model.clinical.PiqiLabResult;
import org.hl7.fhir.r4.model.Bundle;
import org.junit.jupiter.api.Test;

import java.io.InputStream;

import static org.junit.jupiter.api.Assertions.*;

public class PiqiDemographicsR4MapperTest {

    private final PiqiDemographicsR4Mapper mapper = new PiqiDemographicsR4Mapper();

    @Test
    public void testFhirVersion() {
        assertTrue(mapper.isFhirVersion(FhirVersionEnum.R4));
        assertFalse(mapper.isFhirVersion(FhirVersionEnum.R5));
        assertFalse(mapper.isFhirVersion(null));
    }

    @Test
    public void testMappingClassFor() {
        assertTrue(mapper.isMappingClassFor(PiqiDemographic.class));
        assertFalse(mapper.isMappingClassFor(PiqiLabResult.class));
    }

    @Test
    public void testMappingUsingSyntheaBundle() {
        FhirContext ctx = FhirContext.forR4();
        IParser parser = ctx.newJsonParser();
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream("synthea.fhir.json");
        Bundle parsed = parser.parseResource(Bundle.class, inputStream);
        PiqiDemographic piqiDemographics = mapper.mapDemographics(parsed);

        assertNotNull(piqiDemographics);

        assertEquals("2018-12-01", piqiDemographics.getBirthDate().getValue());

        assertNull(piqiDemographics.getBirthSex().getText().getValue());
        assertEquals(1, piqiDemographics.getBirthSex().getCodings().size());
        assertEquals("M", piqiDemographics.getBirthSex().getCodings().getFirst().getCode().getValue());
        assertNull(piqiDemographics.getBirthSex().getCodings().getFirst().getDisplay().getValue());
        assertEquals("http://hl7.org/fhir/us/core/StructureDefinition/us-core-birthsex",
                piqiDemographics.getBirthSex().getCodings().getFirst().getSystem().getValue());

        assertNull(piqiDemographics.getDeathDate());

        assertEquals("No", piqiDemographics.getDeceased().getText().getValue());
        assertEquals(1, piqiDemographics.getDeceased().getCodings().size());
        assertEquals("N", piqiDemographics.getDeceased().getCodings().getFirst().getCode().getValue());
        assertEquals("No", piqiDemographics.getDeceased().getCodings().getFirst().getDisplay().getValue());
        assertEquals("http://terminology.hl7.org/CodeSystem/v2-0136",
                piqiDemographics.getDeceased().getCodings().getFirst().getSystem().getValue());

        assertEquals("Not Hispanic or Latino", piqiDemographics.getEthnicity().getText().getValue());
        assertEquals(1, piqiDemographics.getEthnicity().getCodings().size());
        assertEquals("2186-5", piqiDemographics.getEthnicity().getCodings().getFirst().getCode().getValue());
        assertEquals("Not Hispanic or Latino",
                piqiDemographics.getEthnicity().getCodings().getFirst().getDisplay().getValue());
        assertEquals("urn:oid:2.16.840.1.113883.6.238",
                piqiDemographics.getEthnicity().getCodings().getFirst().getSystem().getValue());

        assertEquals("Male.", piqiDemographics.getGenderIdentity().getText().getValue());
        assertEquals(1, piqiDemographics.getGenderIdentity().getCodings().size());
        assertEquals("male",
                piqiDemographics.getGenderIdentity().getCodings().getFirst().getCode().getValue());
        assertEquals("Male",
                piqiDemographics.getGenderIdentity().getCodings().getFirst().getDisplay().getValue());
        assertEquals("http://hl7.org/fhir/administrative-gender",
                piqiDemographics.getGenderIdentity().getCodings().getFirst().getSystem().getValue());

        assertEquals("English (United States)",
                piqiDemographics.getPrimaryLanguage().getText().getValue());
        assertEquals(1, piqiDemographics.getPrimaryLanguage().getCodings().size());
        assertEquals("en-US",
                piqiDemographics.getPrimaryLanguage().getCodings().getFirst().getCode().getValue());
        assertEquals("English (United States)",
                piqiDemographics.getPrimaryLanguage().getCodings().getFirst().getDisplay().getValue());
        assertEquals("urn:ietf:bcp:47",
                piqiDemographics.getPrimaryLanguage().getCodings().getFirst().getSystem().getValue());

        assertEquals("Never Married",
                piqiDemographics.getMaritalStatus().getText().getValue());
        assertEquals(1, piqiDemographics.getMaritalStatus().getCodings().size());
        assertEquals("S",
                piqiDemographics.getMaritalStatus().getCodings().getFirst().getCode().getValue());
        assertEquals("Never Married",
                piqiDemographics.getMaritalStatus().getCodings().getFirst().getDisplay().getValue());
        assertEquals("http://terminology.hl7.org/CodeSystem/v3-MaritalStatus",
                piqiDemographics.getMaritalStatus().getCodings().getFirst().getSystem().getValue());

        assertEquals("White",
                piqiDemographics.getRace().getText().getValue());
        assertEquals(1, piqiDemographics.getRace().getCodings().size());
        assertEquals("2106-3",
                piqiDemographics.getRace().getCodings().getFirst().getCode().getValue());
        assertEquals("White",
                piqiDemographics.getRace().getCodings().getFirst().getDisplay().getValue());
        assertEquals("urn:oid:2.16.840.1.113883.6.238",
                piqiDemographics.getRace().getCodings().getFirst().getSystem().getValue());
    }
}
