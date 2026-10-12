export class EmployeeServiceUnavailableError extends Error {
  constructor(cause?: unknown) {
    super('El servicio de empleados no esta disponible temporalmente.', { cause });
    this.name = 'EmployeeServiceUnavailableError';
  }
}

export class EmployeeClient {
  private readonly baseUrl = (process.env.EMPLOYEES_SERVICE_URL ?? 'http://localhost:8080').replace(/\/+$/, '');

  async hasEmployeesForDepartment(departmentId: string): Promise<boolean> {
    const url = `${this.baseUrl}/empleados?departamentoId=${encodeURIComponent(departmentId)}`;

    try {
      const response = await fetch(url, {
        headers: { Accept: 'application/json' },
        signal: AbortSignal.timeout(5000),
      });

      if (!response.ok) {
        throw new EmployeeServiceUnavailableError(`HTTP ${response.status}`);
      }

      const employees: unknown = await response.json();
      if (!Array.isArray(employees)) {
        throw new EmployeeServiceUnavailableError('La respuesta no contiene un listado de empleados');
      }

      return employees.length > 0;
    } catch (error) {
      if (error instanceof EmployeeServiceUnavailableError) {
        throw error;
      }
      throw new EmployeeServiceUnavailableError(error);
    }
  }
}
