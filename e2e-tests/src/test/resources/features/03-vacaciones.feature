# language: es
Característica: Gestión de vacaciones y suspensión de la cuenta
  Como administrador de recursos humanos
  Quiero programar los períodos de vacaciones del personal
  Para que la cuenta de acceso se suspenda mientras el empleado está ausente
  Y se reactive sola cuando regrese

  El ciclo completo combina tres esperas distintas: la respuesta sincrónica del
  POST, la propagación del evento por el broker, y el paso del tiempo hasta que
  el período arranca. Para no acoplar la prueba a la frecuencia del scheduler se
  usan los endpoints de disparo manual, que existen precisamente para esto y
  están protegidos con rol ADMIN.

  Antecedentes:
    Dado que estoy autenticado como "ADMIN"
    Y que existe un departamento para las pruebas
    Y que existe un empleado activo con credenciales establecidas

  Escenario: Programación exitosa de un período de vacaciones
    Cuando programo un período de vacaciones de 5 días que empieza mañana
    Entonces la respuesta debe tener código 201
    Y el período debe quedar en estado "PROGRAMADA"

  # La notificación al empleado no la produce la programación en sí, sino el
  # inicio del período: vacations-service publica "vacaciones.iniciadas",
  # auth-service suspende la cuenta y publica "cuenta.desactivada", y es ese
  # evento el que notifications-service convierte en correo. El evento
  # "vacaciones.programadas" existe, pero su payload identifica al período y no
  # al empleado, así que no alimenta una notificación personal.
  Escenario: La programación produce una notificación asíncrona al iniciar el período
    Dado que programo un período de vacaciones que inicia de inmediato
    Cuando el período de vacaciones inicia
    Entonces eventualmente debe existir una notificación "cuenta.desactivada" para el empleado
    Y el motivo de la desactivación debe ser "VACACIONES"

  Escenario: Rechazo por fechas incoherentes
    Cuando programo un período de vacaciones cuya fecha de fin es anterior a la de inicio
    Entonces la respuesta debe tener código 400
    Y el mensaje de error debe contener "no puede ser anterior a fechaInicio"

  Escenario: Rechazo por fecha de inicio en el pasado
    Cuando programo un período de vacaciones que empezó la semana pasada
    Entonces la respuesta debe tener código 400
    Y el mensaje de error debe contener "no puede ser una fecha pasada"

  Escenario: Rechazo por solapamiento con un período existente
    Dado que programo un período de vacaciones de 5 días que empieza mañana
    Cuando programo otro período que se solapa con el anterior
    Entonces la respuesta debe tener código 400
    Y el mensaje de error debe contener "se solapa con uno existente"

  Escenario: Un período de un solo día es válido
    Cuando programo un período de vacaciones de 1 día que empieza mañana
    Entonces la respuesta debe tener código 201

  Escenario: La cuenta se suspende al iniciar las vacaciones y se reactiva al terminar
    Dado que el empleado puede iniciar sesión
    Y que programo un período de vacaciones que inicia de inmediato
    Cuando el período de vacaciones inicia
    Entonces eventualmente el empleado no puede iniciar sesión
    Y el mensaje de error debe contener "vacaciones"
    Y eventualmente debe existir una notificación "cuenta.desactivada" para el empleado
    Y el motivo de la desactivación debe ser "VACACIONES"
    Cuando el período de vacaciones finaliza
    Entonces eventualmente el empleado puede iniciar sesión de nuevo
    Y eventualmente debe existir una notificación "cuenta.activada" para el empleado
