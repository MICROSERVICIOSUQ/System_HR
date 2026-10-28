# language: es
Característica: Seguridad y control de acceso
  Como sistema de autenticación
  Quiero controlar el acceso a los recursos
  Para garantizar que solo los usuarios autorizados realicen operaciones

  Estos escenarios son completamente sincrónicos: cada petición recibe su
  respuesta de inmediato y no hay eventos que esperar.

  Escenario: Acceso denegado sin token de autenticación
    Cuando consulto la lista de empleados sin token de autenticación
    Entonces la respuesta debe tener código 401

  Escenario: Acceso denegado con un token malformado
    Cuando consulto la lista de empleados con el token "esto-no-es-un-jwt"
    Entonces la respuesta debe tener código 401

  Escenario: Acceso denegado con un token cuya firma fue alterada
    Cuando consulto la lista de empleados con un token de firma falsificada
    Entonces la respuesta debe tener código 401
    Y el mensaje de error debe contener "inválido"

  Escenario: Las cabeceras de identidad enviadas por el cliente no otorgan acceso
    Cuando consulto la lista de empleados enviando las cabeceras de identidad "admin" y "ADMIN"
    Entonces la respuesta debe tener código 401

  Escenario: Un token de restablecimiento no sirve como token de acceso
    Dado que estoy autenticado como "ADMIN"
    Y que existe un departamento para las pruebas
    Y que registro un empleado nuevo
    Y que obtengo su token de activación
    Cuando consulto la lista de empleados con el token de activación
    Entonces la respuesta debe tener código 401

  Regla: El rol USER tiene acceso de solo lectura

    Antecedentes:
      Dado que estoy autenticado como "ADMIN"
      Y que existe un departamento para las pruebas
      Y que registro un empleado nuevo
      Y que el empleado activa su cuenta y queda autenticado

    Escenario: Un USER puede consultar la lista de empleados
      Cuando consulto la lista de empleados
      Entonces la respuesta debe tener código 200

    Escenario: Un USER puede consultar la lista de departamentos
      Cuando consulto la lista de departamentos
      Entonces la respuesta debe tener código 200

    Escenario: Un USER no puede eliminar un empleado
      Cuando intento retirar al empleado
      Entonces la respuesta debe tener código 403

    Escenario: Un USER no puede crear un departamento
      Cuando intento crear el departamento "PROHIBIDO"
      Entonces la respuesta debe tener código 403

    Escenario: Un USER puede actualizar su propio perfil
      Cuando actualizo el perfil del empleado con el cargo "Analista Senior"
      Entonces la respuesta debe tener código 200

    Escenario: Un USER no puede actualizar el perfil de otro empleado
      Cuando actualizo el perfil de otro empleado con el cargo "Intruso"
      Entonces la respuesta debe tener código 403

  Regla: El rol ADMIN tiene acceso total

    Antecedentes:
      Dado que estoy autenticado como "ADMIN"
      Y que existe un departamento para las pruebas

    Escenario: Un ADMIN puede crear un departamento
      Cuando intento crear el departamento "ADMIN-OK"
      Entonces la respuesta debe tener código 201

    Escenario: Un ADMIN puede consultar la lista de empleados
      Cuando consulto la lista de empleados
      Entonces la respuesta debe tener código 200

    Escenario: Un ADMIN puede registrar y retirar empleados
      Dado que registro un empleado nuevo
      Cuando intento retirar al empleado
      Entonces la respuesta debe tener código 200
