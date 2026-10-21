# language: es
Característica: Offboarding de empleados
  Como administrador del sistema
  Quiero desvincular empleados
  Para que pierdan el acceso de forma inmediata y definitiva
  Pero su historial se conserve para auditoría

  La baja es lógica, no física: el registro permanece con su fechaRetiro. Lo que
  desaparece es la capacidad de entrar al sistema.

  Antecedentes:
    Dado que estoy autenticado como "ADMIN"
    Y que existe un departamento para las pruebas
    Y que existe un empleado activo con credenciales establecidas

  Escenario: Desvinculación completa de un empleado
    Dado que el empleado puede iniciar sesión
    Cuando retiro al empleado
    Entonces la respuesta debe tener código 200
    Y eventualmente debe existir una notificación "cuenta.desactivada" para el empleado
    Y el motivo de la desactivación debe ser "RETIRO"

  Escenario: El empleado retirado no puede iniciar sesión
    Dado que el empleado puede iniciar sesión
    Y que retiro al empleado
    Entonces eventualmente el empleado no puede iniciar sesión
    Y el mensaje de error debe contener "permanente"

  Escenario: La recuperación de contraseña falla para un empleado retirado
    Dado que retiro al empleado
    Cuando eventualmente el empleado solicita recuperar su contraseña y es rechazado
    Entonces el mensaje de error debe contener "permanente"

  Escenario: El empleado retirado aparece en la consulta de auditoría
    Dado que retiro al empleado
    Cuando consulto la auditoría de empleados retirados
    Entonces la respuesta debe tener código 200
    Y el empleado debe figurar en la auditoría con su fecha de retiro

  Escenario: El retiro conserva los datos del empleado
    Dado que retiro al empleado
    Cuando consulto el empleado por su identificador
    Entonces la respuesta debe tener código 200
    Y el estado del empleado debe ser "RETIRADO"

  Escenario: Caso borde — un empleado retirado durante sus vacaciones no recupera el acceso
    Dado que el empleado puede iniciar sesión
    Y que programo un período de vacaciones que inicia de inmediato
    Y que el período de vacaciones inicia
    Y que eventualmente el empleado no puede iniciar sesión
    Cuando retiro al empleado
    Y el período de vacaciones finaliza
    Entonces eventualmente el empleado no puede iniciar sesión
    Y el mensaje de error debe contener "permanente"
