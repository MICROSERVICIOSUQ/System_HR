# language: es
Característica: Onboarding de empleados
  Como administrador del sistema
  Quiero registrar nuevos empleados
  Para que reciban sus credenciales y puedan acceder al sistema

  El registro de un empleado desencadena una cadena asíncrona: employees-service
  publica "empleado.creado", auth-service crea la cuenta y publica
  "usuario.creado" con el token de activación, y notifications-service simula el
  correo. Los pasos que empiezan por "eventualmente" usan polling con reintentos,
  porque esos efectos no son inmediatos.

  Antecedentes:
    Dado que estoy autenticado como "ADMIN"
    Y que existe un departamento para las pruebas

  Escenario: Registro exitoso de un empleado
    Cuando registro un empleado nuevo
    Entonces la respuesta debe tener código 201
    Y el cuerpo debe contener los diez campos del modelo canónico
    Y el estado del empleado debe ser "ACTIVO"

  Escenario: El registro aprovisiona una cuenta y notifica el token de activación
    Cuando registro un empleado con datos válidos e ID único
    Entonces la respuesta debe tener código 201
    Y eventualmente el servicio de autenticación debe haber creado un usuario para ese correo
    Y eventualmente el servicio de notificaciones debe registrar el correo de bienvenida con su token de activación
    Y el token de activación recibido no debe ser una contraseña en claro

  Escenario: El empleado establece su contraseña y realiza su primer login
    Dado que registro un empleado nuevo
    Y que se generó un token de activación para el nuevo empleado
    Cuando el empleado establece su nueva contraseña utilizando el token de activación
    Entonces la respuesta debe tener código 200
    Y eventualmente el empleado puede iniciar sesión exitosamente con su nueva contraseña
    Y el token recibido debe tener el rol "USER"

  # El mensaje es genérico a propósito: la cuenta existe pero todavía no tiene
  # contraseña, y auth-service verifica las credenciales antes de mirar el estado.
  # Responder "la cuenta no está activada" aquí revelaría a un desconocido que el
  # correo está registrado en la empresa.
  Escenario: Antes de activar la cuenta el empleado no puede iniciar sesión
    Dado que registro un empleado nuevo
    Cuando el empleado intenta iniciar sesión
    Entonces la respuesta debe tener código 401
    Y el mensaje de error debe contener "Credenciales inválidas"

  Escenario: El empleado recién registrado aparece en la consulta por identificador
    Dado que registro un empleado nuevo
    Cuando consulto el empleado por su identificador
    Entonces la respuesta debe tener código 200
    Y el estado del empleado debe ser "ACTIVO"

  Escenario: Rechazo de un registro con departamento inexistente
    Cuando registro un empleado en el departamento "NO-EXISTE"
    Entonces la respuesta debe tener código 400
    Y el mensaje de error debe contener "El departamento con id NO-EXISTE no existe"

  Escenario: Rechazo de un registro con campos obligatorios faltantes
    Cuando registro un empleado sin los campos obligatorios
    Entonces la respuesta debe tener código 400
    Y el mensaje de error debe contener "campos inválidos"

  Escenario: Rechazo de un registro con email duplicado
    Dado que registro un empleado nuevo
    Cuando registro otro empleado con el mismo email
    Entonces la respuesta debe tener código 400
    Y el mensaje de error debe contener "ya está registrado"

  Escenario: Rechazo de un registro con número de empleado duplicado
    Dado que registro un empleado nuevo
    Cuando registro otro empleado con el mismo número de empleado
    Entonces la respuesta debe tener código 400
    Y el mensaje de error debe contener "ya está registrado"

  Esquema del escenario: Rechazo de registros con un email de formato inválido
    Cuando registro un empleado con el email "<email>"
    Entonces la respuesta debe tener código 400

    Ejemplos:
      | email              |
      | sin-arroba         |
      | @sin-usuario.com   |
      | espacio en@med.io  |
