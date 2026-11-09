package com.navapbc.piqi.map.fhir.eob;

import com.navapbc.piqi.model.eob.*;
import org.hl7.fhir.r4.model.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Maps a FHIR R4 ExplanationOfBenefit (plus related resources) to a PIQI PAT_EOB_V1 PiqiPatient.
 * Assumes HAPI FHIR R4 and the generated classes from navapbc/piqi-model, and an EOB that
 * follows the CARIN Blue Button (C4BB) conventions for adjudication categories and
 * supportingInfo categories.
 * Verified against the published C4BB code systems (adjudication and supporting info type).
 * NOT verified: see the "UNVERIFIED" comments (dental supportingInfo, TOB digit split,
 * related-claim handling, linePaymentAmount source).
 */
public class R4EobToPiqiPatientMapper extends PiqiBaseR4Mapper {

    private static final String NPI = "http://hl7.org/fhir/sid/us-npi";
    private static final String US_CORE_BIRTHSEX =
            "http://hl7.org/fhir/us/core/StructureDefinition/us-core-birthsex";

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    /** Convenience overload: no coverages or providers are resolved. */
    public PiqiPatient map(ExplanationOfBenefit eob, Patient patient) {
        return map(eob, patient, List.of(), ref -> null);
    }

    /** Convenience overload: coverages supplied, providers are skipped (nothing can resolve them). */
    public PiqiPatient map(ExplanationOfBenefit eob, Patient patient, List<Coverage> coverages) {
        return map(eob, patient, coverages, ref -> null);
    }

    /**
     * @param coverages the Coverage resources the EOB references. If empty and a resolver is given,
     *                  they are resolved from eob.insurance[].coverage.
     * @param resolve   turns a FHIR Reference (Coverage, Practitioner, Organization, PractitionerRole)
     *                  into the resource. May return null; unresolved items are skipped.
     */
    public PiqiPatient map(ExplanationOfBenefit eob, Patient patient, List<Coverage> coverages,
                           Function<Reference, Resource> resolve) {
        Function<Reference, Resource> res = resolve == null ? ref -> null : resolve;

        PiqiPatient piqiPatient = new PiqiPatient();
        piqiPatient.setMember(mapMember(patient));

        List<Coverage> covs = new ArrayList<>(coverages == null ? List.of() : coverages);
        if (covs.isEmpty()) {
            for (ExplanationOfBenefit.InsuranceComponent ins : eob.getInsurance()) {
                if (res.apply(ins.getCoverage()) instanceof Coverage cv) {
                    covs.add(cv);
                }
            }
        }
        covs.forEach(cv -> piqiPatient.getCoverage().add(mapCoverage(cv)));

        String type = eob.getType().getCodingFirstRep().getCode();
        boolean pharmacy = "pharmacy".equals(type);
        Map<String, BigDecimal> totals = totals(eob);
        Map<Integer, String> notes = notes(eob);

        switch (type == null ? "" : type) {
            case "pharmacy" -> piqiPatient.getPharmacyClaim().addAll(mapPharmacyClaims(eob, totals));
            case "oral" -> eob.getItem().forEach(i -> piqiPatient.getDentalClaim().add(mapDentalClaim(eob, i)));
            default -> piqiPatient.getMedicalClaim().add(mapMedicalClaim(eob, totals));
        }

        eob.getItem().forEach(i -> piqiPatient.getClaimServiceLine().add(mapLine(eob, i, pharmacy, notes)));
        eob.getDiagnosis().forEach(d -> piqiPatient.getClaimDiagnosis().add(mapDiagnosis(d)));
        eob.getProcedure().forEach(p -> piqiPatient.getClaimProcedure().add(mapProcedure(p)));
        piqiPatient.getProvider().addAll(mapProviders(eob, res));
        return piqiPatient;
    }

    // ------------------------------------------------------------------
    // Member
    // ------------------------------------------------------------------

    private PiqiMember mapMember(Patient patient) {
        PiqiMember piqiMember = new PiqiMember();

        Identifier mb = patient.getIdentifier().stream()
                .filter(i -> i.getType().getCoding().stream().anyMatch(c -> "MB".equals(c.getCode())))
                .findFirst().orElse(null);
        if (mb != null) {
            piqiMember.setMemberId(simpleAttribute(mb.getValue()));
            piqiMember.setMemberIdentifierType(codeableConcept(mb.getType()));
        }
        piqiMember.setUniqueMemberId(simpleAttribute(patient.getIdElement().getIdPart()));
        piqiMember.setDateOfBirth(simpleAttribute(patient.getBirthDateElement().getValueAsString()));

        if (patient.hasDeceasedDateTimeType()) {
            piqiMember.setDateOfDeath(simpleAttribute(patient.getDeceasedDateTimeType().getValueAsString()));
            piqiMember.setDeceasedFlag(simpleAttribute("true"));
        } else if (patient.hasDeceasedBooleanType()) {
            piqiMember.setDeceasedFlag(simpleAttribute(String.valueOf(patient.getDeceasedBooleanType().booleanValue())));
        }

        Extension birthSex = patient.getExtensionByUrl(US_CORE_BIRTHSEX);
        if (birthSex != null && birthSex.getValue() instanceof CodeType ct) {
            piqiMember.setBirthSex(codeableConcept(null, ct.getValue()));
        }

        if (patient.hasAddress()) {
            Address a = patient.getAddressFirstRep();
            piqiMember.setStreetAddress(simpleAttribute(a.hasLine() ? a.getLine().getFirst().getValue() : null));
            piqiMember.setCity(simpleAttribute(a.getCity()));
            piqiMember.setCounty(simpleAttribute(a.getDistrict()));
            piqiMember.setState(simpleAttribute(a.getState()));
            piqiMember.setCountry(simpleAttribute(a.getCountry()));
            piqiMember.setPostalCode(simpleAttribute(a.getPostalCode()));
            piqiMember.setAddressStartDate(simpleAttribute(a.getPeriod().getStartElement().getValueAsString()));
            piqiMember.setAddressEndDate(simpleAttribute(a.getPeriod().getEndElement().getValueAsString()));
        }

        if (patient.hasTelecom()) {
            ContactPoint contactPoint = patient.getTelecomFirstRep();
            if (contactPoint.hasSystem()) {
                piqiMember.setTelecomSystem(codeableConcept("http://hl7.org/fhir/contact-point-system", contactPoint.getSystem().toCode()));
            }
            if (contactPoint.hasUse()) {
                piqiMember.setTelecomUse(codeableConcept("http://hl7.org/fhir/contact-point-use", contactPoint.getUse().toCode()));
            }
        }

        patient.getCommunication().stream()
                .filter(Patient.PatientCommunicationComponent::getPreferred).findFirst()
                .or(() -> patient.getCommunication().stream().findFirst())
                .ifPresent(c -> piqiMember.setPrimaryLanguage(codeableConcept(c.getLanguage())));

        // raceCode / ethnicity intentionally not mapped (nested US Core extensions)
        return piqiMember;
    }

    // ------------------------------------------------------------------
    // Coverage
    // ------------------------------------------------------------------

    private PiqiCoverage mapCoverage(Coverage coverage) {
        PiqiCoverage piqiCoverage = new PiqiCoverage();
        piqiCoverage.setSubscriberId(simpleAttribute(coverage.getSubscriberId()));
        piqiCoverage.setCoverageType(codeableConcept(coverage.getType()));
        if (coverage.hasStatus()) {
            piqiCoverage.setCoverageStatus(codeableConcept("http://hl7.org/fhir/fm-status", coverage.getStatus().toCode()));
        }
        piqiCoverage.setStartDate(simpleAttribute(coverage.getPeriod().getStartElement().getValueAsString()));
        piqiCoverage.setEndDate(simpleAttribute(coverage.getPeriod().getEndElement().getValueAsString()));

        for (Coverage.ClassComponent c : coverage.getClass_()) {
            String t = c.getType().getCodingFirstRep().getCode();
            if ("group".equals(t)) {
                piqiCoverage.setGroupId(simpleAttribute(c.getValue()));
                piqiCoverage.setGroupName(simpleAttribute(c.getName()));
            } else if ("plan".equals(t)) {
                piqiCoverage.setPlanIdentifier(simpleAttribute(c.getValue()));
                piqiCoverage.setPlanName(simpleAttribute(c.getName()));
            }
        }

        piqiCoverage.setRelationshipToSubscriber(codeableConcept(coverage.getRelationship()));

        if (coverage.hasPayor()) {
            Reference payor = coverage.getPayorFirstRep();
            piqiCoverage.setPayerIdentifier(simpleAttribute(refId(payor)));
            piqiCoverage.setClaimPayerName(simpleAttribute(payor.getDisplay()));
        }
        // otherPayerName: no direct source
        return piqiCoverage;
    }

    // ------------------------------------------------------------------
    // Medical claim header
    // ------------------------------------------------------------------

    private PiqiMedicalClaim mapMedicalClaim(ExplanationOfBenefit eob, Map<String, BigDecimal> totals) {
        PiqiMedicalClaim piqiMedicalClaim = new PiqiMedicalClaim();

        piqiMedicalClaim.setClaimTypeCode(codeableConcept(eob.getType()));
        piqiMedicalClaim.setClaimSubType(codeableConcept(eob.getSubType()));
        if (eob.hasUse()) {
            piqiMedicalClaim.setClaimUse(codeableConcept("http://hl7.org/fhir/claim-use", eob.getUse().toCode()));
        }
        if (eob.hasOutcome()) {
            piqiMedicalClaim.setProcessingStatusCode(codeableConcept("http://hl7.org/fhir/remittance-outcome", eob.getOutcome().toCode()));
        }

        // C4BB treats EOB.created as the adjudication date (judgment call)
        piqiMedicalClaim.setAdjudicationDate(simpleAttribute(eob.getCreatedElement().getValueAsString()));
        piqiMedicalClaim.setClaimLastUpdateDate(simpleAttribute(eob.getMeta().getLastUpdatedElement().getValueAsString()));

        Period p = eob.getBillablePeriod();
        piqiMedicalClaim.setClaimStartDate(simpleAttribute(p.getStartElement().getValueAsString()));
        piqiMedicalClaim.setClaimServiceEndDate(simpleAttribute(p.getEndElement().getValueAsString()));
        piqiMedicalClaim.setStatementFromDate(simpleAttribute(p.getStartElement().getValueAsString()));
        piqiMedicalClaim.setStatementThruDate(simpleAttribute(p.getEndElement().getValueAsString()));

        Identifier id = claimIdentifier(eob);
        if (id != null) {
            piqiMedicalClaim.setPayerClaimUniqueIdentifier(simpleAttribute(id.getValue()));
            piqiMedicalClaim.setClaimIdentifierType(simpleAttribute(id.getType().getCodingFirstRep().getCode()));
        }
        piqiMedicalClaim.setClaimPayerIdentifier(simpleAttribute(refId(eob.getInsurer())));

        if (eob.hasPayee()) {
            piqiMedicalClaim.setClaimPayeeTypeCode(codeableConcept(eob.getPayee().getType()));
            piqiMedicalClaim.setClaimPayee(simpleAttribute(eob.getPayee().getParty().getReference()));
        }

        // UNVERIFIED: related-claim handling. Only a "prior" relationship is mapped to adjustedFrom.
        if (eob.hasRelated()) {
            ExplanationOfBenefit.RelatedClaimComponent r = eob.getRelatedFirstRep();
            piqiMedicalClaim.setClaimAdjustedRelationship(codeableConcept(r.getRelationship()));
            if ("prior".equals(r.getRelationship().getCodingFirstRep().getCode())) {
                piqiMedicalClaim.setClaimAdjustedfromIdentifier(simpleAttribute(r.hasReference()
                        ? r.getReference().getValue() : r.getClaim().getReferenceElement().getIdPart()));
            }
        }

        // supportingInfo (C4BB categories)
        ExplanationOfBenefit.SupportingInformationComponent s;
        if ((s = info(eob, "clmrecvddate")) != null && s.hasTimingDateType()) {
            piqiMedicalClaim.setClaimReceivedDate(simpleAttribute(s.getTimingDateType().getValueAsString()));
        }
        if ((s = info(eob, "admissionperiod")) != null && s.hasTimingPeriod()) {
            piqiMedicalClaim.setAdmissionDate(simpleAttribute(s.getTimingPeriod().getStartElement().getValueAsString()));
            piqiMedicalClaim.setDischargedate(simpleAttribute(s.getTimingPeriod().getEndElement().getValueAsString()));
        }
        if ((s = info(eob, "discharge-status")) != null && s.hasCode()) {
            piqiMedicalClaim.setPatientDischargeStatusCode(codeableConcept(s.getCode()));
        }
        if ((s = info(eob, "admtype")) != null && s.hasCode()) {
            piqiMedicalClaim.setInpatientAdmissionTypeCode(codeableConcept(s.getCode()));
        }
        if ((s = info(eob, "pointoforigin")) != null && s.hasCode()) {
            piqiMedicalClaim.setInpatientSourceAdmissionCode(codeableConcept(s.getCode()));
        }
        if ((s = info(eob, "typeofbill")) != null && s.hasCode()) {
            piqiMedicalClaim.setBillFacilityTypeCode(codeableConcept(s.getCode()));
            // UNVERIFIED: splitting the 3-digit TOB per the schema descriptions
            // (digit 2 = service classification, digit 3 = frequency). System left null.
            String tob = s.getCode().getCodingFirstRep().getCode();
            if (tob != null) {
                String d = (tob.length() == 4 && tob.startsWith("0")) ? tob.substring(1) : tob;
                if (d.matches("\\d{3}")) {
                    piqiMedicalClaim.setServiceClassificationTypeCode(codeableConcept(null, d.substring(1, 2)));
                    piqiMedicalClaim.setFrequencyCode(codeableConcept(null, d.substring(2, 3)));
                }
            }
        }
        piqiMedicalClaim.setMedicalRecordNumber(simpleAttribute(infoText(info(eob, "medicalrecordnumber"))));
        piqiMedicalClaim.setPatientAccountNumber(simpleAttribute(infoText(info(eob, "patientaccountnumber"))));

        // DRG: supportingInfo "drg" first, else a diagnosis packageCode
        CodeableConcept drg;
        if ((s = info(eob, "drg")) != null && s.hasCode()) {
            drg = s.getCode();
        } else {
            drg = eob.getDiagnosis().stream()
                    .filter(ExplanationOfBenefit.DiagnosisComponent::hasPackageCode)
                    .map(ExplanationOfBenefit.DiagnosisComponent::getPackageCode)
                    .findFirst().orElse(null);
        }
        if (drg != null) {
            piqiMedicalClaim.setClaimDrgCode(codeableConcept(drg));
            Coding k = drg.getCodingFirstRep();
            piqiMedicalClaim.setClaimDrgVersion(simpleAttribute(k.hasVersion() ? k.getVersion() : k.getSystem()));
        }

        // Payment
        if (eob.hasPayment()) {
            piqiMedicalClaim.setClaimPaidDate(simpleAttribute(eob.getPayment().getDateElement().getValueAsString()));
            piqiMedicalClaim.setClaimPaymentAmount(simpleAttribute(eob.getPayment().getAmount().getValue()));
        }
        if (piqiMedicalClaim.getClaimPaymentAmount() == null) {
            piqiMedicalClaim.setClaimPaymentAmount(simpleAttribute(totals.get("benefit"))); // fallback
        }

        // Totals (EOB.total, falling back to header-level adjudication)
        piqiMedicalClaim.setClaimTotalSubmittedAmount(simpleAttribute(totals.get("submitted")));
        piqiMedicalClaim.setClaimTotalAllowedAmount(simpleAttribute(totals.get("eligible")));
        piqiMedicalClaim.setAmountPaidByPatient(simpleAttribute(totals.get("paidbypatient")));
        piqiMedicalClaim.setClaimAmountPaidToProvider(simpleAttribute(totals.get("paidtoprovider")));
        piqiMedicalClaim.setMemberReimbursement(simpleAttribute(totals.get("paidtopatient")));
        piqiMedicalClaim.setClaimNonCoveredAmount(simpleAttribute(totals.get("noncovered")));
        piqiMedicalClaim.setMemberPaidDeductible(simpleAttribute(totals.get("deductible")));
        piqiMedicalClaim.setCoInsuranceLiabilityAmount(simpleAttribute(totals.get("coinsurance")));
        piqiMedicalClaim.setCopayAmount(simpleAttribute(totals.get("copay")));
        piqiMedicalClaim.setMemberLiability(simpleAttribute(totals.get("memberliability")));
        piqiMedicalClaim.setClaimOtherPayerPaidAmount(simpleAttribute(totals.get("priorpayerpaid")));
        piqiMedicalClaim.setClaimDiscountAmount(simpleAttribute(totals.get("discount")));

        // Header-level denial reason, if any
        for (ExplanationOfBenefit.AdjudicationComponent a : eob.getAdjudication()) {
            if (a.hasReason()) {
                piqiMedicalClaim.setPaymentDenialCode(codeableConcept(a.getReason()));
                break;
            }
        }

        // Not mapped: claimPaymentStatusCode (paid/denied is an inference), primaryPayerIdentifier,
        // procedureCodeType, totalAmount + adjudicationAmountType (generic pair; specific fields used).
        return piqiMedicalClaim;
    }

    // ------------------------------------------------------------------
    // Pharmacy claim header (one row per total category)
    // ------------------------------------------------------------------

    private List<PiqiPharmacyClaim> mapPharmacyClaims(ExplanationOfBenefit eob, Map<String, BigDecimal> totals) {
        List<PiqiPharmacyClaim> out = new ArrayList<>();
        if (totals.isEmpty()) {
            out.add(pharmacyBase(eob));
            return out;
        }
        // The schema says "total amount for each category", so emit one row per category
        // (the pharmacy attributes repeat on each row). Collapse to one row if you prefer.
        for (Map.Entry<String, BigDecimal> e : totals.entrySet()) {
            PiqiPharmacyClaim x = pharmacyBase(eob);
            x.setTotalAmount(simpleAttribute(e.getValue()));
            x.setAdjudicationAmountType(simpleAttribute(e.getKey()));
            out.add(x);
        }
        return out;
    }

    private PiqiPharmacyClaim pharmacyBase(ExplanationOfBenefit eob) {
        PiqiPharmacyClaim x = new PiqiPharmacyClaim();
        x.setDaysSupply(simpleAttribute(infoText(info(eob, "dayssupply"))));
        x.setRefillNumber(simpleAttribute(infoText(info(eob, "refillnum"))));
        x.setDawCode(simpleAttribute(infoText(info(eob, "dawcode"))));
        x.setPrescriptionOriginCode(simpleAttribute(infoText(info(eob, "rxorigincode"))));
        ExplanationOfBenefit.SupportingInformationComponent bg = info(eob, "brandgenericindicator");
        if (bg != null && bg.hasCode()) {
            x.setPlanReportedBrandGenericCode(codeableConcept(bg.getCode()));
        }
        Identifier id = claimIdentifier(eob);
        if (id != null) {
            x.setRxServiceReferenceNumber(simpleAttribute(id.getValue()));
        }
        return x;
    }

    // ------------------------------------------------------------------
    // Dental claim (one per EOB item)
    // ------------------------------------------------------------------

    private PiqiDentalClaim mapDentalClaim(ExplanationOfBenefit eob, ExplanationOfBenefit.ItemComponent i) {
        PiqiDentalClaim d = new PiqiDentalClaim();
        List<ExplanationOfBenefit.SupportingInformationComponent> infos = linkedInfo(eob, i);

        d.setProcedureCode(codeableConcept(i.getProductOrService()));
        if (i.hasModifier()) {
            d.setProcedureModifierCode(codeableConcept(i.getModifierFirstRep()));
        }
        if (i.hasBodySite()) {
            d.setToothNumberFirstOccurrence(codeableConcept(i.getBodySite()));
        }
        if (i.hasSubSite()) {
            CodeableConcept surfaces = new CodeableConcept();
            i.getSubSite().forEach(s -> s.getCoding().forEach(surfaces::addCoding));
            d.setToothSurface(codeableConcept(surfaces));
        }

        // "additionalbodysite" is a verified C4BB category: teeth after the first
        CodeableConcept extra = new CodeableConcept();
        infos.stream().filter(s -> hasCategory(s, "additionalbodysite") && s.hasCode())
                .forEach(s -> s.getCode().getCoding().forEach(extra::addCoding));
        d.setToothNumberAfterFirstOccurrence(codeableConcept(extra));

        // UNVERIFIED category code for missing teeth (prefix match on "missingtooth")
        List<CodeableConcept> missing = infos.stream()
                .filter(s -> s.getCategory().getCoding().stream().anyMatch(c ->
                        c.getCode() != null && c.getCode().toLowerCase().startsWith("missingtooth")))
                .filter(ExplanationOfBenefit.SupportingInformationComponent::hasCode)
                .map(ExplanationOfBenefit.SupportingInformationComponent::getCode)
                .toList();
        if (!missing.isEmpty()) {
            d.setMissingToothNumberFirstOccurrence(codeableConcept(missing.getFirst()));
        }
        if (missing.size() > 1) {
            CodeableConcept rest = new CodeableConcept();
            missing.stream().skip(1).forEach(m -> m.getCoding().forEach(rest::addCoding));
            d.setMissingToothNumberAfterFirstOccurrence(codeableConcept(rest));
        }

        // UNVERIFIED: how orthodontics and prosthesis data are carried (categories are verified,
        // the use of timing/value is a guess)
        ExplanationOfBenefit.SupportingInformationComponent ortho = firstInfo(infos, "orthodontics");
        if (ortho != null) {
            d.setOrthodonticsTreatmentIndicator(simpleAttribute("true"));
            if (ortho.hasTimingDateType()) {
                d.setOrthodonticsApplianceApplicationDate(simpleAttribute(ortho.getTimingDateType().getValueAsString()));
            }
            if (ortho.hasValueQuantity()) {
                d.setTotalNumberOfMonthsForOrthodontia(simpleAttribute(ortho.getValueQuantity().getValue()));
            }
        }
        ExplanationOfBenefit.SupportingInformationComponent pros = firstInfo(infos, "prosthesis");
        if (pros != null) {
            d.setProsthesisReplacementIndicator(simpleAttribute("true"));
            if (pros.hasTimingDateType()) {
                d.setDateOfPriorProsthesisPlacement(simpleAttribute(pros.getTimingDateType().getValueAsString()));
            }
        }
        return d;
    }

    // ------------------------------------------------------------------
    // Lines, diagnoses, procedures
    // ------------------------------------------------------------------

    private PiqiClaimLine mapLine(ExplanationOfBenefit eob, ExplanationOfBenefit.ItemComponent i,
                                  boolean pharmacy, Map<Integer, String> notes) {
        PiqiClaimLine l = new PiqiClaimLine();
        l.setLineNumber(simpleAttribute(String.valueOf(i.getSequence())));

        if (i.hasServicedDateType()) {
            l.setServiceDate(simpleAttribute(i.getServicedDateType().getValueAsString()));
        }
        if (i.hasServicedPeriod()) {
            l.setServiceDate(simpleAttribute(i.getServicedPeriod().getStartElement().getValueAsString()));
            l.setServiceToDate(simpleAttribute(i.getServicedPeriod().getEndElement().getValueAsString()));
        }

        l.setTypeOfService(codeableConcept(i.getCategory()));
        if (pharmacy) {
            l.setNationalDrugCode(codeableConcept(i.getProductOrService()));
            l.setQuantityDispensed(simpleAttribute(i.getQuantity().getValue()));
            if (i.getQuantity().hasCode()) {
                l.setQuantityQualifierCode(codeableConcept("http://unitsofmeasure.org", i.getQuantity().getCode()));
            }
            ExplanationOfBenefit.SupportingInformationComponent cmp = info(eob, "compoundcode");
            if (cmp != null && cmp.hasCode()) {
                l.setCompoundCode(codeableConcept(cmp.getCode()));
            }
        } else {
            l.setLineProcedureCode(codeableConcept(i.getProductOrService()));
        }

        if (i.hasModifier()) { // the schema allows only one modifier per line
            l.setLineProcedureCodeModifier(codeableConcept(i.getModifierFirstRep()));
        }
        l.setRevenueCenterCode(codeableConcept(i.getRevenue()));
        if (i.hasLocationCodeableConcept()) {
            l.setPlaceOfServiceCode(codeableConcept(i.getLocationCodeableConcept()));
        }

        // Adjudication amounts (C4BB categories)
        Map<String, BigDecimal> a = new HashMap<>();
        i.getAdjudication().forEach(x -> putAdj(a, x));
        l.setLineSubmittedAmount(simpleAttribute(a.containsKey("submitted") ? a.get("submitted") : i.getNet().getValue()));
        l.setLineAllowedAmount(simpleAttribute(a.get("eligible")));
        l.setLineCopayAmount(simpleAttribute(a.get("copay")));
        l.setLineCoinsuranceAmount(simpleAttribute(a.get("coinsurance")));
        l.setLinePatientDeductible(simpleAttribute(a.get("deductible")));
        l.setLineNoncoveredAmount(simpleAttribute(a.get("noncovered")));
        l.setLineOtherPayerPaidAmount(simpleAttribute(a.get("priorpayerpaid")));
        l.setLineAmountPaidByPatient(simpleAttribute(a.get("paidbypatient")));
        l.setLineAmountPaidToProvider(simpleAttribute(a.get("paidtoprovider")));
        l.setLineMemberReimbursement(simpleAttribute(a.get("paidtopatient")));
        l.setLineMemberLiabilityAmount(simpleAttribute(a.get("memberliability")));
        l.setLineDiscountAmount(simpleAttribute(a.get("discount")));
        l.setDrugCost(simpleAttribute(a.get("drugcost")));
        l.setAllowedNumberOfUnits(simpleAttribute(a.get("allowedunits")));
        // UNVERIFIED: "benefit" is the closest standard category to "amount sent to the payee"
        l.setLinePaymentAmount(simpleAttribute(a.get("benefit")));

        for (ExplanationOfBenefit.AdjudicationComponent adj : i.getAdjudication()) {
            if (adj.hasReason()) {
                l.setLinePaymentDenialCode(codeableConcept(adj.getReason()));
                break;
            }
        }

        String expl = i.getNoteNumber().stream()
                .map(n -> notes.get(n.getValue()))
                .filter(t -> t != null && !t.isBlank())
                .collect(Collectors.joining(" "));
        l.setPaymentMemberExplanation(simpleAttribute(expl));

        // benefitPaymentStatus (in/out of network): no confident source
        return l;
    }

    private PiqiClaimDiagnosis mapDiagnosis(ExplanationOfBenefit.DiagnosisComponent d) {
        PiqiClaimDiagnosis x = new PiqiClaimDiagnosis();
        String code = null;
        if (d.hasDiagnosisCodeableConcept()) {
            x.setDiagnosisCode(codeableConcept(d.getDiagnosisCodeableConcept()));
            code = d.getDiagnosisCodeableConcept().getCodingFirstRep().getCode();
        }
        x.setDiagnosisType(codeableConcept(d.getTypeFirstRep()));
        x.setPresentOnAdmissionIndicator(simpleAttribute(d.getOnAdmission().getCodingFirstRep().getCode()));
        if (code != null) {
            // E-codes are ICD-10 V00 through Y99. Value format ("true"/"false") is a guess.
            x.seteCodeIndicator(simpleAttribute(String.valueOf(code.matches("(?i)^[V-Y]\\d.*"))));
        }
        return x;
    }

    private PiqiClaimProcedure mapProcedure(ExplanationOfBenefit.ProcedureComponent p) {
        PiqiClaimProcedure x = new PiqiClaimProcedure();
        if (p.hasProcedureCodeableConcept()) {
            x.setProcedureCode(codeableConcept(p.getProcedureCodeableConcept()));
        }
        x.setProcedureDate(simpleAttribute(p.getDateElement().getValueAsString()));
        x.setProcedureType(codeableConcept(p.getTypeFirstRep()));
        // procedureClaimLine left null = header-level, per the schema description
        return x;
    }

    // ------------------------------------------------------------------
    // Providers
    // ------------------------------------------------------------------

    private List<PiqiProvider> mapProviders(ExplanationOfBenefit eob, Function<Reference, Resource> resolve) {
        List<PiqiProvider> out = new ArrayList<>();

        if (eob.hasProvider()) { // billing provider
            PiqiProvider p = mapProvider(resolve.apply(eob.getProvider()),
                    new CodeableConcept().setText("billing provider"), resolve);
            if (p != null) {
                out.add(p);
            }
        }
        for (ExplanationOfBenefit.CareTeamComponent ct : eob.getCareTeam()) {
            PiqiProvider p = mapProvider(resolve.apply(ct.getProvider()), ct.getRole(), resolve);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    private PiqiProvider mapProvider(Resource r, CodeableConcept role, Function<Reference, Resource> resolve) {
        if (r == null) {
            return null;
        }
        PiqiProvider p = new PiqiProvider();
        p.setCareTeamRole(codeableConcept(role));

        switch (r) {
            case Practitioner pr -> {
                p.setProviderNpi(simpleAttribute(npi(pr.getIdentifier())));
                if (pr.hasName()) {
                    p.setProviderName(simpleAttribute(pr.getNameFirstRep().getNameAsSingleString()));
                }
                p.setProviderAddress(simpleAttribute(addr(pr.getAddressFirstRep())));
                p.setProviderType(codeableConcept(pr.getQualificationFirstRep().getCode()));
            }
            case Organization org -> {
                p.setProviderNpi(simpleAttribute(npi(org.getIdentifier())));
                p.setProviderName(simpleAttribute(org.getName()));
                p.setProviderAddress(simpleAttribute(addr(org.getAddressFirstRep())));
                p.setProviderType(codeableConcept(org.getTypeFirstRep()));
            }
            case PractitionerRole prole -> {
                PiqiProvider base = mapProvider(resolve.apply(prole.getPractitioner()), role, resolve);
                if (base == null) {
                    return null;
                }
                base.setProviderType(codeableConcept(prole.getSpecialtyFirstRep()));
                return base;
            }
            default -> {
                return null;
            }
        }
        // providerContractingStatus, siteOfServiceNetworkStatus, providerPayerSpecificIdentifier: not mapped
        return p;
    }

    private static String npi(List<Identifier> ids) {
        return ids.stream().filter(i -> NPI.equals(i.getSystem()))
                .map(Identifier::getValue).findFirst().orElse(null);
    }

    private static String addr(Address a) {
        if (a == null || a.isEmpty()) {
            return null;
        }
        if (a.hasText()) {
            return a.getText();
        }
        String lines = a.getLine().stream().map(StringType::getValue).collect(Collectors.joining(" "));
        return Stream.of(lines, a.getCity(), a.getState(), a.getPostalCode())
                .filter(s -> s != null && !s.isBlank())
                .collect(Collectors.joining(", "));
    }

    // ------------------------------------------------------------------
    // EOB helpers: adjudication, notes, identifiers, supportingInfo
    // ------------------------------------------------------------------

    /** Header-level adjudication first, then EOB.total overrides. Keyed by category code. */
    private static Map<String, BigDecimal> totals(ExplanationOfBenefit eob) {
        Map<String, BigDecimal> m = new LinkedHashMap<>();
        eob.getAdjudication().forEach(a -> putAdj(m, a));
        for (ExplanationOfBenefit.TotalComponent t : eob.getTotal()) {
            BigDecimal v = t.getAmount().getValue();
            if (v == null) {
                continue;
            }
            for (Coding k : t.getCategory().getCoding()) {
                if (k.getCode() != null) {
                    m.put(k.getCode(), v);
                }
            }
        }
        return m;
    }

    private static void putAdj(Map<String, BigDecimal> m, ExplanationOfBenefit.AdjudicationComponent a) {
        BigDecimal v = a.hasAmount() ? a.getAmount().getValue() : (a.hasValue() ? a.getValue() : null);
        if (v == null) {
            return;
        }
        for (Coding k : a.getCategory().getCoding()) {
            if (k.getCode() != null) {
                m.putIfAbsent(k.getCode(), v);
            }
        }
    }

    private static Map<Integer, String> notes(ExplanationOfBenefit eob) {
        Map<Integer, String> m = new HashMap<>();
        for (ExplanationOfBenefit.NoteComponent n : eob.getProcessNote()) {
            m.put(n.getNumber(), n.getText());
        }
        return m;
    }

    /** Prefer the identifier typed "uc" (unique claim id), else the first identifier. */
    private static Identifier claimIdentifier(ExplanationOfBenefit eob) {
        return eob.getIdentifier().stream()
                .filter(i -> i.getType().getCoding().stream().anyMatch(c -> "uc".equals(c.getCode())))
                .findFirst()
                .orElse(eob.hasIdentifier() ? eob.getIdentifierFirstRep() : null);
    }

    private static String refId(Reference r) {
        if (r == null) {
            return null;
        }
        return r.hasIdentifier() ? r.getIdentifier().getValue() : r.getReferenceElement().getIdPart();
    }

    private static boolean hasCategory(ExplanationOfBenefit.SupportingInformationComponent s, String code) {
        return s.getCategory().getCoding().stream().anyMatch(c -> code.equalsIgnoreCase(c.getCode()));
    }

    private static ExplanationOfBenefit.SupportingInformationComponent info(ExplanationOfBenefit eob, String code) {
        return firstInfo(eob.getSupportingInfo(), code);
    }

    private static ExplanationOfBenefit.SupportingInformationComponent firstInfo(
            List<ExplanationOfBenefit.SupportingInformationComponent> list, String code) {
        return list.stream().filter(s -> hasCategory(s, code)).findFirst().orElse(null);
    }

    /** supportingInfo linked to an item via informationSequence; all claim-level info if none are linked. */
    private static List<ExplanationOfBenefit.SupportingInformationComponent> linkedInfo(
            ExplanationOfBenefit eob, ExplanationOfBenefit.ItemComponent i) {
        Set<Integer> seqs = i.getInformationSequence().stream()
                .map(PositiveIntType::getValue).collect(Collectors.toSet());
        if (seqs.isEmpty()) {
            return eob.getSupportingInfo();
        }
        return eob.getSupportingInfo().stream().filter(s -> seqs.contains(s.getSequence())).toList();
    }

    private static String infoText(ExplanationOfBenefit.SupportingInformationComponent s) {
        if (s == null) {
            return null;
        }
        if (s.hasValueQuantity()) {
            return s.getValueQuantity().getValue().toPlainString();
        }
        if (s.hasValueStringType()) {
            return s.getValueStringType().getValue();
        }
        if (s.hasValueBooleanType()) {
            return String.valueOf(s.getValueBooleanType().booleanValue());
        }
        if (s.hasCode()) {
            return s.getCode().getCodingFirstRep().getCode();
        }
        return null;
    }

    // ------------------------------------------------------------------
    // PIQI value helpers
    // ------------------------------------------------------------------

    private static PiqiSimpleAttribute simpleAttribute(String v) {
        return (v == null || v.isBlank()) ? null : new PiqiSimpleAttribute(v);
    }

    private static PiqiSimpleAttribute simpleAttribute(BigDecimal v) {
        return v == null ? null : new PiqiSimpleAttribute(v.toPlainString());
    }

    /** Returns null for a null or empty concept, so the field is omitted from the JSON. */
    private static PiqiCodeableConcept codeableConcept(CodeableConcept c) {
        if (c == null || c.isEmpty()) {
            return null;
        }
        PiqiCodeableConcept piqiCodeableConcept = new PiqiCodeableConcept();
        String text = c.hasText() ? c.getText() : c.getCodingFirstRep().getDisplay();
        piqiCodeableConcept.setText(simpleAttribute(text));
        for (Coding k : c.getCoding()) {
            piqiCodeableConcept.getCodings().add(coding(k));
        }
        return piqiCodeableConcept;
    }

    /** For fixed-value enums where FHIR gives a bare code (use, outcome, status, ...). */
    private static PiqiCodeableConcept codeableConcept(String system, String code) {
        return codeableConcept(new CodeableConcept().addCoding(new Coding().setSystem(system).setCode(code)));
    }

    private static PiqiCoding coding(Coding coding) {
        PiqiCoding piqiCoding = new PiqiCoding();
        piqiCoding.setSystem(simpleAttribute(coding.getSystem()));
        piqiCoding.setCode(simpleAttribute(coding.getCode()));
        piqiCoding.setDisplay(simpleAttribute(coding.getDisplay()));
        return piqiCoding;
    }
}
