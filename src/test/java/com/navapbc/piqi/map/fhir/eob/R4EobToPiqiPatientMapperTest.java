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

public class R4EobToPiqiPatientMapperTest {

    private static final String ADJ = "http://terminology.hl7.org/CodeSystem/adjudication";
    private static final String C4BB_ADJ = "http://hl7.org/fhir/us/carin-bb/CodeSystem/C4BBAdjudication";
    private static final String C4BB_INFO = "http://hl7.org/fhir/us/carin-bb/CodeSystem/C4BBSupportingInfoType";
    private static final String CLAIM_TYPE = "http://terminology.hl7.org/CodeSystem/claim-type";
    private static final String NPI = "http://hl7.org/fhir/sid/us-npi";
    private static final String US_CORE_BIRTHSEX =
            "http://hl7.org/fhir/us/core/StructureDefinition/us-core-birthsex";

    R4EobToPiqiPatientMapper mapper = new R4EobToPiqiPatientMapper();

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

    private static ExplanationOfBenefit eob(String type) {
        ExplanationOfBenefit e = new ExplanationOfBenefit();
        if (type != null) {
            e.getType().addCoding().setSystem(CLAIM_TYPE).setCode(type);
        }
        return e;
    }

    private static CodeableConcept concept(String system, String code) {
        return new CodeableConcept().addCoding(new Coding(system, code, null));
    }

    private static Money money(String amount) {
        return new Money().setValue(new BigDecimal(amount));
    }

    private static void addTotal(ExplanationOfBenefit e, String system, String category, String amount) {
        e.addTotal().setCategory(concept(system, category)).setAmount(money(amount));
    }

    private static void addAdj(ExplanationOfBenefit.ItemComponent i, String system, String category, String amount) {
        i.addAdjudication().setCategory(concept(system, category)).setAmount(money(amount));
    }

    private static ExplanationOfBenefit.SupportingInformationComponent info(ExplanationOfBenefit e, int seq, String category) {
        ExplanationOfBenefit.SupportingInformationComponent s = e.addSupportingInfo();
        s.setSequence(seq);
        s.setCategory(concept(C4BB_INFO, category));
        return s;
    }

    private static ExplanationOfBenefit.ItemComponent item(ExplanationOfBenefit e, int seq) {
        ExplanationOfBenefit.ItemComponent i = e.addItem();
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

    private PiqiPatient map(ExplanationOfBenefit e) {
        return mapper.map(e, patient());
    }

    private PiqiMedicalClaim medical(ExplanationOfBenefit e) {
        PiqiPatient out = map(e);
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
            PiqiPatient out = map(eob(type));
            assertEquals(1, out.getMedicalClaim().size());
            assertTrue(out.getPharmacyClaim().isEmpty());
            assertTrue(out.getDentalClaim().isEmpty());
        }

        @Test
        void missingTypeDefaultsToMedical() {
            PiqiPatient out = map(eob(null));
            assertEquals(1, out.getMedicalClaim().size());
            assertTrue(out.getPharmacyClaim().isEmpty());
            assertTrue(out.getDentalClaim().isEmpty());
        }

        @Test
        void pharmacyProducesPharmacyClaimOnly() {
            PiqiPatient out = map(eob("pharmacy"));
            assertTrue(out.getMedicalClaim().isEmpty());
            assertEquals(1, out.getPharmacyClaim().size());
            assertTrue(out.getDentalClaim().isEmpty());
        }

        @Test
        void oralProducesOneDentalClaimPerItem() {
            ExplanationOfBenefit e = eob("oral");
            item(e, 1);
            item(e, 2);
            PiqiPatient out = map(e);
            assertTrue(out.getMedicalClaim().isEmpty());
            assertTrue(out.getPharmacyClaim().isEmpty());
            assertEquals(2, out.getDentalClaim().size());
            assertEquals(2, out.getClaimServiceLine().size());
        }

        @Test
        void emptyEobDoesNotThrow() {
            assertDoesNotThrow(() -> mapper.map(new ExplanationOfBenefit(), patient()));
        }

        @Test
        void nullCoveragesAndResolverAreTolerated() {
            assertDoesNotThrow(() -> mapper.map(eob("professional"), patient(), null, null));
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

            var m = mapper.map(eob("professional"), p).getMember();

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
            var m = mapper.map(eob("professional"), p).getMember();
            assertNull(m.getMemberId());
            assertEquals("p1", val(m.getUniqueMemberId()));
        }

        @Test
        void birthSexComesFromUsCoreExtensionNotGender() {
            Patient p = patient();
            p.setGender(org.hl7.fhir.r4.model.Enumerations.AdministrativeGender.MALE);
            assertNull(mapper.map(eob("professional"), p).getMember().getBirthSex());

            p.addExtension().setUrl(US_CORE_BIRTHSEX).setValue(new CodeType("F"));
            assertEquals("F", code(mapper.map(eob("professional"), p).getMember().getBirthSex()));
        }

        @Test
        void deceasedDateSetsFlagAndDate() {
            Patient p = patient();
            p.setDeceased(new DateTimeType("2025-12-01"));
            var m = mapper.map(eob("professional"), p).getMember();
            assertEquals("2025-12-01", val(m.getDateOfDeath()));
            assertEquals("true", val(m.getDeceasedFlag()));
        }

        @Test
        void deceasedBooleanFalseSetsOnlyFlag() {
            Patient p = patient();
            p.setDeceased(new BooleanType(false));
            var m = mapper.map(eob("professional"), p).getMember();
            assertNull(m.getDateOfDeath());
            assertEquals("false", val(m.getDeceasedFlag()));
        }

        @Test
        void primaryLanguagePrefersPreferredCommunication() {
            Patient p = patient();
            p.addCommunication().setLanguage(concept("urn:ietf:bcp:47", "es")).setPreferred(false);
            p.addCommunication().setLanguage(concept("urn:ietf:bcp:47", "en")).setPreferred(true);
            assertEquals("en", code(mapper.map(eob("professional"), p).getMember().getPrimaryLanguage()));
        }

        @Test
        void primaryLanguageFallsBackToFirstCommunication() {
            Patient p = patient();
            p.addCommunication().setLanguage(concept("urn:ietf:bcp:47", "es"));
            assertEquals("es", code(mapper.map(eob("professional"), p).getMember().getPrimaryLanguage()));
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
            PiqiPatient out = mapper.map(eob("professional"), patient(), List.of(coverage()));
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
            PiqiPatient out = mapper.map(eob("professional"), patient(), List.of(cv));
            assertEquals("org9", val(out.getCoverage().getFirst().getPayerIdentifier()));
        }

        @Test
        void resolvesCoverageFromInsuranceWhenNoneSupplied() {
            ExplanationOfBenefit e = eob("professional");
            e.addInsurance().setCoverage(new Reference("Coverage/c1"));
            Coverage cv = coverage();

            PiqiPatient out = mapper.map(e, patient(), List.of(),
                    ref -> "Coverage/c1".equals(ref.getReference()) ? cv : null);

            assertEquals(1, out.getCoverage().size());
            assertEquals("S1", val(out.getCoverage().getFirst().getSubscriberId()));
        }

        @Test
        void noCoverageWhenNoneSuppliedAndNothingResolvable() {
            ExplanationOfBenefit e = eob("professional");
            e.addInsurance().setCoverage(new Reference("Coverage/c1"));
            assertTrue(map(e).getCoverage().isEmpty());
        }
    }

    // ------------------------------------------------------------------
    // Medical claim header
    // ------------------------------------------------------------------

    @Nested
    class MedicalHeader {

        @Test
        void mapsTypeUseOutcomeAndDates() {
            ExplanationOfBenefit e = eob("institutional");
            e.setUse(ExplanationOfBenefit.Use.CLAIM);
            e.setOutcome(ExplanationOfBenefit.RemittanceOutcome.COMPLETE);
            e.setCreatedElement(new DateTimeType("2026-02-01"));
            e.getBillablePeriod().setStartElement(new DateTimeType("2026-01-01"))
                    .setEndElement(new DateTimeType("2026-01-05"));

            PiqiMedicalClaim m = medical(e);

            assertEquals("institutional", code(m.getClaimTypeCode()));
            assertEquals(CLAIM_TYPE, system(m.getClaimTypeCode()));
            assertEquals("claim", code(m.getClaimUse()));
            assertEquals("complete", code(m.getProcessingStatusCode()));
            assertEquals("2026-02-01", val(m.getAdjudicationDate()));
            assertEquals("2026-01-01", val(m.getClaimStartDate()));
            assertEquals("2026-01-05", val(m.getClaimServiceEndDate()));
            assertEquals("2026-01-01", val(m.getStatementFromDate()));
            assertEquals("2026-01-05", val(m.getStatementThruDate()));
        }

        @Test
        void mapsLastUpdated() {
            ExplanationOfBenefit e = eob("professional");
            e.getMeta().setLastUpdatedElement(new InstantType("2026-03-01T10:00:00Z"));
            String v = val(medical(e).getClaimLastUpdateDate());
            assertNotNull(v);
            assertTrue(v.startsWith("2026-03-01"));
        }

        @Test
        void claimIdentifierPrefersUcType() {
            ExplanationOfBenefit e = eob("professional");
            e.addIdentifier().setValue("OTHER");
            e.addIdentifier().setValue("CLM-9").getType().addCoding().setCode("uc");

            PiqiMedicalClaim m = medical(e);

            assertEquals("CLM-9", val(m.getPayerClaimUniqueIdentifier()));
            assertEquals("uc", val(m.getClaimIdentifierType()));
        }

        @Test
        void claimIdentifierFallsBackToFirst() {
            ExplanationOfBenefit e = eob("professional");
            e.addIdentifier().setValue("A1");
            PiqiMedicalClaim m = medical(e);
            assertEquals("A1", val(m.getPayerClaimUniqueIdentifier()));
            assertNull(m.getClaimIdentifierType());
        }

        @Test
        void blankIdentifierValueProducesNoAttribute() {
            ExplanationOfBenefit e = eob("professional");
            e.addIdentifier().setValue("  ");
            assertNull(medical(e).getPayerClaimUniqueIdentifier());
        }

        @Test
        void payerIdentifierPrefersIdentifierOverReference() {
            ExplanationOfBenefit e = eob("professional");
            e.getInsurer().setReference("Organization/ins1");
            assertEquals("ins1", val(medical(e).getClaimPayerIdentifier()));

            e.getInsurer().setIdentifier(new Identifier().setValue("PAYER-X"));
            assertEquals("PAYER-X", val(medical(e).getClaimPayerIdentifier()));
        }

        @Test
        void mapsPayee() {
            ExplanationOfBenefit e = eob("professional");
            e.getPayee().setType(concept("http://terminology.hl7.org/CodeSystem/payeetype", "provider"));
            e.getPayee().setParty(new Reference("Organization/o1"));
            PiqiMedicalClaim m = medical(e);
            assertEquals("provider", code(m.getClaimPayeeTypeCode()));
            assertEquals("Organization/o1", val(m.getClaimPayee()));
        }

        @Test
        void priorRelatedClaimFillsAdjustedFrom() {
            ExplanationOfBenefit e = eob("professional");
            e.addRelated().setRelationship(concept("rel", "prior"))
                    .setReference(new Identifier().setValue("CLM-OLD"));
            PiqiMedicalClaim m = medical(e);
            assertEquals("prior", code(m.getClaimAdjustedRelationship()));
            assertEquals("CLM-OLD", val(m.getClaimAdjustedfromIdentifier()));
        }

        @Test
        void nonPriorRelatedClaimSetsRelationshipOnly() {
            ExplanationOfBenefit e = eob("professional");
            e.addRelated().setRelationship(concept("rel", "associated"))
                    .setReference(new Identifier().setValue("CLM-OTHER"));
            PiqiMedicalClaim m = medical(e);
            assertEquals("associated", code(m.getClaimAdjustedRelationship()));
            assertNull(m.getClaimAdjustedfromIdentifier());
        }

        @Test
        void leavesInferredFieldsUnset() {
            PiqiMedicalClaim m = medical(eob("professional"));
            assertNull(m.getClaimPaymentStatusCode());
            assertNull(m.getPrimaryPayerIdentifier());
            assertNull(m.getProcedureCodeType());
            assertNull(m.getTotalAmount());
            assertNull(m.getAdjudicationAmountType());
        }
    }

    // ------------------------------------------------------------------
    // Totals and payment
    // ------------------------------------------------------------------

    @Nested
    class TotalsAndPayment {

        @Test
        void mapsEveryTotalCategoryToItsField() {
            ExplanationOfBenefit e = eob("professional");
            addTotal(e, ADJ, "submitted", "200.00");
            addTotal(e, ADJ, "eligible", "120.00");
            addTotal(e, ADJ, "copay", "10.00");
            addTotal(e, ADJ, "deductible", "30.00");
            addTotal(e, C4BB_ADJ, "coinsurance", "5.00");
            addTotal(e, C4BB_ADJ, "memberliability", "45.00");
            addTotal(e, C4BB_ADJ, "noncovered", "7.00");
            addTotal(e, C4BB_ADJ, "priorpayerpaid", "3.00");
            addTotal(e, C4BB_ADJ, "paidbypatient", "55.00");
            addTotal(e, C4BB_ADJ, "paidtoprovider", "80.00");
            addTotal(e, C4BB_ADJ, "paidtopatient", "2.00");
            addTotal(e, C4BB_ADJ, "discount", "1.00");

            PiqiMedicalClaim m = medical(e);

            assertEquals("200.00", val(m.getClaimTotalSubmittedAmount()));
            assertEquals("120.00", val(m.getClaimTotalAllowedAmount()));
            assertEquals("10.00", val(m.getCopayAmount()));
            assertEquals("30.00", val(m.getMemberPaidDeductible()));
            assertEquals("5.00", val(m.getCoInsuranceLiabilityAmount()));
            assertEquals("45.00", val(m.getMemberLiability()));
            assertEquals("7.00", val(m.getClaimNonCoveredAmount()));
            assertEquals("3.00", val(m.getClaimOtherPayerPaidAmount()));
            assertEquals("55.00", val(m.getAmountPaidByPatient()));
            assertEquals("80.00", val(m.getClaimAmountPaidToProvider()));
            assertEquals("2.00", val(m.getMemberReimbursement()));
            assertEquals("1.00", val(m.getClaimDiscountAmount()));
        }

        @Test
        void totalOverridesHeaderLevelAdjudication() {
            ExplanationOfBenefit e = eob("professional");
            e.addAdjudication().setCategory(concept(ADJ, "submitted")).setAmount(money("100.00"));
            addTotal(e, ADJ, "submitted", "200.00");
            assertEquals("200.00", val(medical(e).getClaimTotalSubmittedAmount()));
        }

        @Test
        void headerLevelAdjudicationUsedWhenNoTotal() {
            ExplanationOfBenefit e = eob("professional");
            e.addAdjudication().setCategory(concept(ADJ, "eligible")).setAmount(money("50.00"));
            assertEquals("50.00", val(medical(e).getClaimTotalAllowedAmount()));
        }

        @Test
        void absentCategoriesStayNull() {
            ExplanationOfBenefit e = eob("professional");
            addTotal(e, ADJ, "submitted", "200.00");
            PiqiMedicalClaim m = medical(e);
            assertNull(m.getClaimTotalAllowedAmount());
            assertNull(m.getCopayAmount());
            assertNull(m.getMemberLiability());
        }

        @Test
        void paymentBlockFillsPaymentAmountAndDate() {
            ExplanationOfBenefit e = eob("professional");
            e.getPayment().setAmount(money("80.00")).setDateElement(new DateType("2026-02-10"));
            PiqiMedicalClaim m = medical(e);
            assertEquals("80.00", val(m.getClaimPaymentAmount()));
            assertEquals("2026-02-10", val(m.getClaimPaidDate()));
        }

        @Test
        void paymentBlockTakesPrecedenceOverBenefitTotal() {
            ExplanationOfBenefit e = eob("professional");
            e.getPayment().setAmount(money("80.00"));
            addTotal(e, ADJ, "benefit", "99.00");
            assertEquals("80.00", val(medical(e).getClaimPaymentAmount()));
        }

        @Test
        void benefitTotalIsFallbackPaymentAmount() {
            ExplanationOfBenefit e = eob("professional");
            addTotal(e, ADJ, "benefit", "99.00");
            assertEquals("99.00", val(medical(e).getClaimPaymentAmount()));
        }

        @Test
        void headerLevelDenialReason() {
            ExplanationOfBenefit e = eob("professional");
            e.addAdjudication().setCategory(concept(ADJ, "submitted")).setAmount(money("1"));
            e.addAdjudication().setCategory(concept(C4BB_ADJ, "noncovered"))
                    .setReason(concept("denial", "CO-97"));
            assertEquals("CO-97", code(medical(e).getPaymentDenialCode()));
        }
    }

    // ------------------------------------------------------------------
    // supportingInfo on the medical header
    // ------------------------------------------------------------------

    @Nested
    class SupportingInfoMapping {

        @Test
        void mapsInstitutionalSupportingInfo() {
            ExplanationOfBenefit e = eob("institutional");
            info(e, 1, "clmrecvddate").setTiming(new DateType("2026-01-20"));
            info(e, 2, "admissionperiod").setTiming(new Period()
                    .setStartElement(new DateTimeType("2026-01-01"))
                    .setEndElement(new DateTimeType("2026-01-05")));
            info(e, 3, "discharge-status").setCode(concept("nubc", "01"));
            info(e, 4, "admtype").setCode(concept("nubc", "1"));
            info(e, 5, "pointoforigin").setCode(concept("nubc", "7"));
            info(e, 6, "medicalrecordnumber").setValue(new StringType("MRN7"));
            info(e, 7, "patientaccountnumber").setValue(new StringType("ACCT8"));

            PiqiMedicalClaim m = medical(e);

            assertEquals("2026-01-20", val(m.getClaimReceivedDate()));
            assertEquals("2026-01-01", val(m.getAdmissionDate()));
            assertEquals("2026-01-05", val(m.getDischargedate()));
            assertEquals("01", code(m.getPatientDischargeStatusCode()));
            assertEquals("1", code(m.getInpatientAdmissionTypeCode()));
            assertEquals("7", code(m.getInpatientSourceAdmissionCode()));
            assertEquals("MRN7", val(m.getMedicalRecordNumber()));
            assertEquals("ACCT8", val(m.getPatientAccountNumber()));
        }

        @Test
        void categoryMatchIsCaseInsensitive() {
            ExplanationOfBenefit e = eob("institutional");
            info(e, 1, "ClmRecvdDate").setTiming(new DateType("2026-01-20"));
            assertEquals("2026-01-20", val(medical(e).getClaimReceivedDate()));
        }

        @Test
        void unrelatedSupportingInfoIsIgnored() {
            ExplanationOfBenefit e = eob("institutional");
            info(e, 1, "somethingelse").setTiming(new DateType("2026-01-20"));
            assertNull(medical(e).getClaimReceivedDate());
        }

        @ParameterizedTest
        @CsvSource({"0111,1,1", "111,1,1", "0131,3,1", "0721,2,1", "0117,1,7"})
        void typeOfBillIsSplitIntoClassificationAndFrequency(String tob, String classification, String frequency) {
            ExplanationOfBenefit e = eob("institutional");
            info(e, 1, "typeofbill").setCode(concept("https://www.nubc.org/CodeSystem/TypeOfBill", tob));

            PiqiMedicalClaim m = medical(e);

            assertEquals(tob, code(m.getBillFacilityTypeCode()));
            assertEquals(classification, code(m.getServiceClassificationTypeCode()));
            assertEquals(frequency, code(m.getFrequencyCode()));
        }

        @ParameterizedTest
        @CsvSource({"ABC", "11", "01111"})
        void malformedTypeOfBillIsNotSplit(String tob) {
            ExplanationOfBenefit e = eob("institutional");
            info(e, 1, "typeofbill").setCode(concept("nubc", tob));

            PiqiMedicalClaim m = medical(e);

            assertEquals(tob, code(m.getBillFacilityTypeCode()));
            assertNull(m.getServiceClassificationTypeCode());
            assertNull(m.getFrequencyCode());
        }

        @Test
        void drgFromSupportingInfoUsesCodingVersion() {
            ExplanationOfBenefit e = eob("institutional");
            info(e, 1, "drg").setCode(new CodeableConcept()
                    .addCoding(new Coding("http://drg-system", "470", "Joint replacement").setVersion("39")));

            PiqiMedicalClaim m = medical(e);

            assertEquals("470", code(m.getClaimDrgCode()));
            assertEquals("39", val(m.getClaimDrgVersion()));
        }

        @Test
        void drgFallsBackToDiagnosisPackageCodeAndUsesSystemWhenNoVersion() {
            ExplanationOfBenefit e = eob("institutional");
            ExplanationOfBenefit.DiagnosisComponent d = e.addDiagnosis();
            d.setSequence(1);
            d.setPackageCode(concept("http://drg-system", "470"));

            PiqiMedicalClaim m = medical(e);

            assertEquals("470", code(m.getClaimDrgCode()));
            assertEquals("http://drg-system", val(m.getClaimDrgVersion()));
        }

        @Test
        void noDrgWhenNoneProvided() {
            PiqiMedicalClaim m = medical(eob("institutional"));
            assertNull(m.getClaimDrgCode());
            assertNull(m.getClaimDrgVersion());
        }
    }

    // ------------------------------------------------------------------
    // Pharmacy
    // ------------------------------------------------------------------

    @Nested
    class PharmacyMapping {

        private ExplanationOfBenefit pharmacyEob() {
            ExplanationOfBenefit e = eob("pharmacy");
            e.addIdentifier().setValue("RX-1");
            info(e, 1, "dayssupply").setValue(new Quantity(30));
            info(e, 2, "refillnum").setValue(new Quantity(2));
            info(e, 3, "dawcode").setCode(concept("ncpdp", "0"));
            info(e, 4, "rxorigincode").setCode(concept("ncpdp", "3"));
            info(e, 5, "brandgenericindicator").setCode(concept("ncpdp", "G"));
            info(e, 6, "compoundcode").setCode(concept("ncpdp", "1"));
            return e;
        }

        @Test
        void emitsOneRowPerTotalCategoryRepeatingAttributes() {
            ExplanationOfBenefit e = pharmacyEob();
            addTotal(e, ADJ, "submitted", "50");
            addTotal(e, ADJ, "eligible", "40");

            List<PiqiPharmacyClaim> rows = map(e).getPharmacyClaim();

            assertEquals(2, rows.size());
            assertEquals("submitted", val(rows.getFirst().getAdjudicationAmountType()));
            assertEquals("50", val(rows.getFirst().getTotalAmount()));
            assertEquals("eligible", val(rows.get(1).getAdjudicationAmountType()));
            assertEquals("40", val(rows.get(1).getTotalAmount()));
            for (PiqiPharmacyClaim r : rows) {
                assertEquals("30", val(r.getDaysSupply()));
                assertEquals("2", val(r.getRefillNumber()));
                assertEquals("0", val(r.getDawCode()));
                assertEquals("3", val(r.getPrescriptionOriginCode()));
                assertEquals("G", code(r.getPlanReportedBrandGenericCode()));
                assertEquals("RX-1", val(r.getRxServiceReferenceNumber()));
            }
        }

        @Test
        void singleRowWithoutAmountWhenNoTotals() {
            List<PiqiPharmacyClaim> rows = map(pharmacyEob()).getPharmacyClaim();
            assertEquals(1, rows.size());
            assertNull(rows.getFirst().getTotalAmount());
            assertNull(rows.getFirst().getAdjudicationAmountType());
            assertEquals("30", val(rows.getFirst().getDaysSupply()));
        }

        @Test
        void pharmacyLineUsesNdcQuantityAndCompoundCode() {
            ExplanationOfBenefit e = pharmacyEob();
            ExplanationOfBenefit.ItemComponent i = item(e, 1);
            i.getProductOrService().addCoding().setSystem("http://hl7.org/fhir/sid/ndc").setCode("00093-1234-01");
            i.getQuantity().setValue(30).setCode("tablet");

            PiqiClaimLine l = map(e).getClaimServiceLine().getFirst();

            assertEquals("00093-1234-01", code(l.getNationalDrugCode()));
            assertEquals("30", val(l.getQuantityDispensed()));
            assertEquals("tablet", code(l.getQuantityQualifierCode()));
            assertEquals("http://unitsofmeasure.org", system(l.getQuantityQualifierCode()));
            assertEquals("1", code(l.getCompoundCode()));
            assertNull(l.getLineProcedureCode());
        }

        @Test
        void medicalLineUsesProcedureCodeNotNdc() {
            ExplanationOfBenefit e = eob("professional");
            item(e, 1).getProductOrService().addCoding().setCode("99213");

            PiqiClaimLine l = map(e).getClaimServiceLine().getFirst();

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
        void mapsToothSurfaceAndProcedureFromItem() {
            ExplanationOfBenefit e = eob("oral");
            ExplanationOfBenefit.ItemComponent i = item(e, 1);
            i.getProductOrService().addCoding().setCode("D2391");
            i.addModifier().addCoding().setCode("LT");
            i.getBodySite().addCoding().setCode("8");
            i.addSubSite().addCoding().setCode("M");
            i.addSubSite().addCoding().setCode("O");

            PiqiDentalClaim d = map(e).getDentalClaim().getFirst();

            assertEquals("D2391", code(d.getProcedureCode()));
            assertEquals("LT", code(d.getProcedureModifierCode()));
            assertEquals("8", code(d.getToothNumberFirstOccurrence()));
            assertEquals(2, d.getToothSurface().getCodings().size());
            assertEquals("M", val(d.getToothSurface().getCodings().getFirst().getCode()));
            assertEquals("O", val(d.getToothSurface().getCodings().get(1).getCode()));
        }

        @Test
        void unlinkedItemSeesAllClaimLevelSupportingInfo() {
            ExplanationOfBenefit e = eob("oral");
            info(e, 1, "additionalbodysite").setCode(concept("x", "9"));
            info(e, 2, "prosthesis").setTiming(new DateType("2020-05-05"));
            info(e, 3, "orthodontics").setTiming(new DateType("2026-01-15")).setValue(new Quantity(24));
            info(e, 4, "missingtoothnumber").setCode(concept("x", "1"));
            info(e, 5, "missingtoothnumber").setCode(concept("x", "2"));
            info(e, 6, "missingtoothnumber").setCode(concept("x", "3"));
            item(e, 1);

            PiqiDentalClaim d = map(e).getDentalClaim().getFirst();

            assertEquals("9", code(d.getToothNumberAfterFirstOccurrence()));
            assertEquals("true", val(d.getProsthesisReplacementIndicator()));
            assertEquals("2020-05-05", val(d.getDateOfPriorProsthesisPlacement()));
            assertEquals("true", val(d.getOrthodonticsTreatmentIndicator()));
            assertEquals("2026-01-15", val(d.getOrthodonticsApplianceApplicationDate()));
            assertEquals("24", val(d.getTotalNumberOfMonthsForOrthodontia()));
            assertEquals("1", code(d.getMissingToothNumberFirstOccurrence()));
            assertEquals(2, d.getMissingToothNumberAfterFirstOccurrence().getCodings().size());
            assertEquals("2", val(d.getMissingToothNumberAfterFirstOccurrence().getCodings().getFirst().getCode()));
            assertEquals("3", val(d.getMissingToothNumberAfterFirstOccurrence().getCodings().get(1).getCode()));
        }

        @Test
        void linkedItemOnlySeesItsOwnSupportingInfo() {
            ExplanationOfBenefit e = eob("oral");
            info(e, 1, "additionalbodysite").setCode(concept("x", "9"));
            info(e, 2, "prosthesis").setTiming(new DateType("2020-05-05"));
            info(e, 3, "orthodontics").setTiming(new DateType("2026-01-15"));
            ExplanationOfBenefit.ItemComponent i = item(e, 1);
            i.addInformationSequence(2);

            PiqiDentalClaim d = map(e).getDentalClaim().getFirst();

            assertEquals("true", val(d.getProsthesisReplacementIndicator()));
            assertNull(d.getToothNumberAfterFirstOccurrence());
            assertNull(d.getOrthodonticsTreatmentIndicator());
            assertNull(d.getMissingToothNumberFirstOccurrence());
        }

        @Test
        void eachItemGetsItsOwnLinkedInfo() {
            ExplanationOfBenefit e = eob("oral");
            info(e, 1, "prosthesis").setTiming(new DateType("2020-05-05"));
            info(e, 2, "orthodontics").setTiming(new DateType("2026-01-15"));
            item(e, 1).addInformationSequence(1);
            item(e, 2).addInformationSequence(2);

            List<PiqiDentalClaim> rows = map(e).getDentalClaim();

            assertEquals("true", val(rows.getFirst().getProsthesisReplacementIndicator()));
            assertNull(rows.getFirst().getOrthodonticsTreatmentIndicator());
            assertEquals("true", val(rows.get(1).getOrthodonticsTreatmentIndicator()));
            assertNull(rows.get(1).getProsthesisReplacementIndicator());
        }

        @Test
        void singleMissingToothLeavesAfterFirstEmpty() {
            ExplanationOfBenefit e = eob("oral");
            info(e, 1, "missingtoothnumber").setCode(concept("x", "5"));
            item(e, 1);

            PiqiDentalClaim d = map(e).getDentalClaim().getFirst();

            assertEquals("5", code(d.getMissingToothNumberFirstOccurrence()));
            assertNull(d.getMissingToothNumberAfterFirstOccurrence());
        }
    }

    // ------------------------------------------------------------------
    // Claim lines
    // ------------------------------------------------------------------

    @Nested
    class LineMapping {

        @Test
        void mapsCodesAndDates() {
            ExplanationOfBenefit e = eob("professional");
            ExplanationOfBenefit.ItemComponent i = item(e, 3);
            i.setCategory(concept("benefitcat", "1"));
            i.getProductOrService().addCoding().setSystem("http://www.ama-assn.org/go/cpt").setCode("99213");
            i.setRevenue(concept("rev", "0450"));
            i.setLocation(concept("pos", "11"));
            i.setServiced(new DateType("2026-01-01"));

            PiqiClaimLine l = map(e).getClaimServiceLine().getFirst();

            assertEquals("3", val(l.getLineNumber()));
            assertEquals("1", code(l.getTypeOfService()));
            assertEquals("99213", code(l.getLineProcedureCode()));
            assertEquals("0450", code(l.getRevenueCenterCode()));
            assertEquals("11", code(l.getPlaceOfServiceCode()));
            assertEquals("2026-01-01", val(l.getServiceDate()));
            assertNull(l.getServiceToDate());
        }

        @Test
        void servicedPeriodFillsFromAndToDates() {
            ExplanationOfBenefit e = eob("professional");
            item(e, 1).setServiced(new Period().setStartElement(new DateTimeType("2026-01-01"))
                    .setEndElement(new DateTimeType("2026-01-03")));

            PiqiClaimLine l = map(e).getClaimServiceLine().getFirst();

            assertEquals("2026-01-01", val(l.getServiceDate()));
            assertEquals("2026-01-03", val(l.getServiceToDate()));
        }

        @Test
        void onlyFirstModifierIsMapped() {
            ExplanationOfBenefit e = eob("professional");
            ExplanationOfBenefit.ItemComponent i = item(e, 1);
            i.addModifier().addCoding().setCode("25");
            i.addModifier().addCoding().setCode("59");

            PiqiClaimLine l = map(e).getClaimServiceLine().getFirst();

            assertEquals("25", code(l.getLineProcedureCodeModifier()));
            assertEquals(1, l.getLineProcedureCodeModifier().getCodings().size());
        }

        @Test
        void mapsEveryAdjudicationCategory() {
            ExplanationOfBenefit e = eob("professional");
            ExplanationOfBenefit.ItemComponent i = item(e, 1);
            addAdj(i, ADJ, "submitted", "200.00");
            addAdj(i, ADJ, "eligible", "120.00");
            addAdj(i, ADJ, "copay", "10.00");
            addAdj(i, ADJ, "deductible", "30.00");
            addAdj(i, ADJ, "benefit", "75.00");
            addAdj(i, C4BB_ADJ, "coinsurance", "5.00");
            addAdj(i, C4BB_ADJ, "noncovered", "7.00");
            addAdj(i, C4BB_ADJ, "priorpayerpaid", "3.00");
            addAdj(i, C4BB_ADJ, "paidbypatient", "55.00");
            addAdj(i, C4BB_ADJ, "paidtoprovider", "80.00");
            addAdj(i, C4BB_ADJ, "paidtopatient", "2.00");
            addAdj(i, C4BB_ADJ, "memberliability", "45.00");
            addAdj(i, C4BB_ADJ, "discount", "1.00");
            addAdj(i, C4BB_ADJ, "drugcost", "9.00");
            i.addAdjudication().setCategory(concept(C4BB_ADJ, "allowedunits")).setValue(new BigDecimal("2"));

            PiqiClaimLine l = map(e).getClaimServiceLine().getFirst();

            assertEquals("200.00", val(l.getLineSubmittedAmount()));
            assertEquals("120.00", val(l.getLineAllowedAmount()));
            assertEquals("10.00", val(l.getLineCopayAmount()));
            assertEquals("30.00", val(l.getLinePatientDeductible()));
            assertEquals("75.00", val(l.getLinePaymentAmount()));
            assertEquals("5.00", val(l.getLineCoinsuranceAmount()));
            assertEquals("7.00", val(l.getLineNoncoveredAmount()));
            assertEquals("3.00", val(l.getLineOtherPayerPaidAmount()));
            assertEquals("55.00", val(l.getLineAmountPaidByPatient()));
            assertEquals("80.00", val(l.getLineAmountPaidToProvider()));
            assertEquals("2.00", val(l.getLineMemberReimbursement()));
            assertEquals("45.00", val(l.getLineMemberLiabilityAmount()));
            assertEquals("1.00", val(l.getLineDiscountAmount()));
            assertEquals("9.00", val(l.getDrugCost()));
            assertEquals("2", val(l.getAllowedNumberOfUnits()));
        }

        @Test
        void submittedFallsBackToNetWhenNoSubmittedAdjudication() {
            ExplanationOfBenefit e = eob("professional");
            item(e, 1).setNet(money("150.00"));
            assertEquals("150.00", val(map(e).getClaimServiceLine().getFirst().getLineSubmittedAmount()));
        }

        @Test
        void submittedAdjudicationWinsOverNet() {
            ExplanationOfBenefit e = eob("professional");
            ExplanationOfBenefit.ItemComponent i = item(e, 1);
            i.setNet(money("150.00"));
            addAdj(i, ADJ, "submitted", "200.00");
            assertEquals("200.00", val(map(e).getClaimServiceLine().getFirst().getLineSubmittedAmount()));
        }

        @Test
        void absentAmountsStayNull() {
            ExplanationOfBenefit e = eob("professional");
            item(e, 1);
            PiqiClaimLine l = map(e).getClaimServiceLine().getFirst();
            assertNull(l.getLineAllowedAmount());
            assertNull(l.getLineCopayAmount());
            assertNull(l.getLinePaymentAmount());
            assertNull(l.getAllowedNumberOfUnits());
            assertNull(l.getLinePaymentDenialCode());
            assertNull(l.getPaymentMemberExplanation());
        }

        @Test
        void firstAdjudicationReasonBecomesDenialCode() {
            ExplanationOfBenefit e = eob("professional");
            ExplanationOfBenefit.ItemComponent i = item(e, 1);
            i.addAdjudication().setCategory(concept(ADJ, "deductible")).setAmount(money("30"))
                    .setReason(concept("denial", "CO-45"));
            i.addAdjudication().setCategory(concept(ADJ, "copay")).setAmount(money("10"))
                    .setReason(concept("denial", "PR-3"));

            assertEquals("CO-45", code(map(e).getClaimServiceLine().getFirst().getLinePaymentDenialCode()));
        }

        @Test
        void referencedProcessNotesBecomeMemberExplanation() {
            ExplanationOfBenefit e = eob("professional");
            e.addProcessNote().setNumber(1).setText("Note A");
            e.addProcessNote().setNumber(2).setText("Note B");
            e.addProcessNote().setNumber(3).setText("Unreferenced");
            ExplanationOfBenefit.ItemComponent i = item(e, 1);
            i.addNoteNumber(1);
            i.addNoteNumber(2);
            i.addNoteNumber(99); // no such note: ignored

            assertEquals("Note A Note B", val(map(e).getClaimServiceLine().getFirst().getPaymentMemberExplanation()));
        }

        @Test
        void oneLinePerItemInOrder() {
            ExplanationOfBenefit e = eob("professional");
            item(e, 1);
            item(e, 2);
            item(e, 3);
            List<PiqiClaimLine> lines = map(e).getClaimServiceLine();
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
            ExplanationOfBenefit e = eob("institutional");
            ExplanationOfBenefit.DiagnosisComponent d = e.addDiagnosis();
            d.setSequence(1);
            d.setDiagnosis(new CodeableConcept().addCoding(new Coding("icd10", "E11.9", "Type 2 diabetes")));
            d.addType().addCoding().setCode("principal");
            d.setOnAdmission(concept("poa", "Y"));

            var x = map(e).getClaimDiagnosis().getFirst();

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
            ExplanationOfBenefit e = eob("professional");
            ExplanationOfBenefit.DiagnosisComponent d = e.addDiagnosis();
            d.setSequence(1);
            d.setDiagnosis(new CodeableConcept().addCoding(new Coding("icd10", icd, null)));
            assertEquals(expected, val(map(e).getClaimDiagnosis().getFirst().geteCodeIndicator()));
        }

        @Test
        void diagnosisWithoutCodeHasNoECodeIndicator() {
            ExplanationOfBenefit e = eob("professional");
            ExplanationOfBenefit.DiagnosisComponent d = e.addDiagnosis();
            d.setSequence(1);
            assertNull(map(e).getClaimDiagnosis().getFirst().geteCodeIndicator());
        }

        @Test
        void mapsProcedureFields() {
            ExplanationOfBenefit e = eob("institutional");
            ExplanationOfBenefit.ProcedureComponent p = e.addProcedure();
            p.setSequence(1);
            p.setProcedure(concept("icd10pcs", "0BH17EZ"));
            p.setDateElement(new DateTimeType("2026-01-02"));
            p.addType().addCoding().setCode("primary");

            var x = map(e).getClaimProcedure().getFirst();

            assertEquals("0BH17EZ", code(x.getProcedureCode()));
            assertEquals("2026-01-02", val(x.getProcedureDate()));
            assertEquals("primary", code(x.getProcedureType()));
            assertNull(x.getProcedureClaimLine()); // null = header level
        }

        @Test
        void oneRowPerDiagnosisAndProcedure() {
            ExplanationOfBenefit e = eob("institutional");
            e.addDiagnosis().setSequence(1);
            e.addDiagnosis().setSequence(2);
            e.addProcedure().setSequence(1);
            PiqiPatient out = map(e);
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

        private PiqiPatient mapWith(ExplanationOfBenefit e, Map<String, Resource> dir) {
            return mapper.map(e, patient(), List.of(), ref -> dir.get(ref.getReference()));
        }

        @Test
        void billingProviderResolvedFromOrganization() {
            Organization o = org();
            o.setId("o1");
            ExplanationOfBenefit e = eob("professional");
            e.setProvider(new Reference("Organization/o1"));

            List<PiqiProvider> out = mapWith(e, directory(o)).getProvider();

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
            ExplanationOfBenefit e = eob("professional");
            ExplanationOfBenefit.CareTeamComponent ct = e.addCareTeam();
            ct.setSequence(1);
            ct.setProvider(new Reference("Practitioner/pr1"));
            ct.setRole(concept("careteamrole", "primary"));

            List<PiqiProvider> out = mapWith(e, directory(pr)).getProvider();

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
            ExplanationOfBenefit e = eob("professional");
            e.setProvider(new Reference("Organization/o1"));
            ExplanationOfBenefit.CareTeamComponent ct = e.addCareTeam();
            ct.setSequence(1);
            ct.setProvider(new Reference("Practitioner/pr1"));

            List<PiqiProvider> out = mapWith(e, directory(o, pr)).getProvider();

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

            ExplanationOfBenefit e = eob("professional");
            ExplanationOfBenefit.CareTeamComponent ct = e.addCareTeam();
            ct.setSequence(1);
            ct.setProvider(new Reference("PractitionerRole/r1"));
            ct.setRole(concept("careteamrole", "performing"));

            List<PiqiProvider> out = mapWith(e, directory(pr, role)).getProvider();

            assertEquals(1, out.size());
            assertEquals("9876543210", val(out.getFirst().getProviderNpi()));
            assertEquals("207R00000X", code(out.getFirst().getProviderType()));
            assertEquals("performing", code(out.getFirst().getCareTeamRole()));
        }

        @Test
        void unresolvedProvidersAreSkipped() {
            ExplanationOfBenefit e = eob("professional");
            e.setProvider(new Reference("Organization/missing"));
            ExplanationOfBenefit.CareTeamComponent ct = e.addCareTeam();
            ct.setSequence(1);
            ct.setProvider(new Reference("Practitioner/missing"));
            assertTrue(mapWith(e, directory()).getProvider().isEmpty());
        }

        @Test
        void unsupportedResourceTypeIsSkipped() {
            Patient notAProvider = new Patient();
            notAProvider.setId("x1");
            ExplanationOfBenefit e = eob("professional");
            e.setProvider(new Reference("Patient/x1"));
            assertTrue(mapWith(e, directory(notAProvider)).getProvider().isEmpty());
        }

        @Test
        void noResolverMeansNoProviders() {
            ExplanationOfBenefit e = eob("professional");
            e.setProvider(new Reference("Organization/o1"));
            assertTrue(mapper.map(e, patient()).getProvider().isEmpty());
        }

        @Test
        void npiIsOnlyTakenFromTheNpiSystem() {
            Organization o = new Organization();
            o.setId("o2");
            o.addIdentifier().setSystem("http://other").setValue("NOT-AN-NPI");
            ExplanationOfBenefit e = eob("professional");
            e.setProvider(new Reference("Organization/o2"));

            List<PiqiProvider> out = mapWith(e, directory(o)).getProvider();

            assertEquals(1, out.size());
            assertNull(out.getFirst().getProviderNpi());
        }
    }

    // ------------------------------------------------------------------
    // Whole-resource sanity check
    // ------------------------------------------------------------------

    @Test
    void listsAreNeverNullEvenForAnEmptyEob() {
        PiqiPatient out = map(new ExplanationOfBenefit());
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
