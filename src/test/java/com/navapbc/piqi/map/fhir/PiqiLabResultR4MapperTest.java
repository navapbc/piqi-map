package com.navapbc.piqi.map.fhir;

import ca.uhn.fhir.context.FhirContext;
import ca.uhn.fhir.context.FhirVersionEnum;
import ca.uhn.fhir.parser.IParser;
import com.navapbc.piqi.model.PiqiDemographics;
import com.navapbc.piqi.model.PiqiLabResult;
import org.hl7.fhir.r4.model.Bundle;
import org.hl7.fhir.r4.model.DiagnosticReport;
import org.hl7.fhir.r4.model.Observation;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class PiqiLabResultR4MapperTest {

    private final PiqiLabResultsR4Mapper mapper = new PiqiLabResultsR4Mapper();

    @Test
    public void testFhirVersion() {
        assertTrue(mapper.isFhirVersion(FhirVersionEnum.R4));
        assertFalse(mapper.isFhirVersion(FhirVersionEnum.R5));
        assertFalse(mapper.isFhirVersion(null));
    }

    @Test
    public void testMappingClassFor() {
        assertFalse(mapper.isMappingClassFor(PiqiDemographics.class));
        assertTrue(mapper.isMappingClassFor(PiqiLabResult.class));
    }

    @Test
    public void testMapperUsingSyntheaBundle() {
        FhirContext ctx = FhirContext.forR4();
        IParser parser = ctx.newJsonParser();
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream("synthea.fhir.json");
        Bundle parsed = parser.parseResource(Bundle.class, inputStream);

        Map<String, Observation> observations = new HashMap<>();
        Map<String, DiagnosticReport> diagnosticReports = new HashMap<>();
        for (Bundle.BundleEntryComponent entry : parsed.getEntry()) {
            if (entry.getResource() instanceof Observation observation) {
                observations.put(observation.getIdElement().getIdPart(), observation);
            }
            if (entry.getResource() instanceof DiagnosticReport diagnosticReport) {
                diagnosticReports.put(diagnosticReport.getIdElement().getIdPart(), diagnosticReport);
            }
        }
        if (!diagnosticReports.isEmpty()) {
            assertEquals(24, diagnosticReports.size());
            PiqiLabResultsR4Mapper mapper = new PiqiLabResultsR4Mapper();
            assertNotNull(diagnosticReports.get("urn:uuid:01d6ee97-ee40-4430-68d1-c5c938abfdad"));
            List<PiqiLabResult> results =
                    mapper.mapLabResults(diagnosticReports.get("urn:uuid:01d6ee97-ee40-4430-68d1-c5c938abfdad"),
                            observations);
            assertEquals(11, results.size());
            // TODO add additional checks once mapping is completed.
        } else {
            fail("No DiagnosticReport FHIR resources were found.");
        }
    }

    @Test
    public void testMapperUsingSyntheaBundleAsBundle() {
        FhirContext ctx = FhirContext.forR4();
        IParser parser = ctx.newJsonParser();
        InputStream inputStream = getClass().getClassLoader().getResourceAsStream("synthea.fhir.json");
        Bundle parsed = parser.parseResource(Bundle.class, inputStream);
        List<PiqiLabResult> results = mapper.mapLabResults(parsed);
        assertEquals(37, results.size());
    }

}
