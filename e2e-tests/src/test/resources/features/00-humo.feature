# language: es
Característica: Verificación del sistema
  Como responsable de la suite de pruebas
  Quiero confirmar que el ecosistema está desplegado y operativo
  Para no interpretar un sistema caído como una regresión funcional

  Si este escenario falla, no tiene sentido ejecutar los demás: el problema está
  en el despliegue, no en el comportamiento del sistema.

  Escenario: El sistema responde correctamente
    Dado que el sistema está desplegado y operativo
    Entonces la respuesta debe tener código 200

  Escenario: El administrador semilla puede autenticarse
    Cuando me autentico como administrador
    Entonces la respuesta debe tener código 200
    Y el token recibido debe tener el rol "ADMIN"
