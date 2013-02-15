/*
 * Copyright 2012 Midokura PTE LTD.
 */
package org.midonet.api.validation;

import javax.validation.ConstraintValidator;
import javax.validation.ConstraintValidatorContext;

public class AllowedValueConstraintValidator implements
        ConstraintValidator<AllowedValue, String> {

    private String[] allowedValues;

    @Override
    public void initialize(AllowedValue annotation) {
        allowedValues = annotation.values();
    }

    @Override
    public boolean isValid(String object,
            ConstraintValidatorContext constraintContext) {
        if (object == null) {
            return true;
        }

        // Do case-insensitive matching
        for(String val : allowedValues) {
            if(val.equalsIgnoreCase(object)) {
                return true;
            }
        }

        return false;
    }
}