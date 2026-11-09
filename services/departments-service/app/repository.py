"""Acceso a datos de departamentos.

Mantiene dos comportamientos de la versión anterior que no son obvios y que algún
cliente podría estar dando por hechos:

1. **El id se puede generar.** Si el POST no trae `id`, se obtiene de la secuencia
   `departments_id_seq` de PostgreSQL con el formato `D001`, `D002`... La
   secuencia la crearon las migraciones de Prisma y se sigue usando: generar el
   id en la aplicación (con un contador o un UUID) habría cambiado el formato de
   los identificadores y roto las referencias existentes.

2. **El listado sin paginar va ordenado por `createdAt` descendente.** Es el
   orden que devolvía Prisma y el que espera quien consuma la lista sin
   parámetros.
"""

from __future__ import annotations

from sqlalchemy import func, select, text
from sqlalchemy.orm import Session

from app.models import Departamento

CAMPOS_ORDENABLES = ("id", "nombre", "descripcion", "createdAt", "updatedAt")


class RepositorioDeDepartamentos:
    def __init__(self, sesion: Session) -> None:
        self.sesion = sesion

    def listar(self) -> list[Departamento]:
        consulta = select(Departamento).order_by(Departamento.createdAt.desc())
        return list(self.sesion.execute(consulta).scalars())

    def listar_pagina(
        self, pagina: int, limite: int, ordenar_por: str, orden: str
    ) -> tuple[int, list[Departamento]]:
        total = self.sesion.execute(
            select(func.count()).select_from(Departamento)
        ).scalar_one()

        columna = getattr(Departamento, ordenar_por)
        criterio = columna.asc() if orden == "asc" else columna.desc()

        consulta = (
            select(Departamento)
            .order_by(criterio)
            .offset((pagina - 1) * limite)
            .limit(limite)
        )
        return total, list(self.sesion.execute(consulta).scalars())

    def buscar_por_id(self, id_departamento: str) -> Departamento | None:
        return self.sesion.get(Departamento, id_departamento)

    def siguiente_id_generado(self) -> str:
        """Siguiente identificador de la secuencia, con formato D001.

        Se delega en PostgreSQL y no se calcula en Python a propósito: `nextval`
        es atómico, así que dos peticiones simultáneas nunca obtienen el mismo
        número. Un `SELECT MAX(id) + 1` en la aplicación sí podría dárselo, y el
        fallo aparecería solo bajo concurrencia.
        """
        fila = self.sesion.execute(
            text("SELECT 'D' || LPAD(nextval('departments_id_seq')::text, 3, '0') AS id")
        ).one()
        return fila.id

    def crear(self, id_departamento: str, nombre: str, descripcion: str) -> Departamento:
        departamento = Departamento(
            id=id_departamento, nombre=nombre, descripcion=descripcion
        )
        self.sesion.add(departamento)
        self.sesion.commit()
        self.sesion.refresh(departamento)
        return departamento

    def actualizar(
        self,
        departamento: Departamento,
        nombre: str | None,
        descripcion: str | None,
    ) -> Departamento:
        if nombre is not None:
            departamento.nombre = nombre
        if descripcion is not None:
            departamento.descripcion = descripcion
        self.sesion.commit()
        self.sesion.refresh(departamento)
        return departamento

    def eliminar(self, departamento: Departamento) -> None:
        """Borrado FÍSICO, a diferencia del retiro de empleados.

        Un empleado se da de baja lógicamente porque su historial laboral debe
        conservarse para auditoría. Un departamento no tiene historial que
        preservar: o existe en el organigrama o no. La protección que importa aquí
        es otra —no borrarlo si tiene empleados asociados— y la aplica el servicio
        antes de llegar a este método.
        """
        self.sesion.delete(departamento)
        self.sesion.commit()
