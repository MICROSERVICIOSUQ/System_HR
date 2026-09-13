package com.microservicios.employees.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

public enum EmployeeStatus {
    ACTIVE("ACTIVO"),
    ON_VACATION("EN_VACACIONES"),
    RETIRED("RETIRADO");

    private final String jsonValue;

    EmployeeStatus(String jsonValue) {
        this.jsonValue = jsonValue;
    }

    @JsonValue
    public String jsonValue() {
        return jsonValue;
    }

    @JsonCreator
    public static EmployeeStatus fromJsonValue(String value) {
        for (EmployeeStatus status : values()) {
            if (status.jsonValue.equals(value)) {
                return status;
            }
        }
        throw new IllegalArgumentException("Invalid employee status: " + value);
    }
}
