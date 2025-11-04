package com.navapbc.piqi.map.fhir;

import com.navapbc.piqi.model.PiqiCodeableConcept;
import com.navapbc.piqi.model.PiqiCoding;
import com.navapbc.piqi.model.PiqiDemographics;
import com.navapbc.piqi.model.PiqiSimpleAttribute;
import org.hl7.fhir.r4.model.*;

public class PiqiDemographicsR4Mapper extends PiqiBaseR4Mapper {

    private static final String BIRTH_SEX_SYSTEM = "http://hl7.org/fhir/us/core/StructureDefinition/us-core-birthsex";
    private static final String ETHNICITY_SYSTEM = "http://hl7.org/fhir/us/core/StructureDefinition/us-core-ethnicity";
    private static final String RACE_SYSTEM = "http://hl7.org/fhir/us/core/StructureDefinition/us-core-race";

    @Override
    public PiqiDemographics mapDemographics(Bundle bundle) {
        PiqiDemographics demographics = null;
        Patient patient = null;
        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            if (entry.getResource() instanceof Patient) {
                patient = (Patient) entry.getResource();
                break;
            }
        }
        if (patient != null) {
            demographics = new PiqiDemographics();
            demographics.setBirthDate(FhirR4MappingHelper.simpleAttributeFromDateAsDate(patient.getBirthDate()));
            mapBirthSex(demographics, patient);
            mapDeathAttributes(demographics, patient);
            mapEthnicity(demographics, patient);
            mapGender(demographics, patient);
            if (patient.hasCommunication()) {
                for (Patient.PatientCommunicationComponent communicationComponent: patient.getCommunication()) {
                    demographics.setPrimaryLanguage(FhirR4MappingHelper.mapCodeableConcept(communicationComponent.getLanguage()));
                    if (communicationComponent.hasPreferred() && communicationComponent.getPreferred()) {
                        break;
                    }
                }
            }
            demographics.setMaritalStatus(FhirR4MappingHelper.mapCodeableConcept(patient.getMaritalStatus()));
            mapRace(demographics, patient);
        }
        return demographics;
    }

    private void mapBirthSex(PiqiDemographics demographics, Patient patient) {
        if (patient.hasExtension(BIRTH_SEX_SYSTEM)) {
            Extension extension = patient.getExtensionByUrl(BIRTH_SEX_SYSTEM);
            if (extension != null && extension.hasValue() && !extension.getValue().isEmpty()) {
                if (extension.getValue() instanceof CodeType codeType) {
                    PiqiCodeableConcept codeableConcept = new PiqiCodeableConcept();
                    codeableConcept.setText(new PiqiSimpleAttribute(codeType.getDisplay()));
                    PiqiCoding coding = new PiqiCoding();
                    if (codeType.getSystem() != null) {
                        coding.setSystem(new PiqiSimpleAttribute(codeType.getSystem()));
                    } else {
                        coding.setSystem(new PiqiSimpleAttribute(BIRTH_SEX_SYSTEM));
                    }
                    coding.setDisplay(new PiqiSimpleAttribute(codeType.getDisplay()));
                    coding.setCode(new PiqiSimpleAttribute(codeType.getCode()));
                    codeableConcept.getCodings().add(coding);
                    demographics.setBirthSex(codeableConcept);
                }
            }
        }
    }

    private void mapDeathAttributes(PiqiDemographics demographics, Patient patient) {
        CodeableConcept codeableConcept = new CodeableConcept();
        if (patient.hasDeceased()) {
            if (patient.hasDeceasedBooleanType()) {
                codeableConcept.setText(patient.getDeceasedBooleanType().getValue().equals(Boolean.TRUE) ? "Yes" : "No");
                Coding coding = new Coding();
                coding.setSystem("http://terminology.hl7.org/CodeSystem/v2-0136");
                coding.setCode(patient.getDeceasedBooleanType().getValue().equals(Boolean.TRUE) ? "Y" : "N");
                coding.setDisplay(patient.getDeceasedBooleanType().getValue().equals(Boolean.TRUE) ? "Yes" : "No");
                codeableConcept.addCoding(coding);
                demographics.setDeceased(FhirR4MappingHelper.mapCodeableConcept(codeableConcept));
            }
            if (patient.hasDeceasedDateTimeType()) {
                demographics.setDeathDate(new PiqiSimpleAttribute(patient.getDeceasedDateTimeType().toString()));
            }
        } else {
            codeableConcept.setText("No");
            Coding coding = new Coding();
            coding.setSystem("http://terminology.hl7.org/CodeSystem/v2-0136");
            coding.setCode("N");
            coding.setDisplay("No");
            codeableConcept.addCoding(coding);
            demographics.setDeceased(FhirR4MappingHelper.mapCodeableConcept(codeableConcept));
        }
    }

    private void mapEthnicity(PiqiDemographics demographics, Patient patient) {
        if (patient.hasExtension(ETHNICITY_SYSTEM)) {
            Extension extension = patient.getExtensionByUrl(ETHNICITY_SYSTEM);
            if (extension.hasExtension()) {
                PiqiCodeableConcept ethnicityCodeableConcept = new PiqiCodeableConcept();
                if (extension.hasExtension()) {
                    mapExtension(ethnicityCodeableConcept, extension);
                    demographics.setEthnicity(ethnicityCodeableConcept);
                }
            }
        }
    }

    private void mapExtension(PiqiCodeableConcept codeableConcept, Extension extension) {
        for (Extension extensionValue : extension.getExtension()) {
            if  (extensionValue.hasUrl() && extensionValue.getUrl().equals("text")) {
                codeableConcept.setText(new PiqiSimpleAttribute(extensionValue.getValue().toString()));
            } else if (extensionValue.hasUrl() && extensionValue.getUrl().equals("ombCategory") && extensionValue.hasValue()) {
                if (extensionValue.getValue() instanceof Coding coding) {
                    PiqiCoding ethnicityCoding = new PiqiCoding();
                    ethnicityCoding.setCode(new PiqiSimpleAttribute(coding.getCode()));
                    ethnicityCoding.setSystem(new PiqiSimpleAttribute(coding.getSystem()));
                    ethnicityCoding.setDisplay(new PiqiSimpleAttribute(coding.getDisplay()));
                    codeableConcept.getCodings().add(ethnicityCoding);
                }
            }
        }
    }

    private void mapGender(PiqiDemographics demographics, Patient patient) {
        if (patient.hasGender()) {
            PiqiCoding coding = new PiqiCoding();
            coding.setSystem(new PiqiSimpleAttribute(patient.getGender().getSystem()));
            coding.setCode(new PiqiSimpleAttribute(patient.getGender().toCode()));
            coding.setDisplay(new PiqiSimpleAttribute(patient.getGender().getDisplay()));
            PiqiCodeableConcept codeableConcept = new PiqiCodeableConcept();
            codeableConcept.getCodings().add(coding);
            codeableConcept.setText(new PiqiSimpleAttribute(patient.getGender().getDefinition()));
            demographics.setGenderIdentity(codeableConcept);
        }
    }

    private void mapRace(PiqiDemographics demographics, Patient patient) {
        if (patient.hasExtension(RACE_SYSTEM)) {
            Extension extension = patient.getExtensionByUrl(RACE_SYSTEM);
//                if (extension != null && extension.hasValue() && !extension.getValue().isEmpty()) {
//                    log.info("Found 'us-core-race' extension=[{}]", extension);
//                    if (extension.getValue() instanceof CodeableConcept) {
//                        demographics.setRaceCategory(mapCodeableConcept((CodeableConcept) extension.getValue()));
//                    }
//                }
            if (extension != null && extension.hasExtension()) {
                PiqiCodeableConcept raceCodeableConcept = new PiqiCodeableConcept();
                if (extension.hasExtension()) {
                    mapExtension(raceCodeableConcept, extension);
                    demographics.setRace(raceCodeableConcept);
                }
            }
        }
    }

    @Override
    public boolean isMappingClassFor(Class<?> clazz) {
        return clazz.equals(PiqiDemographics.class);
    }
}
