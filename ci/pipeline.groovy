def publicarBDD() {
    junit testResults: 'e2e-tests/reports/junit.xml', allowEmptyResults: false
    cucumber fileIncludePattern: '**/cucumber.json', jsonReportDirectory: 'e2e-tests/reports',
        reportTitle: 'Escenarios de System_HR', buildStatus: 'FAILURE',
        failedScenariosNumber: 0, failedStepsNumber: 0
    publishHTML target: [reportDir: 'e2e-tests/reports', reportFiles: 'cucumber.html',
        reportName: 'Escenarios BDD en español', keepAll: true, alwaysLinkToLastBuild: false,
        allowMissing: false]
}

return this
