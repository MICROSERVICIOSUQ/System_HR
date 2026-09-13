UPDATE "Departamento"
SET "descripcion" = 'Sin descripcion'
WHERE "descripcion" IS NULL;

ALTER TABLE "Departamento"
ALTER COLUMN "descripcion" SET NOT NULL;
