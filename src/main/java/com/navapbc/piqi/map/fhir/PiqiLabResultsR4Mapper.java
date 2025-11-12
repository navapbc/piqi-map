package com.navapbc.piqi.map.fhir;

import com.navapbc.piqi.model.*;
import org.hl7.fhir.instance.model.api.IIdType;
import org.hl7.fhir.r4.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PiqiLabResultsR4Mapper extends PiqiBaseR4Mapper {

    private static final Logger log = LoggerFactory.getLogger(PiqiLabResultsR4Mapper.class);

    @Override
    public boolean isMappingClassFor(Class<?> clazz) {
        return clazz.equals(PiqiLabResult.class);
    }

    @Override
    public List<PiqiLabResult> mapLabResults(Bundle bundle) {
        List<PiqiLabResult> piqiLabResults = new ArrayList<>();
        Map<String, Observation> observations = new HashMap<>();
        Map<String, DiagnosticReport> diagnosticReports = new HashMap<>();
        for (Bundle.BundleEntryComponent entry : bundle.getEntry()) {
            if (entry.getResource() instanceof Observation observation) {
                observations.put(observation.getIdElement().getIdPart(), observation);
            }
            if (entry.getResource() instanceof DiagnosticReport diagnosticReport) {
                diagnosticReports.put(diagnosticReport.getIdElement().getIdPart(), diagnosticReport);
            }
        }
        if (!diagnosticReports.isEmpty()) {
            for (DiagnosticReport diagnosticReport : diagnosticReports.values()) {
                List<PiqiLabResult> results = mapLabResults(diagnosticReport, observations);
                if (!results.isEmpty()) {
                    piqiLabResults.addAll(results);
                }
            }
        }
        return piqiLabResults;
    }

    @Override
    public List<PiqiLabResult> mapLabResults(DiagnosticReport diagnosticReport, Map<String, Observation> observations) {
        List<PiqiLabResult> piqiLabResults = new ArrayList<>();
        if (!FhirR4MappingHelper.isLab(diagnosticReport)) {
            return piqiLabResults;
        }
        // Iterate through each result in the FHIR DiagnosticReport
        for (Reference reference : diagnosticReport.getResult()) {
            Observation fhirObservation = null;
            if (reference.getResource() instanceof Observation) {
                fhirObservation = (Observation) reference.getResource();
            } else if (reference.hasReferenceElement()) {
                IIdType iIdType = reference.getReferenceElement();
                if (iIdType.hasIdPart()) {
                    fhirObservation = observations.get(iIdType.getIdPart());
                }
            }
            if (fhirObservation != null && FhirR4MappingHelper.isLab(fhirObservation)) {
                // Create a new PIQI LabResult data class instance
                PiqiLabResult piqiLabResult = new PiqiLabResult();
                // Map the test and result
                if (fhirObservation.getCode() != null) {
                    piqiLabResult.setTest(FhirR4MappingHelper.mapCodeableConcept(fhirObservation.getCode()));
                }
                if (fhirObservation.getValue() != null) {
                    if (fhirObservation.hasValueQuantity()) {
                        if (piqiLabResult.getResultValue() == null) {
                            piqiLabResult.setResultValue(new PiqiObservationValue());
                        }
                        piqiLabResult.getResultValue().setNumber(new PiqiSimpleAttribute(fhirObservation.getValueQuantity().getValue().toPlainString()));
                        CodeableConcept codeableConcept = new CodeableConcept();
                        codeableConcept.setText(fhirObservation.getValueQuantity().getValue().toPlainString());
                        Coding coding = new Coding();
                        coding.setCode(fhirObservation.getValueQuantity().getCode());
                        coding.setSystem(fhirObservation.getValueQuantity().getSystem());
                        coding.setDisplay(fhirObservation.getValueQuantity().getDisplay());
                        codeableConcept.addCoding(coding);
                        piqiLabResult.setResultUnit(FhirR4MappingHelper.mapCodeableConcept(codeableConcept));
                    } else if (fhirObservation.hasValueCodeableConcept()) {
                        if (piqiLabResult.getResultValue() == null) {
                            piqiLabResult.setResultValue(new PiqiObservationValue());
                        }
                        // TODO ???
                        //piqiLabResult.getResultValue().getCodings().add(FhirR4MappingHelper.mapCodeableConcept(fhirObservation.getValueCodeableConcept()));
                    } else if (fhirObservation.hasValueStringType()) {
                        if (piqiLabResult.getResultValue() == null) {
                            piqiLabResult.setResultValue(new PiqiObservationValue());
                        }
                        piqiLabResult.getResultValue().setText(new PiqiSimpleAttribute(fhirObservation.getValueStringType().getValue()));
                    } else {
                        // ... map other result types
                        //log.warn("fhir type not mapped = [{}}", fhirObservation.getValue().fhirType());
                    }
                }

                // Map other elements
                if (fhirObservation.getInterpretation() != null) {
                    List<CodeableConcept> interepretations = fhirObservation.getInterpretation();
                    //log.debug("interpretations.size=[{}]", interepretations.size());
                    if (!interepretations.isEmpty()) {
                        piqiLabResult.setInterpretation(FhirR4MappingHelper.mapCodeableConcept(fhirObservation.getInterpretation().get(0)));
                    }
                }
                if (fhirObservation.hasReferenceRange()) {
                    // ... map referenceRange from Observation to PIQI RangeValue
                    Observation.ObservationReferenceRangeComponent  referenceRange = fhirObservation.getReferenceRangeFirstRep();
                    PiqiRangeValue piqiRangeValue = new PiqiRangeValue();
                    piqiRangeValue.setHighValue(new PiqiSimpleAttribute(referenceRange.getHigh().toString()));
                    piqiRangeValue.setLowValue(new PiqiSimpleAttribute(referenceRange.getLow().toString()));
                    piqiRangeValue.setText(new PiqiSimpleAttribute(referenceRange.getText()));
                    piqiLabResult.setReferenceRange(piqiRangeValue);
                }
                if (fhirObservation.hasSpecimen()) {
                    Specimen specimen = (Specimen) fhirObservation.getSpecimen().getResource();
                    CodeableConcept specimenCodeableConcept = new CodeableConcept();
                    specimenCodeableConcept.setText(specimen.getText().toString());
                    Coding coding = new Coding();
                    coding.setCode(specimenCodeableConcept.getCodingFirstRep().getCode());
                    coding.setDisplay(specimenCodeableConcept.getCodingFirstRep().getDisplay());
                    coding.setSystem(specimenCodeableConcept.getCodingFirstRep().getSystem());
                    specimenCodeableConcept.addCoding(coding);
                    piqiLabResult.setSpecimenType(FhirR4MappingHelper.mapCodeableConcept(specimenCodeableConcept));
                }

                // Map status and date/time from DiagnosticReport
                piqiLabResult.setResultStatus(mapStatus(diagnosticReport.getStatus()));
                if (diagnosticReport.getIssued() != null) {
                    //piqiLabResult.setPerformedDateTime(diagnosticReport.getIssued().toString("yyyyMMddHHmmss");
                    piqiLabResult.setPerformedDateTime(FhirR4MappingHelper.simpleAttributeFromDateAsDateTime(diagnosticReport.getIssued()));
                }
                if (fhirObservation.hasPerformer()) {
                    for(Reference performer : fhirObservation.getPerformer()) {
                        log.debug("Performer: " + performer.getDisplay());
//                        if (performer instanceof) {
//
//                        }
                    }
                    piqiLabResult.setPerformedDateTime(FhirR4MappingHelper.simpleAttributeFromDateAsDateTime(diagnosticReport.getIssued()));
                } else if (diagnosticReport.hasPerformer()) {
                    for(Reference performer : diagnosticReport.getPerformer()) {
                        log.debug("Performer: " + performer.getDisplay());
                        if (performer.hasReference() && !performer.getReference().isEmpty()) {
                            //Organization?identifier=https://github.com/synthetichealth/synthea|980d9bfa-a344-3bff-8c02-232dd0e8fd34
                            //mapPerformerToCodeableConcept(performer);
                            //PiqiCoding piqiCoding = new PiqiCoding();
                            // TODO parse the string and build the coding.
                            PiqiCodeableConcept piqiCodeableConcept = new PiqiCodeableConcept();
                            piqiCodeableConcept.setText(new PiqiSimpleAttribute(performer.getDisplay()));
                            //piqiCodeableConcept.getCodings().add(piqiCoding);
                            piqiLabResult.setPerformedSite(piqiCodeableConcept);
                        } else if (performer.getResource() != null) {
                            log.debug("Performer has resource.");
                        }
                    }
                }
                if (fhirObservation.hasEncounter()) {
                    Encounter encounter = (Encounter) fhirObservation.getEncounter().getResource();
                    if (encounter.hasLocation()) {
                        List<Encounter.EncounterLocationComponent> locations = encounter.getLocation();
                        for (Encounter.EncounterLocationComponent location : locations) {
                            if (location.hasLocation()) {
                                Location theLocation = location.getLocationTarget();
                                Location aLocation = (Location) location.getLocation().getResource();
//                                if (location.hasPhysicalType()) {
//                                    piqiLabResult.setPerformedSite(mapCodeableConcept(location.getPhysicalType()));
//                                    break;
//                                } else if (aLocation.hasPhysicalType()) {
//                                    piqiLabResult.setPerformedSite(mapCodeableConcept(aLocation.getPhysicalType()));
//                                    break;
//                                }
                            }
                        }
                    }
                }
                // basedOn is ServiceRequest (order)
                if (fhirObservation.hasBasedOn()) {
                    List<Reference> basedOn = fhirObservation.getBasedOn();
                    //log.debug("observation basedOn.size=[{}]", basedOn.size());
                } else if (diagnosticReport.hasBasedOn()) {
                    List<Reference> basedOn = diagnosticReport.getBasedOn();
                    //log.debug("diagnosticreport basedOn.size=[{}]", basedOn.size());
                    for (Reference basedOnReference : basedOn) {
                        //log.debug("basedOnReference==[{}]", basedOnReference.getResource().fhirType());
                        if (basedOnReference.getResource().fhirType().equals(ResourceType.ServiceRequest.toString())) {
                            ServiceRequest serviceRequest = (ServiceRequest) basedOnReference.getResource();
                            //log.debug("serviceRequest=[{}]", serviceRequest.toString());
                            if (serviceRequest.hasOrderDetail()) {
                                piqiLabResult.setOrder(FhirR4MappingHelper.mapCodeableConcept(serviceRequest.getOrderDetailFirstRep()));
                            } else if (serviceRequest.hasCode()) {
                                piqiLabResult.setOrder(FhirR4MappingHelper.mapCodeableConcept(serviceRequest.getCode()));
                            }
                            if (serviceRequest.hasAuthoredOn()) {
                                piqiLabResult.setOrderDate(new PiqiSimpleAttribute(serviceRequest.getAuthoredOn().toString()));
                            } else {
                                //TODO What's the default?
                            }
                        }
                        break;
                    }
                } else {
                    piqiLabResult.setOrder(FhirR4MappingHelper.mapCodeableConcept(diagnosticReport.getCode()));
                    piqiLabResult.setOrderDate(FhirR4MappingHelper.simpleAttributeFromDateAsDate(diagnosticReport.getIssued()));
                }
                // Add the new LabResult to the PIQI data model
                piqiLabResults.add(piqiLabResult);
            }
        }
        return piqiLabResults;
    }

    private PiqiCodeableConcept mapStatus(DiagnosticReport.DiagnosticReportStatus diagnosticReportStatus) {
        PiqiCodeableConcept piqiCodeableConcept = new PiqiCodeableConcept();
        if (diagnosticReportStatus != null) {
            piqiCodeableConcept.setText(new PiqiSimpleAttribute(diagnosticReportStatus.toString()));
            PiqiCoding piqiCoding = new PiqiCoding();
            piqiCoding.setCode(new PiqiSimpleAttribute(diagnosticReportStatus.toCode()));
            piqiCoding.setDisplay(new PiqiSimpleAttribute(diagnosticReportStatus.getDisplay()));
            piqiCoding.setSystem(new PiqiSimpleAttribute(diagnosticReportStatus.getSystem()));
            piqiCodeableConcept.getCodings().add(piqiCoding);
        }
        return piqiCodeableConcept;
    }

    private PiqiCodeableConcept mapPerformerToCodeableConcept(Reference performer) {
        log.debug("performer=[{}]", performer);
        PiqiCodeableConcept piqiCodeableConcept = null;
        if (performer != null && performer.hasReference() && !performer.getReference().isEmpty()) {
            piqiCodeableConcept = new PiqiCodeableConcept();
            piqiCodeableConcept.setText(new PiqiSimpleAttribute(performer.getDisplay()));
            if (performer.hasType()) {
                UriType uriType = performer.getTypeElement();
                PiqiCoding piqiCoding = new PiqiCoding();
                piqiCoding.setSystem(new PiqiSimpleAttribute(""));
                piqiCoding.setCode(new PiqiSimpleAttribute(uriType.getValue()));
                piqiCodeableConcept.getCodings().add(piqiCoding);
            } else if (performer.hasReference()) {
                String reference = performer.getReference();
                //Organization?identifier=https://github.com/synthetichealth/synthea|980d9bfa-a344-3bff-8c02-232dd0e8fd34
                PiqiCoding piqiCoding = new PiqiCoding();
                // TODO parse the string and build the coding.
                piqiCodeableConcept.getCodings().add(piqiCoding);
            } else if (performer.hasIdentifier()) {
                Identifier identifier = performer.getIdentifier();
                PiqiCoding piqiCoding = new PiqiCoding();
                piqiCoding.setSystem(new PiqiSimpleAttribute(identifier.getSystem()));
                piqiCoding.setCode(new PiqiSimpleAttribute(identifier.getValue()));
                piqiCodeableConcept.getCodings().add(piqiCoding);
            }
        }
        log.debug("piqiCodeableConcept=[{}]", piqiCodeableConcept);
        return piqiCodeableConcept;
    }
}
