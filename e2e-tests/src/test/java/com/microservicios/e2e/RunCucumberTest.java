package com.microservicios.e2e;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectClasspathResource;
import org.junit.platform.suite.api.Suite;

import static io.cucumber.junit.platform.engine.Constants.GLUE_PROPERTY_NAME;

/**
 * Punto de entrada único de la suite BDD.
 *
 * <p>Se ejecuta con {@code mvn test} desde el directorio {@code e2e-tests/}, con
 * el sistema ya levantado. El resto de la configuración —los reportes y sus
 * rutas— vive en {@code junit-platform.properties}, de modo que cambiar una ruta
 * de reporte no obligue a recompilar.
 *
 * <p><strong>Por qué los features se seleccionan aquí y no por propiedad.</strong>
 * Con {@code cucumber.features} en el archivo de propiedades, el motor de
 * Cucumber carga los {@code .feature} ante cualquier petición de descubrimiento;
 * sumado a esta clase {@code @Suite}, la suite se ejecutaba dos veces (82 pruebas
 * en lugar de 41). Declarando la selección aquí, el motor solo actúa cuando lo
 * invoca la suite, y Surefire la limita a esta clase.
 */
@Suite
@IncludeEngines("cucumber")
@SelectClasspathResource("features")
@ConfigurationParameter(key = GLUE_PROPERTY_NAME, value = "com.microservicios.e2e")
public class RunCucumberTest {
}
