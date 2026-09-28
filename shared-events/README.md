# Contratos de Eventos Compartidos — System_HR (`shared-events`)

## Propósito

El directorio `shared-events` actúa como el **repositorio oficial de los contratos de eventos** del ecosistema **System_HR**. 

En una arquitectura guiada por eventos (Event-Driven Architecture), los contratos compartidos formalizan la estructura, versionamiento y semántica de los mensajes que viajan a través del bus de mensajería (RabbitMQ), garantizando interoperabilidad y estabilidad entre productores y consumidores.

---

## Contenido

El repositorio de contratos está organizado de la siguiente manera:

* **`catalog/`** → Ejemplos JSON representativos de cada evento de dominio (`empleado.creado`, `empleado.actualizado`, `empleado.retirado`).
* **`schemas/`** → JSON Schemas oficiales que validan la estructura sintáctica del sobre (Event Envelope) y el payload (`data`).
* **`docs/`** → Catálogo de eventos con documentación técnica detallada de atributos, tipos, productores y consumidores.

---

## Regla Arquitectónica

> [!IMPORTANT]
> **Ningún microservicio importa código desde `shared-events`.** Este directorio funciona únicamente como **documentación y contrato compartido entre equipos** de desarrollo. Cada microservicio mantiene su propia implementación interna de modelos y serializadores/deserializadores, evitando el acoplamiento binario o dependencias compartidas en tiempo de compilación.
