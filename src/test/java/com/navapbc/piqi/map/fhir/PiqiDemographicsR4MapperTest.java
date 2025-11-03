package com.navapbc.piqi.map.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.parser.IParser;
import com.navapbc.piqi.model.*;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.Patient;
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
        assertTrue(mapper.isMappingClassFor(PiqiDemographics.class));
        assertFalse(mapper.isMappingClassFor(PiqiLabResult.class));
    }

    @Test
    public void testMappingUsingSyntheaBundle() {
        FhirContext ctx = FhirContext.forR4();
        IParser parser = ctx.newJsonParser();
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream("synthea.fhir.json");
        Bundle parsed = parser.parseResource(Bundle.class, inputStream);
        Patient patient = null;
        for (Bundle.BundleEntryComponent entry : parsed.getEntry()) {
            if (entry.getResource() instanceof Patient) {
                patient = (Patient) entry.getResource();
                break;
            }
        }
        if (patient != null) {
            PiqiDemographics piqiDemographics = mapper.mapDemographics(patient);

            assertNotNull(piqiDemographics);

            assertEquals("2018-12-01", piqiDemographics.getBirthDate().getValue());

            assertNull(piqiDemographics.getBirthSex().getText().getValue());
            assertEquals(1, piqiDemographics.getBirthSex().getCodings().size());
            assertEquals("M", piqiDemographics.getBirthSex().getCodings().get(0).getCode().getValue());
            assertNull(piqiDemographics.getBirthSex().getCodings().get(0).getDisplay().getValue());
            assertEquals("http://hl7.org/fhir/us/core/StructureDefinition/us-core-birthsex",
                    piqiDemographics.getBirthSex().getCodings().get(0).getSystem().getValue());

            assertNull(piqiDemographics.getDeathDate());

            assertEquals("No", piqiDemographics.getDeceased().getText().getValue());
            assertEquals(1, piqiDemographics.getDeceased().getCodings().size());
            assertEquals("N", piqiDemographics.getDeceased().getCodings().get(0).getCode().getValue());
            assertEquals("No", piqiDemographics.getDeceased().getCodings().get(0).getDisplay().getValue());
            assertEquals("http://terminology.hl7.org/CodeSystem/v2-0136",
                    piqiDemographics.getDeceased().getCodings().get(0).getSystem().getValue());

            assertEquals("Not Hispanic or Latino", piqiDemographics.getEthnicity().getText().getValue());
            assertEquals(1, piqiDemographics.getEthnicity().getCodings().size());
            assertEquals("2186-5", piqiDemographics.getEthnicity().getCodings().get(0).getCode().getValue());
            assertEquals("Not Hispanic or Latino",
                    piqiDemographics.getEthnicity().getCodings().get(0).getDisplay().getValue());
            assertEquals("urn:oid:2.16.840.1.113883.6.238",
                    piqiDemographics.getEthnicity().getCodings().get(0).getSystem().getValue());

            assertEquals("Male.", piqiDemographics.getGenderIdentity().getText().getValue());
            assertEquals(1, piqiDemographics.getGenderIdentity().getCodings().size());
            assertEquals("male",
                    piqiDemographics.getGenderIdentity().getCodings().get(0).getCode().getValue());
            assertEquals("Male",
                    piqiDemographics.getGenderIdentity().getCodings().get(0).getDisplay().getValue());
            assertEquals("http://hl7.org/fhir/administrative-gender",
                    piqiDemographics.getGenderIdentity().getCodings().get(0).getSystem().getValue());

            assertEquals("English (United States)",
                    piqiDemographics.getPrimaryLanguage().getText().getValue());
            assertEquals(1, piqiDemographics.getPrimaryLanguage().getCodings().size());
            assertEquals("en-US",
                    piqiDemographics.getPrimaryLanguage().getCodings().get(0).getCode().getValue());
            assertEquals("English (United States)",
                    piqiDemographics.getPrimaryLanguage().getCodings().get(0).getDisplay().getValue());
            assertEquals("urn:ietf:bcp:47",
                    piqiDemographics.getPrimaryLanguage().getCodings().get(0).getSystem().getValue());

            assertEquals("Never Married",
                    piqiDemographics.getMaritalStatus().getText().getValue());
            assertEquals(1, piqiDemographics.getMaritalStatus().getCodings().size());
            assertEquals("S",
                    piqiDemographics.getMaritalStatus().getCodings().get(0).getCode().getValue());
            assertEquals("Never Married",
                    piqiDemographics.getMaritalStatus().getCodings().get(0).getDisplay().getValue());
            assertEquals("http://terminology.hl7.org/CodeSystem/v3-MaritalStatus",
                    piqiDemographics.getMaritalStatus().getCodings().get(0).getSystem().getValue());

            assertEquals("White",
                    piqiDemographics.getRace().getText().getValue());
            assertEquals(1, piqiDemographics.getRace().getCodings().size());
            assertEquals("2106-3",
                    piqiDemographics.getRace().getCodings().get(0).getCode().getValue());
            assertEquals("White",
                    piqiDemographics.getRace().getCodings().get(0).getDisplay().getValue());
            assertEquals("urn:oid:2.16.840.1.113883.6.238",
                    piqiDemographics.getRace().getCodings().get(0).getSystem().getValue());
        } else {
            fail("No Patient FHIR Resource was found.");
        }
    }
}
