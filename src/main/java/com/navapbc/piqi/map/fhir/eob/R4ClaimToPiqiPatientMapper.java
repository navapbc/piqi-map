package com.navapbc.piqi.map.fhir.eob;

import com.navapbc.piqi.model.eob.*;
import org.hl7.fhir.r4.model.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Maps a FHIR R4 Claim (plus related resources) to a PIQI PAT_EOB_V1 PiqiPatient.
 * Assumes HAPI FHIR R4 and the generated classes from navapbc/piqi-model.
 * Fields that live on ExplanationOfBenefit (paid, allowed, copay, denial, adjudication dates)
 * are NOT populated because a Claim does not carry them.
 * UNVERIFIED: the supportingInfo category codes used for pharmacy and dental data
 * (see mapPharmacyClaim / mapDentalClaim) are from memory of the CARIN Blue Button profile.
 */
public class R4ClaimToPiqiPatientMapper extends PiqiBaseR4Mapper {

    private static final String NPI = "http://hl7.org/fhir/sid/us-npi";
    private static final String US_CORE_BIRTHSEX =
            "http://hl7.org/fhir/us/core/StructureDefinition/us-core-birthsex";

    // ------------------------------------------------------------------
    // Entry points
    // ------------------------------------------------------------------

    /** Convenience overload: providers are skipped because nothing can resolve the references. */
    public PiqiPatient map(Claim claim, Patient patient, List<Coverage> coverages) {
        return map(claim, patient, coverages, ref -> null);
    }

    /**
     * @param resolve turns a FHIR Reference (Practitioner, Organization, PractitionerRole) into the
     *                resource. May return null; unresolved providers are skipped.
     */
    public PiqiPatient map(Claim claim, Patient patient, List<Coverage> coverages,
                           Function<Reference, Resource> resolve) {
        PiqiPatient out = new PiqiPatient();
        out.setMember(mapMember(patient));
        coverages.forEach(cv -> out.getCoverage().add(mapCoverage(cv)));

        String type = claim.getType().getCodingFirstRep().getCode();
        boolean pharmacy = "pharmacy".equals(type);
        switch (type == null ? "" : type) {
            case "pharmacy" -> out.getPharmacyClaim().add(mapPharmacyClaim(claim));
            case "oral" -> claim.getItem().forEach(i -> out.getDentalClaim().add(mapDentalClaim(claim, i)));
            default -> out.getMedicalClaim().add(mapMedicalClaim(claim));
        }

        claim.getItem().forEach(i -> out.getClaimServiceLine().add(mapLine(i, pharmacy)));
        claim.getDiagnosis().forEach(d -> out.getClaimDiagnosis().add(mapDiagnosis(d)));
        claim.getProcedure().forEach(p -> out.getClaimProcedure().add(mapProcedure(p)));
        out.getProvider().addAll(mapProviders(claim, resolve));
        return out;
    }

    // ------------------------------------------------------------------
    // Member
    // ------------------------------------------------------------------

    private PiqiMember mapMember(Patient p) {
        PiqiMember m = new PiqiMember();

        // Member ID: prefer the identifier typed "MB" (member number)
        Identifier mb = p.getIdentifier().stream()
                .filter(i -> i.getType().getCoding().stream().anyMatch(c -> "MB".equals(c.getCode())))
                .findFirst().orElse(null);
        if (mb != null) {
            m.setMemberId(sa(mb.getValue()));
            m.setMemberIdentifierType(cc(mb.getType()));
        }
        m.setUniqueMemberId(sa(p.getIdElement().getIdPart()));

        m.setDateOfBirth(sa(p.getBirthDateElement().getValueAsString()));

        if (p.hasDeceasedDateTimeType()) {
            m.setDateOfDeath(sa(p.getDeceasedDateTimeType().getValueAsString()));
            m.setDeceasedFlag(sa("true"));
        } else if (p.hasDeceasedBooleanType()) {
            m.setDeceasedFlag(sa(String.valueOf(p.getDeceasedBooleanType().booleanValue())));
        }

        // Birth sex comes from the US Core extension, not Patient.gender
        Extension bs = p.getExtensionByUrl(US_CORE_BIRTHSEX);
        if (bs != null && bs.getValue() instanceof CodeType ct) {
            m.setBirthSex(cc(null, ct.getValue()));
        }

        if (p.hasAddress()) {
            Address a = p.getAddressFirstRep();
            m.setStreetAddress(sa(a.hasLine() ? a.getLine().getFirst().getValue() : null));
            m.setCity(sa(a.getCity()));
            m.setCounty(sa(a.getDistrict()));
            m.setState(sa(a.getState()));
            m.setCountry(sa(a.getCountry()));
            m.setPostalCode(sa(a.getPostalCode()));
            m.setAddressStartDate(sa(a.getPeriod().getStartElement().getValueAsString()));
            m.setAddressEndDate(sa(a.getPeriod().getEndElement().getValueAsString()));
        }

        if (p.hasTelecom()) {
            ContactPoint t = p.getTelecomFirstRep();
            if (t.hasSystem()) {
                m.setTelecomSystem(cc("http://hl7.org/fhir/contact-point-system", t.getSystem().toCode()));
            }
            if (t.hasUse()) {
                m.setTelecomUse(cc("http://hl7.org/fhir/contact-point-use", t.getUse().toCode()));
            }
        }

        p.getCommunication().stream()
                .filter(Patient.PatientCommunicationComponent::getPreferred).findFirst()
                .or(() -> p.getCommunication().stream().findFirst())
                .ifPresent(c -> m.setPrimaryLanguage(cc(c.getLanguage())));

        // raceCode / ethnicity intentionally not mapped (nested US Core extensions)
        return m;
    }

    // ------------------------------------------------------------------
    // Coverage
    // ------------------------------------------------------------------

    private PiqiCoverage mapCoverage(Coverage cv) {
        PiqiCoverage x = new PiqiCoverage();
        x.setSubscriberId(sa(cv.getSubscriberId()));
        x.setCoverageType(cc(cv.getType()));
        if (cv.hasStatus()) {
            x.setCoverageStatus(cc("http://hl7.org/fhir/fm-status", cv.getStatus().toCode()));
        }
        x.setStartDate(sa(cv.getPeriod().getStartElement().getValueAsString()));
        x.setEndDate(sa(cv.getPeriod().getEndElement().getValueAsString()));

        // Group and plan live in Coverage.class, keyed by the class type code
        for (Coverage.ClassComponent c : cv.getClass_()) {
            String t = c.getType().getCodingFirstRep().getCode();
            if ("group".equals(t)) {
                x.setGroupId(sa(c.getValue()));
                x.setGroupName(sa(c.getName()));
            } else if ("plan".equals(t)) {
                x.setPlanIdentifier(sa(c.getValue()));
                x.setPlanName(sa(c.getName()));
            }
        }

        x.setRelationshipToSubscriber(cc(cv.getRelationship()));

        if (cv.hasPayor()) {
            Reference payor = cv.getPayorFirstRep();
            x.setPayerIdentifier(sa(payor.hasIdentifier()
                    ? payor.getIdentifier().getValue()
                    : payor.getReferenceElement().getIdPart()));
            x.setClaimPayerName(sa(payor.getDisplay()));
        }
        // otherPayerName: no direct Claim/Coverage source (COB data on an EOB)
        return x;
    }

    // ------------------------------------------------------------------
    // Medical claim header
    // ------------------------------------------------------------------

    private PiqiMedicalClaim mapMedicalClaim(Claim c) {
        PiqiMedicalClaim m = new PiqiMedicalClaim();
        m.setClaimTypeCode(cc(c.getType()));
        m.setClaimSubType(cc(c.getSubType()));
        if (c.hasUse()) {
            m.setClaimUse(cc("http://hl7.org/fhir/claim-use", c.getUse().toCode()));
        }

        Period p = c.getBillablePeriod();
        m.setClaimStartDate(sa(p.getStartElement().getValueAsString()));
        m.setClaimServiceEndDate(sa(p.getEndElement().getValueAsString()));
        // A Claim has no separate statement dates, so these reuse billablePeriod
        m.setStatementFromDate(sa(p.getStartElement().getValueAsString()));
        m.setStatementThruDate(sa(p.getEndElement().getValueAsString()));

        m.setClaimTotalSubmittedAmount(sa(c.getTotal().getValue()));

        if (c.hasPayee()) {
            m.setClaimPayeeTypeCode(cc(c.getPayee().getType()));
            m.setClaimPayee(sa(c.getPayee().getParty().getReference()));
        }
        return m;
    }

    // ------------------------------------------------------------------
    // Pharmacy claim header
    // ------------------------------------------------------------------

    private PiqiPharmacyClaim mapPharmacyClaim(Claim c) {
        PiqiPharmacyClaim x = new PiqiPharmacyClaim();
        x.setDaysSupply(sa(infoText(info(c, "dayssupply"))));
        x.setRefillNumber(sa(infoText(info(c, "refillnum"))));
        x.setDawCode(sa(infoText(info(c, "dawcode"))));
        x.setPrescriptionOriginCode(sa(infoText(info(c, "rxorigincode"))));
        Claim.SupportingInformationComponent bg = info(c, "brandgenericindicator");
        if (bg != null && bg.hasCode()) {
            x.setPlanReportedBrandGenericCode(cc(bg.getCode()));
        }
        x.setTotalAmount(sa(c.getTotal().getValue()));
        x.setAdjudicationAmountType(sa("submitted")); // our own label
        return x;
    }

    // ------------------------------------------------------------------
    // Dental claim (one per claim item)
    // ------------------------------------------------------------------

    private PiqiDentalClaim mapDentalClaim(Claim c, Claim.ItemComponent i) {
        PiqiDentalClaim d = new PiqiDentalClaim();
        d.setProcedureCode(cc(i.getProductOrService()));
        if (i.hasModifier()) {
            d.setProcedureModifierCode(cc(i.getModifierFirstRep()));
        }
        if (i.hasBodySite()) {
            d.setToothNumberFirstOccurrence(cc(i.getBodySite()));
        }

        if (i.hasSubSite()) { // several surfaces merged into one concept
            CodeableConcept surfaces = new CodeableConcept();
            i.getSubSite().forEach(s -> s.getCoding().forEach(surfaces::addCoding));
            d.setToothSurface(cc(surfaces));
        }

        List<CodeableConcept> missing = c.getSupportingInfo().stream()
                .filter(s -> "missingtooth".equalsIgnoreCase(s.getCategory().getCodingFirstRep().getCode()))
                .map(Claim.SupportingInformationComponent::getCode)
                .toList();
        if (!missing.isEmpty()) {
            d.setMissingToothNumberFirstOccurrence(cc(missing.getFirst()));
        }
        if (missing.size() > 1) {
            CodeableConcept rest = new CodeableConcept();
            missing.stream().skip(1).forEach(m -> m.getCoding().forEach(rest::addCoding));
            d.setMissingToothNumberAfterFirstOccurrence(cc(rest));
        }

        Claim.SupportingInformationComponent pros = info(c, "prosthesis");
        if (pros != null && pros.hasTimingDateType()) {
            d.setDateOfPriorProsthesisPlacement(sa(pros.getTimingDateType().getValueAsString()));
        }
        // Orthodontics fields and prosthesisReplacementIndicator: no confident R4 Claim source
        return d;
    }

    // ------------------------------------------------------------------
    // Lines, diagnoses, procedures
    // ------------------------------------------------------------------

    private PiqiClaimLine mapLine(Claim.ItemComponent i, boolean pharmacy) {
        PiqiClaimLine l = new PiqiClaimLine();
        l.setLineNumber(sa(String.valueOf(i.getSequence())));

        if (i.hasServicedDateType()) {
            l.setServiceDate(sa(i.getServicedDateType().getValueAsString()));
        }
        if (i.hasServicedPeriod()) {
            l.setServiceDate(sa(i.getServicedPeriod().getStartElement().getValueAsString()));
            l.setServiceToDate(sa(i.getServicedPeriod().getEndElement().getValueAsString()));
        }

        if (pharmacy) {
            l.setNationalDrugCode(cc(i.getProductOrService()));
            l.setQuantityDispensed(sa(i.getQuantity().getValue()));
            if (i.getQuantity().hasCode()) {
                l.setQuantityQualifierCode(cc("http://unitsofmeasure.org", i.getQuantity().getCode()));
            }
        } else {
            l.setLineProcedureCode(cc(i.getProductOrService()));
        }

        // The schema allows only one modifier on a line
        if (i.hasModifier()) {
            l.setLineProcedureCodeModifier(cc(i.getModifierFirstRep()));
        }
        l.setRevenueCenterCode(cc(i.getRevenue()));
        if (i.hasLocationCodeableConcept()) {
            l.setPlaceOfServiceCode(cc(i.getLocationCodeableConcept()));
        }
        l.setLineSubmittedAmount(sa(i.getNet().getValue()));
        return l;
    }

    private PiqiClaimDiagnosis mapDiagnosis(Claim.DiagnosisComponent d) {
        PiqiClaimDiagnosis x = new PiqiClaimDiagnosis();
        String code = null;
        if (d.hasDiagnosisCodeableConcept()) {
            x.setDiagnosisCode(cc(d.getDiagnosisCodeableConcept()));
            code = d.getDiagnosisCodeableConcept().getCodingFirstRep().getCode();
        }
        x.setDiagnosisType(cc(d.getTypeFirstRep()));
        x.setPresentOnAdmissionIndicator(sa(d.getOnAdmission().getCodingFirstRep().getCode()));
        if (code != null) {
            // E-codes are ICD-10 V00 through Y99. Value format ("true"/"false") is a guess.
            x.seteCodeIndicator(sa(String.valueOf(code.matches("(?i)^[V-Y]\\d.*"))));
        }
        return x;
    }

    private PiqiClaimProcedure mapProcedure(Claim.ProcedureComponent p) {
        PiqiClaimProcedure x = new PiqiClaimProcedure();
        if (p.hasProcedureCodeableConcept()) {
            x.setProcedureCode(cc(p.getProcedureCodeableConcept()));
        }
        x.setProcedureDate(sa(p.getDateElement().getValueAsString()));
        x.setProcedureType(cc(p.getTypeFirstRep()));
        // procedureClaimLine left null = header-level, per the schema description
        return x;
    }

    // ------------------------------------------------------------------
    // Providers
    // ------------------------------------------------------------------

    private List<PiqiProvider> mapProviders(Claim claim, Function<Reference, Resource> resolve) {
        List<PiqiProvider> out = new ArrayList<>();

        if (claim.hasProvider()) { // billing provider
            PiqiProvider p = mapProvider(resolve.apply(claim.getProvider()),
                    new CodeableConcept().setText("billing provider"), resolve);
            if (p != null) {
                out.add(p);
            }
        }
        for (Claim.CareTeamComponent ct : claim.getCareTeam()) {
            PiqiProvider p = mapProvider(resolve.apply(ct.getProvider()), ct.getRole(), resolve);
            if (p != null) {
                out.add(p);
            }
        }
        return out;
    }

    private PiqiProvider mapProvider(Resource r, CodeableConcept role,
                                     Function<Reference, Resource> resolve) {
        if (r == null) {
            return null;
        }
        PiqiProvider p = new PiqiProvider();
        p.setCareTeamRole(cc(role));

        switch (r) {
            case Practitioner pr -> {
                p.setProviderNpi(sa(npi(pr.getIdentifier())));
                if (pr.hasName()) {
                    p.setProviderName(sa(pr.getNameFirstRep().getNameAsSingleString()));
                }
                p.setProviderAddress(sa(addr(pr.getAddressFirstRep())));
                p.setProviderType(cc(pr.getQualificationFirstRep().getCode()));
            }
            case Organization org -> {
                p.setProviderNpi(sa(npi(org.getIdentifier())));
                p.setProviderName(sa(org.getName()));
                p.setProviderAddress(sa(addr(org.getAddressFirstRep())));
                p.setProviderType(cc(org.getTypeFirstRep()));
            }
            case PractitionerRole prole -> {
                PiqiProvider base = mapProvider(resolve.apply(prole.getPractitioner()), role, resolve);
                if (base == null) {
                    return null;
                }
                base.setProviderType(cc(prole.getSpecialtyFirstRep()));
                return base;
            }
            default -> {
                return null;
            }
        }
        // providerContractingStatus, siteOfServiceNetworkStatus, providerPayerSpecificIdentifier:
        // payer network data, not available from a Claim
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
    // supportingInfo helpers
    // ------------------------------------------------------------------

    private static Claim.SupportingInformationComponent info(Claim c, String code) {
        return c.getSupportingInfo().stream()
                .filter(s -> code.equalsIgnoreCase(s.getCategory().getCodingFirstRep().getCode()))
                .findFirst().orElse(null);
    }

    private static String infoText(Claim.SupportingInformationComponent s) {
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

    private static PiqiSimpleAttribute sa(String v) {
        return (v == null || v.isBlank()) ? null : new PiqiSimpleAttribute(v);
    }

    private static PiqiSimpleAttribute sa(BigDecimal v) {
        return v == null ? null : new PiqiSimpleAttribute(v.toPlainString());
    }

    /** Returns null for a null or empty concept, so the field is omitted from the JSON. */
    private static PiqiCodeableConcept cc(CodeableConcept c) {
        if (c == null || c.isEmpty()) {
            return null;
        }
        PiqiCodeableConcept out = new PiqiCodeableConcept();
        String text = c.hasText() ? c.getText() : c.getCodingFirstRep().getDisplay();
        out.setText(sa(text));
        for (Coding k : c.getCoding()) {
            out.getCodings().add(coding(k));
        }
        return out;
    }

    /** For fixed-value enums where FHIR gives a bare code (Claim.use, Coverage.status, ...). */
    private static PiqiCodeableConcept cc(String system, String code) {
        return cc(new CodeableConcept().addCoding(new Coding().setSystem(system).setCode(code)));
    }

    private static PiqiCoding coding(Coding k) {
        PiqiCoding x = new PiqiCoding();
        x.setSystem(sa(k.getSystem()));
        x.setCode(sa(k.getCode()));
        x.setDisplay(sa(k.getDisplay()));
        return x;
    }
}
