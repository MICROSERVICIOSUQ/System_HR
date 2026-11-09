// Se ejecuta dentro de vacations-service. Solo imprime pasos, estados e IDs.
// La cola temporal observa eventos sin quitar mensajes a los consumidores reales.
const amqp = require('amqplib');
const { randomUUID } = require('node:crypto');
const assert = require('node:assert/strict');

const base = process.env.DEMO_BASE_URL || 'http://api-gateway:8080';
const employeeId = `DEMO-${randomUUID().slice(0, 8)}`;
const email = `${employeeId.toLowerCase()}@example.test`;
const preparePostman = process.env.DEMO_PREPARAR_POSTMAN === '1';
const employeePassword = process.env.DEMO_EMPLOYEE_PASSWORD || `Demo1-${randomUUID()}`;
let adminToken;

async function request(path, method = 'GET', data, authenticated = true) {
  const response = await fetch(base + path, {
    method, headers: {
      'Content-Type': 'application/json',
      ...(authenticated ? { Authorization: `Bearer ${adminToken}` } : {}),
    },
    body: data ? JSON.stringify(data) : undefined,
  });
  return { status: response.status, data: await response.json() };
}
async function eventually(check, message) {
  const deadline = Date.now() + 30000;
  while (Date.now() < deadline) {
    const result = await check();
    if (result) return result;
    await new Promise(resolve => setTimeout(resolve, 200));
  }
  throw new Error(message);
}
const day = offset => {
  const date = new Date(); date.setUTCDate(date.getUTCDate() + offset);
  return date.toISOString().slice(0, 10);
};
const loginEmployee = () => request('/auth/login', 'POST', { usuario: email, password: employeePassword }, false);

async function main() {
  if (!process.env.DEMO_ADMIN_USER || !process.env.DEMO_ADMIN_PASSWORD) {
    throw new Error('Defina DEMO_ADMIN_USER y DEMO_ADMIN_PASSWORD; consulte la guía.');
  }
  if (preparePostman && !process.env.DEMO_EMPLOYEE_PASSWORD) {
    throw new Error('Para Postman indique DEMO_EMPLOYEE_PASSWORD; no se imprime ni se guarda.');
  }
  const login = await request('/auth/login', 'POST', {
    usuario: process.env.DEMO_ADMIN_USER, password: process.env.DEMO_ADMIN_PASSWORD,
  }, false);
  assert.equal(login.status, 200, 'No se pudo autenticar el administrador');
  adminToken = login.data.token;
  console.log('1. Login ADMIN: 200 (token conservado únicamente en memoria).');

  const url = new URL(`amqp://${process.env.RABBITMQ_HOST || 'rabbitmq'}:${process.env.RABBITMQ_PORT || 5672}`);
  url.username = process.env.RABBITMQ_USER; url.password = process.env.RABBITMQ_PASSWORD;
  const connection = await amqp.connect(process.env.RABBITMQ_URL || url.toString());
  const channel = await connection.createChannel();
  const events = [];
  try {
    const queue = await channel.assertQueue('', { exclusive: true });
    await channel.bindQueue(queue.queue, 'hr.events', '');
    await channel.consume(queue.queue, message => {
      if (!message) return;
      events.push(JSON.parse(message.content.toString()));
      channel.ack(message);
    });
    const department = await request('/departamentos', 'POST', {
      id: `D-${randomUUID().slice(0, 8)}`, nombre: `Demo ${employeeId}`, descripcion: 'Datos de sustentación',
    });
    assert.equal(department.status, 201, 'No se pudo crear departamento');
    const departmentId = department.data.id ?? department.data.data?.id;
    assert.ok(departmentId, 'La respuesta de Departamentos no contiene id');
    const employee = await request('/empleados', 'POST', {
      id: employeeId, nombre: 'Ana', apellido: 'Demostración', email,
      numeroEmpleado: employeeId, cargo: 'Analista', area: 'Tecnología',
      departamentoId: departmentId, fechaIngreso: day(0),
    });
    assert.equal(employee.status, 201, 'No se pudo crear empleado');
    const activation = await eventually(() => events.find(event => event.type === 'usuario.creado' && event.data?.id === employeeId), 'No llegó usuario.creado');
    const reset = await request('/auth/reset-password', 'POST', {
      token: activation.data.token, nuevaPassword: employeePassword,
    }, false);
    assert.equal(reset.status, 200, 'No se pudo activar la cuenta');
    await eventually(async () => (await loginEmployee()).status === 200, 'La cuenta no quedó activa');
    console.log(`2. Onboarding y activación: correctos. Empleado ${employeeId}.`);

    if (preparePostman) {
      await eventually(async () => (await request(`/vacaciones/balances/${employeeId}`)).status === 200,
        'El empleado no llegó a la réplica de Vacaciones');
      console.log('Datos públicos para el entorno de Postman:');
      console.log(JSON.stringify({ dev3EmpleadoId: employeeId, dev3EmpleadoEmail: email, dev3DepartamentoId: departmentId }));
      console.log('Cuenta ACTIVA, sin vacaciones y sin retiro. Use la contraseña que eligió.');
      return;
    }

    const invalid = await request('/vacaciones', 'POST', {
      empleadoId: employeeId, fechaInicio: day(5), fechaFin: day(5),
    });
    assert.equal(invalid.status, 400, 'Debía rechazar fechas iguales');
    const dto = { empleadoId: employeeId, fechaInicio: day(5), fechaFin: day(7) };
    const period = await eventually(async () => {
      const result = await request('/vacaciones', 'POST', dto);
      if (result.status === 404) return null; // Esperar a la réplica por eventos.
      assert.equal(result.status, 201, 'No se pudo programar vacaciones');
      return result.data;
    }, 'El empleado no llegó a Vacaciones');
    assert.equal((await request('/vacaciones', 'POST', dto)).status, 400, 'Debía rechazar solapamiento');
    console.log(`3. Período ${period.id}: creado. Fechas iguales y solapamiento: 400.`);

    assert.equal((await request(`/vacaciones/${period.id}/forzar-inicio`, 'POST')).status, 200);
    await eventually(async () => (await loginEmployee()).status === 401, 'La cuenta no se suspendió');
    console.log('4. Inicio manual de demostración: EN_CURSO y login del empleado 401.');
    assert.equal((await request(`/vacaciones/${period.id}/forzar-fin`, 'POST')).status, 200);
    await eventually(async () => (await loginEmployee()).status === 200, 'La cuenta no se reactivó');
    console.log('5. Fin manual de demostración: FINALIZADA y login del empleado 200.');

    const history = await eventually(async () => {
      const result = await request(`/notifications/empleado/${employeeId}`);
      assert.equal(result.status, 200, 'No se pudo consultar el historial');
      const types = new Set(result.data.map(item => item.eventType));
      return ['usuario.creado', 'vacaciones.programadas', 'cuenta.desactivada', 'cuenta.activada'].every(type => types.has(type)) ? result.data : null;
    }, 'Faltan notificaciones del flujo');
    const historyText = JSON.stringify(history);
    assert.ok(!historyText.includes(activation.data.token), 'El historial filtró el token');
    assert.ok(!historyText.includes(employeePassword), 'El historial filtró la contraseña');
    console.log('6. Historial consultable: bienvenida, vacaciones y cambios de cuenta; sin credenciales.');

    assert.equal((await request(`/empleados/${employeeId}`, 'DELETE')).status, 200);
    await eventually(async () => (await loginEmployee()).status === 401, 'No se desactivó por retiro');
    await eventually(async () => {
      const result = await request('/vacaciones', 'POST', { ...dto, fechaInicio: day(15), fechaFin: day(17) });
      return result.status === 400;
    }, 'Vacaciones no rechazó al empleado retirado');
    console.log('7. Retiro: login 401 y nueva solicitud de vacaciones 400.');
    console.log(`Demostración terminada. Se conservan los datos del empleado ${employeeId} para revisarlos.`);
  } finally {
    await channel.close(); await connection.close();
  }
}
main().catch(error => { console.error(`Demostración detenida: ${error.message}`); process.exitCode = 1; });
