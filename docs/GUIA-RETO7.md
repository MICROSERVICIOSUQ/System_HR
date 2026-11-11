# Reto 7 — Integración continua

Este reto conecta las pruebas que ya teníamos con Jenkins. Cada servicio se
prepara, se prueba y se analiza antes de construir su imagen Docker. Después,
la suite BDD comprueba los flujos del sistema completo.

Hay dos pipelines: empleados (Java 21) y vacaciones (TypeScript sobre Node 22).
Los archivos Jenkinsfile están dentro de sus respectivos servicios. El gateway
y los demás servicios participan en E2E, pero no tienen un pipeline independiente
en esta entrega mínima de dos lenguajes.

## 1. Preparar el equipo

Necesitas Docker Engine con Compose v2.20 o posterior, Python 3, Git y conexión a
Internet. La primera construcción descarga Jenkins, plugins y dependencias.
En Linux verifica primero que Docker esté funcionando:

```bash
sudo systemctl start docker
docker info
docker compose version
sysctl vm.max_map_count
```

SonarQube necesita que `vm.max_map_count` sea al menos 524288. Si el valor es
menor, ajústalo en el host siguiendo la documentación oficial de SonarQube.
No hace falta instalar Java, Maven o Node en el host: los pipelines usan
contenedores con esas herramientas.

Desde la raíz de System_HR ejecuta:

```bash
python3 ci/preparar-entorno.py
```

El script conserva las variables existentes y agrega las que faltan. Genera
contraseñas aleatorias para Jenkins y SonarQube y deja `.env` con permisos 600.
El archivo no se versiona. Puedes consultar allí los valores de
`JENKINS_ADMIN_PASSWORD` y `SONAR_ADMIN_PASSWORD`.

Antes de ejecutar Jenkins, los cambios del reto deben estar en la rama remota
que va a consultar. Revisa en `.env` estas dos variables:

```dotenv
CI_REPOSITORY_URL=https://github.com/MICROSERVICIOSUQ/System_HR.git
CI_REPOSITORY_BRANCH=valentina
```

Si trabajas en otra rama, escribe su nombre. Jenkins descarga lo que está en Git;
no puede ver cambios locales que todavía no se hayan subido. El repositorio es
privado: crea un fine-grained personal access token limitado a este repositorio
con permiso **Contents: Read-only**, y guarda el nombre de usuario y el token en
`CI_GIT_USERNAME` y `CI_GIT_TOKEN` dentro de `.env`. JCasC los carga como la
credencial `github-read-token`; el token no se incluye en la URL, el Jenkinsfile
ni el repositorio. No compartas ni registres el valor del token. Si se rota,
actualiza `.env` y recrea Jenkins.

## 2. Levantar el entorno

```bash
docker compose up -d --build
```

También puedes levantar primero solo la infraestructura de CI:

```bash
docker compose up -d --build jenkins
```

Compose arranca PostgreSQL, SonarQube y el servicio de preparación. Este último
espera a que SonarQube esté listo y crea los dos proyectos, la regla de cobertura,
el token y el webhook. Solo entonces arranca Jenkins.

| Herramienta | Dirección | Usuario | Contraseña |
|---|---|---|---|
| Jenkins | http://localhost:9091 | `JENKINS_ADMIN_USER`, por defecto `admin` | `JENKINS_ADMIN_PASSWORD` de `.env` |
| SonarQube | http://localhost:9000 | `admin` | `SONAR_ADMIN_PASSWORD` de `.env` |

El asistente inicial de Jenkins está desactivado porque el administrador y los
jobs se crean con JCasC y Job DSL. Por eso no se utiliza una contraseña inicial
de `initialAdminPassword`: la contraseña de acceso es la generada en `.env`.
SonarQube cambia automáticamente su contraseña inicial `admin` la primera vez.

Para revisar problemas de arranque:

```bash
docker compose ps -a
docker compose logs --tail=100 sonar-setup jenkins sonarqube
```

El HTML navegable se abre desde **Escenarios BDD en español**. Jenkins sirve
ese contenido desde `http://127.0.0.1:9091`, un origen distinto de su interfaz
`http://localhost:9091`. Esto permite desplegar los escenarios y usar los filtros
sin desactivar la protección de la interfaz principal. Si cambias el puerto,
ajusta también `JENKINS_RESOURCE_URL` en `.env`.

El token se guarda en un volumen compartido de solo lectura para Jenkins y se
carga como la credencial `sonar-token`. No aparece en el Jenkinsfile. El webhook
apunta a `http://jenkins:8080/sonarqube-webhook/`, dirección de la red interna;
`localhost:9091` es la dirección que usa la persona desde su navegador.

## 3. Ejecutar un pipeline

Al entrar a Jenkins encontrarás `employees-service` y `vacations-service`.
Abre uno y pulsa **Build Now / Construir ahora**. Después de la primera ejecución
aparecerá **Build with Parameters / Construir con parámetros**. Para una ejecución
normal selecciona `FALLO_DEMO=ninguno`.

| Etapa | Qué comprueba |
|---|---|
| Checkout | Descarga una copia limpia de la rama y comprueba el acceso a Docker |
| Build | Compila Java o instala dependencias, genera Prisma y compila TypeScript |
| Test | Ejecuta las pruebas y publica resultados y cobertura |
| SonarQube | Envía el código y la cobertura para análisis estático |
| Quality Gate | Espera el análisis y exige cobertura del 70 % o más |
| Package | Construye la imagen del servicio con el número del build |
| E2E Tests | Levanta una copia aislada del sistema y ejecuta Cucumber |

Se usan imágenes Maven/Java, Node y SonarScanner como entornos temporales. Maven
y npm guardan sus descargas en volúmenes de caché; los resultados del proyecto
se regeneran en cada ejecución. Al terminar se devuelve al usuario Jenkins la
propiedad de los archivos creados por los agentes, para que pueda limpiar su
workspace en el siguiente build. Los jobs no ejecutan dos builds simultáneos del
mismo servicio y consultan Git cada cinco minutos después de su primera ejecución.
Un webhook de GitHub es opcional: no hace falta exponer Jenkins a Internet.

## 4. Dónde consultar los reportes

| Resultado | Archivo del proyecto | Publicación | Dónde verlo en Jenkins |
|---|---|---|---|
| Unitarias e integración Java | `services/employees-service/target/surefire-reports/TEST-*.xml` | `junit` | Test Result, detalle de cada prueba |
| Unitarias TypeScript | `services/vacations-service/reports/junit.xml` | `junit` | Test Result |
| Cobertura Java | `services/employees-service/target/site/jacoco/jacoco.xml` | `recordCoverage`, parser JACOCO | Coverage y su tendencia |
| Cobertura TypeScript | `services/vacations-service/reports/lcov.info` | `recordCoverage`, parser LCOV | Coverage y su tendencia |
| Empaquetado | `reports/docker/index.html` y `build.log` | `publishHTML`, `archiveArtifacts` | Empaquetado Docker |
| Escenarios BDD | `e2e-tests/reports/junit.xml` y `cucumber.json` | `junit` y `cucumber` | Test Result y Cucumber Reports |
| HTML de Cucumber | `e2e-tests/reports/cucumber.html` | `publishHTML` | Escenarios BDD en español |
| Logs del sistema de pruebas | `reports/e2e/system.log` | `archiveArtifacts` | Artefactos del build |

Los resultados se publican desde `post { always { ... } }`. Las pruebas Java
terminan y generan cobertura aunque alguna aserción falle; después `junit`
registra el detalle y el pipeline se detiene. En vacaciones el comando conserva
su código de salida de error y el bloque `post` publica lo que se haya generado.
Una suite que no genera su reporte tampoco se considera aprobada.

**Verde** significa que la etapa terminó correctamente. **Rojo** significa fallo.
**Gris** indica una etapa que no se ejecutó. Jenkins usa **UNSTABLE**, amarillo,
cuando `junit` encuentra pruebas fallidas; en estos pipelines ese estado se
convierte en **FAILURE** para impedir el empaquetado de un servicio con fallos.

Las siete pruebas Java de integración usan PostgreSQL real con Testcontainers
1.21.4, actualizado para admitir Docker Engine 29.
En este entorno Linux, el agente Maven usa la red del host y la dirección
`127.0.0.1` para alcanzar los puertos temporales de Testcontainers sin depender
de reglas de acceso desde el bridge de Docker. En Docker Desktop se necesita
habilitar el soporte de host networking o adaptar esa conexión.
En CI se revisa su XML para impedir que una falta de Docker las omita y deje un
build aparentemente correcto. El umbral local de JaCoCo se conserva. Solo en CI
se desactiva esa comprobación local, para que SonarQube decida en Quality Gate.
Vacaciones tiene `test:ci` con la misma separación de responsabilidades.

El analizador TypeScript incluido en SonarQube usa `tsconfig.sonar.json`.
La compilación del servicio conserva su `tsconfig.json` de TypeScript 5.9. La
configuración separada evita que opciones recientes del compilador hagan que el
analizador omita los archivos aunque el comando del scanner termine en cero.

El alcance unitario de cobertura de vacaciones son `src/domain` y `src/services`.
Los controladores, rutas, adaptadores, configuración y arranque siguen incluidos
en el análisis estático, pero quedan fuera del porcentaje de cobertura unitaria.
Se verifican funcionalmente al ejecutar el sistema en E2E. En Java se mantienen
las exclusiones de JaCoCo del reto 6, también en SonarQube. La métrica de SonarQube
combina líneas y condiciones, por lo que puede diferir de la cobertura de líneas
mostrada por el runtime. No se deben presentar estos porcentajes como cobertura
de todo el sistema.

La puerta de calidad `System HR - 70%` exige cobertura global del proyecto analizado
mayor o igual al 70 %, incluso en el primer análisis. SonarQube también muestra
bugs, vulnerabilidades, duplicación y mantenibilidad. Esta puerta usa el umbral
de cobertura del reto; no añade umbrales de esas otras métricas.

## 5. Aislamiento y limpieza de E2E

Cada build utiliza un proyecto Compose llamado `hr-ci-<servicio>-<build>`.
`ci/crear-compose-e2e.py` parte de la configuración real, elimina los puertos
públicos y los nombres fijos de redes y volúmenes, y quita los servicios de CI.
El servicio evaluado usa la misma imagen creada en Package; se construyen sus
dependencias y la imagen de BDD para ejecutar el flujo completo.

RabbitMQ recibe sus definiciones dentro de una imagen de pruebas. Los reportes
BDD se copian del contenedor al workspace: no se necesitan carpetas del host
montadas desde un workspace que vive dentro de Jenkins.

La limpieza guarda los logs y ejecuta `down -v` únicamente sobre ese proyecto
aislado. El JSON temporal contiene claves de prueba, se elimina y no se archiva.
Las bases de datos habituales y los volúmenes de Jenkins/SonarQube quedan fuera
del proyecto E2E. No ejecutes `docker compose down -v` sobre tu entorno habitual
para limpiar las pruebas.

Para el entorno académico, Jenkins accede al socket de Docker del host. El cliente incluye Buildx y Compose. El
entrypoint asigna al usuario Jenkins el grupo real del socket sin cambiar sus
permisos. Este acceso permite administrar Docker del host: úsalo solo con código
y usuarios de confianza. Las interfaces web se publican en `127.0.0.1`.

## 6. Evidencias del punto 5

Ejecuta cada pipeline tres veces consecutivas con `FALLO_DEMO=ninguno` y registra
los números de build. Eso permite comprobar estabilidad y mostrar tendencias.
Luego ejecuta los cuatro casos de la tabla desde **Construir con parámetros**:

| FALLO_DEMO | Cambio temporal | Etapa que debe fallar | Evidencia |
|---|---|---|---|
| `unitaria` | Añade una prueba que falla de forma deliberada | Test | Nombre y mensaje en Test Result |
| `cobertura` | Añade código sin pruebas, sin modificar el XML/LCOV | Quality Gate | Cobertura menor al 70 % en SonarQube y puerta rechazada |
| `docker` | Añade una instrucción inválida al Dockerfile del servicio | Package | Error de construcción en esa etapa |
| `bdd` | Añade un escenario que espera HTTP 599 al consultar la salud | E2E Tests | Escenario y paso fallido en Cucumber Reports |

Las simulaciones se aplican después del checkout y solo en el workspace de
Jenkins. No cambian el repositorio remoto. El siguiente build borra el workspace
y descarga el código limpio. Selecciona de nuevo `ninguno` después de la demo.

Guarda en `docs/evidencias/reto7/` capturas reales de:

1. Pipeline completo en verde, con número de build.
2. Test Result con los resultados unitarios.
3. Coverage con el porcentaje y su tendencia.
4. Reporte Cucumber con escenarios en español.
5. Build fallido con la prueba o escenario y su mensaje.
6. SonarQube con el Quality Gate rechazado por cobertura.

La tabla de `docs/evidencias/reto7/README.md` contiene las ejecuciones y las capturas
verificadas en esta entrega. Si repites la demostración, registra tus nuevos builds allí.
Las capturas y las tres ejecuciones no se sustituyen con archivos de ejemplo.

## 7. Decisiones y límites

Se eligió JCasC con Job DSL porque deja usuarios, servidor SonarQube y jobs en
archivos revisables. No hay que crear dos pipelines manualmente en la interfaz.
El aprovisionamiento inicial de SonarQube también es código y se puede repetir
sin crear proyectos ni webhooks duplicados. Se reutiliza el token mientras siga
siendo válido. Si lo revocas, vuelve a ejecutar sonar-setup y recrea Jenkins para
que cargue la nueva credencial.

No se incluyó un registry: el reto lo deja como opcional. Las imágenes permanecen
en el Docker local y se identifican por número de build. Docker y los plugins
se instalan automáticamente; las etiquetas LTS pueden cambiar entre fechas.
Para congelar la instalación de una entrega, registra versiones y digests de las
imágenes y versiones de plugins después de una ejecución validada.

Referencias utilizadas:

- [Agentes Docker y workspaces en Jenkins](https://www.jenkins.io/doc/book/pipeline/docker/)
- [Publicación de cobertura y parser LCOV](https://www.jenkins.io/doc/pipeline/steps/coverage/)
- [Reportes HTML y origen de recursos](https://www.jenkins.io/doc/book/security/user-content/)
- [Secretos de Configuration as Code](https://github.com/jenkinsci/configuration-as-code-plugin/blob/master/docs/features/secrets.adoc)
- [Configuración de SonarQube en Jenkins](https://docs.sonarsource.com/sonarqube-server/analyzing-source-code/ci-integration/jenkins-integration/global-setup)
- [Versiones de Testcontainers](https://github.com/testcontainers/testcontainers-java/releases)
- [Requisitos Linux de SonarQube](https://docs.sonarsource.com/sonarqube-server/2025.1/setup-and-upgrade/pre-installation/linux)
