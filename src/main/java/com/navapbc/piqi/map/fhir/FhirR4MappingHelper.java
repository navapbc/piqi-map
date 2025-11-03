package com.navapbc.piqi.map.fhir;

import com.navapbc.piqi.model.PiqiCodeableConcept;
import com.navapbc.piqi.model.PiqiCoding;
import com.navapbc.piqi.model.PiqiSimpleAttribute;
import org.hl7.fhir.r4.model.*;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;


public class FhirR4MappingHelper {

    public static boolean isCategoryMatch(List<CodeableConcept> categories, String text) {
        boolean isMatch = false;
        for (CodeableConcept codeableConcept : categories) {
            if (codeableConcept.hasCoding()) {
                for (Coding coding : codeableConcept.getCoding()) {
                    if (coding.getCode().equals(text)) {
                        isMatch = true;
                        break;
                    }
                }
            }
        }
        return isMatch;
    }

    public static boolean isLab(DiagnosticReport diagnosticReport) {
        boolean isLab = false;
        if (diagnosticReport != null && diagnosticReport.hasCategory()) {
            isLab = isCategoryMatch(diagnosticReport.getCategory(), "LAB");
        }
        return isLab;
    }

    public static boolean isLab(Observation observation) {
        boolean isLab = false;
        if (observation != null && observation.hasCategory()) {
            isLab = isCategoryMatch(observation.getCategory(), "laboratory");
        }
        return isLab;
    }

    // Helper function to map FHIR CodeableConcept to PIQI Codeable Concept
    public static PiqiCodeableConcept mapCodeableConcept(CodeableConcept fhirConcept) {
        PiqiCodeableConcept piqiConcept = new PiqiCodeableConcept();
        piqiConcept.setText(new PiqiSimpleAttribute(fhirConcept.getText()));
        for (Coding fhirCoding : fhirConcept.getCoding()) {
            PiqiCoding piqiCodings = new PiqiCoding();
            piqiCodings.setCode(new PiqiSimpleAttribute(fhirCoding.getCode()));
            piqiCodings.setDisplay(new PiqiSimpleAttribute(fhirCoding.getDisplay()));
            piqiCodings.setSystem(new PiqiSimpleAttribute(fhirCoding.getSystem()));
            piqiConcept.getCodings().add(piqiCodings);
        }
        return piqiConcept;
    }

    public static PiqiSimpleAttribute simpleAttributeFromDateAsDate(Date date) {
        PiqiSimpleAttribute simpleAttribute = new PiqiSimpleAttribute();
        if (date != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd");
            simpleAttribute.setValue(sdf.format(date));
        }
        return simpleAttribute;
    }

    public static PiqiSimpleAttribute simpleAttributeFromDateAsDateTime(Date date) {
        PiqiSimpleAttribute simpleAttribute = new PiqiSimpleAttribute();
        if (date != null) {
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddHHmmss");
            simpleAttribute.setValue(sdf.format(date));
        }
        return simpleAttribute;
    }

}
