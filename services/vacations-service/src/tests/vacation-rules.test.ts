import { describe, test } from 'node:test';
import assert from 'node:assert/strict';
import {
  aDiaUtc,
  finNoEsPosteriorAInicio,
  inicioEsPasado,
  periodosSolapados,
  rangosSeSolapan,
} from '../domain/vacation-rules.ts';

/**
 * Pruebas unitarias de las reglas de dominio de vacaciones.
 *
 * Son lógica pura: no levantan Docker, no abren conexiones y no tocan Prisma.
 * El solapamiento se verifica como tabla de casos porque es donde están los
 * errores de verdad: los extremos que se tocan por un solo día.
 */

const d = (iso: string) => new Date(`${iso}T00:00:00.000Z`);

describe('aDiaUtc — normalización a día completo', () => {
  test('descarta la hora, de modo que dos instantes del mismo día son iguales', () => {
    const manana = new Date('2026-11-10T08:30:00.000Z');
    const noche = new Date('2026-11-10T23:59:59.999Z');
    assert.equal(aDiaUtc(manana).getTime(), aDiaUtc(noche).getTime());
  });

  test('no muta la fecha recibida', () => {
    const original = new Date('2026-11-10T08:30:00.000Z');
    const copia = original.getTime();
    aDiaUtc(original);
    assert.equal(original.getTime(), copia);
  });
});

describe('finNoEsPosteriorAInicio — validación 1', () => {
  const casos: Array<[string, string, string, boolean]> = [
    ['fin posterior al inicio',          '2026-12-01', '2026-12-05', false],
    ['fin igual al inicio (inválido)',   '2026-12-01', '2026-12-01', true],
    ['fin un día antes del inicio',      '2026-12-02', '2026-12-01', true],
    ['fin un mes antes del inicio',      '2026-12-01', '2026-11-01', true],
  ];

  for (const [nombre, inicio, fin, esperado] of casos) {
    test(`${nombre} → ${esperado}`, () => {
      assert.equal(finNoEsPosteriorAInicio(d(inicio), d(fin)), esperado);
    });
  }

  test('un día de vacaciones termina al día siguiente', () => {
    assert.equal(finNoEsPosteriorAInicio(d('2026-12-01'), d('2026-12-02')), false);
  });
});

describe('inicioEsPasado — validación 2', () => {
  const hoy = d('2026-11-10');

  const casos: Array<[string, string, boolean]> = [
    ['inicio mañana',        '2026-11-11', false],
    ['inicio hoy',           '2026-11-10', false],
    ['inicio ayer',          '2026-11-09', true],
    ['inicio el año pasado', '2025-11-10', true],
  ];

  for (const [nombre, inicio, esperado] of casos) {
    test(`${nombre} → ${esperado}`, () => {
      assert.equal(inicioEsPasado(d(inicio), hoy), esperado);
    });
  }

  test('hoy no es pasado aunque la hora de referencia sea avanzada', () => {
    const hoyPorLaNoche = new Date('2026-11-10T23:00:00.000Z');
    assert.equal(inicioEsPasado(d('2026-11-10'), hoyPorLaNoche), false);
  });
});

describe('rangosSeSolapan — validación 4, tabla de casos borde', () => {
  // Período existente de referencia: del 10 al 20 de noviembre, con regreso el día 20.
  const EXISTE_INI = '2026-11-10';
  const EXISTE_FIN = '2026-11-20';

  const casos: Array<[string, string, string, boolean]> = [
    // Sin contacto
    ['termina mucho antes',                        '2026-11-01', '2026-11-05', false],
    ['empieza mucho después',                      '2026-11-25', '2026-11-30', false],
    ['termina justo el día antes',                 '2026-11-05', '2026-11-09', false],
    ['empieza justo el día después',               '2026-11-21', '2026-11-25', false],

    // Los extremos se pueden tocar: fechaFin es regreso
    ['termina exactamente el día en que empieza',  '2026-11-05', '2026-11-10', false],
    ['empieza exactamente el día en que termina',  '2026-11-20', '2026-11-25', false],

    // Intersecciones parciales
    ['entra por la izquierda',                     '2026-11-05', '2026-11-15', true],
    ['entra por la derecha',                       '2026-11-15', '2026-11-25', true],

    // Contención
    ['queda dentro del existente',                 '2026-11-12', '2026-11-18', true],
    ['contiene al existente',                      '2026-11-01', '2026-11-30', true],
    ['es idéntico al existente',                   '2026-11-10', '2026-11-20', true],

    // Un solo día
    ['un día dentro del existente',                '2026-11-15', '2026-11-15', false],
    ['un día igual al inicio del existente',       '2026-11-10', '2026-11-10', false],
    ['un día igual al fin del existente',          '2026-11-20', '2026-11-20', false],
    ['un día justo antes del existente',           '2026-11-09', '2026-11-09', false],
    ['un día justo después del existente',         '2026-11-21', '2026-11-21', false],
  ];

  for (const [nombre, inicio, fin, esperado] of casos) {
    test(`${nombre} (${inicio} → ${fin}) → ${esperado}`, () => {
      assert.equal(
        rangosSeSolapan(d(inicio), d(fin), d(EXISTE_INI), d(EXISTE_FIN)),
        esperado
      );
    });
  }

  test('el predicado es simétrico: el orden de los rangos no cambia el resultado', () => {
    for (const [, inicio, fin, esperado] of casos) {
      assert.equal(
        rangosSeSolapan(d(EXISTE_INI), d(EXISTE_FIN), d(inicio), d(fin)),
        esperado,
        `falló invertido para ${inicio} → ${fin}`
      );
    }
  });

  test('ignora la hora: un rango con hora solapa igual que sin ella', () => {
    const conHora = new Date('2026-11-19T18:45:00.000Z');
    assert.equal(rangosSeSolapan(conHora, d(EXISTE_FIN), d(EXISTE_INI), d(EXISTE_FIN)), true);
  });
});

describe('periodosSolapados — filtrado sobre varios períodos existentes', () => {
  const existentes = [
    { id: 'p1', fechaInicio: d('2026-11-01'), fechaFin: d('2026-11-05'), estado: 'PROGRAMADA' },
    { id: 'p2', fechaInicio: d('2026-11-10'), fechaFin: d('2026-11-20'), estado: 'EN_CURSO' },
    { id: 'p3', fechaInicio: d('2026-12-01'), fechaFin: d('2026-12-10'), estado: 'PROGRAMADA' },
  ];

  test('sin conflicto devuelve lista vacía', () => {
    const r = periodosSolapados(d('2026-11-06'), d('2026-11-09'), existentes);
    assert.deepEqual(r, []);
  });

  test('devuelve el único período en conflicto', () => {
    const r = periodosSolapados(d('2026-11-15'), d('2026-11-18'), existentes);
    assert.deepEqual(r.map((p) => p.id), ['p2']);
  });

  test('devuelve TODOS los períodos en conflicto, no solo el primero', () => {
    const r = periodosSolapados(d('2026-11-01'), d('2026-12-31'), existentes);
    assert.deepEqual(r.map((p) => p.id), ['p1', 'p2', 'p3']);
  });

  test('una lista vacía de existentes nunca produce conflicto', () => {
    assert.deepEqual(periodosSolapados(d('2026-11-15'), d('2026-11-18'), []), []);
  });
});
