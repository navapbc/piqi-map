package com.navapbc.piqi.map.fhir.eob;

import ca.uhn.fhir.context.FhirVersionEnum;
import com.navapbc.piqi.model.eob.*;
import org.hl7.fhir.r4.model.*;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

public class R4ClaimToPiqiPatientMapperTest {

    private static final String CLAIM_TYPE = "http://terminology.hl7.org/CodeSystem/claim-type";
    private static final String C4BB_INFO = "http://hl7.org/fhir/us/carin-bb/CodeSystem/C4BBSupportingInfoType";
    private static final String NPI = "http://hl7.org/fhir/sid/us-npi";
    private static final String US_CORE_BIRTHSEX =
            "http://hl7.org/fhir/us/core/StructureDefinition/us-core-birthsex";

    R4ClaimToPiqiPatientMapper mapper = new R4ClaimToPiqiPatientMapper();

    @Test
    public void testFhirVersion() {
        assertTrue(mapper.isFhirVersion(FhirVersionEnum.R4));
        assertFalse(mapper.isFhirVersion(FhirVersionEnum.R5));
        assertFalse(mapper.isFhirVersion(null));
    }

    // ------------------------------------------------------------------
    // Fixtures and assertion helpers
    // ------------------------------------------------------------------

    private static Patient patient() {
        Patient p = new Patient();
        p.setId("Patient/p1");
        return p;
    }

    private static Claim claim(String type) {
        Claim c = new Claim();
        if (type != null) {
            c.getType().addCoding().setSystem(CLAIM_TYPE).setCode(type);
        }
        return c;
    }

    private static CodeableConcept concept(String system, String code) {
        return new CodeableConcept().addCoding(new Coding(system, code, null));
    }

    private static Money money(String amount) {
        return new Money().setValue(new BigDecimal(amount));
    }

    private static Claim.SupportingInformationComponent info(Claim c, int seq, String category) {
        Claim.SupportingInformationComponent s = c.addSupportingInfo();
        s.setSequence(seq);
        s.setCategory(concept(C4BB_INFO, category));
        return s;
    }

    private static Claim.ItemComponent item(Claim c, int seq) {
        Claim.ItemComponent i = c.addItem();
        i.setSequence(seq);
        return i;
    }

    private static String val(PiqiSimpleAttribute a) {
        return a == null ? null : a.getValue();
    }

    /** Code of the first coding, or null. */
    private static String code(PiqiCodeableConcept c) {
        return (c == null || c.getCodings().isEmpty()) ? null : val(c.getCodings().getFirst().getCode());
    }

    private static String system(PiqiCodeableConcept c) {
        return (c == null || c.getCodings().isEmpty()) ? null : val(c.getCodings().getFirst().getSystem());
    }

    private PiqiPatient map(Claim c) {
        return mapper.map(c, patient(), List.of());
    }

    private PiqiMedicalClaim medical(Claim c) {
        PiqiPatient out = map(c);
        assertEquals(1, out.getMedicalClaim().size());
        return out.getMedicalClaim().getFirst();
    }

    // ------------------------------------------------------------------
    // Routing by claim type
    // ------------------------------------------------------------------

    @Nested
    class Routing {

        @ParameterizedTest
        @CsvSource({"professional", "institutional", "vision"})
        void medicalTypesProduceOneMedicalClaim(String type) {
            PiqiPatient out = map(claim(type));
            assertEquals(1, out.getMedicalClaim().size());
            assertTrue(out.getPharmacyClaim().isEmpty());
            assertTrue(out.getDentalClaim().isEmpty());
        }

        @Test
        void missingTypeDefaultsToMedical() {
            PiqiPatient out = map(claim(null));
            assertEquals(1, out.getMedicalClaim().size());
            assertTrue(out.getPharmacyClaim().isEmpty());
            assertTrue(out.getDentalClaim().isEmpty());
        }

        @Test
        void pharmacyProducesPharmacyClaimOnly() {
            PiqiPatient out = map(claim("pharmacy"));
            assertTrue(out.getMedicalClaim().isEmpty());
            assertEquals(1, out.getPharmacyClaim().size());
            assertTrue(out.getDentalClaim().isEmpty());
        }

        @Test
        void oralProducesOneDentalClaimPerItem() {
            Claim c = claim("oral");
            item(c, 1);
            item(c, 2);
            PiqiPatient out = map(c);
            assertTrue(out.getMedicalClaim().isEmpty());
            assertTrue(out.getPharmacyClaim().isEmpty());
            assertEquals(2, out.getDentalClaim().size());
            assertEquals(2, out.getClaimServiceLine().size());
        }

        @Test
        void emptyClaimDoesNotThrow() {
            assertDoesNotThrow(() -> mapper.map(new Claim(), patient(), List.of()));
        }

        @Test
        void resolverReturningNullIsTolerated() {
            Claim c = claim("professional");
            c.setProvider(new Reference("Organization/o1"));
            assertDoesNotThrow(() -> mapper.map(c, patient(), List.of(), ref -> null));
        }
    }

    // ------------------------------------------------------------------
    // Member
    // ------------------------------------------------------------------

    @Nested
    class MemberMapping {

        @Test
        void mapsIdentityAddressAndTelecom() {
            Patient p = patient();
            p.addIdentifier().setValue("M123").getType().addCoding().setCode("MB");
            p.setBirthDateElement(new DateType("1980-02-03"));
            p.addAddress().addLine("1 Main St").setCity("Chicago").setDistrict("Cook")
                    .setState("IL").setPostalCode("60601").setCountry("US");
            p.addTelecom().setSystem(ContactPoint.ContactPointSystem.PHONE)
                    .setUse(ContactPoint.ContactPointUse.HOME);

            var m = mapper.map(claim("professional"), p, List.of()).getMember();

            assertEquals("M123", val(m.getMemberId()));
            assertEquals("MB", code(m.getMemberIdentifierType()));
            assertEquals("p1", val(m.getUniqueMemberId()));
            assertEquals("1980-02-03", val(m.getDateOfBirth()));
            assertEquals("1 Main St", val(m.getStreetAddress()));
            assertEquals("Chicago", val(m.getCity()));
            assertEquals("Cook", val(m.getCounty()));
            assertEquals("IL", val(m.getState()));
            assertEquals("60601", val(m.getPostalCode()));
            assertEquals("US", val(m.getCountry()));
            assertEquals("phone", code(m.getTelecomSystem()));
            assertEquals("home", code(m.getTelecomUse()));
        }

        @Test
        void memberIdIsOnlyTakenFromMbIdentifier() {
            Patient p = patient();
            p.addIdentifier().setValue("SSN-LIKE").getType().addCoding().setCode("SS");
            var m = mapper.map(claim("professional"), p, List.of()).getMember();
            assertNull(m.getMemberId());
            assertEquals("p1", val(m.getUniqueMemberId()));
        }

        @Test
        void birthSexComesFromUsCoreExtensionNotGender() {
            Patient p = patient();
            p.setGender(Enumerations.AdministrativeGender.MALE);
            assertNull(mapper.map(claim("professional"), p, List.of()).getMember().getBirthSex());

            p.addExtension().setUrl(US_CORE_BIRTHSEX).setValue(new CodeType("F"));
            assertEquals("F", code(mapper.map(claim("professional"), p, List.of()).getMember().getBirthSex()));
        }

        @Test
        void deceasedDateSetsFlagAndDate() {
            Patient p = patient();
            p.setDeceased(new DateTimeType("2025-12-01"));
            var m = mapper.map(claim("professional"), p, List.of()).getMember();
            assertEquals("2025-12-01", val(m.getDateOfDeath()));
            assertEquals("true", val(m.getDeceasedFlag()));
        }

        @Test
        void deceasedBooleanFalseSetsOnlyFlag() {
            Patient p = patient();
            p.setDeceased(new BooleanType(false));
            var m = mapper.map(claim("professional"), p, List.of()).getMember();
            assertNull(m.getDateOfDeath());
            assertEquals("false", val(m.getDeceasedFlag()));
        }

        @Test
        void primaryLanguagePrefersPreferredCommunication() {
            Patient p = patient();
            p.addCommunication().setLanguage(concept("urn:ietf:bcp:47", "es")).setPreferred(false);
            p.addCommunication().setLanguage(concept("urn:ietf:bcp:47", "en")).setPreferred(true);
            assertEquals("en",
                    code(mapper.map(claim("professional"), p, List.of()).getMember().getPrimaryLanguage()));
        }

        @Test
        void primaryLanguageFallsBackToFirstCommunication() {
            Patient p = patient();
            p.addCommunication().setLanguage(concept("urn:ietf:bcp:47", "es"));
            assertEquals("es",
                    code(mapper.map(claim("professional"), p, List.of()).getMember().getPrimaryLanguage()));
        }
    }

    // ------------------------------------------------------------------
    // Coverage
    // ------------------------------------------------------------------

    @Nested
    class CoverageMapping {

        private Coverage coverage() {
            Coverage cv = new Coverage();
            cv.setStatus(Coverage.CoverageStatus.ACTIVE);
            cv.setSubscriberId("S1");
            cv.setPeriod(new Period().setStartElement(new DateTimeType("2026-01-01"))
                    .setEndElement(new DateTimeType("2026-12-31")));
            cv.addClass_().setValue("G1").setName("Group One").getType().addCoding().setCode("group");
            cv.addClass_().setValue("P1").setName("Gold").getType().addCoding().setCode("plan");
            cv.setRelationship(concept("http://terminology.hl7.org/CodeSystem/subscriber-relationship", "self"));
            cv.addPayor().setIdentifier(new Identifier().setValue("PAYER1")).setDisplay("Acme Health");
            return cv;
        }

        @Test
        void mapsSuppliedCoverage() {
            PiqiPatient out = mapper.map(claim("professional"), patient(), List.of(coverage()));
            assertEquals(1, out.getCoverage().size());
            var c = out.getCoverage().getFirst();
            assertEquals("S1", val(c.getSubscriberId()));
            assertEquals("active", code(c.getCoverageStatus()));
            assertEquals("2026-01-01", val(c.getStartDate()));
            assertEquals("2026-12-31", val(c.getEndDate()));
            assertEquals("G1", val(c.getGroupId()));
            assertEquals("Group One", val(c.getGroupName()));
            assertEquals("P1", val(c.getPlanIdentifier()));
            assertEquals("Gold", val(c.getPlanName()));
            assertEquals("self", code(c.getRelationshipToSubscriber()));
            assertEquals("PAYER1", val(c.getPayerIdentifier()));
            assertEquals("Acme Health", val(c.getClaimPayerName()));
        }

        @Test
        void payerIdentifierFallsBackToReferenceId() {
            Coverage cv = new Coverage();
            cv.addPayor().setReference("Organization/org9");
            PiqiPatient out = mapper.map(claim("professional"), patient(), List.of(cv));
            assertEquals("org9", val(out.getCoverage().getFirst().getPayerIdentifier()));
        }

        @Test
        void oneCoverageRowPerSuppliedCoverage() {
            PiqiPatient out = mapper.map(claim("professional"), patient(), List.of(coverage(), coverage()));
            assertEquals(2, out.getCoverage().size());
        }

        @Test
        void claimInsuranceReferencesAreNotAutoResolved() {
            Claim c = claim("professional");
            c.addInsurance().setCoverage(new Reference("Coverage/c1"));
            PiqiPatient out = mapper.map(c, patient(), List.of(), ref -> coverage());
            assertTrue(out.getCoverage().isEmpty());
        }
    }

    // ------------------------------------------------------------------
    // Medical claim header
    // ------------------------------------------------------------------

    @Nested
    class MedicalHeader {

        @Test
        void mapsTypeSubTypeUseAndDates() {
            Claim c = claim("institutional");
            c.setSubType(concept("subtype", "inpatient"));
            c.setUse(Claim.Use.CLAIM);
            c.getBillablePeriod().setStartElement(new DateTimeType("2026-01-01"))
                    .setEndElement(new DateTimeType("2026-01-05"));

            PiqiMedicalClaim m = medical(c);

            assertEquals("institutional", code(m.getClaimTypeCode()));
            assertEquals(CLAIM_TYPE, system(m.getClaimTypeCode()));
            assertEquals("inpatient", code(m.getClaimSubType()));
            assertEquals("claim", code(m.getClaimUse()));
            assertEquals("http://hl7.org/fhir/claim-use", system(m.getClaimUse()));
            assertEquals("2026-01-01", val(m.getClaimStartDate()));
            assertEquals("2026-01-05", val(m.getClaimServiceEndDate()));
            assertEquals("2026-01-01", val(m.getStatementFromDate()));
            assertEquals("2026-01-05", val(m.getStatementThruDate()));
        }

        @Test
        void mapsOtherUseValues() {
            Claim c = claim("professional");
            c.setUse(Claim.Use.PREAUTHORIZATION);
            assertEquals("preauthorization", code(medical(c).getClaimUse()));
        }

        @Test
        void mapsTotalToSubmittedAmount() {
            Claim c = claim("professional");
            c.setTotal(money("125.50"));
            assertEquals("125.50", val(medical(c).getClaimTotalSubmittedAmount()));
        }

        @Test
        void missingTotalLeavesSubmittedAmountNull() {
            assertNull(medical(claim("professional")).getClaimTotalSubmittedAmount());
        }

        @Test
        void mapsPayee() {
            Claim c = claim("professional");
            c.getPayee().setType(concept("http://terminology.hl7.org/CodeSystem/payeetype", "provider"));
            c.getPayee().setParty(new Reference("Organization/o1"));
            PiqiMedicalClaim m = medical(c);
            assertEquals("provider", code(m.getClaimPayeeTypeCode()));
            assertEquals("Organization/o1", val(m.getClaimPayee()));
        }

        @Test
        void noPayeeLeavesPayeeFieldsNull() {
            PiqiMedicalClaim m = medical(claim("professional"));
            assertNull(m.getClaimPayeeTypeCode());
            assertNull(m.getClaimPayee());
        }

        @Test
        void emptyBillablePeriodLeavesDatesNull() {
            PiqiMedicalClaim m = medical(claim("professional"));
            assertNull(m.getClaimStartDate());
            assertNull(m.getClaimServiceEndDate());
            assertNull(m.getStatementFromDate());
            assertNull(m.getStatementThruDate());
        }

        @Test
        void leavesAdjudicationOnlyFieldsUnset() {
            Claim c = claim("professional");
            c.setTotal(money("125.50"));
            PiqiMedicalClaim m = medical(c);

            assertNull(m.getAdjudicationDate());
            assertNull(m.getClaimPaidDate());
            assertNull(m.getClaimPaymentAmount());
            assertNull(m.getClaimTotalAllowedAmount());
            assertNull(m.getClaimAmountPaidToProvider());
            assertNull(m.getAmountPaidByPatient());
            assertNull(m.getCopayAmount());
            assertNull(m.getMemberLiability());
            assertNull(m.getPaymentDenialCode());
            assertNull(m.getClaimPaymentStatusCode());
            assertNull(m.getPayerClaimUniqueIdentifier());
            assertNull(m.getClaimReceivedDate());
        }
    }

    // ------------------------------------------------------------------
    // Pharmacy
    // ------------------------------------------------------------------

    @Nested
    class PharmacyMapping {

        private Claim pharmacyClaim() {
            Claim c = claim("pharmacy");
            info(c, 1, "dayssupply").setValue(new Quantity(30));
            info(c, 2, "refillnum").setValue(new Quantity(2));
            info(c, 3, "dawcode").setCode(concept("ncpdp", "0"));
            info(c, 4, "rxorigincode").setCode(concept("ncpdp", "3"));
            info(c, 5, "brandgenericindicator").setCode(concept("ncpdp", "G"));
            return c;
        }

        @Test
        void mapsPharmacyAttributesFromSupportingInfo() {
            Claim c = pharmacyClaim();
            c.setTotal(money("50.00"));

            PiqiPharmacyClaim x = map(c).getPharmacyClaim().getFirst();

            assertEquals("30", val(x.getDaysSupply()));
            assertEquals("2", val(x.getRefillNumber()));
            assertEquals("0", val(x.getDawCode()));
            assertEquals("3", val(x.getPrescriptionOriginCode()));
            assertEquals("G", code(x.getPlanReportedBrandGenericCode()));
            assertEquals("50.00", val(x.getTotalAmount()));
            assertEquals("submitted", val(x.getAdjudicationAmountType()));
        }

        @Test
        void amountTypeIsSubmittedEvenWithoutATotal() {
            PiqiPharmacyClaim x = map(pharmacyClaim()).getPharmacyClaim().getFirst();
            assertNull(x.getTotalAmount());
            assertEquals("submitted", val(x.getAdjudicationAmountType()));
        }

        @Test
        void missingSupportingInfoLeavesAttributesNull() {
            PiqiPharmacyClaim x = map(claim("pharmacy")).getPharmacyClaim().getFirst();
            assertNull(x.getDaysSupply());
            assertNull(x.getRefillNumber());
            assertNull(x.getDawCode());
            assertNull(x.getPrescriptionOriginCode());
            assertNull(x.getPlanReportedBrandGenericCode());
        }

        @Test
        void supportingInfoCategoryMatchIsCaseInsensitive() {
            Claim c = claim("pharmacy");
            info(c, 1, "DaysSupply").setValue(new Quantity(7));
            assertEquals("7", val(map(c).getPharmacyClaim().getFirst().getDaysSupply()));
        }

        @Test
        void daysSupplyAcceptsStringValue() {
            Claim c = claim("pharmacy");
            info(c, 1, "dayssupply").setValue(new StringType("90"));
            assertEquals("90", val(map(c).getPharmacyClaim().getFirst().getDaysSupply()));
        }

        @Test
        void pharmacyLineUsesNdcAndQuantityNotProcedureCode() {
            Claim c = pharmacyClaim();
            Claim.ItemComponent i = item(c, 1);
            i.getProductOrService().addCoding().setSystem("http://hl7.org/fhir/sid/ndc").setCode("00093-1234-01");
            i.getQuantity().setValue(30).setCode("tablet");

            PiqiClaimLine l = map(c).getClaimServiceLine().getFirst();

            assertEquals("00093-1234-01", code(l.getNationalDrugCode()));
            assertEquals("30", val(l.getQuantityDispensed()));
            assertEquals("tablet", code(l.getQuantityQualifierCode()));
            assertEquals("http://unitsofmeasure.org", system(l.getQuantityQualifierCode()));
            assertNull(l.getLineProcedureCode());
        }

        @Test
        void pharmacyLineWithoutUnitOmitsQualifier() {
            Claim c = pharmacyClaim();
            item(c, 1).getQuantity().setValue(30);
            PiqiClaimLine l = map(c).getClaimServiceLine().getFirst();
            assertEquals("30", val(l.getQuantityDispensed()));
            assertNull(l.getQuantityQualifierCode());
        }

        @Test
        void medicalLineUsesProcedureCodeNotNdc() {
            Claim c = claim("professional");
            item(c, 1).getProductOrService().addCoding().setCode("99213");

            PiqiClaimLine l = map(c).getClaimServiceLine().getFirst();

            assertEquals("99213", code(l.getLineProcedureCode()));
            assertNull(l.getNationalDrugCode());
            assertNull(l.getQuantityDispensed());
        }
    }

    // ------------------------------------------------------------------
    // Dental
    // ------------------------------------------------------------------

    @Nested
    class DentalMapping {

        @Test
        void mapsProcedureToothAndSurfaceFromItem() {
            Claim c = claim("oral");
            Claim.ItemComponent i = item(c, 1);
            i.getProductOrService().addCoding().setCode("D2391");
            i.addModifier().addCoding().setCode("LT");
            i.getBodySite().addCoding().setCode("8");
            i.addSubSite().addCoding().setCode("M");
            i.addSubSite().addCoding().setCode("O");

            PiqiDentalClaim d = map(c).getDentalClaim().getFirst();

            assertEquals("D2391", code(d.getProcedureCode()));
            assertEquals("LT", code(d.getProcedureModifierCode()));
            assertEquals("8", code(d.getToothNumberFirstOccurrence()));
            assertEquals(2, d.getToothSurface().getCodings().size());
            assertEquals("M", val(d.getToothSurface().getCodings().getFirst().getCode()));
            assertEquals("O", val(d.getToothSurface().getCodings().get(1).getCode()));
        }

        @Test
        void onlyFirstModifierIsMapped() {
            Claim c = claim("oral");
            Claim.ItemComponent i = item(c, 1);
            i.addModifier().addCoding().setCode("LT");
            i.addModifier().addCoding().setCode("RT");

            PiqiDentalClaim d = map(c).getDentalClaim().getFirst();

            assertEquals("LT", code(d.getProcedureModifierCode()));
            assertEquals(1, d.getProcedureModifierCode().getCodings().size());
        }

        @Test
        void firstMissingToothGoesInFirstOccurrenceRestAreMerged() {
            Claim c = claim("oral");
            info(c, 1, "missingtooth").setCode(concept("x", "1"));
            info(c, 2, "missingtooth").setCode(concept("x", "2"));
            info(c, 3, "missingtooth").setCode(concept("x", "3"));
            item(c, 1);

            PiqiDentalClaim d = map(c).getDentalClaim().getFirst();

            assertEquals("1", code(d.getMissingToothNumberFirstOccurrence()));
            assertEquals(2, d.getMissingToothNumberAfterFirstOccurrence().getCodings().size());
            assertEquals("2", val(d.getMissingToothNumberAfterFirstOccurrence().getCodings().getFirst().getCode()));
            assertEquals("3", val(d.getMissingToothNumberAfterFirstOccurrence().getCodings().get(1).getCode()));
        }

        @Test
        void singleMissingToothLeavesAfterFirstEmpty() {
            Claim c = claim("oral");
            info(c, 1, "missingtooth").setCode(concept("x", "5"));
            item(c, 1);

            PiqiDentalClaim d = map(c).getDentalClaim().getFirst();

            assertEquals("5", code(d.getMissingToothNumberFirstOccurrence()));
            assertNull(d.getMissingToothNumberAfterFirstOccurrence());
        }

        @Test
        void prosthesisDateFromSupportingInfoTiming() {
            Claim c = claim("oral");
            info(c, 1, "prosthesis").setTiming(new DateType("2020-05-05"));
            item(c, 1);
            assertEquals("2020-05-05", val(map(c).getDentalClaim().getFirst().getDateOfPriorProsthesisPlacement()));
        }

        @Test
        void claimLevelInfoIsSharedByEveryItem() {
            Claim c = claim("oral");
            info(c, 1, "prosthesis").setTiming(new DateType("2020-05-05"));
            item(c, 1);
            item(c, 2);

            List<PiqiDentalClaim> rows = map(c).getDentalClaim();

            assertEquals("2020-05-05", val(rows.getFirst().getDateOfPriorProsthesisPlacement()));
            assertEquals("2020-05-05", val(rows.get(1).getDateOfPriorProsthesisPlacement()));
        }

        @Test
        void fieldsWithoutAClaimSourceStayUnset() {
            Claim c = claim("oral");
            info(c, 1, "orthodontics").setTiming(new DateType("2026-01-15"));
            info(c, 2, "additionalbodysite").setCode(concept("x", "9"));
            Claim.ItemComponent i = item(c, 1);
            i.getBodySite().addCoding().setCode("8");

            PiqiDentalClaim d = map(c).getDentalClaim().getFirst();

            assertNull(d.getOrthodonticsTreatmentIndicator());
            assertNull(d.getOrthodonticsApplianceApplicationDate());
            assertNull(d.getTotalNumberOfMonthsForOrthodontia());
            assertNull(d.getProsthesisReplacementIndicator());
            assertNull(d.getToothNumberAfterFirstOccurrence());
        }

        @Test
        void bareItemProducesAnEmptyDentalRow() {
            Claim c = claim("oral");
            item(c, 1);
            PiqiDentalClaim d = map(c).getDentalClaim().getFirst();
            assertNull(d.getProcedureCode());
            assertNull(d.getToothNumberFirstOccurrence());
            assertNull(d.getToothSurface());
            assertNull(d.getMissingToothNumberFirstOccurrence());
        }
    }

    // ------------------------------------------------------------------
    // Claim lines
    // ------------------------------------------------------------------

    @Nested
    class LineMapping {

        @Test
        void mapsCodesAndServiceDate() {
            Claim c = claim("professional");
            Claim.ItemComponent i = item(c, 3);
            i.getProductOrService().addCoding().setSystem("http://www.ama-assn.org/go/cpt").setCode("99213");
            i.setRevenue(concept("rev", "0450"));
            i.setLocation(concept("pos", "11"));
            i.setServiced(new DateType("2026-01-01"));
            i.setNet(money("150.00"));

            PiqiClaimLine l = map(c).getClaimServiceLine().getFirst();

            assertEquals("3", val(l.getLineNumber()));
            assertEquals("99213", code(l.getLineProcedureCode()));
            assertEquals("http://www.ama-assn.org/go/cpt", system(l.getLineProcedureCode()));
            assertEquals("0450", code(l.getRevenueCenterCode()));
            assertEquals("11", code(l.getPlaceOfServiceCode()));
            assertEquals("2026-01-01", val(l.getServiceDate()));
            assertNull(l.getServiceToDate());
            assertEquals("150.00", val(l.getLineSubmittedAmount()));
        }

        @Test
        void servicedPeriodFillsFromAndToDates() {
            Claim c = claim("professional");
            item(c, 1).setServiced(new Period().setStartElement(new DateTimeType("2026-01-01"))
                    .setEndElement(new DateTimeType("2026-01-03")));

            PiqiClaimLine l = map(c).getClaimServiceLine().getFirst();

            assertEquals("2026-01-01", val(l.getServiceDate()));
            assertEquals("2026-01-03", val(l.getServiceToDate()));
        }

        @Test
        void onlyFirstModifierIsMapped() {
            Claim c = claim("professional");
            Claim.ItemComponent i = item(c, 1);
            i.addModifier().addCoding().setCode("25");
            i.addModifier().addCoding().setCode("59");

            PiqiClaimLine l = map(c).getClaimServiceLine().getFirst();

            assertEquals("25", code(l.getLineProcedureCodeModifier()));
            assertEquals(1, l.getLineProcedureCodeModifier().getCodings().size());
        }

        @Test
        void noModifierLeavesFieldNull() {
            Claim c = claim("professional");
            item(c, 1);
            assertNull(map(c).getClaimServiceLine().getFirst().getLineProcedureCodeModifier());
        }

        @Test
        void missingNetLeavesSubmittedAmountNull() {
            Claim c = claim("professional");
            item(c, 1);
            assertNull(map(c).getClaimServiceLine().getFirst().getLineSubmittedAmount());
        }

        @Test
        void leavesAdjudicationOnlyLineFieldsUnset() {
            Claim c = claim("professional");
            item(c, 1).setNet(money("150.00"));

            PiqiClaimLine l = map(c).getClaimServiceLine().getFirst();

            assertNull(l.getLineAllowedAmount());
            assertNull(l.getLineCopayAmount());
            assertNull(l.getLineCoinsuranceAmount());
            assertNull(l.getLinePatientDeductible());
            assertNull(l.getLinePaymentAmount());
            assertNull(l.getLineAmountPaidToProvider());
            assertNull(l.getLineMemberLiabilityAmount());
            assertNull(l.getLinePaymentDenialCode());
            assertNull(l.getPaymentMemberExplanation());
        }

        @Test
        void oneLinePerItemInOrder() {
            Claim c = claim("professional");
            item(c, 1);
            item(c, 2);
            item(c, 3);
            List<PiqiClaimLine> lines = map(c).getClaimServiceLine();
            assertEquals(3, lines.size());
            assertEquals("1", val(lines.getFirst().getLineNumber()));
            assertEquals("3", val(lines.get(2).getLineNumber()));
        }
    }

    // ------------------------------------------------------------------
    // Diagnoses and procedures
    // ------------------------------------------------------------------

    @Nested
    class DiagnosisAndProcedure {

        @Test
        void mapsDiagnosisFields() {
            Claim c = claim("institutional");
            Claim.DiagnosisComponent d = c.addDiagnosis();
            d.setSequence(1);
            d.setDiagnosis(new CodeableConcept().addCoding(new Coding("icd10", "E11.9", "Type 2 diabetes")));
            d.addType().addCoding().setCode("principal");
            d.setOnAdmission(concept("poa", "Y"));

            var x = map(c).getClaimDiagnosis().getFirst();

            assertEquals("E11.9", code(x.getDiagnosisCode()));
            assertEquals("principal", code(x.getDiagnosisType()));
            assertEquals("Y", val(x.getPresentOnAdmissionIndicator()));
        }

        @ParameterizedTest
        @CsvSource({
                "V43.52XA,true", "W19.XXXA,true", "X00.0XXA,true", "Y92.9,true", "v43.52xa,true",
                "S06.0X0A,false", "E11.9,false", "Z00.00,false", "VA1,false"
        })
        void eCodeIndicatorIsTrueOnlyForV00ThroughY99(String icd, String expected) {
            Claim c = claim("professional");
            Claim.DiagnosisComponent d = c.addDiagnosis();
            d.setSequence(1);
            d.setDiagnosis(new CodeableConcept().addCoding(new Coding("icd10", icd, null)));
            assertEquals(expected, val(map(c).getClaimDiagnosis().getFirst().geteCodeIndicator()));
        }

        @Test
        void diagnosisWithoutCodeHasNoECodeIndicator() {
            Claim c = claim("professional");
            Claim.DiagnosisComponent d = c.addDiagnosis();
            d.setSequence(1);
            var x = map(c).getClaimDiagnosis().getFirst();
            assertNull(x.getDiagnosisCode());
            assertNull(x.geteCodeIndicator());
        }

        @Test
        void mapsProcedureFields() {
            Claim c = claim("institutional");
            Claim.ProcedureComponent p = c.addProcedure();
            p.setSequence(1);
            p.setProcedure(concept("icd10pcs", "0BH17EZ"));
            p.setDateElement(new DateTimeType("2026-01-02"));
            p.addType().addCoding().setCode("primary");

            var x = map(c).getClaimProcedure().getFirst();

            assertEquals("0BH17EZ", code(x.getProcedureCode()));
            assertEquals("2026-01-02", val(x.getProcedureDate()));
            assertEquals("primary", code(x.getProcedureType()));
            assertNull(x.getProcedureClaimLine()); // null = header level
        }

        @Test
        void oneRowPerDiagnosisAndProcedure() {
            Claim c = claim("institutional");
            c.addDiagnosis().setSequence(1);
            c.addDiagnosis().setSequence(2);
            c.addProcedure().setSequence(1);
            PiqiPatient out = map(c);
            assertEquals(2, out.getClaimDiagnosis().size());
            assertEquals(1, out.getClaimProcedure().size());
        }
    }

    // ------------------------------------------------------------------
    // Providers
    // ------------------------------------------------------------------

    @Nested
    class ProviderMapping {

        private Organization org() {
            Organization o = new Organization();
            o.setName("Springfield Hospital");
            o.addIdentifier().setSystem(NPI).setValue("1234567893");
            o.addIdentifier().setSystem("http://other").setValue("NOT-AN-NPI");
            o.addAddress().addLine("2 Elm").setCity("Chicago").setState("IL").setPostalCode("60601");
            o.addType().addCoding().setCode("prov");
            return o;
        }

        private Practitioner practitioner() {
            Practitioner p = new Practitioner();
            p.addName().setFamily("Smith").addGiven("Jane");
            p.addIdentifier().setSystem(NPI).setValue("9876543210");
            return p;
        }

        private Map<String, Resource> directory(Resource... resources) {
            Map<String, Resource> m = new HashMap<>();
            for (Resource r : resources) {
                m.put(r.fhirType() + "/" + r.getIdElement().getIdPart(), r);
            }
            return m;
        }

        private PiqiPatient mapWith(Claim c, Map<String, Resource> dir) {
            return mapper.map(c, patient(), List.of(), ref -> dir.get(ref.getReference()));
        }

        @Test
        void billingProviderResolvedFromOrganization() {
            Organization o = org();
            o.setId("o1");
            Claim c = claim("professional");
            c.setProvider(new Reference("Organization/o1"));

            List<PiqiProvider> out = mapWith(c, directory(o)).getProvider();

            assertEquals(1, out.size());
            PiqiProvider p = out.getFirst();
            assertEquals("billing provider", val(p.getCareTeamRole().getText()));
            assertEquals("1234567893", val(p.getProviderNpi()));
            assertEquals("Springfield Hospital", val(p.getProviderName()));
            assertEquals("2 Elm, Chicago, IL, 60601", val(p.getProviderAddress()));
            assertEquals("prov", code(p.getProviderType()));
        }

        @Test
        void careTeamPractitionerGetsRoleNpiAndName() {
            Practitioner pr = practitioner();
            pr.setId("pr1");
            Claim c = claim("professional");
            Claim.CareTeamComponent ct = c.addCareTeam();
            ct.setSequence(1);
            ct.setProvider(new Reference("Practitioner/pr1"));
            ct.setRole(concept("careteamrole", "primary"));

            List<PiqiProvider> out = mapWith(c, directory(pr)).getProvider();

            assertEquals(1, out.size());
            assertEquals("primary", code(out.getFirst().getCareTeamRole()));
            assertEquals("9876543210", val(out.getFirst().getProviderNpi()));
            assertTrue(val(out.getFirst().getProviderName()).contains("Smith"));
        }

        @Test
        void billingProviderComesBeforeCareTeam() {
            Organization o = org();
            o.setId("o1");
            Practitioner pr = practitioner();
            pr.setId("pr1");
            Claim c = claim("professional");
            c.setProvider(new Reference("Organization/o1"));
            Claim.CareTeamComponent ct = c.addCareTeam();
            ct.setSequence(1);
            ct.setProvider(new Reference("Practitioner/pr1"));

            List<PiqiProvider> out = mapWith(c, directory(o, pr)).getProvider();

            assertEquals(2, out.size());
            assertEquals("billing provider", val(out.getFirst().getCareTeamRole().getText()));
            assertEquals("9876543210", val(out.get(1).getProviderNpi()));
        }

        @Test
        void practitionerRoleResolvesPractitionerAndSetsSpecialtyAsType() {
            Practitioner pr = practitioner();
            pr.setId("pr1");
            PractitionerRole role = new PractitionerRole();
            role.setId("r1");
            role.setPractitioner(new Reference("Practitioner/pr1"));
            role.addSpecialty().addCoding().setCode("207R00000X");

            Claim c = claim("professional");
            Claim.CareTeamComponent ct = c.addCareTeam();
            ct.setSequence(1);
            ct.setProvider(new Reference("PractitionerRole/r1"));
            ct.setRole(concept("careteamrole", "performing"));

            List<PiqiProvider> out = mapWith(c, directory(pr, role)).getProvider();

            assertEquals(1, out.size());
            assertEquals("9876543210", val(out.getFirst().getProviderNpi()));
            assertEquals("207R00000X", code(out.getFirst().getProviderType()));
            assertEquals("performing", code(out.getFirst().getCareTeamRole()));
        }

        @Test
        void unresolvedProvidersAreSkipped() {
            Claim c = claim("professional");
            c.setProvider(new Reference("Organization/missing"));
            Claim.CareTeamComponent ct = c.addCareTeam();
            ct.setSequence(1);
            ct.setProvider(new Reference("Practitioner/missing"));
            assertTrue(mapWith(c, directory()).getProvider().isEmpty());
        }

        @Test
        void unsupportedResourceTypeIsSkipped() {
            Patient notAProvider = new Patient();
            notAProvider.setId("x1");
            Claim c = claim("professional");
            c.setProvider(new Reference("Patient/x1"));
            assertTrue(mapWith(c, directory(notAProvider)).getProvider().isEmpty());
        }

        @Test
        void noResolverMeansNoProviders() {
            Claim c = claim("professional");
            c.setProvider(new Reference("Organization/o1"));
            assertTrue(mapper.map(c, patient(), List.of()).getProvider().isEmpty());
        }

        @Test
        void npiIsOnlyTakenFromTheNpiSystem() {
            Organization o = new Organization();
            o.setId("o2");
            o.addIdentifier().setSystem("http://other").setValue("NOT-AN-NPI");
            Claim c = claim("professional");
            c.setProvider(new Reference("Organization/o2"));

            List<PiqiProvider> out = mapWith(c, directory(o)).getProvider();

            assertEquals(1, out.size());
            assertNull(out.getFirst().getProviderNpi());
        }

        @Test
        void addressFallsBackToTextWhenPresent() {
            Organization o = new Organization();
            o.setId("o3");
            o.addAddress().setText("Free-text address");
            Claim c = claim("professional");
            c.setProvider(new Reference("Organization/o3"));

            assertEquals("Free-text address",
                    val(mapWith(c, directory(o)).getProvider().getFirst().getProviderAddress()));
        }
    }

    // ------------------------------------------------------------------
    // Whole-resource sanity check
    // ------------------------------------------------------------------

    @Test
    void listsAreNeverNullEvenForAnEmptyClaim() {
        PiqiPatient out = map(new Claim());
        assertNotNull(out.getMember());
        assertNotNull(out.getCoverage());
        assertNotNull(out.getMedicalClaim());
        assertNotNull(out.getClaimServiceLine());
        assertNotNull(out.getClaimDiagnosis());
        assertNotNull(out.getClaimProcedure());
        assertNotNull(out.getPharmacyClaim());
        assertNotNull(out.getDentalClaim());
        assertNotNull(out.getProvider());
        assertFalse(out.getMedicalClaim().isEmpty());
    }
}
